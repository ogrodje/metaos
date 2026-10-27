package si.ogrodje.metaos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class AuditTest @Autowired constructor(
    private val episodesRepository: EpisodesRepository,
    private val episodesMCP: EpisodesMCP,
    private val peopleMCP: PeopleMCP,
    private val sessionsMCP: SessionsMCP,
    private val showsMCP: ShowsMCP,
    private val auditMCP: AuditMCP,
    private val jdbcTemplate: JdbcTemplate,
) {

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun signInAs(username: String) {
        val jwt = Jwt.withTokenValue("token").header("alg", "none")
            .subject("$username-id").claim("preferred_username", username).claim("azp", "claude-code")
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `records who changed what, when and through which tool`() {
        signInAs("ana")
        val session = sessionsMCP.create(LocalDate.of(2026, 6, 1), location = "Studio", address = null)
        sessionsMCP.update(session.id!!, date = null, location = "Kino Šiška", address = null)

        val history = auditMCP.history(session.id)

        assertEquals(listOf("INSERT" to "create_session", "UPDATE" to "update_session"), history.map { it.operation to it.tool })
        assertTrue(history.all { it.table == "sessions" && it.actor == "ana" && it.actorId == "ana-id" && it.client == "claude-code" })
        assertEquals(
            mapOf("date" to ColumnChange(null, "2026-06-01"), "location" to ColumnChange(null, "Studio")),
            history[0].changes,
        )
        assertEquals(mapOf("location" to ColumnChange("Studio", "Kino Šiška")), history[1].changes)
    }

    @Test
    fun `changes outside a signed-in tool call have no actor or tool`() {
        signInAs("bojan")
        episodesMCP.create(code = "ep-1301", title = "Signed in")
        signOut()
        val episode = episodesRepository.saveAndFlush(Episode(title = "Anonymous", code = "ep-1302"))

        val change = auditMCP.history(episode.id!!).single()

        assertEquals(listOf(null, null, null, null), listOf(change.actor, change.actorId, change.client, change.tool))
    }

    @Test
    fun `session history shows episodes moving in and out and the soft delete`() {
        signInAs("cene")
        val session = sessionsMCP.create(LocalDate.of(2026, 6, 2), location = null, address = null)
        episodesMCP.create(code = "ep-1303", title = "Moved")
        sessionsMCP.setEpisodeSession(code = "ep-1303", sessionId = session.id)
        sessionsMCP.delete(session.id!!)

        val history = auditMCP.history(session.id)

        assertEquals(
            listOf(
                "sessions" to "create_session",
                "episodes" to "set_episode_session",
                "episodes" to "delete_session",
                "sessions" to "delete_session",
            ),
            history.map { it.table to it.tool },
        )
        assertEquals(mapOf("session_id" to ColumnChange(session.id.toString(), null)), history[2].changes)
        assertEquals(setOf("deleted_at"), history[3].changes.keys)
        assertNull(history[3].changes.getValue("deleted_at").from)
    }

    @Test
    fun `show history shows episodes moving in and out`() {
        signInAs("cene")
        val show = showsMCP.create(name = "Audited Show", color = null)
        episodesMCP.create(code = "ep-1305", title = "Moved")
        showsMCP.setEpisodeShow(code = "ep-1305", show = "Audited Show")
        showsMCP.setEpisodeShow(code = "ep-1305", show = null)

        assertEquals(
            listOf(
                "shows" to "create_show",
                "episodes" to "set_episode_show",
                "episodes" to "set_episode_show",
            ),
            auditMCP.history(show.id!!).map { it.table to it.tool },
        )
    }

    @Test
    fun `roles show up in both the episode's and the person's history`() {
        signInAs("dora")
        val episode = episodesMCP.create(code = "ep-1304", title = "Roles")
        val person = peopleMCP.create(name = "Guest", email = null)
        episodesMCP.assignPerson(code = "ep-1304", personId = person.id!!, role = Role.GUEST)
        episodesMCP.assignPerson(code = "ep-1304", personId = person.id, role = Role.CO_HOST)
        episodesMCP.removePerson(code = "ep-1304", personId = person.id, role = Role.GUEST)

        val expected = listOf(ColumnChange(null, "GUEST"), ColumnChange(null, "CO_HOST"), ColumnChange("GUEST", null))
        for (id in listOf(episode.id!!, person.id)) {
            assertEquals(expected, auditMCP.history(id).filter { it.table == "episode_people" }.map { it.changes["role"] })
        }
    }

    @Test
    fun `skips updates that change nothing`() {
        val session = sessionsMCP.create(LocalDate.of(2026, 6, 3), location = "Studio", address = null)

        jdbcTemplate.update("update sessions set location = location where id = ?", session.id)

        assertEquals(listOf("INSERT"), auditMCP.history(session.id!!).map { it.operation })
    }

    @Test
    fun `lists recent changes newest first`() {
        val first = sessionsMCP.create(LocalDate.of(2026, 6, 4), location = null, address = null)
        val second = sessionsMCP.create(LocalDate.of(2026, 6, 5), location = null, address = null)

        assertEquals(listOf(second.id, first.id), auditMCP.recentChanges(limit = 2).map { it.rowId })
        assertTrue(auditMCP.history(UUID.randomUUID()).isEmpty())
    }
}
