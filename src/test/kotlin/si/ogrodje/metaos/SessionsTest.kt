package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
class SessionsTest @Autowired constructor(
    private val episodesRepository: EpisodesRepository,
    private val sessionsRepository: SessionsRepository,
    private val episodesMCP: EpisodesMCP,
    private val sessionsMCP: SessionsMCP,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `lists sessions newest first`() {
        val older = sessionsMCP.create(
            LocalDate.of(2026, 3, 1), location = "Kino Šiška", address = "Trg prekomorskih brigad 3, Ljubljana"
        )
        val newer = sessionsMCP.create(LocalDate.of(2026, 9, 15), location = null, address = null)

        assertEquals(listOf(newer, older), sessionsMCP.list().filter { it.id in setOf(older.id, newer.id) })
    }

    @Test
    fun `moves episodes between sessions and out of them`() {
        val first = sessionsMCP.create(LocalDate.of(2026, 10, 1), location = "Studio", address = null)
        val second = sessionsMCP.create(LocalDate.of(2026, 10, 8), location = "Studio", address = null)
        episodesRepository.saveAndFlush(Episode(title = "Two", code = "ep-802"))
        episodesRepository.saveAndFlush(Episode(title = "One", code = "ep-801"))

        sessionsMCP.setEpisodeSession(code = "ep-802", sessionId = first.id)
        sessionsMCP.setEpisodeSession(code = "ep-801", sessionId = first.id)
        assertEquals(listOf("ep-802", "ep-801"), sessionsMCP.listEpisodes(first.id!!).map { it.code })

        sessionsMCP.setEpisodeSession(code = "EP-802", sessionId = second.id)
        assertEquals(listOf("ep-801"), sessionsMCP.listEpisodes(first.id).map { it.code })
        assertEquals(second.id, episodesMCP.get("ep-802")?.sessionId)

        sessionsMCP.setEpisodeSession(code = "ep-801", sessionId = null)
        assertTrue(sessionsMCP.listEpisodes(first.id).isEmpty())
        assertNull(episodesMCP.get("ep-801")?.sessionId)
    }

    @Test
    fun `updates only the given session fields`() {
        val session = sessionsMCP.create(LocalDate.of(2026, 4, 1), location = "Studio", address = "Old street 1")

        assertEquals(
            session.copy(date = LocalDate.of(2026, 4, 2)),
            sessionsMCP.update(session.id!!, date = LocalDate.of(2026, 4, 2), location = null, address = null),
        )
        assertEquals(
            session.copy(date = LocalDate.of(2026, 4, 2), location = null, address = "New street 2"),
            sessionsMCP.update(session.id, date = null, location = "", address = "New street 2"),
        )
        val stored = sessionsMCP.list().single { it.id == session.id }
        assertEquals(null to "New street 2", stored.location to stored.address)
    }

    @Test
    fun `names a session, renames it and clears its name`() {
        val session = sessionsMCP.create(
            LocalDate.of(2026, 4, 9), name = "  AWS Community Day Adria ", location = "Kino Šiška", address = null
        )
        assertEquals("AWS Community Day Adria", session.name)
        assertEquals("AWS Community Day Adria", jdbcTemplate.queryForObject<String>("select name from sessions where id = ?", session.id))

        // Left out, it stays; given, it's the new name; empty, it's gone, and the session goes by its location again
        assertEquals(session, sessionsMCP.update(session.id!!, date = null, location = null, address = null))
        assertEquals(
            session.copy(name = "AWS Community Day Adria 2026"),
            sessionsMCP.update(session.id, date = null, name = "AWS Community Day Adria 2026", location = null, address = null),
        )
        assertEquals(
            session.copy(name = null),
            sessionsMCP.update(session.id, date = null, name = " ", location = null, address = null),
        )
        assertNull(sessionsMCP.create(LocalDate.of(2026, 4, 10), location = "Studio", address = null).name)
    }

    @Test
    fun `deletes a session but keeps its episodes`() {
        val session = sessionsMCP.create(LocalDate.of(2026, 5, 1), location = null, address = null)
        episodesRepository.saveAndFlush(Episode(title = "Kept", code = "ep-1101"))
        sessionsMCP.setEpisodeSession(code = "ep-1101", sessionId = session.id)

        assertEquals(session, sessionsMCP.delete(session.id!!))

        assertTrue(sessionsMCP.list().none { it.id == session.id })
        assertEquals("Kept", episodesMCP.get("ep-1101")?.title)
        assertNull(jdbcTemplate.queryForObject<UUID>("select session_id from episodes where code = ?", "ep-1101"))
        assertNull(sessionsMCP.delete(session.id))
    }

    @Test
    fun `soft-deleted sessions drop off their episodes`() {
        val session = sessionsMCP.create(LocalDate.of(2026, 11, 5), location = null, address = null)
        episodesRepository.saveAndFlush(Episode(title = "Left behind", code = "ep-901"))
        sessionsMCP.setEpisodeSession(code = "ep-901", sessionId = session.id)

        sessionsRepository.deleteById(session.id!!)

        assertNull(episodesMCP.get("ep-901")?.sessionId)
        assertTrue(sessionsMCP.list().none { it.id == session.id })
    }

    @Test
    fun `rejects unknown episode or session`() {
        val session = sessionsMCP.create(LocalDate.of(2026, 12, 24), location = null, address = null)
        episodesRepository.saveAndFlush(Episode(title = "Lonely", code = "ep-1001"))

        assertFailsWith<IllegalArgumentException> { sessionsMCP.setEpisodeSession(code = "nope", sessionId = session.id) }
        assertFailsWith<IllegalArgumentException> { sessionsMCP.setEpisodeSession(code = "ep-1001", sessionId = UUID.randomUUID()) }
        assertFailsWith<IllegalArgumentException> { sessionsMCP.listEpisodes(UUID.randomUUID()) }
        assertFailsWith<IllegalArgumentException> {
            sessionsMCP.update(UUID.randomUUID(), date = LocalDate.of(2027, 1, 1), location = null, address = null)
        }
    }

    @Test
    fun `restores a session with the episodes deleting it took out, unless they moved since`() {
        val session = sessionsMCP.create(LocalDate.of(2026, 7, 1), location = "Studio", address = null)
        val other = sessionsMCP.create(LocalDate.of(2026, 7, 2), location = null, address = null)
        episodesRepository.saveAndFlush(Episode(title = "Comes back", code = "ep-1602"))
        episodesRepository.saveAndFlush(Episode(title = "Moves on", code = "ep-1603"))
        sessionsMCP.setEpisodeSession(code = "ep-1602", sessionId = session.id!!)
        sessionsMCP.setEpisodeSession(code = "ep-1603", sessionId = session.id)

        sessionsMCP.delete(session.id)
        sessionsMCP.setEpisodeSession(code = "ep-1603", sessionId = other.id!!)

        assertEquals(session, sessionsMCP.restore(session.id))
        assertEquals(listOf("ep-1602"), sessionsMCP.listEpisodes(session.id).map { it.code })
        assertEquals(other.id, episodesMCP.get("ep-1603")?.sessionId)
        assertFailsWith<IllegalArgumentException> { sessionsMCP.restore(session.id) }
        assertFailsWith<IllegalArgumentException> { sessionsMCP.restore(UUID.randomUUID()) }
    }
}
