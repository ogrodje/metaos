package si.ogrodje.metaos

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.PendingActivityState
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowFailedException
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.TemporalFailure
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The task queue patreon-gateway works on, the activities that change Patreon (its Activities.kt) */
const val PATREON_QUEUE = "patreon"

/** Changes in Patreon take turns, whoever asks for them (Slots.kt, metaos.slots.patreon) */
const val PATREON_SLOT = "patreon"

/** Who may see a post, as patreon-gateway has it: [tiers] by their IDs for TIERS */
data class PatreonPostAccess(val kind: PatreonAccess, val tiers: List<String> = emptyList())

/** What a post says; what's null stays as it is when it's changed */
data class PatreonPostSpec(val title: String? = null, val text: String? = null, val access: PatreonPostAccess? = null)

/** A file for the gateway to download from [url] and put on a post as its audio or video, named [fileName] */
data class PatreonMediaRequest(val url: String, val kind: PatreonMediaKind, val fileName: String? = null)

/** What a change does in Patreon; [id] is how its jobs are named, as patreon-gateway named them */
enum class PatreonChangeKind(val id: String) {
    CREATE_DRAFT("create-draft"),
    UPDATE_POST("update-post"),
    ATTACH_MEDIA("attach-media"),
    DELETE_POST("delete-post"),
}

/**
 * A change in Patreon: a new draft as [spec] says, a post changed or deleted, or a file put on it, from [media] or, of
 * MetaOS's own video and audio, the file [mediaId], which the workflow signs a link to when it's its turn, named on
 * Patreon [fileName] or else as it is
 */
data class PatreonChange(
    val kind: PatreonChangeKind,
    val postId: String? = null,
    val spec: PatreonPostSpec? = null,
    val media: PatreonMediaRequest? = null,
    val mediaId: UUID? = null,
    val fileName: String? = null,
)

/**
 * What patreon-gateway does in Patreon's web app, as activities on its task queue ([PATREON_QUEUE]), which it works on
 * one at a time. The gateway has the same interface, by the same names, with its own types of the same shape: posts
 * and media come back as they're there, as maps. There's nothing to publish with: a person publishes, in Patreon.
 */
@ActivityInterface
interface PatreonActivities {
    @ActivityMethod(name = "PatreonCreateDraft")
    fun createDraft(spec: PatreonPostSpec): Map<String, Any?>

    @ActivityMethod(name = "PatreonUpdatePost")
    fun update(id: String, spec: PatreonPostSpec): Map<String, Any?>

    @ActivityMethod(name = "PatreonAttachMedia")
    fun attachMedia(id: String, request: PatreonMediaRequest): Map<String, Any?>

    @ActivityMethod(name = "PatreonDeletePost")
    fun delete(id: String): Map<String, Any?>
}

/** MetaOS's own part of a change: a link to one of an episode's files for the gateway to download */
@ActivityInterface
interface PatreonMediaActivities {
    /** A link to the file, signed for [PATREON_LINK_FOR], with what it is on Patreon and its name */
    fun mediaLink(mediaId: UUID): PatreonMediaRequest
}

/**
 * A change in Patreon (a draft made, changed, deleted, or given its audio or video), which Temporal keeps and runs to
 * its end through restarts of MetaOS and the gateway. Changes take turns ([PATREON_SLOT], one at a time), so they're
 * made in the order they were asked for and Patreon is asked one thing at a time; waiting holds nothing. Patreon turns
 * the gateway away when it's asked often (a rate limit, Cloudflare's bot check): the change waits and tries again,
 * after 2 minutes, then ever later, up to every half hour, for half a day (an upload, a day), holding its turn. So does
 * one that finds the gateway, its browser or its Patreon session down, e.g. till someone runs login.sh again. What
 * trying again won't change fails it at once. Its ID says what it is ([PatreonChanges]); it's the job get_patreon_job
 * follows.
 */
@WorkflowInterface
interface PatreonChangeWorkflow : SlotHolder {
    @WorkflowMethod
    fun change(change: PatreonChange): Map<String, Any?>
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class PatreonChangeWorkflowImpl : PatreonChangeWorkflow {

    private val granted = mutableSetOf<String>()

    private val retries = RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofMinutes(2)).setBackoffCoefficient(2.0).setMaximumInterval(Duration.ofMinutes(30))
        .setMaximumAttempts(0)
        .build()

    private val changes = Workflow.newActivityStub(
        PatreonActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(PATREON_QUEUE)
            // A page or two and some calls, each of which Patreon may take two minutes over
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setScheduleToCloseTimeout(Duration.ofHours(12))
            .setRetryOptions(retries)
            .build(),
    )

    // An episode's video takes long to download and send, so the gateway says it's alive meanwhile, and one that went
    // away (a restart) is noticed in minutes; the next try goes on from what it uploaded
    private val uploads = Workflow.newActivityStub(
        PatreonActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(PATREON_QUEUE)
            .setStartToCloseTimeout(Duration.ofHours(8))
            .setHeartbeatTimeout(Duration.ofMinutes(2))
            .setScheduleToCloseTimeout(PATREON_LINK_FOR)
            .setRetryOptions(retries)
            .build(),
    )

