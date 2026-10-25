package si.ogrodje.metaos

import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The four Patreon links of an episode (OG-16): the full episode and its bonus, each as video and audio only
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class PatreonLinksTest @Autowired constructor(
    private val mvc: MockMvc,
    private val episodesRepository: EpisodesRepository,
    private val episodesMCP: EpisodesMCP,
    private val peopleMCP: PeopleMCP,
    private val jdbcTemplate: JdbcTemplate,
    private val history: History,
) {

    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    private val EpisodeDetailsDto.patreon
        get() = listOf(patreonUrl, patreonAudioUrl, patreonBonusUrl, patreonBonusAudioUrl)

    @Test
    fun `sets and clears each of the four Patreon links on its own`() {
        episodesRepository.saveAndFlush(Episode(title = "Four ways", code = "pl-101"))
        val urls = listOf("https://www.patreon.com/posts/full", "https://patreon.com/posts/full-audio",
            "https://www.patreon.com/posts/bonus", "https://www.patreon.com/posts/bonus-audio")
        val platforms = listOf(Platform.PATREON, Platform.PATREON_AUDIO, Platform.PATREON_BONUS, Platform.PATREON_BONUS_AUDIO)

        platforms.zip(urls).forEachIndexed { i, (platform, url) ->
            val set = episodesMCP.setLink("pl-101", platform, url)
            assertEquals(urls.take(i + 1) + List(3 - i) { null },
                listOf(set.patreonUrl, set.patreonAudioUrl, set.patreonBonusUrl, set.patreonBonusAudioUrl))
        }

        episodesMCP.setLink("pl-101", Platform.PATREON_AUDIO, null)
        assertEquals(listOf(urls[0], null, urls[2], urls[3]), assertNotNull(episodesMCP.get("pl-101")).patreon)
        episodesMCP.setLink("pl-101", Platform.PATREON, null)
        episodesMCP.setLink("pl-101", Platform.PATREON_BONUS_AUDIO, null)
        assertEquals(listOf(null, null, urls[2], null), assertNotNull(episodesMCP.get("pl-101")).patreon)

        assertFailsWith<IllegalArgumentException> {
            episodesMCP.setLink("pl-101", Platform.PATREON_BONUS, "https://youtu.be/not-patreon")
        }

        val fields = history.of(checkNotNull(episodesRepository.findByCode("pl-101")?.id)).flatMap { it.fields }.map { it.field }
        assertTrue(fields.containsAll(listOf("Patreon", "Patreon (audio only)", "Patreon bonus", "Patreon bonus (audio only)")))
    }

    @Test
    fun `the Patreon link there was is the full episode as video`() {
        // As V28 left it: the link written to patreon_url before there were four
        val id = checkNotNull(episodesRepository.saveAndFlush(Episode(title = "From before", code = "pl-102")).id)
        jdbcTemplate.update("UPDATE episodes SET patreon_url = ? WHERE id = ?", "https://www.patreon.com/posts/old", id)

        val episode = assertNotNull(episodesMCP.get("pl-102"))
        assertEquals(listOf("https://www.patreon.com/posts/old", null, null, null), episode.patreon)

        episodesMCP.setLink("pl-102", Platform.PATREON_BONUS, "https://www.patreon.com/posts/new-bonus")
        assertEquals(
            listOf("https://www.patreon.com/posts/old", null, "https://www.patreon.com/posts/new-bonus", null),
            assertNotNull(episodesMCP.get("pl-102")).patreon,
        )
    }

    @Test
    fun `early access needs any of the Patreon links, a bonus too`() {
        val host = peopleMCP.create(name = "Patreon Host", email = null)
        episodesMCP.create(code = "pl-103", title = "Early")
        episodesMCP.assignPerson("pl-103", host.id!!, Role.HOST)
        episodesMCP.assignPerson("pl-103", host.id, Role.PRODUCER)
        val id = checkNotNull(episodesRepository.findByCode("pl-103")?.id)
        jdbcTemplate.update(
            "UPDATE episodes SET status = 'READY', recording_date = CURRENT_DATE, summary = 'About' WHERE id = ?", id,
        )

        val refused = assertFailsWith<IllegalArgumentException> { episodesMCP.setStatus("pl-103", EpisodeStatus.EARLY_ACCESS) }
        assertTrue("a Patreon link" in refused.message.orEmpty(), refused.message)

        episodesMCP.setLink("pl-103", Platform.PATREON_BONUS_AUDIO, "https://www.patreon.com/posts/bonus")
        assertEquals(EpisodeStatus.EARLY_ACCESS, episodesMCP.setStatus("pl-103", EpisodeStatus.EARLY_ACCESS).status)
    }

    @Test
    fun `GraphQL reads all four and sets each, with metaos write`() {
        val id = checkNotNull(episodesRepository.saveAndFlush(Episode(title = "Over GraphQL", code = "pl-104")).id)
        val fields = "patreonUrl patreonAudioUrl patreonBonusUrl patreonBonusAudioUrl"

        graphql("""mutation { setEpisodeLink(ref: "pl-104", platform: PATREON_AUDIO, url: "https://www.patreon.com/posts/a") { id } }""")
            .andExpect(jsonPath("$.errors[0].extensions.classification", anyOf(equalTo("UNAUTHORIZED"), equalTo("FORBIDDEN"))))
        graphql("""mutation { setEpisodeLink(ref: "pl-104", platform: PATREON_AUDIO, url: "https://www.patreon.com/posts/a") { id } }""", reader)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))

        graphql("""mutation { setEpisodeLink(ref: "pl-104", platform: PATREON_AUDIO, url: "https://www.patreon.com/posts/a") { $fields } }""", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.setEpisodeLink.patreonAudioUrl").value("https://www.patreon.com/posts/a"))
            .andExpect(jsonPath("$.data.setEpisodeLink.patreonUrl").value(null as Any?))
        graphql("""mutation { setEpisodeLink(ref: "$id", platform: PATREON_BONUS_AUDIO, url: "https://www.patreon.com/posts/ba") { id } }""", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
        graphql("""mutation { setEpisodeLink(ref: "pl-104", platform: PATREON, url: "https://www.patreon.com/posts/v") { id } }""", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
        graphql("""mutation { setEpisodeLink(ref: "pl-104", platform: PATREON, url: null) { id } }""", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
        graphql("""mutation { setEpisodeLink(ref: "pl-104", platform: PATREON_BONUS, url: "https://example.com/x") { id } }""", writer)
            .andExpect(jsonPath("$.errors[0].message").exists())

        graphql("""{ episode(ref: "pl-104") { $fields } }""", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.patreonUrl").value(null as Any?))
            .andExpect(jsonPath("$.data.episode.patreonAudioUrl").value("https://www.patreon.com/posts/a"))
            .andExpect(jsonPath("$.data.episode.patreonBonusUrl").value(null as Any?))
            .andExpect(jsonPath("$.data.episode.patreonBonusAudioUrl").value("https://www.patreon.com/posts/ba"))
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions)
            .andExpect(status().isOk)
    }
}
