package si.ogrodje.metaos

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/** Where a picture is sent: a resumable upload ([SignedUpload]) started at [url] with [headers], until [expires] */
data class ImageUploadDto(val id: UUID, val url: String, val headers: List<HeaderDto>, val expires: String)

data class HeaderDto(val name: String, val value: String)

/**
 * Where an uploaded picture is in the episode's Dropbox folder: at [path] in its place, put there by MetaOS ([copied])
 * or there already; or why it isn't ([error])
 */
data class DropboxCopyDto(val path: String?, val copied: Boolean, val error: String? = null)

/**
 * One of an episode's pictures, as it was set, and, for one uploaded, where it is in the episode's Dropbox folder, when
 * it has one
 */
data class EpisodeImageSetDto(val kind: EpisodeImage, val image: ImageDto, val dropbox: DropboxCopyDto?)

// How long the browser or an agent has to start sending a picture
private val UPLOAD_FOR = Duration.ofHours(1)

// What MetaOS takes as pictures, of which an episode's are PNG or JPEG (ARTWORK_TYPES)
val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

/**
 * Pictures set on what they're of, for MCP and GraphQL alike: an episode's (its thumbnails, web picture and cover), a
 * person's avatar and an organisation's logo, from wherever they are ([PictureFrom]). An upload is sent straight to
 * MetaOS's bucket, under uploads/, with a signed URL ([upload]), by the browser or an agent, then published as any other
 * (Images), which refuses one of an episode's unless it's that picture's size; the upload goes either way. An episode's
 * uploaded picture also goes to its Dropbox folder, when it has one, unless it's in it already, e.g. as the designer
 * dropped it from there.
 */
@Component
@ConditionalOnMedia
class ImageUploads(
    private val images: Images,
    private val imagesRepository: ImagesRepository,
    private val storage: ObjectProvider<Storage>,
    private val episodes: EpisodesRepository,
    private val people: PeopleRepository,
    private val organisations: Organisations,
    private val dropboxFolders: EpisodeDropboxFoldersRepository,
    private val dropbox: ObjectProvider<Dropbox>,
    transactionManager: PlatformTransactionManager,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun findPerson(id: UUID) = requireNotNull(people.findByIdOrNull(id)) { "Person $id not found" }

    private fun image(id: UUID?) = id?.let(imagesRepository::findByIdOrNull)?.let(images::toDto)

    private fun storage() = checkNotNull(storage.ifAvailable) { "MetaOS has no bucket for uploads (METAOS_STORAGE_BUCKET)" }

    /** A place in MetaOS's bucket for a picture, PNG, JPEG, WebP or GIF of [size] bytes */
    fun upload(contentType: String, size: Int): ImageUploadDto {
        require(contentType in IMAGE_TYPES) { "A picture is PNG, JPEG, WebP or GIF: image/png, image/jpeg, image/webp or image/gif" }
        require(size in 1..MAX_IMAGE_BYTES) { "A picture is up to ${MAX_IMAGE_BYTES / 1024 / 1024} MB" }
        val id = UUID.randomUUID()
        val upload = storage().uploadUrl(uploadPath(id), contentType, UPLOAD_FOR)
        return ImageUploadDto(id, upload.url.toString(), upload.headers.map { (name, value) -> HeaderDto(name, value) }, upload.expires.toString())
    }

    /** A place for one of the episode's pictures, PNG or JPEG, as [upload] is: what artworkUpload was, before uploads were for any picture */
    fun artworkUpload(ref: String, contentType: String, size: Int): ImageUploadDto {
        findEpisode(ref)
        require(contentType in ARTWORK_TYPES) { "An episode's pictures are PNG or JPEG" }
        return upload(contentType, size)
    }

    /**
     * Makes a picture the episode's [kind] of picture, which it has to be the size of. Another picture the episode has
     * there already is only replaced when it's to be ([replace]). An upload goes either way: to try again, it's sent again.
     */
    fun setEpisodeImage(ref: String, kind: EpisodeImage, from: PictureFrom, replace: Boolean): EpisodeImageSetDto = uploaded(from) {
        findEpisode(ref)
        val image = images.publish(from, kind)
        // Asked again, as someone may have set it meanwhile
        val episode = checkNotNull(transaction.execute {
            findEpisode(ref).also { episode ->
                val had = episode.imageId(kind)
                require(had == null || had == image.id || replace) { "The episode's ${kind.label} is another picture now; replace it, or keep that one" }
                episode.setImageId(kind, image.id)
            }
        })
        val copy = when (from) {
            is PictureFrom.Upload -> toDropbox(episode, image.name) { storage().read(from.path, MAX_IMAGE_BYTES + 1) }
            is PictureFrom.Data -> toDropbox(episode, image.name) { from.bytes }
            // Where it came from is where it is
            else -> null
        }
        EpisodeImageSetDto(kind, images.toDto(image), copy)
    }

    /** Takes one of the episode's pictures off it; the one it had */
    fun removeEpisodeImage(ref: String, kind: EpisodeImage): ImageDto? = checkNotNull(transaction.execute {
        val episode = findEpisode(ref)
        Optional(image(episode.imageId(kind)).also { episode.setImageId(kind, null) })
    }).value

    /** Makes a picture, of any size, the person's avatar */
    fun setAvatar(personId: UUID, from: PictureFrom): ImageDto = uploaded(from) {
        findPerson(personId)
        val image = images.publish(from)
        transaction.executeWithoutResult { findPerson(personId).avatarImageId = image.id }
        images.toDto(image)
    }

    /** Takes the person's avatar off them; the one they had */
    fun removeAvatar(personId: UUID): ImageDto? = checkNotNull(transaction.execute {
        val person = findPerson(personId)
        Optional(image(person.avatarImageId).also { person.avatarImageId = null })
    }).value

    /** Makes a picture, of any size, the organisation's logo */
    fun setLogo(organisationId: UUID, from: PictureFrom): ImageDto = uploaded(from) {
        organisations.find(organisationId)
        val image = images.publish(from)
        transaction.executeWithoutResult { organisations.find(organisationId).logoImageId = image.id }
        images.toDto(image)
    }

    /** Takes the organisation's logo off it; the one it had */
    fun removeLogo(organisationId: UUID): ImageDto? = checkNotNull(transaction.execute {
        val organisation = organisations.find(organisationId)
        Optional(image(organisation.logoImageId).also { organisation.logoImageId = null })
    }).value

    // TransactionTemplate's execute takes no null
    private class Optional<T>(val value: T?)

    // An upload goes once it's published, or refused
    private fun <T> uploaded(from: PictureFrom, set: () -> T): T = try {
        set()
    } finally {
        if (from is PictureFrom.Upload) {
            runCatching { storage().delete(from.path) }.onFailure { logger.warn("{} stays in the bucket", from.path, it) }
        }
    }

    /**
     * The picture in the episode's first Dropbox folder, as its page lists them: the same content right in it or in a
     * folder right in it, where its artwork is (DropboxPictures), or else a copy right in it, under another name when its
     * own is taken. None without a Dropbox folder; when it can't be put there, why.
     */
    private fun toDropbox(episode: Episode, name: String, read: () -> ByteArray): DropboxCopyDto? {
        val dropbox = dropbox.ifAvailable ?: return null
        val link = dropboxFolders.findByEpisode(episode).minWithOrNull(compareBy({ it.path }, { it.place })) ?: return null
        return try {
            val folder = dropbox.fileById(link.place, link.folderId)
                ?: return DropboxCopyDto(null, false, "The episode's Dropbox folder isn't in its place anymore")
            val content = read()
            val hash = dropboxContentHash(content)
            val inFolder = dropbox.list(link.place, folder.path)
            val there = (inFolder.asSequence() + inFolder.asSequence().filter { it.folder }.flatMap { dropbox.list(link.place, it.path) })
                .firstOrNull { !it.folder && it.contentHash == hash }
            when {
                there != null -> DropboxCopyDto(there.path, copied = false)
                dropbox.places().none { it.id == link.place && it.writable } ->
                    DropboxCopyDto(null, false, "The bot may only read the episode's Dropbox folder")
                else -> DropboxCopyDto(dropbox.upload(link.place, folder.path, name, content, overwrite = false, rename = true).path, copied = true)
            }
        } catch (e: Exception) {
            logger.warn("{} of episode {} didn't go to Dropbox", name, episode.id, e)
            DropboxCopyDto(null, false, e.message ?: "Dropbox didn't answer")
        }
    }
}

