package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** One of an episode's video or audio files as MCP clients get it, with a link that downloads it for an hour */
data class MediaFileDto(
    val id: UUID?,
    val kind: MediaKind,
    val title: String?,
    val name: String,
    val contentType: String,
    // Bytes
    val size: Long,
    val md5: String?,
    // Their person's name, or else their username
    val uploadedBy: String,
    val uploadedAt: OffsetDateTime,
    val info: MediaInfo,
    // None for one that's been removed
    val downloadUrl: String?,
)

private const val MEDIA_ID = "The file's ID, from list_episode_media"

/**
 * An episode's video and audio (MediaFiles.kt). They're uploaded from the episode's page, as MCP can't send gigabytes;
 * these list them, with links an agent can hand on, e.g. to patreon-gateway, and change or remove them. The tools that
 * write are transactions of their own, so the audit log has the tool's name (AuditingTransactionManager).
 */
@Component
@ConditionalOnStorage
class MediaFilesMCP(
    private val mediaFiles: MediaFiles,
    private val episodes: EpisodesRepository,
    private val accounts: Accounts,
) {

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun toDtos(files: List<MediaFile>, links: Boolean = true): List<MediaFileDto> {
        val people = accounts.people(files.map { it.uploaderId })
        return files.map {
            MediaFileDto(
                it.id, it.kind, it.title, it.name, it.contentType, it.size, it.md5,
                people[it.uploaderId]?.name ?: it.uploader ?: "Someone", it.uploadedAt, it.info,
                if (links) mediaFiles.downloadUrl(it).toString() else null,
            )
        }
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_episode_media",
        annotations = McpTool.McpAnnotations(
            title = "List episode media",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List an episode's video and audio, the first uploaded first: its full video and full audio, one " +
            "each, and its bonuses as video and audio, any number, each with a title. With what's in each, as ffprobe " +
            "read it (duration, container, video codec, size and frame rate, audio codec, sample rate and channels, ...), " +
            "and a link that downloads it for an hour, e.g. for set_patreon_post_media. The team uploads them on the " +
            "episode's page (Upload video and audio)"
    )
    fun listEpisodeMedia(@McpToolParam(description = EPISODE_REF) code: String): List<MediaFileDto> {
        val episode = findEpisode(code)
        return toDtos(mediaFiles.of(listOf(episode)).getValue(episode))
    }

    @Transactional
    @McpTool(
        name = "update_episode_media",
        annotations = McpTool.McpAnnotations(
            title = "Update episode media",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Change one of an episode's video or audio files: its title (an empty one clears it), or what it " +
            "is, e.g. a bonus video that's the full video. Video stays video and audio audio, and a file becomes the " +
            "full video or full audio only while the episode has none. What's left out stays as it is"
    )
    fun updateEpisodeMedia(
        @McpToolParam(description = MEDIA_ID) id: UUID,
        @McpToolParam(description = "Its title, e.g. what a bonus is about; empty to clear it", required = false) title: String?,
        @McpToolParam(description = "What it is", required = false) kind: MediaKind?,
    ): MediaFileDto = toDtos(listOf(mediaFiles.update(id, title, kind))).single()

    @Transactional
    @McpTool(
        name = "remove_episode_media",
        annotations = McpTool.McpAnnotations(
            title = "Remove episode media",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Take a video or audio file off its episode and delete it from MetaOS's bucket; production's keeps " +
            "deleted files 30 days, where only an administrator gets them back. The history keeps that it was there. " +
            "Returns the file it was, without a link"
    )
    fun removeEpisodeMedia(@McpToolParam(description = MEDIA_ID) id: UUID): MediaFileDto =
        toDtos(listOf(mediaFiles.remove(id)), links = false).single()
}
