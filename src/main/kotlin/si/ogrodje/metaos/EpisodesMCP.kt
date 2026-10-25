package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpResource
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

data class EpisodeDto(
    val id: UUID?,
    val code: String?,
    val title: String,
    val status: EpisodeStatus,
    val sessionId: UUID?,
    val show: String?,
    val recommendedById: UUID?,
    val recommendedBy: String?,
    val recordingDate: LocalDate?,
    val airedDate: LocalDate?,
    val addedDate: LocalDate,
    val youtubeUrl: String?,
    val spotifyUrl: String?,
    val applePodcastsUrl: String?,
    val patreonUrl: String?,
    val patreonAudioUrl: String?,
    val patreonBonusUrl: String?,
    val patreonBonusAudioUrl: String?,
    val tinySummary: String?,
)

// What get_episode returns; the summary and the AI summary are long, so lists of episodes have only the tiny one
data class EpisodeDetailsDto(
    val id: UUID?,
    val code: String?,
    val title: String,
    val titleB: String?,
    val titleC: String?,
    val status: EpisodeStatus,
    val sessionId: UUID?,
    val show: String?,
    val recommendedById: UUID?,
    val recommendedBy: String?,
    val recordingDate: LocalDate?,
    val airedDate: LocalDate?,
    val addedDate: LocalDate,
    val youtubeUrl: String?,
    val spotifyUrl: String?,
    val applePodcastsUrl: String?,
    val patreonUrl: String?,
    val patreonAudioUrl: String?,
    val patreonBonusUrl: String?,
    val patreonBonusAudioUrl: String?,
    val summary: String?,
    val tinySummary: String?,
    val machineSummary: String?,
)
data class EpisodePersonDto(val personId: UUID?, val name: String, val email: String?, val roles: List<Role>)

fun Episode.toDto() = EpisodeDto(
    id = id, code = code, title = title, status = status, sessionId = session?.id, show = show?.name,
    recommendedById = recommendedBy?.id, recommendedBy = recommendedBy?.name,
    recordingDate = recordingDate, airedDate = airedDate, addedDate = addedDate,
    youtubeUrl = youtubeUrl, spotifyUrl = spotifyUrl, applePodcastsUrl = applePodcastsUrl, patreonUrl = patreonUrl,
    patreonAudioUrl = patreonAudioUrl, patreonBonusUrl = patreonBonusUrl, patreonBonusAudioUrl = patreonBonusAudioUrl,
    tinySummary = tinySummary,
)

private fun Episode.toDetailsDto() = EpisodeDetailsDto(
    id = id, code = code, title = title, titleB = titleB, titleC = titleC, status = status, sessionId = session?.id,
    show = show?.name, recommendedById = recommendedBy?.id, recommendedBy = recommendedBy?.name,
    recordingDate = recordingDate, airedDate = airedDate, addedDate = addedDate,
    youtubeUrl = youtubeUrl, spotifyUrl = spotifyUrl, applePodcastsUrl = applePodcastsUrl, patreonUrl = patreonUrl,
    patreonAudioUrl = patreonAudioUrl, patreonBonusUrl = patreonBonusUrl, patreonBonusAudioUrl = patreonBonusAudioUrl,
    summary = summary, tinySummary = tinySummary, machineSummary = machineSummary,
)

// For messages: ideas may not have a code yet
val Episode.label get() = code ?: "\"$title\""

private fun Person.onEpisode(assignments: List<EpisodePerson>) =
    EpisodePersonDto(id, name, email, assignments.map { it.role }.sorted())

// One entry per person, with all of their roles on the episode; hosts first
fun List<EpisodePerson>.toEpisodePersonDtos(): List<EpisodePersonDto> =
    groupBy { it.person.id }.values.map { it.first().person.onEpisode(it) }
        .sortedWith(compareBy({ it.roles.first() }, { it.name }))

private fun List<String>.joinedWithAnd() = if (size < 2) joinToString() else dropLast(1).joinToString() + " and " + last()

