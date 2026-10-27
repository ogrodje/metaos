package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class EpisodesTest @Autowired constructor(
    private val episodesRepository: EpisodesRepository,
    private val episodesMCP: EpisodesMCP,
    private val peopleMCP: PeopleMCP,
    private val jdbcTemplate: JdbcTemplate,
    private val history: History,
) {

    @Test
    fun `restores a soft-deleted episode with its people and links`() {
        episodesRepository.saveAndFlush(Episode(title = "Comeback", code = "ep-1501"))
        val host = peopleMCP.create(name = "Comeback Host", email = null)
        episodesMCP.assignPerson(code = "ep-1501", personId = host.id!!, role = Role.HOST)
        episodesMCP.setLink("ep-1501", Platform.YOUTUBE, "https://youtu.be/abc")

        episodesMCP.delete("ep-1501")
        assertNull(episodesMCP.get("ep-1501"))

        val restored = episodesMCP.restore("EP-1501")

        assertEquals("ep-1501" to "https://youtu.be/abc", restored.code to restored.youtubeUrl)
        assertEquals(listOf("Comeback Host" to listOf(Role.HOST)), episodesMCP.listPeople("ep-1501").map { it.name to it.roles })
        assertFailsWith<IllegalArgumentException> { episodesMCP.restore("ep-1501") }
        assertFailsWith<IllegalArgumentException> { episodesMCP.restore("ep-1599") }
    }

    @Test
    fun `does not restore over a live episode with the same code`() {
        episodesRepository.saveAndFlush(Episode(title = "Old", code = "ep-1502"))
        episodesMCP.delete("ep-1502")
        episodesRepository.saveAndFlush(Episode(title = "New", code = "ep-1502"))

        assertFailsWith<IllegalArgumentException> { episodesMCP.restore("ep-1502") }
        assertEquals("New", episodesMCP.get("ep-1502")?.title)
    }

    @Test
    fun `restores an episode under a new code, when its own has been taken since`() {
        episodesRepository.saveAndFlush(Episode(title = "Older", code = "ep-1511"))
        episodesMCP.delete("ep-1511")
        episodesRepository.saveAndFlush(Episode(title = "Newer", code = "ep-1511"))
        episodesRepository.saveAndFlush(Episode(title = "Other", code = "ep-1513"))

        assertFailsWith<IllegalArgumentException> { episodesMCP.restore("ep-1511", newCode = "EP-1513") }
        assertFailsWith<IllegalArgumentException> { episodesMCP.restore("ep-1511", newCode = " ") }
        val restored = episodesMCP.restore("ep-1511", newCode = " ep-1512 ")

        assertEquals("ep-1512" to "Older", restored.code to restored.title)
        assertEquals("Newer", episodesMCP.get("ep-1511")?.title)
    }

    @Test
    fun `can save and find episode by code`() {
        val episode = Episode(title = "Test Episode", code = "ep-101")
        val saved = episodesRepository.saveAndFlush(episode)
        println("Saved episode ID: ${saved.id}, code: ${saved.code}")

        val foundById = episodesRepository.findById(saved.id!!)
        println("Found by ID: $foundById")

        val found = episodesRepository.findByCode("ep-101")
        println("Found by lowercase code: $found")

        assertNotNull(found)
        assertEquals("Test Episode", found.title)
    }

    @Test
    fun `rejects duplicate code regardless of case`() {
        episodesRepository.saveAndFlush(Episode(title = "Original", code = "ep-201"))

        assertFailsWith<DataIntegrityViolationException> {
            episodesRepository.saveAndFlush(Episode(title = "Duplicate", code = "EP-201"))
        }
    }

    @Test
    fun `soft deletes episode and frees its code`() {
        val saved = episodesRepository.saveAndFlush(Episode(title = "To delete", code = "ep-301"))

        episodesRepository.delete(saved)

        assertNull(episodesRepository.findByCode("ep-301"))
        assertTrue(episodesRepository.findAll().none { it.id == saved.id })
        assertEquals(
            true,
            jdbcTemplate.queryForObject<Boolean>(
                "select deleted_at is not null from episodes where id = ?", saved.id
            )
        )

        episodesRepository.saveAndFlush(Episode(title = "Recreated", code = "ep-301"))
        assertEquals("Recreated", episodesRepository.findByCode("ep-301")?.title)
    }

    @Test
    fun `updates an episode's code and title, leaving omitted fields as they are`() {
        episodesRepository.saveAndFlush(Episode(title = "Old title", code = "ep-1203"))
        episodesRepository.saveAndFlush(Episode(title = "Taken", code = "ep-1205"))

        assertEquals("New title", episodesMCP.update("EP-1203", newCode = null, title = "  New title ").title)
        val moved = episodesMCP.update("ep-1203", newCode = "ep-1204", title = null)

        assertEquals("ep-1204" to "New title", moved.code to moved.title)
        assertNull(episodesMCP.get("ep-1203"))
        assertFailsWith<IllegalArgumentException> { episodesMCP.update("ep-1204", newCode = "EP-1205", title = null) }
        assertFailsWith<IllegalArgumentException> { episodesMCP.update("ep-1204", newCode = " ", title = null) }
        assertFailsWith<IllegalArgumentException> { episodesMCP.update("ep-1204", newCode = null, title = " ") }
        assertFailsWith<IllegalArgumentException> { episodesMCP.update("nope", newCode = null, title = "Title") }
    }

    @Test
    fun `sets and clears episode links`() {
        episodesRepository.saveAndFlush(Episode(title = "Linked", code = "ep-1201"))

        episodesMCP.setLink("ep-1201", Platform.YOUTUBE, "https://youtu.be/abc")
        episodesMCP.setLink("EP-1201", Platform.APPLE_PODCASTS, "https://podcasts.apple.com/si/podcast/x/id1?i=2")
        episodesMCP.setLink("ep-1201", Platform.PATREON, "https://www.patreon.com/posts/x-1")
        episodesMCP.setLink("ep-1201", Platform.YOUTUBE, null)

        val episode = assertNotNull(episodesMCP.get("ep-1201"))
        assertEquals(
            listOf(null, null, "https://podcasts.apple.com/si/podcast/x/id1?i=2", "https://www.patreon.com/posts/x-1"),
            listOf(episode.youtubeUrl, episode.spotifyUrl, episode.applePodcastsUrl, episode.patreonUrl),
        )
    }

    @Test
    fun `sets and clears alternative titles, which only get_episode returns`() {
        episodesRepository.saveAndFlush(Episode(title = "Title A", code = "ep-1901"))

        val updated = episodesMCP.update("ep-1901", newCode = null, title = null, titleB = " Title B ", titleC = "Title C")
        assertEquals(Triple("Title A", "Title B", "Title C"), Triple(updated.title, updated.titleB, updated.titleC))

        episodesMCP.update("ep-1901", newCode = null, title = null, titleB = "", titleC = null)
        val episode = assertNotNull(episodesMCP.get("ep-1901"))
        assertEquals(null to "Title C", episode.titleB to episode.titleC)
    }

    @Test
    fun `sets and clears summaries, of which lists have only the tiny one`() {
        episodesRepository.saveAndFlush(Episode(title = "Summarized", code = "ep-1701"))

        val updated = episodesMCP.update(
            "ep-1701", newCode = null, title = null, summary = " **Bold** intro ", tinySummary = " In *short* ", machineSummary = "AI text",
        )
        assertEquals(
            listOf("Summarized", "**Bold** intro", "In *short*", "AI text"),
            listOf(updated.title, updated.summary, updated.tinySummary, updated.machineSummary),
        )

        episodesMCP.update("ep-1701", newCode = null, title = null, summary = "", tinySummary = null, machineSummary = null)
        val episode = assertNotNull(episodesMCP.get("ep-1701"))
        assertEquals(listOf(null, "In *short*", "AI text"), listOf(episode.summary, episode.tinySummary, episode.machineSummary))
        assertEquals("In *short*", episodesMCP.list().single { it.code == "ep-1701" }.tinySummary)

        episodesMCP.update("ep-1701", newCode = null, title = null, tinySummary = " ")
        assertNull(assertNotNull(episodesMCP.get("ep-1701")).tinySummary)
    }

    @Test
    fun `rejects links that are not https URLs of the platform`() {
        episodesRepository.saveAndFlush(Episode(title = "Wrong", code = "ep-1202"))

        assertFailsWith<IllegalArgumentException> { episodesMCP.setLink("ep-1202", Platform.SPOTIFY, "https://youtu.be/abc") }
        assertFailsWith<IllegalArgumentException> { episodesMCP.setLink("ep-1202", Platform.YOUTUBE, "http://youtu.be/abc") }
        assertFailsWith<IllegalArgumentException> { episodesMCP.setLink("ep-1202", Platform.YOUTUBE, "not a url") }
        assertFailsWith<IllegalArgumentException> { episodesMCP.setLink("ep-1202", Platform.PATREON, "https://patreon.example.com/x") }
    }

    // Signed in with a verified email, as through an MCP client
    private fun <T> signedInAs(email: String, block: () -> T): T {
        val jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-$email")
            .claim("email", email).claim("email_verified", true).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        return try { block() } finally { SecurityContextHolder.clearContext() }
    }

    @Test
    fun `credits whoever adds an episode with recommending it, unless they name someone else`() {
        val adder = signedInAs(ACCOUNT_LINKER) { peopleMCP.create(name = "Adding Recommender", email = "adding.recommender@example.com") }
        val other = peopleMCP.create(name = "Named Recommender", email = null)

        val mine = signedInAs("adding.recommender@example.com") { episodesMCP.create(code = "ep-2101", title = "Mine") }
        assertEquals(adder.id to "Adding Recommender", mine.recommendedById to mine.recommendedBy)
        val theirs = signedInAs("adding.recommender@example.com") {
            episodesMCP.create(code = "ep-2102", title = "Theirs", recommendedBy = other.id)
        }
        assertEquals(other.id, theirs.recommendedById)
        // Nobody signed in, or nobody linked to them
        assertNull(episodesMCP.create(code = "ep-2103", title = "Nobody's").recommendedById)
        assertNull(signedInAs("unknown@example.com") { episodesMCP.create(code = "ep-2104", title = "A stranger's") }.recommendedById)

        assertEquals(adder.id, episodesMCP.setRecommendedBy("ep-2102", adder.id).recommendedById)
        assertNull(episodesMCP.setRecommendedBy("ep-2102", null).recommendedById)
        assertFailsWith<IllegalArgumentException> { episodesMCP.setRecommendedBy("ep-2102", UUID.randomUUID()) }
        assertEquals(
            listOf(FieldChangeDto("Recommended by", "Named Recommender", "Adding Recommender"), FieldChangeDto("Recommended by", "Adding Recommender", null)),
            history.of(theirs.id!!).takeLast(2).map { it.fields.single() },
        )
    }

    @Test
    fun `takes a deleted person off the episodes they recommended, and restoring them puts them back`() {
        val person = peopleMCP.create(name = "Deleted Recommender", email = null)
        episodesMCP.create(code = "ep-2201", title = "Recommended", recommendedBy = person.id)
        episodesMCP.create(code = "ep-2202", title = "Recommended, then credited to someone else", recommendedBy = person.id)

        peopleMCP.delete(person.id!!)
        assertNull(assertNotNull(episodesMCP.get("ep-2201")).recommendedById)
        val other = peopleMCP.create(name = "Credited Since", email = null)
        episodesMCP.setRecommendedBy("ep-2202", other.id)

        peopleMCP.restore(person.id)
        assertEquals(person.id, assertNotNull(episodesMCP.get("ep-2201")).recommendedById)
        assertEquals(other.id, assertNotNull(episodesMCP.get("ep-2202")).recommendedById)
    }

    @Test
    fun `makes a candidate a guest in one change, and lists candidates last`() {
        val episode = episodesRepository.saveAndFlush(Episode(title = "Pitched", code = "ep-2001"))
        val host = peopleMCP.create(name = "Pitch Host", email = null)
        val maybe = peopleMCP.create(name = "Maybe Guest", email = null)
        val another = peopleMCP.create(name = "Another Candidate", email = null)
        episodesMCP.assignPerson("ep-2001", maybe.id!!, Role.CANDIDATE)
        episodesMCP.assignPerson("ep-2001", another.id!!, Role.CANDIDATE)
        episodesMCP.assignPerson("ep-2001", another.id, Role.GUEST)
        episodesMCP.assignPerson("ep-2001", host.id!!, Role.HOST)

        assertEquals(listOf(Role.GUEST), episodesMCP.confirmGuest("ep-2001", maybe.id).roles)
        assertEquals(
            Triple("Changed Maybe Guest's role", "candidate", "guest"),
            history.of(episode.id!!).last().let { Triple(it.summary, it.fields.single().from, it.fields.single().to) },
        )
        // Already a guest, they're just no longer a candidate
        assertEquals(listOf(Role.GUEST), episodesMCP.confirmGuest("ep-2001", another.id).roles)
        assertFailsWith<IllegalArgumentException> { episodesMCP.confirmGuest("ep-2001", maybe.id) }

        episodesMCP.assignPerson("ep-2001", host.id, Role.CANDIDATE)
        assertEquals(
            listOf("Pitch Host" to listOf(Role.HOST, Role.CANDIDATE), "Another Candidate" to listOf(Role.GUEST), "Maybe Guest" to listOf(Role.GUEST)),
            episodesMCP.listPeople("ep-2001").map { it.name to it.roles },
        )
    }

    @Test
    fun `lists the latest season and episode first, and the bonus season after them`() {
        listOf("S91E02", "S99E01", "S92E01", "S91E10").forEach { episodesMCP.create(code = it, title = it) }

        assertEquals(
            listOf("S92E01", "S91E10", "S91E02", "S99E01"),
            episodesMCP.list().mapNotNull { it.code }.filter { it.startsWith("S9") },
        )
    }
}