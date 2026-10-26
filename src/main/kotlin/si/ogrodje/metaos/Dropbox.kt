package si.ogrodje.metaos

import com.dropbox.core.DbxException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.PathRootErrorException
import com.dropbox.core.http.StandardHttpRequestor
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.oauth.DbxOAuthException
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.common.PathRoot
import com.dropbox.core.v2.files.CreateFolderErrorException
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import com.dropbox.core.v2.files.GetMetadataErrorException
import com.dropbox.core.v2.files.ListFolderErrorException
import com.dropbox.core.v2.files.LookupError
import com.dropbox.core.v2.files.Metadata
import com.dropbox.core.v2.files.RelocationErrorException
import com.dropbox.core.v2.files.SearchOptions
import com.dropbox.core.v2.files.ThumbnailSize
import com.dropbox.core.v2.files.UploadErrorException
import com.dropbox.core.v2.files.WriteMode
import com.dropbox.core.v2.sharing.AccessLevel
import com.dropbox.core.v2.sharing.CreateSharedLinkWithSettingsErrorException
import com.dropbox.core.v2.sharing.LinkAudience
import com.dropbox.core.v2.sharing.SharedLinkSettings
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLConnection
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/**
 * A folder another Dropbox user shares with the bot - MetaOS works only in these. Its [path] is where the bot has it in
 * its Dropbox, and null until it's added there; only then does search see it.
 */
data class DropboxPlace(val id: String, val name: String, val owner: String?, val writable: Boolean, val path: String?)

/**
 * A file or folder in a place, by its path there ("/" is the shared folder itself), and by Dropbox's [id] for it
 * ("id:..."), which stays with it when it's moved or renamed; the shared folder itself has none. A file has Dropbox's
 * hash of what's in it ([dropboxContentHash]).
 */
data class DropboxFile(
    val place: String,
    val path: String,
    val name: String,
    val folder: Boolean,
    val size: Long?,
    val modified: OffsetDateTime?,
    val contentType: String?,
    val id: String? = null,
    val contentHash: String? = null,
)

// What Dropbox hashes a file by
private const val HASH_BLOCK = 4 * 1024 * 1024

/**
 * Dropbox's hash of a file's content, as its files have it: the SHA-256 of the SHA-256s of its 4 MB blocks
 * (https://www.dropbox.com/developers/reference/content-hash). The same content is the same file, whatever its name.
 */
fun dropboxContentHash(content: ByteArray): String {
    val blocks = MessageDigest.getInstance("SHA-256")
    for (start in content.indices step HASH_BLOCK) {
        blocks.update(MessageDigest.getInstance("SHA-256").digest(content.copyOfRange(start, minOf(start + HASH_BLOCK, content.size))))
    }
    return HexFormat.of().formatHex(blocks.digest())
}

/** An image, e.g. a thumbnail Dropbox made of a picture */
class DropboxImage(val contentType: String, val bytes: ByteArray)

/** Dropbox, as Ogrodje's bot account sees it. Places are shared folders, by ID; paths are in a place. */
interface Dropbox {
    /** The folders shared with the bot */
    fun places(): List<DropboxPlace>

    /** A file or folder, or null when there's none at the path */
    fun file(place: String, path: String): DropboxFile?

    /** A file or folder by its ID, wherever it is in the place now; null once it's gone, or not in the place anymore */
    fun fileById(place: String, id: String): DropboxFile?

    /** What's directly in a folder */
    fun list(place: String, folder: String): List<DropboxFile>

    /** Every folder in a place, all the way down: their paths by their IDs */
    fun folders(place: String): Map<String, String>

    /**
     * A link to a folder or file, by its ID, that only those with access to it can open, wherever they have it in their
     * Dropbox: one the bot made before, or a new one. Never one for anyone else; when Dropbox won't make it for members
     * only, it fails.
     */
    fun membersLink(id: String): String

    /** Files and folders by name, in one place or in all those in the bot's Dropbox, most relevant first */
    fun search(text: String, place: String?, limit: Int): List<DropboxFile>

    /** The start of a file, at most [maxBytes] */
    fun read(place: String, path: String, maxBytes: Int): ByteArray

    /**
     * A link anyone who has it can download a file by, for four hours: how a big file, e.g. an episode's video, goes to
     * where MetaOS doesn't stream it through itself (patreon-gateway)
     */
    fun temporaryLink(place: String, path: String): String

    /**
     * A JPEG Dropbox makes of an image (jpg, png, gif, webp, tiff, bmp, up to 20 MB), as big as fits in its [size],
     * named as Dropbox names them: w256h256, w640h480, w1024h768, w2048h1536, ...
     */
    fun thumbnail(place: String, path: String, size: String): DropboxImage

