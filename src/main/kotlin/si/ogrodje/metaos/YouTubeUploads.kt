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
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.TemporalFailure
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.CancellationScope
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import jakarta.persistence.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

/** Uploads to YouTube take turns, whoever asks for them (Slots.kt, metaos.slots.youtube): one at a time */
const val YOUTUBE_SLOT = "youtube"

/** How far an upload is: waiting for its turn, being sent, YouTube processing it; then done, failed or cancelled */
enum class YouTubeUploadStatus {
    QUEUED, UPLOADING, PROCESSING, DONE, FAILED, CANCELLED;

    /** Still on: waiting for its turn, being sent, or being processed */
    val on get() = this in ON

    companion object {
        val ON = setOf(QUEUED, UPLOADING, PROCESSING)
    }
}

/**
 * One of an episode's video files being sent to YouTube (V42__youtube.sql), from when it's asked for till it's there,
 * failed or cancelled, as its workflow goes ([YouTubeUploadWorkflow]), which keeps this up to date: how far it is, for
 * MetaOS's pages and tools, and what was sent when, by whom, as what. The episode and the file are linked by ID, as
 * transcriptions' are.
 */
@Entity
@Table(name = "youtube_uploads")
class YouTubeUpload(
    @Column(name = "episode_id", nullable = false) val episodeId: UUID,
    @Column(name = "media_id", nullable = false) val mediaId: UUID,
    @Column(nullable = false, columnDefinition = "text") val mediaName: String,
    @Column(nullable = false, columnDefinition = "text") val title: String,
    @Column(nullable = false, columnDefinition = "text") val description: String,
    @Column(nullable = false, columnDefinition = "text[]") val tags: Array<String>,
    // Which of the episode's pictures it gets as its thumbnail, if any
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    val thumbnail: EpisodeImage?,
    @Column(nullable = false, columnDefinition = "text") val workflowId: String,
    // Who asked for it: their Keycloak user ID and username then
    @Column(columnDefinition = "text") val requesterId: String?,
    @Column(columnDefinition = "text") val requester: String?,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var status: YouTubeUploadStatus = YouTubeUploadStatus.QUEUED

    // How much of the file is sent, by tens
    var percent: Int? = null

    @Column(columnDefinition = "text") var error: String? = null

    // What didn't go with it, e.g. its thumbnail, while the video did
    @Column(columnDefinition = "text") var note: String? = null

    // YouTube's ID of it, once the file is there
    @Column(name = "video_id", columnDefinition = "text") var videoId: String? = null

    @Column(nullable = false) val createdAt: OffsetDateTime = now()
    var startedAt: OffsetDateTime? = null
    var finishedAt: OffsetDateTime? = null

    override fun equals(other: Any?) = this === other || (other is YouTubeUpload && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "YouTubeUpload(id=$id, mediaName=$mediaName, status=$status)"
}

interface YouTubeUploadsRepository : JpaRepository<YouTubeUpload, UUID> {
    fun findByEpisodeIdInOrderByCreatedAtDesc(episodeIds: Collection<UUID>): List<YouTubeUpload>
    fun findByStatusInOrderByCreatedAtDesc(statuses: Collection<YouTubeUploadStatus>, page: Pageable): List<YouTubeUpload>
    fun findByMediaIdAndStatusIn(mediaId: UUID, statuses: Collection<YouTubeUploadStatus>): YouTubeUpload?

    /** Ends one that's still on, as [status], and says so; not one that's ended meanwhile */
    @Modifying
    @Transactional
    @Query(
        "update YouTubeUpload u set u.status = :status, u.error = :error, u.percent = null, u.finishedAt = :at " +
            "where u.id = :id and u.status in :on"
    )
    fun endIfOn(id: UUID, status: YouTubeUploadStatus, error: String?, at: OffsetDateTime, on: Collection<YouTubeUploadStatus> = YouTubeUploadStatus.ON): Int
}

/** What an upload's workflow keeps: its row, which has the rest, and how often it asks YouTube how processing goes */
data class YouTubeUploadRequest(val uploadId: UUID, val pollMillis: Long)

/** Where an upload is, kept with each chunk sent: its session at YouTube, and the video once it's all there */
data class YouTubeUploadProgress(val session: String, val videoId: String? = null)

/** What an upload made: the video, by YouTube's ID, and how YouTube has it then (processed, or still uploaded) */
data class YouTubeUploadResult(val videoId: String, val uploadStatus: String?)

/**
 * One of an episode's video files, sent from MetaOS's bucket to YouTube as a private video, with its title,
 * description, tags and thumbnail, which Temporal runs to its end through restarts and deploys: the file goes in
 * chunks, and a try that broke off goes on from the chunk YouTube has. Its ID is the file's ([workflowId]), so a file
 * is sent once at a time. Uploads take turns ([YOUTUBE_SLOT], one at a time), as each is gigabytes through MetaOS and a
 * good part of the day's quota; waiting holds nothing. YouTube being busy, down or out of quota for the day is waited
 * out and tried again, for a day; what trying again won't change fails it at once. Cancelled, it stops sending; a
 * video that's on YouTube by then stays there, private.
 */
@WorkflowInterface
interface YouTubeUploadWorkflow : SlotHolder {
    @WorkflowMethod
    fun upload(request: YouTubeUploadRequest): YouTubeUploadResult

    companion object {
        fun workflowId(mediaId: UUID) = "youtube-upload-$mediaId"
    }
}

@ActivityInterface
interface YouTubeUploadActivities {
    /** The upload's row says it's [status], and, failed, why */
    fun markUpload(uploadId: UUID, status: YouTubeUploadStatus, error: String?)

    /** Sends the file to YouTube, chunk by chunk, saying it's alive with each, and answers with the video's ID */
    fun sendVideo(uploadId: UUID): String

    /** The video is on the channel: MetaOS keeps it as the episode's, made from its file, and the row says it's processing */
    fun keepVideo(uploadId: UUID, videoId: String)

    /** Gives the video the thumbnail it was asked with, if any */
    fun setVideoThumbnail(uploadId: UUID, videoId: String)

    /** The row notes what didn't go with the video */
    fun noteUpload(uploadId: UUID, note: String)

    /** Asks YouTube how it has the video, once: uploaded (processing), processed, failed or rejected, with why */
    fun checkVideo(videoId: String): YouTubeUploadResult

    /** The row says it's done */
    fun finishUpload(uploadId: UUID, videoId: String)
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class YouTubeUploadWorkflowImpl : YouTubeUploadWorkflow {

    private val granted = mutableSetOf<String>()

    private val quick = Workflow.newActivityStub(
        YouTubeUploadActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).setDoNotRetry(*FINAL).build())
            .build(),
    )

    // The file itself: hours for an episode's master, alive with each chunk, so one whose worker went away (a deploy)
    // is noticed in minutes and goes on from the chunk YouTube has. Tried again ever later, for a day, which a day's
    // quota comes back in.
    private val sending = Workflow.newActivityStub(
        YouTubeUploadActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofHours(12))
            .setHeartbeatTimeout(Duration.ofMinutes(10))
            .setScheduleToCloseTimeout(Duration.ofHours(26))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMinutes(1)).setBackoffCoefficient(2.0).setMaximumInterval(Duration.ofMinutes(30))
                    .setMaximumAttempts(0).setDoNotRetry(*FINAL).build()
            )
            .build(),
    )

    // What YouTube may be too busy or out of quota for: asked again, ever less often, for a few hours
    private val asking = Workflow.newActivityStub(
        YouTubeUploadActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setScheduleToCloseTimeout(Duration.ofHours(6))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(30)).setBackoffCoefficient(2.0).setMaximumInterval(Duration.ofMinutes(15))
                    .setMaximumAttempts(0).setDoNotRetry(*FINAL).build()
            )
            .build(),
    )

    override fun granted(resource: String) {
        granted += resource
    }

    override fun upload(request: YouTubeUploadRequest): YouTubeUploadResult {
        val id = request.uploadId
        try {
            // A turn for the sending, not for YouTube's processing
            val videoId = withSlot(YOUTUBE_SLOT, granted) {
                quick.markUpload(id, YouTubeUploadStatus.UPLOADING, null)
                sending.sendVideo(id)
            }
            quick.keepVideo(id, videoId)
            // The video is there whatever becomes of its thumbnail, e.g. on a channel that isn't verified
            try {
                asking.setVideoThumbnail(id, videoId)
            } catch (failed: ActivityFailure) {
                if (CancellationScope.current().isCancelRequested) throw failed
                quick.noteUpload(id, "Its thumbnail wasn't set: ${why(failed)}")
            }
            val result = process(videoId, Duration.ofMillis(request.pollMillis))
            quick.finishUpload(id, videoId)
            return result
        } catch (failed: TemporalFailure) {
            // Cancelled (also as an activity that was on then, which fails with it), or failed; either way the row says so
            val cancelled = CancellationScope.current().isCancelRequested
            Workflow.newDetachedCancellationScope {
                if (cancelled) quick.markUpload(id, YouTubeUploadStatus.CANCELLED, null)
                else quick.markUpload(id, YouTubeUploadStatus.FAILED, why(failed))
            }.run()
            throw failed
        }
    }

    /**
     * Asks YouTube how it has the video, waiting on Temporal's timer in between, which holds nothing in MetaOS: at
     * first after [pollEvery], then ever longer, up to twenty times that (10 minutes). Processed, it's done; failed or
     * rejected (a duplicate, too long, ...), the upload failed, though the video is on the channel. One YouTube is
     * still at after 12 hours is done too: it's there, and the hourly look at the channel says how it ends.
     */
    private fun process(videoId: String, pollEvery: Duration): YouTubeUploadResult {
        val giveUpAt = Workflow.currentTimeMillis() + PROCESSING_LIMIT.toMillis()
        var wait = pollEvery
        while (true) {
            val check = asking.checkVideo(videoId)
            if (check.uploadStatus != "uploaded" || Workflow.currentTimeMillis() + wait.toMillis() > giveUpAt) return check
            Workflow.sleep(wait)
            wait = minOf(wait.multipliedBy(3).dividedBy(2), pollEvery.multipliedBy(20))
        }
    }

    // What went wrong, as the activity or YouTube said it, without Temporal's wrapping
    private fun why(failure: Throwable): String {
        var cause: Throwable? = failure
        while (cause is ActivityFailure) cause = cause.cause
        return ((cause as? ApplicationFailure)?.originalMessage ?: cause?.message ?: failure.message ?: "It failed").take(2000)
    }

    private companion object {
        val PROCESSING_LIMIT: Duration = Duration.ofHours(12)

        // What trying again won't change: a request that's wrong, or YouTube refusing it
        val FINAL = arrayOf(IllegalArgumentException::class.java.name, YouTubeRefused::class.java.name)
    }
}

