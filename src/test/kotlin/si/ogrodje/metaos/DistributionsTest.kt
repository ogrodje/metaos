package si.ogrodje.metaos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Without Temporal, so previews wait (DistributionPreviewTest fetches them)
@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class DistributionsTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mcp: DistributionsMCP,
    private val episodesMCP: EpisodesMCP,
    private val history: History,
    private val episodes: EpisodesRepository,
) {

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun signInAs(username: String) {
        val jwt = Jwt.withTokenValue("token").header("alg", "none").subject("$username-id")
            .claim("preferred_username", username).claim("azp", "claude-code").claim("realm_access", APPROVED).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    private fun list(code: String) = mcp.listEpisodeDistributions(code)

    @Test
    fun `the episode's links are in its distribution, and follow it`() {
        signInAs("ana")
        episodesMCP.create(code = "ds-101", title = "Povezave")
        episodesMCP.setLink("ds-101", Platform.PATREON_AUDIO, "https://www.patreon.com/posts/audio-1")
        episodesMCP.setLink("ds-101", Platform.YOUTUBE, "https://www.youtube.com/watch?v=abc")

        val links = list("ds-101")
        // In Platform's order, each on its platform's channel, waiting for its preview
        assertEquals(listOf(Platform.YOUTUBE, Platform.PATREON_AUDIO), links.map { it.platform })
        assertEquals(listOf(Channel.YOUTUBE, Channel.PATREON), links.map { it.channel })
        assertTrue(links.all { it.preview.status == PreviewStatus.PENDING && it.addedBy == "ana" })

        // A changed link is the same entry, with its URL
        episodesMCP.setLink("ds-101", Platform.YOUTUBE, "https://youtu.be/def")
        val changed = list("ds-101").first()
        assertEquals(links.first().id, changed.id)
        assertEquals("https://youtu.be/def", changed.url)

        // A cleared one goes
        episodesMCP.setLink("ds-101", Platform.YOUTUBE, null)
        assertEquals(listOf(Platform.PATREON_AUDIO), list("ds-101").map { it.platform })

        // Removed there, the episode's link is cleared too
        mcp.removeEpisodeDistribution(checkNotNull(links.last().id))
        assertEquals(emptyList(), list("ds-101"))
        assertNull(episodesMCP.get("ds-101")?.patreonAudioUrl)
    }

    @Test
    fun `logs posts by their URLs, once each, on the channel their host says`() {
        signInAs("bojan")
        episodesMCP.create(code = "ds-102", title = "Objave")
        val post = mcp.addEpisodeDistribution(
            "ds-102", "https://WWW.LinkedIn.com/posts/ogrodje_123?utm_source=share&utm_medium=member&rcm=x&keep=1",
            null, "2026-11-10", "  Iva's post  ", null, null,
        )
        assertEquals(Channel.LINKEDIN, post.channel)
        assertEquals("https://www.linkedin.com/posts/ogrodje_123?keep=1", post.url)
        assertEquals("Iva's post", post.note)
        assertEquals(OffsetDateTime.parse("2026-11-10T00:00+01:00").toInstant(), post.postedAt?.toInstant())
        assertNull(post.platform)

        // The same URL again, however it's written, is that post, with what's given changed
        val again = mcp.addEpisodeDistribution("ds-102", "https://www.linkedin.com/posts/ogrodje_123?keep=1&fbclid=1", null, null, null, "Objava", null)
        assertEquals(post.id, again.id)
        assertEquals("Objava", again.title)
        assertEquals("Iva's post", again.note)

        // Mastodon, which no host tells, by hand; anything else is OTHER
        assertEquals(Channel.MASTODON, mcp.addEpisodeDistribution("ds-102", "https://mastodon.social/@ogrodje/1", Channel.MASTODON, null, null, null, null).channel)
        assertEquals(Channel.OTHER, mcp.addEpisodeDistribution("ds-102", "https://example.com/blog", null, null, null, null, null).channel)
        assertEquals(Channel.MONITOR, mcp.addEpisodeDistribution("ds-102", "https://www.monitor.si/clanek/1", null, null, null, null, null).channel)

        assertFailsWith<IllegalArgumentException> { mcp.addEpisodeDistribution("ds-102", "http://example.com", null, null, null, null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.addEpisodeDistribution("ds-102", "https://example.com/x", null, "yesterday", null, null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.addEpisodeDistribution("ds-404", "https://example.com", null, null, null, null, null) }

        // Changed and cleared; a URL another post has is refused
        val changed = mcp.updateEpisodeDistribution(checkNotNull(post.id), null, null, "", "", "", )
        assertNull(changed.note)
        assertNull(changed.postedAt)
        assertNull(changed.title)
        assertFailsWith<IllegalArgumentException> { mcp.updateEpisodeDistribution(checkNotNull(post.id), "https://example.com/blog", null, null, null, null) }

        mcp.removeEpisodeDistribution(checkNotNull(post.id))
        assertEquals(listOf(Channel.MONITOR, Channel.OTHER, Channel.MASTODON).sorted(), list("ds-102").map { it.channel }.sorted())
        assertTrue(mcp.listDistributions(Channel.MONITOR, null, null).any { it.episode == "ds-102" })
    }

    @Test
    fun `a post can become one of the episode's links, and a link's URL as a post is the link`() {
        signInAs("cene")
        episodesMCP.create(code = "ds-103", title = "Patreon")
        val post = mcp.addEpisodeDistribution("ds-103", "https://www.patreon.com/posts/bonus-7", null, null, "Bonus", null, null)
        assertEquals(Channel.PATREON, post.channel)

        // Set as a link, the post with its URL becomes it
        episodesMCP.setLink("ds-103", Platform.PATREON_BONUS, "https://www.patreon.com/posts/bonus-7")
        val link = list("ds-103").single()
        assertEquals(post.id, link.id)
        assertEquals(Platform.PATREON_BONUS, link.platform)
        assertEquals("Bonus", link.note)

        // A link's URL added as a post is the link
        assertEquals(link.id, mcp.addEpisodeDistribution("ds-103", "https://www.patreon.com/posts/bonus-7", null, null, null, null, null).id)

        // With link, it's the episode's link, as set_episode_link checks it
        val youtube = mcp.addEpisodeDistribution("ds-103", "https://www.youtube.com/watch?v=xyz", null, null, null, null, Platform.YOUTUBE)
        assertEquals(Platform.YOUTUBE, youtube.platform)
        assertEquals("https://www.youtube.com/watch?v=xyz", episodesMCP.get("ds-103")?.youtubeUrl)
        assertFailsWith<IllegalArgumentException> {
            mcp.addEpisodeDistribution("ds-103", "https://example.com/v", null, null, null, null, Platform.SPOTIFY)
        }
        // A link's channel is its platform's
        assertFailsWith<IllegalArgumentException> { mcp.updateEpisodeDistribution(checkNotNull(youtube.id), null, Channel.REDDIT, null, null, null) }

        // Its URL changed there is the episode's link changed
        mcp.updateEpisodeDistribution(checkNotNull(youtube.id), "https://youtu.be/xyz2", null, null, null, null)
        assertEquals("https://youtu.be/xyz2", episodesMCP.get("ds-103")?.youtubeUrl)
    }

    @Test
    fun `the history tells the posts, not the links, which the episode's changes tell`() {
        signInAs("dora")
        episodesMCP.create(code = "ds-104", title = "Zgodovina")
        episodesMCP.setLink("ds-104", Platform.YOUTUBE, "https://www.youtube.com/watch?v=h1")
        val post = mcp.addEpisodeDistribution("ds-104", "https://discord.com/channels/1/2/3", null, null, null, null, null)
        mcp.updateEpisodeDistribution(checkNotNull(post.id), null, null, null, "Pinned", null)
        mcp.removeEpisodeDistribution(checkNotNull(post.id))

        val id = checkNotNull(episodes.findByCode("ds-104")?.id)
        val told = history.of(id).filter { it.kind == ChangeKind.DISTRIBUTION }
        assertEquals(listOf("Shared it on Discord", "Changed a post on Discord", "Removed a post on Discord"), told.map { it.summary })
        assertEquals(listOf(FieldChangeDto("Note", null, "Pinned")), told[1].fields)
        assertEquals("add_episode_distribution", told[0].tool)
        assertTrue(history.of(id).any { it.summary == "Changed the episode" && it.fields.any { f -> f.field == "YouTube" } })
    }

    // Approved users, through MetaOS's pages
    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    @Test
    fun `GraphQL gives the team an episode's distribution, and lets writers log posts`() {
        signInAs("ana")
        episodesMCP.create(code = "ds-105", title = "GraphQL")
        episodesMCP.setLink("ds-105", Platform.SPOTIFY, "https://open.spotify.com/episode/1")
        signOut()
        val query = """{ episode(ref: "ds-105") { distributions { platform channel url addedBy preview { status image { url } } } } }"""

        graphql(query, reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.distributions[0].platform").value("SPOTIFY"))
            .andExpect(jsonPath("$.data.episode.distributions[0].channel").value("SPOTIFY"))
            .andExpect(jsonPath("$.data.episode.distributions[0].addedBy").value("ana"))
            .andExpect(jsonPath("$.data.episode.distributions[0].preview.status").value("PENDING"))

        val add = """mutation { addDistribution(ref: "ds-105", url: "https://www.reddit.com/r/slovenia/comments/1", note: "r/slovenia") { channel note episode { code } } }"""
        graphql(add, reader).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        graphql(add, writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.addDistribution.channel").value("REDDIT"))
            .andExpect(jsonPath("$.data.addDistribution.episode.code").value("ds-105"))

        graphql("""{ distributions(channel: REDDIT) { url } }""", reader)
            .andExpect(jsonPath("$.data.distributions[0].url").value("https://www.reddit.com/r/slovenia/comments/1"))
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
