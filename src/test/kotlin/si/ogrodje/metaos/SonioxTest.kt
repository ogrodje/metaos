package si.ogrodje.metaos

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** MetaOS's Soniox provider, against an API that answers as Soniox's does, and a bucket in memory */
class SonioxTest {

    private val mapper = JsonMapper.builder().build()
    private val asked = mutableListOf<String>()
    private var created: String? = null
    private var polls = 0
    private var deleted = false

    private val tokens = """
        [{"text":"Do","start_ms":0,"end_ms":200,"confidence":0.9,"speaker":"1"},
         {"text":"ber","start_ms":200,"end_ms":400,"confidence":0.7,"speaker":"1"},
         {"text":" dan","start_ms":500,"end_ms":800,"confidence":1.0,"speaker":"1"},
         {"text":".","start_ms":800,"end_ms":820,"confidence":0.98,"speaker":"1"},
         {"text":" Ži","start_ms":1500,"end_ms":1700,"confidence":0.95,"speaker":"2"},
         {"text":"vjo","start_ms":1700,"end_ms":1900,"confidence":0.85,"speaker":"2"},
         {"text":"!","start_ms":1900,"end_ms":1950,"confidence":0.9,"speaker":"2"}]
    """.trimIndent()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            val method = exchange.requestMethod
            asked += "$method $path"
            val (status, body) = when {
                exchange.requestHeaders.getFirst("Authorization") != "Bearer secret" ->
                    401 to """{"status_code":401,"error_type":"unauthenticated","message":"Invalid API key."}"""
                method == "POST" && path == "/v1/transcriptions" -> {
                    created = exchange.requestBody.readBytes().decodeToString()
                    if ("\"model\":\"stt-async-v5\"" !in created!!) 400 to """{"error_type":"invalid_request","message":"Invalid model.","validation_errors":[{"loc":["model"]}]}"""
                    else 201 to """{"id":"t-1","status":"queued"}"""
                }
                method == "GET" && path == "/v1/transcriptions/t-1" ->
                    if (deleted) 404 to """{"error_type":"not_found","message":"Transcription not found."}"""
                    else if (polls++ == 0) 200 to """{"id":"t-1","status":"processing"}"""
                    else 200 to """{"id":"t-1","status":"completed","audio_duration_ms":12345,"model":"stt-async-v5"}"""
                method == "GET" && path == "/v1/transcriptions/t-1/transcript" -> 200 to """{"id":"t-1","text":"Dober dan. Živjo!","tokens":$tokens}"""
                method == "DELETE" && path == "/v1/transcriptions/t-1" -> { deleted = true; 204 to "" }
                method == "GET" && path == "/v1/transcriptions/broken" ->
                    200 to """{"id":"broken","status":"error","error_type":"audio_decode_error","error_message":"The audio couldn't be decoded."}"""
                method == "GET" && path == "/v1/transcriptions/busy" -> 429 to """{"error_type":"limit_exceeded","message":"Too many requests."}"""
                else -> 404 to """{"error_type":"not_found","message":"No $path"}"""
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val storage = FakeStorage("ogrodje-metaos-test")
    private fun soniox(key: String = "secret", model: String = "stt-async-v5") =
        SonioxSpeechToText("http://127.0.0.1:${server.address.port}", key, model, storage, mapper)

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `transcribes a file from a signed link, keeps the answer in the bucket and deletes it at Soniox`() {
        val soniox = soniox()
        val operation = soniox.start(
            "episode-media/u-1/S09E90.mp3", "transcripts/m-1/r-1", "sl-SI", false,
            SpeechHints(title = "Jezikovni modeli", show = "Bytes", people = listOf("Domen Vreš", "Oto Brglez")),
        )
        assertEquals("t-1", operation)
        val request = mapper.readTree(created)
        assertEquals("stt-async-v5", request["model"].asString())
        // A link to the file in the bucket, which Soniox fetches itself
        assertTrue(request["audio_url"].asString().startsWith("https://storage.example.com/ogrodje-metaos-test/episode-media/u-1/S09E90.mp3?"))
        assertEquals(listOf("sl"), request["language_hints"].toList().map { it.asString() })
        assertTrue(request["enable_speaker_diarization"].asBoolean())
        assertEquals("transcripts/m-1/r-1", request["client_reference_id"].asString())
        // To spell names as the episode has them
        assertEquals(listOf("Domen Vreš", "Oto Brglez", "Bytes"), request["context"]["terms"].toList().map { it.asString() })
        val general = request["context"]["general"].associate { it["key"].asString() to it["value"].asString() }
        assertEquals("Jezikovni modeli", general["topic"])
        assertEquals("2: Domen Vreš, Oto Brglez", general["speakers"])

        assertFailsWith<StillRecognizing> { soniox.result(operation, "episode-media/u-1/S09E90.mp3", "transcripts/m-1/r-1") }
        val recognized = soniox.result(operation, "episode-media/u-1/S09E90.mp3", "transcripts/m-1/r-1")
        assertEquals("transcripts/m-1/r-1/soniox.json", recognized.results)
        assertEquals(12.345, recognized.billedSeconds)
        val kept = mapper.readTree(storage.read(recognized.results, 1_000_000))
        assertEquals("completed", kept["transcription"]["status"].asString())
        assertEquals(7, kept["transcript"]["tokens"].size())
        assertTrue(deleted, asked.toString())

        // Asked again once it's gone at Soniox, e.g. by a retried activity: from the bucket
        assertEquals(recognized, soniox.result(operation, "episode-media/u-1/S09E90.mp3", "transcripts/m-1/r-1"))
        assertEquals(
            listOf("Dober dan.", "Živjo!"),
            soniox.heard(storage.read(recognized.results, 1_000_000).decodeToString()).map { it.text },
        )
    }

    @Test
    fun `hears Soniox's tokens as words, and a speaker's turn as a piece`() {
        val heard = sonioxHeard(mapper.readTree("""{"transcript":{"tokens":$tokens}}"""))
        assertEquals(
            listOf(
                TranscriptSegment(
                    "1", 0.0, 0.82, "Dober dan.",
                    listOf(TranscriptWord(0.0, 0.4, "Dober", 0.8), TranscriptWord(0.5, 0.82, "dan.", 0.99)),
                ),
                TranscriptSegment("2", 1.5, 1.95, "Živjo!", listOf(TranscriptWord(1.5, 1.95, "Živjo!", 0.9))),
            ),
            heard,
        )
    }

    @Test
    fun `says why Soniox couldn't, and whether asking again may help`() {
        val failed = assertFailsWith<IllegalStateException> { soniox().result("broken", "a.mp3", "transcripts/m-2/r-1") }
        assertEquals("Soniox couldn't transcribe it: The audio couldn't be decoded.", failed.message)
        // Busy: the workflow asks again
        assertFailsWith<SonioxException> { soniox().result("busy", "a.mp3", "transcripts/m-2/r-1") }
        // A key or request it doesn't take: it doesn't
        val refused = assertFailsWith<SonioxRefused> { soniox(key = "wrong").start("a.mp3", "o", "sl-SI", false, SpeechHints()) }
        assertEquals("Soniox answered 401: Invalid API key.", refused.message)
        val invalid = assertFailsWith<SonioxRefused> { soniox(model = "stt-nope").start("a.mp3", "o", "sl-SI", false, SpeechHints()) }
        // With what it found wrong
        assertEquals("""Soniox answered 400: Invalid model. [{"loc":["model"]}]""", invalid.message)
    }
}
