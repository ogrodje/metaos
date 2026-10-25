package si.ogrodje.metaos

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class VotesGraphQLTest @Autowired constructor(
    private val mvc: MockMvc,
    private val episodes: EpisodesRepository,
    private val episodesMCP: EpisodesMCP,
) {

    // Approved users, through MetaOS's pages: one who may only read, and two who may also write
    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")
    private val otherWriter = user("cene", "$API_READ_SCOPE $API_WRITE_SCOPE")
    // With the scopes, but not approved: Keycloak may issue them to more than approved users (Security.kt)
    private val unapproved = jwt().jwt {
        it.subject("dana-id").claim("preferred_username", "dana").claim("scope", "$API_READ_SCOPE $API_WRITE_SCOPE")
    }

    // Approved, but may only write: the answer to a vote names voters, which only readers see
    private val writeOnly = user("eva", API_WRITE_SCOPE)

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun idea(code: String): String {
        val jwt = Jwt.withTokenValue("token").header("alg", "none").subject("ana-id").claim("preferred_username", "ana").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try { episodesMCP.create(code = code, title = "Voted on") } finally { SecurityContextHolder.clearContext() }
        return checkNotNull(episodes.findByCode(code)?.id).toString()
    }

    private fun vote(ref: String, vote: String) = """mutation { voteOnIdea(ref: "$ref", vote: $vote) { score up down mine voters { name vote } } }"""

    @Test
    fun `votes as the signed-in user, with metaos write and approval only`() {
        val id = idea("vg-101")

        graphql(vote(id, "UP"))
            .andExpect(jsonPath("$.errors[0].extensions.classification", anyOf(equalTo("UNAUTHORIZED"), equalTo("FORBIDDEN"))))
            .andExpect(jsonPath("$.data").value(null as Any?))
        graphql(vote(id, "UP"), reader).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        graphql(vote(id, "UP"), unapproved).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        graphql(vote(id, "UP"), writeOnly).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))

        graphql(vote(id, "UP"), writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.voteOnIdea.score").value(1))
            .andExpect(jsonPath("$.data.voteOnIdea.mine").value("UP"))
            .andExpect(jsonPath("$.data.voteOnIdea.voters[0].name").value("bojan"))
        graphql(vote("vg-101", "DOWN"), otherWriter)
            .andExpect(jsonPath("$.data.voteOnIdea.score").value(0))
            .andExpect(jsonPath("$.data.voteOnIdea.mine").value("DOWN"))
    }

    @Test
    fun `shows approved users an idea's votes, each with their own, and nobody else`() {
        val id = idea("vg-102")
        graphql(vote(id, "UP"), writer).andExpect(jsonPath("$.errors").doesNotExist())
        val query = """{ episode(ref: "vg-102") { votes { score up down mine voters { name username vote } } } }"""

        graphql(query, reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.votes.score").value(1))
            .andExpect(jsonPath("$.data.episode.votes.mine").value("NONE"))
            .andExpect(jsonPath("$.data.episode.votes.voters[0].username").value("bojan"))
        graphql(query, writer).andExpect(jsonPath("$.data.episode.votes.mine").value("UP"))
        graphql("""{ ideas { code votes { score } } }""", reader)
            .andExpect(jsonPath("$.data.ideas[?(@.code == 'vg-102')].votes.score").value(1))

    }

    @Test
    fun `keeps the votes of an idea released since the team's`() {
        val id = idea("vg-103")
        graphql(vote(id, "UP"), writer).andExpect(jsonPath("$.errors").doesNotExist())
        // Released, as the rules wouldn't let it be without its links and pictures
        episodes.save(checkNotNull(episodes.findByCode("vg-103")).apply { status = EpisodeStatus.PUBLISHED; airedDate = LocalDate.of(2026, 10, 1) })
        val query = """{ episode(ref: "vg-103") { title votes { score up down mine voters { name } } } }"""

        for (auth in listOf(null, unapproved)) {
            graphql(query, auth)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.episode.title").value("Voted on"))
                .andExpect(jsonPath("$.data.episode.votes.score").value(0))
                .andExpect(jsonPath("$.data.episode.votes.up").value(0))
                .andExpect(jsonPath("$.data.episode.votes.mine").value("NONE"))
                .andExpect(jsonPath("$.data.episode.votes.voters").isEmpty())
        }
        graphql(query, reader).andExpect(jsonPath("$.data.episode.votes.score").value(1))
    }

    @Test
    fun `refuses an episode that isn't an idea, or isn't there`() {
        graphql(vote("vg-404", "UP"), writer)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.errors[0].message").value("Episode vg-404 not found"))
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        return mvc.perform(request).andExpect(status().isOk)
    }
}