    /**
     * A new file in a folder; one of the same name is only replaced when [overwrite] is set, and Dropbox keeps it as a
     * version. With [rename], Dropbox gives the new one another name instead, e.g. "ogrodje-zidar-thumb1 (1).png".
     */
    fun upload(place: String, folder: String, name: String, content: ByteArray, overwrite: Boolean, rename: Boolean = false): DropboxFile

    fun createFolder(place: String, parent: String, name: String)

    /** Moves or renames a file or folder, also into another place; never replaces what's at [toPath] */
    fun move(place: String, path: String, toPlace: String, toPath: String)
}

/** Dropbox is off until the bot's refresh token is set (DROPBOX_REFRESH_TOKEN, from dropbox/refresh_token.sh) */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.dropbox.refresh-token:}' != ''")
annotation class ConditionalOnDropbox

// A shared folder's ID is its namespace's, so this finds it whether or not, and wherever, the bot has it in its Dropbox
private fun at(place: String, path: String) = "ns:$place" + if (path == "/") "" else path

private fun join(folder: String, name: String) = folder.trimEnd('/') + "/" + name

private fun Metadata.toDropboxFile(place: String, path: String) = when (this) {
    is FileMetadata -> DropboxFile(
        place, path, name, false, size, serverModified.toInstant().atOffset(ZoneOffset.UTC), URLConnection.guessContentTypeFromName(name), id,
        contentHash,
    )
    is FolderMetadata -> DropboxFile(place, path, name, true, null, null, null, id)
    else -> DropboxFile(place, path, name, false, null, null, null)
}

// The shared folder it's in: Metadata's own parentSharedFolderId is deprecated for its sharing info's
private val Metadata.parentSharedFolder: String?
    get() = when (this) {
        is FileMetadata -> sharingInfo?.parentSharedFolderId
        is FolderMetadata -> sharingInfo?.parentSharedFolderId
        else -> null
    }

private fun LookupError.notFound() = isNotFound || isNotFolder || isNotFile

// Links that open only for those with access to what they're to
private val MEMBERS_ONLY = setOf(LinkAudience.NO_ONE, LinkAudience.MEMBERS)

// Dropbox-API-Arg is a header, so its JSON has to be ASCII
private val headerJson = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()

