package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Over two pieces of text with characters of 1, 2, 3 and 4 bytes, so pieces end next to every kind of them */
fun longSloveneText() = buildString { while (length < MAX_READ) append("Čšž in ž, ključ € ter 🎙️ ogrodje. ") }.repeat(2)

/** Every piece [read] gives, from the start until next is null: the text, put back together as bytes */
fun readInPieces(read: (Long?) -> Pair<String, Long?>): ByteArray {
    val all = ByteArrayOutputStream()
    var offset: Long? = null
    do {
        val (text, next) = read(offset)
        assertTrue(text.toByteArray().size <= MAX_READ)
        assertTrue('�' !in text, "a piece ends inside a character")
        all.writeBytes(text.toByteArray())
        offset = next
    } while (next != null)
    return all.toByteArray()
}

class ContentTest {

    @Test
    fun `cuts text into pieces between characters, never through one`() {
        val bytes = longSloveneText().toByteArray()
        assertContentEquals(bytes, readInPieces { offset ->
            val from = offset ?: 0
            textPiece(bytes.copyOfRange(from.toInt(), minOf(bytes.size, from.toInt() + MAX_READ + 1)), from).let { it.text to it.next }
        })
        assertEquals(TextPiece("kratko", 3, null), textPiece("kratko".toByteArray(), 3))
    }

    @Test
    fun `knows an upload's size without decoding it, line breaks and padding included`() {
        for (size in listOf(0, 1, 2, 3, 57, 58, 1000)) {
            val bytes = ByteArray(size) { (it * 7).toByte() }
            for (encoded in listOf(Base64.getEncoder(), Base64.getMimeEncoder(), Base64.getEncoder().withoutPadding()).map { it.encodeToString(bytes) }) {
                val upload = uploadContent("x.bin", encoded, base64 = true)
                assertEquals(size.toLong(), upload.size)
                assertContentEquals(bytes, upload.stream.readAllBytes())
            }
        }
        assertEquals(6, uploadContent("x.md", "Čšž", base64 = false).size)
        assertFailsWith<IllegalArgumentException> { uploadContent("x.bin", "QUJDR", base64 = true) }
        assertFailsWith<IllegalArgumentException> { uploadContent("x.md", "ž".repeat(MAX_UPLOAD / 2 + 1), base64 = false) }
    }
}
