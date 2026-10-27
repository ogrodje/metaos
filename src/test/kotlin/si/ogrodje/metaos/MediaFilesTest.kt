package si.ogrodje.metaos

import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ffprobe for a bucket in memory, whose files are what ffprobe would say about them (ffprobeJson); anything else it
 * can't read
 */
class FakeProbe(private val storage: FakeStorage, private val jsonMapper: JsonMapper) : MediaProbe {
    override fun probe(url: String, name: String): Probed {
        val file = requireNotNull(storage.downloaded(url)) { "There's no file at $url" }
        val json = file.bytes.decodeToString()
        require(json.startsWith("{")) { "$name isn't video or audio that MetaOS can read: $name: Invalid data found when processing input" }
        return parseProbe(jsonMapper, json, name)
    }
}

@SpringBootTest(properties = ["metaos.storage.bucket=ogrodje-metaos-test"])
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, ImagesTest.Config::class, MediaFilesTest.Config::class)
class MediaFilesTest @Autowired constructor(
    private val storage: Storage,
    private val episodes: EpisodesRepository,
    private val repository: MediaFilesRepository,
    private val mcp: MediaFilesMCP,
    private val history: History,
    private val mvc: MockMvc,
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun storageKey(registry: DynamicPropertyRegistry) {
            registry.add("metaos.storage.key", ::fakeServiceAccountKey)
        }
    }

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun fakeProbe(storage: Storage, jsonMapper: JsonMapper): MediaProbe = FakeProbe(storage as FakeStorage, jsonMapper)
    }

    // Approved users on MetaOS's pages: one who may only read, and one who may also write
    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    private val bucket get() = storage as FakeStorage

    private val fields = "id kind title name contentType size md5 uploadedBy info { duration container videoCodec width height " +
        "frameRate audioCodec sampleRate channels audioTracks } downloadUrl"

    // As the browser does: asks where to send a file, and sends it there
    private fun upload(ref: String, kind: MediaKind, contentType: String, content: String): String {
        val answer = graphql("""mutation { mediaUpload(ref: "$ref", kind: $kind, contentType: "$contentType", size: ${content.length}) { id url headers { name value } } }""")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.mediaUpload.url", startsWith("https://storage.example.com/fake/uploads/")))
            .andReturn().response.contentAsString
        val id = JsonPath.read<String>(answer, "$.data.mediaUpload.id")
        assertEquals(contentType, JsonPath.read<List<String>>(answer, "$.data.mediaUpload.headers[?(@.name == 'Content-Type')].value").single())
        bucket.upload("uploads/$id", contentType, content.byteInputStream(), overwrite = false)
        return id
    }

    private fun add(ref: String, kind: MediaKind, upload: String, name: String, title: String? = null, replace: Boolean = false) = graphql(
        """mutation { addEpisodeMedia(ref: "$ref", kind: $kind, upload: "$upload", name: "$name"${title?.let { ", title: \"$it\"" }.orEmpty()},
            replace: $replace) { $fields } }"""
    )

    @Test
    fun `keeps a full video uploaded from the browser, with what ffprobe read of it, and lets the upload go`() {
        val episode = episodes.saveAndFlush(Episode(title = "Video", code = "S09E90"))
        val id = upload("S09E90", MediaKind.FULL_VIDEO, "video/mp4", videoJson())

        add("S09E90", MediaKind.FULL_VIDEO, id, "S09E90 – končni.mp4")
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.addEpisodeMedia.kind").value("FULL_VIDEO"))
            .andExpect(jsonPath("$.data.addEpisodeMedia.name").value("S09E90 – končni.mp4"))
            .andExpect(jsonPath("$.data.addEpisodeMedia.contentType").value("video/mp4"))
            .andExpect(jsonPath("$.data.addEpisodeMedia.uploadedBy").value("bojan"))
            .andExpect(jsonPath("$.data.addEpisodeMedia.info.container").value("MP4"))
            .andExpect(jsonPath("$.data.addEpisodeMedia.info.videoCodec").value("h264"))
            .andExpect(jsonPath("$.data.addEpisodeMedia.info.width").value(1920))
            .andExpect(jsonPath("$.data.addEpisodeMedia.info.frameRate").value(25.0))
            .andExpect(jsonPath("$.data.addEpisodeMedia.info.sampleRate").value(48000))
            .andExpect(jsonPath("$.data.addEpisodeMedia.info.duration").value(3600.0))
            .andExpect(jsonPath("$.data.addEpisodeMedia.downloadUrl", startsWith("https://storage.example.com/fake/episode-media/$id/")))

        // Kept in the bucket where it's downloaded as a file by its name, and the upload's gone
        assertNull(bucket.file("uploads/$id"))
        val kept = assertNotNull(bucket.files["episode-media/$id/S09E90 – končni.mp4"])
        assertEquals("attachment; filename=\"S09E90 _ kon_ni.mp4\"; filename*=UTF-8''S09E90%20%E2%80%93%20kon%C4%8Dni.mp4", kept.contentDisposition)
        val file = repository.findAll().single { it.episodeId == episode.id }
        assertEquals(kept.file.md5, file.md5)
        assertEquals("bojan-id", file.uploaderId)
        // ffprobe's whole answer, as Postgres keeps JSON
        assertEquals("h264", JsonPath.read<String>(file.probe, "$.streams[0].codec_name"))

        // In the episode's history, and for MCP clients, with a link
        assertEquals(listOf("Uploaded the full video: S09E90 – končni.mp4"), history.of(checkNotNull(episode.id)).filter { it.kind == ChangeKind.MEDIA }.map { it.summary })
        val listed = mcp.listEpisodeMedia("S09E90").single()
        assertEquals(MediaKind.FULL_VIDEO to "bojan", listed.kind to listed.uploadedBy)
        assertEquals(bucket.files["episode-media/$id/S09E90 – končni.mp4"], bucket.downloaded(listed.downloadUrl!!))
    }

    @Test
    fun `refuses what isn't what it's uploaded as, and lets the upload go all the same`() {
        episodes.saveAndFlush(Episode(title = "Refused", code = "S09E91"))

        graphql("""mutation { mediaUpload(ref: "S09E91", kind: FULL_VIDEO, contentType: "audio/mpeg", size: 1000) { id } }""")
            .andExpect(jsonPath("$.errors[0].message").value("A full video is MP4 or MOV"))
        graphql("""mutation { mediaUpload(ref: "S09E91", kind: BONUS_AUDIO, contentType: "video/quicktime", size: 1000) { id } }""")
            .andExpect(jsonPath("$.errors[0].message").value("A bonus audio is MP3, M4A, WAV or FLAC"))
        graphql("""mutation { mediaUpload(ref: "S09E91", kind: FULL_VIDEO, contentType: "video/mp4", size: ${MAX_MEDIA_BYTES + 1}) { id } }""")
            .andExpect(jsonPath("$.errors[0].message").value("A file is up to 50 GB"))
        graphql("""mutation { mediaUpload(ref: "S09E91", kind: FULL_VIDEO, contentType: "video/mp4", size: 1000) { id } }""", reader)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))

        // An MP3 as a video, by what's in it; a video as audio; and what isn't either
        val audio = upload("S09E91", MediaKind.FULL_VIDEO, "video/mp4", audioJson())
        add("S09E91", MediaKind.FULL_VIDEO, audio, "really-audio.mp4")
            .andExpect(jsonPath("$.errors[0].message").value("really-audio.mp4 has no video; upload it as audio"))
        val video = upload("S09E91", MediaKind.FULL_AUDIO, "audio/mp4", videoJson())
        add("S09E91", MediaKind.FULL_AUDIO, video, "really-video.m4a")
            .andExpect(jsonPath("$.errors[0].message").value("really-video.m4a has video; upload it as the full video or a bonus video"))
        val broken = upload("S09E91", MediaKind.BONUS_VIDEO, "video/mp4", "not a video")
        add("S09E91", MediaKind.BONUS_VIDEO, broken, "broken.mp4")
            .andExpect(jsonPath("$.errors[0].message").value("broken.mp4 isn't video or audio that MetaOS can read: broken.mp4: Invalid data found when processing input"))
        // Uploaded as audio, given as a video
        val mp3 = upload("S09E91", MediaKind.FULL_AUDIO, "audio/mpeg", audioJson())
        add("S09E91", MediaKind.FULL_VIDEO, mp3, "S09E91.mp3")
            .andExpect(jsonPath("$.errors[0].message").value("S09E91.mp3 isn't MP4 or MOV, which a full video is"))
        // One that never arrived
        add("S09E91", MediaKind.FULL_VIDEO, "00000000-0000-0000-0000-000000000000", "lost.mp4")
            .andExpect(jsonPath("$.errors[0].message").value("lost.mp4 didn't arrive in MetaOS's bucket; upload it again"))

        val uploads = listOf(audio, video, broken, mp3)
        assertEquals(emptyList(), (bucket.list("uploads", 100) + bucket.list("episode-media", 100)).filter { file -> uploads.any { it in file.path } })
        assertEquals(emptyList(), mcp.listEpisodeMedia("S09E91"))
    }

    @Test
    fun `has one full video and one full audio, replaced only when asked, and any number of bonuses, but each file once`() {
        val episode = episodes.saveAndFlush(Episode(title = "Bonuses", code = "S09E92"))
        val first = upload("S09E92", MediaKind.FULL_AUDIO, "audio/mpeg", audioJson(tags = mapOf("title" to "first")))
        add("S09E92", MediaKind.FULL_AUDIO, first, "S09E92.mp3").andExpect(jsonPath("$.errors").doesNotExist())
        val firstPath = repository.findAll().single { it.episodeId == episode.id }.path

        // Another full audio, unless it's to replace the one there; the upload goes either way
        val second = upload("S09E92", MediaKind.FULL_AUDIO, "audio/wav", audioJson(tags = mapOf("title" to "second")))
        add("S09E92", MediaKind.FULL_AUDIO, second, "S09E92-master.wav")
            .andExpect(jsonPath("$.errors[0].message").value("S09E92 has a full audio already, S09E92.mp3; replace it, or keep that one"))
        assertNull(bucket.file("uploads/$second"))
        val again = upload("S09E92", MediaKind.FULL_AUDIO, "audio/wav", audioJson(tags = mapOf("title" to "second")))
        add("S09E92", MediaKind.FULL_AUDIO, again, "S09E92-master.wav", replace = true)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.addEpisodeMedia.name").value("S09E92-master.wav"))
        assertEquals(listOf("S09E92-master.wav"), mcp.listEpisodeMedia("S09E92").map { it.name })
        assertNull(bucket.file(firstPath))

        // Bonuses, with titles
        for ((i, title) in listOf("Behind the scenes", "Q&A").withIndex()) {
            val bonus = upload("S09E92", MediaKind.BONUS_VIDEO, "video/quicktime", videoJson(width = 1280, height = 720, tags = mapOf("title" to "bonus $i")))
            add("S09E92", MediaKind.BONUS_VIDEO, bonus, "bonus-$i.mov", title = title).andExpect(jsonPath("$.errors").doesNotExist())
        }
        // The same file again isn't, whatever it's called
        val same = upload("S09E92", MediaKind.BONUS_VIDEO, "video/quicktime", videoJson(width = 1280, height = 720, tags = mapOf("title" to "bonus 0")))
        add("S09E92", MediaKind.BONUS_VIDEO, same, "bonus-copy.mov")
            .andExpect(jsonPath("$.errors[0].message").value("S09E92 has this file already: bonus-0.mov, its bonus video"))

        val listed = mcp.listEpisodeMedia("S09E92")
        assertEquals(
            listOf(MediaKind.FULL_AUDIO to null, MediaKind.BONUS_VIDEO to "Behind the scenes", MediaKind.BONUS_VIDEO to "Q&A"),
            listed.map { it.kind to it.title },
        )
        assertTrue(listed.all { bucket.downloaded(it.downloadUrl!!) != null })
        assertNull(bucket.file("uploads/$same"))
        assertEquals(
            listOf(
                "Uploaded the full audio: S09E92.mp3", "Removed the full audio: S09E92.mp3", "Uploaded the full audio: S09E92-master.wav",
                "Added a bonus video: bonus-0.mov", "Added a bonus video: bonus-1.mov",
            ),
            history.of(checkNotNull(episode.id)).filter { it.kind == ChangeKind.MEDIA }.map { it.summary },
        )
    }

    @Test
    fun `changes a file's title and what it is, within video or audio, and removes it with its file`() {
        val episode = episodes.saveAndFlush(Episode(title = "Changes", code = "S09E93"))
        val video = upload("S09E93", MediaKind.BONUS_VIDEO, "video/mp4", videoJson(tags = mapOf("title" to "a")))
        add("S09E93", MediaKind.BONUS_VIDEO, video, "S09E93.mp4", title = "Pogovor").andExpect(jsonPath("$.errors").doesNotExist())
        val full = upload("S09E93", MediaKind.BONUS_VIDEO, "video/mp4", videoJson(tags = mapOf("title" to "b")))
        add("S09E93", MediaKind.BONUS_VIDEO, full, "S09E93-full.mp4").andExpect(jsonPath("$.errors").doesNotExist())
        val (first, second) = mcp.listEpisodeMedia("S09E93").map { checkNotNull(it.id) }

        // The full video, as MCP does, then no other; nor audio
        assertEquals(MediaKind.FULL_VIDEO, mcp.updateEpisodeMedia(second, null, MediaKind.FULL_VIDEO).kind)
        graphql("""mutation { updateEpisodeMedia(id: "$first", kind: FULL_VIDEO) { kind } }""")
            .andExpect(jsonPath("$.errors[0].message").value("The episode has a full video already, S09E93-full.mp4; remove that one first"))
        graphql("""mutation { updateEpisodeMedia(id: "$first", kind: BONUS_AUDIO) { kind } }""")
            .andExpect(jsonPath("$.errors[0].message").value("A bonus video can't be a bonus audio: one is video, the other audio"))
        // A title, changed, and left as it is when none is given
        graphql("""mutation { updateEpisodeMedia(id: "$first", title: "  Pogovor po snemanju ") { title kind } }""")
            .andExpect(jsonPath("$.data.updateEpisodeMedia.title").value("Pogovor po snemanju"))
        graphql("""mutation { updateEpisodeMedia(id: "$first", kind: BONUS_VIDEO) { title } }""")
            .andExpect(jsonPath("$.data.updateEpisodeMedia.title").value("Pogovor po snemanju"))
        graphql("""mutation { updateEpisodeMedia(id: "$first", title: "") { title } }""")
            .andExpect(jsonPath("$.data.updateEpisodeMedia.title").value(null as Any?))

        // Removed, with its file; a reader may not
        graphql("""mutation { removeEpisodeMedia(id: "$first") }""", reader)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        graphql("""mutation { removeEpisodeMedia(id: "$first") }""").andExpect(jsonPath("$.data.removeEpisodeMedia").value(first.toString()))
        assertNull(bucket.file("episode-media/$video/S09E93.mp4"))
        val removed = mcp.removeEpisodeMedia(second)
        assertEquals("S09E93-full.mp4" to null, removed.name to removed.downloadUrl)
        assertNull(bucket.file("episode-media/$full/S09E93-full.mp4"))
        assertEquals(emptyList(), mcp.listEpisodeMedia("S09E93"))

        val changes = history.of(checkNotNull(episode.id)).filter { it.kind == ChangeKind.MEDIA }
        assertEquals(
            listOf(
                "Added a bonus video: S09E93.mp4", "Added a bonus video: S09E93-full.mp4", "Made S09E93-full.mp4 the full video",
                "Titled a bonus video S09E93.mp4: Pogovor po snemanju", "Took the title off a bonus video S09E93.mp4",
                "Removed a bonus video: S09E93.mp4", "Removed the full video: S09E93-full.mp4",
            ),
            changes.map { it.summary },
        )
        assertEquals(listOf(FieldChangeDto("Kind", "bonus video", "full video")), changes[2].fields)
        assertEquals(listOf(FieldChangeDto("Title", "Pogovor", "Pogovor po snemanju")), changes[3].fields)
        assertEquals("update_episode_media", changes[2].tool)
    }

    @Test
    fun `are the team's only`() {
        val episode = Episode(title = "Released", code = "S09E94").apply {
            status = EpisodeStatus.PUBLISHED
            airedDate = LocalDate.of(2026, 10, 1)
        }
        episodes.saveAndFlush(episode)
        val id = upload("S09E94", MediaKind.FULL_VIDEO, "video/mp4", videoJson(tags = mapOf("title" to "released")))
        add("S09E94", MediaKind.FULL_VIDEO, id, "S09E94.mp4").andExpect(jsonPath("$.errors").doesNotExist())
        val fileId = mcp.listEpisodeMedia("S09E94").single().id

        graphql("""{ episode(ref: "S09E94") { media { name } } }""", reader)
            .andExpect(jsonPath("$.data.episode.media[0].name").value("S09E94.mp4"))
        graphql("""{ mediaFile(id: "$fileId") { name downloadUrl } }""", reader)
            .andExpect(jsonPath("$.data.mediaFile.downloadUrl", startsWith("https://storage.example.com/fake/episode-media/$id/")))
        // Anyone else sees the released episode, without its files
        graphql("""{ episode(ref: "S09E94") { title media { name } } }""", null)
            .andExpect(jsonPath("$.data.episode.title").value("Released"))
            .andExpect(jsonPath("$.data.episode.media").isEmpty)
        graphql("""{ mediaFile(id: "$fileId") { name } }""", null)
            .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"))
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = writer): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        // Answered right away, unless a resolver is asynchronous
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions).andExpect(status().isOk)
    }
}
