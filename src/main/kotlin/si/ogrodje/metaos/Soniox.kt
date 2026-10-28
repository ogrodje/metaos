package si.ogrodje.metaos

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Soniox's API key (SONIOX_API_KEY) turns it on */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.soniox.api-key:}' != ''")
annotation class ConditionalOnSoniox

/** Soniox couldn't be asked, or answered with an error that asking again may not have (busy, down) */
class SonioxException(message: String) : RuntimeException(message)

/** Soniox turned the request down, e.g. as invalid, with a key it doesn't take, or with no balance: asking again won't do */
class SonioxRefused(message: String) : RuntimeException(message)

/**
 * Soniox (soniox.com), the second speech-to-text provider: its async transcription, which punctuates Slovenian and
 * tells speakers apart, where Google's long does neither. It fetches the file itself, from a link to MetaOS's bucket
 * signed for a day, as a transcription may wait in its queue; MetaOS asks after it, and once it's done, keeps its
 * answer in the bucket (soniox.json, with the transcription's details) and deletes it at Soniox, which keeps
 * transcriptions until they're deleted. Soniox's project, and so its key, is in a region, where the audio and its
 * transcript are processed and kept: Ogrodje's is in the United States (api.soniox.com); the EU's is
 * api.eu.soniox.com, with a key of a project there (metaos.soniox.url).
 */
@Component
@ConditionalOnStorage
@ConditionalOnSoniox
class SonioxSpeechToText(
    @Value("\${metaos.soniox.url}") private val url: String,
    @Value("\${metaos.soniox.api-key}") private val key: String,
    @Value("\${metaos.soniox.model}") override val model: String,
    private val storage: Storage,
    private val mapper: JsonMapper,
) : SpeechToText {

    override val provider = "soniox"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun start(path: String, output: String, language: String, batch: Boolean, hints: SpeechHints): String {
        val body = mapOf(
            "model" to model,
            "audio_url" to storage.downloadUrl(path, Duration.ofDays(1)).toString(),
            // Soniox's languages are ISO 639-1, e.g. sl; a hint, as English is spoken in Slovenian episodes too
            "language_hints" to listOf(language.substringBefore('-').lowercase()),
            "enable_speaker_diarization" to true,
            "context" to context(hints),
            // Where its answer goes, to find it at Soniox
            "client_reference_id" to output.takeLast(256),
        ).filterValues { it != null }
        return call("POST", "/v1/transcriptions", body)["id"].asString()
    }

    // As key-value pairs (about ten at most) and terms, in Soniox's 10,000 characters or so
    private fun context(hints: SpeechHints): Map<String, Any>? {
        val general = listOfNotNull(
            "domain" to "A podcast and YouTube show about software, technology and the people who make them",
            hints.show?.let { "show" to it },
            hints.title?.let { "topic" to it },
            hints.people.takeIf { it.isNotEmpty() }?.let { "speakers" to "${it.size}: ${it.joinToString(", ")}" },
        ).map { (key, value) -> mapOf("key" to key, "value" to value.take(500)) }
        val terms = (hints.people + listOfNotNull(hints.show)).distinct().take(100)
        return mapOf("general" to general, "terms" to terms).filterValues { (it as List<*>).isNotEmpty() }.ifEmpty { null }
    }

    override fun result(operation: String, path: String, output: String): Recognized {
        val answer = "$output/soniox.json"
        val transcription = try {
            call("GET", "/v1/transcriptions/$operation")
        } catch (gone: SonioxRefused) {
            // Deleted at Soniox once its answer was in the bucket, by an attempt before this one
            if (storage.file(answer) == null) throw gone
            return recognized(answer, mapper.readTree(storage.read(answer, MAX_ANSWER_BYTES))["transcription"])
        }
        when (val status = transcription["status"]?.asString()) {
            "completed" -> {}
            "error", "failed" -> throw IllegalStateException(
                "Soniox couldn't transcribe it: ${transcription["error_message"]?.asString() ?: transcription["error_type"]?.asString() ?: status}"
            )
            // queued, processing (downloading, transcribing)
            else -> throw StillRecognizing(null)
        }
        val transcript = call("GET", "/v1/transcriptions/$operation/transcript")
        val both = mapOf("transcription" to transcription, "transcript" to transcript)
        storage.upload(answer, "application/json", mapper.writeValueAsBytes(both).inputStream(), overwrite = true)
        // MetaOS has it now; one left behind is only Soniox's storage, so this may fail
        runCatching { call("DELETE", "/v1/transcriptions/$operation") }
        return recognized(answer, transcription)
    }

    // Deleted, it's stopped, as far as Soniox says; one it's done already is gone too
    override fun cancel(operation: String) {
        call("DELETE", "/v1/transcriptions/$operation")
    }

    private fun recognized(answer: String, transcription: JsonNode?) =
        Recognized(answer, transcription?.get("audio_duration_ms")?.takeIf { it.isNumber }?.asDouble()?.div(1000))

    override fun heard(results: String) = sonioxHeard(mapper.readTree(results))

    private fun call(method: String, path: String, body: Any? = null): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + path))
            .header("Authorization", "Bearer $key")
            .timeout(Duration.ofMinutes(2))
            .apply {
                if (body == null) method(method, HttpRequest.BodyPublishers.noBody())
                else header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            }
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw SonioxException("Soniox can't be reached: ${e.message}")
        }
        val tree = runCatching { mapper.readTree(response.body()) }.getOrNull() ?: mapper.createObjectNode()
        val status = response.statusCode()
        if (status in 200..299) return tree
        val message = tree["message"]?.asString() ?: tree["error_message"]?.asString() ?: response.body().take(200)
        val why = "Soniox answered $status: $message" + (tree["validation_errors"]?.takeUnless { it.isNull }?.let { " $it" } ?: "")
        // Busy (429) or down: the workflow asks again; anything else, it won't
        throw if (status == 429 || status >= 500) SonioxException(why) else SonioxRefused(why)
    }

    private companion object {
        // Its tokens for a few hours of speech are a few megabytes
        const val MAX_ANSWER_BYTES = 64 * 1024 * 1024
    }
}

