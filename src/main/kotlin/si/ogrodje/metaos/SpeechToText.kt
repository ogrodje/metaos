package si.ogrodje.metaos

import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.speech.v2.AutoDetectDecodingConfig
import com.google.cloud.speech.v2.BatchRecognizeFileMetadata
import com.google.cloud.speech.v2.BatchRecognizeRequest
import com.google.cloud.speech.v2.BatchRecognizeRequest.ProcessingStrategy
import com.google.cloud.speech.v2.BatchRecognizeResponse
import com.google.cloud.speech.v2.BatchRecognizeResults
import com.google.cloud.speech.v2.GcsOutputConfig
import com.google.cloud.speech.v2.NativeOutputFileFormatConfig
import com.google.cloud.speech.v2.OperationMetadata
import com.google.cloud.speech.v2.OutputFormatConfig
import com.google.cloud.speech.v2.RecognitionConfig
import com.google.cloud.speech.v2.RecognitionFeatures
import com.google.cloud.speech.v2.RecognitionOutputConfig
import com.google.cloud.speech.v2.RecognizerName
import com.google.cloud.speech.v2.SpeechClient
import com.google.cloud.speech.v2.SpeechSettings
import com.google.cloud.speech.v2.VttOutputFileFormatConfig
import com.google.protobuf.util.JsonFormat
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Where Speech-to-Text left a transcription: its results (JSON) and subtitles (WebVTT), as paths in MetaOS's bucket */
data class Recognized(val results: String, val vtt: String?, val billedSeconds: Double?)

/** A transcription Speech-to-Text is still on, and how far it got, if it says */
class StillRecognizing(val percent: Int?) : RuntimeException("Speech-to-Text is ${percent?.let { "$it% " } ?: ""}through")

/**
 * Speech-to-Text, as Google's batch recognition: it reads a file from MetaOS's bucket itself and writes what it heard
 * next to it, taking minutes for an episode, or up to a day for a fifth of the price ([batch]). The transcription
 * workflow (Transcription.kt) starts it and asks after it until it's done.
 */
interface SpeechToText {
    /** Speech-to-Text's model, e.g. chirp_3 */
    val model: String

    /**
     * Starts on the file at [path] in MetaOS's bucket, hearing it as [language] (BCP-47, e.g. sl-SI), and writing what
     * it heard under [output]; its operation's name
     */
    fun start(path: String, output: String, language: String, batch: Boolean): String

    /** What the operation found, once it's done; throws [StillRecognizing] while it isn't, and what went wrong, if it did */
    fun result(operation: String, path: String): Recognized

    /** Speech-to-Text's results as it wrote them (BatchRecognizeResults, as JSON): what was said, in stretches */
    fun segments(results: String): List<TranscriptSegment> = segmentsOf(results)
}

/**
 * Each of Speech-to-Text's results as a stretch of what was said, from where the one before it ended to where it ends.
 * Without speakers: Speech-to-Text doesn't tell them apart in Slovenian (diarization is for a dozen other languages,
 * English one of them, which MetaOS doesn't ask for yet).
 */
fun segmentsOf(results: String): List<TranscriptSegment> {
    val parsed = BatchRecognizeResults.newBuilder().also { JsonFormat.parser().ignoringUnknownFields().merge(results, it) }.build()
    var start = 0.0
    return parsed.resultsList.mapNotNull { result ->
        val end = if (result.hasResultEndOffset()) result.resultEndOffset.let { it.seconds + it.nanos / 1e9 } else start
        val text = result.alternativesList.firstOrNull()?.transcript?.trim().orEmpty()
        TranscriptSegment(null, start, end, text).takeIf { text.isNotEmpty() }.also { start = end }
    }
}