@Component
class EpisodesMCP(
    private val episodes: EpisodesRepository,
    private val people: PeopleRepository,
    private val episodePeople: EpisodePeopleRepository,
    private val accounts: Accounts,
) {

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    @McpTool(
        name = "list_episodes",
        annotations = McpTool.McpAnnotations(
            title = "List episodes",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List episodes, latest season and episode first, then the bonus season ($BONUS_SEASON) and ideas " +
            "without a code last, optionally only those with a given status. Of the summaries only the tiny one is " +
            "there; get_episode has them all"
    )
    fun list(
        @McpToolParam(description = "Only list episodes with this status", required = false) status: EpisodeStatus? = null,
    ): List<EpisodeDto> =
        (if (status == null) episodes.findLatestFirst() else episodes.findLatestFirstByStatus(status)).map { it.toDto() }

    @McpTool(
        name = "get_episode",
        annotations = McpTool.McpAnnotations(
            title = "Get episode",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Find an episode by its code (case-insensitive) or ID, with its summaries"
    )
    fun get(@McpToolParam(description = EPISODE_REF) code: String): EpisodeDetailsDto? =
        episodes.findByRef(code)?.toDetailsDto()

    private fun findPerson(id: UUID) = requireNotNull(people.findByIdOrNull(id)) { "Person $id not found" }

    @Transactional
    @McpTool(
        name = "create_episode",
        annotations = McpTool.McpAnnotations(
            title = "Create episode",
            readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false
        ),
        description = "Create a new episode. It starts as an IDEA, which can do without a code until it is planned. " +
            "It's recommended by the person linked to you, unless you name someone else"
    )
    fun create(
        @McpToolParam(description = "Unique episode code, e.g. S05E08", required = false) code: String?,
        @McpToolParam(description = "Episode title") title: String,
        @McpToolParam(
            description = "ID of the person who recommended it; omit for the person linked to you by email, if there is one",
            required = false,
        )
        recommendedBy: UUID? = null,
    ): EpisodeDto {
        val episode = Episode(title = title, code = code?.trim()?.ifEmpty { null })
        episode.recommendedBy = recommendedBy?.let(::findPerson) ?: accounts.signedInPerson()
        return episodes.save(episode).toDto()
    }

    @Transactional
    @McpTool(
        name = "set_episode_recommended_by",
        annotations = McpTool.McpAnnotations(
            title = "Set who recommended episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Set who recommended an episode, e.g. someone else than who added it, or clear it"
    )
    fun setRecommendedBy(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Person ID; omit to clear", required = false) personId: UUID?,
    ): EpisodeDto {
        val episode = findEpisode(code)
        episode.recommendedBy = personId?.let(::findPerson)
        return episode.toDto()
    }

    @Transactional
    @McpTool(
        name = "update_episode",
        annotations = McpTool.McpAnnotations(
            title = "Update episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Change an episode's code, title, alternative titles (B and C, for A/B tests), summaries or dates. " +
            "Omitted fields are left as they are"
    )
    fun update(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "New unique episode code", required = false) newCode: String?,
        @McpToolParam(description = "New title", required = false) title: String?,
        @McpToolParam(description = "Alternative title B, which the title is A/B tested against; empty to clear", required = false)
        titleB: String? = null,
        @McpToolParam(description = "Alternative title C, which the title is A/B tested against; empty to clear", required = false)
        titleC: String? = null,
        @McpToolParam(description = "Description of the episode, in Markdown; empty to clear", required = false)
        summary: String? = null,
        @McpToolParam(description = "Very short summary of the episode, e.g. a sentence, in Markdown; empty to clear", required = false)
        tinySummary: String? = null,
        @McpToolParam(description = "AI-generated summary of the episode, in Markdown; empty to clear", required = false)
        machineSummary: String? = null,
        @McpToolParam(description = "When the episode is or was recorded, e.g. 2026-10-19", required = false)
        recordingDate: LocalDate? = null,
        @McpToolParam(description = "When the episode was released, e.g. 2026-10-19", required = false)
        airedDate: LocalDate? = null,
        @McpToolParam(description = "When the episode was added, e.g. 2026-10-19", required = false)
        addedDate: LocalDate? = null,
    ): EpisodeDetailsDto {
        val episode = findEpisode(code)
        newCode?.trim()?.let { new ->
            require(new.isNotEmpty()) { "Code must not be blank" }
            require(episodes.findByCode(new).let { it == null || it == episode }) { "Episode $new already exists" }
            episode.code = new
        }
        title?.let {
            require(it.isNotBlank()) { "Title must not be blank" }
            episode.title = it.trim()
        }
        titleB?.let { episode.titleB = it.trim().ifEmpty { null } }
        titleC?.let { episode.titleC = it.trim().ifEmpty { null } }
        summary?.let { episode.summary = it.trim().ifEmpty { null } }
        tinySummary?.let { episode.tinySummary = it.trim().ifEmpty { null } }
        machineSummary?.let { episode.machineSummary = it.trim().ifEmpty { null } }
        recordingDate?.let { episode.recordingDate = it }
        airedDate?.let { episode.airedDate = it }
        addedDate?.let { episode.addedDate = it }
        return episode.toDetailsDto()
    }

    @Transactional
    @McpTool(
        name = "set_episode_status",
        annotations = McpTool.McpAnnotations(
            title = "Set episode status",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Move an episode through its lifecycle: IDEA → PLANNED → SCHEDULED → RECORDED → IN_PRODUCTION → " +
            "READY → EARLY_ACCESS (optional) → PUBLISHED, one step forward or back to any earlier step; ON_HOLD or " +
            "CANCELLED any time before it is published. Each step needs what the ones before it did, plus: PLANNED a " +
            "code and a host, SCHEDULED a recording date, RECORDED one that is not in the future, IN_PRODUCTION a " +
            "producer, READY a summary, EARLY_ACCESS any of its Patreon links, PUBLISHED a YouTube, Spotify or Apple Podcasts " +
            "link. Publishing sets the aired date to today if there is none"
    )
    fun setStatus(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Status to move the episode to") status: EpisodeStatus,
    ): EpisodeDto {
        val episode = findEpisode(code)
        require(episode.status.canMoveTo(status)) { "Episode ${episode.label} can't go from ${episode.status} to $status" }
        val missing = missingFor(episode, status)
        require(missing.isEmpty()) { "Episode ${episode.label} needs ${missing.joinedWithAnd()} before it can be $status" }
        episode.status = status
        if (status == EpisodeStatus.PUBLISHED && episode.airedDate == null) episode.airedDate = LocalDate.now()
        return episode.toDto()
    }

    // What an episode needs before it can enter a status. Needs build up along the main path, so resuming from
    // ON_HOLD can't skip any; the Patreon link is only needed for EARLY_ACCESS itself, as that step is optional.
    private fun missingFor(episode: Episode, target: EpisodeStatus): List<String> {
        if (target.codeOptional) return emptyList()
        val roles by lazy { episodePeople.findByEpisodeWithPerson(episode).map { it.role }.toSet() }
        fun from(step: EpisodeStatus) = target.ordinal >= step.ordinal
        val publicLinks = listOf(episode.youtubeUrl, episode.spotifyUrl, episode.applePodcastsUrl)
        return buildList {
            if (episode.code == null) add("a code")
            if (Role.HOST !in roles) add("a host")
            if (from(EpisodeStatus.SCHEDULED) && episode.recordingDate == null) add("a recording date")
            if (from(EpisodeStatus.RECORDED) && episode.recordingDate?.isAfter(LocalDate.now()) == true) {
                add("a recording date that is not in the future")
            }
            if (from(EpisodeStatus.IN_PRODUCTION) && Role.PRODUCER !in roles) add("a producer")
            if (from(EpisodeStatus.READY) && episode.summary == null) add("a summary")
            if (target == EpisodeStatus.EARLY_ACCESS && episode.patreonLinks.all { it == null }) add("a Patreon link")
            if (target == EpisodeStatus.PUBLISHED && publicLinks.all { it == null }) {
                add("a YouTube, Spotify or Apple Podcasts link")
            }
        }
    }

    @Transactional
    @McpTool(
        name = "set_episode_link",
        annotations = McpTool.McpAnnotations(
            title = "Set episode link",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Set or clear where an episode can be watched or listened to: YouTube, Spotify, Apple Podcasts or " +
            "Patreon. Patreon has four links, each set or cleared on its own: the full episode as video (PATREON) or " +
            "audio only (PATREON_AUDIO), and its bonus as video (PATREON_BONUS) or audio only (PATREON_BONUS_AUDIO)"
    )
    fun setLink(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(
            description = "Platform the link points to; for Patreon, which of its four: PATREON is the full episode as " +
                "video, PATREON_AUDIO the full episode as audio only, PATREON_BONUS the bonus as video, " +
                "PATREON_BONUS_AUDIO the bonus as audio only"
        ) platform: Platform,
        @McpToolParam(description = "https URL of the episode on that platform; omit to clear", required = false) url: String?,
    ): EpisodeDto {
        val episode = findEpisode(code)
        episode.setLink(platform, url)
        return episode.toDto()
    }

    @Transactional
    @McpTool(
        name = "delete_episode",
        annotations = McpTool.McpAnnotations(
            title = "Delete episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Soft-delete an episode by its code (case-insensitive) or ID"
    )
    fun delete(@McpToolParam(description = EPISODE_REF) code: String): EpisodeDto? =
        episodes.findByRef(code)?.also { episodes.delete(it) }?.toDto()

    @Transactional
    @McpTool(
        name = "restore_episode",
        annotations = McpTool.McpAnnotations(
            title = "Restore episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Bring back a soft-deleted episode by its code (case-insensitive) or ID, with its people, links, " +
            "session, show and who recommended it. Any of those deleted meanwhile come back to it once they're " +
            "restored too. Under a new code, if its own has been taken since"
    )
    fun restore(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "New unique code to restore it under, e.g. when its own has been taken since", required = false)
        newCode: String? = null,
    ): EpisodeDto {
        val id = requireNotNull(code.toUuidOrNull()?.takeIf { episodes.isDeleted(it) } ?: episodes.findDeletedIdByCode(code)) {
            "There is no deleted episode $code"
        }
        val new = newCode?.trim()
        if (new == null) {
            episodes.findDeletedCode(id)?.let {
                require(episodes.findByCode(it) == null) { "Episode $it already exists; restore it under a new code (newCode)" }
            }
        } else {
            require(new.isNotEmpty()) { "Code must not be blank" }
            require(episodes.findByCode(new) == null) { "Episode $new already exists" }
            episodes.recodeDeleted(id, new)
        }
        episodes.restore(id)
        return requireNotNull(episodes.findByIdOrNull(id)).toDto()
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_episode_people",
        annotations = McpTool.McpAnnotations(
            title = "List episode people",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the people on an episode and their roles"
    )
    fun listPeople(@McpToolParam(description = EPISODE_REF) code: String): List<EpisodePersonDto> =
        episodePeople.findByEpisodeWithPerson(findEpisode(code)).toEpisodePersonDtos()

    @Transactional
    @McpTool(
        name = "assign_person_to_episode",
        annotations = McpTool.McpAnnotations(
            title = "Assign person to episode",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Give a person a role on an episode and return all of their roles on it. A person can have " +
            "several roles on one episode; assigning a role they already have changes nothing"
    )
    fun assignPerson(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(description = "Role on the episode; CANDIDATE for someone who could be its guest, until confirm_guest makes them one")
        role: Role,
    ): EpisodePersonDto {
        val episode = findEpisode(code)
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        if (episodePeople.findByEpisodeAndPersonAndRole(episode, person, role) == null) {
            episodePeople.save(EpisodePerson(episode = episode, person = person, role = role))
        }
        return person.onEpisode(episodePeople.findByEpisodeAndPerson(episode, person))
    }

    @Transactional
    @McpTool(
        name = "remove_person_from_episode",
        annotations = McpTool.McpAnnotations(
            title = "Remove person from episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Take a person off an episode: one of their roles on it, or all of them when no role is given. " +
            "Returns the roles they still have on it"
    )
    fun removePerson(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(description = "Role to remove; omit to remove all of the person's roles", required = false) role: Role?,
    ): EpisodePersonDto {
        val episode = findEpisode(code)
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        val (removed, remaining) = episodePeople.findByEpisodeAndPerson(episode, person)
            .partition { role == null || it.role == role }
        require(removed.isNotEmpty()) {
            if (role == null) "${person.name} is not on episode ${episode.label}"
            else "${person.name} is not $role on episode ${episode.label}"
        }
        episodePeople.deleteAll(removed)
        return person.onEpisode(remaining)
    }

    @Transactional
    @McpTool(
        name = "confirm_guest",
        annotations = McpTool.McpAnnotations(
            title = "Confirm guest",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Make a candidate a guest: a person who is a CANDIDATE on an episode, someone who could be its " +
            "guest, becomes its GUEST, e.g. once they've said yes. Returns all of their roles on it"
    )
    fun confirmGuest(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Person ID") personId: UUID,
    ): EpisodePersonDto {
        val episode = findEpisode(code)
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        val candidate = requireNotNull(episodePeople.findByEpisodeAndPersonAndRole(episode, person, Role.CANDIDATE)) {
            "${person.name} is not a candidate on episode ${episode.label}"
        }
        // The candidacy becomes the guest role, so the history has it as one change; for a guest already, it just goes
        if (episodePeople.findByEpisodeAndPersonAndRole(episode, person, Role.GUEST) == null) candidate.role = Role.GUEST
        else episodePeople.delete(candidate)
        return person.onEpisode(episodePeople.findByEpisodeAndPerson(episode, person))
    }

    @McpResource(uri = "episode://{code}", name = "Episode", description = "Episode by code or ID")
    fun resource(code: String): String =
        episodes.findByRef(code)?.let { "${it.label}: ${it.title}" } ?: "Episode $code not found"
}
