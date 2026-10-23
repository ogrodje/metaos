package si.ogrodje.metaos

import io.micrometer.core.instrument.MeterRegistry
import net.koofr.api.auth.basic.HttpBasicAuthenticator
import net.koofr.api.http.Client
import net.koofr.api.http.Request
import net.koofr.api.http.errors.HttpException
import net.koofr.api.http.impl.basic.BasicRequest
import net.koofr.api.rest.v2.Api
import net.koofr.api.rest.v2.RSearch
import net.koofr.api.rest.v2.data.Files
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A folder another Koofr user shares with the bot - MetaOS works only in these */
data class KoofrPlace(val id: String, val name: String, val owner: String?, val writable: Boolean)

/** A file or folder in a place, by its path there ("/" is the shared folder itself) */
data class KoofrFile(
    val place: String,
    val path: String,
    val name: String,
    val folder: Boolean,
    val size: Long?,
    val modified: OffsetDateTime?,
    val contentType: String?,
)

/** An image, e.g. a thumbnail Koofr made of a photo */
class KoofrImage(val contentType: String, val bytes: ByteArray)

/** Koofr, as Ogrodje's bot account sees it. Places are Koofr mounts; paths are in a place. */
interface Koofr {
    /** The folders shared with the bot */
    fun places(): List<KoofrPlace>

    /** A file or folder, or null when there's none at the path */
    fun file(place: String, path: String): KoofrFile?

    /** What's directly in a folder */
    fun list(place: String, folder: String): List<KoofrFile>

    /** Files and folders by name, in one place or in all the bot can see, most relevant first */
    fun search(text: String, place: String?, limit: Int): List<KoofrFile>

    /** The start of a file, at most [maxBytes] */
    fun read(place: String, path: String, offset: Long, maxBytes: Int): ByteArray

    /**
     * A thumbnail Koofr makes of an image, as wide as its [size] says: small (64 px), shmedium (128), medium (384),
     * large (512) or huge
     */
    fun thumbnail(place: String, path: String, size: String): KoofrImage

    /** A new file in a folder; one of the same name is only replaced when [overwrite] is set, and Koofr keeps it as a version */
    fun upload(place: String, folder: String, name: String, contentType: String, content: InputStream, size: Long, overwrite: Boolean): KoofrFile

    fun createFolder(place: String, parent: String, name: String)

    /** Moves or renames a file or folder, also into another place */
    fun move(place: String, path: String, toPlace: String, toPath: String)
}

/** Koofr is off until the bot's app password is set (KOOFR_APP_PASSWORD) */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.koofr.app-password:}' != ''")
annotation class ConditionalOnKoofr

// The SDK's own client waits forever on a stalled connection
private class TimeoutClient : Client<BasicRequest>() {
    override fun createRequest(url: String, method: Request.Method): BasicRequest {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.requestMethod = method.name
        connection.doInput = true
        connection.doOutput = method == Request.Method.PUT || method == Request.Method.POST
        return object : BasicRequest(connection) {}.apply { addHeader("X-Koofr-Version", "2.1") }
    }
}

private fun millis(value: Long?) = value?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC) }

private fun Files.File.toKoofrFile(place: String, path: String) =
    KoofrFile(place, path, name, type == Files.File.TYPE_DIR, size, millis(modified), contentType)

private fun join(folder: String, name: String) = folder.trimEnd('/') + "/" + name

@Component
@ConditionalOnKoofr
class KoofrStorage(
    @Value("\${metaos.koofr.email}") email: String,
    @Value("\${metaos.koofr.app-password}") appPassword: String,
) : Koofr {

    private val api = Api("https://app.koofr.net/api/v2", HttpBasicAuthenticator(email, appPassword), TimeoutClient())

    private fun files(place: String) = api.mounts().mount(place).files()

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: HttpException.Unauthorized) {
        throw IllegalStateException(
            "Koofr doesn't accept the bot's app password. Make a new one at https://app.koofr.net/app/admin/preferences/password " +
                "(signed in as the bot), put it in KOOFR_APP_PASSWORD (.envrc, then k8s/set_secrets.sh) and restart MetaOS", e
        )
    }

    // Mounts of type "import" are the folders other users share with the bot
    override fun places(): List<KoofrPlace> = call {
        api.mounts().get().mounts.filter { it.type == "import" }.map { mount ->
            KoofrPlace(
                id = mount.id, name = mount.name,
                owner = mount.owner?.let { it.email ?: it.name },
                writable = mount.permissions.orEmpty().any { (permission, granted) -> permission.equals("write", ignoreCase = true) && granted == true },
            )
        }
    }

    override fun file(place: String, path: String): KoofrFile? = call {
        try {
            files(place).info(path).toKoofrFile(place, path)
        } catch (_: HttpException.NotFound) {
            null
        }
    }

    override fun list(place: String, folder: String): List<KoofrFile> = call {
        files(place).list(folder).files.orEmpty().map { it.toKoofrFile(place, join(folder, it.name)) }
    }

    override fun search(text: String, place: String?, limit: Int): List<KoofrFile> {
        val query = RSearch.QueryParameters().apply {
            this.query = text
            this.limit = limit
            this.mountId = place
        }
        return call {
            api.search().query(query).hits.orEmpty().map { hit ->
                KoofrFile(hit.mountId, hit.path, hit.name, hit.type == Files.File.TYPE_DIR, hit.size, millis(hit.modified), hit.contentType)
            }
        }
    }

    // Koofr sends a file from its start, so the bytes before offset are read past, not kept
    override fun read(place: String, path: String, offset: Long, maxBytes: Int): ByteArray = call {
        files(place).download(path, null).downloadStream.use { it.skipNBytes(offset); it.readNBytes(maxBytes) }
    }

    override fun thumbnail(place: String, path: String, size: String): KoofrImage = call {
        val download = files(place).download(path, Files.DownloadOptions().apply { thumbnailSize = size })
        try {
            KoofrImage(download.contentType ?: "image/jpeg", download.downloadStream.readAllBytes())
        } finally {
            download.close()
        }
    }

    override fun upload(place: String, folder: String, name: String, contentType: String, content: InputStream, size: Long, overwrite: Boolean): KoofrFile {
        // Without either, Koofr would pick another name ("name (1).txt") for a file that's already there
        val options = Files.UploadOptions().apply { if (overwrite) forceOverwrite = true else noRename = true }
        return call {
            try {
                files(place).upload(folder, name, contentType, size, content, options)
                    .toKoofrFile(place, join(folder, name))
            } catch (_: HttpException.Conflict) {
                throw IllegalArgumentException("${join(folder, name)} is already there; overwrite replaces it, keeping the old one as a version")
            }
        }
    }

    override fun createFolder(place: String, parent: String, name: String) = call { files(place).createFolder(parent, name) }

    override fun move(place: String, path: String, toPlace: String, toPath: String) {
        call { files(place).move(path, toPlace, toPath) }
    }
}

@Component
@ConditionalOnKoofr
class KoofrHealthIndicator(private val koofr: Koofr, registry: MeterRegistry) : ConnectionHealthIndicator("koofr", registry) {
    override fun probe() = "places" to koofr.places().joinToString { it.name }.ifEmpty { "none shared with the bot yet" }
}
