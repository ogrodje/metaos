package si.ogrodje.metaos

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.health.contributor.Status
import java.io.InputStream
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Koofr in memory: places, folders and files with their content */
class FakeKoofr : Koofr {
    val shared = mutableListOf<KoofrPlace>()
    private val files = mutableMapOf<Pair<String, String>, Pair<KoofrFile, ByteArray?>>()

    fun place(id: String, name: String, writable: Boolean = true) = KoofrPlace(id, name, "ogrodje@example.com", writable).also { shared += it }

    fun put(place: String, path: String, content: ByteArray?, contentType: String? = "text/plain") {
        files[place to path] = KoofrFile(place, path, path.substringAfterLast('/'), content == null, content?.size?.toLong(), null, contentType) to content
    }

    override fun places() = shared.toList()
    override fun file(place: String, path: String) = files[place to path]?.first
    override fun list(place: String, folder: String) =
        files.values.map { it.first }.filter { it.place == place && it.path.substringBeforeLast('/').ifEmpty { "/" } == folder }

    override fun search(text: String, place: String?, limit: Int) =
        files.values.map { it.first }.filter { (place == null || it.place == place) && text.lowercase() in it.name.lowercase() }.take(limit)

    override fun read(place: String, path: String, offset: Long, maxBytes: Int) =
        files.getValue(place to path).second!!.let { it.copyOfRange(offset.toInt(), minOf(it.size, offset.toInt() + maxBytes)) }

    // The image itself stands in for its thumbnail
    override fun thumbnail(place: String, path: String, size: String) =
        files.getValue(place to path).let { (file, content) -> KoofrImage(file.contentType ?: "image/jpeg", content!!) }

    override fun upload(place: String, folder: String, name: String, contentType: String, content: InputStream, size: Long, overwrite: Boolean): KoofrFile {
        val path = "${folder.trimEnd('/')}/$name"
        require(overwrite || file(place, path) == null) { "$path is already there" }
        val bytes = content.readAllBytes()
        check(bytes.size.toLong() == size) { "$name came as ${bytes.size} bytes, not $size" }
        put(place, path, bytes, contentType)
        return file(place, path)!!
    }

    override fun createFolder(place: String, parent: String, name: String) = put(place, "${parent.trimEnd('/')}/$name", null, null)

    // A folder takes what's in it along
    override fun move(place: String, path: String, toPlace: String, toPath: String) {
        files.keys.filter { (at, p) -> at == place && (p == path || p.startsWith("$path/")) }.forEach { key ->
            val (file, content) = files.remove(key)!!
            val moved = toPath + key.second.removePrefix(path)
            files[toPlace to moved] = file.copy(place = toPlace, path = moved, name = moved.substringAfterLast('/')) to content
        }
    }
}

class KoofrTest {

    private val koofr = FakeKoofr().apply {
        place("mount-media", "Ogrodje Media")
        place("mount-archive", "Arhiv", writable = false)
        put("mount-media", "/Epizode", null)
        put("mount-media", "/Epizode/S05E08", null)
        put("mount-media", "/Epizode/S05E08/notes.md", "# Zapiski\n\nČ, š in ž".toByteArray(), "text/markdown")
        put("mount-media", "/Epizode/S05E08/raw.wav", ByteArray(64), "audio/wav")
        put("mount-archive", "/S04E14.md", "Koofr".toByteArray(), null)
        // The bot's own space, which isn't shared with it
        put("mount-own", "/S05E08 private.md", "private".toByteArray())
    }
    private val mcp = KoofrMCP(koofr) {}

    @Test
    fun `normalises paths and refuses to climb out of them`() {
        assertEquals("/", koofrPath(null))
        assertEquals("/", koofrPath("/"))
        assertEquals("/Epizode/S05E08", koofrPath("Epizode//S05E08/"))
        assertFailsWith<IllegalArgumentException> { koofrPath("/Epizode/../..") }
    }

    @Test
    fun `lists, searches and reads only in the folders shared with the bot`() {
        assertEquals(listOf("Ogrodje Media", "Arhiv"), mcp.places().map { it.name })
        assertEquals(listOf("S05E08", "notes.md", "raw.wav"), (mcp.list("ogrodje media", "/Epizode") + mcp.list("mount-media", "Epizode/S05E08")).map { it.name })
        assertFailsWith<IllegalArgumentException> { mcp.list("mount-own", "/") }

        assertEquals(listOf("mount-media" to "/Epizode/S05E08", "mount-media" to "/Epizode/S05E08/notes.md").toSet(),
            mcp.search("S05E08", null).map { it.place to it.path }.toSet() + mcp.search("notes", "Ogrodje Media").map { it.place to it.path })

        val notes = mcp.read("Ogrodje Media", "/Epizode/S05E08/notes.md", null)
        assertEquals("# Zapiski\n\nČ, š in ž", notes.text)
        assertNull(notes.next)
        assertEquals("Koofr", mcp.read("Arhiv", "S04E14.md", null).text)
        assertFailsWith<IllegalArgumentException> { mcp.read("Ogrodje Media", "/Epizode/S05E08/raw.wav", null) }
        assertFailsWith<IllegalArgumentException> { mcp.read("Ogrodje Media", "/Epizode/S05E08", null) }
        assertFailsWith<IllegalArgumentException> { mcp.read("Ogrodje Media", "/nothing.md", null) }
    }

