package si.ogrodje.metaos

import io.temporal.activity.Activity
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.TemporalFailure
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.CancellationScope
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What a transcription is of, and for whom: one of an episode's files, by IDs, as the workflow keeps them, and its row
 * in transcriptions (Transcriptions.kt), which the workflow keeps up to date
 */
data class TranscriptionRequest(
    val transcriptionId: UUID,
    val episodeId: UUID,
    val mediaId: UUID,
    // What's said in it, BCP-47, e.g. sl-SI: the episode's show's language, unless asked for another
    val language: String,
    // Who hears it (SpeechProviders), e.g. google or soniox
    val provider: String,
    // What the provider may go by, to spell names as the episode has them
    val hints: SpeechHints,
    // The provider's batching, e.g. Speech-to-Text's: within a day, for a fifth of the price
    val batch: Boolean,
    // How long it waits, at first, before asking the provider again (metaos.transcription.poll-every), in milliseconds
    val pollMillis: Long,
    val requesterId: String?,
    val requester: String?,
)

/**
 * Where the provider started: who it is and with which model, its operation, the file it's on and where it leaves what
 * it heard, in MetaOS's bucket
 */
data class RecognitionStarted(
    val provider: String,
    val model: String,
    val operation: String,
    val mediaPath: String,
    val output: String,
)

/** Whether the provider is done: what it heard, if it is, else how far it got, if it says */
data class RecognitionCheck(val recognized: Recognized? = null, val percent: Int? = null)

data class TranscriptionResult(
    val transcriptId: UUID,
    val segments: Int,
    val words: Int,
    // Seconds of audio, as the provider billed them
    val duration: Double?,
)

/**
 * A transcript of one of an episode's files: a speech-to-text provider hears it from MetaOS's bucket, taking minutes,
 * or up to a day in a batch, which Temporal waits out through restarts and deploys, and MetaOS saves what it heard as
 * the file's transcript, in its own format, in the database and the bucket (Transcripts.kt); its row in transcriptions
 * says how far it is (Transcriptions.kt). Its ID is the file's and the provider's ([workflowId]), so a file has one
 * transcription at a time by each. A provider hears so many at once (Slots.kt, metaos.slots.<provider>); the rest wait
 * their turn, holding nothing. Cancelled, it cancels the provider's too. What's done with a transcript, e.g. its
 * episode's chapters, is for others (Agents.kt).
 */
@WorkflowInterface
interface TranscriptionWorkflow : SlotHolder {
    @WorkflowMethod
    fun transcribe(request: TranscriptionRequest): TranscriptionResult

    companion object {
        fun workflowId(mediaId: UUID, provider: String) = "transcribe-$mediaId-$provider"
    }
}

@ActivityInterface
interface TranscriptionActivities {
    /** The transcription's row says it's [status], and, failed, why */
    fun mark(transcriptionId: UUID, status: TranscriptionStatus, error: String?)

    /** Starts the provider on the file, and the row says so; at most once in a while, as each start is billed */
    fun startRecognition(request: TranscriptionRequest): RecognitionStarted

    /** Asks the provider whether it's done, once, and the row says how far it got, where it says */
    fun checkRecognition(transcriptionId: UUID, started: RecognitionStarted): RecognitionCheck

    /** Asks the provider to stop, where it can, as it was cancelled */
    fun cancelRecognition(started: RecognitionStarted)

