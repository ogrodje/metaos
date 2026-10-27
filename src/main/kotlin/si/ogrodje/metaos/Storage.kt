package si.ogrodje.metaos

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.http.HttpTransportOptions
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.HttpMethod
import com.google.cloud.storage.Storage.BlobListOption
import com.google.cloud.storage.Storage.BlobTargetOption
import com.google.cloud.storage.Storage.BlobWriteOption
import com.google.cloud.storage.Storage.CopyRequest
import com.google.cloud.storage.Storage.SignUrlOption
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.channels.Channels
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * A file in MetaOS's bucket, by its path there, e.g. "episodes/S05E08/cover.png", with its hashes in hex as Cloud Storage
 * has them: the same content has the same [md5]
 */
data class StoredFile(
    val path: String,
    val size: Long,
    val contentType: String?,
    val updated: OffsetDateTime?,
    val md5: String? = null,
    val crc32c: String? = null,
)

/**
 * A resumable upload straight to the bucket, without MetaOS in between - how big files get there from the browser:
 * POST to [url] with exactly these [headers] and no body, then PUT the file to the response's Location, all at once or
 * in chunks (https://cloud.google.com/storage/docs/performing-resumable-uploads). It only starts until [expires].
 * When another file got to the path first, the upload still starts, but the PUT that finishes it gets a 412.
 */
data class SignedUpload(val url: URL, val headers: Map<String, String>, val expires: Instant)

/**
 * MetaOS's own files - pictures, videos, anything - in Google Cloud Storage: a bucket per environment, used as a service
 * account that can use only that bucket and the environment's public one (Media, infra/gcp). Folders are only
 * the slashes in paths.
 */
interface Storage {
    val bucket: String

    /** A file, or null when there's none at the path */
    fun file(path: String): StoredFile?

    /** The files in a folder and its subfolders, by path; the whole bucket for "" */
    fun list(folder: String, limit: Int): List<StoredFile>

    /**
     * Stores a file, streaming it; one already at the path is only replaced when [overwrite] is set. [cacheControl] is
     * what it's served with, e.g. from the public bucket.
     */
    fun upload(path: String, contentType: String, content: InputStream, overwrite: Boolean, cacheControl: String? = null): StoredFile

    /** The start of a file, at most [maxBytes] */
    fun read(path: String, maxBytes: Int): ByteArray

    /**
     * Copies a file to a path that has none, inside the bucket, without downloading it, e.g. an upload to where it's kept.
     * [contentDisposition] is what it's downloaded with, e.g. attachment, so a browser saves it rather than plays it.
     */
    fun copy(from: String, to: String, contentDisposition: String? = null): StoredFile

    /** Lets whoever has it upload a file that isn't at the path yet, for [validFor] */
    fun uploadUrl(path: String, contentType: String, validFor: Duration): SignedUpload

    /** Lets whoever has it download a file, for [validFor] */
    fun downloadUrl(path: String, validFor: Duration): URL

    /** False when there was no file; the production bucket keeps a deleted one recoverable for 30 days */
    fun delete(path: String): Boolean
}

/** "a/b", from "a/b", "/a/b" or "a//b/"; no . or .. */
fun storagePath(path: String): String {
    val parts = path.split('/').filter { it.isNotEmpty() }
    require(parts.isNotEmpty()) { "A file needs a path" }
    require(parts.none { it == "." || it == ".." }) { "$path: no . or .. in storage paths" }
    require(path.none { it.isISOControl() }) { "$path: no control characters in storage paths" }
    return parts.joinToString("/").also { require(it.toByteArray().size <= 1024) { "Storage paths are 1024 bytes at most" } }
}

/** Storage is off until its service account's key is set (just storage-key) */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.storage.key:}' != ''")
annotation class ConditionalOnStorage

// What V4 signatures allow
private val MAX_SIGNED = Duration.ofDays(7)

private fun Blob.toStoredFile() =
    StoredFile(name, size ?: 0, contentType, updateTimeOffsetDateTime, md5?.let { md5ToHexString }, crc32c?.let { crc32cToHexString })

