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
data class CurrentUserDto(
    val username: String?,
    val email: String?,
    val emailVerified: Boolean,
    val person: PersonDto?,
    // Once MetaOS has seen it
    val account: AccountDto?,
    // Whether they may link accounts to people (ACCOUNT_LINKER)
    val canLinkAccounts: Boolean,
)

fun Person.toDto() =
    PersonDto(id, name, email, bio, homepageUrl, linkedinUrl, twitterUrl, instagramUrl, facebookUrl)

/** The user a token belongs to, with their account and the person they are (Accounts.signedInPerson) */
fun Jwt.toCurrentUserDto(accounts: Accounts) = CurrentUserDto(
    username = getClaimAsString("preferred_username"),
    email = getClaimAsString("email"),
    emailVerified = getClaimAsBoolean("email_verified") == true,
    person = accounts.signedInPerson()?.toDto(),
    account = accounts.signedInAccount()?.let { accounts.dtos(listOf(it)).single() },
    canLinkAccounts = mayLinkAccounts(),
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
    private val accounts: Accounts,
) {

    @McpTool(
        name = "list_people",
        annotations = McpTool.McpAnnotations(
            title = "List people",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List all people"
    )
    fun list(): List<PersonDto> = people.findAll().map { it.toDto() }

    @Transactional
    @McpTool(
        name = "create_person",
        annotations = McpTool.McpAnnotations(
            title = "Create person",
            readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false
        ),
        description = "Create a new person"
    )
    fun create(
        @McpToolParam(description = "Full name") name: String,
        @McpToolParam(description = "Unique email address (case-insensitive)", required = false) email: String?,
    ): PersonDto {
        require(email == null || people.findByEmail(email) == null) { "Person with email $email already exists" }
        return people.save(Person(name = name, email = email)).also(accounts::linkByEmail).toDto()
    }

    @Transactional
    @McpTool(
        name = "set_person_email",
        annotations = McpTool.McpAnnotations(
            title = "Set person email",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Set or clear a person's email. Accounts with the same verified email that aren't linked to anyone " +
            "are linked to this person (unless someone unlinked them)"
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
        accounts.linkByEmail(person)
        return person.toDto()
    }

    @Transactional
    @McpTool(
        name = "set_person_bio",
        annotations = McpTool.McpAnnotations(
            title = "Set person bio",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
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
        annotations = McpTool.McpAnnotations(
            title = "Set person link",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
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
        annotations = McpTool.McpAnnotations(
            title = "Delete person",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
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
        annotations = McpTool.McpAnnotations(
            title = "Restore person",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
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
        annotations = McpTool.McpAnnotations(
            title = "Who am I",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Show the signed-in user, their account and the person they are: their account's, or else the " +
            "one with their verified email"
    )
    fun whoami(): CurrentUserDto = checkNotNull(currentJwt()) { "Not signed in" }.toCurrentUserDto(accounts)

    @Transactional(readOnly = true)
    @McpTool(
        name = "my_episodes",
        annotations = McpTool.McpAnnotations(
            title = "My episodes",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the episodes the signed-in user is on and their roles in each, latest first"
    )
    fun myEpisodes(): List<PersonEpisodeDto> =
        episodePeople.findByPersonWithEpisode(signedInPerson()).toPersonEpisodeDtos()

    private fun signedInPerson(): Person {
        checkNotNull(currentJwt()) { "Not signed in" }
        return checkNotNull(accounts.signedInPerson()) {
            "Your account isn't linked to a person; link it with link_account, or give your person your email with set_person_email"
        }
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_person_episodes",
        annotations = McpTool.McpAnnotations(
            title = "List person episodes",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the episodes a person is on and their roles in each, latest first"
    )
    fun listEpisodes(@McpToolParam(description = "Person ID") personId: UUID): List<PersonEpisodeDto> {
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        return episodePeople.findByPersonWithEpisode(person).toPersonEpisodeDtos()
    }
}