// Pictures are the team's, as other changes are: an approved user's token with metaos:write
@Controller
class ImageUploadsGraphQL(private val uploads: ObjectProvider<ImageUploads>) {

    private fun uploads() = checkNotNull(uploads.ifAvailable) { "MetaOS has no public bucket for pictures (METAOS_MEDIA_BUCKET)" }

    @MutationMapping
    @PreAuthorize(WRITES)
    fun imageUpload(@Argument contentType: String, @Argument size: Int): ImageUploadDto = uploads().upload(contentType, size)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun artworkUpload(@Argument ref: String, @Argument contentType: String, @Argument size: Int): ImageUploadDto =
        uploads().artworkUpload(ref, contentType, size)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun setEpisodeImage(
        @Argument ref: String,
        @Argument kind: EpisodeImage,
        @Argument upload: UUID,
        @Argument name: String,
        @Argument replace: Boolean,
    ): EpisodeImageSetDto = uploads().setEpisodeImage(ref, kind, PictureFrom.Upload(upload, name), replace)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun removeEpisodeImage(@Argument ref: String, @Argument kind: EpisodeImage): ImageDto? = uploads().removeEpisodeImage(ref, kind)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun setPersonAvatar(@Argument personId: UUID, @Argument upload: UUID, @Argument name: String): ImageDto =
        uploads().setAvatar(personId, PictureFrom.Upload(upload, name))

    @MutationMapping
    @PreAuthorize(WRITES)
    fun removePersonAvatar(@Argument personId: UUID): ImageDto? = uploads().removeAvatar(personId)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun setOrganisationLogo(@Argument organisationId: UUID, @Argument upload: UUID, @Argument name: String): ImageDto =
        uploads().setLogo(organisationId, PictureFrom.Upload(upload, name))

    @MutationMapping
    @PreAuthorize(WRITES)
    fun removeOrganisationLogo(@Argument organisationId: UUID): ImageDto? = uploads().removeLogo(organisationId)
}