    @Test
    fun `reads a long text file in pieces that add up to all of it`() {
        val text = longSloveneText()
        koofr.put("mount-media", "/Epizode/S05E08/transcript.srt", text.toByteArray(), "application/x-subrip")
        assertContentEquals(text.toByteArray(), readInPieces { mcp.read("Ogrodje Media", "/Epizode/S05E08/transcript.srt", it).let { r -> r.text to r.next } })
        assertFailsWith<IllegalArgumentException> { mcp.read("Arhiv", "S04E14.md", 6) }
    }

    @Test
    fun `streams an upload of up to 10 MB, refusing a bigger one before decoding it`() {
        val big = ByteArray(MAX_UPLOAD) { it.toByte() }
        mcp.upload("Ogrodje Media", "/", "big.bin", Base64.getMimeEncoder().encodeToString(big), base64 = true, overwrite = null)
        assertContentEquals(big, koofr.read("mount-media", "/big.bin", 0, MAX_UPLOAD))
        assertFailsWith<IllegalArgumentException> { mcp.upload("Ogrodje Media", "/", "bigger.bin", Base64.getEncoder().encodeToString(big + 0), true, null) }
        assertFailsWith<IllegalArgumentException> { mcp.upload("Ogrodje Media", "/", "bigger.md", "a".repeat(MAX_UPLOAD + 1), null, null) }
        assertNull(koofr.file("mount-media", "/bigger.bin"))
    }

    @Test
    fun `uploads, creates folders and moves without replacing anything unasked`() {
        val uploaded = mcp.upload("Ogrodje Media", "/Epizode/S05E08", "shownotes.md", "Shownotes", base64 = null, overwrite = null)
        assertEquals("/Epizode/S05E08/shownotes.md", uploaded.path)
        assertFailsWith<IllegalArgumentException> { mcp.upload("Ogrodje Media", "/Epizode/S05E08", "shownotes.md", "Again", null, null) }
        mcp.upload("Ogrodje Media", "/Epizode/S05E08", "shownotes.md", "Shownotes v2", base64 = null, overwrite = true)
        assertEquals("Shownotes v2", mcp.read("Ogrodje Media", "/Epizode/S05E08/shownotes.md", null).text)

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        mcp.upload("Ogrodje Media", "/Epizode/S05E08", "cover.png", Base64.getEncoder().encodeToString(png), base64 = true, overwrite = null)
        assertEquals("image/png", koofr.file("mount-media", "/Epizode/S05E08/cover.png")?.contentType)
        assertFailsWith<IllegalArgumentException> { mcp.upload("Arhiv", "/", "x.md", "read-only", null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.upload("Ogrodje Media", "/", "../x.md", "no", null, null) }

        assertEquals("/Arhiv 2025", mcp.createFolder("Ogrodje Media", null, "Arhiv 2025").path)
        val moved = mcp.move("Ogrodje Media", "/Epizode/S05E08/shownotes.md", "/Arhiv 2025/S05E08 shownotes.md", null)
        assertEquals("S05E08 shownotes.md", moved.name)
        assertNull(koofr.file("mount-media", "/Epizode/S05E08/shownotes.md"))
        assertFailsWith<IllegalArgumentException> { mcp.move("Ogrodje Media", "/Epizode/S05E08/notes.md", "/Arhiv 2025/S05E08 shownotes.md", null) }
        assertFailsWith<IllegalArgumentException> { mcp.move("Ogrodje Media", "/Epizode/S05E08/notes.md", "/S04E14.md", "Arhiv") }
    }

    @Test
    fun `reports whether the bot can reach Koofr`() {
        val registry = SimpleMeterRegistry()
        val indicator = KoofrHealthIndicator(koofr, registry)
        indicator.check()
        assertEquals(Status.UP, indicator.health().status)
        assertEquals("Ogrodje Media, Arhiv", indicator.health().details["places"])
        assertEquals(1.0, registry.get("metaos.koofr.connected").gauge().value())
    }

    /** Against the real account, as the bot - only with its app password in the environment (.envrc). Reads only. */
    @Test
    @EnabledIfEnvironmentVariable(named = "KOOFR_APP_PASSWORD", matches = ".+")
    fun `reads the folders shared with the bot`() {
        val storage = KoofrStorage(System.getenv("KOOFR_EMAIL"), System.getenv("KOOFR_APP_PASSWORD"))
        val places = storage.places()
        places.firstOrNull()?.let { place ->
            assertNotNull(storage.file(place.id, "/"))
            val files = storage.list(place.id, "/")
            files.firstOrNull { !it.folder }?.let { assertTrue(storage.read(place.id, it.path, 0, 16).size <= 16) }
            storage.search(files.firstOrNull()?.name ?: "a", place.id, 5)
        }

        val wrong = KoofrStorage(System.getenv("KOOFR_EMAIL"), "not-the-app-password")
        assertTrue("KOOFR_APP_PASSWORD" in assertFailsWith<IllegalStateException> { wrong.places() }.message.orEmpty())
    }
}
