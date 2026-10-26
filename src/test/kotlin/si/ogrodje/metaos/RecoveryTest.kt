package si.ogrodje.metaos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class RecoveryTest @Autowired constructor(
    private val episodesRepository: EpisodesRepository,
    private val episodeFolders: EpisodeKoofrFoldersRepository,
    private val sessions: SessionsRepository,
    private val sessionFolders: SessionKoofrFoldersRepository,
    private val episodesMCP: EpisodesMCP,
    private val peopleMCP: PeopleMCP,
    private val sessionsMCP: SessionsMCP,
    private val showsMCP: ShowsMCP,
    private val auditMCP: AuditMCP,
    private val recoveryMCP: RecoveryMCP,
) {

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    // Changes by someone signed in, so the audit log has who made them
    private fun signIn(username: String) {
        val jwt = Jwt.withTokenValue("token").header("alg", "none").subject("$username-id").claim("preferred_username", username).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    // The ID of the latest change that removed a link of the episode's, as get_history lists it
    private fun removal(episodeId: UUID, table: String) =
        auditMCP.history(episodeId).last { it.table == table && it.operation == "DELETE" }.id

    @Test
    fun `lists what was deleted, the most recently deleted first, with who deleted it, until it's restored`() {
        val episode = episodesMCP.create(code = "rc-1", title = "Recovery Episode")
        val person = peopleMCP.create(name = "Recovery Person", email = null)
        val session = sessionsMCP.create(date = LocalDate.of(2031, 2, 3), location = "Recovery Venue", address = null)
        val show = showsMCP.create(name = "Recovery Show", color = null)
        signIn("eraser")
        episodesMCP.delete("rc-1")
        peopleMCP.delete(person.id!!)
        sessionsMCP.delete(session.id!!)
        showsMCP.delete("Recovery Show")
        signOut()

        // Other tests' deleted ones are there too
        val ours = setOf(episode.id, person.id, session.id, show.id)
        val deleted = recoveryMCP.listDeleted().filter { it.id in ours }
        assertEquals(
            listOf(
                Triple(DeletedKind.SHOW, "Recovery Show", null),
                Triple(DeletedKind.SESSION, "2031-02-03 · Recovery Venue", null),
                Triple(DeletedKind.PERSON, "Recovery Person", null),
                Triple(DeletedKind.EPISODE, "Recovery Episode", "rc-1"),
            ),
            deleted.map { Triple(it.kind, it.name, it.code) },
        )
        assertTrue(deleted.all { it.deletedBy == "eraser" })
        assertEquals(listOf(person.id), recoveryMCP.listDeleted(DeletedKind.PERSON).map { it.id }.filter { it in ours })

        episodesMCP.restore("rc-1")
        assertTrue(recoveryMCP.listDeleted().none { it.id == episode.id })
    }

    @Test
    fun `brings back a removed link as it was, by the change that removed it`() {
        val episode = episodesMCP.create(code = "rc-2", title = "Recovery Links")
        val host = peopleMCP.create(name = "Recovery Host", email = null)
        episodesMCP.assignPerson("rc-2", host.id!!, Role.HOST)
        episodesMCP.removePerson("rc-2", host.id!!, null)

        val restored = recoveryMCP.restoreLink(removal(episode.id!!, "episode_people"))
        assertEquals("episode_people" to "HOST", restored.table to restored.link["role"])
        assertEquals(listOf("Recovery Host" to listOf(Role.HOST)), episodesMCP.listPeople("rc-2").map { it.name to it.roles })
        assertFailsWith<IllegalArgumentException> { recoveryMCP.restoreLink(removal(episode.id!!, "episode_people")) }

        // A folder, as unlink_episode_koofr_folder removes one
        val folder = episodeFolders.save(EpisodeKoofrFolder(checkNotNull(episodesRepository.findByCode("rc-2")), "place-rc", "/Recovered"))
        episodeFolders.delete(folder)
        recoveryMCP.restoreLink(removal(episode.id!!, "episode_koofr_folders"))
        assertEquals(listOf("/Recovered"), episodeFolders.findByEpisodeIds(listOf(episode.id!!)).map { it.path })
    }

    @Test
    fun `brings back a session's removed link, once the session is restored`() {
        val session = sessionsMCP.create(date = LocalDate.of(2031, 3, 4), location = "Recovery Studio", address = null)
        val folder = sessionFolders.save(SessionKoofrFolder(checkNotNull(sessions.findByIdOrNull(session.id!!)), "place-rc", "/Session"))
        sessionFolders.delete(folder)
        val removal = auditMCP.history(session.id!!).last { it.table == "session_koofr_folders" && it.operation == "DELETE" }.id
        sessionsMCP.delete(session.id!!)

        assertFailsWith<IllegalArgumentException> { recoveryMCP.restoreLink(removal) }
        sessionsMCP.restore(session.id!!)
        assertEquals("session_koofr_folders", recoveryMCP.restoreLink(removal).table)
        assertEquals(listOf("/Session"), sessionFolders.findBySessionIds(listOf(session.id!!)).map { it.path })
    }

    @Test
    fun `brings back a link only while what it linked is there, and not over one linked again`() {
        val episode = episodesMCP.create(code = "rc-3", title = "Recovery Relinked")
        val guest = peopleMCP.create(name = "Relinked Guest", email = null)
        val gone = peopleMCP.create(name = "Gone Guest", email = null)
        episodesMCP.assignPerson("rc-3", guest.id!!, Role.GUEST)
        episodesMCP.assignPerson("rc-3", gone.id!!, Role.GUEST)
        episodesMCP.removePerson("rc-3", guest.id!!, null)
        val guestRemoval = removal(episode.id!!, "episode_people")
        episodesMCP.removePerson("rc-3", gone.id!!, null)
        val goneRemoval = removal(episode.id!!, "episode_people")
        episodesMCP.assignPerson("rc-3", guest.id!!, Role.GUEST)
        peopleMCP.delete(gone.id!!)

        assertEquals("It has been linked again since", assertFailsWith<IllegalArgumentException> { recoveryMCP.restoreLink(guestRemoval) }.message)
        assertFailsWith<IllegalArgumentException> { recoveryMCP.restoreLink(goneRemoval) }
        peopleMCP.restore(gone.id!!)
        recoveryMCP.restoreLink(goneRemoval)
        assertEquals(setOf("Relinked Guest", "Gone Guest"), episodesMCP.listPeople("rc-3").map { it.name }.toSet())

        // Only removed links come back: not the episode, nor a change that's not there
        assertFailsWith<IllegalArgumentException> { recoveryMCP.restoreLink(auditMCP.history(episode.id!!).first().id) }
        assertFailsWith<IllegalArgumentException> { recoveryMCP.restoreLink(Long.MAX_VALUE) }
    }
}
