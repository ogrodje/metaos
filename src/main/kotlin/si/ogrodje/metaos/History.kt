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
enum class ChangeKind { EPISODE, SESSION, PERSON, DOCUMENT, KOOFR_FOLDER, DROPBOX_FOLDER, COMMENT, ACCOUNT, OTHER }

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
)

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
    else -> ChangeKind.OTHER
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

// The columns people care about, in the order they're shown, by their names
private val EPISODE_COLUMNS = linkedMapOf(
    "code" to "Code", "title" to "Title", "title_b" to "Title B", "title_c" to "Title C", "status" to "Status",
    "show_id" to "Show", "session_id" to "Session", "recommended_by" to "Recommended by",
    "recording_date" to "Recording date", "aired_date" to "Aired", "added_date" to "Added",
    "youtube_url" to "YouTube", "spotify_url" to "Spotify", "apple_podcasts_url" to "Apple Podcasts",
    "patreon_url" to "Patreon", "summary" to "Summary", "tiny_summary" to "Tiny summary", "machine_summary" to "AI summary",
    "thumbnail_image_id" to "Thumbnail", "thumbnail_b_image_id" to "Thumbnail B", "thumbnail_c_image_id" to "Thumbnail C",
    "cover_image_id" to "Cover",
)
private val SESSION_COLUMNS = linkedMapOf("date" to "Date", "location" to "Location", "address" to "Address")

// Too long to show what they were and what they became
private val LONG_COLUMNS = setOf("summary", "machine_summary")
private val DATE_COLUMNS = setOf("recording_date", "aired_date", "added_date", "date")
// Pictures, by the names of the files they were made from
private val IMAGE_COLUMNS = setOf("thumbnail_image_id", "thumbnail_b_image_id", "thumbnail_c_image_id", "cover_image_id")

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
        // Who made them, by their person's name once their account is linked
        val actors = accounts.people(rows.mapNotNull { it.actorId })
        val names = Names(
            people = names("select id, name from people", rows.ids("person_id") + rows.ids("recommended_by")),
            shows = names("select id, name from shows", rows.ids("show_id")),
            sessions = names("select id, date, location from sessions", rows.ids("session_id")) { rs ->
                listOfNotNull(DATE.format(rs.getObject("date", LocalDate::class.java)), rs.getString("location")).joinToString(" · ")
            },
            images = names("select id, name from images", IMAGE_COLUMNS.flatMap { rows.ids(it) }.toSet()),
        )
        return rows.map { row ->
            val described = names.describe(row, subject = id.toString())
            HistoryEntryDto(
                row.at, row.actorId?.let(actors::get)?.name ?: row.actor, row.client, row.tool, kindOf(row.table),
                row.rowId.takeIf { row.table == "comments" }, described.summary, described.fields,
            )
        }
    }

    private fun List<AuditRow>.ids(column: String): Set<String> =
        flatMap { listOf(it.old[column], it.new[column]) }.mapNotNull { it?.toString() }.toSet()

    // Soft-deleted ones too: a change shows what was there when it was made
    private fun names(
        select: String,
        ids: Set<String>,
        name: (java.sql.ResultSet) -> String = { it.getString(2) },
    ): Map<String, String> =
        if (ids.isEmpty()) emptyMap()
        else jdbcTemplate.query("$select where id::text in (${ids.joinToString { "?" }})", { rs, _ -> rs.getString(1) to name(rs) },
            *ids.toTypedArray()).toMap()
}

private data class Described(val summary: String, val fields: List<FieldChangeDto> = emptyList())

private class Names(
    val people: Map<String, String>,
    val shows: Map<String, String>,
    val sessions: Map<String, String>,
    val images: Map<String, String>,
) {

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
