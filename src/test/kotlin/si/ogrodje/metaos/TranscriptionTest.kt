package si.ogrodje.metaos

import com.jayway.jsonpath.JsonPath
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowFailedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A speech-to-text provider for a bucket in memory: it "hears" what [heard] says a file has, writing it under the
 * output as Google's results (BatchRecognizeResults, as JSON), and says it's still on [busy] times before it's done, or
 * as long as it's [holding]; it's too busy to start [busyStarts] times
 */
class FakeSpeech(private val storage: FakeStorage, override val provider: String = "fake") : SpeechToText {
    override val model = "long"

    val heard = ConcurrentHashMap<String, List<Pair<Double, String>>>()
    var busy = 1
    @Volatile var holding = false
    @Volatile var busyStarts = 0
    val starts = AtomicInteger()
    // Files it can't hear, and the operations it was asked to stop
    val failing = ConcurrentHashMap.newKeySet<String>()
    val cancelled = ConcurrentHashMap.newKeySet<String>()
    // The language each file was last heard as, and what it was told about it
    val languages = ConcurrentHashMap<String, String>()
    val hints = ConcurrentHashMap<String, SpeechHints>()
    private val operations = ConcurrentHashMap<String, Pair<String, Int>>()

    override fun start(path: String, output: String, language: String, batch: Boolean, hints: SpeechHints): String {
        starts.incrementAndGet()
        if (busyStarts > 0) {
            busyStarts--
            throw SonioxException("Soniox answered 429: Too many requests.")
        }
        languages[path] = language
        this.hints[path] = hints
        val name = "projects/fake/locations/eu/operations/${UUID.randomUUID()}"
        val results = heard.getValue(path).joinToString(",") { (end, text) ->
            """{"alternatives":[{"transcript":"$text","confidence":0.9}],"resultEndOffset":"${end}s","languageCode":"sl-si"}"""
        }
        storage.upload("$output/results.json", "application/json", """{"results":[$results]}""".byteInputStream(), overwrite = true)
        operations[name] = output to busy
        return name
    }

    override fun result(operation: String, path: String, output: String): Recognized {
        if (path in failing) throw IllegalStateException("Speech-to-text couldn't transcribe it: no speech")
        val (output, left) = operations.getValue(operation)
        if (holding || left > 0) {
            if (!holding) operations[operation] = output to left - 1
            throw StillRecognizing(50)
        }
        return Recognized("$output/results.json", heard.getValue(path).last().first)
    }

    override fun cancel(operation: String) {
        cancelled += operation
    }

    override fun heard(results: String) = googleHeard(results)
}

@SpringBootTest(
    properties = [
        "metaos.storage.bucket=ogrodje-metaos-test",
        // Temporal in memory, MetaOS its worker
        "spring.temporal.test-server.enabled=true",
        // Quick, but not so quick that a cancel mostly finds a check on (Temporal's test server can't cancel one that
        // just finished)
        "metaos.transcription.poll-every=200ms",
        "metaos.speech.provider=fake",
        // One at a time, to see the rest wait in line, and a turn taken back from one that ended right away
        "metaos.slots.single=1",
        "metaos.slots.check-every=200ms",
    ]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, ImagesTest.Config::class, MediaFilesTest.Config::class, TranscriptionTest.Config::class)
