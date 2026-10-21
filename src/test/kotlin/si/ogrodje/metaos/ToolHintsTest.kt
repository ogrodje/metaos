package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every MCP tool says whether it only reads, adds, or changes and removes what's there (readOnlyHint, destructiveHint),
 * so clients that honour the hints ask before a destructive call even when the user auto-approves the rest. A tool
 * without a hint counts as destructive (Spring AI's default), so this checks the reading and adding ones by name.
 * restore_* counts as destructive: it can rename, recode or clear the email of what it brings back.
 */
class ToolHintsTest {

    private data class Hints(val name: String, val readOnly: Boolean, val destructive: Boolean)

    // Read from the class files: a component scan would skip the integrations that are off without their credentials
    private val tools = CachingMetadataReaderFactory().let { readers ->
        PathMatchingResourcePatternResolver().getResources("classpath*:si/ogrodje/metaos/**/*.class")
            .flatMap { readers.getMetadataReader(it).annotationMetadata.getAnnotatedMethods(McpTool::class.java.name) }
            .map {
                val tool = it.annotations.get(McpTool::class.java)
                val hints = tool.getAnnotation("annotations", McpTool.McpAnnotations::class.java)
                Hints(tool.getString("name"), hints.getBoolean("readOnlyHint"), hints.getBoolean("destructiveHint"))
            }
    }

    private val reads = Regex("^(list_|get_|read_|search|view_)|^(whoami|my_episodes)$")
    private val adds = Regex("^(create_|link_|assign_)|^match_episode_documents$")

    @Test
    fun `tools that only read say so, and only they do`() {
        assertTrue(tools.size > 80, "found ${tools.size} tools")
        assertEquals(
            tools.filter { reads.containsMatchIn(it.name) }.map { it.name }.sorted(),
            tools.filter { it.readOnly }.map { it.name }.sorted(),
        )
    }

    @Test
    fun `tools that only add aren't marked destructive, and every other change is`() {
        val changes = tools.filterNot { it.readOnly }
        assertEquals(
            changes.filter { adds.containsMatchIn(it.name) }.map { it.name }.sorted(),
            changes.filterNot { it.destructive }.map { it.name }.sorted(),
        )
    }
}
