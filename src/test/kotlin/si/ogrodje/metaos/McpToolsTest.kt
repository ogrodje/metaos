package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import si.ogrodje.metaos.Impact.MAJOR
import si.ogrodje.metaos.Impact.MINOR
import si.ogrodje.metaos.Impact.PATCH
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * MetaOS's MCP tools and their version. Clients keep the tools they were given, often until the user refreshes them
 * (the Usage page says how), so every change to them is one to the version MetaOS gives clients, as semver has it, by
 * what it is for a client with the old ones (Impact): from the tools as they were, in the snapshot (mcp-tools.json).
 * `just mcp-tools` writes it and the version, also the Usage page's; and refuses a change that breaks those clients
 * unless it's `just mcp-tools --breaking` (metaos.mcpTools, from -PmcpTools).
 */
class McpToolsTest {

    private val mode = System.getProperty("metaos.mcpTools").orEmpty()

    @Test
    fun `the snapshot has the tools as they are, at their version`() {
        val tools = mcpTools()
        val version = toolsVersion()
        if (!SNAPSHOT.exists()) {
            if (mode.isEmpty()) refuse("No snapshot of the MCP tools ($SNAPSHOT): run `just mcp-tools`")
            return writeTools(tools, version)
        }
        val changes = toolChanges(readSnapshot(), tools)
        if (changes.isEmpty()) return

        val next = version.after(changes.maxOf { it.impact })
        val listed = changes.joinToString("\n") { "- $it" }
        val breaking = changes.any { it.impact == MAJOR }
        when {
            mode.isEmpty() -> refuse(
                "The MCP tools changed since their snapshot, which makes them $next:\n$listed\n" +
                    if (breaking) KEEP_THE_OLD else "Run `just mcp-tools` and commit what it writes."
            )
            breaking && mode != "breaking" -> refuse(
                "These break assistants that still have the old tools, which they may keep for days:\n$listed\n$KEEP_THE_OLD"
            )
            else -> {
                writeTools(tools, next)
                println("MCP tools: $version → $next\n$listed")
            }
        }
    }

    @Test
    fun `the Usage page has the tools' version`() {
        assertEquals(toolsVersion().toString(), usageVersion(), "The version in fe/src/tools.ts")
    }

    // Also on the console, which shows the tests' output but not their failures' messages
    private fun refuse(message: String): Nothing {
        println(message)
        fail(message)
    }

    private companion object {
        const val KEEP_THE_OLD = "Keep the old tools working for 14 days alongside (CLAUDE.md), or else run " +
            "`just mcp-tools --breaking` and commit what it writes."
    }
}

class ToolChangesTest {

    private fun tool(vararg parameters: Pair<String, Map<String, Any?>>, required: List<String> = emptyList()) = mapOf(
        "title" to "Set link",
        "description" to "Set a link",
        "annotations" to mapOf("readOnlyHint" to false),
        "inputSchema" to mapOf("type" to "object", "properties" to mapOf(*parameters), "required" to required),
    )

    private val code = "code" to mapOf("type" to "string", "description" to "Episode code")
    private val url = "url" to mapOf("type" to "string")
    private val platform = "platform" to mapOf("type" to "string", "enum" to listOf("YOUTUBE", "SPOTIFY"))

    private fun impacts(before: Map<String, Any?>, after: Map<String, Any?>) =
        toolChanges(mapOf("set_link" to before), mapOf("set_link" to after)).map { it.impact }

    @Test
    fun `nothing changed is no change`() {
        assertEquals(emptyList(), toolChanges(mapOf("set_link" to tool(code)), mapOf("set_link" to tool(code))))
    }

    @Test
    fun `new tools and optional parameters are minor, what's gone is major`() {
        assertEquals(listOf(MINOR), toolChanges(emptyMap(), mapOf("set_link" to tool(code))).map { it.impact })
        assertEquals(listOf(MAJOR), toolChanges(mapOf("set_link" to tool(code)), emptyMap()).map { it.impact })
        assertEquals(listOf(MINOR), impacts(tool(code), tool(code, url)))
        assertEquals(listOf(MAJOR), impacts(tool(code, url), tool(code)))
    }

    @Test
    fun `a required parameter is major, unless it no longer is`() {
        assertEquals(listOf(MAJOR), impacts(tool(code), tool(code, url, required = listOf("url"))))
        assertEquals(listOf(MAJOR), impacts(tool(code, url), tool(code, url, required = listOf("url"))))
        assertEquals(listOf(MINOR), impacts(tool(code, url, required = listOf("url")), tool(code, url)))
    }

    @Test
    fun `a value more is minor, one less or another type major`() {
        val more = "platform" to mapOf("type" to "string", "enum" to listOf("YOUTUBE", "SPOTIFY", "PATREON"))
        val less = "platform" to mapOf("type" to "string", "enum" to listOf("YOUTUBE"))
        assertEquals(listOf(MINOR), impacts(tool(platform), tool(more)))
        assertEquals(listOf(MAJOR), impacts(tool(platform), tool(less)))
        assertEquals(listOf(MAJOR), impacts(tool(code), tool("code" to mapOf("type" to "integer", "description" to "Episode code"))))
        val list = { item: Map<String, Any?> -> "platforms" to mapOf("type" to "array", "items" to item) }
        assertEquals(listOf(MINOR), impacts(tool(list(platform.second)), tool(list(more.second))))
    }

    @Test
    fun `words are a patch`() {
        assertEquals(listOf(PATCH), impacts(tool(code), tool(code) + ("description" to "Set or clear a link")))
        assertEquals(listOf(PATCH), impacts(tool(code), tool(code) + ("annotations" to mapOf("readOnlyHint" to true))))
        assertEquals(listOf(PATCH), impacts(tool(code), tool("code" to mapOf("type" to "string", "description" to "Code"))))
        // The order of required parameters, or of an enum's values
        assertEquals(listOf(PATCH), impacts(tool(code, url, required = listOf("code", "url")), tool(code, url, required = listOf("url", "code"))))
    }

    @Test
    fun `the version goes up by the biggest change`() {
        val version = Version.parse("0.1.0")
        assertEquals("0.1.1", version.after(PATCH).toString())
        assertEquals("0.2.0", Version.parse("0.1.3").after(MINOR).toString())
        assertEquals("1.0.0", Version.parse("0.4.2").after(MAJOR).toString())
    }
}
