package si.ogrodje.metaos

import jakarta.persistence.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What an episode's video and audio are: the full episode and its bonuses, each as video or audio only, as Patreon has
 * them (Platform). An episode has one full video and one full audio, and any number of bonuses.
 */
enum class MediaKind(val label: String, val video: Boolean, val full: Boolean) {
    FULL_VIDEO("full video", video = true, full = true),
    FULL_AUDIO("full audio", video = false, full = true),
    BONUS_VIDEO("bonus video", video = true, full = false),
    BONUS_AUDIO("bonus audio", video = false, full = false),
}

/** What video and audio files may be, by their content type: video or not */
val MEDIA_TYPES = mapOf(
    "video/mp4" to true, "video/quicktime" to true,
    "audio/mpeg" to false, "audio/mp4" to false, "audio/wav" to false, "audio/flac" to false,
)

private fun typesOf(video: Boolean) = if (video) "MP4 or MOV" else "MP3, M4A, WAV or FLAC"

// A full episode's master can be big; Cloud Storage takes up to 5 TiB a file. In GB as the Finder says them.
const val MAX_MEDIA_BYTES = 50_000_000_000L
private const val MAX_MEDIA_GB = MAX_MEDIA_BYTES / 1_000_000_000

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

/**
 * One of an episode's video or audio files, in MetaOS's private bucket at [path], uploaded from its page (MediaFiles)
 * by the user [uploaderId] is (their Keycloak user ID, and their username then), with what ffprobe read of it: [info],
 * and its whole answer in [probe]. The episode is linked by ID, as comments are, since it's soft-deleted.
 */
@Entity
@Table(name = "episode_media")
class MediaFile(
    @Column(name = "episode_id", nullable = false) val episodeId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var kind: MediaKind,
    // A bonus's, e.g. what's in it
    @Column(columnDefinition = "text") var title: String?,
    // The file's name, as it was uploaded, e.g. S05E08-bonus.mp4
    @Column(nullable = false, columnDefinition = "text") val name: String,
    @Column(nullable = false, columnDefinition = "text") val path: String,
    @Column(nullable = false, columnDefinition = "text") val contentType: String,
    @Column(nullable = false) val size: Long,
    @Column(columnDefinition = "text") val md5: String?,
    @Column(columnDefinition = "text") val crc32c: String?,
    @Column(nullable = false, columnDefinition = "text") val uploaderId: String,
    @Column(columnDefinition = "text") val uploader: String?,
    @Embedded val info: MediaInfo,
    // Sent as text, which Postgres takes for JSON (stringtype=unspecified)
    @Column(columnDefinition = "jsonb") val probe: String?,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Column(nullable = false) val uploadedAt: OffsetDateTime = now()

    override fun equals(other: Any?) = this === other || (other is MediaFile && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "MediaFile(id=$id, episodeId=$episodeId, kind=$kind, name=$name)"
}

interface MediaFilesRepository : JpaRepository<MediaFile, UUID> {
    fun findByEpisodeIdInOrderByUploadedAtAsc(episodeIds: Collection<UUID>): List<MediaFile>
    fun findByEpisodeIdAndKind(episodeId: UUID, kind: MediaKind): List<MediaFile>
    fun findByEpisodeIdAndMd5(episodeId: UUID, md5: String): List<MediaFile>
}

/** Where the browser sends a file: a resumable upload ([SignedUpload]) it starts at [url] with [headers], until [expires] */
data class MediaUploadDto(val id: UUID, val url: String, val headers: List<HeaderDto>, val expires: String)

// How long the browser has to start sending a file, and how long a link to download one, or ffprobe's, works
private val UPLOAD_FOR = Duration.ofHours(1)
private val DOWNLOAD_FOR = Duration.ofHours(1)
private val PROBE_FOR = Duration.ofMinutes(15)

private const val MAX_TITLE_LENGTH = 200
private const val MAX_NAME_LENGTH = 255

private fun uploadPath(id: UUID) = "uploads/$id"

// Downloaded as a file by its name, rather than played in the browser: in ASCII for old browsers, and as it is
private fun attachment(name: String): String {
    val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''${URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")}"
}

/** A file's name as it's kept: without the folders it was in, on one line */
private fun fileName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').trim().also {
        require(it.isNotEmpty() && it != "." && it != "..") { "A file needs its name" }
        require(it.length <= MAX_NAME_LENGTH) { "A file's name can be at most $MAX_NAME_LENGTH characters long" }
        require(it.none { char -> char.isISOControl() }) { "A file's name can't have control characters" }
    }

// Empty is none
private fun title(title: String?): String? = title?.trim()?.ifEmpty { null }?.also {
    require(it.length <= MAX_TITLE_LENGTH) { "A title can be at most $MAX_TITLE_LENGTH characters long" }
}

// Once the transaction that removed a file's row is in, or right away without one
private fun afterCommit(action: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
        override fun afterCommit() = action()
    })
}

