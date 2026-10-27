package si.ogrodje.metaos

import io.modelcontextprotocol.json.McpJsonDefaults
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.provider.tool.SyncStatelessMcpToolProvider
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import org.springframework.objenesis.SpringObjenesis
import si.ogrodje.metaos.Impact.MAJOR
import si.ogrodje.metaos.Impact.MINOR
import si.ogrodje.metaos.Impact.PATCH
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.File

// MetaOS's MCP tools as clients have them, and how a change to them changes their version (McpToolsTest, CLAUDE.md)

typealias Tools = Map<String, Map<String, Any?>>

/** The tools as they were, as `just mcp-tools` last wrote them, by name */
val SNAPSHOT = File("src/test/resources/mcp-tools.json")
private val PROPERTIES = File("src/main/resources/mcp.properties")
private val USAGE = File("fe/src/tools.ts")
private val PROPERTIES_VERSION = Regex("""(?m)^spring\.ai\.mcp\.server\.version=(.*)$""")
private val USAGE_VERSION = Regex("""export const TOOLS_VERSION = '([^']*)'""")

// Sorted, so the snapshot only changes where the tools do
private val json = JsonMapper.builder()
    .enable(SerializationFeature.INDENT_OUTPUT, SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    .build()

/**
 * Every tool, by name, as tools/list has it (without its name): by Spring AI's own code, from the tools' classes rather
 * than their beans, as a component scan would skip the integrations that are off without their credentials, and only
 * calling a tool needs its bean's dependencies
 */
fun mcpTools(): Tools {
    val readers = CachingMetadataReaderFactory()
    val objenesis = SpringObjenesis()
    val beans = PathMatchingResourcePatternResolver().getResources("classpath*:si/ogrodje/metaos/**/*.class")
        .map(readers::getMetadataReader)
        .filter { it.annotationMetadata.hasAnnotatedMethods(McpTool::class.java.name) }
        .map { objenesis.newInstance(Class.forName(it.classMetadata.className)) }
    return SyncStatelessMcpToolProvider(beans).toolSpecifications
        .associate { it.tool().name() to parse(McpJsonDefaults.getMapper().writeValueAsString(it.tool())) - "name" }
        .toSortedMap()
}

@Suppress("UNCHECKED_CAST")
fun parse(text: String) = json.readValue(text, Map::class.java) as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun readSnapshot(): Tools = parse(SNAPSHOT.readText()) as Tools

/** The tools' version, as MetaOS gives it to clients (serverInfo) */
fun toolsVersion() = Version.parse(PROPERTIES_VERSION.find(PROPERTIES.readText())!!.groupValues[1])

/** The tools' version on the Usage page */
fun usageVersion() = USAGE_VERSION.find(USAGE.readText())!!.groupValues[1]

/** The snapshot of [tools], and [version] for MetaOS and the Usage page */
fun writeTools(tools: Tools, version: Version) {
    SNAPSHOT.writeText(json.writeValueAsString(tools) + "\n")
    PROPERTIES.writeText(PROPERTIES.readText().replace(PROPERTIES_VERSION, "spring.ai.mcp.server.version=$version"))
    USAGE.writeText(USAGE.readText().replace(USAGE_VERSION, "export const TOOLS_VERSION = '$version'"))
}

data class Version(val major: Int, val minor: Int, val patch: Int) {
    fun after(impact: Impact) = when (impact) {
        MAJOR -> Version(major + 1, 0, 0)
        MINOR -> Version(major, minor + 1, 0)
        PATCH -> Version(major, minor, patch + 1)
    }

    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(text: String) = text.trim().split('.').map(String::toInt).let { (major, minor, patch) ->
            Version(major, minor, patch)
        }
    }
}

/**
 * What a change to the tools is for a client that still has the old ones, which it may for days: it calls them as they
 * were. MAJOR when that call may now fail or mean something else, MINOR when it still works and there's more it doesn't
 * know of, PATCH when only the words changed (titles, descriptions, hints)
 */
enum class Impact { PATCH, MINOR, MAJOR }

