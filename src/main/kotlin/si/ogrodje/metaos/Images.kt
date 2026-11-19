package si.ogrodje.metaos

import jakarta.persistence.*
import net.coobird.thumbnailator.Thumbnails
import org.hibernate.annotations.Immutable
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import kotlin.concurrent.withLock

/** Where a picture came from: a place and a path in Dropbox or Koofr, a URL, or an upload from the browser (Artwork) */
enum class ImageSource { DROPBOX, KOOFR, URL, UPLOAD }

/**
 * A picture in the public bucket (Media), e.g. an episode's thumbnail: its original at [originalPath], as it came, and
 * copies of it as wide as [widths] says, all narrower than it, at [path]. Stored once per file ([sha256]), however
 * often it's used; it never changes, so its files are cached for good.
 */
@Entity
@Immutable
@Table(name = "images")
class Image(
    @Column(nullable = false, columnDefinition = "text") val sha256: String,
    // The file's name where it came from, e.g. ogrodje-vresGams-thumb1.png
    @Column(nullable = false, columnDefinition = "text") val name: String,
    @Column(nullable = false, columnDefinition = "text") val contentType: String,
    @Column(nullable = false) val size: Long,
    @Column(nullable = false) val width: Int,
    @Column(nullable = false) val height: Int,
    @Column(nullable = false, columnDefinition = "int[]") val widths: IntArray,
    // Of the copies: jpg, or png for a picture that's see-through somewhere
    @Column(nullable = false, columnDefinition = "text") val format: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    val source: ImageSource,
    @Column(columnDefinition = "text") val sourcePlace: String?,
    // A path in the place, or the URL
    @Column(nullable = false, columnDefinition = "text") val sourcePath: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    fun originalPath() = "images/$sha256/original.${FORMATS.values.first { it.contentType == contentType }.extension}"

    fun path(width: Int) = "images/$sha256/$width.$format"

    override fun equals(other: Any?) = this === other || (other is Image && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Image(id=$id, name=$name)"
}

interface ImagesRepository : JpaRepository<Image, UUID> {
    fun findBySha256(sha256: String): Image?
}

/**
 * An episode's pictures, each in PNG or JPEG and exactly as big as it says: its thumbnail, and one for each of its
 * alternative titles, as they're A/B tested together; its web picture, the thumbnail without its words, for ogrodje.si;
 * and its cover, e.g. for podcast apps, as big as Apple Podcasts takes them. As the Artwork folder in Dropbox has them.
 */
enum class EpisodeImage(val label: String, val width: Int, val height: Int) {
    THUMBNAIL("thumbnail", 1920, 1080),
    THUMBNAIL_B("thumbnail B", 1920, 1080),
    THUMBNAIL_C("thumbnail C", 1920, 1080),
    WEB("web picture", 1920, 1080),
    COVER("cover", 3000, 3000),
}

// What an episode's pictures may be
val ARTWORK_TYPES = setOf("image/png", "image/jpeg")

/** Refuses a picture that isn't what the episode's picture has to be: PNG or JPEG, exactly as big as it says */
private fun EpisodeImage.check(name: String, contentType: String, width: Int, height: Int) {
    require(contentType in ARTWORK_TYPES) { "$name isn't PNG or JPEG, which an episode's $label has to be" }
    require(width == this.width && height == this.height) {
        "$name is $width × $height; an episode's $label has to be exactly ${this.width} × ${this.height}"
    }
}

data class ImageSizeDto(val width: Int, val url: String)

/** A picture as anyone can see it: the original at [url], and smaller copies of it, the narrowest first */
data class ImageDto(
    val name: String,
    val url: String,
    val width: Int,
    val height: Int,
    val sizes: List<ImageSizeDto>,
    val from: ImageSource,
    val place: String?,
    val path: String,
    // Of the original: the same file is the same picture
    val sha256: String,
)

private class Format(val extension: String, val contentType: String)

// What MetaOS takes, by ImageIO's names for them
private val FORMATS = mapOf(
    "png" to Format("png", "image/png"),
    "jpeg" to Format("jpg", "image/jpeg"),
    "webp" to Format("webp", "image/webp"),
    "gif" to Format("gif", "image/gif"),
)

// From a list's thumbnails to a page's picture; none as wide as the original, which is there anyway
private val WIDTHS = listOf(320, 640, 1280, 1920)

// The podcast cover from the Artwork folder is 16 MB; decoded, a picture takes 4 bytes a pixel
const val MAX_IMAGE_BYTES = 50 * 1024 * 1024
private const val MAX_PIXELS = 40_000_000L

private const val JPEG_QUALITY = 0.85f

// Paths change with what's in them, so what's there never changes
private const val CACHE_CONTROL = "public, max-age=31536000, immutable"

// What an agent may send inline (MCP's data), as base64, which takes a third more than that
const val MAX_DATA_BYTES = 7 * 1024 * 1024

/**
 * Where a picture comes from, as MCP and GraphQL say it: a file in a place in Dropbox or Koofr, a page's address on the
 * web, or an upload, sent to MetaOS's bucket by the browser or an agent ([Upload]) or inline, as base64 ([Data]); both
 * are kept as [UPLOAD][ImageSource.UPLOAD], by the file's name
 */
sealed interface PictureFrom {
    data class Dropbox(val place: String, val path: String) : PictureFrom
    data class Koofr(val place: String, val path: String) : PictureFrom
    data class Url(val url: String, val name: String? = null) : PictureFrom
    data class Upload(val id: UUID, val name: String) : PictureFrom {
        val path get() = uploadPath(id)
    }
    class Data(val bytes: ByteArray, val name: String) : PictureFrom

    companion object {
        /** A picture sent inline: base64, also as a data: URL's, of at most [MAX_DATA_BYTES] */
        fun data(base64: String, name: String): Data {
            val encoded = base64.trim().let { if (it.startsWith("data:")) it.substringAfter(',') else it }
            require(encoded.isNotEmpty()) { "The picture's data is empty" }
            // Base64 takes a third more than what it encodes, and line breaks, so twice as much is too much anyway
            require(encoded.length <= MAX_DATA_BYTES * 2) { TOO_BIG_DATA }
            val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }
                .getOrElse { throw IllegalArgumentException("The picture's data isn't base64") }
            require(bytes.size <= MAX_DATA_BYTES) { TOO_BIG_DATA }
            return Data(bytes, fileName(name))
        }

        private val TOO_BIG_DATA = "A picture sent as data is up to ${MAX_DATA_BYTES / 1024 / 1024} MB; upload a bigger one " +
            "(start_image_upload)"

        /** A file's name without its folders, which a picture needs where it has no path to be named by */
        fun fileName(name: String) =
            name.substringAfterLast('/').trim().ifEmpty { throw IllegalArgumentException("A picture needs its file's name") }
    }
}