/**
 * An episode's video and audio, uploaded from its page (Upload video and audio): the browser sends each file straight to
 * MetaOS's private bucket, under uploads/, with a signed URL, as pictures go (Artwork); then ffprobe reads what's in it
 * without downloading it, and it's copied inside the bucket to where it's kept, episode-media/<upload>/<name>, never
 * passing through MetaOS. It's the team's only: anyone else gets no link to it.
 */
@Component
@ConditionalOnStorage
class MediaFiles(
    private val storage: Storage,
    private val probe: MediaProbe,
    private val files: MediaFilesRepository,
    private val episodes: EpisodesRepository,
    transactionManager: PlatformTransactionManager,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun find(id: UUID) = requireNotNull(files.findByIdOrNull(id)) { "Video or audio file $id not found" }

    private fun Episode.label() = code ?: "\"$title\""

    // The one the episode has of a full kind, which a new one replaces only when asked
    private fun requireNoOther(episode: Episode, kind: MediaKind, replace: Boolean) {
        val had = files.findByEpisodeIdAndKind(checkNotNull(episode.id), kind).firstOrNull() ?: return
        require(replace) { "${episode.label()} has a ${kind.label} already, ${had.name}; replace it, or keep that one" }
    }

    /** A place in MetaOS's bucket for the episode's [kind] of file, of [contentType] and [size] bytes */
    fun upload(ref: String, kind: MediaKind, contentType: String, size: Long): MediaUploadDto {
        findEpisode(ref)
        require(MEDIA_TYPES[contentType] == kind.video) { "A ${kind.label} is ${typesOf(kind.video)}" }
        require(size in 1..MAX_MEDIA_BYTES) { "A file is up to $MAX_MEDIA_GB GB" }
        val id = UUID.randomUUID()
        val upload = storage.uploadUrl(uploadPath(id), contentType, UPLOAD_FOR)
        return MediaUploadDto(id, upload.url.toString(), upload.headers.map { (name, value) -> HeaderDto(name, value) }, upload.expires.toString())
    }

    /**
     * Makes an upload, of a file called [name], the episode's [kind] of file, which ffprobe has to find it is: video for
     * the videos, audio and no video for the audio. A full video or audio the episode has already is only replaced when
     * it's to be ([replace]), and a file it has already isn't added again. The upload goes either way: to try again, the
     * browser sends the file again.
     */
    fun add(ref: String, kind: MediaKind, upload: UUID, name: String, title: String?, replace: Boolean): MediaFile {
        val path = uploadPath(upload)
        try {
            val named = fileName(name)
            val titled = title(title)
            val episode = findEpisode(ref)
            if (kind.full) requireNoOther(episode, kind, replace)
            val uploaded = requireNotNull(storage.file(path)) { "$named didn't arrive in MetaOS's bucket; upload it again" }
            val contentType = uploaded.contentType
            require(contentType != null && MEDIA_TYPES[contentType] == kind.video) { "$named isn't ${typesOf(kind.video)}, which a ${kind.label} is" }
            require(uploaded.size <= MAX_MEDIA_BYTES) { "$named is more than $MAX_MEDIA_GB GB" }
            uploaded.md5?.let { md5 ->
                val same = files.findByEpisodeIdAndMd5(checkNotNull(episode.id), md5).firstOrNull()
                require(same == null) { "${episode.label()} has this file already: ${same!!.name}, its ${same.kind.label}" }
            }
            val probed = probe.probe(storage.downloadUrl(path, PROBE_FOR).toString(), named)
            if (kind.video) require(probed.info.hasVideo) { "$named has no video; upload it as audio" }
            else {
                require(probed.info.hasAudio) { "$named has no audio" }
                require(!probed.info.hasVideo) { "$named has video; upload it as the full video or a bonus video" }
            }
            val stored = storage.copy(path, "episode-media/$upload/$named", attachment(named))
            try {
                return checkNotNull(transaction.execute { save(ref, kind, titled, named, stored, probed, replace) })
            } catch (e: Exception) {
                runCatching { storage.delete(stored.path) }.onFailure { logger.warn("{} stays in the bucket", stored.path, it) }
                throw e
            }
        } finally {
            runCatching { storage.delete(path) }.onFailure { logger.warn("{} stays in the bucket", path, it) }
        }
    }

    // Asked again, as someone may have added one meanwhile
    private fun save(ref: String, kind: MediaKind, title: String?, name: String, stored: StoredFile, probed: Probed, replace: Boolean): MediaFile {
        val episode = findEpisode(ref)
        val episodeId = checkNotNull(episode.id)
        if (kind.full) {
            requireNoOther(episode, kind, replace)
            // Gone before the new one goes in, as Hibernate would insert first and an episode has only one
            files.findByEpisodeIdAndKind(episodeId, kind).forEach(::delete)
            files.flush()
        }
        val jwt = checkNotNull(currentJwt()) { "Not signed in" }
        return files.saveAndFlush(
            MediaFile(
                episodeId = episodeId,
                kind = kind,
                title = title,
                name = name,
                path = stored.path,
                contentType = checkNotNull(stored.contentType),
                size = stored.size,
                md5 = stored.md5,
                crc32c = stored.crc32c,
                uploaderId = checkNotNull(jwt.subject) { "The token doesn't say who's signed in" },
                uploader = jwt.getClaimAsString("preferred_username"),
                info = probed.info,
                probe = probed.json,
            )
        )
    }

    /** The video and audio of several episodes, in one query, the first uploaded first */
    fun of(episodes: Collection<Episode>): Map<Episode, List<MediaFile>> {
        val byId = files.findByEpisodeIdInOrderByUploadedAtAsc(episodes.mapNotNull { it.id }).groupBy { it.episodeId }
        return episodes.associateWith { byId[it.id].orEmpty() }
    }

    /**
     * Changes a file's [title], when one's given (empty clears it), and what it is, its [kind], which stays video or audio.
     * It becomes the full video or audio only while the episode has none.
     */
    @Transactional
    fun update(id: UUID, title: String?, kind: MediaKind?): MediaFile {
        val file = find(id)
        if (title != null) file.title = title(title)
        if (kind != null && kind != file.kind) {
            require(kind.video == file.kind.video) { "A ${file.kind.label} can't be a ${kind.label}: one is video, the other audio" }
            val other = files.findByEpisodeIdAndKind(file.episodeId, kind).firstOrNull()
            require(!kind.full || other == null) { "The episode has a ${kind.label} already, ${other?.name}; remove that one first" }
            file.kind = kind
        }
        return files.saveAndFlush(file)
    }

    /** Takes a file off its episode, and deletes it from the bucket; production's keeps it 30 days. The audit log keeps its row. */
    @Transactional
    fun remove(id: UUID): MediaFile = find(id).also(::delete)

    private fun delete(file: MediaFile) {
        files.delete(file)
        afterCommit {
            runCatching { storage.delete(file.path) }.onFailure { logger.warn("{} stays in the bucket", file.path, it) }
        }
    }

    /** A link that downloads the file, for an hour, e.g. for patreon-gateway */
    fun downloadUrl(file: MediaFile): URL = storage.downloadUrl(file.path, DOWNLOAD_FOR)
}

