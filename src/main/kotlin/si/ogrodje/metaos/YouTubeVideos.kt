package si.ogrodje.metaos

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
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import jakarta.persistence.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)
private fun Instant.kept() = utc().truncatedTo(ChronoUnit.MICROS)

/**
 * One of the videos on Ogrodje's YouTube channel (V42__youtube.sql), as YouTube's API said it was when MetaOS last
 * looked: every hour, and right after MetaOS uploaded or changed it. Its episode, where it has one, by ID, as an
 * episode is soft-deleted.
 */
@Entity
@Table(name = "youtube_videos")
class YouTubeVideo(
    // YouTube's ID, e.g. dQw4w9WgXcQ
    @Column(name = "video_id", nullable = false, columnDefinition = "text") val videoId: String,
    @Column(name = "channel_id", nullable = false, columnDefinition = "text") val channelId: String,
    @Column(nullable = false, columnDefinition = "text") var title: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Column(nullable = false, columnDefinition = "text") var description: String = ""
    @Column(nullable = false, columnDefinition = "text[]") var tags: Array<String> = emptyArray()
    @Column(name = "category_id", columnDefinition = "text") var categoryId: String? = null
    @Column(columnDefinition = "text") var language: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var privacy: YouTubePrivacy = YouTubePrivacy.PRIVATE

    // YouTube's: uploaded (and being processed), processed, failed, rejected or deleted
    @Column(name = "upload_status", columnDefinition = "text") var uploadStatus: String? = null
    var publishAt: OffsetDateTime? = null
    var publishedAt: OffsetDateTime? = null

    // Seconds
    var duration: Int? = null
    @Column(nullable = false) var live: Boolean = false
    @Column(name = "thumbnail_url", columnDefinition = "text") var thumbnailUrl: String? = null

    @Column(name = "episode_id") var episodeId: UUID? = null

    // The team said whose it is, or that it's no episode's, which the sync leaves
    @Column(name = "episode_manual", nullable = false) var episodeManual: Boolean = false

    // The episode's file MetaOS uploaded it from
    @Column(name = "media_id") var mediaId: UUID? = null

    // When it was found to be off the channel: deleted there, or moved
    var goneAt: OffsetDateTime? = null

    @Column(nullable = false) val createdAt: OffsetDateTime = now()

    val url get() = youtubeUrl(videoId)
    val studioUrl get() = youtubeStudioUrl(videoId)

    override fun equals(other: Any?) = this === other || (other is YouTubeVideo && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "YouTubeVideo(id=$id, videoId=$videoId, title=$title)"
}

interface YouTubeVideosRepository : JpaRepository<YouTubeVideo, UUID> {
    fun findByVideoId(videoId: String): YouTubeVideo?
    fun findByVideoIdIn(videoIds: Collection<String>): List<YouTubeVideo>
    fun findByChannelId(channelId: String): List<YouTubeVideo>
    fun findByMediaIdAndGoneAtIsNull(mediaId: UUID): List<YouTubeVideo>

    // The latest first, by when they were uploaded or went public; those that never said, last
    @Query("select v from YouTubeVideo v where v.episodeId in :episodeIds order by v.publishedAt desc nulls last, v.createdAt desc")
    fun findByEpisodeIds(episodeIds: Collection<UUID>): List<YouTubeVideo>

    @Query("select v from YouTubeVideo v order by v.publishedAt desc nulls last, v.createdAt desc")
    fun findLatestFirst(): List<YouTubeVideo>
}

/** What a look at the channel changed: how many videos it has, and how many were new, changed, or gone since the last */
data class YouTubeSyncSummary(val videos: Int, val added: Int, val changed: Int, val gone: Int)

/** Whether a look at the channel is on, since when, and which channel it is, where MetaOS has one */
data class YouTubeState(
    val connected: Boolean,
    val channelId: String?,
    val channel: String?,
    val handle: String?,
    val refreshing: Boolean,
    val refreshStartedAt: Instant?,
    // Why the channel couldn't be asked, when it couldn't
    val error: String? = null,
)

// Which of an episode's pictures a video's thumbnail may be: those made for YouTube
val YOUTUBE_THUMBNAILS = setOf(EpisodeImage.THUMBNAIL, EpisodeImage.THUMBNAIL_B, EpisodeImage.THUMBNAIL_C)

/**
 * The channel's videos, as MetaOS keeps them, for MCP (YouTubeMCP) and GraphQL (YouTubeGraphQL) alike: gathering them
 * from the channel ([sync], in its workflow), whose episode each is, and changing what one says about itself (its
 * title, description, tags and thumbnail), never who may see it. Reading them needs nothing; the rest needs the bot's
 * YouTube token (ConditionalOnYouTube).
 */
@Component
class YouTubeVideos(
    private val youtube: ObjectProvider<YouTube>,
    private val media: ObjectProvider<Media>,
    private val videos: YouTubeVideosRepository,
    private val episodes: EpisodesRepository,
    private val distributions: DistributionsRepository,
    private val images: ImagesRepository,
    transactionManager: PlatformTransactionManager,
) {

    private val transaction = TransactionTemplate(transactionManager)

    val connected: Boolean get() = youtube.ifAvailable != null

    fun youtube(): YouTube = checkNotNull(youtube.ifAvailable) {
        "MetaOS isn't connected to YouTube: it has no token of the bot's for the channel (GOOGLE_YOUTUBE_REFRESH_TOKEN, google/refresh_token.sh youtube)"
    }

    fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    /** A video by MetaOS's ID, YouTube's, or its address */
    fun find(ref: String): YouTubeVideo {
        val asked = ref.trim()
        val found = asked.toUuidOrNull()?.let(videos::findByIdOrNull) ?: youtubeVideoId(asked)?.let(videos::findByVideoId)
        return requireNotNull(found) {
            "MetaOS knows no YouTube video $asked: list_youtube_videos lists the channel's, and refresh_youtube_videos looks at the channel again"
        }
    }

    /**
     * The channel's videos, the latest first: of [episode] only, those with [privacy], those whose title or description
     * has every word of [query], and with those gone from the channel only when [gone]
     */
    fun list(episode: UUID?, privacy: YouTubePrivacy?, query: String?, unlinked: Boolean, gone: Boolean, limit: Int): List<YouTubeVideo> {
        val words = query?.let(::fold)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        val found = if (episode != null) videos.findByEpisodeIds(listOf(episode)) else videos.findLatestFirst()
        return found.asSequence()
            .filter { gone || it.goneAt == null }
            .filter { privacy == null || it.privacy == privacy }
            .filter { !unlinked || it.episodeId == null }
            .filter { video -> words.isEmpty() || fold("${video.title} ${video.description} ${video.tags.joinToString(" ")}").let { text -> words.all(text::contains) } }
            .take(limit).toList()
    }

    /** The videos of several episodes, in one query, the latest first, without those gone from the channel */
    fun of(episodes: Collection<Episode>): Map<Episode, List<YouTubeVideo>> {
        val byId = videos.findByEpisodeIds(episodes.mapNotNull { it.id }).filter { it.goneAt == null }.groupBy { it.episodeId }
        return episodes.associateWith { byId[it.id].orEmpty() }
    }

    // Whose each video is, by its ID: the episode whose YouTube link it is, else the one that has it among its posts
    private fun episodesByVideo(): Map<String, UUID> {
        val posted = distributions.findByChannel(Channel.YOUTUBE).mapNotNull { post -> youtubeVideoId(post.url)?.let { it to post.episodeId } }
        val linked = episodes.findByYoutubeUrlIsNotNull().mapNotNull { episode -> youtubeVideoId(episode.youtubeUrl!!)?.let { it to checkNotNull(episode.id) } }
        return (posted + linked).toMap()
    }

    // What YouTube says of a video, onto MetaOS's row of it; whether anything changed. Only what differs is set, a time
    // by its instant, so a video that's as it was isn't written again, and leaves no trace in the audit log.
    private fun apply(video: YouTubeVideo, info: YouTubeVideoInfo, episode: UUID?): Boolean {
        var changed = false
        fun <T> set(had: T, now: T, same: (T, T) -> Boolean = { a, b -> a == b }, to: (T) -> Unit) {
            if (!same(had, now)) {
                to(now)
                changed = true
            }
        }
        val sameTime = { a: OffsetDateTime?, b: OffsetDateTime? -> a?.toInstant() == b?.toInstant() }
        set(video.title, info.title) { video.title = it }
        set(video.description, info.description) { video.description = it }
        set(video.tags.toList(), info.tags) { video.tags = it.toTypedArray() }
        set(video.categoryId, info.categoryId) { video.categoryId = it }
        set(video.language, info.language) { video.language = it }
        set(video.privacy, info.privacy) { video.privacy = it }
        set(video.uploadStatus, info.uploadStatus) { video.uploadStatus = it }
        set(video.publishAt, info.publishAt?.kept(), sameTime) { video.publishAt = it }
        set(video.publishedAt, info.publishedAt?.kept(), sameTime) { video.publishedAt = it }
        // YouTube says no length while it processes one: the one it had stays
        set(video.duration, info.duration ?: video.duration) { video.duration = it }
        set(video.live, info.live) { video.live = it }
        set(video.thumbnailUrl, info.thumbnailUrl) { video.thumbnailUrl = it }
        // Its episode by its links, but one MetaOS uploaded, which knows its own, and one the team placed
        if (!video.episodeManual && video.mediaId == null) set(video.episodeId, episode) { video.episodeId = it }
        set(video.goneAt, null) { video.goneAt = it }
        return changed
    }

    /**
     * Keeps what YouTube says of some videos: new ones are added, those that changed are changed, and nothing's written
     * for the rest, so a look at the channel that finds nothing new leaves no trace. With [all], these are all the
     * channel has, and any other of MetaOS's is gone from it.
     */
    fun keep(found: List<YouTubeVideoInfo>, all: Boolean): YouTubeSyncSummary = checkNotNull(transaction.execute {
        val whose = episodesByVideo()
        val had = videos.findByVideoIdIn(found.map { it.id }).associateBy { it.videoId }
        var added = 0
        var changed = 0
        found.forEach { info ->
            val video = had[info.id]
            if (video == null) {
                videos.save(YouTubeVideo(info.id, info.channelId, info.title).also { apply(it, info, whose[info.id]) })
                added++
            } else if (apply(video, info, whose[info.id])) {
                changed++
            }
        }
        var gone = 0
        // Not when YouTube lists nothing at all, which a channel with videos doesn't
        if (all && found.isNotEmpty()) {
            val there = found.map { it.id }.toSet()
            found.map { it.channelId }.distinct().flatMap(videos::findByChannelId)
                .filter { it.videoId !in there && it.goneAt == null }
                .forEach {
                    it.goneAt = now()
                    gone++
                }
        }
        videos.flush()
        YouTubeSyncSummary(found.size, added, changed, gone)
    })

    /** Looks at the channel: every video it has, kept as it is now, and those it no longer has as gone */
    fun sync(): YouTubeSyncSummary {
        val youtube = youtube()
        val channel = youtube.channel()
        val found = youtube.videos(youtube.playlistVideoIds(channel.uploads)).filter { it.channelId == channel.id }
        return keep(found, all = true)
    }

    /** Looks at one video again, e.g. once MetaOS uploaded or changed it: [change] says what else is its, as its row is kept */
    fun look(videoId: String, change: (YouTubeVideo) -> Unit = {}): YouTubeVideo {
        val info = requireNotNull(youtube().videos(listOf(videoId)).singleOrNull()) { "The channel has no video $videoId" }
        return keep(info, change)
    }

    private fun keep(info: YouTubeVideoInfo, change: (YouTubeVideo) -> Unit): YouTubeVideo = checkNotNull(transaction.execute {
        val video = videos.findByVideoId(info.id) ?: YouTubeVideo(info.id, info.channelId, info.title)
        // Its own episode first, so what YouTube says doesn't place it by its links
        change(video)
        apply(video, info, episodesByVideo()[info.id])
        videos.saveAndFlush(video)
    })

    /**
     * Changes what a video says about itself on YouTube: its [title], [description] and [tags], those that are given,
     * the others as they are. Never who may see it.
     */
    fun update(ref: String, title: String?, description: String?, tags: List<String>?): YouTubeVideo {
        val video = find(ref)
        require(video.goneAt == null) { "\"${video.title}\" is no longer on the channel" }
        require(title != null || description != null || tags != null) { "Say what to change: its title, description or tags" }
        // What's left out stays as YouTube has it now, not as it was at the last look at the channel: a snippet is
        // replaced whole, and a title other than the video's stops an A/B test of titles that's on for it in Studio
        val now = youtube().videos(listOf(video.videoId)).firstOrNull { it.id == video.videoId }
        val details = YouTubeDetails(
            title = youtubeTitle(title ?: now?.title ?: video.title),
            description = youtubeDescription(description ?: now?.description ?: video.description),
            tags = youtubeTags(tags ?: now?.tags ?: video.tags.toList()),
        )
        return keep(youtube().update(video.videoId, details)) {}
    }

    /** Says whose a video is: [episode]'s, or, with none, no episode's; the sync leaves it so */
    @Transactional
    fun place(ref: String, episode: String?): YouTubeVideo {
        val video = find(ref)
        video.episodeId = episode?.trim()?.ifEmpty { null }?.let { findEpisode(it).id }
        video.episodeManual = true
        return videos.saveAndFlush(video)
    }

    /**
     * Gives a video one of its episode's pictures as its thumbnail: the picture's largest copy YouTube takes (2 MB),
     * from MetaOS's public bucket
     */
    fun setThumbnail(ref: String, kind: EpisodeImage, episode: String? = null): YouTubeVideo {
        val video = find(ref)
        require(video.goneAt == null) { "\"${video.title}\" is no longer on the channel" }
        require(kind in YOUTUBE_THUMBNAILS) { "A YouTube thumbnail is one of the episode's thumbnails: THUMBNAIL, THUMBNAIL_B or THUMBNAIL_C" }
        val of = episode?.let(::findEpisode) ?: requireNotNull(video.episodeId?.let(episodes::findByIdOrNull)) {
            "\"${video.title}\" is no episode's in MetaOS; say which episode's picture it gets"
        }
        val (contentType, picture) = thumbnail(of, kind)
        youtube().setThumbnail(video.videoId, contentType, picture)
        return look(video.videoId)
    }

    // The picture as it came when YouTube takes it, else its widest copy that it does
    private fun thumbnail(episode: Episode, kind: EpisodeImage): Pair<String, ByteArray> {
        val label = episode.code ?: episode.title
        val image = requireNotNull(episode.imageId(kind)?.let(images::findByIdOrNull)) { "$label has no ${kind.name.lowercase().replace('_', ' ')}; set it first" }
        val storage = checkNotNull(media.ifAvailable) { "MetaOS has no public bucket for pictures (METAOS_MEDIA_BUCKET)" }.storage
        val original = image.originalPath().takeIf { image.contentType in setOf("image/jpeg", "image/png") }?.let { it to image.contentType }
        val copies = image.widths.sortedDescending().map { image.path(it) to if (image.format == "png") "image/png" else "image/jpeg" }
        val (path, contentType) = requireNotNull(
            (listOfNotNull(original) + copies).firstOrNull { (path, _) -> storage.file(path)?.size?.let { it <= MAX_YOUTUBE_THUMBNAIL_BYTES } == true }
        ) { "$label's ${image.name} has no copy of 2 MB or less, which is what YouTube takes" }
        return contentType to storage.read(path, MAX_YOUTUBE_THUMBNAIL_BYTES)
    }
}

/** MetaOS's part of a look at the channel */
@ActivityInterface
interface YouTubeSyncActivities {
    fun syncChannel(): YouTubeSyncSummary
}

/**
 * A look at the channel: every video on it, kept as it is now. Every hour, and when the team asks. YouTube being busy
 * or out of quota is tried again a few times, then left for the next hour.
 */
@WorkflowInterface
interface YouTubeSyncWorkflow {
    @WorkflowMethod
    fun sync(): YouTubeSyncSummary
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class YouTubeSyncWorkflowImpl : YouTubeSyncWorkflow {

    private val activities = Workflow.newActivityStub(
        YouTubeSyncActivities::class.java,
        ActivityOptions.newBuilder()
            // A page of 50 a call, and their details 50 a call: a few seconds for hundreds of videos
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setRetryOptions(
                RetryOptions.newBuilder().setInitialInterval(Duration.ofMinutes(1)).setBackoffCoefficient(2.0).setMaximumAttempts(4)
                    .setDoNotRetry(YouTubeRefused::class.java.name, IllegalStateException::class.java.name).build()
            )
            .build(),
    )

    override fun sync(): YouTubeSyncSummary = activities.syncChannel()
}

/** What it writes is in the audit log as the workflows', with no one's name: what's on the channel isn't whoever looked's */
@Component
@ConditionalOnYouTube
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class YouTubeSyncActivitiesImpl(private val videos: YouTubeVideos) : YouTubeSyncActivities {
    override fun syncChannel(): YouTubeSyncSummary = actingFor(Actor(null, null)) { videos.sync() }
}

/**
 * Starts looks at the channel ([YouTubeSyncWorkflow]): every hour, from whichever pod asks first that hour (by the
 * hour in its workflow's ID), and when the team asks, while one isn't on already. Without Temporal or the bot's
 * YouTube token there's none.
 */
@Component
class YouTubeSyncs(private val client: ObjectProvider<WorkflowClient>, private val videos: YouTubeVideos) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 10, initialDelay = 4, timeUnit = TimeUnit.MINUTES)
    fun hourly() {
        val workflows = client.ifAvailable ?: return
        if (!videos.connected) return
        val hour = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").format(Instant.now().atOffset(ZoneOffset.UTC))
        try {
            start(workflows, "youtube-sync-$hour", WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        } catch (_: WorkflowExecutionAlreadyStarted) {
            // This hour's is on, or done
        } catch (e: RuntimeException) {
            log.warn("The look at the YouTube channel didn't start: {}", e.message)
        }
    }

    /** Starts a look at the channel, unless one's on, and answers with how it is */
    fun refresh(): YouTubeState {
        videos.youtube()
        val workflows = checkNotNull(client.ifAvailable) { "The channel can't be looked at here: MetaOS has no Temporal (TEMPORAL_ADDRESS)" }
        start(workflows, REFRESH_ID, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
        return state()
    }

    /** Which channel MetaOS has, and whether a look at it that the team asked for is on */
    fun state(): YouTubeState {
        val youtube = runCatching { videos.youtube() }.getOrNull() ?: return YouTubeState(false, null, null, null, false, null)
        val (channel, error) = try {
            youtube.channel() to null
        } catch (e: RuntimeException) {
            null to (e.message ?: "YouTube couldn't be asked")
        }
        val run = try {
            client.ifAvailable?.newUntypedWorkflowStub(REFRESH_ID)?.describe()
        } catch (_: WorkflowNotFoundException) {
            null
        }
        val running = run?.status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        return YouTubeState(channel != null, channel?.id, channel?.title, channel?.handle, running, run?.startTime.takeIf { running }, error)
    }

    private fun start(workflows: WorkflowClient, id: String, reuse: WorkflowIdReusePolicy) {
        val workflow = workflows.newWorkflowStub(
            YouTubeSyncWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(WORKFLOWS_QUEUE)
                .setWorkflowId(id)
                .setWorkflowIdReusePolicy(reuse)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .setWorkflowExecutionTimeout(Duration.ofHours(1))
                .build(),
        )
        WorkflowClient.start(workflow::sync)
        log.info("YouTube sync {} started", id)
    }

    private companion object {
        const val REFRESH_ID = "youtube-refresh"
    }
}