    /**
     * What the provider heard, as the file's transcript, in place of the one it had by this provider and of its
     * translation into this language, and the row says it's done; the same one when it's done again
     */
    fun saveTranscript(request: TranscriptionRequest, started: RecognitionStarted, recognized: Recognized): TranscriptionResult
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class TranscriptionWorkflowImpl : TranscriptionWorkflow {

    private val granted = mutableSetOf<String>()
    private var started: RecognitionStarted? = null

    private val quick = Workflow.newActivityStub(
        TranscriptionActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).setDoNotRetry(*FINAL).build())
            .build(),
    )

    // A provider that's busy (too many requests) or down is asked again, ever less often, for a few hours
    private val starting = Workflow.newActivityStub(
        TranscriptionActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setScheduleToCloseTimeout(Duration.ofHours(6))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(30)).setBackoffCoefficient(2.0).setMaximumInterval(Duration.ofMinutes(10))
                    .setMaximumAttempts(0).setDoNotRetry(*FINAL).build()
            )
            .build(),
    )

    // As starting, but for a provider that says it couldn't (IllegalStateException), which asking again won't change
    private val checking = Workflow.newActivityStub(
        TranscriptionActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setScheduleToCloseTimeout(Duration.ofHours(6))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(30)).setBackoffCoefficient(2.0).setMaximumInterval(Duration.ofMinutes(10))
                    .setMaximumAttempts(0).setDoNotRetry(IllegalStateException::class.java.name, *FINAL).build()
            )
            .build(),
    )

    override fun granted(resource: String) {
        granted += resource
    }

    override fun transcribe(request: TranscriptionRequest): TranscriptionResult {
        val id = request.transcriptionId
        try {
            // A turn at the provider for its start and its wait, not for saving
            val recognized = withSlot(request.provider, granted) {
                quick.mark(id, TranscriptionStatus.STARTING, null)
                val on = starting.startRecognition(request).also { started = it }
                recognize(id, on, Duration.ofMillis(request.pollMillis))
            }
            return quick.saveTranscript(request, checkNotNull(started), recognized)
        } catch (failed: TemporalFailure) {
            // Cancelled (also as an activity that was on then, which fails with it), or failed; either way the row says
            // so, and a cancelled provider stops, where it can
            val cancelled = CancellationScope.current().isCancelRequested
            Workflow.newDetachedCancellationScope {
                if (cancelled) {
                    started?.let {
                        try {
                            quick.cancelRecognition(it)
                        } catch (_: ActivityFailure) {
                            // It goes on at the provider, which only costs
                        }
                    }
                    quick.mark(id, TranscriptionStatus.CANCELLED, null)
                } else {
                    quick.mark(id, TranscriptionStatus.FAILED, why(failed))
                }
            }.run()
            throw failed
        }
    }

    /**
     * Asks the provider how it's going, waiting on Temporal's timer in between, which holds nothing in MetaOS: at
     * first after [pollEvery], then ever longer, up to ten times that (5 minutes), so a day's batch doesn't make a long
     * history; for 26 hours at most
     */
    private fun recognize(id: UUID, started: RecognitionStarted, pollEvery: Duration): Recognized {
        val giveUpAt = Workflow.currentTimeMillis() + RECOGNITION_LIMIT.toMillis()
        var wait = pollEvery
        while (true) {
            checking.checkRecognition(id, started).recognized?.let { return it }
            if (Workflow.currentTimeMillis() + wait.toMillis() > giveUpAt) {
                throw ApplicationFailure.newNonRetryableFailure(
                    "${started.provider} didn't finish in ${RECOGNITION_LIMIT.toHours()} hours", "RecognitionTimedOut",
                )
            }
            Workflow.sleep(wait)
            wait = minOf(wait.multipliedBy(3).dividedBy(2), pollEvery.multipliedBy(10))
        }
    }

    // What went wrong, as the activity or provider said it, without Temporal's wrapping
    private fun why(failure: Throwable): String {
        var cause: Throwable? = failure
        while (cause is ActivityFailure) cause = cause.cause
        return ((cause as? ApplicationFailure)?.originalMessage ?: cause?.message ?: failure.message ?: "It failed").take(2000)
    }

    private companion object {
        // A batch takes up to a day
        val RECOGNITION_LIMIT: Duration = Duration.ofHours(26)

        // What trying again won't change: a request that's wrong, or a provider refusing it, e.g. Google with its API off
        val FINAL = arrayOf(
            IllegalArgumentException::class.java.name,
            SonioxRefused::class.java.name,
            "com.google.api.gax.rpc.InvalidArgumentException",
            "com.google.api.gax.rpc.PermissionDeniedException",
            "com.google.api.gax.rpc.UnauthenticatedException",
            "com.google.api.gax.rpc.NotFoundException",
        )
    }
}

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

