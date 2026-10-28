package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class ShowsTest @Autowired constructor(
    private val showsRepository: ShowsRepository,
    private val episodesMCP: EpisodesMCP,
    private val showsMCP: ShowsMCP,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `creates, renames and recolors shows`() {
        val created = showsMCP.create(name = " Zagon Test ", color = "#0404a4")
        assertEquals(ShowDto(created.id, "Zagon Test", "#0404a4", "sl-SI"), created)

        showsMCP.update(name = "ZAGON TEST", newName = "Start Test", color = null)
        assertEquals(ShowDto(created.id, "Start Test", "#0404a4", "sl-SI"), showsMCP.get("start test"))

        showsMCP.update(name = "Start Test", newName = null, color = "")
        assertNull(showsMCP.get("Start Test")?.color)
        assertNull(showsMCP.get("Zagon Test"))
        assertEquals(listOf("Start Test"), showsMCP.list().map { it.name }.filter { it.endsWith(" Test") })
    }

    @Test
    fun `rejects duplicate names regardless of case and colors that are not hex`() {
        showsMCP.create(name = "Dupe Show", color = null)

        assertFailsWith<IllegalArgumentException> { showsMCP.create(name = "DUPE SHOW", color = null) }
        assertFailsWith<DataIntegrityViolationException> { showsRepository.saveAndFlush(Show(name = "dupe show", color = null)) }
        assertFailsWith<IllegalArgumentException> { showsMCP.create(name = "Blue Show", color = "blue") }
        assertFailsWith<DataIntegrityViolationException> { showsRepository.saveAndFlush(Show(name = "Blue Show", color = "blue")) }
        assertFailsWith<IllegalArgumentException> { showsMCP.update(name = "Dupe Show", newName = " ", color = null) }
    }

    @Test
    fun `moves episodes between shows and out of them`() {
        showsMCP.create(name = "First Show", color = null)
        showsMCP.create(name = "Second Show", color = null)
        episodesMCP.create(code = "ep-1402", title = "Two")
        episodesMCP.create(code = "ep-1401", title = "One")

        showsMCP.setEpisodeShow(code = "ep-1402", show = "first show")
        showsMCP.setEpisodeShow(code = "ep-1401", show = "First Show")
        assertEquals(listOf("ep-1402", "ep-1401"), showsMCP.listEpisodes("First Show").map { it.code })

        showsMCP.setEpisodeShow(code = "EP-1402", show = "Second Show")
        assertEquals(listOf("ep-1401"), showsMCP.listEpisodes("First Show").map { it.code })
        assertEquals("Second Show", episodesMCP.get("ep-1402")?.show)

        showsMCP.setEpisodeShow(code = "ep-1401", show = null)
        assertTrue(showsMCP.listEpisodes("First Show").isEmpty())
        assertNull(episodesMCP.get("ep-1401")?.show)
    }

    @Test
    fun `deletes a show but keeps its episodes and frees its name`() {
        val show = showsMCP.create(name = "Gone Show", color = null)
        episodesMCP.create(code = "ep-1403", title = "Kept")
        showsMCP.setEpisodeShow(code = "ep-1403", show = "Gone Show")

        assertEquals(show, showsMCP.delete("gone show"))

        assertNull(showsMCP.get("Gone Show"))
        assertEquals("Kept", episodesMCP.get("ep-1403")?.title)
        assertNull(episodesMCP.get("ep-1403")?.show)
        assertNull(jdbcTemplate.queryForObject<UUID>("select show_id from episodes where code = ?", "ep-1403"))
        assertNull(showsMCP.delete("Gone Show"))

        showsMCP.create(name = "Gone Show", color = null)
    }

    @Test
    fun `rejects unknown show or episode`() {
        showsMCP.create(name = "Known Show", color = null)
        episodesMCP.create(code = "ep-1404", title = "Lonely")

        assertFailsWith<IllegalArgumentException> { showsMCP.setEpisodeShow(code = "nope", show = "Known Show") }
        assertFailsWith<IllegalArgumentException> { showsMCP.setEpisodeShow(code = "ep-1404", show = "Unknown Show") }
        assertFailsWith<IllegalArgumentException> { showsMCP.listEpisodes("Unknown Show") }
        assertFailsWith<IllegalArgumentException> { showsMCP.update(name = "Unknown Show", newName = "X", color = null) }
    }

    @Test
    fun `restores a show with the episodes deleting it took out, unless they moved since`() {
        showsMCP.create(name = "Restorable Show", color = null)
        showsMCP.create(name = "Elsewhere Show", color = null)
        episodesMCP.create(code = "ep-1604", title = "Comes back")
        episodesMCP.create(code = "ep-1605", title = "Moves on")
        showsMCP.setEpisodeShow(code = "ep-1604", show = "Restorable Show")
        showsMCP.setEpisodeShow(code = "ep-1605", show = "Restorable Show")

        showsMCP.delete("Restorable Show")
        showsMCP.setEpisodeShow(code = "ep-1605", show = "Elsewhere Show")

        assertEquals("Restorable Show", showsMCP.restore("RESTORABLE SHOW").name)
        assertEquals(listOf("ep-1604"), showsMCP.listEpisodes("Restorable Show").map { it.code })
        assertEquals("Elsewhere Show", episodesMCP.get("ep-1605")?.show)
        assertFailsWith<IllegalArgumentException> { showsMCP.restore("Restorable Show") }
        assertFailsWith<IllegalArgumentException> { showsMCP.restore("Never Existed Show") }
    }

    @Test
    fun `does not restore a show whose name is taken again`() {
        showsMCP.create(name = "Reused Show", color = null)
        showsMCP.delete("Reused Show")
        showsMCP.create(name = "Reused Show", color = "#000000")

        assertFailsWith<IllegalArgumentException> { showsMCP.restore("Reused Show") }
        assertEquals("#000000", showsMCP.get("Reused Show")?.color)
    }

    @Test
    fun `restores a show under a new name, when its own has been taken since`() {
        showsMCP.create(name = "Renamed Show", color = "#111111")
        showsMCP.delete("Renamed Show")
        showsMCP.create(name = "Renamed Show", color = "#222222")

        assertFailsWith<IllegalArgumentException> { showsMCP.restore("Renamed Show", newName = "renamed show") }
        val restored = showsMCP.restore("Renamed Show", newName = "Renamed Show (old)")

        assertEquals("Renamed Show (old)" to "#111111", restored.name to restored.color)
        assertEquals("#222222", showsMCP.get("Renamed Show")?.color)
    }
}
