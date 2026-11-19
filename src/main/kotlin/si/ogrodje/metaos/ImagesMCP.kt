package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** One of an episode's pictures; none when it doesn't have that one */
data class EpisodeImageDto(val kind: EpisodeImage, val image: ImageDto?)

private const val IMAGE_FROM = "Where the picture is: DROPBOX or KOOFR, with place and path; URL, with url; or " +
    "UPLOAD, with name and either upload (from start_image_upload, then sent there) or data (base64)"
private const val IMAGE_PLACE = "For DROPBOX or KOOFR: a folder shared with Ogrodje's bot there, by name or ID " +
    "(list_dropbox_places, list_koofr_places)"
private const val IMAGE_PATH = "For DROPBOX or KOOFR: the picture's path in the place, e.g. /Season 5/S05E07 - vres gams/ogrodje-vresGams-thumb1.png"
private const val IMAGE_URL = "For URL: an https URL of the picture, on any public site, e.g. Hygraph's (*.graphassets.com)"
private const val IMAGE_NAME = "For URL: the picture's file name, e.g. Hygraph's fileName, as its URL has none; the URL's " +
    "last part when omitted. For UPLOAD (required): the file's name, e.g. ogrodje-vresGams-thumb1.png, which MetaOS keeps it by"
private const val IMAGE_UPLOAD = "For UPLOAD: the ID start_image_upload gave, once the file's been sent there"
private const val IMAGE_DATA = "For UPLOAD, instead of upload: the picture itself, base64 (also a data: URL), up to " +
    "${MAX_DATA_BYTES / 1024 / 1024} MB; a bigger one through start_image_upload"

private const val PUBLISHED = "MetaOS copies it to its public bucket, where anyone can see it, with copies of it 320, " +
    "640, 1280 and 1920 px wide (those narrower than it), and returns their URLs. Up to " +
    "${MAX_IMAGE_BYTES / 1024 / 1024} MB. The copy stays as it is when the picture changes where it came from; setting " +
    "it again makes a new one"

/** Where to send a picture, as start_image_upload answers: [ImageUploadDto]'s, and the shell commands that send it */
data class ImageUploadToolDto(val id: UUID, val url: String, val headers: List<HeaderDto>, val expires: String, val curl: String)

