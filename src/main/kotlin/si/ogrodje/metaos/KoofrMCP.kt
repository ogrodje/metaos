package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.net.URLConnection
import java.util.Base64

data class KoofrContentDto(val file: KoofrFile, val text: String, val truncated: Boolean)

const val KOOFR_PLACE = "A folder shared with Ogrodje's bot in Koofr, by name or ID (list_koofr_places)"
const val KOOFR_PATH = "Path in the place, e.g. /Epizode/S05E08; / is the shared folder itself"

// What read_koofr_file returns, and what upload_koofr_file takes
private const val MAX_READ = 200 * 1024
private const val MAX_UPLOAD = 10 * 1024 * 1024
private const val SEARCH_RESULTS = 25

private val TEXT_TYPES = setOf("application/json", "application/xml", "application/x-yaml", "application/yaml", "application/x-subrip")
private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "srt", "vtt", "html", "htm")

/** Whether a file is text, by its type or else its extension - also for Dropbox's files */
fun isTextFile(name: String, contentType: String?) =
    contentType?.substringBefore(';')?.trim()?.let { it.startsWith("text/") || it in TEXT_TYPES } == true ||
        name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

/** "/a/b", from "a/b" or "/a/b/"; no ".." */
fun koofrPath(path: String?): String {
    val parts = path.orEmpty().split('/').filter { it.isNotEmpty() }
    require(parts.none { it == "." || it == ".." }) { "$path: no . or .. in Koofr paths" }
    return "/" + parts.joinToString("/")
}

