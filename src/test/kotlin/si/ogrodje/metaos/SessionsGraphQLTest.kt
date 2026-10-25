package si.ogrodje.metaos

import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class SessionsGraphQLTest @Autowired constructor(
    private val mvc: MockMvc,
    private val sessionsMCP: SessionsMCP,
    private val episodesMCP: EpisodesMCP,
) {

    // Approved users, through MetaOS's pages: one who may only read, and one who may also write
    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    @Test
    fun `renames a session as the signed-in user, with metaos write, and says so in its history`() {
        val session = sessionsMCP.create(LocalDate.of(2099, 9, 1), location = "Rename Hall", address = "Street 1")
        val rename = """mutation { updateSession(id: "${session.id}", name: "Rename Conf 2099") { id name location address date } }"""

        graphql(rename)
            .andExpect(jsonPath("$.errors[0].extensions.classification", anyOf(equalTo("UNAUTHORIZED"), equalTo("FORBIDDEN"))))
        graphql(rename, reader).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))

        // What's left out stays as it was
        graphql(rename, writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.updateSession.name").value("Rename Conf 2099"))
            .andExpect(jsonPath("$.data.updateSession.location").value("Rename Hall"))
            .andExpect(jsonPath("$.data.updateSession.address").value("Street 1"))
            .andExpect(jsonPath("$.data.updateSession.date").value("2099-09-01"))
        graphql("""mutation { updateSession(id: "${session.id}", date: "1 Sep") { id } }""", writer)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))

        graphql("""{ session(id: "${session.id}") { name history { summary actor tool fields { field from to } } } }""", reader)
            .andExpect(jsonPath("$.data.session.name").value("Rename Conf 2099"))
            .andExpect(jsonPath("$.data.session.history[*].summary", contains("Created the session", "Changed the session")))
            .andExpect(jsonPath("$.data.session.history[1].actor").value("bojan"))
            .andExpect(jsonPath("$.data.session.history[1].tool").value(null as Any?))
            .andExpect(jsonPath("$.data.session.history[1].fields[*].field", contains("Name")))
            .andExpect(jsonPath("$.data.session.history[1].fields[0].from").value(null as Any?))
            .andExpect(jsonPath("$.data.session.history[1].fields[0].to").value("Rename Conf 2099"))

        // Clearing it: the session goes by its location again
        graphql("""mutation { updateSession(id: "${session.id}", name: "") { name location } }""", writer)
            .andExpect(jsonPath("$.data.updateSession.name").value(null as Any?))
            .andExpect(jsonPath("$.data.updateSession.location").value("Rename Hall"))
    }

    @Test
    fun `names a session by its name, else its location, in an episode's history`() {
        val named = sessionsMCP.create(LocalDate.of(2099, 10, 2), name = "Named Conf", location = "Named Hall", address = null)
        val unnamed = sessionsMCP.create(LocalDate.of(2099, 10, 3), location = "Plain Hall", address = null)
        episodesMCP.create(code = "sg-101", title = "Moving around")
        sessionsMCP.setEpisodeSession("sg-101", named.id)
        sessionsMCP.setEpisodeSession("sg-101", unnamed.id)

        graphql("""{ episode(ref: "sg-101") { history { fields { field from to } } } }""", reader)
            .andExpect(jsonPath("$.data.episode.history[1].fields[0].to").value("2 Oct 2099 · Named Conf"))
            .andExpect(jsonPath("$.data.episode.history[2].fields[0].from").value("2 Oct 2099 · Named Conf"))
            .andExpect(jsonPath("$.data.episode.history[2].fields[0].to").value("3 Oct 2099 · Plain Hall"))
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
