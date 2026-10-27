package si.ogrodje.metaos

import io.temporal.client.WorkflowClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Speech-to-Text for a bucket in memory: it "hears" what [heard] says a file has, writing it under the output as
 * Speech-to-Text's results (BatchRecognizeResults, as JSON), and says it's still on [busy] times before it's done
 */
class FakeSpeech(private val storage: FakeStorage) : SpeechToText {
    override val model = "chirp_3"

    val heard = mutableMapOf<String, List<Pair<Double, String>>>()
    var busy = 1
    // The language each file was last heard as
    val languages = mutableMapOf<String, String>()
    private val operations = mutableMapOf<String, Pair<String, Int>>()

    override fun start(path: String, output: String, language: String, batch: Boolean): String {
        languages[path] = language
        val name = "projects/fake/locations/eu/operations/${UUID.randomUUID()}"
        val results = heard.getValue(path).joinToString(",") { (end, text) ->
            """{"alternatives":[{"transcript":"$text","confidence":0.9}],"resultEndOffset":"${end}s","languageCode":"sl-si"}"""
        }
        storage.upload("$output/results.json", "application/json", """{"results":[$results]}""".byteInputStream(), overwrite = true)
        operations[name] = output to busy
        return name
    }

    override fun result(operation: String, path: String): Recognized {
        val (output, left) = operations.getValue(operation)
        if (left > 0) {
            operations[operation] = output to left - 1
            throw StillRecognizing(50)
        }
        return Recognized("$output/results.json", null, heard.getValue(path).last().first)
    }
}

