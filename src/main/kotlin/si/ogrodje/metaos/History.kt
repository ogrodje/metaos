package si.ogrodje.metaos

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** One field of a change, as people read it; from and to are null where there's nothing short to show, e.g. a summary */
data class FieldChangeDto(val field: String, val from: String?, val to: String?)

/** What a change is to, so the front-end can tell changes apart without reading their words, e.g. to group them */
enum class ChangeKind { EPISODE, SESSION, PERSON, DOCUMENT, KOOFR_FOLDER, DROPBOX_FOLDER, COMMENT, ACCOUNT, MEDIA, OTHER }

/** A change to an episode or session, or to what's linked to it, as the front-end shows it */
data class HistoryEntryDto(
    val at: OffsetDateTime,
    val actor: String?,
    val client: String?,
    val tool: String?,
    val kind: ChangeKind,
    // The comment a change is to, which the discussion may still show
    val commentId: UUID?,
    val summary: String,
    val fields: List<FieldChangeDto>,
    // What it's a change to, where the page it's on doesn't say: among the latest changes to everything
    val about: ChangeSubjectDto? = null,
)

/** The episode, session or person a change is to, by name, e.g. Poletna vročica, with its code */
data class ChangeSubjectDto(val kind: ChangeKind, val id: UUID, val name: String, val code: String?)

// Each table by what its changes are to; an episode moving in or out of a session is one to the episode
private fun kindOf(table: String) = when (table) {
    "episodes" -> ChangeKind.EPISODE
    "sessions" -> ChangeKind.SESSION
    "episode_people" -> ChangeKind.PERSON
    "episode_documents", "session_documents" -> ChangeKind.DOCUMENT
    "episode_koofr_folders", "session_koofr_folders" -> ChangeKind.KOOFR_FOLDER
    "episode_dropbox_folders", "session_dropbox_folders" -> ChangeKind.DROPBOX_FOLDER
    "comments" -> ChangeKind.COMMENT
    "accounts" -> ChangeKind.ACCOUNT
    "episode_media", "transcripts" -> ChangeKind.MEDIA
    "people" -> ChangeKind.PERSON
    else -> ChangeKind.OTHER
}

