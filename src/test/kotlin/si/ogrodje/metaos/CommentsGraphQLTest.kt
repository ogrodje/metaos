package si.ogrodje.metaos

import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.equalTo
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
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class CommentsGraphQLTest @Autowired constructor(
    private val mvc: MockMvc,
    private val episodes: EpisodesRepository,
    private val episodesMCP: EpisodesMCP,
    private val sessionsMCP: SessionsMCP,
    private val commentsMCP: CommentsMCP,
    private val commentsGraphQL: CommentsGraphQL,
) {

    // Approved users, through MetaOS's pages: one who may only read, and two who may also write
    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")
    private val otherWriter = user("cene", "$API_READ_SCOPE $API_WRITE_SCOPE")

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    // Comments written through MCP by someone signed in
    private fun <T> asMcpUser(username: String, block: () -> T): T {
        val jwt = Jwt.withTokenValue("token").header("alg", "none")
            .subject("$username-id").claim("preferred_username", username).claim("azp", "claude-code").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        return try { block() } finally { SecurityContextHolder.clearContext() }
    }

    @Test
    fun `shows signed-in users an episode's discussion, and nobody else`() {
        asMcpUser("ana") {
            episodesMCP.create(code = "cg-101", title = "Discussed")
            commentsMCP.commentOnEpisode("cg-101", "Hello **team**")
        }
        val query = """{ episode(ref: "cg-101") { comments { body tool mine editedAt author { name username person { name } } } } }"""

        graphql(query, reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.comments[0].body").value("Hello **team**"))
            .andExpect(jsonPath("$.data.episode.comments[0].tool").value("comment_on_episode"))
            .andExpect(jsonPath("$.data.episode.comments[0].mine").value(true))
            .andExpect(jsonPath("$.data.episode.comments[0].author.name").value("ana"))
            .andExpect(jsonPath("$.data.episode.comments[0].author.person").value(null as Any?))
        graphql(query, writer).andExpect(jsonPath("$.data.episode.comments[0].mine").value(false))

        // Where an episode is public, e.g. among the released episodes, its discussion stays the team's
        val episode = checkNotNull(episodes.findByCode("cg-101"))
        assertEquals(mapOf(episode to emptyList<Comment>()), commentsGraphQL.episodeComments(listOf(episode)))
    }

    @Test
    fun `comments, edits and deletes as the signed-in user, with metaos write`() {
        asMcpUser("ana") { episodesMCP.create(code = "cg-102", title = "Written") }
        val episodeId = checkNotNull(episodes.findByCode("cg-102")?.id)

        // Spring GraphQL can't tell an anonymous mutation's refusal from a forbidden one; refused either way
        graphql("""mutation { commentOnEpisode(ref: "$episodeId", body: "Anonymous") { id } }""")
            .andExpect(jsonPath("$.errors[0].extensions.classification", anyOf(equalTo("UNAUTHORIZED"), equalTo("FORBIDDEN"))))
            .andExpect(jsonPath("$.data").value(null as Any?))
        graphql("""mutation { commentOnEpisode(ref: "$episodeId", body: "Read only") { id } }""", reader)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))

        val answer = graphql("""mutation { commentOnEpisode(ref: "$episodeId", body: "From the page") { id body mine tool author { username } } }""", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.commentOnEpisode.body").value("From the page"))
            .andExpect(jsonPath("$.data.commentOnEpisode.mine").value(true))
            .andExpect(jsonPath("$.data.commentOnEpisode.tool").value(null as Any?))
            .andExpect(jsonPath("$.data.commentOnEpisode.author.username").value("bojan"))
        val id = JsonPath.read<String>(answer.andReturn().response.contentAsString, "$.data.commentOnEpisode.id")

        graphql("""mutation { editComment(id: "$id", body: "Not yours") { id } }""", otherWriter)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.errors[0].message").value("Only whoever wrote a comment can change or delete it"))
        graphql("""mutation { editComment(id: "$id", body: "From *the* page") { body editedAt } }""", writer)
            .andExpect(jsonPath("$.data.editComment.body").value("From *the* page"))
            .andExpect(jsonPath("$.data.editComment.editedAt").isString())
        graphql("""mutation { deleteComment(id: "$id") }""", writer)
            .andExpect(jsonPath("$.data.deleteComment").value(id))

        graphql("""{ episode(ref: "cg-102") { comments { id } history { kind commentId summary tool } } }""", writer)
            .andExpect(jsonPath("$.data.episode.comments").isEmpty())
            .andExpect(jsonPath("$.data.episode.history[1].summary").value("Commented"))
            .andExpect(jsonPath("$.data.episode.history[1].kind").value("COMMENT"))
            .andExpect(jsonPath("$.data.episode.history[1].commentId").value(id))
            .andExpect(jsonPath("$.data.episode.history[0].commentId").value(null as Any?))
            .andExpect(jsonPath("$.data.episode.history[2].summary").value("Edited a comment"))
            .andExpect(jsonPath("$.data.episode.history[3].summary").value("Deleted a comment"))
            .andExpect(jsonPath("$.data.episode.history[3].tool").value(null as Any?))
    }

    @Test
    fun `comments on a session`() {
        val session = asMcpUser("ana") { sessionsMCP.create(LocalDate.of(2026, 11, 5), location = "Kino Šiška", address = null) }

        graphql("""mutation { commentOnSession(id: "${session.id}", body: "Bring the good microphones") { body } }""", writer)
            .andExpect(jsonPath("$.data.commentOnSession.body").value("Bring the good microphones"))
        graphql("""{ session(id: "${session.id}") { comments { body author { name } } } }""", reader)
            .andExpect(jsonPath("$.data.session.comments[0].body").value("Bring the good microphones"))
            .andExpect(jsonPath("$.data.session.comments[0].author.name").value("bojan"))
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        // Answered right away, unless a resolver is asynchronous
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions)
            .andExpect(status().isOk)
    }
}