/** Where an upload is in MetaOS's private bucket, a picture's or a video's, until it's kept (a lifecycle rule deletes it after a day) */
fun uploadPath(id: UUID) = "uploads/$id"

private class Fetched(val name: String, val bytes: ByteArray, val place: String?, val path: String)

/**
 * Pictures copied to the public bucket (Media) from where they are, e.g. new artwork in Dropbox, for everyone to see:
 * the original, and smaller copies of it, JPEGs unless it's see-through somewhere. One at a time, as a big picture takes
 * a lot of memory.
 */
@Component
@ConditionalOnMedia
class Images(
    private val media: Media,
    private val images: ImagesRepository,
    private val dropbox: ObjectProvider<Dropbox>,
    private val koofr: ObjectProvider<Koofr>,
    private val storage: ObjectProvider<Storage>,
    private val web: PublicWeb,
) {

    private val making = ReentrantLock()

    init {
        // In a Spring Boot jar ImageIO finds the plugins (TwelveMonkeys) only with the app's class loader; and decoding
        // in memory rather than through temporary files
        ImageIO.scanForPlugins()
        ImageIO.setUseCache(false)
    }

    /**
     * The picture [from] wherever it is, in the public bucket: a file in Dropbox or Koofr, on the web (MetaOS fetches over
     * https only, from public addresses only, [PublicWeb]), or uploaded. A URL's picture is named after its last part,
     * unless it has a name, e.g. Hygraph's, whose URLs have none; an upload by its file's name. One of an episode's
     * pictures ([artwork]) has to be what that one is: PNG or JPEG, exactly as big as it says.
     */
    fun publish(from: PictureFrom, artwork: EpisodeImage? = null): Image =
        making.withLock {
            // Fetched only once it's its turn, so pictures asked for at once wait rather than all be in memory together
            val fetched = fetch(from)
            val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fetched.bytes))
            images.findBySha256(sha256)?.also { artwork?.check(fetched.name, it.contentType, it.width, it.height) }
                ?: images.save(make(sha256, from.source, fetched, artwork))
        }

    private val PictureFrom.source
        get() = when (this) {
            is PictureFrom.Dropbox -> ImageSource.DROPBOX
            is PictureFrom.Koofr -> ImageSource.KOOFR
            is PictureFrom.Url -> ImageSource.URL
            is PictureFrom.Upload, is PictureFrom.Data -> ImageSource.UPLOAD
        }

    /**
     * A picture someone else fetched from [url], e.g. a post's preview picture (DistributionPreview.kt), which checks
     * where it fetches from itself, as from anywhere on the web rather than Hygraph only
     */
    fun publish(url: String, name: String, bytes: ByteArray): Image = making.withLock {
        require(bytes.size <= MAX_IMAGE_BYTES) { "$url is more than ${MAX_IMAGE_BYTES / 1024 / 1024} MB" }
        val fetched = Fetched(name, bytes, null, url)
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        images.findBySha256(sha256) ?: images.save(make(sha256, ImageSource.URL, fetched, null))
    }

    fun toDto(image: Image) = ImageDto(
        image.name, media.url(image.originalPath()), image.width, image.height,
        image.widths.map { ImageSizeDto(it, media.url(image.path(it))) }, image.source, image.sourcePlace, image.sourcePath,
        image.sha256,
    )

    private fun fetch(from: PictureFrom): Fetched = when (from) {
        is PictureFrom.Dropbox -> {
            val dropbox = checkNotNull(dropbox.ifAvailable) { "Dropbox is off" }
            val shared = dropbox.sharedPlace(from.place)
            val file = requireNotNull(dropbox.file(shared.id, dropboxPath(from.path))) { "There's no ${from.path} in ${shared.name}" }
            require(!file.folder) { "${from.path} is a folder" }
            Fetched(file.name, limited(from.path, file.size) { dropbox.read(shared.id, file.path, it) }, shared.id, file.path)
        }
        is PictureFrom.Koofr -> {
            val koofr = checkNotNull(koofr.ifAvailable) { "Koofr is off" }
            val shared = koofr.sharedPlace(from.place)
            val file = requireNotNull(koofr.file(shared.id, koofrPath(from.path))) { "There's no ${from.path} in ${shared.name}" }
            require(!file.folder) { "${from.path} is a folder" }
            Fetched(file.name, limited(from.path, file.size) { koofr.read(shared.id, file.path, it) }, shared.id, file.path)
        }
        is PictureFrom.Url -> {
            val page = try {
                web.get(from.url, "image/*", MAX_IMAGE_BYTES + 1)
            } catch (e: PreviewUnavailable) {
                throw IllegalArgumentException(e.message, e)
            }
            require(page.status == 200) { "${from.url} answered ${page.status}" }
            val bytes = limited(from.url, null) { page.body }
            val url = page.url.toString()
            Fetched(from.name?.let(PictureFrom::fileName) ?: page.url.path.orEmpty().substringAfterLast('/').ifEmpty { url }, bytes, null, url)
        }
        // Kept by the file's name, as the upload goes once it's published
        is PictureFrom.Upload -> {
            val storage = checkNotNull(storage.ifAvailable) { "MetaOS has no bucket for uploads" }
            val named = PictureFrom.fileName(from.name)
            val file = requireNotNull(storage.file(from.path)) { "$named didn't arrive in MetaOS's bucket; upload it again" }
            Fetched(named, limited(named, file.size) { storage.read(from.path, it) }, null, named)
        }
        is PictureFrom.Data -> Fetched(from.name, from.bytes, null, from.name)
    }

    // Reads one byte more than it takes, to tell a file that's too big from one that isn't
    private fun limited(path: String, size: Long?, read: (Int) -> ByteArray): ByteArray {
        val tooBig = "$path is more than ${MAX_IMAGE_BYTES / 1024 / 1024} MB"
        require(size == null || size <= MAX_IMAGE_BYTES) { tooBig }
        return read(MAX_IMAGE_BYTES + 1).also { require(it.size <= MAX_IMAGE_BYTES) { tooBig } }
    }

    private fun make(sha256: String, source: ImageSource, fetched: Fetched, artwork: EpisodeImage?): Image {
        val (format, picture) = decode(fetched, artwork)
        val copies = if (picture.isOpaque()) "jpg" else "png"
        val widths = WIDTHS.filter { it < picture.width }
        val image = Image(
            sha256, fetched.name, format.contentType, fetched.bytes.size.toLong(), picture.width, picture.height,
            widths.toIntArray(), copies, source, fetched.place, fetched.path,
        )
        // The copies first: the original's there only once they all are
        widths.forEach { width ->
            upload(image.path(width), if (copies == "jpg") "image/jpeg" else "image/png", resized(picture, width, copies))
        }
        upload(image.originalPath(), format.contentType, fetched.bytes)
        return image
    }

    // A file already there is the same one, as its path is its content's hash
    private fun upload(path: String, contentType: String, bytes: ByteArray) {
        media.storage.upload(path, contentType, ByteArrayInputStream(bytes), overwrite = true, cacheControl = CACHE_CONTROL)
    }

    // An episode's picture is refused for its size before it's decoded, by what its header says, and once it's turned
    private fun decode(fetched: Fetched, artwork: EpisodeImage?): Pair<Format, BufferedImage> {
        val unreadable = "${fetched.name} isn't a picture MetaOS can read: PNG, JPEG, WebP or GIF"
        val format = ImageIO.createImageInputStream(ByteArrayInputStream(fetched.bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: throw IllegalArgumentException(unreadable)
            try {
                reader.input = input
                val format = FORMATS[reader.formatName.lowercase()] ?: throw IllegalArgumentException(unreadable)
                val (width, height) = reader.getWidth(0) to reader.getHeight(0)
                artwork?.check(fetched.name, format.contentType, width, height)
                require(width.toLong() * height <= MAX_PIXELS) {
                    "${fetched.name} is $width × $height; at most ${MAX_PIXELS / 1_000_000} megapixels"
                }
                format
            } finally {
                reader.dispose()
            }
        }
        // Turned as its EXIF orientation says, e.g. a photo taken with the phone held upright
        val picture = runCatching { Thumbnails.of(ByteArrayInputStream(fetched.bytes)).scale(1.0).asBufferedImage() }
            .getOrElse { throw IllegalArgumentException(unreadable, it) }
        artwork?.check(fetched.name, format.contentType, picture.width, picture.height)
        return format to picture
    }

    private fun resized(picture: BufferedImage, width: Int, format: String): ByteArray {
        val out = ByteArrayOutputStream()
        Thumbnails.of(picture).width(width).outputFormat(format)
            // JPEG has no transparency, which an opaque picture can do without
            .apply { if (format == "jpg") imageType(BufferedImage.TYPE_INT_RGB).outputQuality(JPEG_QUALITY) }
            .toOutputStream(out)
        return out.toByteArray()
    }
}

// Whether there's nothing see-through in it, also where a PNG could have been
private fun BufferedImage.isOpaque(): Boolean {
    val alpha = alphaRaster?.takeIf { colorModel.hasAlpha() } ?: return true
    val opaque = (1 shl colorModel.getComponentSize(colorModel.numComponents - 1)) - 1
    val row = IntArray(width)
    return (0 until height).all { y -> alpha.getPixels(0, y, width, 1, row).all { it == opaque } }
}
