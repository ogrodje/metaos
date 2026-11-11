package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against the real Ogrodje folder, as the bot - only with its credentials in the environment (.envrc). Reads only. */
@EnabledIfEnvironmentVariable(named = "GOOGLE_DRIVE_REFRESH_TOKEN", matches = ".+")
class GoogleDriveTest {

    private fun drive(refreshToken: String = System.getenv("GOOGLE_DRIVE_REFRESH_TOKEN")) = GoogleDrive(
        clientId = System.getenv("GOOGLE_CLIENT_ID"),
        clientSecret = System.getenv("GOOGLE_CLIENT_SECRET"),
        refreshToken = refreshToken,
        rootFolderId = System.getenv("METAOS_DRIVE_ROOT_FOLDER_ID"),
    )

    // Breadth-first, until the first doc
    private fun firstDoc(drive: GoogleDrive): DriveFile {
        val folders = ArrayDeque(listOf(drive.rootFolderId))
        var doc: DriveFile? = null
        while (doc == null && folders.isNotEmpty()) {
            val children = drive.children(folders.removeFirst())
            doc = children.firstOrNull { it.mimeType == DOCUMENT_TYPE }
            folders += children.filter { it.mimeType == FOLDER_TYPE }.map { it.id }
        }
        return assertNotNull(doc, "a doc in the Ogrodje folder")
    }

    @Test
    fun `reads the Ogrodje folder and exports its docs as Markdown`() {
        val drive = drive()
        val root = assertNotNull(drive.file(drive.rootFolderId))
        assertEquals(FOLDER_TYPE, root.mimeType)

        val markdown = drive.markdown(firstDoc(drive).id, 0, MAX_READ).decodeToString()
        assertTrue(markdown.isNotBlank())
        // What UTF-8 read as ISO-8859-1 looks like
        assertTrue("Ä" !in markdown && "Å" !in markdown, "Markdown decoded as UTF-8")
    }

    @Test
    fun `searches docs by the words in their title`() {
        val drive = drive()
        val doc = firstDoc(drive)

        val found = drive.searchDocuments("\"${doc.name}\"").take(100).toList()
        assertTrue(found.any { it.id == doc.id }, "${doc.name} among ${found.map { it.name }}")
        assertTrue(found.all { it.mimeType == DOCUMENT_TYPE })
        // Quotes and backslashes stay text
        assertTrue(drive.searchDocuments("""Ogrodje's \ zq7xv9""").none())
    }

    @Test
    fun `doesn't find what isn't there`() {
        assertNull(drive().file("0000000000-no-such-file"))
    }

    @Test
    fun `says what to do when Google doesn't accept the refresh token`() {
        val revoked = drive(refreshToken = "1//not-a-refresh-token")

        val error = assertFailsWith<IllegalStateException> { revoked.file(revoked.rootFolderId) }
        assertTrue("google/refresh_token.sh" in error.message.orEmpty(), error.message)
    }
}
