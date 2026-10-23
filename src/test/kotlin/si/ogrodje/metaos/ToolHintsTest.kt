package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every MCP tool has a title and says what it does to what's there, so clients show it by name and those that honour
 * the hints ask before a destructive call even when the user auto-approves the rest. Spring AI's defaults are the
 * cautious ones (destructive, not idempotent, open world), and it sends all four hints, also those left out, so this
 * checks every tool's against what its name says it does.
 * - readOnlyHint: only reads. Those also say they're idempotent and not destructive, as ChatGPT wants them explicit.
 * - destructiveHint: changes or removes what's there. Adding isn't, a comment included, nor is restore_link, which only
 *   puts a removed link back as it was; the other restore_* can rename, recode or clear the email of what they bring back.
 * - idempotentHint: a second call changes nothing more. Only creating episodes, people and sessions, and commenting,
 *   isn't: the others find what's there (shows by name, folders and files by path, links) and fail or keep it.
 * - openWorldHint: reaches out to anyone. Only setting a picture is: it may fetch it from Hygraph and publishes it at
 *   the public bucket. Drive, Dropbox and Koofr aren't, as MetaOS works only in the folders shared with its bot.
 */
class ToolHintsTest {

    private data class Hints(
        val name: String,
        val title: String,
        val readOnly: Boolean,
        val destructive: Boolean,
        val idempotent: Boolean,
        val openWorld: Boolean,
    )

    // Read from the class files: a component scan would skip the integrations that are off without their credentials
    private val tools = CachingMetadataReaderFactory().let { readers ->
        PathMatchingResourcePatternResolver().getResources("classpath*:si/ogrodje/metaos/**/*.class")
            .flatMap { readers.getMetadataReader(it).annotationMetadata.getAnnotatedMethods(McpTool::class.java.name) }
            .map {
                val tool = it.annotations.get(McpTool::class.java)
                val hints = tool.getAnnotation("annotations", McpTool.McpAnnotations::class.java)
                Hints(
                    tool.getString("name"),
                    hints.getString("title"),
                    hints.getBoolean("readOnlyHint"),
                    hints.getBoolean("destructiveHint"),
                    hints.getBoolean("idempotentHint"),
                    hints.getBoolean("openWorldHint"),
                )
            }
    }

    private val reads = Regex("^(list_|get_|read_|search|view_)|^(whoami|my_episodes)$")
    private val adds = Regex("^(create_|link_|assign_|comment_on_)|^(match_episode_documents|restore_link)$")
    private val repeatsAdd = setOf("create_episode", "create_person", "create_session", "comment_on_episode", "comment_on_session")
    private val openWorld = setOf("set_episode_image", "set_person_avatar")

    private fun List<Hints>.names() = map { it.name }.sorted()

    @Test
    fun `every tool has a title`() {
        assertTrue(tools.size > 80, "found ${tools.size} tools")
        assertEquals(emptyList(), tools.filter { it.title.isBlank() }.names())
    }

    @Test
    fun `tools that only read say so, and only they do`() {
        assertEquals(tools.filter { reads.containsMatchIn(it.name) }.names(), tools.filter { it.readOnly }.names())
        assertEquals(emptyList(), tools.filter { it.readOnly && (it.destructive || !it.idempotent) }.names())
    }

    @Test
    fun `tools that only add aren't marked destructive, and every other change is`() {
        val changes = tools.filterNot { it.readOnly }
        assertEquals(
            changes.filter { adds.containsMatchIn(it.name) }.names(),
            changes.filterNot { it.destructive }.names(),
        )
    }

    @Test
    fun `only the tools that add again on a second call aren't idempotent`() {
        assertEquals(repeatsAdd.sorted(), tools.filterNot { it.idempotent }.names())
    }

    @Test
    fun `only the tools that publish pictures are open world`() {
        assertEquals(openWorld.sorted(), tools.filter { it.openWorld }.names())
    }
}
