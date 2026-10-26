package si.ogrodje.metaos

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.health.contributor.Status
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {

    @Test
    fun `normalises paths and refuses to climb out of them`() {
        assertEquals("episodes/S05E08/cover.png", storagePath("/episodes//S05E08/cover.png"))
        assertEquals("episodes", storagePath("episodes/"))
        assertFailsWith<IllegalArgumentException> { storagePath("/") }
        assertFailsWith<IllegalArgumentException> { storagePath("episodes/../people") }
        assertFailsWith<IllegalArgumentException> { storagePath("episodes/\ncover.png") }
        // 1026 bytes in UTF-8
        assertFailsWith<IllegalArgumentException> { storagePath("ž".repeat(513)) }
    }

    @Test
    fun `says what GOOGLE_STORAGE_KEY should be`() {
        val error = assertFailsWith<IllegalArgumentException> { GoogleCloudStorage("""{"type": "authorized_user"}""", "ogrodje-metaos-dev") }
        assertTrue("google/storage.sh key" in error.message.orEmpty(), error.message)
    }
}

/**
 * Against the dev bucket, as its service account - only with its key in the environment (.envrc). Writes under
 * test/, which the bucket empties after a day, and deletes what it wrote.
 */
@EnabledIfEnvironmentVariable(named = "GOOGLE_STORAGE_KEY", matches = ".+")
class GoogleCloudStorageTest {

    private val key: String = System.getenv("GOOGLE_STORAGE_KEY")
    private val storage = GoogleCloudStorage(key, System.getenv("METAOS_STORAGE_BUCKET"))
    private val folder = "test/${UUID.randomUUID()}"
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun deleteWhatsWritten() {
        storage.list(folder, 100).forEach { storage.delete(it.path) }
    }

    @Test
    fun `stores, lists, reads and deletes files, without replacing any unasked`() {
        val path = "$folder/notes/Zapiski.md"
        val text = "# Zapiski\n\nČ, š in ž"

        val stored = storage.upload(path, "text/markdown", text.byteInputStream(), overwrite = false)
        assertEquals(StoredFile(path, text.toByteArray().size.toLong(), "text/markdown", stored.updated, md5(text.toByteArray()), stored.crc32c), stored)
        assertEquals(stored, storage.file("/$path"))
        assertEquals(listOf(path), storage.list(folder, 10).map { it.path })
        assertEquals(text, storage.read(path, 1000).decodeToString())
        assertEquals("# Z", storage.read(path, 3).decodeToString())

        assertFailsWith<IllegalArgumentException> { storage.upload(path, "text/markdown", "again".byteInputStream(), overwrite = false) }
        assertEquals(text, storage.read(path, 1000).decodeToString())
        storage.upload(path, "text/markdown", "v2".byteInputStream(), overwrite = true)
        assertEquals("v2", storage.read(path, 1000).decodeToString())

        assertTrue(storage.delete(path))
        assertNull(storage.file(path))
        assertFalse(storage.delete(path))
        assertFailsWith<IllegalArgumentException> { storage.read(path, 1000) }
    }

    @Test
    fun `uploads and downloads with signed URLs, as the browser would`() {
        val path = "$folder/S05E08 cover.png"
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val upload = storage.uploadUrl(path, "image/png", Duration.ofMinutes(5))

        fun start(headers: Map<String, String> = upload.headers) = http.send(
            HttpRequest.newBuilder(upload.url.toURI())
                .apply { headers.forEach(::header) }
                .header("Origin", "http://localhost:5173")
                .POST(BodyPublishers.noBody()).build(),
            BodyHandlers.ofString(),
        )

        // The signature covers the headers
        assertEquals(403, start(upload.headers + ("Content-Type" to "text/html")).statusCode())

        val started = start()
        assertEquals(201, started.statusCode(), started.body())
        // The bucket's CORS lets the front-end read where the upload goes
        assertEquals("http://localhost:5173", started.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        val session = started.headers().firstValue("Location").orElseThrow()
        val put = http.send(HttpRequest.newBuilder(URI(session)).PUT(BodyPublishers.ofByteArray(png)).build(), BodyHandlers.ofString())
        assertEquals(200, put.statusCode(), put.body())
        assertEquals(StoredFile(path, png.size.toLong(), "image/png", storage.file(path)?.updated, md5(png), storage.file(path)?.crc32c), storage.file(path))

        // Not again, now that it's there: another upload starts, but the bucket refuses its file
        val again = start()
        assertEquals(201, again.statusCode(), again.body())
        val replace = HttpRequest.newBuilder(URI(again.headers().firstValue("Location").orElseThrow()))
            .PUT(BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3))).build()
        assertEquals(412, http.send(replace, BodyHandlers.ofString()).statusCode())
        assertContentEquals(png, storage.read(path, 1000))

        val download = http.send(HttpRequest.newBuilder(storage.downloadUrl(path, Duration.ofMinutes(5)).toURI()).build(), BodyHandlers.ofByteArray())
        assertEquals(200, download.statusCode())
        assertContentEquals(png, download.body())

