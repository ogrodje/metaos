package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class PersonDto(
    val id: UUID?,
    val name: String,
    val email: String?,
    val bio: String? = null,
    val homepageUrl: String? = null,
    val linkedinUrl: String? = null,
    val twitterUrl: String? = null,
    val instagramUrl: String? = null,
    val facebookUrl: String? = null,
)
data class PersonEpisodeDto(val episodeId: UUID?, val code: String?, val title: String, val roles: List<Role>)
data class CurrentUserDto(val username: String?, val email: String?, val emailVerified: Boolean, val person: PersonDto?)

fun Person.toDto() =
    PersonDto(id, name, email, bio, homepageUrl, linkedinUrl, twitterUrl, instagramUrl, facebookUrl)

/** The user a token belongs to, with the person linked to them by verified email */
fun Jwt.toCurrentUserDto(people: PeopleRepository) = CurrentUserDto(
    username = getClaimAsString("preferred_username"),
    email = getClaimAsString("email"),
    emailVerified = getClaimAsBoolean("email_verified") == true,
    person = verifiedEmail?.let(people::findByEmail)?.toDto(),
)

// One entry per episode, with all of the person's roles on it
private fun List<EpisodePerson>.toPersonEpisodeDtos(): List<PersonEpisodeDto> =
    groupBy { it.episode.id }.values.map { assignments ->
        val episode = assignments.first().episode
        PersonEpisodeDto(episode.id, episode.code, episode.title, assignments.map { it.role }.sorted())
    }

@Component
class PeopleMCP(
    private val people: PeopleRepository,
    private val episodePeople: EpisodePeopleRepository,
    private val episodes: EpisodesRepository,
) {

    @McpTool(
        name = "list_people",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "List all people"
    )
    fun list(): List<PersonDto> = people.findAll().map { it.toDto() }

    @Transactional
    @McpTool(
        name = "create_person",
        annotations = McpTool.McpAnnotations(destructiveHint = false),
        description = "Create a new person"
    )
    fun create(
        @McpToolParam(description = "Full name") name: String,
        @McpToolParam(description = "Unique email address (case-insensitive)", required = false) email: String?,
    ): PersonDto {
        require(email == null || people.findByEmail(email) == null) { "Person with email $email already exists" }
        return people.save(Person(name = name, email = email)).toDto()
    }

    @Transactional
    @McpTool(
        name = "set_person_email",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Set or clear a person's email. A signed-in user with the same verified email is linked to this person"
    )
    fun setEmail(
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(description = "Unique email address (case-insensitive); omit to clear", required = false) email: String?,
    ): PersonDto {
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        require(email == null || people.findByEmail(email).let { it == null || it == person }) {
            "Person with email $email already exists"
        }
        person.email = email
        return person.toDto()
    }

    @Transactional
    @McpTool(
        name = "set_person_bio",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Set or clear the line about who a person is, e.g. their role and company"
    )
    fun setBio(
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(description = "Bio, e.g. \"CTO @ Koofr\"; omit or empty to clear", required = false) bio: String?,
    ): PersonDto {
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        person.bio = bio?.trim()?.ifEmpty { null }
        return person.toDto()
    }

    @Transactional
    @McpTool(
        name = "set_person_link",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Set or clear where a person can be found online: their homepage, LinkedIn, Twitter/X, Instagram or Facebook"
    )
    fun setLink(
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(description = "Personal website or social network the link points to") network: SocialNetwork,
        @McpToolParam(description = "URL of the person's page there (https, except a homepage); omit to clear", required = false) url: String?,
    ): PersonDto {
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        person.setSocialLink(network, url?.trim()?.ifEmpty { null })
        return person.toDto()
    }

    @Transactional
    @McpTool(
        name = "delete_person",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Soft-delete a person. They drop off their episodes, and the episodes they recommended, until " +
            "they are restored"
    )
    fun delete(@McpToolParam(description = "Person ID") personId: UUID): PersonDto? =
        people.findByIdOrNull(personId)?.also { person ->
            // In the same transaction, so restoring them finds these in the audit log (reattachToRecommender)
            episodes.findByRecommendedBy(person).forEach { it.recommendedBy = null }
            people.delete(person)
        }?.toDto()

    @Transactional
    @McpTool(
        name = "restore_person",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Bring back a soft-deleted person, with their roles on episodes and the episodes they recommended " +
            "(unless someone else has been credited with one since). With another email, or none, if theirs has " +
            "been taken since"
    )
    fun restore(
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(
            description = "Email to restore them with, e.g. when theirs has been taken since; empty to restore them without one",
            required = false,
        )
        newEmail: String? = null,
    ): PersonDto {
        require(people.isDeleted(personId)) { "There is no deleted person $personId" }
        if (newEmail == null) {
            people.findDeletedEmail(personId)?.let { email ->
                require(people.findByEmail(email) == null) {
                    "Person with email $email already exists; restore them with another email, or none (newEmail)"
                }
            }
        } else {
            val email = newEmail.trim().ifEmpty { null }
            require(email == null || people.findByEmail(email) == null) { "Person with email $email already exists" }
            people.setDeletedEmail(personId, email)
        }
        people.restore(personId)
        episodes.reattachToRecommender(personId)
        return requireNotNull(people.findByIdOrNull(personId)).toDto()
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "whoami",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "Show the signed-in user and the person linked to them by verified email"
    )
    fun whoami(): CurrentUserDto = checkNotNull(currentJwt()) { "Not signed in" }.toCurrentUserDto(people)

    @Transactional(readOnly = true)
    @McpTool(
        name = "my_episodes",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "List the episodes the signed-in user is on and their roles in each, latest first"
    )
    fun myEpisodes(): List<PersonEpisodeDto> =
        episodePeople.findByPersonWithEpisode(signedInPerson()).toPersonEpisodeDtos()

    private fun signedInPerson(): Person {
        val jwt = checkNotNull(currentJwt()) { "Not signed in" }
        val email = checkNotNull(jwt.verifiedEmail) { "Signed-in user has no verified email" }
        return checkNotNull(people.findByEmail(email)) { "No person has email $email; link one with set_person_email" }
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_person_episodes",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "List the episodes a person is on and their roles in each, latest first"
    )
    fun listEpisodes(@McpToolParam(description = "Person ID") personId: UUID): List<PersonEpisodeDto> {
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        return episodePeople.findByPersonWithEpisode(person).toPersonEpisodeDtos()
    }
}
