package si.ogrodje.metaos

import org.springframework.beans.factory.ObjectProvider
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.time.OffsetDateTime
import java.util.UUID

/** A distribution's preview, as GraphQL has it; its picture by ID, batched */
data class DistributionPreviewGql(
    val distributionId: UUID,
    val status: PreviewStatus,
    val title: String?,
    val description: String?,
    val site: String?,
    val publishedAt: OffsetDateTime?,
    val imageId: UUID?,
    val error: String?,
    val fetchedAt: OffsetDateTime?,
)

// Where episodes went out (Distributions.kt) is the team's, as their history is, so anyone else gets none. Lists may ask
// for it too, so it's batched, as are who added each, their episodes and their pictures.
@Controller
class DistributionsGraphQL(
    private val distributions: Distributions,
    private val episodes: EpisodesRepository,
    private val images: ObjectProvider<Images>,
    private val imagesRepository: ImagesRepository,
) {

    @BatchMapping(typeName = "Episode", field = "distributions")
    fun episodeDistributions(episodes: List<Episode>): Map<Episode, List<Distribution>> =
        if (signedIn()) distributions.onEpisodes(episodes) else episodes.associateWith { emptyList() }

    @QueryMapping("distributions")
    @PreAuthorize(SIGNED_IN)
    fun latest(@Argument channel: Channel?, @Argument since: String?, @Argument limit: Int): List<Distribution> =
        distributions.latest(channel, since?.trim()?.ifEmpty { null }?.let(::postedAt), limit)

    @BatchMapping(typeName = "Distribution", field = "episode")
    fun episodes(of: List<Distribution>): Map<Distribution, Episode> {
        val byId = episodes.findAllById(of.map { it.episodeId }.toSet()).associateBy { it.id }
        return of.mapNotNull { entry -> byId[entry.episodeId]?.let { entry to it } }.toMap()
    }

    @BatchMapping(typeName = "Distribution", field = "addedBy")
    fun addedBy(of: List<Distribution>): Map<Distribution, String> {
        val people = distributions.people(of)
        return of.mapNotNull { entry -> (entry.addedById?.let(people::get)?.name ?: entry.addedBy)?.let { entry to it } }.toMap()
    }

    @SchemaMapping(typeName = "Distribution")
    fun preview(entry: Distribution) = DistributionPreviewGql(
        checkNotNull(entry.id), entry.previewStatus, entry.previewTitle, entry.previewDescription, entry.previewSite,
        entry.previewPublishedAt, entry.previewImageId, entry.previewError, entry.previewedAt,
    )

    @BatchMapping(typeName = "DistributionPreview", field = "image")
    fun pictures(of: List<DistributionPreviewGql>): Map<DistributionPreviewGql, ImageDto> {
        val images = images.ifAvailable ?: return emptyMap()
        val byId = imagesRepository.findAllById(of.mapNotNull { it.imageId }.toSet()).associateBy { it.id }
        return of.mapNotNull { preview -> preview.imageId?.let(byId::get)?.let { preview to images.toDto(it) } }.toMap()
    }

    @MutationMapping
    @PreAuthorize(WRITES)
    fun addDistribution(
        @Argument ref: String,
        @Argument url: String,
        @Argument channel: Channel?,
        @Argument postedAt: String?,
        @Argument note: String?,
        @Argument title: String?,
        @Argument link: Platform?,
    ): Distribution = distributions.add(distributions.findEpisode(ref), url, channel, postedAt, note, title, link)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun updateDistribution(
        @Argument id: UUID,
        @Argument url: String?,
        @Argument channel: Channel?,
        @Argument postedAt: String?,
        @Argument note: String?,
        @Argument title: String?,
    ): Distribution = distributions.update(id, url, channel, postedAt, note, title)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun removeDistribution(@Argument id: UUID): UUID = checkNotNull(distributions.remove(id).id)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun refreshDistributionPreview(@Argument id: UUID): Distribution = distributions.refresh(id)
}
