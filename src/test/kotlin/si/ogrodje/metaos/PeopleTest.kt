package si.ogrodje.metaos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class PeopleTest @Autowired constructor(
    private val episodesRepository: EpisodesRepository,
    private val peopleRepository: PeopleRepository,
    private val episodesMCP: EpisodesMCP,
    private val peopleMCP: PeopleMCP,
) {

    @Test
    fun `rejects duplicate email regardless of case`() {
        signInAs(ACCOUNT_LINKER)
        peopleMCP.create(name = "Ana", email = "ana@ogrodje.si")

        assertFailsWith<IllegalArgumentException> {
            peopleMCP.create(name = "Ana Again", email = "ANA@ogrodje.si")
        }
        assertFailsWith<DataIntegrityViolationException> {
            peopleRepository.saveAndFlush(Person(name = "Ana Again", email = "ANA@ogrodje.si"))
        }
    }

    @Test
    fun `allows many people without email`() {
        peopleMCP.create(name = "First Guest", email = null)
        peopleMCP.create(name = "Second Guest", email = null)
    }

    @Test
    fun `sets and clears a person's bio and links, only to the right sites`() {
        val person = peopleMCP.create(name = "Andraž Vrhovec", email = null).id!!

        peopleMCP.setBio(person, "  CTO @ Koofr ")
        peopleMCP.setLink(person, SocialNetwork.HOMEPAGE, "http://andraz.example")
        peopleMCP.setLink(person, SocialNetwork.LINKEDIN, "https://si.linkedin.com/in/andraz")
        peopleMCP.setLink(person, SocialNetwork.TWITTER, "https://x.com/andraz")
        peopleMCP.setLink(person, SocialNetwork.INSTAGRAM, "https://www.instagram.com/andraz")
        val linked = peopleMCP.setLink(person, SocialNetwork.FACEBOOK, "https://www.facebook.com/andraz")
        assertEquals(
            PersonDto(
                person, "Andraž Vrhovec", null, "CTO @ Koofr", "http://andraz.example", "https://si.linkedin.com/in/andraz",
                "https://x.com/andraz", "https://www.instagram.com/andraz", "https://www.facebook.com/andraz",
            ),
            linked,
        )
        assertEquals(linked, peopleMCP.list().single { it.id == person })

        assertFailsWith<IllegalArgumentException> { peopleMCP.setLink(person, SocialNetwork.LINKEDIN, "http://www.linkedin.com/in/andraz") }
        assertFailsWith<IllegalArgumentException> { peopleMCP.setLink(person, SocialNetwork.TWITTER, "https://notlinkedin.com/andraz") }
        assertFailsWith<IllegalArgumentException> { peopleMCP.setLink(person, SocialNetwork.TWITTER, "andraz") }
        assertFailsWith<IllegalArgumentException> { peopleMCP.setLink(person, SocialNetwork.HOMEPAGE, "ftp://andraz.example") }
        assertFailsWith<IllegalArgumentException> { peopleMCP.setBio(UUID.randomUUID(), "Nobody") }

        peopleMCP.setBio(person, "")
        val cleared = peopleMCP.setLink(person, SocialNetwork.TWITTER, null)
        assertEquals(null to null, cleared.bio to cleared.twitterUrl)
        assertEquals("https://www.facebook.com/andraz", cleared.facebookUrl)
    }

    @Test
    fun `gives a person several roles on an episode, each once`() {
        episodesRepository.saveAndFlush(Episode(title = "Roles", code = "ep-401"))
        val person = peopleMCP.create(name = "Host Person", email = null)
        val guest = peopleMCP.create(name = "A Guest", email = null)

        episodesMCP.assignPerson(code = "ep-401", personId = guest.id!!, role = Role.GUEST)
        episodesMCP.assignPerson(code = "ep-401", personId = person.id!!, role = Role.PRODUCER)
        val assigned = episodesMCP.assignPerson(code = "EP-401", personId = person.id, role = Role.HOST)
        episodesMCP.assignPerson(code = "ep-401", personId = person.id, role = Role.HOST)

        assertEquals(listOf(Role.HOST, Role.PRODUCER), assigned.roles)
        assertEquals(
            listOf("Host Person" to listOf(Role.HOST, Role.PRODUCER), "A Guest" to listOf(Role.GUEST)),
            episodesMCP.listPeople("ep-401").map { it.name to it.roles },
        )
    }

    @Test
    fun `removes a person from an episode`() {
        episodesRepository.saveAndFlush(Episode(title = "Panel", code = "ep-1206"))
        val stays = peopleMCP.create(name = "Stays", email = null)
        val leaves = peopleMCP.create(name = "Leaves", email = null)
        episodesMCP.assignPerson(code = "ep-1206", personId = stays.id!!, role = Role.HOST)
        episodesMCP.assignPerson(code = "ep-1206", personId = stays.id, role = Role.DESIGNER)
        episodesMCP.assignPerson(code = "ep-1206", personId = leaves.id!!, role = Role.GUEST)
        episodesMCP.assignPerson(code = "ep-1206", personId = leaves.id, role = Role.CONSULTANT)

        assertEquals(listOf(Role.HOST), episodesMCP.removePerson("ep-1206", stays.id, Role.DESIGNER).roles)
        assertTrue(episodesMCP.removePerson("EP-1206", leaves.id, role = null).roles.isEmpty())

        assertEquals(listOf("Stays" to listOf(Role.HOST)), episodesMCP.listPeople("ep-1206").map { it.name to it.roles })
        assertFailsWith<IllegalArgumentException> { episodesMCP.removePerson("ep-1206", leaves.id, role = null) }
        assertFailsWith<IllegalArgumentException> { episodesMCP.removePerson("ep-1206", stays.id, Role.GUEST) }
    }

    @Test
    fun `soft-deleted people drop off episodes`() {
        episodesRepository.saveAndFlush(Episode(title = "Guests", code = "ep-501"))
        val person = peopleMCP.create(name = "Guest Person", email = null)
        episodesMCP.assignPerson(code = "ep-501", personId = person.id!!, role = Role.GUEST)

        peopleRepository.deleteById(person.id)

        assertTrue(episodesMCP.listPeople("ep-501").isEmpty())
        assertTrue(peopleMCP.list().none { it.id == person.id })
    }

    @Test
    fun `lists a person's episodes once each, latest first, with all their roles, without soft-deleted episodes`() {
        episodesRepository.saveAndFlush(Episode(title = "Second", code = "ep-602"))
        episodesRepository.saveAndFlush(Episode(title = "First", code = "ep-601"))
        val gone = episodesRepository.saveAndFlush(Episode(title = "Gone", code = "ep-603"))
        val person = peopleMCP.create(name = "Busy Person", email = null)
        episodesMCP.assignPerson(code = "ep-602", personId = person.id!!, role = Role.GUEST)
        episodesMCP.assignPerson(code = "ep-601", personId = person.id, role = Role.PRODUCER)
        episodesMCP.assignPerson(code = "ep-601", personId = person.id, role = Role.HOST)
        episodesMCP.assignPerson(code = "ep-603", personId = person.id, role = Role.CO_HOST)

        episodesRepository.delete(gone)

        assertEquals(
            listOf(
                PersonEpisodeDto(null, "ep-602", "Second", listOf(Role.GUEST)),
                PersonEpisodeDto(null, "ep-601", "First", listOf(Role.HOST, Role.PRODUCER)),
            ),
            peopleMCP.listEpisodes(person.id).map { it.copy(episodeId = null) },
        )
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun signInAs(email: String, verified: Boolean = true) {
        val jwt = Jwt.withTokenValue("token").header("alg", "none")
            .claim("preferred_username", "user").claim("email", email).claim("email_verified", verified)
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `links the signed-in user to a person by verified email`() {
        val person = peopleMCP.create(name = "Linked Person", email = null)
        signInAs(ACCOUNT_LINKER)
        peopleMCP.setEmail(person.id!!, "linked@ogrodje.si")

        signInAs("LINKED@ogrodje.si")
        assertEquals(person.copy(email = "linked@ogrodje.si"), peopleMCP.whoami().person)

        signInAs("linked@ogrodje.si", verified = false)
        assertNull(peopleMCP.whoami().person)

        signInAs(ACCOUNT_LINKER)
        peopleMCP.setEmail(person.id, null)
        signInAs("linked@ogrodje.si")
        assertNull(peopleMCP.whoami().person)
    }

    @Test
    fun `lists the signed-in user's episodes`() {
        episodesRepository.saveAndFlush(Episode(title = "Mine", code = "ep-701"))
        episodesRepository.saveAndFlush(Episode(title = "Not mine", code = "ep-702"))
        signInAs(ACCOUNT_LINKER)
        val me = peopleMCP.create(name = "Me", email = "me@ogrodje.si")
        val other = peopleMCP.create(name = "Someone Else", email = null)
        episodesMCP.assignPerson(code = "ep-701", personId = me.id!!, role = Role.CO_HOST)
        episodesMCP.assignPerson(code = "ep-702", personId = other.id!!, role = Role.HOST)

        signInAs("me@ogrodje.si")

        assertEquals(listOf("ep-701" to listOf(Role.CO_HOST)), peopleMCP.myEpisodes().map { it.code to it.roles })
    }

    @Test
    fun `my episodes needs a linked person`() {
        signInAs("nobody@ogrodje.si")
        assertFailsWith<IllegalStateException> { peopleMCP.myEpisodes() }

        signInAs(ACCOUNT_LINKER)
        peopleMCP.create(name = "Unverified", email = "me-too@ogrodje.si")
        signInAs("me-too@ogrodje.si", verified = false)
        assertFailsWith<IllegalStateException> { peopleMCP.myEpisodes() }
    }

    @Test
    fun `rejects an email another person already has`() {
        signInAs(ACCOUNT_LINKER)
        peopleMCP.create(name = "Owner", email = "taken@ogrodje.si")
        val other = peopleMCP.create(name = "Other", email = null)

        assertFailsWith<IllegalArgumentException> { peopleMCP.setEmail(other.id!!, "TAKEN@ogrodje.si") }
    }

    @Test
    fun `only ACCOUNT_LINKER gives a person an email, as it decides whose account is theirs`() {
        val person = peopleMCP.create(name = "Someone", email = null)
        signInAs("not-mine@ogrodje.si")
        assertFailsWith<AccessDeniedException> { peopleMCP.setEmail(person.id!!, "not-mine@ogrodje.si") }
        assertFailsWith<AccessDeniedException> { peopleMCP.create(name = "Not Me", email = "not-mine@ogrodje.si") }
        // Not with that email unverified either
        signInAs(ACCOUNT_LINKER, verified = false)
        assertFailsWith<AccessDeniedException> { peopleMCP.setEmail(person.id!!, "not-mine@ogrodje.si") }
        assertNull(peopleRepository.findByEmail("not-mine@ogrodje.si"))
        // Nor by restoring someone under it
        signInAs(ACCOUNT_LINKER)
        peopleMCP.delete(person.id!!)
        signInAs("not-mine@ogrodje.si")
        assertFailsWith<AccessDeniedException> { peopleMCP.restore(person.id, "not-mine@ogrodje.si") }
    }

    @Test
    fun `rejects unknown episode or person`() {
        val person = peopleMCP.create(name = "Lost Person", email = null)

        assertFailsWith<IllegalArgumentException> {
            episodesMCP.assignPerson(code = "nope", personId = person.id!!, role = Role.GUEST)
        }
        assertFailsWith<IllegalArgumentException> { peopleMCP.listEpisodes(UUID.randomUUID()) }
    }

    @Test
    fun `deletes and restores a person with their roles`() {
        signInAs(ACCOUNT_LINKER)
        episodesRepository.saveAndFlush(Episode(title = "Restored roles", code = "ep-1601"))
        val person = peopleMCP.create(name = "Comeback Guest", email = "comeback@ogrodje.si")
        episodesMCP.assignPerson(code = "ep-1601", personId = person.id!!, role = Role.GUEST)

        assertEquals(person, peopleMCP.delete(person.id))
        assertTrue(episodesMCP.listPeople("ep-1601").isEmpty())
        assertTrue(peopleMCP.list().none { it.id == person.id })

        assertEquals(person, peopleMCP.restore(person.id))
        assertEquals(listOf("Comeback Guest" to listOf(Role.GUEST)), episodesMCP.listPeople("ep-1601").map { it.name to it.roles })
        assertFailsWith<IllegalArgumentException> { peopleMCP.restore(person.id) }
        assertFailsWith<IllegalArgumentException> { peopleMCP.restore(UUID.randomUUID()) }
        assertNull(peopleMCP.delete(UUID.randomUUID()))
    }

    @Test
    fun `does not restore a person whose email is taken again`() {
        signInAs(ACCOUNT_LINKER)
        val gone = peopleMCP.create(name = "Gone", email = "taken-again@ogrodje.si")
        peopleMCP.delete(gone.id!!)
        peopleMCP.create(name = "New Owner", email = "taken-again@ogrodje.si")

        assertFailsWith<IllegalArgumentException> { peopleMCP.restore(gone.id) }
    }

    @Test
    fun `restores a person with another email, or none, when theirs has been taken since`() {
        signInAs(ACCOUNT_LINKER)
        val first = peopleMCP.create(name = "First", email = "reclaimed@ogrodje.si")
        val second = peopleMCP.create(name = "Second", email = "reclaimed-too@ogrodje.si")
        peopleMCP.delete(first.id!!)
        peopleMCP.delete(second.id!!)
        peopleMCP.create(name = "Claimant", email = "reclaimed@ogrodje.si")
        peopleMCP.create(name = "Another Claimant", email = "reclaimed-too@ogrodje.si")

        assertFailsWith<IllegalArgumentException> { peopleMCP.restore(first.id, newEmail = "RECLAIMED@ogrodje.si") }
        assertEquals("first@ogrodje.si", peopleMCP.restore(first.id, newEmail = " first@ogrodje.si ").email)
        assertNull(peopleMCP.restore(second.id, newEmail = "").email)
    }
}
