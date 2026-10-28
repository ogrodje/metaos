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
import com.google.cloud.location.GetLocationRequest
import com.google.cloud.speech.v2.LocationsMetadata
import com.google.protobuf.Duration
import com.google.protobuf.util.JsonFormat
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Where a provider left what it heard, as it answered it, as a path in MetaOS's bucket, and what it billed */
data class Recognized(val results: String, val billedSeconds: Double?)

/**
 * What a provider may go by, to spell names and terms as the episode has them: its title, show and the people on it.
 * Soniox takes them as its context; Google's Speech-to-Text doesn't yet (it could, as phrase hints).
 */
data class SpeechHints(val title: String? = null, val show: String? = null, val people: List<String> = emptyList())

/** A transcription the provider is still on, and how far it got, if it says */
class StillRecognizing(val percent: Int?) : RuntimeException("Speech-to-text is ${percent?.let { "$it% " } ?: ""}through")

/**
 * A speech-to-text provider, Google's Speech-to-Text or Soniox (Soniox.kt): it hears a file in MetaOS's bucket, taking
 * minutes for an episode, or up to a day for less ([batch], Google's), and what it heard, as it answers, is left in the
 * bucket too. The transcription workflow (Transcription.kt) starts it, asks after it until it's done, and keeps what it
 * heard in MetaOS's own format (TranscriptContent), whoever heard it.
 */
interface SpeechToText {
    /** Who it is, as transcripts say, e.g. google */
    val provider: String

    /** The provider's model, e.g. long */
    val model: String

    /**
     * Starts on the file at [path] in MetaOS's bucket, hearing it as [language] (BCP-47, e.g. sl-SI), with what [hints]
     * say, for what it heard to be left under [output]; its operation's name
     */
    fun start(path: String, output: String, language: String, batch: Boolean, hints: SpeechHints): String

    /**
     * What the operation found, under [output] in the bucket, once it's done; throws [StillRecognizing] while it isn't,
     * and what went wrong, if it did
     */
    fun result(operation: String, path: String, output: String): Recognized

    /** Asks it to stop the operation, as its transcription was cancelled; one it can't stop goes on, and is billed */
    fun cancel(operation: String)

    /**
     * What the provider answered (at [Recognized.results]) as its own pieces of what was said, in order, each with its
     * words where it timed them, which MetaOS then cuts into stretches of its own (inStretches)
     */
    fun heard(results: String): List<TranscriptSegment>
}

/** The providers MetaOS has, by name, and the one it uses unless told another (metaos.speech.provider) */
@Component
@ConditionalOnStorage
class SpeechProviders(all: List<SpeechToText>, @Value("\${metaos.speech.provider}") val default: String) {
    private val byName = all.associateBy { it.provider }

    /** Their names, e.g. google and soniox */
    val names get() = byName.keys.sorted()

    /** All of them */
    val all get() = byName.values.sortedBy { it.provider }

    fun of(name: String): SpeechToText =
        requireNotNull(byName[name]) { "MetaOS has no speech-to-text provider $name, only ${byName.keys.sorted()}" }
}

private fun Duration.toSeconds() = seconds + nanos / 1e9

/** How sure a provider was, to the hundredth, as more says nothing and takes room in every word */
fun confidence(value: Double) = Math.round(value * 100) / 100.0

/**
 * Google's results (BatchRecognizeResults, as JSON) as pieces of what was said: one a result, about a minute with long,
 * from its first word, else from where the one before it ended, to where it ends, with its words where Google timed
 * them (a word at 0 s has no start, as JSON leaves out what's 0). Without speakers: Speech-to-Text doesn't tell them
 * apart in Slovenian (diarization is for a dozen other languages, English one of them, which MetaOS doesn't ask for yet).
 */
