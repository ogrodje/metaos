package si.ogrodje.metaos

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.health.contributor.Status
import java.net.URLConnection
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dropbox in memory: places, folders and files with their content, and IDs that stay with them through moves */
class FakeDropbox : Dropbox {
    val shared = mutableListOf<DropboxPlace>()
    private val files = mutableMapOf<Pair<String, String>, Pair<DropboxFile, ByteArray?>>()
    private var ids = 0

    fun place(id: String, name: String, writable: Boolean = true) =
        DropboxPlace(id, name, "Ogrodje", writable, "/$name").also { shared += it; put(id, "/", null) }

    fun put(place: String, path: String, content: ByteArray?) {
        val name = if (path == "/") shared.first { it.id == place }.name else path.substringAfterLast('/')
        val contentType = content?.let { URLConnection.guessContentTypeFromName(name) }
        val id = if (path == "/") null else files[place to path]?.first?.id ?: "id:${++ids}"
        files[place to path] =
            DropboxFile(place, path, name, content == null, content?.size?.toLong(), null, contentType, id, content?.let(::dropboxContentHash)) to content
    }

    override fun places() = shared.toList()
    override fun file(place: String, path: String) = files[place to path]?.first
    override fun fileById(place: String, id: String) = files.values.map { it.first }.firstOrNull { it.place == place && it.id == id }
    override fun folders(place: String) =
        files.values.map { it.first }.filter { it.place == place && it.folder && it.id != null }.associate { it.id!! to it.path }

    /** Whether Dropbox makes links for members only, as a Basic account may not */
    var membersLinks = true

    override fun membersLink(id: String): String {
        check(membersLinks) { "Dropbox won't make the bot a link for members only" }
        return "https://www.dropbox.com/scl/fo/${id.removePrefix("id:")}/members"
    }
    override fun list(place: String, folder: String) =
        files.values.map { it.first }.filter { it.place == place && it.path != "/" && it.path.substringBeforeLast('/').ifEmpty { "/" } == folder }

    override fun search(text: String, place: String?, limit: Int) =
        files.values.map { it.first }.filter { (place == null || it.place == place) && text.lowercase() in it.name.lowercase() }.take(limit)

    override fun read(place: String, path: String, maxBytes: Int) =
        files.getValue(place to path).second!!.let { it.copyOf(minOf(it.size, maxBytes)) }

    // The image itself stands in for its thumbnail
    override fun thumbnail(place: String, path: String, size: String) =
        DropboxImage("image/jpeg", requireNotNull(files[place to path]?.second) { "Dropbox can't make a picture of $path: path not found" })

    // Renamed as Dropbox does, with a number after the name
    override fun upload(place: String, folder: String, name: String, content: ByteArray, overwrite: Boolean, rename: Boolean): DropboxFile {
        fun at(name: String) = "${folder.trimEnd('/')}/$name"
        val (base, extension) = name.substringBeforeLast('.') to name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val path = if (rename && !overwrite && file(place, at(name)) != null)
            generateSequence(1) { it + 1 }.map { at("$base ($it)$extension") }.first { file(place, it) == null }
        else at(name)
        require(overwrite || file(place, path) == null) { "$path is already there" }
        put(place, path, content)
        return file(place, path)!!
    }

    override fun createFolder(place: String, parent: String, name: String) = put(place, "${parent.trimEnd('/')}/$name", null)

    // A folder takes what's in it along
    override fun move(place: String, path: String, toPlace: String, toPath: String) {
        files.keys.filter { (at, p) -> at == place && (p == path || p.startsWith("$path/")) }.forEach { key ->
            val (file, content) = files.remove(key)!!
            val moved = toPath + key.second.removePrefix(path)
            files[toPlace to moved] = file.copy(place = toPlace, path = moved, name = moved.substringAfterLast('/')) to content
        }
    }
}

class DropboxTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    private val dropbox = FakeDropbox().apply {
        place("1001", "Artwork")
        place("1002", "Brand", writable = false)
        put("1001", "/S05E08", null)
        put("1001", "/S05E08/cover.jpg", jpeg)
        put("1001", "/S05E08/brief.md", "# Naslovnica\n\nČ, š in ž".toByteArray())
        put("1002", "/logo.txt", "Ogrodje".toByteArray())
    }
    private val mcp = DropboxMCP(dropbox)

    @Test
    fun `normalises paths and refuses to climb out of them`() {
        assertEquals("/", dropboxPath(null))
        assertEquals("/S05E08/cover.jpg", dropboxPath("S05E08//cover.jpg/"))
        assertFailsWith<IllegalArgumentException> { dropboxPath("/S05E08/../..") }
    }

    @Test
    fun `lists, searches, reads and shows only what's in the folders shared with the bot`() {
        assertEquals(listOf("Artwork", "Brand"), mcp.places().map { it.name })
        assertEquals(listOf("S05E08", "brief.md", "cover.jpg"), (mcp.list("artwork", null) + mcp.list("1001", "S05E08")).map { it.name })
        assertFailsWith<IllegalArgumentException> { mcp.list("1003", "/") }
        assertEquals(listOf("/S05E08/cover.jpg"), mcp.search("cover", "Artwork").map { it.path })
        assertFailsWith<IllegalArgumentException> { mcp.search(" ", null) }

        val brief = mcp.read("Artwork", "/S05E08/brief.md")
        assertEquals("# Naslovnica\n\nČ, š in ž", brief.text)
        assertEquals(false, brief.truncated)
        assertEquals("Ogrodje", mcp.read("Brand", "logo.txt").text)
        assertFailsWith<IllegalArgumentException> { mcp.read("Artwork", "/S05E08/cover.jpg") }
        assertFailsWith<IllegalArgumentException> { mcp.read("Artwork", "/S05E08") }
        assertFailsWith<IllegalArgumentException> { mcp.read("Artwork", "/nothing.md") }

        val view = mcp.view("Artwork", "/S05E08/cover.jpg")
        val image = view.content().filterIsInstance<McpSchema.ImageContent>().single()
        assertEquals("image/jpeg", image.mimeType())
        assertContentEquals(jpeg, Base64.getDecoder().decode(image.data()))
        assertTrue("/S05E08/cover.jpg" in view.content().filterIsInstance<McpSchema.TextContent>().single().text())
        assertFailsWith<IllegalArgumentException> { mcp.view("Artwork", "/S05E08") }
    }

    @Test
    fun `uploads, creates folders and moves without replacing anything unasked`() {
        val uploaded = mcp.upload("Artwork", "/S05E08", "notes.md", "Notes", base64 = null, overwrite = null)
        assertEquals("/S05E08/notes.md", uploaded.path)
        assertFailsWith<IllegalArgumentException> { mcp.upload("Artwork", "/S05E08", "notes.md", "Again", null, null) }
        mcp.upload("Artwork", "/S05E08", "notes.md", "Notes v2", base64 = null, overwrite = true)
        assertEquals("Notes v2", mcp.read("Artwork", "/S05E08/notes.md").text)

        mcp.upload("Artwork", "/S05E08", "cover v2.jpg", Base64.getEncoder().encodeToString(jpeg), base64 = true, overwrite = null)
        assertEquals("image/jpeg", dropbox.file("1001", "/S05E08/cover v2.jpg")?.contentType)
        assertFailsWith<IllegalArgumentException> { mcp.upload("Brand", "/", "x.md", "read-only", null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.upload("Artwork", "/", "../x.md", "no", null, null) }

        assertEquals("/Archive", mcp.createFolder("Artwork", null, "Archive").path)
        val moved = mcp.move("Artwork", "/S05E08/cover v2.jpg", "/Archive/S05E08 cover v2.jpg", null)
        assertEquals("S05E08 cover v2.jpg", moved.name)
        assertNull(dropbox.file("1001", "/S05E08/cover v2.jpg"))
        assertFailsWith<IllegalArgumentException> { mcp.move("Artwork", "/S05E08/cover.jpg", "/Archive/S05E08 cover v2.jpg", null) }
        assertFailsWith<IllegalArgumentException> { mcp.move("Artwork", "/S05E08/cover.jpg", "/cover.jpg", "Brand") }
        assertFailsWith<IllegalArgumentException> { mcp.move("Artwork", "/", "/Artwork 2", null) }
    }

    @Test
    fun `reports whether the bot can reach Dropbox`() {
        val registry = SimpleMeterRegistry()
        val indicator = DropboxHealthIndicator(dropbox, registry)
        indicator.check()
        assertEquals(Status.UP, indicator.health().status)
        assertEquals("Artwork, Brand", indicator.health().details["places"])
        assertEquals(1.0, registry.get("metaos.dropbox.connected").gauge().value())
    }

    /** Against the real account, as the bot - only with its refresh token in the environment (.envrc). Reads only. */
    @Test
    @EnabledIfEnvironmentVariable(named = "DROPBOX_REFRESH_TOKEN", matches = ".+")
    fun `reads the folders shared with the bot`() {
        val (key, secret) = System.getenv("DROPBOX_APP_KEY") to System.getenv("DROPBOX_APP_SECRET")
        val storage = DropboxStorage(key, secret, System.getenv("DROPBOX_REFRESH_TOKEN"))
        storage.places().forEach { place ->
            assertNotNull(storage.file(place.id, "/"))
            val files = storage.list(place.id, "/")
            files.firstOrNull()?.let { assertEquals(it, storage.file(place.id, it.path)?.copy(modified = it.modified)) }
            files.firstOrNull { it.id != null }?.let { assertEquals(it.path, storage.fileById(place.id, it.id!!)?.path) }
            files.firstOrNull { it.folder }?.let { assertEquals(it.path, storage.folders(place.id)[it.id]) }
            files.firstOrNull { !it.folder }?.let { assertTrue(storage.read(place.id, it.path, 16).size <= 16) }
            if (place.path != null) files.firstOrNull()?.let { file ->
                assertTrue(storage.search(file.name, place.id, 5).all { it.place == place.id })
            }
        }
        assertNull(storage.places().firstOrNull()?.let { storage.file(it.id, "/no such file, MetaOS") })

        // A picture of the first image within a few folders of a place's top
        storage.places().firstNotNullOfOrNull { place ->
            val folders = ArrayDeque(listOf("/"))
            var listed = 0
            var image: DropboxFile? = null
            while (image == null && folders.isNotEmpty() && listed++ < 10) {
                val files = storage.list(place.id, folders.removeFirst())
                image = files.firstOrNull { it.contentType?.startsWith("image/") == true }
                folders += files.filter { it.folder }.map { it.path }
            }
            image
        }?.let { image ->
            val picture = storage.thumbnail(image.place, image.path, "w256h256")
            assertEquals("image/jpeg", picture.contentType)
            assertEquals(0xFF.toByte(), picture.bytes.first())
            // Not there, and not ASCII
            val missing = assertFailsWith<IllegalArgumentException> { storage.thumbnail(image.place, "/ni slike, MetaOS č.jpg", "w256h256") }
            assertTrue("not found" in missing.message.orEmpty(), missing.message)
        }

        val wrong = DropboxStorage(key, secret, "not-the-refresh-token")
        assertTrue("DROPBOX_REFRESH_TOKEN" in assertFailsWith<IllegalStateException> { wrong.places() }.message.orEmpty())
    }
}
