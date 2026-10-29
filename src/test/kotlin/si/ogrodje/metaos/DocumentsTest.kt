package si.ogrodje.metaos

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Ogrodje's Drive folder in memory: folders, docs and their Markdown */
class FakeDrive : Drive {
    override val rootFolderId = "ogrodje-folder"
    private val files = mutableMapOf(rootFolderId to DriveFile(rootFolderId, "Ogrodje", FOLDER_TYPE))
    private val markdown = mutableMapOf<String, String>()

    /** What reaching Drive fails with, e.g. a revoked refresh token */
    var failure: Exception? = null

    fun folder(id: String, name: String, parent: String = rootFolderId) =
        DriveFile(id, name, FOLDER_TYPE, listOf(parent)).also { files[id] = it }

    fun doc(id: String, name: String, parent: String, content: String = "# $name") =
        DriveFile(id, name, DOCUMENT_TYPE, listOf(parent), "https://docs.google.com/document/d/$id/edit")
            .also { files[id] = it; markdown[id] = content }

    fun remove(id: String) = files.remove(id)

    override fun file(id: String) = failure?.let { throw it } ?: files[id]
    override fun children(folderId: String) = files.values.filter { folderId in it.parents }.sortedBy { it.name }
    override fun markdown(documentId: String, offset: Long, maxBytes: Int) = markdown.getValue(documentId).toByteArray()
        .also { require(offset <= it.size) { "Offset $offset is past the end of the document" } }
        .let { it.copyOfRange(offset.toInt(), minOf(it.size, offset.toInt() + maxBytes)) }
    override fun searchDocuments(text: String) = files.values.asSequence()
        .filter { it.mimeType == DOCUMENT_TYPE && "${it.name}\n${markdown[it.id]}".contains(text, ignoreCase = true) }
}

