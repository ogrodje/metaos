package si.ogrodje.metaos

import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class SearchTest @Autowired constructor(
    private val mvc: MockMvc,
    private val episodes: EpisodesRepository,
    private val people: PeopleRepository,
    private val episodePeople: EpisodePeopleRepository,
    private val sessions: SessionsRepository,
    private val searchMCP: SearchMCP,
) {

    private val reader = jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", API_READ_SCOPE) }

    private fun graphql(query: String, auth: RequestPostProcessor? = reader): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        // Answered right away, unless a resolver is asynchronous
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions).andExpect(status().isOk)
    }

    private fun search(text: String, limit: Int = 10) = graphql(
        """{ search(text: "$text", limit: $limit) {
            episode { code } season episodeCount personCount sessionCount
            episodes { episode { code title } matchedIn snippet }
            people { person { name } matchedIn }
            sessions { session { location } matchedIn }
        } }"""
    ).andExpect(jsonPath("$.errors").doesNotExist())

    private fun episode(code: String?, title: String, init: Episode.() -> Unit = {}) =
        episodes.save(Episode(title = title, code = code).apply {
            if (code != null) status = EpisodeStatus.PUBLISHED
            init()
        })

    @Test
    fun `reads episode codes and seasons however they're typed`() {
        listOf("S05E08", "s5e8", "S5 E8", "s05e8", "5x08", "5X8").forEach { assertEquals("S05E08", episodeCode(it), it) }
        assertEquals("S99E102", episodeCode("s99e102"))
        listOf("508", "S05", "E08", "2026", "S05E08 GaMS").forEach { assertNull(episodeCode(it), it) }

        listOf("S04", "s4", "S04E", "season 4", "Sezona 4").forEach { assertEquals(4, seasonOf(it), it) }
        listOf("S04E01", "4", "seasons").forEach { assertNull(seasonOf(it), it) }
    }

    @Test
    fun `reads words without case or diacritics`() {
        assertEquals("cuhalev duric sola", fold("Čuhalev Đurić ŠOLA"))
        assertEquals(listOf("jezikovni", "model", "2026"), searchWords("  Jezikovni-model, 2026!"))
    }

    @Test
    fun `cuts a long text around what it found`() {
        val text = "Uvod. ".repeat(60) + "Tu je superračunalnik Leonardo, o katerem sta govorila. " + "Konec. ".repeat(60)
        val found = snippet(text, searchWords("superracunalnik"))
        assertTrue(found.startsWith("…") && found.endsWith("…"), found)
        assertTrue("superračunalnik Leonardo" in found, found)
        assertTrue(found.length <= 162, found)
        assertEquals("Short.", snippet("Short.", listOf("short")))
    }

    @Test
    fun `is for signed-in users only`() {
        graphql("""{ search(text: "anything") { episodeCount } }""", auth = null)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"))
        graphql("""{ search(text: "anything", limit: 0) { episodeCount } }""")
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
    }

    @Test
    fun `finds an episode by its code alone, however it's typed, and a season by its episodes`() {
        episode("S77E05", "Zebroid the fifth")
        episode("S77E04", "Zebroid the fourth, about S77E05")

        search("s77e5")
            .andExpect(jsonPath("$.data.search.episode.code").value("S77E05"))
            .andExpect(jsonPath("$.data.search.episodes", empty<Any>()))
            .andExpect(jsonPath("$.data.search.people", empty<Any>()))
        search("77x05").andExpect(jsonPath("$.data.search.episode.code").value("S77E05"))

        // A code there's no episode of is nothing, not words
        search("S77E99")
            .andExpect(jsonPath("$.data.search.episode").doesNotExist())
            .andExpect(jsonPath("$.data.search.episodeCount").value(0))

        search("S77")
            .andExpect(jsonPath("$.data.search.season").value(77))
            .andExpect(jsonPath("$.data.search.episodes[*].episode.code", contains("S77E05", "S77E04")))
            .andExpect(jsonPath("$.data.search.episodes[0].matchedIn", contains("CODE")))
    }

    @Test
    fun `finds ideas, people and sessions by the start of their words, without diacritics`() {
        val idea = episode(null, "Kvantni računalniki za začetnike")
        val candidate = people.save(Person(name = "Žiga Kvantnik", email = null))
        episodePeople.save(EpisodePerson(episode = idea, person = candidate, role = Role.CANDIDATE))
        sessions.save(Session(date = LocalDate.of(2031, 10, 9), location = "Searchtown Hall", address = "Kvantna ulica 1"))

        search("kvantn")
            .andExpect(jsonPath("$.data.search.episodes[*].episode.title", hasItem("Kvantni računalniki za začetnike")))
            .andExpect(jsonPath("$.data.search.people[*].person.name", hasItem("Žiga Kvantnik")))
            .andExpect(jsonPath("$.data.search.sessions[*].session.location", hasItem("Searchtown Hall")))
        // The candidate's name finds them, and the idea they're on
        search("ziga kvantnik")
            .andExpect(jsonPath("$.data.search.people[0].person.name").value("Žiga Kvantnik"))
            .andExpect(jsonPath("$.data.search.people[0].matchedIn", contains("NAME")))
            .andExpect(jsonPath("$.data.search.episodes[0].episode.title").value("Kvantni računalniki za začetnike"))
            .andExpect(jsonPath("$.data.search.episodes[0].matchedIn", contains("PEOPLE")))
        search("searchtown oktober")
            .andExpect(jsonPath("$.data.search.sessions[0].session.location").value("Searchtown Hall"))
            .andExpect(jsonPath("$.data.search.sessions[0].matchedIn", contains("LOCATION", "DATE")))
    }

    @Test
    fun `puts titles before summaries, says where it matched, and leaves the AI summary out`() {
        episode("S78E02", "Pogovor o narvalih") {
            summary = "Uvod. ".repeat(40) + "Govorila sta tudi o **narvalih** in [njihovih okljih](https://example.com/okel). " + "Konec. ".repeat(40)
        }
        episode("S78E01", "Narvali v Arktiki")
        episode("S78E03", "Nekaj drugega") { titleB = "Narvali, drugič" }
        episode("S78E04", "Spet nekaj drugega") { machineSummary = "Tudi tu so narvali." }

        search("narval")
            // The title, its start first; then another title; then the summary
            .andExpect(jsonPath("$.data.search.episodes[*].episode.code", contains("S78E01", "S78E02", "S78E03")))
            .andExpect(jsonPath("$.data.search.episodes[2].matchedIn", contains("ALTERNATIVE_TITLE")))
            .andExpect(jsonPath("$.data.search.episodes[2].snippet").value("Narvali, drugič"))
        search("narval okl")
            .andExpect(jsonPath("$.data.search.episodes[0].episode.code").value("S78E02"))
            .andExpect(jsonPath("$.data.search.episodes[0].matchedIn", contains("TITLE", "SUMMARY")))
            // Plain text, without the Markdown
            .andExpect(jsonPath("$.data.search.episodes[0].snippet", startsWith("…")))
            .andExpect(jsonPath("$.data.search.episodes[0].snippet", containsString("o narvalih in njihovih okljih.")))
        search("narval", limit = 1)
            .andExpect(jsonPath("$.data.search.episodes[*].episode.code", contains("S78E01")))
            .andExpect(jsonPath("$.data.search.episodeCount").value(3))
    }

    @Test
    fun `searches over MCP too`() {
        episode("S79E01", "Okapiji in žirafe")
        val found = searchMCP.search("s79e1", null)
        assertEquals("S79E01", found.episode?.code)
        assertEquals(listOf("S79E01"), searchMCP.search("okapij zirafe", null).episodes.map { it.episode.code })
        assertThrows<IllegalArgumentException> { searchMCP.search(" ", null) }
    }

    @Test
    fun `shows signed-in users a person with every episode they're on and those they recommended`() {
        val person = people.save(Person(name = "Pia Personpage", email = null).apply { bio = "Producer @ Somewhere" })
        val hosted = episode("S80E02", "Hosted by Pia")
        val idea = episode(null, "Pia's idea") { recommendedBy = person }
        episodePeople.save(EpisodePerson(episode = hosted, person = person, role = Role.HOST))
        episodePeople.save(EpisodePerson(episode = hosted, person = person, role = Role.PRODUCER))
        episodePeople.save(EpisodePerson(episode = idea, person = person, role = Role.CANDIDATE))
        val query = """{ person(id: "${person.id}") { name bio episodes { episode { code title } roles } recommended { title } } }"""

        graphql(query)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.person.name").value("Pia Personpage"))
            .andExpect(jsonPath("$.data.person.bio").value("Producer @ Somewhere"))
            .andExpect(jsonPath("$.data.person.episodes[*].episode.title", contains("Hosted by Pia", "Pia's idea")))
            .andExpect(jsonPath("$.data.person.episodes[0].roles", contains("HOST", "PRODUCER")))
            .andExpect(jsonPath("$.data.person.recommended[*].title", contains("Pia's idea")))
        // She hosts a released episode, so her page is anyone's, with only that one (GraphQLTest has whose isn't)
        graphql(query, auth = null)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.person.episodes[*].episode.title", contains("Hosted by Pia")))
            .andExpect(jsonPath("$.data.person.recommended", empty<Any>()))
    }
}