        assertFailsWith<IllegalArgumentException> { storage.downloadUrl(path, Duration.ofDays(8)) }
    }

    @Test
    fun `takes a big file in chunks, also after a break, as the browser sends a video, and copies it inside the bucket`() {
        val path = "$folder/uploads/S05E08.mp4"
        val video = ByteArray(256 * 1024 + 1000) { (it % 251).toByte() }
        val upload = storage.uploadUrl(path, "video/mp4", Duration.ofMinutes(5))
        val started = http.send(
            HttpRequest.newBuilder(upload.url.toURI()).apply { upload.headers.forEach(::header) }.POST(BodyPublishers.noBody()).build(),
            BodyHandlers.ofString(),
        )
        val session = URI(started.headers().firstValue("Location").orElseThrow())

        fun put(range: String, bytes: ByteArray = byteArrayOf()) = http.send(
            HttpRequest.newBuilder(session).header("Content-Range", range).header("Origin", "http://localhost:5173")
                .PUT(BodyPublishers.ofByteArray(bytes)).build(),
            BodyHandlers.ofString(),
        )

        // The first chunk, a multiple of 256 KiB: the bucket says how much it has, which the front-end may read (CORS)
        val first = put("bytes 0-262143/${video.size}", video.copyOfRange(0, 262144))
        assertEquals(308, first.statusCode(), first.body())
        assertEquals("bytes=0-262143", first.headers().firstValue("Range").orElse(null))
        assertTrue("range" in first.headers().firstValue("Access-Control-Expose-Headers").orElse("").lowercase())
        // After a break, asked how much it has, and the rest from there
        assertEquals("bytes=0-262143", put("bytes */${video.size}").headers().firstValue("Range").orElse(null))
        val rest = put("bytes 262144-${video.size - 1}/${video.size}", video.copyOfRange(262144, video.size))
        assertEquals(200, rest.statusCode(), rest.body())
        assertEquals(md5(video), storage.file(path)?.md5)

        // Copied to where it's kept, downloaded as a file by its name, without passing through MetaOS
        val kept = storage.copy(path, "$folder/episode-media/S05E08 – končni.mp4", "attachment; filename=\"S05E08.mp4\"")
        assertEquals(StoredFile("$folder/episode-media/S05E08 – končni.mp4", video.size.toLong(), "video/mp4", kept.updated, md5(video), storage.file(path)?.crc32c), kept)
        val download = http.send(HttpRequest.newBuilder(storage.downloadUrl(kept.path, Duration.ofMinutes(5)).toURI()).build(), BodyHandlers.ofByteArray())
        assertContentEquals(video, download.body())
        assertEquals("attachment; filename=\"S05E08.mp4\"", download.headers().firstValue("Content-Disposition").orElse(null))
        assertFailsWith<IllegalArgumentException> { storage.copy(path, kept.path) }
        assertFailsWith<IllegalArgumentException> { storage.copy("$folder/nothing.mp4", "$folder/elsewhere.mp4") }
    }

    private fun md5(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes))

    @Test
    @EnabledIfEnvironmentVariable(named = "METAOS_MEDIA_BUCKET", matches = ".+")
    fun `serves the public bucket's files to anyone, as they were stored, but lists it for no one`() {
        val bucket = System.getenv("METAOS_MEDIA_BUCKET")
        val media = Media(GoogleCloudStorage(key, bucket), "https://storage.googleapis.com/$bucket/")
        val path = "$folder/Živjo.txt"
        try {
            media.storage.upload(path, "text/plain; charset=utf-8", "Živjo".byteInputStream(), overwrite = false, cacheControl = "public, max-age=60")
            assertEquals("https://storage.googleapis.com/$bucket/$folder/Živjo.txt", media.url(path))

            val read = http.send(HttpRequest.newBuilder(URI(media.url(path).replace("Živjo", "%C5%BDivjo"))).build(), BodyHandlers.ofString())
            assertEquals(200, read.statusCode())
            assertEquals("Živjo", read.body())
            assertEquals("public, max-age=60", read.headers().firstValue("Cache-Control").orElse(null))
            assertEquals(403, http.send(HttpRequest.newBuilder(URI(media.url)).build(), BodyHandlers.ofString()).statusCode())
        } finally {
            media.storage.delete(path)
        }
    }

    @Test
    fun `reports whether MetaOS can reach its bucket`() {
        val registry = SimpleMeterRegistry()
        val indicator = StorageHealthIndicator(storage, registry)
        indicator.check()
        assertEquals(Status.UP, indicator.health().status)
        assertEquals(System.getenv("METAOS_STORAGE_BUCKET"), indicator.health().details["bucket"])
        assertEquals(1.0, registry.get("metaos.storage.connected").gauge().value())
    }

    @Test
    fun `says what to do when Google doesn't accept the key`() {
        // The service account's, with a private key Google never issued - as if the key had been deleted
        val json = JsonMapper.builder().build()
        val pem = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
            .let { "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(it) + "\n-----END PRIVATE KEY-----\n" }
        val deleted = json.readValue<Map<String, Any>>(key) + mapOf("private_key" to pem, "private_key_id" to "0".repeat(40))
        val revoked = GoogleCloudStorage(json.writeValueAsString(deleted), System.getenv("METAOS_STORAGE_BUCKET"))

        val error = assertFailsWith<IllegalStateException> { revoked.file("$folder/anything") }
        assertTrue("google/storage.sh key" in error.message.orEmpty(), error.message)
    }
}
