package si.ogrodje.patreon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaTest {
    @TempDir
    lateinit var state: Path

    private val media by lazy { Media(PatreonProperties(stateDir = state)) }

    @Test
    fun `files come only from https addresses on the internet`() {
        for (url in listOf(
            "http://example.com/episode.mp3",
            "https://127.0.0.1/episode.mp3",
            "https://localhost/episode.mp3",
            "https://10.1.2.3/episode.mp3",
            "https://192.168.1.10/episode.mp3",
            "https://169.254.169.254/latest/meta-data",
            "https://100.101.102.103/episode.mp3",
            "https://[fd7a:115c:a1e0::1]/episode.mp3",
        )) {
            assertThrows<IllegalArgumentException>(url) { media.onTheInternet(URI.create(url)) }
        }
        media.onTheInternet(URI.create("https://8.8.8.8/episode.mp3"))
    }

    @Test
    fun `a file from the cluster isn't downloaded at all`() {
        val e = assertThrows<IllegalArgumentException> {
            media.downloaded(MediaRequest("https://10.152.183.1/episode.mp3", MediaKind.AUDIO)) { _, _ -> error("downloaded") }
        }
        assertTrue(e.message!!.contains("isn't on the internet"), e.message)
    }

    @Test
    fun `what downloads a restart cut short is deleted`() {
        val downloads = Files.createDirectories(state.resolve("downloads"))
        Files.writeString(downloads.resolve("cut-short"), "half an episode")
        media.forgetDownloads()
        assertFalse(Files.exists(downloads.resolve("cut-short")))
    }

    @Test
    fun `a post's image is a PNG, JPEG or GIF by what's in it, whatever its name`() {
        fun type(name: String, vararg bytes: Int) =
            media.imageType(Files.write(state.resolve(name), ByteArray(bytes.size) { bytes[it].toByte() } + ByteArray(16)))
        assertEquals("image/png", type("thumb.jpg", 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        assertEquals("image/jpeg", type("thumb", 0xFF, 0xD8, 0xFF, 0xE0))
        assertEquals("image/gif", type("thumb.gif", 0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
        val e = assertThrows<IllegalArgumentException> { type("thumb.png", 0x3C, 0x68, 0x74, 0x6D, 0x6C) }
        assertEquals("The file isn't a PNG, JPEG or GIF picture", e.message)
    }

    @Test
    fun `a file's name can't break out of its form`() {
        val e = assertThrows<IllegalArgumentException> {
            media.downloaded(MediaRequest("https://8.8.8.8/x", MediaKind.AUDIO, "a\r\nb.mp3")) { _, _ -> error("downloaded") }
        }
        assertEquals("A file's name can't have slashes or line breaks", e.message)
    }
}