/**
 * Soniox's answer ({"transcription": ..., "transcript": {"tokens": [...]}}) as pieces of what was said: a speaker's
 * turn each, its words from Soniox's tokens, which are parts of words (a token that starts with a space starts a word,
 * punctuation too goes with the word before), timed in milliseconds, each as sure as its tokens are on average
 */
fun sonioxHeard(answer: JsonNode): List<TranscriptSegment> {
    class Word(val speaker: String?, val start: Double, var end: Double, val text: StringBuilder, val sure: MutableList<Double>)

    val words = mutableListOf<Word>()
    val tokens: Iterable<JsonNode> = answer["transcript"]?.get("tokens") ?: emptyList()
    for (token in tokens) {
        // Translations, were Soniox asked for one
        if (token["translation_status"]?.asString() == "translation") continue
        val text = token["text"]?.asString().orEmpty()
        val speaker = token["speaker"]?.takeUnless { it.isNull }?.asString()
        val start = (token["start_ms"]?.asDouble() ?: continue) / 1000
        val end = (token["end_ms"]?.asDouble() ?: continue) / 1000
        val last = words.lastOrNull()
        if (last == null || text.startsWith(" ") || speaker != last.speaker) {
            if (text.isBlank()) continue
            words += Word(speaker, start, end, StringBuilder(text.trim()), mutableListOf())
        } else {
            last.text.append(text)
            last.end = end
        }
        token["confidence"]?.takeIf { it.isNumber }?.let { words.last().sure += it.asDouble() }
    }
    val turns = mutableListOf<MutableList<Word>>()
    for (word in words) {
        val turn = turns.lastOrNull()
        if (turn != null && turn.last().speaker == word.speaker) turn += word else turns += mutableListOf(word)
    }
    return turns.map { turn ->
        val timed = turn.map { word ->
            TranscriptWord(word.start, word.end, word.text.toString(), word.sure.takeIf { it.isNotEmpty() }?.let { confidence(it.average()) })
        }
        TranscriptSegment(turn.first().speaker, timed.first().start, timed.last().end, timed.joinToString(" ") { it.text }, timed)
    }
}
