package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** One of the channel's videos as MCP clients get it */
data class YouTubeVideoDto(
    val id: UUID?,
    // YouTube's ID, where it's watched (by those who may) and where its owner works on it and publishes it
    val videoId: String,
    val url: String,
    val studioUrl: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val privacy: YouTubePrivacy,
    // YouTube's: uploaded (and being processed), processed, failed, rejected or deleted
    val uploadStatus: String?,
    // When a private one is scheduled to go public
    val publishAt: OffsetDateTime?,
    val publishedAt: OffsetDateTime?,
    // Seconds
    val duration: Int?,
    val live: Boolean,
    val thumbnailUrl: String?,
    // Whose it is in MetaOS, if anyone's, and the episode's file MetaOS uploaded it from, if it did
    val episodeId: UUID?,
    val episode: String?,
    val mediaId: UUID?,
    // When it was found to be off the channel
    val goneAt: OffsetDateTime?,
)

private const val VIDEO_REF = "The video: its ID in MetaOS or on YouTube, or its YouTube address"

/**
 * Ogrodje's YouTube channel over MCP (YouTubeVideos.kt and YouTubeUploads.kt, as YouTubeGraphQL has them): what's on
 * it, sending an episode's video there as a private one, and changing what a video says about itself. Never who may
 * see a video, and never deleting one: a person publishes, in YouTube Studio.
 */
