package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import si.ogrodje.metaos.EpisodeStatus.*
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class EpisodeStatusTest @Autowired constructor(
    private val episodesRepository: EpisodesRepository,
    private val episodesMCP: EpisodesMCP,
    private val peopleMCP: PeopleMCP,
) {

    private fun assertNeeds(vararg needs: String, block: () -> Unit) {
        val message = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()
        needs.forEach { assertContains(message, it) }
    }

    @Test
    fun `takes an idea without a code all the way to published`() {
        val idea = episodesMCP.create(code = null, title = "Big idea")
        assertEquals(Triple(IDEA, null, LocalDate.now()), Triple(idea.status, idea.code, idea.addedDate))
        val id = idea.id.toString()

        assertNeeds("a code", "a host") { episodesMCP.setStatus(id, PLANNED) }
        episodesMCP.update(id, newCode = "ep-1801", title = null)
        val host = peopleMCP.create(name = "Lifecycle Host", email = null)
        episodesMCP.assignPerson("ep-1801", host.id!!, Role.HOST)
        episodesMCP.setStatus(id, PLANNED)

        assertNeeds("a recording date") { episodesMCP.setStatus("ep-1801", SCHEDULED) }
        episodesMCP.update("ep-1801", newCode = null, title = null, recordingDate = LocalDate.now().plusDays(7))
        episodesMCP.setStatus("ep-1801", SCHEDULED)

        assertNeeds("not in the future") { episodesMCP.setStatus("ep-1801", RECORDED) }
        episodesMCP.update("ep-1801", newCode = null, title = null, recordingDate = LocalDate.now())
        episodesMCP.setStatus("ep-1801", RECORDED)

        assertNeeds("a producer") { episodesMCP.setStatus("ep-1801", IN_PRODUCTION) }
        episodesMCP.assignPerson("ep-1801", host.id, Role.PRODUCER)
        episodesMCP.setStatus("ep-1801", IN_PRODUCTION)

        assertNeeds("a summary") { episodesMCP.setStatus("ep-1801", READY) }
        episodesMCP.update("ep-1801", newCode = null, title = null, summary = "What it is about")
        episodesMCP.setStatus("ep-1801", READY)

        assertNeeds("a Patreon link") { episodesMCP.setStatus("ep-1801", EARLY_ACCESS) }
        assertNeeds("a YouTube, Spotify or Apple Podcasts link") { episodesMCP.setStatus("ep-1801", PUBLISHED) }
        episodesMCP.setLink("ep-1801", Platform.YOUTUBE, "https://youtu.be/lifecycle")
        val published = episodesMCP.setStatus("ep-1801", PUBLISHED)

        assertEquals(PUBLISHED to LocalDate.now(), published.status to published.airedDate)
        assertFailsWith<IllegalArgumentException> { episodesMCP.setStatus("ep-1801", ON_HOLD) }
    }

    @Test
    fun `moves one step forward or back, with hold and cancel on the side`() {
        val episode = episodesMCP.create(code = "ep-1802", title = "Wandering")
        episodesMCP.assignPerson("ep-1802", peopleMCP.create(name = "Wandering Host", email = null).id!!, Role.HOST)

        assertFailsWith<IllegalArgumentException> { episodesMCP.setStatus("ep-1802", SCHEDULED) }
        episodesMCP.setStatus("ep-1802", PLANNED)
        episodesMCP.setStatus("ep-1802", IDEA)
        episodesMCP.setStatus("ep-1802", ON_HOLD)
        assertFailsWith<IllegalArgumentException> { episodesMCP.setStatus("ep-1802", PUBLISHED) }
        episodesMCP.setStatus("ep-1802", PLANNED)
        episodesMCP.setStatus("ep-1802", CANCELLED)
        assertFailsWith<IllegalArgumentException> { episodesMCP.setStatus("ep-1802", PLANNED) }

        assertEquals(IDEA, episodesMCP.setStatus(episode.id.toString(), IDEA).status)
    }

    @Test
    fun `lists episodes with a given status, ideas without a code last`() {
        episodesMCP.create(code = null, title = "Codeless idea")
        episodesMCP.create(code = "ep-1803", title = "Coded idea")

        val ideas = episodesMCP.list(IDEA)
        assertTrue(ideas.all { it.status == IDEA })
        assertTrue(ideas.indexOfFirst { it.code == "ep-1803" } < ideas.indexOfFirst { it.title == "Codeless idea" })
        assertTrue(episodesMCP.list(PUBLISHED).none { it.code == "ep-1803" })
        assertNull(episodesMCP.list().last().code)
    }

    @Test
    fun `only ideas, and episodes on hold or cancelled, can do without a code`() {
        assertFailsWith<DataIntegrityViolationException> {
            episodesRepository.saveAndFlush(Episode(title = "Planned without code", code = null).apply { status = PLANNED })
        }
        episodesRepository.saveAndFlush(Episode(title = "On hold without code", code = null).apply { status = ON_HOLD })
    }

    @Test
    fun `deletes and restores an idea without a code by its ID`() {
        val id = episodesMCP.create(code = null, title = "Fleeting idea").id.toString()

        episodesMCP.delete(id)
        assertNull(episodesMCP.get(id))

        assertEquals("Fleeting idea", episodesMCP.restore(id).title)
        assertNotNull(episodesMCP.get(id))
        assertFailsWith<IllegalArgumentException> { episodesMCP.restore(id) }
    }

    @Test
    fun `sets the recording, aired and added dates`() {
        episodesMCP.create(code = "ep-1804", title = "Dated")

        val dated = episodesMCP.update(
            "ep-1804", newCode = null, title = null,
            recordingDate = LocalDate.of(2026, 9, 1), airedDate = LocalDate.of(2026, 9, 15), addedDate = LocalDate.of(2026, 8, 1),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 8, 1)),
            listOf(dated.recordingDate, dated.airedDate, dated.addedDate),
        )
    }
}