@Component
@ConditionalOnStorage
class GoogleSpeechToText(
    @Value("\${metaos.storage.key}") key: String,
    @Value("\${metaos.storage.bucket}") private val bucket: String,
    @Value("\${metaos.speech.location}") private val location: String,
    @Value("\${metaos.speech.model}") override val model: String,
) : SpeechToText, DisposableBean {

    // As MetaOS's bucket's service account, which may recognize speech (roles/speech.client, infra/gcp); Speech-to-Text's
    // own service agent reads the file and writes the results, in the project's buckets
    private val credentials = ServiceAccountCredentials.fromStream(key.byteInputStream())
        .createScoped("https://www.googleapis.com/auth/cloud-platform") as ServiceAccountCredentials

    // The location's own endpoint, e.g. eu-speech.googleapis.com, which keeps the audio in the EU; made when it's
    // first used, as most of MetaOS's runs never transcribe
    private val lazyClient = lazy {
        SpeechClient.create(
            SpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .setEndpoint("$location-speech.googleapis.com:443")
                .build()
        )
    }
    private val client by lazyClient

    // The location's default recognizer, configured by each request
    private val recognizer = RecognizerName.of(checkNotNull(credentials.projectId) { "GOOGLE_STORAGE_KEY has no project" }, location, "_").toString()

    private fun gs(path: String) = "gs://$bucket/${storagePath(path)}"

    private fun pathOf(uri: String): String {
        require(uri.startsWith("gs://$bucket/")) { "Speech-to-Text wrote outside MetaOS's bucket: $uri" }
        return uri.removePrefix("gs://$bucket/")
    }

    override fun start(path: String, output: String, language: String, batch: Boolean): String {
        val config = RecognitionConfig.newBuilder()
            // WAV, FLAC, MP3, and AAC in MP4, M4A or MOV, whichever it is
            .setAutoDecodingConfig(AutoDetectDecodingConfig.getDefaultInstance())
            .setModel(model)
            .addLanguageCodes(language)
            .setFeatures(RecognitionFeatures.newBuilder().setEnableAutomaticPunctuation(true))
            .build()
        val request = BatchRecognizeRequest.newBuilder()
            .setRecognizer(recognizer)
            .setConfig(config)
            .addFiles(BatchRecognizeFileMetadata.newBuilder().setUri(gs(path)))
            .setRecognitionOutputConfig(
                RecognitionOutputConfig.newBuilder()
                    .setGcsOutputConfig(GcsOutputConfig.newBuilder().setUri(gs(output) + "/"))
                    .setOutputFormatConfig(
                        OutputFormatConfig.newBuilder()
                            .setNative(NativeOutputFileFormatConfig.getDefaultInstance())
                            .setVtt(VttOutputFileFormatConfig.getDefaultInstance())
                    )
            )
            .setProcessingStrategy(if (batch) ProcessingStrategy.DYNAMIC_BATCHING else ProcessingStrategy.PROCESSING_STRATEGY_UNSPECIFIED)
            .build()
        // The operation as Google started it, without a future that would keep asking after it in this JVM
        return client.batchRecognizeCallable().call(request).name
    }

    override fun result(operation: String, path: String): Recognized {
        val op = client.operationsClient.getOperation(operation)
        if (!op.done) throw StillRecognizing(op.metadata.takeIf { it.`is`(OperationMetadata::class.java) }
            ?.unpack(OperationMetadata::class.java)?.progressPercent)
        if (op.hasError()) throw IllegalStateException("Speech-to-Text failed: ${op.error.message}")
        val response = op.response.unpack(BatchRecognizeResponse::class.java)
        val file = checkNotNull(response.resultsMap[gs(path)]) { "Speech-to-Text has no result for ${gs(path)}" }
        if (file.hasError() && file.error.code != 0) throw IllegalStateException("Speech-to-Text couldn't transcribe it: ${file.error.message}")
        val stored = file.cloudStorageResult
        val billed = response.totalBilledDuration.takeIf { response.hasTotalBilledDuration() }?.let { it.seconds + it.nanos / 1e9 }
        return Recognized(pathOf(stored.uri), stored.vttFormatUri.ifEmpty { null }?.let(::pathOf), billed)
    }

    override fun destroy() {
        if (lazyClient.isInitialized()) client.close()
    }
}