@Component
class YouTubeMCP(
    private val videos: YouTubeVideos,
    private val uploads: YouTubeUploads,
    private val syncs: YouTubeSyncs,
    private val episodes: EpisodesRepository,
) {

    private fun toDtos(of: List<YouTubeVideo>): List<YouTubeVideoDto> {
        val byId = episodes.findAllById(of.mapNotNull { it.episodeId }.toSet()).associateBy { it.id }
        return of.map { video ->
            YouTubeVideoDto(
                video.id, video.videoId, video.url, video.studioUrl, video.title, video.description, video.tags.toList(),
                video.privacy, video.uploadStatus, video.publishAt, video.publishedAt, video.duration, video.live, video.thumbnailUrl,
                video.episodeId, byId[video.episodeId]?.let { it.code ?: it.title }, video.mediaId, video.goneAt,
            )
        }
    }

    private fun privacy(asked: String?): YouTubePrivacy? = asked?.trim()?.ifEmpty { null }?.let { name ->
        requireNotNull(YouTubePrivacy.entries.find { it.name.equals(name, ignoreCase = true) }) { "A video is public, unlisted or private, not $name" }
    }

    private fun picture(asked: String?): EpisodeImage? = asked?.trim()?.ifEmpty { null }?.let { name ->
        requireNotNull(YOUTUBE_THUMBNAILS.find { it.name.equals(name, ignoreCase = true) }) { "A thumbnail is THUMBNAIL, THUMBNAIL_B or THUMBNAIL_C, not $name" }
    }

    @McpTool(
        name = "get_youtube_channel",
        annotations = McpTool.McpAnnotations(
            title = "Get YouTube channel",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Which YouTube channel MetaOS is connected to, as Ogrodje's bot account: its ID, name and handle, " +
            "whether the connection works (and why not, when it doesn't), and whether a look at the channel that someone " +
            "asked for (refresh_youtube_videos) is on"
    )
    fun getYouTubeChannel(): YouTubeState = syncs.state()

    @McpTool(
        name = "list_youtube_videos",
        annotations = McpTool.McpAnnotations(
            title = "List YouTube videos",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "The videos on Ogrodje's YouTube channel, as MetaOS knows them, the latest first: full episodes, " +
            "clips, Shorts and streams, private and unlisted ones too. Each with its title, description, tags, who may " +
            "see it (PUBLIC, UNLISTED or PRIVATE), how YouTube has it (uploadStatus), its length, its address and its " +
            "page in YouTube Studio, and whose it is in MetaOS: the episode whose YouTube link or post it is, or that it " +
            "was uploaded from. MetaOS looks at the channel every hour; refresh_youtube_videos looks now"
    )
    fun listYouTubeVideos(
        @McpToolParam(description = "Only this episode's: $EPISODE_REF", required = false) code: String?,
        @McpToolParam(description = "Only those that are: public, unlisted or private", required = false) privacy: String?,
        @McpToolParam(description = "Only those with every one of these words in their title, description or tags", required = false) query: String?,
        @McpToolParam(description = "Only those that are no episode's in MetaOS; false by default", required = false) unlinked: Boolean?,
        @McpToolParam(description = "Also those that are no longer on the channel; false by default", required = false) gone: Boolean?,
        @McpToolParam(description = "At most this many, up to 500; 50 by default", required = false) limit: Int?,
    ): List<YouTubeVideoDto> = toDtos(
        videos.list(code?.let { videos.findEpisode(it).id }, privacy(privacy), query, unlinked == true, gone == true, (limit ?: 50).coerceIn(1, 500))
    )

    @McpTool(
        name = "get_youtube_video",
        annotations = McpTool.McpAnnotations(
            title = "Get YouTube video",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "One of the channel's videos, as MetaOS knows it: what list_youtube_videos says of each"
    )
    fun getYouTubeVideo(@McpToolParam(description = VIDEO_REF) video: String): YouTubeVideoDto = toDtos(listOf(videos.find(video))).single()

    @McpTool(
        name = "refresh_youtube_videos",
        annotations = McpTool.McpAnnotations(
            title = "Refresh YouTube videos",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Look at the YouTube channel now, in a workflow, rather than at the next hour: every video on it " +
            "is kept as it is there, new ones are added, and those no longer there are marked gone. It takes seconds; " +
            "get_youtube_channel says whether it's still on, and list_youtube_videos has what it found"
    )
    fun refreshYouTubeVideos(): YouTubeState = syncs.refresh()

    @McpTool(
        name = "upload_youtube_video",
        annotations = McpTool.McpAnnotations(
            title = "Upload YouTube video",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Send one of an episode's video files in MetaOS (list_episode_media) to Ogrodje's YouTube " +
            "channel, in a durable workflow on Temporal: by default its full video; a bonus or a clip by its file's ID. " +
            "Always as a PRIVATE video: a person publishes or schedules it in YouTube Studio, MetaOS never does. With a " +
            "title (by default the episode's; up to 100 characters), a description (by default the episode's summary; up " +
            "to 5,000 bytes; chapters go here, as lines of 0:00 Title), tags, and one of the episode's thumbnails (the " +
            "full video gets its thumbnail by default). An episode's video takes a while, one upload at a time; it " +
            "answers at once with the upload, which get_youtube_upload follows: QUEUED, UPLOADING (with how much is " +
            "sent), PROCESSING (it's on YouTube, with its videoId and its page in Studio), DONE, FAILED or CANCELLED. A " +
            "file that MetaOS sent already isn't sent again, unless again is set. YouTube's A/B tests of titles and " +
            "thumbnails are set up in YouTube Studio alone, not through its API: the video goes up with one title and " +
            "one thumbnail (A, by default), and a person adds the episode's titles and thumbnails B and C to the test there"
    )
    fun uploadYouTubeVideo(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "The file's ID, from list_episode_media; by default the episode's full video", required = false) media: UUID?,
        @McpToolParam(description = "The video's title on YouTube, up to 100 characters; by default the episode's", required = false) title: String?,
        @McpToolParam(description = "Its description on YouTube, plain text, up to 5,000 bytes, without < and >; by default the episode's summary", required = false) description: String?,
        @McpToolParam(description = "Its tags, up to 500 characters in all", required = false) tags: List<String>?,
        @McpToolParam(description = "Which of the episode's pictures is its thumbnail: THUMBNAIL, THUMBNAIL_B, THUMBNAIL_C, or NONE; by default THUMBNAIL for the full video, NONE for any other file", required = false) thumbnail: String?,
        @McpToolParam(description = "Send a file that MetaOS has sent to YouTube before; false by default", required = false) again: Boolean?,
    ): YouTubeUploadDto {
        val none = thumbnail?.trim().equals("NONE", ignoreCase = true)
        val started = uploads.start(code, media, title, description, tags, if (none) null else picture(thumbnail), none, again == true)
        return uploads.dtos(listOf(started)).single()
    }

    @McpTool(
        name = "list_youtube_uploads",
        annotations = McpTool.McpAnnotations(
            title = "List YouTube uploads",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Uploads of episodes' videos to YouTube, the latest first: each with its episode and file, what it " +
            "was sent as, and how far it is: QUEUED (waiting for its turn, with its place in line), UPLOADING (with how " +
            "much of the file is sent), PROCESSING (on YouTube, which is processing it), DONE, FAILED (with why) or " +
            "CANCELLED; and the video it made, with its page in YouTube Studio, where a person publishes it"
    )
    fun listYouTubeUploads(
        @McpToolParam(description = "Which: on (queued, uploading or processing), done, failed, cancelled or all; all by default", required = false) status: String?,
        @McpToolParam(description = "Only those of this episode: $EPISODE_REF", required = false) code: String?,
        @McpToolParam(description = "At most this many, up to 500; 20 by default", required = false) limit: Int?,
    ): List<YouTubeUploadDto> {
        val statuses = when (status?.trim()?.lowercase()) {
            null, "", "all" -> YouTubeUploadStatus.entries
            "on" -> YouTubeUploadStatus.ON
            else -> listOf(
                requireNotNull(YouTubeUploadStatus.entries.find { it.name.equals(status.trim(), ignoreCase = true) }) {
                    "An upload is on, done, failed or cancelled, not $status"
                }
            )
        }
        return uploads.dtos(uploads.list(statuses, code?.let { videos.findEpisode(it).id }, (limit ?: 20).coerceIn(1, 500)))
    }

    @McpTool(
        name = "get_youtube_upload",
        annotations = McpTool.McpAnnotations(
            title = "Get YouTube upload",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "One upload to YouTube by its ID, from upload_youtube_video or list_youtube_uploads, as it is now, " +
            "to watch it. An episode's video takes a while to send, so asking every few minutes is enough"
    )
    fun getYouTubeUpload(@McpToolParam(description = "The upload's ID, from upload_youtube_video or list_youtube_uploads") id: UUID): YouTubeUploadDto =
        uploads.dtos(listOf(uploads.get(id))).single()

    @McpTool(
        name = "cancel_youtube_upload",
        annotations = McpTool.McpAnnotations(
            title = "Cancel YouTube upload",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Stop an upload to YouTube that's still on. One that's waiting or being sent leaves nothing on " +
            "YouTube; one that YouTube is already processing leaves its video there, private, which only a person removes, " +
            "in YouTube Studio"
    )
    fun cancelYouTubeUpload(@McpToolParam(description = "The upload's ID, from upload_youtube_video or list_youtube_uploads") id: UUID): YouTubeUploadDto =
        uploads.dtos(listOf(uploads.cancel(id))).single()

    @McpTool(
        name = "update_youtube_video",
        annotations = McpTool.McpAnnotations(
            title = "Update YouTube video",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Change what one of the channel's videos says about itself on YouTube: its title, description " +
            "(e.g. to add chapters, as lines of 0:00 Title) or tags, each replaced whole, those left out staying as they " +
            "are; on a public video, everyone sees the change at once. And whose it is in MetaOS (code), which the " +
            "hourly look at the channel then leaves. It never changes who may see the video, and never deletes it. " +
            "Changing the title of a video that has an A/B test of titles on in YouTube Studio stops the test, which " +
            "the API can't say is on: ask before changing a title that may be tested; its description and tags don't stop it"
    )
    fun updateYouTubeVideo(
        @McpToolParam(description = VIDEO_REF) video: String,
        @McpToolParam(description = "Its new title, up to 100 characters", required = false) title: String?,
        @McpToolParam(description = "Its new description, plain text, up to 5,000 bytes, without < and >", required = false) description: String?,
        @McpToolParam(description = "Its new tags, in place of those it has; an empty list clears them", required = false) tags: List<String>?,
        @McpToolParam(description = "The episode it is in MetaOS: $EPISODE_REF; empty for no episode's", required = false) code: String?,
    ): YouTubeVideoDto {
        require(title != null || description != null || tags != null || code != null) { "Say what to change: its title, description, tags or episode" }
        if (code != null) videos.place(video, code)
        val changed = if (title != null || description != null || tags != null) videos.update(video, title, description, tags) else videos.find(video)
        return toDtos(listOf(changed)).single()
    }

    @McpTool(
        name = "set_youtube_video_thumbnail",
        annotations = McpTool.McpAnnotations(
            title = "Set YouTube video thumbnail",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Give one of the channel's videos one of its episode's pictures in MetaOS as its thumbnail on " +
            "YouTube: its thumbnail, or thumbnail B or C, the ones its titles are A/B tested with (list_episode_images). " +
            "On a public video, everyone sees it at once. YouTube takes custom thumbnails only on a verified channel. " +
            "Changing the thumbnail of a video that has an A/B test of thumbnails on in YouTube Studio stops the test, " +
            "which the API can't say is on, and can't start either: ask before changing one that may be tested"
    )
    fun setYouTubeVideoThumbnail(
        @McpToolParam(description = VIDEO_REF) video: String,
        @McpToolParam(description = "Which of the episode's pictures: THUMBNAIL (the default), THUMBNAIL_B or THUMBNAIL_C", required = false) picture: String?,
        @McpToolParam(description = "The episode whose picture it is, when the video is no episode's in MetaOS, or another's: $EPISODE_REF", required = false) code: String?,
    ): YouTubeVideoDto = toDtos(listOf(videos.setThumbnail(video, picture(picture) ?: EpisodeImage.THUMBNAIL, code))).single()
}
