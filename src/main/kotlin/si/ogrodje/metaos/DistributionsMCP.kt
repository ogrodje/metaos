package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** What a distribution's page says about itself, as MetaOS fetched it, and its picture in the public bucket */
data class DistributionPreviewDto(
    val status: PreviewStatus,
    val title: String?,
    val description: String?,
    val site: String?,
    val publishedAt: OffsetDateTime?,
    val image: String?,
    // Why there's none
    val error: String?,
    val fetchedAt: OffsetDateTime?,
)

/** Somewhere an episode went out, as MCP clients get it */
data class DistributionDto(
    val id: UUID?,
    val episodeId: UUID,
    val episode: String?,
    // The episode's link this is, e.g. YOUTUBE; null for a post
    val platform: Platform?,
    val channel: Channel,
    val url: String,
    // What someone called it, else its page's title
    val title: String?,
    val note: String?,
    // When it went out, as someone said, else as its page says
    val postedAt: OffsetDateTime?,
    // Their person's name, or else their username
    val addedBy: String?,
    val createdAt: OffsetDateTime,
    val preview: DistributionPreviewDto,
)

private const val POSTED_AT =
    "When it went out: a day, e.g. 2026-11-10, or a date and time, e.g. 2026-11-10T09:30:00+01:00; empty to clear"

/**
 * Where episodes went out once they were released (Distributions.kt): their links, and posts that promote them. The
 * tools that write are transactions of their own, so the audit log has the tool's name (AuditingTransactionManager).
 */