class TranscriptionTest @Autowired constructor(
    private val mvc: MockMvc,
    private val episodes: EpisodesRepository,
    private val media: MediaFilesRepository,
    private val transcripts: TranscriptsRepository,
    private val texts: TranscriptTextsRepository,
    private val storage: Storage,
    private val jsonMapper: JsonMapper,
    private val shows: ShowsRepository,
    private val mcp: TranscriptsMCP,
    private val speech: SpeechToText,
    private val providers: SpeechProviders,
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

        // Another provider, beside it
        @Bean
        fun otherSpeech(storage: Storage): SpeechToText = FakeSpeech(storage as FakeStorage, "other")

        // One that has a single turn
        @Bean
        fun singleSpeech(storage: Storage): SpeechToText = FakeSpeech(storage as FakeStorage, "single")
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

    private fun await(mediaId: UUID, provider: String = "fake"): TranscriptionResult =
        client.newUntypedWorkflowStub(TranscriptionWorkflow.workflowId(mediaId, provider))
            .getResult(30, TimeUnit.SECONDS, TranscriptionResult::class.java)

    private fun transcription(code: String, file: MediaFile, provider: String) =
        mcp.getEpisodeTranscript(code, file.id, provider).transcriptions.singleOrNull()

    private fun until(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Never $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `transcribes an episode's full audio in a workflow, and reads it back with timestamps`() {
        val episode = episodes.saveAndFlush(Episode(title = "Prepis", code = "S09E80"))
        file(episode, MediaKind.FULL_VIDEO, "S09E80.mp4")
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E80.mp3")
        fake.heard[audio.path] = listOf(3.5 to "Dober dan in dobrodošli v Ogrodju.", 3725.25 to "Hvala za poslušanje.")

        signedInAs("ana")
        val started = mcp.transcribeEpisode("S09E80", null, null, null, null, null)
        assertEquals("fake", started.provider)
        assertTrue(started.status.on, started.status.name)
        assertEquals("ana", started.requestedBy)
        assertEquals("S09E80.mp3", started.media)
        // Watched by its ID, which transcribe_episode answers with
        assertEquals(started.id to "S09E80.mp3", mcp.getTranscription(started.id).let { it.id to it.media })

        val result = await(checkNotNull(audio.id))
        // Its row, as it went: done, with what it made
        val done = mcp.listTranscriptions(null, null, "S09E80", null).single()
        assertEquals(TranscriptionStatus.DONE, done.status)
        assertEquals(result.transcriptId, done.transcriptId)
        assertEquals(done, mcp.getTranscription(started.id))
        val unknown = assertFailsWith<IllegalArgumentException> { mcp.getTranscription(UUID.randomUUID()) }
        assertTrue("No transcription" in unknown.message.orEmpty(), unknown.message)
        assertEquals("long", done.model)
        assertNotNull(done.startedAt)
        assertNotNull(done.finishedAt)
        assertEquals(listOf(done.id), mcp.listTranscriptions("done", "fake", "S09E80", null).map { it.id })
        assertTrue(mcp.listTranscriptions("on", null, "S09E80", null).isEmpty())
        // What it went by, to spell names as the episode has them
        assertEquals(SpeechHints(title = "Prepis"), fake.hints[audio.path])
        assertEquals(2, result.segments)
        assertEquals(9, result.words)

        val read = mcp.getEpisodeTranscript("S09E80", null, null)
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
        assertEquals("fake", transcript.provider)
        assertEquals("long", transcript.model)
        assertEquals(2, transcript.segments)

        // What was said is in the database and, the same, in the bucket, next to what the provider answered
        val saved = transcripts.findSpoken(checkNotNull(audio.id), "fake")!!
        val content = jsonMapper.readValue<TranscriptContent>(texts.findById(checkNotNull(saved.id)).get().content)
        assertEquals(listOf("Dober dan in dobrodošli v Ogrodju.", "Hvala za poslušanje."), content.segments.map { it.text })
        assertEquals("fake" to "sl-SI", content.provider to content.language)
        assertEquals(content, jsonMapper.readValue<TranscriptContent>(storage.read(saved.path, 1_000_000)))
        assertTrue(storage.read(checkNotNull(saved.rawPath), 1_000_000).decodeToString().startsWith("""{"results":"""))

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
        mcp.transcribeEpisode("S09E81", null, null, null, null, null)
        await(checkNotNull(video.id))
        val first = assertNotNull(transcripts.findSpoken(checkNotNull(video.id), "fake"))

        val refused = assertFailsWith<IllegalArgumentException> { mcp.transcribeEpisode("S09E81", null, null, null, null, null) }
        assertTrue("has a transcript by fake already" in refused.message.orEmpty(), refused.message)

        fake.heard[video.path] = listOf(10.0 to "Drugič.")
        // Heard as English this time, which it is, so it replaces the Slovenian one rather than standing beside it
        mcp.transcribeEpisode("S09E81", video.id, null, "EN-us", null, true)
        await(checkNotNull(video.id))
        val second = assertNotNull(transcripts.findSpoken(checkNotNull(video.id), "fake"))
        assertNotEquals(first.id, second.id)
        // The one it replaced went, and what it said with it
        assertTrue(!texts.existsById(checkNotNull(first.id)), "the replaced transcript's text is gone")
        assertEquals("en-US", second.language)
        assertEquals(listOf(second.id), transcripts.findByMediaId(checkNotNull(video.id)).map { it.id })
        assertEquals("[0:00:00] Drugič.", mcp.getEpisodeTranscript("S09E81", video.id, null).transcripts.single().text)
    }

    @Test
    fun `transcribes a file by each provider, to compare them, each replacing only its own`() {
        val episode = episodes.saveAndFlush(Episode(title = "Primerjava", code = "S09E84"))
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E84.mp3")
        fake.heard[audio.path] = listOf(5.0 to "Prvi posluša.")
        (providers.of("other") as FakeSpeech).heard[audio.path] = listOf(5.0 to "Drugi posluša.")
        val other = checkNotNull(audio.id)

        mcp.transcribeEpisode("S09E84", null, null, null, null, null)
        await(other)
        mcp.transcribeEpisode("S09E84", null, " Other ", null, null, null)
        await(other, "other")
        assertEquals(setOf("fake", "other"), transcripts.findByMediaId(other).map { it.provider }.toSet())
        val theirs = mcp.getEpisodeTranscript("S09E84", null, "other").transcripts.single()
        assertEquals("other" to "[0:00:00] Drugi posluša.", theirs.provider to theirs.text)

        // Each refuses its own again, unless replaced, which leaves the other's be
        val refused = assertFailsWith<IllegalArgumentException> { mcp.transcribeEpisode("S09E84", null, "other", null, null, null) }
        assertTrue("has a transcript by other already" in refused.message.orEmpty(), refused.message)
        val fakes = transcripts.findSpoken(other, "fake")?.id
        mcp.transcribeEpisode("S09E84", null, "other", null, null, true)
        await(other, "other")
        assertEquals(fakes, transcripts.findSpoken(other, "fake")?.id)
        assertEquals(2, transcripts.findByMediaId(other).size)

        val unknown = assertFailsWith<IllegalArgumentException> { mcp.transcribeEpisode("S09E84", null, "whisper", null, null, true) }
        assertTrue("no speech-to-text provider whisper" in unknown.message.orEmpty(), unknown.message)
    }

    @Test
    fun `transcribes no more at once than a provider has turns, the rest in line, and takes a turn back from one that ended`() {
        val single = providers.of("single") as FakeSpeech
        val episode = episodes.saveAndFlush(Episode(title = "Vrsta", code = "S09E86"))
        val first = file(episode, MediaKind.FULL_AUDIO, "S09E86.mp3")
        val second = file(episode, MediaKind.FULL_VIDEO, "S09E86.mp4")
        single.heard[first.path] = listOf(4.0 to "Prvi.")
        single.heard[second.path] = listOf(4.0 to "Drugi.")
        single.holding = true

        val firstOn = mcp.transcribeEpisode("S09E86", first.id, "single", null, null, null)
        until("the first was heard") { transcription("S09E86", first, "single")?.status == TranscriptionStatus.RECOGNIZING }
        assertEquals(50, transcription("S09E86", first, "single")?.percent)
        val secondOn = mcp.transcribeEpisode("S09E86", second.id, "single", null, null, null)
        until("the second waited its turn, next in line") {
            mcp.getTranscription(secondOn.id).let { it.status == TranscriptionStatus.QUEUED && it.queued == 1 }
        }

        // The first ends without giving its turn back (terminated), which its slots take back, and the second has it
        client.newUntypedWorkflowStub(TranscriptionWorkflow.workflowId(checkNotNull(first.id), "single")).terminate("test")
        single.holding = false
        val heard = try {
            await(checkNotNull(second.id), "single")
        } catch (e: TimeoutException) {
            val slots = runCatching { client.newWorkflowStub(SlotsWorkflow::class.java, SlotsWorkflow.workflowId("single")).state() }
            val first = runCatching { client.newUntypedWorkflowStub(TranscriptionWorkflow.workflowId(checkNotNull(first.id), "single")).describe().status }
            throw AssertionError("The second never had its turn; single's slots: $slots, the first: $first", e)
        }
        assertEquals(1, heard.segments)
        assertEquals("[0:00:00] Drugi.", mcp.getEpisodeTranscript("S09E86", second.id, "single").transcripts.single().text)
        assertEquals(TranscriptionStatus.DONE, mcp.getTranscription(secondOn.id).status)
        // The first, which ended without saying so, as failed once it's asked after
        mcp.getTranscription(firstOn.id).let {
            assertEquals(TranscriptionStatus.FAILED, it.status)
            assertEquals("Its workflow ended without saying how it went", it.error)
        }
    }

    @Test
    fun `has a file transcribed again go first, after its run ended holding the turn`() {
        val single = providers.of("single") as FakeSpeech
        val episode = episodes.saveAndFlush(Episode(title = "Znova", code = "S09E88"))
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E88.mp3")
        single.heard[audio.path] = listOf(4.0 to "Znova.")
        single.holding = true
        mcp.transcribeEpisode("S09E88", null, "single", null, null, null)
        until("it was heard") { transcription("S09E88", audio, "single")?.status == TranscriptionStatus.RECOGNIZING }

        // Its workflow ID again, while the slots still count the one before as holding the turn
        client.newUntypedWorkflowStub(TranscriptionWorkflow.workflowId(checkNotNull(audio.id), "single")).terminate("test")
        mcp.transcribeEpisode("S09E88", null, "single", null, null, null)
        single.holding = false
        assertEquals(1, await(checkNotNull(audio.id), "single").segments)
    }

    @Test
    fun `waits out a provider that's too busy to start, asking again ever less often`() {
        val episode = episodes.saveAndFlush(Episode(title = "Zasedeno", code = "S09E87"))
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E87.mp3")
        fake.heard[audio.path] = listOf(4.0 to "Končno.")
        val before = fake.starts.get()
        fake.busyStarts = 2

        mcp.transcribeEpisode("S09E87", null, null, null, null, null)
        assertEquals(1, await(checkNotNull(audio.id)).segments)
        assertEquals(3, fake.starts.get() - before)
    }

    @Test
    fun `cancels a transcription that's on, and the provider's with it`() {
        val other = providers.of("other") as FakeSpeech
        val episode = episodes.saveAndFlush(Episode(title = "Preklic", code = "S09E89"))
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E89.mp3")
        other.heard[audio.path] = listOf(4.0 to "Nikoli.")
        other.holding = true
        try {
            val started = mcp.transcribeEpisode("S09E89", null, "other", null, null, null)
            until("it was heard") { transcription("S09E89", audio, "other")?.status == TranscriptionStatus.RECOGNIZING }
            val before = other.cancelled.size

            mcp.cancelTranscription(started.id)
            until("it was cancelled") { mcp.listTranscriptions(null, "other", "S09E89", null).single().status == TranscriptionStatus.CANCELLED }
            assertEquals(before + 1, other.cancelled.size)
            assertNull(transcripts.findSpoken(checkNotNull(audio.id), "other"))
            val again = assertFailsWith<IllegalArgumentException> { mcp.cancelTranscription(started.id) }
            assertTrue("is cancelled already" in again.message.orEmpty(), again.message)
        } finally {
            other.holding = false
        }
    }

    @Test
    fun `says why a transcription failed`() {
        val episode = episodes.saveAndFlush(Episode(title = "Tišina", code = "S09E90"))
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E90.mp3")
        fake.heard[audio.path] = listOf(4.0 to "")
        fake.failing += audio.path

        mcp.transcribeEpisode("S09E90", null, null, null, null, null)
        assertFailsWith<WorkflowFailedException> { await(checkNotNull(audio.id)) }
        val failed = mcp.listTranscriptions("failed", null, "S09E90", null).single()
        assertEquals("Speech-to-text couldn't transcribe it: no speech", failed.error)
        assertEquals(TranscriptionStatus.FAILED, mcp.getEpisodeTranscript("S09E90", null, null).transcriptions.single().status)
    }

    @Test
    fun `removes a transcript, and what it says`() {
        val episode = episodes.saveAndFlush(Episode(title = "Odstrani", code = "S09E91"))
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E91.mp3")
        fake.heard[audio.path] = listOf(4.0 to "Kmalu ne bo.")
        mcp.transcribeEpisode("S09E91", null, null, null, null, null)
        val id = await(checkNotNull(audio.id)).transcriptId

        assertEquals("S09E91.mp3", mcp.removeTranscript(id).media)
        assertTrue(transcripts.findByMediaId(checkNotNull(audio.id)).isEmpty())
        assertTrue(!texts.existsById(id), "its text went with it")
        assertTrue(history.of(checkNotNull(episode.id)).any { it.summary == "Removed the transcript of S09E91.mp3" })
        assertFailsWith<IllegalArgumentException> { mcp.removeTranscript(id) }
    }

    // Approved users, through MetaOS's pages: one who may only read, and one who may also write
    private val reader = user("ana", API_READ_SCOPE)
    private val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")

    private fun user(username: String, scope: String) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", scope)
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        // Answered right away, unless a resolver is asynchronous
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions).andExpect(status().isOk)
    }

    @Test
    fun `transcribes, lists, reads and removes over GraphQL, for the team`() {
        val episode = episodes.saveAndFlush(Episode(title = "Prek GraphQL", code = "S09E92"))
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E92.mp3")
        fake.heard[audio.path] = listOf(4.0 to "Dober dan.", 9.0 to "Nasvidenje.")
        val transcribe = """mutation { transcribeEpisode(ref: "S09E92") { id status provider mediaName episode { code } requestedBy } }"""

        // Changing takes metaos:write, reading the team's
        graphql(transcribe, reader).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        graphql("{ transcriptions { id } }").andExpect(jsonPath("$.errors[0].extensions.classification", anyOf(equalTo("UNAUTHORIZED"), equalTo("FORBIDDEN"))))

        val asked = graphql(transcribe, writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.transcribeEpisode.provider").value("fake"))
            .andExpect(jsonPath("$.data.transcribeEpisode.mediaName").value("S09E92.mp3"))
            .andExpect(jsonPath("$.data.transcribeEpisode.episode.code").value("S09E92"))
            .andExpect(jsonPath("$.data.transcribeEpisode.requestedBy").value("bojan"))
        val id = JsonPath.read<String>(asked.andReturn().response.contentAsString, "$.data.transcribeEpisode.id")
        val made = await(checkNotNull(audio.id)).transcriptId

        // By its ID, to watch it: done, with what it made; none for an ID there's none of, and nothing for anyone else
        graphql("""{ transcription(id: "$id") { status transcript { id } } }""", reader)
            .andExpect(jsonPath("$.data.transcription.status").value("DONE"))
            .andExpect(jsonPath("$.data.transcription.transcript.id").value(made.toString()))
        graphql("""{ transcription(id: "${UUID.randomUUID()}") { status } }""", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.transcription").value(null as Any?))
        graphql("""{ transcription(id: "$id") { status } }""")
            .andExpect(jsonPath("$.errors[0].extensions.classification", anyOf(equalTo("UNAUTHORIZED"), equalTo("FORBIDDEN"))))

        graphql("""{ transcriptions(statuses: [DONE], limit: 500) { status mediaName transcript { id segments lines { start text } } } }""", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.transcriptions[?(@.mediaName == 'S09E92.mp3')].transcript.id", contains(made.toString())))
            .andExpect(jsonPath("$.data.transcriptions[?(@.mediaName == 'S09E92.mp3')].transcript.lines[1].text", contains("Nasvidenje.")))
        graphql("""{ episode(ref: "S09E92") { language transcripts { id provider } } }""", reader)
            .andExpect(jsonPath("$.data.episode.language").value("sl-SI"))
            .andExpect(jsonPath("$.data.episode.transcripts[0].id").value(made.toString()))
        graphql("""{ speechProviders { name slots } }""", reader)
            .andExpect(jsonPath("$.data.speechProviders[?(@.name == 'single')].slots", contains(1)))

        graphql("""mutation { removeTranscript(id: "$made") }""", reader).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
        graphql("""mutation { removeTranscript(id: "$made") }""", writer).andExpect(jsonPath("$.data.removeTranscript").value(made.toString()))
        graphql("""{ transcript(id: "$made") { id } }""", reader).andExpect(jsonPath("$.data.transcript").value(null as Any?))
    }

    @Test
    fun `refuses an episode without full audio or video`() {
        episodes.saveAndFlush(Episode(title = "Brez", code = "S09E82"))
        val refused = assertFailsWith<IllegalArgumentException> { mcp.transcribeEpisode("S09E82", null, null, null, null, null) }
        assertTrue("has no full audio or video" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `hears an episode in its show's language, Borderless's in English, unless told another`() {
        val borderless = shows.saveAndFlush(Show(name = "Borderless Test", color = null, language = "en-US"))
        val episode = episodes.saveAndFlush(Episode(title = "Abroad", code = "S09E83").apply { show = borderless })
        val audio = file(episode, MediaKind.FULL_AUDIO, "S09E83.mp3")
        fake.heard[audio.path] = listOf(4.0 to "Welcome to Ogrodje.")

        mcp.transcribeEpisode("S09E83", null, null, null, null, null)
        await(checkNotNull(audio.id))
        assertEquals("en-US", fake.languages[audio.path])
        assertEquals("en-US", mcp.getEpisodeTranscript("S09E83", null, null).transcripts.single().language)

        mcp.transcribeEpisode("S09E83", null, null, "sl-SI", null, true)
        await(checkNotNull(audio.id))
        assertEquals("sl-SI", fake.languages[audio.path])

        val refused = assertFailsWith<IllegalArgumentException> { mcp.transcribeEpisode("S09E83", null, null, "slovenian!", null, true) }
        assertTrue("isn't a language tag" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `hears Google's results as pieces from their first timed word, else where the one before ended, with their words`() {
        val results = """
            {"results": [
              {"alternatives": [{"transcript": "dober dan", "words": [{"endOffset": "0.600s", "word": "dober"}, {"startOffset": "0.600s", "endOffset": "1.100s", "word": "dan", "confidence": 0.75}]}], "resultEndOffset": "3.500s"},
              {"alternatives": [{"transcript": " "}], "resultEndOffset": "7s"},
              {"alternatives": [{"transcript": "hvala", "words": [{"startOffset": "8s", "word": "hvala"}]}], "resultEndOffset": "9.250s"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(
                // A word at 0 s has no start in Google's JSON
                TranscriptSegment(null, 0.0, 3.5, "dober dan", listOf(TranscriptWord(0.0, 0.6, "dober"), TranscriptWord(0.6, 1.1, "dan", 0.75))),
                // Untimed words aren't kept, but where the first starts is
                TranscriptSegment(null, 8.0, 9.25, "hvala"),
            ),
            googleHeard(results),
        )
    }

    @Test
    fun `cuts what was heard into stretches at a pause, a sentence's end or 20 seconds, whoever heard it`() {
        fun words(vararg timed: Pair<Double, String>) = timed.map { (start, text) -> TranscriptWord(start, start + 0.5, text) }
        // A minute of Google's, unpunctuated, without pauses: 20 s at most
        val minute = words(*(0 until 60).map { it.toDouble() to "w$it" }.toTypedArray())
        assertEquals(
            listOf(0.0 to 19.5, 20.0 to 39.5, 40.0 to 59.5),
            inStretches(listOf(TranscriptSegment(null, 0.0, 60.0, "", minute))).map { it.start to it.end },
        )
        // A sentence's end once it's 8 s long, but not before, and a pause of a second or more
        val spoken = words(
            0.0 to "Prvi", 1.0 to "stavek.", 2.0 to "še", 3.0 to "in", 4.0 to "še", 5.0 to "malo", 6.0 to "in", 7.0 to "še",
            8.0 to "konec.", 9.0 to "Nato", 11.0 to "pavza",
        )
        val stretches = inStretches(listOf(TranscriptSegment("1", 0.0, 11.5, "", spoken)))
        assertEquals(listOf("Prvi stavek. še in še malo in še konec.", "Nato", "pavza"), stretches.map { it.text })
        assertEquals(listOf(0.0 to 8.5, 9.0 to 9.5, 11.0 to 11.5), stretches.map { it.start to it.end })
        assertEquals(setOf("1"), stretches.map { it.speaker }.toSet())
        // A piece without timed words stays as it is
        val untimed = TranscriptSegment(null, 0.0, 90.0, "brez besed")
        assertEquals(listOf(untimed), inStretches(listOf(untimed)))
    }

    @Test
    fun `says h-mm-ss as YouTube's chapters do`() {
        assertEquals("0:00:00", timestamp(0.4))
        assertEquals("1:02:05", timestamp(Duration.ofMinutes(62).plusSeconds(5).seconds.toDouble()))
    }
}
