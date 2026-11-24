package si.ogrodje.metaos

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.UUID

// The YouTube channel over GraphQL, for MetaOS's pages, as YouTubeMCP has it over MCP (YouTubeVideos.kt,
// YouTubeUploads.kt). It's the team's: an episode's videos and uploads are empty for anyone else, as private and
// unlisted videos are among them. Lists may ask for their episodes and who asked for them, so those are batched.
@Controller
class YouTubeGraphQL(
    private val videos: YouTubeVideos,
    private val uploads: YouTubeUploads,
    private val syncs: YouTubeSyncs,
    private val catalogue: YouTubeVideosRepository,
    private val episodes: EpisodesRepository,
    private val transcriptions: Transcriptions,
) {

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun youtube(): YouTubeState = syncs.state()

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun youtubeVideos(@Argument privacy: YouTubePrivacy?, @Argument query: String?, @Argument unlinked: Boolean, @Argument limit: Int): List<YouTubeVideo> =
        videos.list(null, privacy, query, unlinked, gone = false, limit = limit.coerceIn(1, 500))

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun youtubeUploads(@Argument statuses: List<YouTubeUploadStatus>?, @Argument limit: Int): List<YouTubeUpload> =
        uploads.list(statuses ?: YouTubeUploadStatus.entries, null, limit.coerceIn(1, 500))

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun youtubeUpload(@Argument id: UUID): YouTubeUpload? =
        try {
            uploads.get(id)
        } catch (_: IllegalArgumentException) {
            null
        }

    @BatchMapping(typeName = "Episode", field = "youtubeVideos")
    fun episodeVideos(of: List<Episode>): Map<Episode, List<YouTubeVideo>> =
        if (signedIn()) videos.of(of) else of.associateWith { emptyList() }

    @BatchMapping(typeName = "Episode", field = "youtubeUploads")
    fun episodeUploads(of: List<Episode>): Map<Episode, List<YouTubeUpload>> =
        if (signedIn()) uploads.of(of) else of.associateWith { emptyList() }

    private fun episodesOf(ids: Collection<UUID>) = episodes.findAllById(ids.toSet()).associateBy { it.id }

    @BatchMapping(typeName = "YouTubeVideo", field = "episode")
    fun videoEpisodes(of: List<YouTubeVideo>): Map<YouTubeVideo, Episode> {
        val byId = episodesOf(of.mapNotNull { it.episodeId })
        return of.mapNotNull { video -> byId[video.episodeId]?.let { video to it } }.toMap()
    }

    @SchemaMapping(typeName = "YouTubeVideo")
    fun tags(video: YouTubeVideo): List<String> = video.tags.toList()

    @BatchMapping(typeName = "YouTubeUpload", field = "episode")
    fun uploadEpisodes(of: List<YouTubeUpload>): Map<YouTubeUpload, Episode> {
        val byId = episodesOf(of.map { it.episodeId })
        return of.mapNotNull { job -> byId[job.episodeId]?.let { job to it } }.toMap()
    }

    @SchemaMapping(typeName = "YouTubeUpload")
    fun tags(upload: YouTubeUpload): List<String> = upload.tags.toList()

    @BatchMapping(typeName = "YouTubeUpload", field = "queued")
    fun queued(of: List<YouTubeUpload>): Map<YouTubeUpload, Int> {
        val places = uploads.places(of)
        return of.mapNotNull { job -> places[job.id]?.let { job to it } }.toMap()
    }

    @BatchMapping(typeName = "YouTubeUpload", field = "video")
    fun made(of: List<YouTubeUpload>): Map<YouTubeUpload, YouTubeVideo> {
        val byId = catalogue.findByVideoIdIn(of.mapNotNull { it.videoId }.toSet()).associateBy { it.videoId }
        return of.mapNotNull { job -> byId[job.videoId]?.let { job to it } }.toMap()
    }

    @BatchMapping(typeName = "YouTubeUpload", field = "requestedBy")
    fun requesters(of: List<YouTubeUpload>): Map<YouTubeUpload, String> {
        val names = transcriptions.requesters(of.map { it.requesterId to it.requester })
        return of.mapNotNull { job -> (job.requesterId?.let { names[it] } ?: job.requester)?.let { job to it } }.toMap()
    }

    @MutationMapping
    @PreAuthorize(WRITES)
    fun refreshYouTubeVideos(): YouTubeState = syncs.refresh()

    // thumbnail: one of the episode's, or none with noThumbnail; neither is the default, the full video's own
    @MutationMapping
    @PreAuthorize(WRITES)
    fun uploadToYouTube(
        @Argument ref: String,
        @Argument media: UUID?,
        @Argument title: String?,
        @Argument description: String?,
        @Argument tags: List<String>?,
        @Argument thumbnail: EpisodeImage?,
        @Argument noThumbnail: Boolean,
        @Argument again: Boolean,
    ): YouTubeUpload = uploads.start(ref, media, title, description, tags, thumbnail, noThumbnail, again)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun cancelYouTubeUpload(@Argument id: UUID): YouTubeUpload = uploads.cancel(id)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun updateYouTubeVideo(@Argument id: String, @Argument title: String?, @Argument description: String?, @Argument tags: List<String>?): YouTubeVideo =
        videos.update(id, title, description, tags)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun setYouTubeVideoEpisode(@Argument id: String, @Argument episode: String?): YouTubeVideo = videos.place(id, episode)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun setYouTubeVideoThumbnail(@Argument id: String, @Argument picture: EpisodeImage, @Argument episode: String?): YouTubeVideo =
        videos.setThumbnail(id, picture, episode)
}
