package si.ogrodje.metaos

import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class SecurityTest @Autowired constructor(private val mvc: MockMvc, private val jdbc: JdbcTemplate) {

    private fun MockHttpServletRequestDsl.initialize() {
        contentType = MediaType.APPLICATION_JSON
        accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        content = """
            {"jsonrpc":"2.0","id":1,"method":"initialize",
             "params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
        """.trimIndent()
    }

    @Test
    fun `publishes protected resource metadata pointing at Keycloak`() {
        mvc.get("/.well-known/oauth-protected-resource/mcp").andExpect {
            status { isOk() }
            jsonPath("$.resource") { value("http://localhost/mcp") }
            jsonPath("$.authorization_servers[0]") { value("http://issuer.invalid/realms/metaos") }
            jsonPath("$.scopes_supported[0]") { value(MCP_SCOPE) }
        }
    }

    @Test
    fun `challenges anonymous MCP requests with resource metadata location`() {
        mvc.post("/mcp") { initialize() }.andExpect {
            status { isUnauthorized() }
            header {
                string(
                    HttpHeaders.WWW_AUTHENTICATE,
                    containsString("resource_metadata=http://localhost/.well-known/oauth-protected-resource/mcp")
                )
            }
        }
    }

    @Test
    fun `rejects tokens without the MCP scope`() {
        mvc.post("/mcp") {
            initialize()
            with(jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", "profile email") })
        }.andExpect { status { isForbidden() } }
    }

    // Clients that honour the hints ask before a destructive call (ToolHintsTest checks every tool's). The title in the
    // annotations is also the tool's own
    @Test
    fun `lists the tools with their titles and hints`() {
        mvc.post("/mcp") {
            contentType = MediaType.APPLICATION_JSON
            accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            content = """{"jsonrpc":"2.0","id":4,"method":"tools/list"}"""
            with(jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", MCP_SCOPE) })
        }.andExpect {
            status { isOk() }
            jsonPath("$.result.tools[?(@.name == 'get_episode')].annotations.readOnlyHint") { value(contains(true)) }
            jsonPath("$.result.tools[?(@.name == 'delete_episode')].annotations.readOnlyHint") { value(contains(false)) }
            jsonPath("$.result.tools[?(@.name == 'delete_episode')].annotations.destructiveHint") { value(contains(true)) }
            jsonPath("$.result.tools[?(@.name == 'create_episode')].annotations.destructiveHint") { value(contains(false)) }
            jsonPath("$.result.tools[?(@.name == 'create_episode')].annotations.idempotentHint") { value(contains(false)) }
            jsonPath("$.result.tools[?(@.name == 'get_episode')].annotations.openWorldHint") { value(contains(false)) }
            jsonPath("$.result.tools[?(@.name == 'get_episode')].annotations.title") { value(contains("Get episode")) }
            jsonPath("$.result.tools[?(@.name == 'get_episode')].title") { value(contains("Get episode")) }
        }
    }

    // Stateless (application.properties): no session to keep, so any pod can answer
    @Test
    fun `accepts tokens with the MCP scope, without a session`() {
        mvc.post("/mcp") {
            initialize()
            with(jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", "profile $MCP_SCOPE") })
        }.andExpect {
            status { isOk() }
            header { doesNotExist("Mcp-Session-Id") }
            jsonPath("$.result.protocolVersion") { exists() }
        }
    }

    // ChatGPT starts with server/discover, of MCP's newer revision, and falls back to initialize when told the method
    // isn't known - as a JSON-RPC error; it gave up on the HTTP 500 that MCP's SDK 2.0 answered (build.gradle.kts)
    @Test
    fun `answers a method it doesn't know with method not found`() {
        mvc.post("/mcp") {
            contentType = MediaType.APPLICATION_JSON
            accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            content = """{"jsonrpc":"2.0","id":7,"method":"server/discover","params":{}}"""
            with(jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", MCP_SCOPE) })
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(7) }
            jsonPath("$.error.code") { value(-32601) }
        }
    }

    // Keycloak can issue the scope to more than approved users (Security.kt, APPROVED_ROLE)
    @Test
    fun `rejects the MCP scope without approval`() {
        mvc.post("/mcp") {
            initialize()
            with(jwt().jwt { it.claim("scope", "profile $MCP_SCOPE") })
        }.andExpect { status { isForbidden() } }
        mvc.post("/mcp") {
            initialize()
            with(jwt().jwt { it.claim("realm_access", mapOf("roles" to listOf("default-roles-metaos"))).claim("scope", MCP_SCOPE) })
        }.andExpect { status { isForbidden() } }
    }

    // A client registered through DCR for its own service account: client_credentials, no user, no approval gate
    @Test
    fun `rejects a client's own service account`() {
        mvc.post("/mcp") {
            contentType = MediaType.APPLICATION_JSON
            accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            content = """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"create_person","arguments":{"name":"Service Sanja"}}}
            """.trimIndent()
            with(jwt().jwt { it.subject("sa-1").claim("preferred_username", "service-account-6e6f6416").claim("scope", "profile email $MCP_SCOPE") })
        }.andExpect { status { isForbidden() } }
        assertEquals(0, jdbc.queryForObject("select count(*) from people where name = 'Service Sanja'", Int::class.java))
    }

    // A call on its own, as it may reach another pod than the one that answered initialize: the tool still knows who
    // called it, and the audit trail has them and the tool
    @Test
    fun `calls tools without a session, as the token's user`() {
        mvc.post("/mcp") {
            contentType = MediaType.APPLICATION_JSON
            accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            content = """
                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                 "params":{"name":"create_person","arguments":{"name":"Stateless Stanko"}}}
            """.trimIndent()
            with(jwt().jwt { it.claim("realm_access", APPROVED).subject("u-42").claim("preferred_username", "stanko").claim("scope", MCP_SCOPE) })
        }.andExpect {
            status { isOk() }
            jsonPath("$.result.isError") { value(false) }
            jsonPath("$.result.content[0].text") { value(containsString("Stateless Stanko")) }
        }
        val (actor, tool) = jdbc.queryForObject(
            "select actor, tool from audit_log where table_name = 'people' and new_row ->> 'name' = 'Stateless Stanko'",
        ) { rs, _ -> rs.getString(1) to rs.getString(2) }!!
        assertEquals("stanko" to "create_person", actor to tool)
    }

    @Test
    fun `keeps the stream of changes public`() {
        mvc.get("/events") { accept(MediaType.TEXT_EVENT_STREAM) }.andExpect {
            status { isOk() }
            request { asyncStarted() }
        }
    }

    @Test
    fun `keeps health endpoint public`() {
        mvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    @Test
    fun `keeps probe endpoints public without details`() {
        listOf("/livez", "/readyz").forEach { path ->
            mvc.get(path).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
                jsonPath("$.components") { doesNotExist() }
            }
        }
    }

    @Test
    fun `keeps prometheus endpoint public`() {
        mvc.get("/actuator/prometheus").andExpect {
            status { isOk() }
            content { string(containsString("jvm_memory_used_bytes")) }
        }
    }
}