fun googleHeard(results: String): List<TranscriptSegment> {
    val parsed = BatchRecognizeResults.newBuilder().also { JsonFormat.parser().ignoringUnknownFields().merge(results, it) }.build()
    var after = 0.0
    return parsed.resultsList.mapNotNull { result ->
        val best = result.alternativesList.firstOrNull()
        val end = if (result.hasResultEndOffset()) result.resultEndOffset.toSeconds() else after
        val start = best?.wordsList?.firstOrNull()?.takeIf { it.hasStartOffset() }?.startOffset?.toSeconds() ?: after
        val text = best?.transcript?.trim().orEmpty()
        val timed = best?.wordsList.orEmpty().any { it.hasEndOffset() }
        val words = if (!timed) emptyList() else best?.wordsList.orEmpty().map { word ->
            TranscriptWord(
                word.startOffset.toSeconds(), word.endOffset.toSeconds(), word.word,
                word.confidence.takeIf { it > 0 }?.let { confidence(it.toDouble()) },
            )
        }
        TranscriptSegment(null, start, end, text, words).takeIf { text.isNotEmpty() }.also { after = end }
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

    override val provider = "google"

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

    private val project = checkNotNull(credentials.projectId) { "GOOGLE_STORAGE_KEY has no project" }

    // The location's default recognizer, configured by each request
    private val recognizer = RecognizerName.of(project, location, "_").toString()

    // What the model can do for each language here, as Google says (its Locations API), e.g. word_timestamps, and
    // automatic_punctuation, which long has for English but not for Slovenian; asked once a language
    private val features = ConcurrentHashMap<String, Set<String>>()

    private fun features(language: String): Set<String> = features.computeIfAbsent(language) {
        val location = client.getLocation(GetLocationRequest.newBuilder().setName("projects/$project/locations/$location").build())
        val models = location.metadata.unpack(LocationsMetadata::class.java).languages.modelsMap
        val model = requireNotNull(models[language]?.modelFeaturesMap?.get(model)) {
            "Speech-to-Text's $model doesn't hear $language in ${this.location}"
        }
        model.modelFeatureList.map { it.feature }.toSet()
    }

    private fun gs(path: String) = "gs://$bucket/${storagePath(path)}"

    private fun pathOf(uri: String): String {
        require(uri.startsWith("gs://$bucket/")) { "Speech-to-Text wrote outside MetaOS's bucket: $uri" }
        return uri.removePrefix("gs://$bucket/")
    }

    /**
     * As the team transcribed episodes in Speech-to-Text's console before: model long, in the episode's language alone
     * (an alternative English heard Slovenian as English gibberish), words timed; but in the EU, and the first channel
     * only, as the episodes' are mixes of the same (both, separately, were billed twice for the same words). Punctuated
     * where the model can, which long can't in Slovenian.
     */
    override fun start(path: String, output: String, language: String, batch: Boolean, hints: SpeechHints): String {
        val can = features(language)
        val config = RecognitionConfig.newBuilder()
            // WAV, FLAC, MP3, and AAC in MP4, M4A or MOV, whichever it is
            .setAutoDecodingConfig(AutoDetectDecodingConfig.getDefaultInstance())
            .setModel(model)
            .addLanguageCodes(language)
            .setFeatures(
                RecognitionFeatures.newBuilder()
                    .setEnableWordTimeOffsets("word_timestamps" in can)
                    .setEnableAutomaticPunctuation("automatic_punctuation" in can)
            )
            .build()
        val request = BatchRecognizeRequest.newBuilder()
            .setRecognizer(recognizer)
            .setConfig(config)
            .addFiles(BatchRecognizeFileMetadata.newBuilder().setUri(gs(path)))
            .setRecognitionOutputConfig(
                RecognitionOutputConfig.newBuilder()
                    .setGcsOutputConfig(GcsOutputConfig.newBuilder().setUri(gs(output) + "/"))
                    // Its own results only, which say what subtitles would
                    .setOutputFormatConfig(OutputFormatConfig.newBuilder().setNative(NativeOutputFileFormatConfig.getDefaultInstance()))
            )
            .setProcessingStrategy(if (batch) ProcessingStrategy.DYNAMIC_BATCHING else ProcessingStrategy.PROCESSING_STRATEGY_UNSPECIFIED)
            .build()
        // The operation as Google started it, without a future that would keep asking after it in this JVM
        return client.batchRecognizeCallable().call(request).name
    }

    // Google writes its answer under the output itself, as start told it
    override fun result(operation: String, path: String, output: String): Recognized {
        val op = client.operationsClient.getOperation(operation)
        if (!op.done) throw StillRecognizing(op.metadata.takeIf { it.`is`(OperationMetadata::class.java) }
            ?.unpack(OperationMetadata::class.java)?.progressPercent)
        if (op.hasError()) throw IllegalStateException("Speech-to-Text failed: ${op.error.message}")
        val response = op.response.unpack(BatchRecognizeResponse::class.java)
        val file = checkNotNull(response.resultsMap[gs(path)]) { "Speech-to-Text has no result for ${gs(path)}" }
        if (file.hasError() && file.error.code != 0) throw IllegalStateException("Speech-to-Text couldn't transcribe it: ${file.error.message}")
        val stored = file.cloudStorageResult
        val billed = response.totalBilledDuration.takeIf { response.hasTotalBilledDuration() }?.let { it.seconds + it.nanos / 1e9 }
        return Recognized(pathOf(stored.uri), billed)
    }

    override fun cancel(operation: String) = client.operationsClient.cancelOperation(operation)

    override fun heard(results: String) = googleHeard(results)

    override fun destroy() {
        if (lazyClient.isInitialized()) client.close()
    }
}