// The team's, as an episode's files are: anyone else gets none, and uploads and changes need an approved user's token
// with metaos:write. Read from the database, so the files are listed also while the bucket is off.
@Controller
class MediaFilesGraphQL(
    private val mediaFiles: ObjectProvider<MediaFiles>,
    private val repository: MediaFilesRepository,
    private val accounts: Accounts,
) {

    private fun mediaFiles() = checkNotNull(mediaFiles.ifAvailable) { "MetaOS has no bucket for video and audio (GOOGLE_STORAGE_KEY)" }

    @BatchMapping(typeName = "Episode", field = "media")
    fun episodeMedia(episodes: List<Episode>): Map<Episode, List<MediaFile>> {
        val byId = if (signedIn()) repository.findByEpisodeIdInOrderByUploadedAtAsc(episodes.mapNotNull { it.id }).groupBy { it.episodeId }
        else emptyMap()
        return episodes.associateWith { byId[it.id].orEmpty() }
    }

    // By their person's name once their account is linked, as comments' authors are
    @BatchMapping(typeName = "MediaFile", field = "uploadedBy")
    fun uploadedBy(files: List<MediaFile>): Map<MediaFile, String> {
        val people = accounts.people(files.map { it.uploaderId })
        return files.associateWith { people[it.uploaderId]?.name ?: it.uploader ?: "Someone" }
    }

    // Signed when it's asked for, as it works for an hour: the page asks for it as it's downloaded (mediaFile)
    @SchemaMapping(typeName = "MediaFile")
    fun downloadUrl(file: MediaFile): String? = mediaFiles.ifAvailable?.takeIf { signedIn() }?.downloadUrl(file)?.toString()

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun mediaFile(@Argument id: UUID): MediaFile? = repository.findByIdOrNull(id)

    // A Float, as GraphQL's Int stops at 2 GB
    @MutationMapping
    @PreAuthorize(WRITES)
    fun mediaUpload(@Argument ref: String, @Argument kind: MediaKind, @Argument contentType: String, @Argument size: Double): MediaUploadDto {
        require(size == Math.floor(size)) { "A file's size is in bytes" }
        return mediaFiles().upload(ref, kind, contentType, size.toLong())
    }

    @MutationMapping
    @PreAuthorize(WRITES)
    fun addEpisodeMedia(
        @Argument ref: String,
        @Argument kind: MediaKind,
        @Argument upload: UUID,
        @Argument name: String,
        @Argument title: String?,
        @Argument replace: Boolean,
    ): MediaFile = mediaFiles().add(ref, kind, upload, name, title, replace)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun updateEpisodeMedia(@Argument id: UUID, @Argument title: String?, @Argument kind: MediaKind?): MediaFile =
        mediaFiles().update(id, title, kind)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun removeEpisodeMedia(@Argument id: UUID): UUID = checkNotNull(mediaFiles().remove(id).id)
}
