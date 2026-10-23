package si.ogrodje.metaos

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.drive.model.File
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.io.EOFException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

const val FOLDER_TYPE = "application/vnd.google-apps.folder"
const val DOCUMENT_TYPE = "application/vnd.google-apps.document"

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val webViewLink: String? = null,
    val modifiedTime: OffsetDateTime? = null,
    val trashed: Boolean = false,
)

/** Google Drive, as Ogrodje's bot account sees it: the Ogrodje folder and whatever else is shared with the bot. */
interface Drive {
    /** The Ogrodje folder - MetaOS works only with what's inside it */
    val rootFolderId: String

    /** A file or folder, or null when it doesn't exist, is in the trash or the bot can't see it */
    fun file(id: String): DriveFile?

    /** The files and folders directly in a folder, by name */
    fun children(folderId: String): List<DriveFile>

    /** A Google Doc as Markdown */
    fun markdown(documentId: String, offset: Long, maxBytes: Int): ByteArray

    /** The Google Docs with the words in their title or text, wherever they are, most relevant first; read page by page */
    fun searchDocuments(text: String): Sequence<DriveFile>
}

/** Google Drive is off until the bot's Drive refresh token is set (google/refresh_token.sh) */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.google.drive-refresh-token:}' != ''")
annotation class ConditionalOnDrive

private const val FILE_FIELDS = "id, name, mimeType, parents, webViewLink, modifiedTime, trashed"

// Drive file IDs go into search queries
private val DRIVE_ID = Regex("""[\w-]+""")

// Text in search queries is between single quotes
private fun quoted(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

private fun File.toDriveFile() = DriveFile(
    id = id, name = name, mimeType = mimeType, parents = parents.orEmpty(), webViewLink = webViewLink,
    modifiedTime = modifiedTime?.let { Instant.ofEpochMilli(it.value).atOffset(ZoneOffset.UTC) },
    trashed = trashed == true,
)

@Component
@ConditionalOnDrive
class GoogleDrive(
    @Value("\${metaos.google.client-id}") clientId: String,
    @Value("\${metaos.google.client-secret}") clientSecret: String,
    @Value("\${metaos.google.drive-refresh-token}") refreshToken: String,
    @Value("\${metaos.google.drive-root-folder-id}") override val rootFolderId: String,
) : Drive {

    private val account = GoogleAccount(
        clientId, clientSecret, refreshToken,
        renew = "Get a new one with google/refresh_token.sh, put it in GOOGLE_DRIVE_REFRESH_TOKEN (.envrc, then " +
            "k8s/set_secrets.sh) and restart MetaOS",
    )

    private val drive = com.google.api.services.drive.Drive.Builder(googleTransport, googleJson, account.requests)
        .setApplicationName("MetaOS")
        .build()

    private fun requireId(id: String) = id.also { require(DRIVE_ID.matches(it)) { "$it is not a Google Drive file ID" } }

    override fun file(id: String): DriveFile? = account.call {
        try {
            drive.files().get(requireId(id)).setFields(FILE_FIELDS).execute().toDriveFile().takeUnless { it.trashed }
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404) null else throw e
        }
    }

    override fun children(folderId: String): List<DriveFile> = account.call {
        buildList {
            var pageToken: String? = null
            do {
                val page = drive.files().list()
                    .setQ("'${requireId(folderId)}' in parents and trashed = false")
                    .setFields("nextPageToken, files($FILE_FIELDS)")
                    .setOrderBy("name")
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute()
                page.files.orEmpty().mapTo(this) { it.toDriveFile() }
                pageToken = page.nextPageToken
            } while (pageToken != null)
        }
    }

    // UTF-8, though Drive leaves the charset out of text/markdown. An export has no ranges, so the bytes before offset
    // are read past, not kept
    override fun markdown(documentId: String, offset: Long, maxBytes: Int): ByteArray = account.call {
        drive.files().export(requireId(documentId), "text/markdown").executeMediaAsInputStream().use {
            try {
                it.skipNBytes(offset)
            } catch (_: EOFException) {
                throw IllegalArgumentException("Offset $offset is past the end of the document")
            }
            it.readNBytes(maxBytes)
        }
    }

    // fullText results come most relevant first; Drive doesn't sort them any other way
    override fun searchDocuments(text: String): Sequence<DriveFile> = sequence {
        var pageToken: String? = null
        do {
            val page = account.call {
                drive.files().list()
                    .setQ("mimeType = '$DOCUMENT_TYPE' and fullText contains ${quoted(text)} and trashed = false")
                    .setFields("nextPageToken, files($FILE_FIELDS)")
                    .setPageSize(100)
                    .setPageToken(pageToken)
                    .execute()
            }
            page.files.orEmpty().forEach { yield(it.toDriveFile()) }
            pageToken = page.nextPageToken
        } while (pageToken != null)
    }
}

@Component
@ConditionalOnDrive
class DriveHealthIndicator(private val drive: Drive, registry: MeterRegistry) : ConnectionHealthIndicator("drive", registry) {
    override fun probe() = "folder" to (drive.file(drive.rootFolderId)?.name
        ?: error("${drive.rootFolderId} is gone or not shared with the bot"))
}