@Component
@ConditionalOnStorage
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class TranscriptionActivitiesImpl(
    private val storage: Storage,
    private val providers: SpeechProviders,
    private val media: MediaFilesRepository,
    private val transcripts: TranscriptsRepository,
    private val texts: TranscriptTextsRepository,
    private val jobs: TranscriptionsRepository,
    private val jsonMapper: JsonMapper,
    transactionManager: PlatformTransactionManager,
) : TranscriptionActivities {

    private val transaction = TransactionTemplate(transactionManager)

    private fun info() = Activity.getExecutionContext().info

    // The transcription's row, changed as whoever asked for it, in the audit log
    private fun <T> changing(id: UUID, change: (Transcription) -> T): T? {
        val job = jobs.findByIdOrNull(id) ?: return null
        return actingFor(Actor(job.requesterId, job.requester)) {
            transaction.execute { change(checkNotNull(jobs.findByIdOrNull(id))).also { jobs.flush() } }
        }
    }

    override fun mark(transcriptionId: UUID, status: TranscriptionStatus, error: String?) {
        changing(transcriptionId) { job ->
            // One that's done stays done, as it was
            if (job.status.on) {
                job.status = status
                job.error = error
                if (!status.on) job.finishedAt = now()
            }
        }
    }

    override fun startRecognition(request: TranscriptionRequest): RecognitionStarted {
        val file = requireNotNull(media.findByIdOrNull(request.mediaId)) { "The file ${request.mediaId} was removed" }
        // Under the run, so a transcription done again doesn't mix with the one before
        val output = "transcripts/${request.mediaId}/${info().workflowRunId}"
        val speech = providers.of(request.provider)
        val operation = speech.start(file.path, output, request.language, request.batch, request.hints)
        changing(request.transcriptionId) { job ->
            job.status = TranscriptionStatus.RECOGNIZING
            job.model = speech.model
            job.startedAt = now()
        }
        return RecognitionStarted(speech.provider, speech.model, operation, file.path, output)
    }

    override fun checkRecognition(transcriptionId: UUID, started: RecognitionStarted): RecognitionCheck {
        val check = try {
            RecognitionCheck(providers.of(started.provider).result(started.operation, started.mediaPath, started.output))
        } catch (still: StillRecognizing) {
            RecognitionCheck(percent = still.percent)
        }
        // By tens, so the row changes, and the pages with it, only now and then
        val percent = check.percent?.let { it / 10 * 10 }
        if (percent != null && jobs.findByIdOrNull(transcriptionId)?.percent != percent) {
            changing(transcriptionId) { it.percent = percent }
        }
        return check
    }

    override fun cancelRecognition(started: RecognitionStarted) =
        providers.of(started.provider).cancel(started.operation)

    override fun saveTranscript(request: TranscriptionRequest, started: RecognitionStarted, recognized: Recognized): TranscriptionResult {
        // In MetaOS's own format, whoever heard it, cut into stretches of its own
        val heard = providers.of(started.provider).heard(storage.read(recognized.results, MAX_RESULTS_BYTES).decodeToString())
        val content = TranscriptContent(started.provider, started.model, request.language, inStretches(heard))
        val json = jsonMapper.writeValueAsString(content)
        // The same in the bucket as in the database, next to what the provider answered
        val path = "${started.output}/transcript.json"
        storage.upload(path, "application/json", json.encodeToByteArray().inputStream(), overwrite = true)
        val workflowId = checkNotNull(info().workflowId)
        val transcript = actingFor(Actor(request.requesterId, request.requester)) {
            checkNotNull(transaction.execute {
                val had = transcripts.findByMediaId(request.mediaId)
                // Done already, by an attempt before this one
                had.find { it.path == path } ?: run {
                    // Gone before the new one goes in, as Hibernate would insert first and a file has one in each
                    // language by each provider; another provider's stay, to compare
                    had.filter { it.provider == started.provider && (it.language == it.spokenLanguage || it.language == request.language) }
                        .forEach(transcripts::delete)
                    transcripts.flush()
                    val file = media.findByIdOrNull(request.mediaId)
                    transcripts.saveAndFlush(
                        Transcript(
                            episodeId = request.episodeId,
                            mediaId = request.mediaId,
                            mediaName = file?.name ?: request.mediaId.toString(),
                            language = request.language,
                            spokenLanguage = request.language,
                            provider = started.provider,
                            model = started.model,
                            path = path,
                            rawPath = recognized.results,
                            duration = recognized.billedSeconds,
                            segments = content.segments.size,
                            words = content.words(),
                            // Labels only, as the provider tells them apart, till someone names them
                            speakers = jsonMapper.writeValueAsString(
                                content.segments.mapNotNull { it.speaker }.distinct().map { TranscriptSpeaker(it) }
                            ),
                            workflowId = workflowId,
                            requesterId = request.requesterId,
                            requester = request.requester,
                        )
                    ).also { texts.saveAndFlush(TranscriptText(checkNotNull(it.id), json)) }
                }.also { saved ->
                    jobs.findByIdOrNull(request.transcriptionId)?.let { job ->
                        job.status = TranscriptionStatus.DONE
                        job.transcriptId = saved.id
                        job.percent = null
                        job.finishedAt = now()
                        jobs.flush()
                    }
                }
            })
        }
        return TranscriptionResult(checkNotNull(transcript.id), transcript.segments, transcript.words, recognized.billedSeconds)
    }

    private companion object {
        // A provider's JSON for a few hours of speech is a few megabytes
        const val MAX_RESULTS_BYTES = 64 * 1024 * 1024
    }
}
