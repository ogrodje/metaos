package si.ogrodje.metaos

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class AccountsGraphQLTest @Autowired constructor(
    private val mvc: MockMvc,
    private val people: PeopleRepository,
) {

    // Approved users through MetaOS's pages, as Keycloak would sign them in
    private fun user(username: String, scope: String, email: String? = null) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
        if (email != null) it.claim("email", email).claim("email_verified", true)
    }

    private val writes = "$API_READ_SCOPE $API_WRITE_SCOPE"

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    @Test
    fun `writes down whoever asks, and links them by verified email`() {
        people.save(Person(name = "Iva Graf", email = "iva.graphql@example.com"))

        graphql("{ me { username person { name } account { username email emailVerified linkedHow person { name } } } }",
            user("agq-iva", API_READ_SCOPE, email = "iva.graphql@example.com"))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.me.person.name").value("Iva Graf"))
            .andExpect(jsonPath("$.data.me.account.username").value("agq-iva"))
            .andExpect(jsonPath("$.data.me.account.linkedHow").value("EMAIL"))
            .andExpect(jsonPath("$.data.me.account.person.name").value("Iva Graf"))
    }

    @Test
    fun `links and unlinks accounts with metaos write, and shows them on the person`() {
        val person = people.save(Person(name = "Jan Pirc", email = null))
        // Seen once, so MetaOS has their account
        graphql("{ me { username } }", user("agq-jan", API_READ_SCOPE))
        val unlinked = graphql("{ accounts(unlinked: true) { id username } }", user("agq-admin", writes, email = ACCOUNT_LINKER))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn().response.contentAsString
        val id = JsonPath.read<List<String>>(unlinked, "$.data.accounts[?(@.username == 'agq-jan')].id").single()

        graphql("""mutation { linkAccount(id: "$id", person: "${person.id}") { id } }""", user("agq-reader", API_READ_SCOPE))
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        // Writing isn't enough: only ACCOUNT_LINKER links accounts
        graphql("""mutation { linkAccount(id: "$id", person: "${person.id}") { id } }""", user("agq-writer", writes))
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        graphql("{ me { canLinkAccounts } }", user("agq-writer", writes))
            .andExpect(jsonPath("$.data.me.canLinkAccounts").value(false))
        graphql("{ me { canLinkAccounts } }", user("agq-admin", writes, email = ACCOUNT_LINKER))
            .andExpect(jsonPath("$.data.me.canLinkAccounts").value(true))
        graphql("""mutation { linkAccount(id: "$id", person: "${person.id}") { linkedHow linkedBy person { name } } }""", user("agq-admin", writes, email = ACCOUNT_LINKER))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.linkAccount.linkedHow").value("MANUAL"))
            .andExpect(jsonPath("$.data.linkAccount.linkedBy").value("agq-admin"))
            .andExpect(jsonPath("$.data.linkAccount.person.name").value("Jan Pirc"))
        graphql("""{ person(id: "${person.id}") { accounts { username } } }""", user("agq-admin", writes, email = ACCOUNT_LINKER))
            .andExpect(jsonPath("$.data.person.accounts[0].username").value("agq-jan"))
        graphql("{ me { person { name } } }", user("agq-jan", API_READ_SCOPE))
            .andExpect(jsonPath("$.data.me.person.name").value("Jan Pirc"))

        graphql("""mutation { unlinkAccount(id: "$id") { person { name } linkedHow } }""", user("agq-admin", writes, email = ACCOUNT_LINKER))
            .andExpect(jsonPath("$.data.unlinkAccount.person").value(null as Any?))
            .andExpect(jsonPath("$.data.unlinkAccount.linkedHow").value(null as Any?))
    }

    @Test
    fun `keeps accounts from anyone not signed in`() {
        graphql("{ accounts { id } }")
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"))
        graphql("{ people { name accounts { id } } }")
            .andExpect(jsonPath("$.errors").doesNotExist())
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