@Component
class DistributionsMCP(
    private val distributions: Distributions,
    private val episodes: EpisodesRepository,
    private val images: ObjectProvider<Images>,
    private val imagesRepository: ImagesRepository,
) {

    private fun dtos(of: List<Distribution>): List<DistributionDto> {
        val people = distributions.people(of)
        val codes = episodes.findAllById(of.map { it.episodeId }.toSet()).associate { it.id to it.label }
        val pictures = images.ifAvailable?.let { images ->
            imagesRepository.findAllById(of.mapNotNull { it.previewImageId }.toSet()).associate { it.id to images.toDto(it).url }
        }.orEmpty()
        return of.map {
            DistributionDto(
                it.id, it.episodeId, codes[it.episodeId], it.platform, it.channel, it.url, it.title ?: it.previewTitle,
                it.note, it.postedAt ?: it.previewPublishedAt, it.addedById?.let(people::get)?.name ?: it.addedBy, it.createdAt,
                DistributionPreviewDto(
                    it.previewStatus, it.previewTitle, it.previewDescription, it.previewSite, it.previewPublishedAt,
                    it.previewImageId?.let(pictures::get), it.previewError, it.previewedAt,
                ),
            )
        }
    }

    private fun dto(entry: Distribution) = dtos(listOf(entry)).single()

    @McpTool(
        name = "list_episode_distributions",
        annotations = McpTool.McpAnnotations(
            title = "List episode distributions",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List where an episode went out: first its own links (platform set, e.g. YOUTUBE, as " +
            "set_episode_link sets them), then the posts that promote it, e.g. on Discord, LinkedIn or Reddit, the " +
            "latest first. Each with its channel, URL, title, note, when it went out, who added it, and its page's " +
            "preview (OpenGraph: title, description, site, picture), or why there's none, e.g. a page behind a login"
    )
    fun listEpisodeDistributions(@McpToolParam(description = EPISODE_REF) code: String): List<DistributionDto> =
        dtos(distributions.of(distributions.findEpisode(code)))

    @McpTool(
        name = "list_distributions",
        annotations = McpTool.McpAnnotations(
            title = "List distributions",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List where episodes went out, of every episode, the latest first by when they went out, e.g. " +
            "what was posted this week, or which episodes are on LinkedIn; as list_episode_distributions has them"
    )
    fun listDistributions(
        @McpToolParam(description = "Only those on this channel, e.g. LINKEDIN", required = false) channel: Channel?,
        @McpToolParam(description = "Only those that went out since, e.g. 2026-11-01", required = false) since: String?,
        @McpToolParam(description = "At most this many, up to 500; 100 without it", required = false) limit: Int?,
    ): List<DistributionDto> =
        dtos(distributions.latest(channel, since?.trim()?.ifEmpty { null }?.let(::postedAt), limit ?: 100))

    @Transactional
    @McpTool(
        name = "add_episode_distribution",
        annotations = McpTool.McpAnnotations(
            title = "Add episode distribution",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Log where an episode went out, by its URL, e.g. a post on Discord, LinkedIn, Facebook, Reddit or " +
            "Monitor that promotes it. Its channel is told by the URL's host, unless given. MetaOS then fetches the " +
            "page's preview (OpenGraph) and copies its picture to the public bucket, which list_episode_distributions " +
            "shows once it's there. A URL the episode has already, also as one of its links, is that entry, with what's " +
            "given changed. With link, the URL becomes that one of the episode's links, as set_episode_link does"
    )
    fun addEpisodeDistribution(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "https URL of the post or page") url: String,
        @McpToolParam(description = "Where it is, e.g. MASTODON, which no host tells; by the URL's host without it", required = false)
        channel: Channel?,
        @McpToolParam(description = POSTED_AT, required = false) postedAt: String?,
        @McpToolParam(description = "A note about it, e.g. who posted it where, or that it was boosted; at most $MAX_DISTRIBUTION_NOTE_LENGTH characters", required = false)
        note: String?,
        @McpToolParam(description = "What to call it, when its page doesn't say, e.g. one behind a login", required = false)
        title: String?,
        @McpToolParam(description = "Make it this one of the episode's links, e.g. YOUTUBE or PATREON_AUDIO, as set_episode_link does", required = false)
        link: Platform?,
    ): DistributionDto = dto(distributions.add(distributions.findEpisode(code), url, channel, postedAt, note, title, link))

    @Transactional
    @McpTool(
        name = "update_episode_distribution",
        annotations = McpTool.McpAnnotations(
            title = "Update episode distribution",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Change where an episode went out: its URL (whose preview is fetched again), channel, note, " +
            "title or when it went out. Omitted fields are left as they are. One of the episode's links changes the " +
            "link too, and keeps its platform's channel"
    )
    fun updateEpisodeDistribution(
        @McpToolParam(description = "Distribution ID") distributionId: UUID,
        @McpToolParam(description = "Its new https URL", required = false) url: String?,
        @McpToolParam(description = "Where it is, e.g. MASTODON", required = false) channel: Channel?,
        @McpToolParam(description = POSTED_AT, required = false) postedAt: String?,
        @McpToolParam(description = "A note about it; empty to clear", required = false) note: String?,
        @McpToolParam(description = "What to call it; empty for its page's title", required = false) title: String?,
    ): DistributionDto = dto(distributions.update(distributionId, url, channel, postedAt, note, title))

    @Transactional
    @McpTool(
        name = "remove_episode_distribution",
        annotations = McpTool.McpAnnotations(
            title = "Remove episode distribution",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Take where an episode went out off it; one of its links is cleared on the episode too, as " +
            "set_episode_link without a URL does. The history keeps that it was there"
    )
    fun removeEpisodeDistribution(@McpToolParam(description = "Distribution ID") distributionId: UUID): DistributionDto =
        dto(distributions.remove(distributionId))

    @Transactional
    @McpTool(
        name = "refresh_distribution_preview",
        annotations = McpTool.McpAnnotations(
            title = "Refresh distribution preview",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Fetch the preview of where an episode went out again, e.g. after the post changed, or when its " +
            "page didn't let MetaOS in before. It keeps the one it had until the new one is there"
    )
    fun refreshDistributionPreview(@McpToolParam(description = "Distribution ID") distributionId: UUID): DistributionDto =
        dto(distributions.refresh(distributionId))
}