@SpringBootTest(
    properties = [
        "metaos.storage.bucket=ogrodje-metaos-test",
        // Temporal in memory, MetaOS its worker
        "spring.temporal.test-server.enabled=true",
        "metaos.transcription.poll-every=10ms",
    ]
)
@Import(TestcontainersConfig::class, ImagesTest.Config::class, MediaFilesTest.Config::class, TranscriptionTest.Config::class)
class TranscriptionTest @Autowired constructor(
    private val episodes: EpisodesRepository,
    private val media: MediaFilesRepository,
    private val transcripts: TranscriptsRepository,
    private val shows: ShowsRepository,
    private val mcp: TranscriptsMCP,
    private val speech: SpeechToText,
    private val client: WorkflowClient,
    private val history: History,
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
        fun fakeSpeech(storage: Storage): SpeechToText = FakeSpeech(storage as FakeStorage)
    }

    private val fake get() = speech as FakeSpeech

    private fun signedInAs(username: String) {
        val jwt = Jwt.withTokenValue("token").header("alg", "none").subject("$username-id")
            .claim("preferred_username", username).claim("azp", "claude-code").claim("realm_access", APPROVED).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun file(episode: Episode, kind: MediaKind, name: String): MediaFile = media.saveAndFlush(
        MediaFile(
            episodeId = checkNotNull(episode.id), kind = kind, title = null, name = name,
            path = "episode-media/${UUID.randomUUID()}/$name", contentType = if (kind.video) "video/mp4" else "audio/mpeg",
            size = 1000, md5 = null, crc32c = null, uploaderId = "ana-id", uploader = "ana",
            info = MediaInfo(
                duration = 3600.0, bitrate = null, container = null, videoCodec = null, videoProfile = null, width = null,
                height = null, frameRate = null, bitDepth = null, color = null, audioCodec = "mp3", sampleRate = 48000,
                channels = 2, audioBitrate = null, audioTracks = 1, madeAt = null, encoder = null,
            ),
            probe = null,
        )
    )

    private fun await(mediaId: UUID): TranscriptionResult =
        client.newUntypedWorkflowStub(TranscriptionWorkflow.workflowId(mediaId))
            .getResult(30, TimeUnit.SECONDS, TranscriptionResult::class.java)

    @Test
    fun `transcribes an episode's full audio in a workflow, and reads it back with timestamps`() {
        val episode = episodes.saveAndFlush(Episode(title = "Prepis", code = "S09E80"))
        file(episode, MediaKind.FULL_VIDEO, "S09E80.mp4")
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E80.mp3")
        fake.heard[audio.path] = listOf(3.5 to "Dober dan in dobrodošli v Ogrodju.", 3725.25 to "Hvala za poslušanje.")

        signedInAs("ana")
        val started = mcp.transcribeEpisode("S09E80", null, null, null, null, false)
        assertEquals("transcribe-${audio.id}", started.workflowId)
        assertEquals("S09E80.mp3", started.media)

        val result = await(checkNotNull(audio.id))
        assertEquals(2, result.segments)
        assertEquals(9, result.words)

        val read = mcp.getEpisodeTranscript("S09E80", null)
        val transcript = read.transcripts.single()
        assertEquals("S09E80.mp3", transcript.media)
        // Without a show, an episode is in Slovenian
        assertEquals("sl-SI", fake.languages[audio.path])
        assertEquals("sl-SI", transcript.language)
        assertEquals("sl-SI", transcript.spokenLanguage)
        assertEquals("ana", transcript.requestedBy)
        assertEquals(
            "[0:00:00] Dober dan in dobrodošli v Ogrodju.\n[0:00:03] Hvala za poslušanje.",
            transcript.text,
        )
        assertTrue(read.transcriptions.isEmpty(), "none on: ${read.transcriptions}")

        // In the episode's history, as whoever asked for it, through the workflows
        val entry = history.of(checkNotNull(episode.id)).single { it.summary == "Transcribed S09E80.mp3" }
        assertEquals("ana", entry.actor)
        assertEquals(WORKFLOWS_CLIENT, entry.client)
    }

    @Test
    fun `transcribes a file again only to replace its transcript`() {
        val episode = episodes.saveAndFlush(Episode(title = "Ponovno", code = "S09E81"))
        val video = file(episode, MediaKind.FULL_VIDEO, "S09E81.mov")
        fake.heard[video.path] = listOf(10.0 to "Prvič.")
        mcp.transcribeEpisode("S09E81", null, null, null, null, false)
        await(checkNotNull(video.id))
        val first = assertNotNull(transcripts.findSpoken(checkNotNull(video.id)))

        val refused = assertFailsWith<IllegalArgumentException> { mcp.transcribeEpisode("S09E81", null, null, null, null, false) }
        assertTrue("has a transcript already" in refused.message.orEmpty(), refused.message)

        fake.heard[video.path] = listOf(10.0 to "Drugič.")
        // Heard as English this time, which it is, so it replaces the Slovenian one rather than standing beside it
        mcp.transcribeEpisode("S09E81", video.id, "EN-us", null, true, false)
        await(checkNotNull(video.id))
        val second = assertNotNull(transcripts.findSpoken(checkNotNull(video.id)))
        assertNotEquals(first.id, second.id)
        assertEquals("en-US", second.language)
        assertEquals(listOf(second.id), transcripts.findByMediaId(checkNotNull(video.id)).map { it.id })
        assertEquals("[0:00:00] Drugič.", mcp.getEpisodeTranscript("S09E81", video.id).transcripts.single().text)
    }

    @Test
    fun `refuses an episode without full audio or video`() {
        episodes.saveAndFlush(Episode(title = "Brez", code = "S09E82"))
        val refused = assertFailsWith<IllegalArgumentException> { mcp.transcribeEpisode("S09E82", null, null, null, null, false) }
        assertTrue("has no full audio or video" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `hears an episode in its show's language, Borderless's in English, unless told another`() {
        val borderless = shows.saveAndFlush(Show(name = "Borderless Test", color = null, language = "en-US"))
        val episode = episodes.saveAndFlush(Episode(title = "Abroad", code = "S09E83").apply { show = borderless })
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E83.mp3")
        fake.heard[audio.path] = listOf(4.0 to "Welcome to Ogrodje.")

        mcp.transcribeEpisode("S09E83", null, null, null, null, false)
        await(checkNotNull(audio.id))
        assertEquals("en-US", fake.languages[audio.path])
        assertEquals("en-US", mcp.getEpisodeTranscript("S09E83", null).transcripts.single().language)

        mcp.transcribeEpisode("S09E83", null, "sl-SI", null, true, false)
        await(checkNotNull(audio.id))
        assertEquals("sl-SI", fake.languages[audio.path])

        val refused = assertFailsWith<IllegalArgumentException> { mcp.transcribeEpisode("S09E83", null, "slovenian!", null, true, false) }
        assertTrue("isn't a language tag" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `says h-mm-ss as YouTube's chapters do`() {
        assertEquals("0:00:00", timestamp(0.4))
        assertEquals("1:02:05", timestamp(Duration.ofMinutes(62).plusSeconds(5).seconds.toDouble()))
    }
}