@SpringBootTest(
    properties = [
        "metaos.google.client-id=test", "metaos.google.client-secret=test",
        "metaos.google.drive-refresh-token=test", "metaos.google.drive-root-folder-id=ogrodje-folder",
    ]
)
@Import(TestcontainersConfig::class, DocumentsTest.Config::class)
class DocumentsTest @Autowired constructor(
    private val drive: FakeDrive,
    private val episodesRepository: EpisodesRepository,
    private val sessionsRepository: SessionsRepository,
    private val documentsMCP: DocumentsMCP,
    private val auditMCP: AuditMCP,
) {

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun fakeDrive() = FakeDrive()
    }

    @BeforeEach
    fun drive() {
        drive.folder("folder-episodes", "Epizode")
        drive.folder("folder-elsewhere", "Somebody else's", parent = "someone-elses-root")
        drive.doc("doc-elsewhere", "Not Ogrodje's", parent = "folder-elsewhere")
    }

    @Test
    fun `finds file IDs in Drive and Docs URLs and episode codes in doc titles`() {
        val id = "1n6MmDV9grG0pyoRiRjkph0e_YXyy8jl_nzKS5BKWvLE"
        assertEquals(id, driveFileId("https://docs.google.com/document/d/$id/edit?usp=drivesdk&ouid=1076"))
        assertEquals(id, driveFileId("https://drive.google.com/drive/folders/$id"))
        assertEquals(id, driveFileId("https://drive.google.com/open?id=$id"))
        assertEquals(id, driveFileId(" $id "))
        assertFailsWith<IllegalArgumentException> { driveFileId("not an id") }

        assertEquals("S05E03", episodeCodeIn("S05E03 - Jure Čuhalev: Kako preživeti kot inženir v dobi AI"))
        assertEquals("S05E07", episodeCodeIn("Ogrodje S05E7 - Domen Vreš in jezikovni model za slovenščino GaMS"))
        assertEquals("S04E14", episodeCodeIn("s4e14 notes"))
        assertEquals(null, episodeCodeIn("S05XX - META - TBD"))
        assertEquals(null, episodeCodeIn("Ogrodje Epizode Ideje"))
    }

    @Test
    fun `lists folders and reads docs in the Ogrodje folder, but nothing outside it`() {
        drive.folder("folder-ideas", "Ideje", parent = "folder-episodes")
        drive.doc("doc-ideas", "Ogrodje Epizode Ideje", parent = "folder-ideas", content = "# Ideje\n\n- Č, š in ž")

        assertTrue("Epizode" in documentsMCP.listFolder(null).map { it.name })
        assertEquals(
            listOf(DriveFileDto("doc-ideas", "Ogrodje Epizode Ideje", "document", "https://docs.google.com/document/d/doc-ideas/edit", null)),
            documentsMCP.listFolder("https://drive.google.com/drive/folders/folder-ideas"),
        )
        assertEquals("# Ideje\n\n- Č, š in ž", documentsMCP.read("https://docs.google.com/document/d/doc-ideas/edit", null).markdown)

        val text = longSloveneText()
        drive.doc("doc-transcript", "Prepis", parent = "folder-ideas", content = text)
        assertContentEquals(text.toByteArray(), readInPieces { documentsMCP.read("https://docs.google.com/document/d/doc-transcript/edit", it).let { r -> r.markdown to r.next } })
        assertFailsWith<IllegalArgumentException> { documentsMCP.read("https://docs.google.com/document/d/doc-ideas/edit", 1_000_000) }

        assertFailsWith<IllegalArgumentException> { documentsMCP.read("doc-elsewhere", null) }
        assertFailsWith<IllegalArgumentException> { documentsMCP.listFolder("folder-elsewhere") }
        assertFailsWith<IllegalArgumentException> { documentsMCP.read("folder-episodes", null) }
        assertFailsWith<IllegalArgumentException> { documentsMCP.read("doc-that-isnt-there", null) }
    }

    @Test
    fun `searches docs in the Ogrodje folder or a folder of it, but nothing outside it`() {
        drive.folder("folder-season-7", "Sezona 7", parent = "folder-episodes")
        drive.doc("doc-s07e01", "S07E01 - Kubernetes", parent = "folder-season-7")
        drive.doc("doc-s07e02", "S07E02 - Notes", parent = "folder-season-7", content = "# Notes\n\nO Kubernetesu")
        drive.doc("doc-k8s-plan", "Plan", parent = "ogrodje-folder", content = "Kubernetes, nekoč")
        drive.doc("doc-k8s-elsewhere", "Kubernetes elsewhere", parent = "folder-elsewhere")

        fun search(text: String, folder: String?) = documentsMCP.search(text, folder).map { it.id }

        assertEquals(listOf("doc-s07e01", "doc-s07e02", "doc-k8s-plan"), search("kubernetes", null))
        assertEquals(listOf("doc-s07e01", "doc-s07e02"), search("kubernetes", "https://drive.google.com/drive/folders/folder-episodes"))
        assertEquals(listOf("doc-s07e02"), search("o kubernetesu", "folder-season-7"))

        assertFailsWith<IllegalArgumentException> { search("kubernetes", "folder-elsewhere") }
        assertFailsWith<IllegalArgumentException> { search(" ", null) }
    }

    @Test
    fun `links and unlinks docs, and the episode's history shows it`() {
        val episode = episodesRepository.saveAndFlush(Episode(title = "Linked", code = "S09E01"))
        drive.folder("folder-s09e01", "S09E01", parent = "folder-episodes")
        drive.doc("doc-s09e01-prep", "S09E01 - Prep", parent = "folder-s09e01")
        drive.doc("doc-s09e01-notes", "S09E01 - Notes", parent = "folder-s09e01")

        documentsMCP.link("s09e01", "https://docs.google.com/document/d/doc-s09e01-prep/edit")
        documentsMCP.link("S09E01", "doc-s09e01-notes")
        documentsMCP.link("S09E01", "doc-s09e01-notes") // already linked
        assertEquals(listOf("S09E01 - Notes", "S09E01 - Prep"), documentsMCP.listEpisodeDocuments("S09E01").map { it.document.name })
        assertFailsWith<IllegalArgumentException> { documentsMCP.link("S09E01", "doc-elsewhere") }

        drive.remove("doc-s09e01-notes")
        assertEquals(listOf("missing", "document"), documentsMCP.listEpisodeDocuments("S09E01").map { it.document.type })

        documentsMCP.unlink("S09E01", "doc-s09e01-notes")
        assertEquals(listOf("doc-s09e01-prep"), documentsMCP.listEpisodeDocuments("S09E01").map { it.document.id })
        assertFailsWith<IllegalArgumentException> { documentsMCP.unlink("S09E01", "doc-s09e01-notes") }

        val history = auditMCP.history(episode.id!!).filter { it.table == "episode_documents" }
        assertEquals(listOf("INSERT", "INSERT", "DELETE"), history.map { it.operation })
        assertEquals(ColumnChange("doc-s09e01-notes", null), history.last().changes["file_id"])
    }

    @Test
    fun `links and unlinks docs to a recording session, and the session's history shows it`() {
        val recordingDay = sessionsRepository.saveAndFlush(Session(LocalDate.of(2026, 10, 22), location = "Four Points", address = null))
        val session = recordingDay.id!!
        episodesRepository.saveAndFlush(Episode(title = "Recorded there", code = "S09E02").also { it.session = recordingDay })
        drive.doc("doc-recording-day", "Ogrodje Snemalni Dan - 22.10.2026", parent = "folder-episodes")
        drive.doc("doc-recording-day-en", "Ogrodje Recording Day - 22.10.2026", parent = "folder-episodes")
        drive.doc("doc-s09e02-prep", "S09E02 - Prep", parent = "folder-episodes")

        documentsMCP.linkToSession(session, "https://docs.google.com/document/d/doc-recording-day/edit")
        documentsMCP.linkToSession(session, "doc-recording-day-en")
        documentsMCP.linkToSession(session, "doc-recording-day-en") // already linked
        assertEquals(
            listOf("Ogrodje Recording Day - 22.10.2026", "Ogrodje Snemalni Dan - 22.10.2026"),
            documentsMCP.listSessionDocuments(session).map { it.name },
        )
        assertFailsWith<IllegalArgumentException> { documentsMCP.linkToSession(session, "doc-elsewhere") }
        assertFailsWith<IllegalArgumentException> { documentsMCP.linkToSession(UUID.randomUUID(), "doc-recording-day") }

        // The session's docs are the episode's too, once each
        documentsMCP.link("S09E02", "doc-s09e02-prep")
        documentsMCP.link("S09E02", "doc-recording-day-en")
        assertEquals(
            listOf(
                "Ogrodje Recording Day - 22.10.2026" to LinkedTo.EPISODE,
                "Ogrodje Snemalni Dan - 22.10.2026" to LinkedTo.SESSION,
                "S09E02 - Prep" to LinkedTo.EPISODE,
            ),
            documentsMCP.listEpisodeDocuments("S09E02").map { it.document.name to it.linkedTo },
        )

        drive.remove("doc-recording-day")
        assertEquals(listOf("missing", "document"), documentsMCP.listSessionDocuments(session).map { it.type })

        documentsMCP.unlinkFromSession(session, "doc-recording-day")
        assertEquals(listOf("doc-recording-day-en"), documentsMCP.listSessionDocuments(session).map { it.id })
        assertFailsWith<IllegalArgumentException> { documentsMCP.unlinkFromSession(session, "doc-recording-day") }

        val history = auditMCP.history(session).filter { it.table == "session_documents" }
        assertEquals(listOf("INSERT", "INSERT", "DELETE"), history.map { it.operation })
        assertEquals(ColumnChange("doc-recording-day", null), history.last().changes["file_id"])
    }

    @Test
    fun `matches docs to episodes by the code in their title, and links them only when asked to`() {
        episodesRepository.saveAndFlush(Episode(title = "Three", code = "S08E03"))
        episodesRepository.saveAndFlush(Episode(title = "Seven", code = "S08E07"))
        drive.folder("folder-season-8", "Sezona 8", parent = "folder-episodes")
        drive.doc("doc-s08e03", "S08E03 - Guest: Title", parent = "folder-season-8")
        drive.doc("doc-s08e07", "Ogrodje S08E7 - Title", parent = "folder-season-8")
        drive.doc("doc-s08e09", "S08E09 - Not recorded yet", parent = "folder-season-8")
        drive.doc("doc-s08xx", "S08XX - META - TBD", parent = "folder-season-8")

        fun match(apply: Boolean?) =
            documentsMCP.match("folder-season-8", apply).associate { it.document.name to (it.episode to it.result) }

        val expected = mapOf(
            "Ogrodje S08E7 - Title" to ("S08E07" to MatchResult.WOULD_LINK),
            "S08E03 - Guest: Title" to ("S08E03" to MatchResult.WOULD_LINK),
            "S08E09 - Not recorded yet" to ("S08E09" to MatchResult.NO_SUCH_EPISODE),
            "S08XX - META - TBD" to (null to MatchResult.NO_EPISODE_CODE),
        )
        assertEquals(expected, match(apply = null))
        assertTrue(documentsMCP.listEpisodeDocuments("S08E03").isEmpty())

        assertEquals(
            expected.mapValues { (_, match) -> if (match.second == MatchResult.WOULD_LINK) match.first to MatchResult.LINKED else match },
            match(apply = true),
        )
        assertEquals(listOf("doc-s08e07"), documentsMCP.listEpisodeDocuments("S08E07").map { it.document.id })
        assertEquals(MatchResult.ALREADY_LINKED, match(apply = true).getValue("S08E03 - Guest: Title").second)
    }
}