@Component
@ConditionalOnDropbox
class DropboxStorage(
    @Value("\${metaos.dropbox.app-key}") appKey: String,
    @Value("\${metaos.dropbox.app-secret}") appSecret: String,
    @Value("\${metaos.dropbox.refresh-token}") refreshToken: String,
) : Dropbox {

    // No access token yet: the client gets one with the refresh token before its first call, and again when it expires.
    // When Dropbox refuses the refresh token, the client goes on with this one, which Dropbox refuses as it does an
    // expired token (401); an empty one gets a 400 instead.
    private val credential = DbxCredential("none", 0L, refreshToken, appKey, appSecret)

    private val client = DbxClientV2(
        DbxRequestConfig.newBuilder("Ogrodje MetaOS").withHttpRequestor(
            StandardHttpRequestor(
                StandardHttpRequestor.Config.builder().withConnectTimeout(10, TimeUnit.SECONDS).withReadTimeout(60, TimeUnit.SECONDS).build()
            )
        ).build(),
        credential,
    )

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: DbxOAuthException) {
        throw rejected(e)
    } catch (e: InvalidAccessTokenException) {
        val missing = e.authError?.takeIf { it.isMissingScope }?.missingScopeValue?.requiredScope ?: throw rejected(e)
        throw IllegalStateException(
            "The bot's Dropbox token can't do that without $missing. Tick it on the Dropbox app's Permissions tab, make a " +
                "new token with dropbox/refresh_token.sh, put it in DROPBOX_REFRESH_TOKEN (.envrc, then k8s/set_secrets.sh) " +
                "and restart MetaOS", e
        )
    }

    private fun rejected(e: DbxException?) = IllegalStateException(
        "Dropbox doesn't accept the bot's refresh token, or MetaOS's app key and secret. Make a new token with " +
            "dropbox/refresh_token.sh, put it in DROPBOX_REFRESH_TOKEN (.envrc, then k8s/set_secrets.sh) and restart MetaOS", e
    )

    override fun places(): List<DropboxPlace> = call {
        var result = client.sharing().listFolders()
        val folders = result.entries.toMutableList()
        while (result.cursor != null) {
            result = client.sharing().listFoldersContinue(result.cursor)
            folders += result.entries
        }
        folders.filter { it.accessType != AccessLevel.NO_ACCESS }.map { folder ->
            DropboxPlace(
                id = folder.sharedFolderId, name = folder.name,
                owner = folder.ownerDisplayNames?.joinToString()?.ifEmpty { null },
                writable = folder.accessType == AccessLevel.OWNER || folder.accessType == AccessLevel.EDITOR,
                path = folder.pathDisplay,
            )
        }
    }

    // Dropbox has no metadata for the root of a folder
    override fun file(place: String, path: String): DropboxFile? = call {
        if (path == "/") return@call places().firstOrNull { it.id == place }?.let { DropboxFile(place, "/", it.name, true, null, null, null) }
        try {
            client.files().getMetadata(at(place, path)).toDropboxFile(place, path)
        } catch (e: GetMetadataErrorException) {
            if (e.errorValue.isPath && e.errorValue.pathValue.notFound()) null else throw e
        }
    }

    // With the place as the root, Dropbox tells the path in it
    override fun fileById(place: String, id: String): DropboxFile? = call {
        try {
            val metadata = client.withPathRoot(PathRoot.namespaceId(place)).files().getMetadata(id)
            metadata.pathDisplay?.takeIf { metadata.parentSharedFolder == place }?.let { metadata.toDropboxFile(place, it) }
        } catch (e: GetMetadataErrorException) {
            if (e.errorValue.isPath && e.errorValue.pathValue.notFound()) null else throw e
        } catch (_: PathRootErrorException) {
            null
        }
    }

    override fun list(place: String, folder: String): List<DropboxFile> = call {
        var result = try {
            client.files().listFolder(at(place, folder))
        } catch (e: ListFolderErrorException) {
            if (e.errorValue.isPath && e.errorValue.pathValue.notFound()) throw IllegalArgumentException("There's no folder $folder in Dropbox")
            throw e
        }
        val entries = result.entries.toMutableList()
        while (result.hasMore) {
            result = client.files().listFolderContinue(result.cursor)
            entries += result.entries
        }
        entries.map { it.toDropboxFile(place, join(folder, it.name)) }
    }

    // With the place as the root, paths are in it
    override fun folders(place: String): Map<String, String> = call {
        val files = client.withPathRoot(PathRoot.namespaceId(place)).files()
        var result = files.listFolderBuilder("").withRecursive(true).start()
        val entries = result.entries.toMutableList()
        while (result.hasMore) {
            result = files.listFolderContinue(result.cursor)
            entries += result.entries
        }
        entries.filterIsInstance<FolderMetadata>().mapNotNull { folder -> folder.pathDisplay?.let { folder.id to it } }.toMap()
    }

    // A link for no_one gives no access of its own: those who have it open it with what access they have, wherever they
    // have the folder. Members, which Dropbox won't make for a new link, is the same. Dropbox keeps a link working when
    // what it's to is moved or renamed.
    override fun membersLink(id: String): String = call {
        val sharing = client.sharing()
        val made = sharing.listSharedLinksBuilder().withPath(id).withDirectOnly(true).start().links
        made.firstOrNull { it.linkPermissions.effectiveAudience in MEMBERS_ONLY }?.let { return@call it.url }
        val link = try {
            sharing.createSharedLinkWithSettings(id, SharedLinkSettings.newBuilder().withAudience(LinkAudience.NO_ONE).build())
        } catch (e: CreateSharedLinkWithSettingsErrorException) {
            val error = e.errorValue
            when {
                error.isSettingsError -> throw IllegalStateException("Dropbox won't make the bot a link for members only (${error.settingsErrorValue})", e)
                error.isSharedLinkAlreadyExists -> throw IllegalStateException("There's a link to it already, and not for members only", e)
                else -> throw e
            }
        }
        // Should Dropbox have made one for more than members after all, it's taken back
        if (link.linkPermissions.effectiveAudience !in MEMBERS_ONLY) {
            sharing.revokeSharedLink(link.url)
            throw IllegalStateException("Dropbox made a link for ${link.linkPermissions.effectiveAudience} rather than members only; MetaOS took it back")
        }
        link.url
    }

    // Search finds files by where they are in the bot's Dropbox, so what it finds is mapped back to the shared folders
    // there, the innermost first; the rest is the bot's own
    override fun search(text: String, place: String?, limit: Int): List<DropboxFile> = call {
        val places = places().filter { it.path != null }.sortedByDescending { it.path!!.length }
        val within = place?.let { id ->
            requireNotNull(places.firstOrNull { it.id == id }?.path) { "$id isn't in the bot's Dropbox yet, so search can't see it" }
        }
        val options = SearchOptions.newBuilder().withPath(within).withFilenameOnly(true).withMaxResults(limit * 2L).build()
        client.files().searchV2Builder(text).withOptions(options).start().matches.mapNotNull { match ->
            val metadata = match.metadata.takeIf { it.isMetadata }?.metadataValue ?: return@mapNotNull null
            val found = metadata.pathDisplay ?: return@mapNotNull null
            val shared = places.firstOrNull { found.equals(it.path, ignoreCase = true) || found.lowercase().startsWith(it.path!!.lowercase() + "/") }
                ?: return@mapNotNull null
            metadata.toDropboxFile(shared.id, found.substring(shared.path!!.length).ifEmpty { "/" })
        }.take(limit)
    }

    override fun read(place: String, path: String, maxBytes: Int): ByteArray = call {
        val download = client.files().download(at(place, path))
        try {
            download.inputStream.readNBytes(maxBytes)
        } finally {
            download.close()
        }
    }

    override fun temporaryLink(place: String, path: String): String = call {
        client.files().getTemporaryLink(at(place, path)).link
    }

    // Not through the SDK: since 7.1.0 it sends get_thumbnail_v2 a quality, which Dropbox refuses ("unexpected key
    // 'quality'"). With the SDK's access token, which it refreshes on the credential.
    override fun thumbnail(place: String, path: String, size: String): DropboxImage = call {
        val arg = headerJson.writeValueAsString(
            mapOf(
                "resource" to mapOf(".tag" to "path", "path" to at(place, path)),
                "format" to "jpeg", "size" to ThumbnailSize.valueOf(size.uppercase()).name.lowercase(), "mode" to "strict",
            )
        )
        fun send(): HttpResponse<ByteArray> {
            if (credential.aboutToExpire()) client.refreshAccessToken()
            val request = HttpRequest.newBuilder(URI("https://content.dropboxapi.com/2/files/get_thumbnail_v2"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer ${credential.accessToken}").header("Dropbox-API-Arg", arg)
                .POST(HttpRequest.BodyPublishers.noBody()).build()
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        }
        // Once more with a new access token, as the SDK does, when Dropbox has dropped the one it had
        var response = send()
        if (response.statusCode() == 401) {
            client.refreshAccessToken()
            response = send()
        }
        when (response.statusCode()) {
            // What was asked for: Dropbox sends it as application/octet-stream
            200 -> DropboxImage("image/jpeg", response.body())
            401 -> throw rejected(null)
            // e.g. unsupported_extension/.., path/not_found/..
            409 -> {
                val summary = headerJson.readTree(response.body()).path("error_summary").asString().trimEnd('.', '/')
                throw IllegalArgumentException("Dropbox can't make a picture of $path: ${summary.replace('/', ' ').replace('_', ' ')}")
            }
            else -> throw IllegalStateException("Dropbox answered ${response.statusCode()} for a picture of $path: ${response.body().toString(Charsets.UTF_8)}")
        }
    }

    // Without autorename Dropbox fails on a file that's already there, where it would otherwise pick another name; the
    // one it picked is the file's
    override fun upload(place: String, folder: String, name: String, content: ByteArray, overwrite: Boolean, rename: Boolean): DropboxFile = call {
        try {
            val saved = client.files().uploadBuilder(at(place, join(folder, name)))
                .withMode(if (overwrite) WriteMode.OVERWRITE else WriteMode.ADD).withAutorename(rename)
                .uploadAndFinish(ByteArrayInputStream(content))
            saved.toDropboxFile(place, join(folder, saved.name))
        } catch (e: UploadErrorException) {
            if (e.errorValue.isPath && e.errorValue.pathValue.reason.isConflict)
                throw IllegalArgumentException("${join(folder, name)} is already there; overwrite replaces it, keeping the old one as a version")
            throw e
        }
    }

    override fun createFolder(place: String, parent: String, name: String) {
        call {
            try {
                client.files().createFolderV2(at(place, join(parent, name)), false)
            } catch (e: CreateFolderErrorException) {
                if (e.errorValue.isPath && e.errorValue.pathValue.isConflict) throw IllegalArgumentException("${join(parent, name)} is already there")
                throw e
            }
        }
    }

    override fun move(place: String, path: String, toPlace: String, toPath: String) {
        call {
            try {
                client.files().moveV2Builder(at(place, path), at(toPlace, toPath)).withAutorename(false).start()
            } catch (e: RelocationErrorException) {
                if (e.errorValue.isTo && e.errorValue.toValue.isConflict) throw IllegalArgumentException("$toPath is already there")
                throw e
            }
        }
    }
}

@Component
@ConditionalOnDropbox
class DropboxHealthIndicator(private val dropbox: Dropbox, registry: MeterRegistry) : ConnectionHealthIndicator("dropbox", registry) {
    override fun probe() = "places" to dropbox.places().joinToString { it.name }.ifEmpty { "none shared with the bot yet" }
}