data class ToolChange(val impact: Impact, val what: String) {
    override fun toString() = "${impact.name.lowercase()}: $what"
}

/** How [new] differs from [old] for a client that has [old] */
fun toolChanges(old: Tools, new: Tools): List<ToolChange> {
    val changes = (old.keys + new.keys).sorted().flatMap { name ->
        val before = old[name]
        val after = new[name]
        when {
            after == null -> listOf(ToolChange(MAJOR, "$name is gone"))
            before == null -> listOf(ToolChange(MINOR, "$name is new"))
            else -> toolChanges(name, before, after)
        }
    }
    // Only the order changed, e.g. of an enum's values: no client minds, but the snapshot has to follow
    return changes.ifEmpty { if (old == new) emptyList() else listOf(ToolChange(PATCH, "the order of something")) }
}

private fun toolChanges(name: String, before: Map<String, Any?>, after: Map<String, Any?>) =
    (before.keys + after.keys).sorted().filter { before[it] != after[it] }.flatMap { key ->
        when (key) {
            "inputSchema" -> schemaChanges(name, before[key].asMap(), after[key].asMap())
            "title", "description", "annotations", "_meta" -> listOf(ToolChange(PATCH, "$name's $key changed"))
            // None has one yet (generateOutputSchema): a client checks what a tool answers against the one it has
            "outputSchema" -> listOf(ToolChange(MAJOR, "$name's outputSchema changed"))
            else -> listOf(ToolChange(MAJOR, "$name's $key changed"))
        }
    }

// A parameter's schema, or a tool's: what a client calls it with has to keep working and meaning what it did
private fun schemaChanges(at: String, before: Map<String, Any?>, after: Map<String, Any?>): List<ToolChange> {
    val properties = schemaPropertyChanges(at, before, after)
    val rest = (before.keys + after.keys - setOf("properties", "required")).sorted()
        .filter { before[it] != after[it] }
        .flatMap { key ->
            val was = before[key]
            val now = after[key]
            when (key) {
                "description", "\$schema" -> listOf(ToolChange(PATCH, "$at's $key changed"))
                "enum" -> {
                    val gone = was.asList() - now.asList().toSet()
                    val new = now.asList() - was.asList().toSet()
                    listOfNotNull(
                        gone.takeIf { it.isNotEmpty() }?.let { ToolChange(MAJOR, "$at no longer takes ${it.joinToString()}") },
                        new.takeIf { it.isNotEmpty() }?.let { ToolChange(MINOR, "$at also takes ${it.joinToString()}") },
                    )
                }
                "items" ->
                    if (was == null || now == null) listOf(ToolChange(MAJOR, "$at's items changed"))
                    else schemaChanges("$at[]", was.asMap(), now.asMap())
                else -> listOf(ToolChange(MAJOR, "$at's $key changed from $was to $now"))
            }
        }
    return properties + rest
}

private fun schemaPropertyChanges(at: String, before: Map<String, Any?>, after: Map<String, Any?>): List<ToolChange> {
    val was = before["properties"].asMap()
    val now = after["properties"].asMap()
    val wasRequired = before["required"].asList().toSet()
    val nowRequired = after["required"].asList().toSet()
    return (was.keys + now.keys).sorted().flatMap { property ->
        val path = "$at.$property"
        val required = property in nowRequired
        when {
            now[property] == null -> listOf(ToolChange(MAJOR, "$path is gone"))
            was[property] == null ->
                listOf(if (required) ToolChange(MAJOR, "$path is new, and required") else ToolChange(MINOR, "$path is new"))
            else -> listOfNotNull(
                ToolChange(MAJOR, "$path is required now").takeIf { required && property !in wasRequired },
                ToolChange(MINOR, "$path is optional now").takeIf { !required && property in wasRequired },
            ) + schemaChanges(path, was[property].asMap(), now[property].asMap())
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap() = this as Map<String, Any?>? ?: emptyMap()

private fun Any?.asList() = this as List<*>? ?: emptyList<Any?>()
