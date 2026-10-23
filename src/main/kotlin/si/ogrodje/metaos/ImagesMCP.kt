package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** One of an episode's pictures; none when it doesn't have that one */
data class EpisodeImageDto(val kind: EpisodeImage, val image: ImageDto?)

private const val IMAGE_FROM = "Where the picture is: DROPBOX or KOOFR, with place and path, or URL, with url"
private const val IMAGE_PLACE = "For DROPBOX or KOOFR: a folder shared with Ogrodje's bot there, by name or ID " +
    "(list_dropbox_places, list_koofr_places)"
private const val IMAGE_PATH = "For DROPBOX or KOOFR: the picture's path in the place, e.g. /Season 5/S05E07 - vres gams/ogrodje-vresGams-thumb1.png"
private const val IMAGE_URL = "For URL: an https URL of a picture in Hygraph, the CMS (*.graphassets.com)"
private const val IMAGE_NAME = "For URL: the picture's file name, e.g. Hygraph's fileName, as its URL has none; the URL's last part when omitted"

private const val PUBLISHED = "MetaOS copies it to its public bucket, where anyone can see it, with copies of it 320, " +
    "640, 1280 and 1920 px wide (those narrower than it), and returns their URLs. Up to " +
    "${MAX_IMAGE_BYTES / 1024 / 1024} MB. The copy stays as it is when the picture changes where it came from; setting " +
    "it again makes a new one"

@Component
@ConditionalOnMedia
class ImagesMCP(
    private val images: Images,
    private val imagesRepository: ImagesRepository,
    private val episodes: EpisodesRepository,
    private val people: PeopleRepository,
) {

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun findPerson(id: UUID) = requireNotNull(people.findByIdOrNull(id)) { "Person $id not found" }

    private fun image(id: UUID?) = id?.let(imagesRepository::findByIdOrNull)?.let(images::toDto)

    private fun publish(from: ImageSource, place: String?, path: String?, url: String?, name: String?, artwork: EpisodeImage? = null): Image =
        when (from) {
            ImageSource.UPLOAD -> throw IllegalArgumentException("Uploads come from MetaOS's pages: Upload artwork on an episode's page")
            ImageSource.URL -> images.publish(
                from, null, requireNotNull(url?.trim()?.ifEmpty { null }) { "A picture from a URL needs the url" },
                name?.trim()?.ifEmpty { null }, artwork,
            )
            else -> images.publish(from, place, requireNotNull(path?.trim()?.ifEmpty { null }) { "A picture in $from needs its path" }, artwork = artwork)
        }

    @Transactional
    @McpTool(
        name = "set_episode_image",
        annotations = McpTool.McpAnnotations(
            title = "Set episode image",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Make a picture, e.g. new artwork in Dropbox, one of an episode's pictures, each in PNG or JPEG and " +
            "exactly as big as it says, or it's refused: its THUMBNAIL (1920 × 1080), THUMBNAIL_B or THUMBNAIL_C (1920 × " +
            "1080), which go with its alternative titles B and C as they're A/B tested together, its WEB picture (1920 × " +
            "1080), the thumbnail without its words, for ogrodje.si, or its COVER (3000 × 3000, e.g. for podcast apps). In " +
            "the Artwork folder in Dropbox, an episode's folder usually has its cover (...-podcast.png), thumbnails " +
            "(...-thumb1.png, -thumb2, -thumb3) and web picture (...-web.png). $PUBLISHED"
    )
    fun setEpisodeImage(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Which of the episode's pictures it is") kind: EpisodeImage,
        @McpToolParam(description = IMAGE_FROM) from: ImageSource,
        @McpToolParam(description = IMAGE_PLACE, required = false) place: String?,
        @McpToolParam(description = IMAGE_PATH, required = false) path: String?,
        @McpToolParam(description = IMAGE_URL, required = false) url: String?,
        @McpToolParam(description = IMAGE_NAME, required = false) name: String? = null,
    ): EpisodeImageDto {
        val episode = findEpisode(code)
        val image = publish(from, place, path, url, name, kind)
        episode.setImageId(kind, image.id)
        return EpisodeImageDto(kind, images.toDto(image))
    }

    @Transactional
    @McpTool(
        name = "remove_episode_image",
        annotations = McpTool.McpAnnotations(
            title = "Remove episode image",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Take one of an episode's pictures off it, e.g. a thumbnail B no longer tested. Returns the one it had"
    )
    fun removeEpisodeImage(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Which of the episode's pictures") kind: EpisodeImage,
    ): EpisodeImageDto {
        val episode = findEpisode(code)
        val removed = EpisodeImageDto(kind, image(episode.imageId(kind)))
        episode.setImageId(kind, null)
        return removed
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_episode_images",
        annotations = McpTool.McpAnnotations(
            title = "List episode images",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List an episode's pictures: its thumbnail, thumbnails B and C, web picture and cover, with their URLs; none where it has none"
    )
    fun listEpisodeImages(@McpToolParam(description = EPISODE_REF) code: String): List<EpisodeImageDto> {
        val episode = findEpisode(code)
        return EpisodeImage.entries.map { EpisodeImageDto(it, image(episode.imageId(it))) }
    }

    @Transactional
    @McpTool(
        name = "set_person_avatar",
        annotations = McpTool.McpAnnotations(
            title = "Set person avatar",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Make a picture a person's avatar, e.g. one from Hygraph, the CMS, or a photo in Dropbox or Koofr: PNG, " +
            "JPEG, WebP or GIF, of any size. $PUBLISHED"
    )
    fun setPersonAvatar(
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(description = IMAGE_FROM) from: ImageSource,
        @McpToolParam(description = IMAGE_PLACE, required = false) place: String?,
        @McpToolParam(description = IMAGE_PATH, required = false) path: String?,
        @McpToolParam(description = IMAGE_URL, required = false) url: String?,
        @McpToolParam(description = IMAGE_NAME, required = false) name: String? = null,
    ): ImageDto {
        val person = findPerson(personId)
        val image = publish(from, place, path, url, name)
        person.avatarImageId = image.id
        return images.toDto(image)
    }

    @Transactional
    @McpTool(
        name = "remove_person_avatar",
        annotations = McpTool.McpAnnotations(
            title = "Remove person avatar",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Take a person's avatar off them. Returns the one they had"
    )
    fun removePersonAvatar(@McpToolParam(description = "Person ID") personId: UUID): ImageDto? {
        val person = findPerson(personId)
        return image(person.avatarImageId).also { person.avatarImageId = null }
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "get_person_avatar",
        annotations = McpTool.McpAnnotations(
            title = "Get person avatar",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "A person's avatar, with its URLs; none when they have none"
    )
    fun getPersonAvatar(@McpToolParam(description = "Person ID") personId: UUID): ImageDto? =
        image(findPerson(personId).avatarImageId)
}
