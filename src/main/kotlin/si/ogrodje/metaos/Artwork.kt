package si.ogrodje.metaos

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/** Where the browser sends a picture: a resumable upload ([SignedUpload]) it starts at [url] with [headers], until [expires] */
data class ArtworkUploadDto(val id: UUID, val url: String, val headers: List<HeaderDto>, val expires: String)

data class HeaderDto(val name: String, val value: String)

/**
 * Where an uploaded picture is in the episode's Dropbox folder: at [path] in its place, put there by MetaOS ([copied])
 * or there already; or why it isn't ([error])
 */
data class DropboxCopyDto(val path: String?, val copied: Boolean, val error: String? = null)

/** One of an episode's pictures, as an upload made it, and where it is in the episode's Dropbox folder, when it has one */
data class EpisodeImageSetDto(val kind: EpisodeImage, val image: ImageDto, val dropbox: DropboxCopyDto?)

// How long the browser has to start sending a picture
private val UPLOAD_FOR = Duration.ofHours(1)

private fun uploadPath(id: UUID) = "uploads/$id"

/**
 * An episode's pictures uploaded from its page (Upload artwork): the browser sends each straight to MetaOS's bucket,
 * under uploads/, with a signed URL, and it's then published as one from Dropbox is (Images), which refuses it unless
 * it's the picture's size. When the episode has a Dropbox folder, the picture goes there too, unless it's in it already,
 * e.g. as the designer dropped it from there.
 */
@Component
@ConditionalOnMedia
class Artwork(
    private val images: Images,
    private val storage: Storage,
    private val episodes: EpisodesRepository,
    private val dropboxFolders: EpisodeDropboxFoldersRepository,
    private val dropbox: ObjectProvider<Dropbox>,
    transactionManager: PlatformTransactionManager,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    /** A place in MetaOS's bucket for a picture of the episode's, PNG or JPEG of [size] bytes */
    fun upload(ref: String, contentType: String, size: Int): ArtworkUploadDto {
        findEpisode(ref)
        require(contentType in ARTWORK_TYPES) { "An episode's pictures are PNG or JPEG" }
        require(size in 1..MAX_IMAGE_BYTES) { "A picture is up to ${MAX_IMAGE_BYTES / 1024 / 1024} MB" }
        val id = UUID.randomUUID()
        val upload = storage.uploadUrl(uploadPath(id), contentType, UPLOAD_FOR)
        return ArtworkUploadDto(id, upload.url.toString(), upload.headers.map { (name, value) -> HeaderDto(name, value) }, upload.expires.toString())
    }

    /**
     * Makes an upload, of a file called [name], the episode's [kind] of picture, which it has to be the size of. Another
     * picture the episode has there already is only replaced when it's to be ([replace]). The upload goes either way: to
     * try again, the browser sends the file again.
     */
    fun set(ref: String, kind: EpisodeImage, upload: UUID, name: String, replace: Boolean): EpisodeImageSetDto {
        val path = uploadPath(upload)
        try {
            val named = name.substringAfterLast('/').trim().ifEmpty { throw IllegalArgumentException("A picture needs its file's name") }
            findEpisode(ref)
            val image = images.publish(ImageSource.UPLOAD, null, path, named, kind)
            // Asked again, as someone may have set it meanwhile
            val episode = checkNotNull(transaction.execute {
                findEpisode(ref).also { episode ->
                    val had = episode.imageId(kind)
                    require(had == null || had == image.id || replace) { "The episode's ${kind.label} is another picture now; replace it, or keep that one" }
                    episode.setImageId(kind, image.id)
                }
            })
            return EpisodeImageSetDto(kind, images.toDto(image), toDropbox(episode, named, path))
        } finally {
            runCatching { storage.delete(path) }.onFailure { logger.warn("{} stays in the bucket", path, it) }
        }
    }

    /**
     * The picture in the episode's first Dropbox folder, as its page lists them: the same content right in it or in a
     * folder right in it, where its artwork is (DropboxPictures), or else a copy right in it, under another name when its
     * own is taken. None without a Dropbox folder; when it can't be put there, why.
     */
    private fun toDropbox(episode: Episode, name: String, path: String): DropboxCopyDto? {
        val dropbox = dropbox.ifAvailable ?: return null
        val link = dropboxFolders.findByEpisode(episode).minWithOrNull(compareBy({ it.path }, { it.place })) ?: return null
        return try {
            val folder = dropbox.fileById(link.place, link.folderId)
                ?: return DropboxCopyDto(null, false, "The episode's Dropbox folder isn't in its place anymore")
            val content = storage.read(path, MAX_IMAGE_BYTES + 1)
            val hash = dropboxContentHash(content)
            val inFolder = dropbox.list(link.place, folder.path)
            val there = (inFolder.asSequence() + inFolder.asSequence().filter { it.folder }.flatMap { dropbox.list(link.place, it.path) })
                .firstOrNull { !it.folder && it.contentHash == hash }
            when {
                there != null -> DropboxCopyDto(there.path, copied = false)
                dropbox.places().none { it.id == link.place && it.writable } ->
                    DropboxCopyDto(null, false, "The bot may only read the episode's Dropbox folder")
                else -> DropboxCopyDto(dropbox.upload(link.place, folder.path, name, content.inputStream(), content.size.toLong(), overwrite = false, rename = true).path, copied = true)
            }
        } catch (e: Exception) {
            logger.warn("{} of episode {} didn't go to Dropbox", name, episode.id, e)
            DropboxCopyDto(null, false, e.message ?: "Dropbox didn't answer")
        }
    }
}

// Uploads are the team's, as other changes are: an approved user's token with metaos:write
@Controller
class ArtworkGraphQL(private val artwork: ObjectProvider<Artwork>) {

    private fun artwork() = checkNotNull(artwork.ifAvailable) { "MetaOS has no public bucket for pictures (METAOS_MEDIA_BUCKET)" }

    @MutationMapping
    @PreAuthorize(WRITES)
    fun artworkUpload(@Argument ref: String, @Argument contentType: String, @Argument size: Int): ArtworkUploadDto =
        artwork().upload(ref, contentType, size)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun setEpisodeImage(
        @Argument ref: String,
        @Argument kind: EpisodeImage,
        @Argument upload: UUID,
        @Argument name: String,
        @Argument replace: Boolean,
    ): EpisodeImageSetDto = artwork().set(ref, kind, upload, name, replace)
}
