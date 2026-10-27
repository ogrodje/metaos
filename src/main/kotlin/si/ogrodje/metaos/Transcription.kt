package si.ogrodje.metaos

import io.temporal.activity.Activity
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ChildWorkflowFailure
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What a transcription is of, and for whom: one of an episode's files, by IDs, as the workflow keeps them */
data class TranscriptionRequest(
    val episodeId: UUID,
    // For the agent, which knows episodes by their codes; else the ID
    val episodeRef: String,
    val mediaId: UUID,
    // What's said in it, BCP-47, e.g. sl-SI: the episode's show's language, unless asked for another
    val language: String,
    // Speech-to-Text's dynamic batching: within a day, for a fifth of the price
    val batch: Boolean,
    // Whether an agent then posts the episode's chapters and summary as a comment (Agents.kt)
    val chapters: Boolean,
    val requesterId: String?,
    val requester: String?,
)

/** Where Speech-to-Text started: its operation, the file it's on and where it writes, in MetaOS's bucket */
data class RecognitionStarted(val operation: String, val mediaPath: String, val output: String)

data class TranscriptionResult(
    val transcriptId: UUID,
    val segments: Int,
    val words: Int,
    // Seconds of audio, as Speech-to-Text billed them
    val duration: Double?,
    // What the agent answered, once it posted the chapters; or why it didn't
    val chapters: String? = null,
)

/** How far a transcription is: starting, Speech-to-Text hearing it, saving it, the agent on its chapters, done */
enum class TranscriptionStage { STARTING, RECOGNIZING, SAVING, CHAPTERS, DONE }

/**
 * A transcript of one of an episode's files: Speech-to-Text hears it from MetaOS's bucket, taking minutes, or up to a
 * day in a batch, which Temporal waits out through restarts and deploys; MetaOS saves what it heard as the file's
 * transcript (Transcripts.kt); then, if asked, an agent reads it and posts the episode's chapters (Agents.kt). Its ID
 * is the file's ([workflowId]), so a file has one transcription at a time.
 */
@WorkflowInterface
interface TranscriptionWorkflow {
    @WorkflowMethod
    fun transcribe(request: TranscriptionRequest): TranscriptionResult

    @QueryMethod
    fun stage(): TranscriptionStage

    companion object {
        fun workflowId(mediaId: UUID) = "transcribe-$mediaId"
    }
}

@ActivityInterface
interface TranscriptionActivities {
    /** Starts Speech-to-Text on the file; at most once in a while, as each start is billed */
    fun startRecognition(request: TranscriptionRequest): RecognitionStarted

    /** Waits, heartbeating how far it got, until Speech-to-Text is done */
    fun awaitRecognition(started: RecognitionStarted): Recognized

    /**
     * What Speech-to-Text heard, as the file's transcript, in place of the one it had and of any translation into this
     * language; the same one when it's done again
     */
    fun saveTranscript(request: TranscriptionRequest, started: RecognitionStarted, recognized: Recognized): TranscriptionResult
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class TranscriptionWorkflowImpl : TranscriptionWorkflow {

    private var stage = TranscriptionStage.STARTING

    private val quick = Workflow.newActivityStub(
        TranscriptionActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).setDoNotRetry(*FINAL).build())
            .build(),
    )

    // Up to a day and a bit, for a batch; a worker that went away is noticed by its heartbeats, and another asks on
    private val waiting = Workflow.newActivityStub(
        TranscriptionActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofHours(26))
            .setHeartbeatTimeout(Duration.ofMinutes(3))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(20).setDoNotRetry(IllegalStateException::class.java.name).build())
            .build(),
    )

    override fun transcribe(request: TranscriptionRequest): TranscriptionResult {
        val started = quick.startRecognition(request)
        stage = TranscriptionStage.RECOGNIZING
        val recognized = waiting.awaitRecognition(started)
        stage = TranscriptionStage.SAVING
        val saved = quick.saveTranscript(request, started, recognized)
        if (!request.chapters) return saved.also { stage = TranscriptionStage.DONE }

        // The transcript is saved either way: an agent that fails leaves it be, and says why
        stage = TranscriptionStage.CHAPTERS
        val agent = Workflow.newChildWorkflowStub(
            AgentWorkflow::class.java,
            ChildWorkflowOptions.newBuilder().setWorkflowId("chapters-${request.mediaId}-${Workflow.getInfo().runId}").build(),
        )
        val chapters = try {
            agent.run(chaptersTask(request.episodeRef, request.language)).text
        } catch (e: ChildWorkflowFailure) {
            "No chapters: ${e.cause?.message ?: e.message}"
        }
        stage = TranscriptionStage.DONE
        return saved.copy(chapters = chapters)
    }

    override fun stage() = stage

    private companion object {
        // What trying again won't change: a request that's wrong, or Google refusing it, e.g. with its API off
        val FINAL = arrayOf(
            IllegalArgumentException::class.java.name,
            "com.google.api.gax.rpc.InvalidArgumentException",
            "com.google.api.gax.rpc.PermissionDeniedException",
            "com.google.api.gax.rpc.UnauthenticatedException",
            "com.google.api.gax.rpc.NotFoundException",
        )
    }
}