@Component
@ConditionalOnStorage
class GoogleCloudStorage(
    @Value("\${metaos.storage.key}") key: String,
    @Value("\${metaos.storage.bucket}") override val bucket: String,
) : Storage {

    private val credentials = try {
        ServiceAccountCredentials.fromStream(key.byteInputStream())
    } catch (e: IOException) {
        throw IllegalArgumentException("GOOGLE_STORAGE_KEY isn't a service account's key; just storage-key makes one", e)
    }

    init {
        require(bucket.isNotBlank()) { "METAOS_STORAGE_BUCKET is missing: the bucket ${credentials.clientEmail} may use" }
    }

    private val storage = StorageOptions.newBuilder()
        .setCredentials(credentials)
        .setProjectId(credentials.projectId)
        .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(10_000).setReadTimeout(60_000).build())
        .build().service

    private fun id(path: String) = BlobId.of(bucket, storagePath(path))

    // A deleted key fails as Google turns it into an access token
    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        if (generateSequence<Throwable>(e) { it.cause }.any { "invalid_grant" in it.message.orEmpty() }) throw IllegalStateException(
            "Google no longer accepts key ${credentials.privateKeyId} of ${credentials.clientEmail}. Make a new one with " +
                "just storage-key, put it in GOOGLE_STORAGE_KEY (.envrc, then k8s/set_secrets.sh) and restart MetaOS", e
        )
        throw e
    }

    override fun file(path: String): StoredFile? = call { storage.get(id(path))?.toStoredFile() }

    // Leaves out the empty "folder/" objects the Cloud Console makes
    override fun list(folder: String, limit: Int): List<StoredFile> {
        val prefix = if (folder.trim('/').isEmpty()) "" else storagePath(folder) + "/"
        return call {
            storage.list(bucket, BlobListOption.prefix(prefix), BlobListOption.pageSize(minOf(limit, 1000).toLong()))
                .iterateAll().asSequence().filterNot { it.name.endsWith("/") }.take(limit).map { it.toStoredFile() }.toList()
        }
    }

    // A resumable upload, which the client retries chunk by chunk
    override fun upload(path: String, contentType: String, content: InputStream, overwrite: Boolean, cacheControl: String?): StoredFile {
        val blob = BlobInfo.newBuilder(id(path)).setContentType(contentType).setCacheControl(cacheControl).build()
        // Without it GCS would replace a file that's already there
        val options = if (overwrite) emptyArray() else arrayOf(BlobWriteOption.doesNotExist())
        return call {
            try {
                storage.createFrom(blob, content, *options).toStoredFile()
            } catch (e: StorageException) {
                if (e.code == 412) throw IllegalArgumentException("${blob.name} is already there; overwrite replaces it")
                throw e
            }
        }
    }

    override fun read(path: String, maxBytes: Int): ByteArray = call {
        try {
            storage.reader(id(path)).limit(maxBytes.toLong()).use { Channels.newInputStream(it).readAllBytes() }
        } catch (e: IOException) {
            // How the reader hands on what Google answered
            val answer = e.cause as? StorageException ?: throw e
            if (answer.code == 404) throw IllegalArgumentException("There's no file at ${storagePath(path)}")
            throw answer
        }
    }

    // A rewrite, which Cloud Storage does in one go inside a bucket, as the file stays where it is and in its class
    override fun copy(from: String, to: String, contentDisposition: String?): StoredFile = call {
        val source = storage.get(id(from)) ?: throw IllegalArgumentException("There's no file at ${storagePath(from)}")
        val target = BlobInfo.newBuilder(id(to)).setContentType(source.contentType).setContentDisposition(contentDisposition).build()
        try {
            storage.copy(CopyRequest.newBuilder().setSource(source.blobId).setTarget(target, BlobTargetOption.doesNotExist()).build())
                .result.toStoredFile()
        } catch (e: StorageException) {
            if (e.code == 412) throw IllegalArgumentException("${target.name} is already there")
            throw e
        }
    }

    // Signed with the key itself, without asking Google
    private fun sign(path: String, validFor: Duration, method: HttpMethod, headers: Map<String, String> = emptyMap()): URL {
        require(!validFor.isNegative && !validFor.isZero && validFor <= MAX_SIGNED) { "Signed URLs are valid for up to 7 days" }
        return storage.signUrl(
            BlobInfo.newBuilder(id(path)).build(), validFor.toSeconds(), TimeUnit.SECONDS,
            SignUrlOption.httpMethod(method), SignUrlOption.withV4Signature(), SignUrlOption.withExtHeaders(headers),
        )
    }

    // Signed headers, so the browser can't pick another content type or replace a file that's there
    override fun uploadUrl(path: String, contentType: String, validFor: Duration): SignedUpload {
        val headers = mapOf("Content-Type" to contentType, "x-goog-resumable" to "start", "x-goog-if-generation-match" to "0")
        return SignedUpload(sign(path, validFor, HttpMethod.POST, headers), headers, Instant.now() + validFor)
    }

    override fun downloadUrl(path: String, validFor: Duration): URL = sign(path, validFor, HttpMethod.GET)

    override fun delete(path: String): Boolean = call { storage.delete(id(path)) }
}

/**
 * MetaOS's files for everyone, e.g. episodes' thumbnails (Images.kt): those in the environment's public bucket
 * (infra/gcp), which anyone can read at [url] + their path, e.g. https://media.ogrodje.si/images/... in
 * production, through Cloudflare, and straight from Google in development.
 */
class Media(val storage: Storage, url: String) {
    val url = url.trimEnd('/')

    fun url(path: String) = "$url/${storagePath(path)}"
}

/** The public bucket is off until there's a key (ConditionalOnStorage) and the bucket's name (METAOS_MEDIA_BUCKET) */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.storage.key:}' != '' and '\${metaos.media.bucket:}' != ''")
annotation class ConditionalOnMedia

@Configuration
class MediaConfig {

    // As the same service account, which may use both of its environment's buckets; served by Google itself unless
    // METAOS_MEDIA_URL says otherwise
    @Bean
    @ConditionalOnMedia
    fun media(
        @Value("\${metaos.storage.key}") key: String,
        @Value("\${metaos.media.bucket}") bucket: String,
        @Value("\${metaos.media.url:}") url: String,
    ) = Media(GoogleCloudStorage(key, bucket), url.ifBlank { "https://storage.googleapis.com/$bucket" })
}

@Component
@ConditionalOnStorage
class StorageHealthIndicator(
    private val storage: Storage,
    registry: MeterRegistry,
    private val media: Media? = null,
) : ConnectionHealthIndicator("storage", registry) {
    override fun probe(): Pair<String, String> {
        storage.list("", 1)
        media?.storage?.list("", 1)
        return "bucket" to listOfNotNull(storage.bucket, media?.storage?.bucket).joinToString()
    }
}