    private val links = Workflow.newActivityStub(
        PatreonMediaActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).setDoNotRetry(IllegalArgumentException::class.java.name).build())
            .build(),
    )

    override fun granted(resource: String) {
        granted += resource
    }

    override fun change(change: PatreonChange): Map<String, Any?> = withSlot(PATREON_SLOT, granted) {
        fun post() = checkNotNull(change.postId)
        when (change.kind) {
            PatreonChangeKind.CREATE_DRAFT -> changes.createDraft(checkNotNull(change.spec))
            PatreonChangeKind.UPDATE_POST -> changes.update(post(), checkNotNull(change.spec))
            // Signed once it's its turn, so the link is good for as long as the upload may try
            PatreonChangeKind.ATTACH_MEDIA -> uploads.attachMedia(
                post(),
                change.media ?: links.mediaLink(checkNotNull(change.mediaId)).let { it.copy(fileName = change.fileName ?: it.fileName) },
            )
            PatreonChangeKind.DELETE_POST -> changes.delete(post())
        }
    }
}

/** How long the gateway has to download one of MetaOS's files: as long as an upload tries */
val PATREON_LINK_FOR: Duration = Duration.ofDays(1)

@Component
@ConditionalOnStorage
@ConditionalOnTemporal
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class PatreonMediaActivitiesImpl(private val storage: Storage, private val media: MediaFilesRepository) : PatreonMediaActivities {
    override fun mediaLink(mediaId: UUID): PatreonMediaRequest {
        val file = requireNotNull(media.findByIdOrNull(mediaId)) { "The file $mediaId was removed" }
        val kind = if (file.kind.video) PatreonMediaKind.VIDEO else PatreonMediaKind.AUDIO
        return PatreonMediaRequest(storage.downloadUrl(file.path, PATREON_LINK_FOR).toString(), kind, file.name)
    }
}

/**
 * Changes in Patreon, as workflows ([PatreonChangeWorkflow]), and how each is going, as a job: QUEUED while it waits
 * its turn, for the gateway, or to try again after Patreon turned it away (with why, and when), RUNNING while the
 * gateway is at it, then SUCCEEDED with what it made, or FAILED and why. Temporal keeps them two weeks after they end.
 */
@Component
@ConditionalOnPatreon
@ConditionalOnTemporal
class PatreonChanges(private val client: WorkflowClient, private val mapper: JsonMapper) {

    /** Starts [change], and answers with its job as it is then */
    fun start(change: PatreonChange): PatreonJob {
        val id = "patreon-${change.kind.id}-${UUID.randomUUID()}"
        val workflow = client.newWorkflowStub(
            PatreonChangeWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(WORKFLOWS_QUEUE).setWorkflowId(id).build(),
        )
        WorkflowClient.start(workflow::change, change)
        return job(id)
    }

    /** Waits up to [timeout] for the job to end, and answers with it as it is then */
    fun await(job: PatreonJob, timeout: Duration): PatreonJob {
        if (job.done) return job
        try {
            client.newUntypedWorkflowStub(job.id).getResult(timeout.toMillis(), TimeUnit.MILLISECONDS, Any::class.java)
        } catch (_: TimeoutException) {
            // Still on
        } catch (_: WorkflowFailedException) {
            // Said by the job
        }
        return job(job.id)
    }

    fun job(id: String): PatreonJob {
        val kind = PatreonChangeKind.entries.firstOrNull { id.startsWith("patreon-${it.id}-") }
            ?: throw IllegalArgumentException("$id isn't a Patreon job: theirs start with patreon-, e.g. patreon-create-draft-...")
        val stub = client.newUntypedWorkflowStub(id)
        val description = try {
            stub.describe()
        } catch (_: WorkflowNotFoundException) {
            throw IllegalArgumentException("No Patreon job $id: Temporal keeps them two weeks after they end")
        }
        return when (description.status) {
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING -> {
                // The gateway's activity, once it's its turn; MetaOS's own (a file's link) is quick
                val pending = description.rawDescription.pendingActivitiesList.firstOrNull { it.activityType.name.startsWith("Patreon") }
                val failure = pending?.takeIf { it.hasLastFailure() }?.lastFailure
                // Waiting to try again, after the last try failed
                val retryAt = pending?.takeIf { it.hasNextAttemptScheduleTime() }?.nextAttemptScheduleTime
                    ?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }?.takeIf { it.isAfter(Instant.now()) }
                PatreonJob(
                    id = id,
                    kind = kind.id,
                    status = if (pending?.state == PendingActivityState.PENDING_ACTIVITY_STATE_STARTED && retryAt == null) "RUNNING" else "QUEUED",
                    result = null,
                    error = failure?.message?.ifEmpty { null },
                    attempts = pending?.attempt ?: 0,
                    retryAt = retryAt?.atOffset(ZoneOffset.UTC),
                )
            }
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED ->
                PatreonJob(id, kind.id, "SUCCEEDED", mapper.valueToTree(stub.getResult(Map::class.java)), null, 0, null)
            else -> PatreonJob(id, kind.id, "FAILED", null, why(stub), 0, null)
        }
    }

    // What went wrong, as the gateway or Temporal said it, without Temporal's wrapping
    private fun why(stub: WorkflowStub): String = try {
        stub.getResult(Any::class.java)
        "It ended without finishing"
    } catch (e: WorkflowFailedException) {
        var cause: Throwable? = e.cause
        while (cause is ActivityFailure) cause = cause.cause
        ((cause as? ApplicationFailure)?.originalMessage ?: (cause as? TemporalFailure)?.originalMessage ?: cause?.message ?: e.message ?: "It failed").take(2000)
    }
}