@Component
@ConditionalOnStorage
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class TranscriptionActivitiesImpl(
    private val storage: Storage,
    private val speech: SpeechToText,
    private val media: MediaFilesRepository,
    private val transcripts: TranscriptsRepository,
    private val jsonMapper: JsonMapper,
    transactionManager: PlatformTransactionManager,
    @Value("\${metaos.transcription.poll-every:30s}") private val pollEvery: Duration,
) : TranscriptionActivities {

    private val transaction = TransactionTemplate(transactionManager)

    private fun info() = Activity.getExecutionContext().info

    override fun startRecognition(request: TranscriptionRequest): RecognitionStarted {
        val file = requireNotNull(media.findByIdOrNull(request.mediaId)) { "The file ${request.mediaId} was removed" }
        // Under the run, so a transcription done again doesn't mix with the one before
        val output = "transcripts/${request.mediaId}/${info().workflowRunId}"
        return RecognitionStarted(speech.start(file.path, output, request.language, request.batch), file.path, output)
    }

    override fun awaitRecognition(started: RecognitionStarted): Recognized {
        val context = Activity.getExecutionContext()
        while (true) {
            try {
                return speech.result(started.operation, started.mediaPath)
            } catch (still: StillRecognizing) {
                // Also how a cancelled workflow stops it waiting
                context.heartbeat(still.percent)
            }
            Thread.sleep(pollEvery)
        }
    }

    override fun saveTranscript(request: TranscriptionRequest, started: RecognitionStarted, recognized: Recognized): TranscriptionResult {
        val segments = speech.segments(storage.read(recognized.results, MAX_RESULTS_BYTES).decodeToString())
        val content = TranscriptContent(emptyList(), segments)
        val path = "${started.output}/transcript.json"
        storage.upload(path, "application/json", jsonMapper.writeValueAsBytes(content).inputStream(), overwrite = true)
        val words = segments.sumOf { segment -> segment.text.split(Regex("\\s+")).count { it.isNotEmpty() } }
        val workflowId = checkNotNull(info().workflowId)
        val transcript = actingFor(Actor(request.requesterId, request.requester)) {
            checkNotNull(transaction.execute {
                val had = transcripts.findByMediaId(request.mediaId)
                // Done already, by an attempt before this one
                had.find { it.path == path }?.let { return@execute it }
                // Gone before the new one goes in, as Hibernate would insert first and a file has one in each language
                had.filter { it.language == it.spokenLanguage || it.language == request.language }.forEach(transcripts::delete)
                transcripts.flush()
                val file = media.findByIdOrNull(request.mediaId)
                transcripts.saveAndFlush(
                    Transcript(
                        episodeId = request.episodeId,
                        mediaId = request.mediaId,
                        mediaName = file?.name ?: request.mediaId.toString(),
                        path = path,
                        language = request.language,
                        spokenLanguage = request.language,
                        model = speech.model,
                        duration = recognized.billedSeconds,
                        words = words,
                        speakers = "[]",
                        workflowId = workflowId,
                        requesterId = request.requesterId,
                        requester = request.requester,
                    )
                )
            })
        }
        return TranscriptionResult(checkNotNull(transcript.id), segments.size, words, recognized.billedSeconds)
    }

    private companion object {
        // Speech-to-Text's JSON for a few hours of speech is a few megabytes
        const val MAX_RESULTS_BYTES = 64 * 1024 * 1024
    }
}

/** A transcription asked for: its workflow, the file it's of, and whether it's on or done */
data class TranscriptionDto(
    val workflowId: String,
    val mediaId: UUID,
    val media: String,
    // RUNNING, COMPLETED, FAILED, ...: Temporal's
    val status: String,
    // While it's running
    val stage: TranscriptionStage?,
    val startedAt: Instant?,
    val closedAt: Instant?,
    val note: String? = null,
)