@Component
@ConditionalOnYouTubeUploads
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class YouTubeUploadActivitiesImpl(
    private val storage: Storage,
    private val youtube: YouTube,
    private val videos: YouTubeVideos,
    private val media: MediaFilesRepository,
    private val episodes: EpisodesRepository,
    private val jobs: YouTubeUploadsRepository,
    @Value("\${metaos.youtube.category:28}") private val category: String,
    transactionManager: PlatformTransactionManager,
) : YouTubeUploadActivities {

    private val transaction = TransactionTemplate(transactionManager)

    private fun job(id: UUID) = requireNotNull(jobs.findByIdOrNull(id)) { "The upload $id is gone" }

    // The upload's row, changed as whoever asked for it, in the audit log
    private fun <T> changing(id: UUID, change: (YouTubeUpload) -> T): T? {
        val job = jobs.findByIdOrNull(id) ?: return null
        return actingFor(Actor(job.requesterId, job.requester)) {
            transaction.execute { change(checkNotNull(jobs.findByIdOrNull(id))).also { jobs.flush() } }
        }
    }

    override fun markUpload(uploadId: UUID, status: YouTubeUploadStatus, error: String?) {
        changing(uploadId) { job ->
            // One that's ended stays as it ended
            if (job.status.on) {
                job.status = status
                job.error = error
                if (status == YouTubeUploadStatus.UPLOADING && job.startedAt == null) job.startedAt = now()
                if (!status.on) {
                    job.percent = null
                    job.finishedAt = now()
                }
            }
        }
    }

    override fun sendVideo(uploadId: UUID): String {
        val context = Activity.getExecutionContext()
        val job = job(uploadId)
        val file = requireNotNull(media.findByIdOrNull(job.mediaId)) { "${job.mediaName} was removed from its episode" }
        val size = requireNotNull(storage.file(file.path)) { "${file.name} isn't in MetaOS's bucket" }.size
        val episode = episodes.findByIdOrNull(job.episodeId)
        val details = YouTubeDetails(job.title, job.description, job.tags.toList(), category, episode?.language?.substringBefore('-')?.lowercase())

        // Where the try before this one got to, if there was one: its video, when it had sent it all, else its session,
        // unless YouTube has let go of that
        val before = context.getHeartbeatDetails(YouTubeUploadProgress::class.java).orElse(null)
        before?.videoId?.let { return it }
        var session = before?.session
        var sent = session?.let {
            try {
                youtube.sent(it, size)
            } catch (_: YouTubeSessionGone) {
                session = null
                null
            }
        } ?: YouTubeSent(0, null)
        if (session == null) {
            session = youtube.startUpload(details, file.contentType, size)
            context.heartbeat(YouTubeUploadProgress(session))
        }

        var percent = -1
        while (sent.videoId == null) {
            require(sent.offset < size) { "YouTube says it has ${sent.offset} bytes of ${file.name}, which is $size" }
            val chunk = storage.read(file.path, sent.offset, minOf(CHUNK_BYTES.toLong(), size - sent.offset).toInt())
            check(chunk.isNotEmpty()) { "${file.name} ends at ${sent.offset}, short of its $size bytes" }
            sent = youtube.send(checkNotNull(session), sent.offset, chunk, size)
            // Also how a cancelled upload learns it's cancelled: the heartbeat throws
            context.heartbeat(YouTubeUploadProgress(checkNotNull(session), sent.videoId))
            // By tens, so the row changes, and the pages with it, only now and then
            val now = if (sent.videoId != null) 100 else (sent.offset * 100 / size).toInt() / 10 * 10
            if (now != percent) {
                percent = now
                changing(uploadId) { if (it.status == YouTubeUploadStatus.UPLOADING) it.percent = now }
            }
        }
        return checkNotNull(sent.videoId)
    }

    override fun keepVideo(uploadId: UUID, videoId: String) {
        val job = job(uploadId)
        actingFor(Actor(job.requesterId, job.requester)) {
            videos.look(videoId) { video ->
                video.mediaId = job.mediaId
                video.episodeId = job.episodeId
            }
        }
        changing(uploadId) {
            if (it.status.on) {
                it.status = YouTubeUploadStatus.PROCESSING
                it.videoId = videoId
                it.percent = null
            }
        }
    }

    override fun setVideoThumbnail(uploadId: UUID, videoId: String) {
        val job = job(uploadId)
        val kind = job.thumbnail ?: return
        actingFor(Actor(job.requesterId, job.requester)) { videos.setThumbnail(videoId, kind, job.episodeId.toString()) }
    }

    override fun noteUpload(uploadId: UUID, note: String) {
        changing(uploadId) { it.note = note.take(2000) }
    }

    override fun checkVideo(videoId: String): YouTubeUploadResult {
        val video = actingFor(Actor(null, null)) { videos.look(videoId) }
        val info = youtube.videos(listOf(videoId)).singleOrNull()
        if (video.uploadStatus == "failed" || video.uploadStatus == "rejected") throw ApplicationFailure.newNonRetryableFailure(
            "YouTube has the video, but ${if (video.uploadStatus == "failed") "couldn't process it" else "rejected it"}" +
                (info?.problem?.let { ": $it" } ?: "") + ". It's on the channel, private: ${video.studioUrl}",
            "YouTubeProcessingFailed",
        )
        return YouTubeUploadResult(videoId, video.uploadStatus)
    }

    override fun finishUpload(uploadId: UUID, videoId: String) {
        changing(uploadId) {
            if (it.status.on) {
                it.status = YouTubeUploadStatus.DONE
                it.videoId = videoId
                it.percent = null
                it.finishedAt = now()
            }
        }
    }

    private companion object {
        // A multiple of 256 KB, as YouTube wants every chunk but the last; in memory once, while it's sent
        const val CHUNK_BYTES = 8 * 1024 * 1024
    }
}