@Component
@ConditionalOnMedia
class ImagesMCP(
    private val images: Images,
    private val imagesRepository: ImagesRepository,
    private val uploads: ImageUploads,
    private val episodes: EpisodesRepository,
    private val people: PeopleRepository,
    private val organisations: Organisations,
) {

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun findPerson(id: UUID) = requireNotNull(people.findByIdOrNull(id)) { "Person $id not found" }

    private fun image(id: UUID?) = id?.let(imagesRepository::findByIdOrNull)?.let(images::toDto)

    private fun String?.given() = this?.trim()?.ifEmpty { null }

    private fun from(from: ImageSource, place: String?, path: String?, url: String?, name: String?, upload: String?, data: String?): PictureFrom {
        fun place() = requireNotNull(place.given()) { "A picture in $from needs its place" }
        fun path() = requireNotNull(path.given()) { "A picture in $from needs its path" }
        return when (from) {
            ImageSource.DROPBOX -> PictureFrom.Dropbox(place(), path())
            ImageSource.KOOFR -> PictureFrom.Koofr(place(), path())
            ImageSource.URL -> PictureFrom.Url(requireNotNull(url.given()) { "A picture from a URL needs the url" }, name.given())
            ImageSource.UPLOAD -> {
                val named = requireNotNull(name.given()) { "An upload needs its file's name (name)" }
                val id = upload.given()
                val inline = data.given()
                require((id == null) != (inline == null)) { "An upload is either upload, from start_image_upload, or data, base64" }
                if (id != null) {
                    PictureFrom.Upload(runCatching { UUID.fromString(id) }.getOrElse { throw IllegalArgumentException("$id isn't an upload's ID") }, named)
                } else {
                    PictureFrom.data(checkNotNull(inline), named)
                }
            }
        }
    }

    @McpTool(
        name = "start_image_upload",
        annotations = McpTool.McpAnnotations(
            title = "Start image upload",
            readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false
        ),
        description = "Get a place to send a picture to, straight to MetaOS's bucket, for set_episode_image, " +
            "set_person_avatar or set_organisation_logo with from UPLOAD and its upload ID: PNG, JPEG, WebP or GIF (an " +
            "episode's pictures PNG or JPEG), up to ${MAX_IMAGE_BYTES / 1024 / 1024} MB. Sending it is a resumable " +
            "upload: POST to url with exactly these headers and no body, then PUT the file to the Location it answers; " +
            "curl has both as shell commands, for the file at \$FILE. It starts within an hour, and is deleted once it's " +
            "set (or refused), or after a day. A picture up to ${MAX_DATA_BYTES / 1024 / 1024} MB can go as data instead"
    )
    fun startImageUpload(
        @McpToolParam(description = "The picture's type: image/png, image/jpeg, image/webp or image/gif") contentType: String,
        @McpToolParam(description = "The file's size, in bytes") size: Int,
    ): ImageUploadToolDto {
        val upload = uploads.upload(contentType.trim().lowercase(), size)
        val headers = upload.headers.joinToString(" ") { "-H '${it.name}: ${it.value}'" }
        val curl = "location=$(curl -sS -X POST $headers -D - -o /dev/null '${upload.url}' | tr -d '\\r' | " +
            "sed -n 's/^[Ll]ocation: //p') && curl -sS -X PUT -H 'Content-Type: ${contentType.trim().lowercase()}' " +
            "--data-binary @\"\$FILE\" \"\$location\""
        return ImageUploadToolDto(upload.id, upload.url, upload.headers, upload.expires, curl)
    }

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
            "(...-thumb1.png, -thumb2, -thumb3) and web picture (...-web.png). An uploaded one also goes to the episode's " +
            "first Dropbox folder, unless it's there already (dropbox says where, or why not). $PUBLISHED"
    )
    fun setEpisodeImage(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Which of the episode's pictures it is") kind: EpisodeImage,
        @McpToolParam(description = IMAGE_FROM) from: ImageSource,
        @McpToolParam(description = IMAGE_PLACE, required = false) place: String?,
        @McpToolParam(description = IMAGE_PATH, required = false) path: String?,
        @McpToolParam(description = IMAGE_URL, required = false) url: String?,
        @McpToolParam(description = IMAGE_NAME, required = false) name: String? = null,
        @McpToolParam(description = IMAGE_UPLOAD, required = false) upload: String? = null,
        @McpToolParam(description = IMAGE_DATA, required = false) data: String? = null,
    ): EpisodeImageSetDto {
        findEpisode(code)
        return uploads.setEpisodeImage(code, kind, from(from, place, path, url, name, upload, data), replace = true)
    }

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
    ): EpisodeImageDto = EpisodeImageDto(kind, uploads.removeEpisodeImage(code, kind))

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

    @McpTool(
        name = "set_person_avatar",
        annotations = McpTool.McpAnnotations(
            title = "Set person avatar",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Make a picture a person's avatar, e.g. a photo in Dropbox or Koofr, one on the web, or one uploaded: " +
            "PNG, JPEG, WebP or GIF, of any size. $PUBLISHED"
    )
    fun setPersonAvatar(
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(description = IMAGE_FROM) from: ImageSource,
        @McpToolParam(description = IMAGE_PLACE, required = false) place: String?,
        @McpToolParam(description = IMAGE_PATH, required = false) path: String?,
        @McpToolParam(description = IMAGE_URL, required = false) url: String?,
        @McpToolParam(description = IMAGE_NAME, required = false) name: String? = null,
        @McpToolParam(description = IMAGE_UPLOAD, required = false) upload: String? = null,
        @McpToolParam(description = IMAGE_DATA, required = false) data: String? = null,
    ): ImageDto {
        findPerson(personId)
        return uploads.setAvatar(personId, from(from, place, path, url, name, upload, data))
    }

    @McpTool(
        name = "remove_person_avatar",
        annotations = McpTool.McpAnnotations(
            title = "Remove person avatar",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Take a person's avatar off them. Returns the one they had"
    )
    fun removePersonAvatar(@McpToolParam(description = "Person ID") personId: UUID): ImageDto? = uploads.removeAvatar(personId)

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

    @McpTool(
        name = "set_organisation_logo",
        annotations = McpTool.McpAnnotations(
            title = "Set organisation logo",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Make a picture an organisation's logo, e.g. one in Dropbox or Koofr, on its website, or uploaded: " +
            "PNG, JPEG, WebP or GIF, of any size, best see-through around it, as it's shown on the home page and in the " +
            "footer. $PUBLISHED"
    )
    fun setOrganisationLogo(
        @McpToolParam(description = "Organisation ID") organisationId: UUID,
        @McpToolParam(description = IMAGE_FROM) from: ImageSource,
        @McpToolParam(description = IMAGE_PLACE, required = false) place: String?,
        @McpToolParam(description = IMAGE_PATH, required = false) path: String?,
        @McpToolParam(description = IMAGE_URL, required = false) url: String?,
        @McpToolParam(description = IMAGE_NAME, required = false) name: String? = null,
        @McpToolParam(description = IMAGE_UPLOAD, required = false) upload: String? = null,
        @McpToolParam(description = IMAGE_DATA, required = false) data: String? = null,
    ): ImageDto {
        organisations.find(organisationId)
        return uploads.setLogo(organisationId, from(from, place, path, url, name, upload, data))
    }

    @McpTool(
        name = "remove_organisation_logo",
        annotations = McpTool.McpAnnotations(
            title = "Remove organisation logo",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Take an organisation's logo off it. Returns the one it had"
    )
    fun removeOrganisationLogo(@McpToolParam(description = "Organisation ID") organisationId: UUID): ImageDto? =
        uploads.removeLogo(organisationId)

    @Transactional(readOnly = true)
    @McpTool(
        name = "get_organisation_logo",
        annotations = McpTool.McpAnnotations(
            title = "Get organisation logo",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "An organisation's logo, with its URLs; none when it has none"
    )
    fun getOrganisationLogo(@McpToolParam(description = "Organisation ID") organisationId: UUID): ImageDto? =
        image(organisations.find(organisationId).logoImageId)
}