/** A place by ID or name - only folders shared with the bot, not its own Koofr space */
fun Koofr.sharedPlace(ref: String): KoofrPlace {
    val places = places()
    return places.firstOrNull { it.id == ref } ?: places.firstOrNull { it.name.equals(ref.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("$ref isn't a folder shared with the bot; there's ${places.joinToString { it.name }.ifEmpty { "none" }}")
}

/** A file or folder moved through MetaOS, so that what's linked to it can follow (KoofrFoldersMCP) */
data class KoofrMoved(val fromPlace: String, val fromPath: String, val toPlace: String, val toPath: String)

@Component
@ConditionalOnKoofr
class KoofrMCP(private val koofr: Koofr, private val events: ApplicationEventPublisher) {

    private fun place(ref: String) = koofr.sharedPlace(ref)

    private fun writablePlace(ref: String) = place(ref).also { require(it.writable) { "${it.name} is shared with the bot read-only" } }

    @McpTool(
        name = "list_koofr_places",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "List the folders shared with Ogrodje's bot in Koofr - MetaOS works only in these - and whether it may change them"
    )
    fun places(): List<KoofrPlace> = koofr.places()

    @McpTool(
        name = "list_koofr_folder",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "List the files and folders directly in a Koofr folder"
    )
    fun list(
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = "$KOOFR_PATH; / when omitted", required = false) path: String?,
    ): List<KoofrFile> = koofr.list(place(place).id, koofrPath(path)).sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))

    @McpTool(
        name = "search_koofr",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "Search the folders shared with Ogrodje's bot in Koofr for files and folders by name, most relevant first, " +
            "at most $SEARCH_RESULTS"
    )
    fun search(
        @McpToolParam(description = "Words in the name") text: String,
        @McpToolParam(description = "$KOOFR_PLACE; all of them when omitted", required = false) place: String?,
    ): List<KoofrFile> {
        require(text.isNotBlank()) { "Nothing to search for" }
        val placeId = place?.let { place(it).id }
        val shared = koofr.places().map { it.id }.toSet()
        // Koofr searches everything the bot can see, its own space too
        return koofr.search(text, placeId, SEARCH_RESULTS * 2).filter { it.place in shared }.take(SEARCH_RESULTS)
    }

    @McpTool(
        name = "read_koofr_file",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "Read a text file (txt, md, csv, json, srt, ...) from Koofr, at most ${MAX_READ / 1024} kB of it. " +
            "For other files, list_koofr_folder tells their size and type"
    )
    fun read(
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = KOOFR_PATH) path: String,
    ): KoofrContentDto {
        val placeId = place(place).id
        val file = requireNotNull(koofr.file(placeId, koofrPath(path))) { "There's no $path in Koofr" }
        require(!file.folder) { "$path is a folder; list_koofr_folder lists it" }
        require(isTextFile(file.name, file.contentType)) { "$path isn't a text file (${file.contentType}, ${file.size} bytes)" }
        val bytes = koofr.read(placeId, file.path, MAX_READ + 1)
        return KoofrContentDto(file, bytes.copyOf(minOf(bytes.size, MAX_READ)).toString(Charsets.UTF_8), bytes.size > MAX_READ)
    }

    @McpTool(
        name = "upload_koofr_file",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Upload a file into a Koofr folder: text, or base64 for anything else, at most ${MAX_UPLOAD / 1024 / 1024} MB. " +
            "A file of the same name is only replaced with overwrite, and Koofr keeps the old one as a version"
    )
    fun upload(
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = "Folder to upload into, e.g. /Epizode/S05E08") folder: String,
        @McpToolParam(description = "File name, e.g. notes.md") name: String,
        @McpToolParam(description = "The file's content: text, or base64 if base64 is true") content: String,
        @McpToolParam(description = "content is base64", required = false) base64: Boolean?,
        @McpToolParam(description = "Replace a file of the same name", required = false) overwrite: Boolean?,
    ): KoofrFile {
        val placeId = writablePlace(place).id
        require(name.isNotBlank() && '/' !in name) { "A file name, without folders: $name" }
        val bytes = if (base64 == true) Base64.getMimeDecoder().decode(content) else content.toByteArray()
        require(bytes.size <= MAX_UPLOAD) { "$name is ${bytes.size} bytes; at most $MAX_UPLOAD" }
        val contentType = URLConnection.guessContentTypeFromName(name)
            ?: if (base64 == true) "application/octet-stream" else "text/plain; charset=utf-8"
        return koofr.upload(placeId, koofrPath(folder), name, contentType, bytes, overwrite == true)
    }

    @McpTool(
        name = "create_koofr_folder",
        annotations = McpTool.McpAnnotations(destructiveHint = false),
        description = "Create a folder in Koofr"
    )
    fun createFolder(
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = "Folder to create it in; / when omitted", required = false) parent: String?,
        @McpToolParam(description = "Name of the new folder") name: String,
    ): KoofrFile {
        val placeId = writablePlace(place).id
        require(name.isNotBlank() && '/' !in name) { "A folder name, without folders: $name" }
        val parentPath = koofrPath(parent)
        koofr.createFolder(placeId, parentPath, name)
        return requireNotNull(koofr.file(placeId, koofrPath("$parentPath/$name")))
    }

    // Transactional, so that the episodes' and sessions' links follow the move in the same transaction
    @Transactional
    @McpTool(
        name = "move_koofr_file",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Move or rename a file or folder in Koofr, also into another shared folder. Never replaces what's at the " +
            "new path. Folders linked to episodes or sessions stay linked"
    )
    fun move(
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = KOOFR_PATH) path: String,
        @McpToolParam(description = "New path, with the name, e.g. /Arhiv/notes.md") to: String,
        @McpToolParam(description = "Place to move it into; the same when omitted", required = false) toPlace: String?,
    ): KoofrFile {
        val from = writablePlace(place).id
        val into = toPlace?.let { writablePlace(it).id } ?: from
        val (source, target) = koofrPath(path) to koofrPath(to)
        require(source != "/") { "The shared folder itself can't be moved" }
        requireNotNull(koofr.file(from, source)) { "There's no $path in Koofr" }
        require(koofr.file(into, target) == null) { "$to is already there" }
        koofr.move(from, source, into, target)
        events.publishEvent(KoofrMoved(from, source, into, target))
        return requireNotNull(koofr.file(into, target))
    }
}