/** An upload as MCP clients get it: what it's of, as what, how far it is, and the video it made */
data class YouTubeUploadDto(
    val id: UUID,
    val episodeId: UUID,
    val mediaId: UUID,
    val media: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val thumbnail: EpisodeImage?,
    // Always PRIVATE: a person publishes it, in YouTube Studio
    val privacy: YouTubePrivacy,
    val status: YouTubeUploadStatus,
    // While it's QUEUED: its place in line, 1 next
    val queued: Int?,
    // While it's UPLOADING: how much of the file is sent
    val percent: Int?,
    val error: String?,
    val note: String?,
    // YouTube's ID of the video, once the file is there, where it's watched (by those who may) and where it's published
    val videoId: String?,
    val url: String?,
    val studioUrl: String?,
    // Their person's name, or else their username
    val requestedBy: String?,
    val createdAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
)

/**
 * Sending episodes' videos to YouTube, for MCP (YouTubeMCP) and GraphQL (YouTubeGraphQL) alike: starting an upload, in
 * its workflow ([YouTubeUploadWorkflow]), and its row in youtube_uploads; listing and cancelling them. It needs the
 * bot's YouTube token, Temporal (TEMPORAL_ADDRESS) and the bucket; listing needs none.
 */
@Component
class YouTubeUploads(
    private val client: ObjectProvider<WorkflowClient>,
    private val videos: YouTubeVideos,
    private val catalogue: YouTubeVideosRepository,
    private val media: MediaFilesRepository,
    private val jobs: YouTubeUploadsRepository,
    private val transcriptions: Transcriptions,
    @Value("\${metaos.youtube.poll-every:30s}") private val pollEvery: Duration,
) {

    private fun workflows() = checkNotNull(client.ifAvailable) { "MetaOS runs no workflows (TEMPORAL_ADDRESS), so it can't upload to YouTube" }

    // The one asked for, else the episode's full video
    private fun pick(episode: Episode, mediaId: UUID?): MediaFile {
        val label = episode.code ?: episode.title
        val files = media.findByEpisodeIdInOrderByUploadedAtAsc(listOf(checkNotNull(episode.id)))
        val file = if (mediaId != null) requireNotNull(files.find { it.id == mediaId }) { "$label has no file $mediaId" }
        else requireNotNull(files.find { it.kind == MediaKind.FULL_VIDEO }) { "$label has no full video to send to YouTube; upload one on its page, or say which file" }
        require(file.kind.video) { "${file.name} is audio; YouTube takes video" }
        return file
    }

    // The episode's summary where YouTube takes it as it is, else its short one, else none: a description is the team's to write
    private fun description(episode: Episode): String =
        listOfNotNull(episode.summary, episode.tinySummary).map { it.trim() }
            .firstOrNull { it.isNotEmpty() && runCatching { youtubeDescription(it) }.isSuccess }.orEmpty()

    /**
     * Starts sending one of the episode's video files to YouTube, as a private video: [mediaId]'s, else its full video,
     * with [title] (else the file's own title, else the episode's), [description] (else the episode's summary, where
     * YouTube takes it), [tags] and, as its thumbnail, one of the episode's ([thumbnail]; by default the full video gets
     * the episode's thumbnail, where it has one, and no other file any). A file that's on the channel already, sent by
     * MetaOS, goes again only when it's to ([again]); none that's being sent.
     */
    fun start(
        ref: String, mediaId: UUID?, title: String?, description: String?, tags: List<String>?, thumbnail: EpisodeImage?, noThumbnail: Boolean,
        again: Boolean,
    ): YouTubeUpload {
        val workflows = workflows()
        videos.youtube()
        val episode = videos.findEpisode(ref)
        val file = pick(episode, mediaId)
        val fileId = checkNotNull(file.id)
        catalogue.findByMediaIdAndGoneAtIsNull(fileId).firstOrNull()?.let {
            require(again) { "${file.name} is on YouTube already, as \"${it.title}\" (${it.url}); send it again only if it's meant to be there twice" }
        }
        jobs.findByMediaIdAndStatusIn(fileId, YouTubeUploadStatus.ON)?.let { on ->
            reconcile(listOf(on))
            require(jobs.findByIdOrNull(checkNotNull(on.id))?.status?.on != true) { "${file.name} is being sent to YouTube already" }
        }
        require(thumbnail == null || thumbnail in YOUTUBE_THUMBNAILS) { "A YouTube thumbnail is one of the episode's thumbnails: THUMBNAIL, THUMBNAIL_B or THUMBNAIL_C" }
        require(thumbnail == null || episode.imageId(thumbnail) != null) { "${episode.code ?: episode.title} has no ${thumbnail!!.name.lowercase().replace('_', ' ')}; set it first, or send the video without" }
        val picture = when {
            noThumbnail -> null
            thumbnail != null -> thumbnail
            else -> EpisodeImage.THUMBNAIL.takeIf { file.kind == MediaKind.FULL_VIDEO && episode.imageId(it) != null }
        }

        val jwt = currentJwt()
        val workflowId = YouTubeUploadWorkflow.workflowId(fileId)
        val job = jobs.saveAndFlush(
            YouTubeUpload(
                episodeId = checkNotNull(episode.id),
                mediaId = fileId,
                mediaName = file.name,
                title = youtubeTitle(title ?: file.title?.takeUnless { file.kind.full } ?: episode.title),
                description = description?.let(::youtubeDescription) ?: description(episode),
                tags = youtubeTags(tags.orEmpty()).toTypedArray(),
                thumbnail = picture,
                workflowId = workflowId,
                requesterId = jwt?.subject,
                requester = jwt?.getClaimAsString("preferred_username"),
            )
        )
        val workflow = workflows.newWorkflowStub(
            YouTubeUploadWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(WORKFLOWS_QUEUE)
                .setWorkflowId(workflowId)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build(),
        )
        try {
            WorkflowClient.start(workflow::upload, YouTubeUploadRequest(checkNotNull(job.id), pollEvery.toMillis()))
        } catch (e: WorkflowExecutionAlreadyStarted) {
            jobs.endIfOn(checkNotNull(job.id), YouTubeUploadStatus.FAILED, "It was being sent already", now())
            throw IllegalArgumentException("${file.name} is being sent to YouTube already")
        } catch (e: RuntimeException) {
            jobs.endIfOn(checkNotNull(job.id), YouTubeUploadStatus.FAILED, "Its workflow didn't start: ${e.message}", now())
            throw e
        }
        return job
    }

    /** Cancels an upload that's on: its workflow stops sending. A video that's on YouTube by then stays there, private. */
    fun cancel(id: UUID): YouTubeUpload {
        val job = requireNotNull(jobs.findByIdOrNull(id)) { "No YouTube upload $id" }
        require(job.status.on) { "The upload of ${job.mediaName} is ${job.status.name.lowercase()} already" }
        val workflow = client.ifAvailable?.newUntypedWorkflowStub(job.workflowId)
        val cancelling = workflow != null && !ended(job) && try {
            workflow.cancel()
            true
        } catch (e: WorkflowNotFoundException) {
            false
        }
        if (!cancelling) jobs.endIfOn(id, YouTubeUploadStatus.CANCELLED, null, now())
        return checkNotNull(jobs.findByIdOrNull(id))
    }

    /** Uploads, the latest first: those in [statuses], of [episode] where it's given, at most [limit] */
    fun list(statuses: Collection<YouTubeUploadStatus>, episode: UUID?, limit: Int): List<YouTubeUpload> {
        fun find(): List<YouTubeUpload> =
            if (episode != null) jobs.findByEpisodeIdInOrderByCreatedAtDesc(listOf(episode)).filter { it.status in statuses }.take(limit)
            else jobs.findByStatusInOrderByCreatedAtDesc(statuses, PageRequest.of(0, limit.coerceIn(1, 500)))
        val found = find()
        return if (reconcile(found.filter { it.status.on }) > 0) find() else found
    }

    /** The uploads of several episodes, in one query, the latest first */
    fun of(episodes: Collection<Episode>): Map<Episode, List<YouTubeUpload>> {
        val byId = jobs.findByEpisodeIdInOrderByCreatedAtDesc(episodes.mapNotNull { it.id }).groupBy { it.episodeId }
        return episodes.associateWith { byId[it.id].orEmpty() }
    }

    /** An upload by its ID, as it is now: one on whose workflow ended without saying so (terminated) as failed */
    fun get(id: UUID): YouTubeUpload {
        val job = requireNotNull(jobs.findByIdOrNull(id)) { "No YouTube upload $id" }
        return if (job.status.on && reconcile(listOf(job)) > 0) checkNotNull(jobs.findByIdOrNull(id)) else job
    }

    // Whether an upload's workflow ended, as a transcription's is told (Transcriptions.ended)
    private fun ended(job: YouTubeUpload): Boolean {
        val workflows = client.ifAvailable ?: return false
        val run = try {
            workflows.newUntypedWorkflowStub(job.workflowId).describe()
        } catch (e: WorkflowNotFoundException) {
            null
        }
        // Give or take Temporal's clock against MetaOS's
        val ours = run != null && !run.startTime.isBefore(job.createdAt.toInstant().minusSeconds(5))
        return if (ours) run.status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        else job.createdAt.isBefore(now().minusMinutes(1))
    }

    // Those on whose workflow ended without saying how it went, e.g. terminated, as failed. How many.
    private fun reconcile(on: List<YouTubeUpload>): Int {
        if (client.ifAvailable == null) return 0
        return on.filter(::ended).sumOf {
            jobs.endIfOn(checkNotNull(it.id), YouTubeUploadStatus.FAILED, "Its workflow ended without saying how it went", now())
        }
    }

    /** Their places in line, 1 next, of those QUEUED, as the uploads' slots have them (Slots.kt) */
    fun places(of: Collection<YouTubeUpload>): Map<UUID, Int> {
        val queued = of.filter { it.status == YouTubeUploadStatus.QUEUED }
        val workflows = client.ifAvailable ?: return emptyMap()
        if (queued.isEmpty()) return emptyMap()
        val waiting = runCatching {
            workflows.newWorkflowStub(SlotsWorkflow::class.java, SlotsWorkflow.workflowId(YOUTUBE_SLOT)).state().waiting
        }.getOrDefault(emptyList())
        return queued.mapNotNull { job -> waiting.indexOf(job.workflowId).takeIf { it >= 0 }?.let { checkNotNull(job.id) to it + 1 } }.toMap()
    }

    fun dtos(of: List<YouTubeUpload>): List<YouTubeUploadDto> {
        val places = places(of)
        val names = transcriptions.requesters(of.map { it.requesterId to it.requester })
        return of.map { job ->
            YouTubeUploadDto(
                checkNotNull(job.id), job.episodeId, job.mediaId, job.mediaName, job.title, job.description, job.tags.toList(),
                job.thumbnail, YouTubePrivacy.PRIVATE, job.status, places[job.id], job.percent, job.error, job.note, job.videoId,
                job.videoId?.let(::youtubeUrl), job.videoId?.let(::youtubeStudioUrl),
                job.requesterId?.let { names[it] } ?: job.requester, job.createdAt, job.startedAt, job.finishedAt,
            )
        }
    }
}
