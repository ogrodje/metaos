package si.ogrodje.metaos

import java.io.InputStream
import java.util.Base64

// How much of a text file read_document, read_dropbox_file and read_koofr_file return at a time, and the most an
// upload_dropbox_file or upload_koofr_file takes
const val MAX_READ = 200 * 1024
const val MAX_UPLOAD = 10 * 1024 * 1024

const val READ_OFFSET = "Byte to start at: the previous answer's next, to read on; the start when omitted"

/** A piece of a text file: [text] starts at byte [offset], and [next] is where the next piece starts, null at the end */
data class TextPiece(val text: String, val offset: Long, val next: Long?)

/**
 * The piece of a text file in [bytes], read at [offset]: up to [MAX_READ] of them, and one more to tell whether the file
 * goes on. A piece ends before a character it can't hold whole, so reading on from next gives every byte exactly once
 */
fun textPiece(bytes: ByteArray, offset: Long): TextPiece {
    if (bytes.size <= MAX_READ) return TextPiece(bytes.decodeToString(), offset, null)
    var end = MAX_READ
    // A UTF-8 character has at most 3 bytes after its first, each 10xxxxxx
    while (end > MAX_READ - 3 && bytes[end].toInt() and 0xC0 == 0x80) end--
    return TextPiece(bytes.decodeToString(0, end), offset, offset + end)
}

fun requireOffset(offset: Long?, size: Long?): Long = (offset ?: 0).also {
    require(it >= 0 && (size == null || it <= size)) { "Offset $it is outside the file${size?.let { s -> " ($s bytes)" } ?: ""}" }
}

private val BASE64_DIGITS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + '+' + '/'
private val IS_BASE64_DIGIT = BooleanArray(128).also { for (c in BASE64_DIGITS) it[c.code] = true }

/** The bytes of an upload, as a stream, and how many there are: [content] itself, or decoded from base64 if [base64] */
class UploadContent(val size: Long, val stream: InputStream)

fun uploadContent(name: String, content: String, base64: Boolean): UploadContent {
    if (!base64) {
        // A character is at least a byte, so a longer text can't fit
        require(content.length <= MAX_UPLOAD) { "$name is more than $MAX_UPLOAD bytes" }
        val bytes = content.toByteArray()
        require(bytes.size <= MAX_UPLOAD) { "$name is ${bytes.size} bytes; at most $MAX_UPLOAD" }
        return UploadContent(bytes.size.toLong(), bytes.inputStream())
    }
    // The MIME decoder skips line breaks and whatever else isn't a base64 digit, so the digits alone tell the size, and
    // it decodes as the upload reads, never holding the whole file
    val digits = content.count { it.code < 128 && IS_BASE64_DIGIT[it.code] }
    require(digits % 4 != 1) { "$name isn't base64: $digits digits can't be decoded" }
    val size = digits * 6L / 8
    require(size <= MAX_UPLOAD) { "$name is $size bytes; at most $MAX_UPLOAD" }
    return UploadContent(size, Base64.getMimeDecoder().wrap(Base64Chars(content)))
}

// content's characters as bytes, without copying them; anything outside ASCII becomes a space, which the decoder skips
private class Base64Chars(private val content: String) : InputStream() {
    private var at = 0
    override fun read(): Int = if (at < content.length) content[at++].code.let { if (it < 128) it else ' '.code } else -1
}