// The episode, session or person a change is to, by kind and ID; null for one no history tells
private fun AuditRow.subject(): Pair<ChangeKind, String>? {
    fun id(column: String) = (new[column] ?: old[column])?.toString()
    return when (table) {
        "episodes" -> ChangeKind.EPISODE to rowId.toString()
        "sessions" -> ChangeKind.SESSION to rowId.toString()
        "people" -> ChangeKind.PERSON to rowId.toString()
        "episode_people", "episode_documents", "episode_koofr_folders", "episode_dropbox_folders", "episode_media",
        "transcripts" -> id("episode_id")?.let { ChangeKind.EPISODE to it }
        "session_documents", "session_koofr_folders", "session_dropbox_folders" -> id("session_id")?.let { ChangeKind.SESSION to it }
        "comments" -> id("episode_id")?.let { ChangeKind.EPISODE to it } ?: id("session_id")?.let { ChangeKind.SESSION to it }
        "accounts" -> id("person_id")?.let { ChangeKind.PERSON to it }
        else -> null
    }
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

// The columns people care about, in the order they're shown, by their names
private val EPISODE_COLUMNS = linkedMapOf(
    "code" to "Code", "title" to "Title", "title_b" to "Title B", "title_c" to "Title C", "status" to "Status",
    "show_id" to "Show", "session_id" to "Session", "recommended_by" to "Recommended by",
    "recording_date" to "Recording date", "aired_date" to "Aired", "added_date" to "Added",
    "youtube_url" to "YouTube", "spotify_url" to "Spotify", "apple_podcasts_url" to "Apple Podcasts",
    "patreon_url" to "Patreon", "patreon_audio_url" to "Patreon (audio only)", "patreon_bonus_url" to "Patreon bonus",
    "patreon_bonus_audio_url" to "Patreon bonus (audio only)", "summary" to "Summary", "tiny_summary" to "Tiny summary", "machine_summary" to "AI summary",
    "thumbnail_image_id" to "Thumbnail", "thumbnail_b_image_id" to "Thumbnail B", "thumbnail_c_image_id" to "Thumbnail C",
    "web_image_id" to "Web picture", "cover_image_id" to "Cover",
)
private val SESSION_COLUMNS = linkedMapOf("name" to "Name", "date" to "Date", "location" to "Location", "address" to "Address")
// A person's, but for their email, which their page doesn't show either
private val PERSON_COLUMNS = linkedMapOf(
    "name" to "Name", "bio" to "Bio", "homepage_url" to "Website", "linkedin_url" to "LinkedIn", "twitter_url" to "X",
    "instagram_url" to "Instagram", "facebook_url" to "Facebook", "avatar_image_id" to "Avatar",
)

// Too long to show what they were and what they became
private val LONG_COLUMNS = setOf("summary", "machine_summary")
private val DATE_COLUMNS = setOf("recording_date", "aired_date", "added_date", "date")
// Pictures, by the names of the files they were made from
private val IMAGE_COLUMNS =
    setOf(
        "thumbnail_image_id", "thumbnail_b_image_id", "thumbnail_c_image_id", "web_image_id", "cover_image_id",
        "avatar_image_id",
    )

// How much of a comment the history shows
private const val EXCERPT_LENGTH = 100
private val WHITESPACE = Regex("\\s+")

// The start of a comment, on one line
private fun excerpt(body: Any?): String? = body?.toString()?.replace(WHITESPACE, " ")?.trim()
    ?.let { if (it.length > EXCERPT_LENGTH) it.take(EXCERPT_LENGTH).trimEnd() + "…" else it }

/**
 * The history of an episode or a session, from the audit log (AuditLog): each change told as what happened, with
 * people, shows and sessions by name rather than by ID.
 */
@Component
class History(private val auditLog: AuditLog, private val jdbcTemplate: JdbcTemplate, private val accounts: Accounts) {

    fun of(id: UUID): List<HistoryEntryDto> {
        val rows = auditLog.history(id)
        val names = namesOf(rows)
        return rows.map { row -> names.entry(row, subject = id.toString()) }
    }

    /**
     * The latest [limit] changes to episodes, sessions and people, and to what's linked to them, oldest first, each
     * with what it's a change to, as no page says it, e.g. on the home page
     */
    fun recent(limit: Int): List<HistoryEntryDto> {
        val rows = auditLog.recentTold(limit).reversed()
        val subjects = rows.mapNotNull { it.subject() }
        fun ids(kind: ChangeKind) = subjects.filter { it.first == kind }.map { it.second }.toSet()
        val names = namesOf(rows, people = ids(ChangeKind.PERSON), sessions = ids(ChangeKind.SESSION))
        val episodes = byId("select id, code, title from episodes", ids(ChangeKind.EPISODE)) { it.getString(2) to it.getString(3) }
        return rows.mapNotNull { row ->
            val (kind, id) = row.subject() ?: return@mapNotNull null
            val about = when (kind) {
                ChangeKind.EPISODE -> episodes[id]?.let { (code, title) -> ChangeSubjectDto(kind, UUID.fromString(id), title, code) }
                ChangeKind.SESSION -> names.sessions[id]?.let { ChangeSubjectDto(kind, UUID.fromString(id), it, null) }
                else -> names.people[id]?.let { ChangeSubjectDto(kind, UUID.fromString(id), it, null) }
            }
            names.entry(row, subject = id, about)
        }
    }

    // Who made the changes, by their person's name once their account is linked, and the people, shows, sessions and
    // pictures they have by ID, by name
    private fun namesOf(rows: List<AuditRow>, people: Set<String> = emptySet(), sessions: Set<String> = emptySet()) = Names(
        actors = accounts.people(rows.mapNotNull { it.actorId }).mapValues { it.value.name },
        people = names("select id, name from people", rows.ids("person_id") + rows.ids("recommended_by") + people),
        shows = names("select id, name from shows", rows.ids("show_id")),
        // A session by its date and what it goes by, as the front-end's sessionTitle has it
        sessions = names("select id, date, coalesce(name, location) from sessions", rows.ids("session_id") + sessions) { rs ->
            listOfNotNull(DATE.format(rs.getObject("date", LocalDate::class.java)), rs.getString(3)).joinToString(" · ")
        },
        images = names("select id, name from images", IMAGE_COLUMNS.flatMap { rows.ids(it) }.toSet()),
    )

    private fun List<AuditRow>.ids(column: String): Set<String> =
        flatMap { listOf(it.old[column], it.new[column]) }.mapNotNull { it?.toString() }.toSet()

    // Soft-deleted ones too: a change shows what was there when it was made
    private fun names(
        select: String,
        ids: Set<String>,
        name: (java.sql.ResultSet) -> String = { it.getString(2) },
    ): Map<String, String> = byId(select, ids, name)

    private fun <T> byId(select: String, ids: Set<String>, value: (java.sql.ResultSet) -> T): Map<String, T> =
        if (ids.isEmpty()) emptyMap()
        else jdbcTemplate.query("$select where id::text in (${ids.joinToString { "?" }})", { rs, _ -> rs.getString(1) to value(rs) },
            *ids.toTypedArray()).toMap()
}

private data class Described(val summary: String, val fields: List<FieldChangeDto> = emptyList())

private class Names(
    // Who made changes, by their accounts' IDs (sub)
    val actors: Map<String, String>,
    val people: Map<String, String>,
    val shows: Map<String, String>,
    val sessions: Map<String, String>,
    val images: Map<String, String>,
) {

    fun entry(row: AuditRow, subject: String, about: ChangeSubjectDto? = null): HistoryEntryDto {
        val described = describe(row, subject)
        return HistoryEntryDto(
            row.at, row.actorId?.let(actors::get) ?: row.actor, row.client, row.tool, kindOf(row.table),
            row.rowId.takeIf { row.table == "comments" }, described.summary, described.fields, about,
        )
    }

    fun describe(row: AuditRow, subject: String): Described = when (row.table) {
        "episodes" -> if (row.rowId.toString() == subject) change(row, "episode", EPISODE_COLUMNS) else inSession(row, subject)
        "sessions" -> change(row, "session", SESSION_COLUMNS)
        "episode_people" -> role(row)
        "episode_documents", "session_documents" ->
            Described(if (row.operation == "DELETE") "Unlinked a Google Doc" else "Linked a Google Doc")
        "episode_koofr_folders", "session_koofr_folders" -> folder(row, "Koofr")
        "episode_dropbox_folders", "session_dropbox_folders" -> folder(row, "Dropbox")
        "comments" -> comment(row)
        "accounts" -> account(row)
        "episode_media" -> media(row)
        "transcripts" -> transcript(row)
        "people" -> change(row, "person", PERSON_COLUMNS)
        else -> Described("Changed ${row.table}")
    }

    // An account linked to a person or unlinked from them, by its username or email
    private fun account(row: AuditRow): Described {
        val account = (row.new["username"] ?: row.new["email"] ?: row.old["username"] ?: row.old["email"])?.toString() ?: "An account"
        val to = row.new["person_id"]?.let { people[it.toString()] ?: "someone deleted since" }
        val from = row.old["person_id"]?.let { people[it.toString()] ?: "someone deleted since" }
        return when {
            to != null -> Described("Linked $account to $to" + if (row.new["linked_how"] == "EMAIL") " (by email)" else "")
            from != null -> Described("Unlinked $account from $from")
            else -> Described("Changed the account $account")
        }
    }

    // A video or audio file, by its name: the full ones are the episode's only one of their kind, bonuses one of several
    private fun media(row: AuditRow): Described {
        fun kind(values: Map<String, Any?>) = values["kind"]?.toString()?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
        fun what(kind: MediaKind?) = when {
            kind == null -> "a file"
            kind.full -> "the ${kind.label}"
            else -> "a ${kind.label}"
        }
        val name = (row.new["name"] ?: row.old["name"])?.toString()
        return when (row.operation) {
            "INSERT" -> Described((if (kind(row.new)?.full == true) "Uploaded " else "Added ") + "${what(kind(row.new))}: $name")
            "DELETE" -> Described("Removed ${what(kind(row.old))}: $name")
            // In words that say it all, as the front-end's activity shows fields only for the episode's own changes
            else -> {
                val diff = row.diff()
                val kindChange = diff["kind"]
                val titleChange = diff["title"]
                val fields = listOfNotNull(
                    kindChange?.let { FieldChangeDto("Kind", kind(row.old)?.label, kind(row.new)?.label) },
                    titleChange?.let { FieldChangeDto("Title", it.from?.toString(), it.to?.toString()) },
                )
                val summary = when {
                    kindChange != null && titleChange == null -> "Made $name ${what(kind(row.new))}"
                    titleChange != null && kindChange == null && titleChange.to != null ->
                        "Titled ${what(kind(row.new))} $name: ${titleChange.to}"
                    titleChange != null && kindChange == null -> "Took the title off ${what(kind(row.new))} $name"
                    else -> "Changed ${what(kind(row.old))}: $name"
                }
                Described(summary, fields)
            }
        }
    }

    // A transcript of one of the episode's files (Transcription.kt), by the file's name: made, made again, or named
    private fun transcript(row: AuditRow): Described {
        val name = (row.new["media_name"] ?: row.old["media_name"])?.toString()
        return when (row.operation) {
            "INSERT" -> Described("Transcribed $name")
            "DELETE" -> Described("Removed the transcript of $name")
            else -> Described(if (row.diff()["speakers"] != null) "Named the speakers in the transcript of $name" else "Changed the transcript of $name")
        }
    }

    // A comment in the discussion, with the start of what it said; comments are long, so an edit doesn't show the text
    private fun comment(row: AuditRow): Described {
        val deletedAt = row.diff()["deleted_at"]
        return when {
            row.operation == "INSERT" -> Described("Commented", listOf(FieldChangeDto("Comment", null, excerpt(row.new["body"]))))
            row.operation == "DELETE" -> Described("Deleted a comment for good")
            deletedAt != null -> Described(if (deletedAt.to != null) "Deleted a comment" else "Restored a comment")
            else -> Described("Edited a comment")
        }
    }

    // The episode or the session itself; soft deletes set deleted_at
    private fun change(row: AuditRow, what: String, columns: Map<String, String>): Described {
        val diff = row.diff()
        val deletedAt = diff["deleted_at"]
        return when {
            row.operation == "INSERT" -> Described("Created the $what", fields(diff, columns))
            row.operation == "DELETE" -> Described("Deleted the $what for good")
            deletedAt != null -> Described(if (deletedAt.to != null) "Deleted the $what" else "Restored the $what")
            else -> Described("Changed the $what", fields(diff, columns))
        }
    }

    private fun fields(diff: Map<String, ColumnChange>, columns: Map<String, String>): List<FieldChangeDto> =
        columns.filterKeys { it in diff }.map { (column, label) ->
            val change = diff.getValue(column)
            if (column in LONG_COLUMNS) FieldChangeDto(label, null, null)
            else FieldChangeDto(label, value(column, change.from), value(column, change.to))
        }

    private fun value(column: String, value: Any?): String? = when {
        value == null -> null
        column == "status" -> value.toString().lowercase().replace('_', ' ')
        column == "show_id" -> shows[value.toString()] ?: "a show deleted since"
        column == "session_id" -> sessions[value.toString()] ?: "a session deleted since"
        column == "recommended_by" -> people[value.toString()] ?: "someone deleted since"
        column in IMAGE_COLUMNS -> images[value.toString()] ?: "a picture"
        column in DATE_COLUMNS -> runCatching { DATE.format(LocalDate.parse(value.toString())) }.getOrDefault(value.toString())
        else -> value.toString()
    }

    // An episode moving in or out of the session whose history this is
    private fun inSession(row: AuditRow, session: String): Described {
        val episode = (row.new["code"] ?: row.old["code"])?.toString() ?: "\"${row.new["title"] ?: row.old["title"]}\""
        return Described(if (row.new["session_id"]?.toString() == session) "Added $episode to the session" else "Took $episode out of the session")
    }

    private fun role(row: AuditRow): Described {
        val person = people[(row.new["person_id"] ?: row.old["person_id"]).toString()] ?: "someone deleted since"
        // CO_HOST -> co-host, as the front-end has it
        fun role(values: Map<String, Any?>) = values["role"]?.toString()?.lowercase()?.replace('_', '-')
        return when (row.operation) {
            "INSERT" -> Described("Added $person as ${role(row.new)}")
            "DELETE" -> Described("Removed $person as ${role(row.old)}")
            else -> Described("Changed $person's role", listOf(FieldChangeDto("Role", role(row.old), role(row.new))))
        }
    }

    // A Dropbox folder's link changes only to get a link to it
    private fun folder(row: AuditRow, service: String): Described = when {
        row.operation == "INSERT" -> Described("Linked the $service folder ${row.new["path"]}")
        row.operation == "DELETE" -> Described("Unlinked the $service folder ${row.old["path"]}")
        row.old["path"] == row.new["path"] -> Described("Made a link to the $service folder ${row.new["path"]}")
        else -> Described("Moved a $service folder", listOf(FieldChangeDto("Path", row.old["path"]?.toString(), row.new["path"]?.toString())))
    }
}