/**
 * Transcribing episodes' files, for MCP (TranscriptsMCP) and later the front-end: which file, whether it may, and
 * starting its workflow (TranscriptionWorkflow) or saying how it's doing
 */
@Component
@ConditionalOnTemporal
class Transcription(
    private val client: WorkflowClient,
    private val episodes: EpisodesRepository,
    private val media: MediaFilesRepository,
    private val transcripts: TranscriptsRepository,
    private val speech: org.springframework.beans.factory.ObjectProvider<SpeechToText>,
    // Off without OpenRouter's key (application.properties)
    @Value("#{'\${spring.ai.openai.api-key}' != 'none'}") private val agents: Boolean,
) {

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    // The one asked for, else the episode's full audio, else its full video, which Speech-to-Text reads the audio of
    private fun pick(episode: Episode, mediaId: UUID?): MediaFile {
        val files = media.findByEpisodeIdInOrderByUploadedAtAsc(listOf(checkNotNull(episode.id)))
        if (mediaId != null) return requireNotNull(files.find { it.id == mediaId }) { "${episode.code ?: episode.title} has no file $mediaId" }
        return requireNotNull(files.find { it.kind == MediaKind.FULL_AUDIO } ?: files.find { it.kind == MediaKind.FULL_VIDEO }) {
            "${episode.code ?: episode.title} has no full audio or video to transcribe; upload one on its page"
        }
    }

    /**
     * Starts transcribing the episode's file, as spoken in [language] (BCP-47), else its show's language; one that has
     * a transcript only when it's to be replaced, and none that's being transcribed. [chapters] has an agent post the
     * chapters too, when MetaOS has agents (OPENROUTER_API_KEY).
     */
    fun start(ref: String, mediaId: UUID?, language: String?, batch: Boolean, replace: Boolean, chapters: Boolean): TranscriptionDto {
        checkNotNull(speech.ifAvailable) { "MetaOS has no bucket for video and audio (GOOGLE_STORAGE_KEY), so nothing to transcribe" }
        val episode = findEpisode(ref)
        val file = pick(episode, mediaId)
        val fileId = checkNotNull(file.id)
        transcripts.findSpoken(fileId)?.let {
            require(replace) { "${file.name} has a transcript already, from ${it.createdAt.toLocalDate()}; replace it, or keep that one" }
        }
        val jwt = currentJwt()
        val request = TranscriptionRequest(
            episodeId = checkNotNull(episode.id),
            episodeRef = episode.code ?: episode.id.toString(),
            mediaId = fileId,
            language = language?.let(::languageTag) ?: episode.language,
            batch = batch,
            chapters = chapters && agents,
            requesterId = jwt?.subject,
            requester = jwt?.getClaimAsString("preferred_username"),
        )
        val workflow = client.newWorkflowStub(
            TranscriptionWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(WORKFLOWS_QUEUE)
                .setWorkflowId(TranscriptionWorkflow.workflowId(fileId))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build(),
        )
        try {
            WorkflowClient.start(workflow::transcribe, request)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            throw IllegalArgumentException("${file.name} is being transcribed already")
        }
        val note = when {
            chapters && !agents -> "MetaOS has no agents (OPENROUTER_API_KEY), so no chapters"
            batch -> "In a batch, Speech-to-Text takes up to a day"
            else -> null
        }
        return checkNotNull(of(file)).copy(note = note)
    }

    /** The file's transcription, as Temporal has it: on, done or failed; null when there was none */
    fun of(file: MediaFile): TranscriptionDto? {
        val workflowId = TranscriptionWorkflow.workflowId(checkNotNull(file.id))
        val execution = try {
            client.newUntypedWorkflowStub(workflowId).describe()
        } catch (e: WorkflowNotFoundException) {
            return null
        }
        val running = execution.status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        val stage = if (running) runCatching {
            client.newWorkflowStub(TranscriptionWorkflow::class.java, workflowId).stage()
        }.getOrNull() else null
        return TranscriptionDto(
            workflowId, checkNotNull(file.id), file.name, execution.status.name.removePrefix("WORKFLOW_EXECUTION_STATUS_"),
            stage, execution.executionTime, execution.closeTime,
        )
    }

    /** The transcriptions of an episode's files */
    fun of(ref: String): List<TranscriptionDto> =
        media.findByEpisodeIdInOrderByUploadedAtAsc(listOf(checkNotNull(findEpisode(ref).id))).mapNotNull(::of)
}
