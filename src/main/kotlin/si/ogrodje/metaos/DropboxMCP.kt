package si.ogrodje.metaos

import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.util.Base64

data class DropboxContentDto(val file: DropboxFile, val text: String, val truncated: Boolean)

const val DROPBOX_PLACE = "A folder shared with Ogrodje's bot in Dropbox, by name or ID (list_dropbox_places)"
const val DROPBOX_PATH = "Path in the place, e.g. /S05E08/cover.png; / is the shared folder itself"

// What read_dropbox_file returns, and what upload_dropbox_file takes
private const val MAX_READ = 200 * 1024
private const val MAX_UPLOAD = 10 * 1024 * 1024
private const val SEARCH_RESULTS = 25

// What view_dropbox_image shows: big enough to judge artwork by, small enough for a model to take in
private const val VIEW_SIZE = "w1024h768"

/** "/a/b", from "a/b" or "/a/b/"; no ".." */
fun dropboxPath(path: String?): String {
    val parts = path.orEmpty().split('/').filter { it.isNotEmpty() }
    require(parts.none { it == "." || it == ".." }) { "$path: no . or .. in Dropbox paths" }
    return "/" + parts.joinToString("/")
}

/** A place by ID or name - only folders shared with the bot, not its own Dropbox */
fun Dropbox.sharedPlace(ref: String): DropboxPlace {
    val places = places()
    return places.firstOrNull { it.id == ref } ?: places.firstOrNull { it.name.equals(ref.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("$ref isn't a folder shared with the bot; there's ${places.joinToString { it.name }.ifEmpty { "none" }}")
}

@Component
@ConditionalOnDropbox
class DropboxMCP(private val dropbox: Dropbox) {

    private fun place(ref: String) = dropbox.sharedPlace(ref)

    private fun writablePlace(ref: String) = place(ref).also { require(it.writable) { "${it.name} is shared with the bot read-only" } }

    @McpTool(
        name = "list_dropbox_places",
        annotations = McpTool.McpAnnotations(
            title = "List Dropbox places",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the folders shared with Ogrodje's bot in Dropbox, e.g. Artwork - MetaOS works only in these - and " +
            "whether it may change them"
    )
    fun places(): List<DropboxPlace> = dropbox.places()

    @McpTool(
        name = "list_dropbox_folder",
        annotations = McpTool.McpAnnotations(
            title = "List Dropbox folder",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the files and folders directly in a Dropbox folder"
    )
    fun list(
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = "$DROPBOX_PATH; / when omitted", required = false) path: String?,
    ): List<DropboxFile> = dropbox.list(place(place).id, dropboxPath(path)).sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))

    @McpTool(
        name = "search_dropbox",
        annotations = McpTool.McpAnnotations(
            title = "Search Dropbox",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Search the folders shared with Ogrodje's bot in Dropbox for files and folders by name, most relevant " +
            "first, at most $SEARCH_RESULTS"
    )
    fun search(
        @McpToolParam(description = "Words in the name") text: String,
        @McpToolParam(description = "$DROPBOX_PLACE; all of them when omitted", required = false) place: String?,
    ): List<DropboxFile> {
        require(text.isNotBlank()) { "Nothing to search for" }
        return dropbox.search(text, place?.let { place(it).id }, SEARCH_RESULTS)
    }

    @McpTool(
        name = "read_dropbox_file",
        annotations = McpTool.McpAnnotations(
            title = "Read Dropbox file",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Read a text file (txt, md, csv, json, srt, ...) from Dropbox, at most ${MAX_READ / 1024} kB of it. " +
            "view_dropbox_image shows pictures; for other files, list_dropbox_folder tells their size and type"
    )
    fun read(
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = DROPBOX_PATH) path: String,
    ): DropboxContentDto {
        val file = existingFile(place(place).id, path)
        require(isTextFile(file.name, file.contentType)) { "$path isn't a text file (${file.contentType}, ${file.size} bytes)" }
        val bytes = dropbox.read(file.place, file.path, MAX_READ + 1)
        return DropboxContentDto(file, bytes.copyOf(minOf(bytes.size, MAX_READ)).toString(Charsets.UTF_8), bytes.size > MAX_READ)
    }

    // An image, as MCP clients show them to their model, rather than JSON
    @McpTool(
        name = "view_dropbox_image",
        annotations = McpTool.McpAnnotations(
            title = "View Dropbox image",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Look at a picture in Dropbox, e.g. artwork for an episode: a JPEG of it, at most 1024 × 768 " +
            "(jpg, png, gif, webp, tiff or bmp, up to 20 MB)"
    )
    fun view(
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = DROPBOX_PATH) path: String,
    ): McpSchema.CallToolResult {
        val file = existingFile(place(place).id, path)
        val image = dropbox.thumbnail(file.place, file.path, VIEW_SIZE)
        return McpSchema.CallToolResult.builder()
            .addTextContent("${file.path} (${file.contentType ?: "unknown type"}, ${file.size} bytes)")
            .addContent(McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(image.bytes), image.contentType).build())
            .build()
    }

    @McpTool(
        name = "upload_dropbox_file",
        annotations = McpTool.McpAnnotations(
            title = "Upload Dropbox file",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Upload a file into a Dropbox folder: text, or base64 for anything else, at most ${MAX_UPLOAD / 1024 / 1024} MB. " +
            "A file of the same name is only replaced with overwrite, and Dropbox keeps the old one as a version"
    )
    fun upload(
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = "Folder to upload into, e.g. /S05E08") folder: String,
        @McpToolParam(description = "File name, e.g. cover.png") name: String,
        @McpToolParam(description = "The file's content: text, or base64 if base64 is true") content: String,
        @McpToolParam(description = "content is base64", required = false) base64: Boolean?,
        @McpToolParam(description = "Replace a file of the same name", required = false) overwrite: Boolean?,
    ): DropboxFile {
        val placeId = writablePlace(place).id
        require(name.isNotBlank() && '/' !in name) { "A file name, without folders: $name" }
        val bytes = if (base64 == true) Base64.getMimeDecoder().decode(content) else content.toByteArray()
        require(bytes.size <= MAX_UPLOAD) { "$name is ${bytes.size} bytes; at most $MAX_UPLOAD" }
        return dropbox.upload(placeId, dropboxPath(folder), name, bytes, overwrite == true)
    }

    @McpTool(
        name = "create_dropbox_folder",
        annotations = McpTool.McpAnnotations(
            title = "Create Dropbox folder",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Create a folder in Dropbox"
    )
    fun createFolder(
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = "Folder to create it in; / when omitted", required = false) parent: String?,
        @McpToolParam(description = "Name of the new folder") name: String,
    ): DropboxFile {
        val placeId = writablePlace(place).id
        require(name.isNotBlank() && '/' !in name) { "A folder name, without folders: $name" }
        val parentPath = dropboxPath(parent)
        dropbox.createFolder(placeId, parentPath, name)
        return requireNotNull(dropbox.file(placeId, dropboxPath("$parentPath/$name")))
    }

    @McpTool(
        name = "move_dropbox_file",
        annotations = McpTool.McpAnnotations(
            title = "Move Dropbox file",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Move or rename a file or folder in Dropbox, also into another shared folder. Never replaces what's at " +
            "the new path"
    )
    fun move(
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = DROPBOX_PATH) path: String,
        @McpToolParam(description = "New path, with the name, e.g. /Archive/cover.png") to: String,
        @McpToolParam(description = "Place to move it into; the same when omitted", required = false) toPlace: String?,
    ): DropboxFile {
        val from = writablePlace(place).id
        val into = toPlace?.let { writablePlace(it).id } ?: from
        val (source, target) = dropboxPath(path) to dropboxPath(to)
        require(source != "/") { "The shared folder itself can't be moved" }
        existing(from, source)
        require(dropbox.file(into, target) == null) { "$to is already there" }
        dropbox.move(from, source, into, target)
        return requireNotNull(dropbox.file(into, target))
    }

    private fun existing(place: String, path: String) =
        requireNotNull(dropbox.file(place, dropboxPath(path))) { "There's no $path in Dropbox" }

    private fun existingFile(place: String, path: String) =
        existing(place, path).also { require(!it.folder) { "$path is a folder; list_dropbox_folder lists it" } }
}
