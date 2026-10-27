package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What MCP clients get: the tools as their snapshot has them, at their version (McpToolsTest, McpVersion.kt) */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class McpServerTest @Autowired constructor(private val mvc: MockMvc) {

    @Suppress("UNCHECKED_CAST")
    private fun call(method: String, params: String) = mvc.post("/mcp") {
        contentType = MediaType.APPLICATION_JSON
        accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        content = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
        with(jwt().jwt { it.claim("realm_access", APPROVED).claim("scope", MCP_SCOPE) })
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let { parse(it)["result"] as Map<String, Any?> }

    @Test
    fun `tells clients the tools' version, and what to do when theirs are older`() {
        val result = call(
            "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""",
        )
        assertEquals(toolsVersion().toString(), (result["serverInfo"] as Map<*, *>)["version"])
        val instructions = result["instructions"] as String
        assertContains(instructions, "version ${toolsVersion()}")
        assertContains(instructions, "/usage#refresh")
    }

    // Those that are on in tests: the snapshot also has those of the integrations that are off without their credentials
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `lists the tools as their snapshot has them`() {
        val snapshot = readSnapshot()
        val listed = call("tools/list", "{}")["tools"] as List<Map<String, Any?>>
        assertTrue(listed.size > 50, "Tools listed: ${listed.size}")
        for (tool in listed) assertEquals(snapshot[tool["name"]], tool - "name", "${tool["name"]}")
    }

    // As an assistant with older tools calls one: without a parameter it has got since, with a value it no longer takes
    @Test
    fun `says a call that doesn't fit its tool may be one from older tools`() {
        for ((tool, arguments, error) in listOf(
            Triple("get_episode", "{}", "required property 'code' not found"),
            Triple("set_episode_link", """{"code":"S01E01","platform":"MYSPACE"}""", "does not have a value in the enumeration"),
        )) {
            val result = call("tools/call", """{"name":"$tool","arguments":$arguments}""")
            assertEquals(true, result["isError"])
            val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
            assertContains(text, "Tool ($tool) input validation failed")
            assertContains(text, error)
            assertContains(
                text, "they're older than MetaOS's, at version ${toolsVersion()}: refresh them as http://localhost:5173/usage#refresh says",
            )
        }
    }
}
