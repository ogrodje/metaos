package si.ogrodje.metaos

import com.sun.net.httpserver.HttpServer
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * patreon-gateway as its worker of MetaOS's workflows' activities: makes drafts, changes and deletes them, and puts
 * files and pictures on them, as it's told to, failing [busy] times first as Patreon turning it away does
 */
@ActivityImpl(taskQueues = [PATREON_QUEUE])
class FakePatreonGateway : PatreonActivities {
    val calls = CopyOnWriteArrayList<String>()
    val media = CopyOnWriteArrayList<PatreonMediaRequest>()
    val images = CopyOnWriteArrayList<PatreonImageRequest>()
    val busy = ConcurrentHashMap<String, AtomicInteger>()

    private fun post(id: String, title: String?) =
        mapOf("id" to id, "title" to title, "editUrl" to "https://www.patreon.com/posts/$id/edit", "draft" to true)

    private fun turnedAway(id: String) {
        if ((busy[id]?.getAndDecrement() ?: 0) > 0) {
            throw ApplicationFailure.newFailure("Patreon is turning the gateway away for now (429): try again in a few minutes", "PatreonBusyException")
        }
    }

    override fun createDraft(spec: PatreonPostSpec): Map<String, Any?> {
        calls += "create ${spec.title} ${spec.access?.kind} ${spec.access?.tiers}"
        return post("173396086", spec.title)
    }

    override fun update(id: String, spec: PatreonPostSpec): Map<String, Any?> {
        calls += "update $id ${spec.title}"
        turnedAway(id)
        if (id == "1") throw ApplicationFailure.newNonRetryableFailure("Patreon has no /api/posts/1", "IllegalArgumentException")
        return post(id, spec.title)
    }

    override fun attachMedia(id: String, request: PatreonMediaRequest): Map<String, Any?> {
        calls += "attach $id"
        media += request
        return mapOf("post" to post(id, null), "media" to mapOf("id" to "767881200", "fileName" to request.fileName, "state" to "ready"))
    }

    override fun setImage(id: String, request: PatreonImageRequest): Map<String, Any?> {
        calls += "image $id"
        images += request
        return mapOf("post" to post(id, null), "media" to mapOf("id" to "767881201", "state" to "ready"))
    }

    override fun delete(id: String): Map<String, Any?> {
        calls += "delete $id"
        return mapOf("deleted" to id)
    }
}

@SpringBootTest(
    properties = [
        "metaos.storage.bucket=ogrodje-metaos-test", "metaos.media.bucket=ogrodje-metaos-public-test",
        "metaos.dropbox.app-key=key", "metaos.dropbox.app-secret=secret", "metaos.dropbox.refresh-token=test",
        "spring.temporal.test-server.enabled=true",
        "metaos.patreon.gateway-token=secret",
        "metaos.slots.patreon=1",
    ]
)
@Import(TestcontainersConfig::class, ImagesTest.Config::class, MediaFilesTest.Config::class, PatreonChangesTest.Config::class)
class PatreonChangesTest @Autowired constructor(
    private val mcp: PatreonMCP,
    private val gateway: FakePatreonGateway,
    private val changes: PatreonChanges,
    private val episodes: EpisodesRepository,
    private val mediaFiles: MediaFilesRepository,
    private val images: ImagesMCP,
    private val dropbox: FakeDropbox,
) {

    @TestConfiguration
    class Config {
        @Bean
        fun fakePatreonGateway() = FakePatreonGateway()
    }

    companion object {
        // The gateway's own API, for what MetaOS reads: a draft without its audio yet, and one that's ready
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.toString()
                val (status, body) = when (path) {
                    "/posts/172923221/web" -> 200 to draft("172923221", "S05E08 Poletna vročica", "podcast")
                    "/posts/172923221/media" -> 200 to "[]"
                    "/posts/172923222/web" -> 200 to draft("172923222", "S05E08 Poletna vročica VIDEO", "video_external_file")
                    "/posts/172923222/media" -> 200 to """[{"kind":"VIDEO","fileName":"S05E08.mp4","sizeBytes":2749623806,"durationSeconds":5837,"url":"https://stream.mux.com/abc/highest.mp4?token=t","expiresAt":"2026-10-27T02:00:00Z","referer":"https://www.patreon.com/"}]"""
                    "/posts/172923223/web" -> 200 to draft("172923223", "", "text_only")
                    else -> 404 to """{"error":"No $path"}"""
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        private fun draft(id: String, title: String, type: String) =
            """{"id":"$id","title":"$title","url":null,"editUrl":"https://www.patreon.com/posts/$id/edit","publishedAt":null,"scheduledFor":null,"draft":true,"type":"$type","editedAt":"2026-10-25T20:00:00Z"}"""

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("metaos.storage.key", ::fakeServiceAccountKey)
            registry.add("metaos.patreon.gateway-url") { "http://127.0.0.1:${server.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }

    private fun done(job: PatreonJob): PatreonJob {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        var current = job
        while (!current.done) {
            check(System.nanoTime() < deadline) { "Never done: $current" }
            Thread.sleep(50)
            current = changes.await(current, Duration.ofSeconds(5))
        }
        return current
    }

    @Test
    fun `a draft is made by the gateway, in a workflow, and comes back done`() {
        val job = mcp.createDraft("S05E08", "Text", PatreonAccess.TIERS, listOf("9650087"))
        assertEquals("SUCCEEDED", job.status, job.toString())
        assertEquals("create-draft", job.kind)
        assertTrue(job.id.startsWith("patreon-create-draft-"), job.id)
        assertEquals("173396086", job.result!!["id"].asString())
        assertTrue("create S05E08 TIERS [9650087]" in gateway.calls, gateway.calls.toString())
        // And followed by its ID
        assertEquals(job, mcp.job(job.id))
    }

    @Test
    fun `a change Patreon turned away is tried again, and says so meanwhile`() {
        gateway.busy["172000001"] = AtomicInteger(1)
        val job = changes.start(PatreonChange(PatreonChangeKind.UPDATE_POST, "172000001", PatreonPostSpec(title = "S05E09")))
        // Waiting to try again, with why
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        var waiting = mcp.job(job.id)
        while (waiting.error == null && !waiting.done) {
            check(System.nanoTime() < deadline) { "Never turned away: $waiting" }
            Thread.sleep(50)
            waiting = mcp.job(job.id)
        }
        // Waiting to try again (QUEUED, till retryAt), or, as Temporal's test server skips the wait, at it again
        if (!waiting.done) {
            assertTrue(waiting.error!!.startsWith("Patreon is turning the gateway away"), waiting.error)
            assertTrue(waiting.status == "QUEUED" && waiting.retryAt != null || waiting.status == "RUNNING" && waiting.attempts == 2, waiting.toString())
        }
        val finished = done(job)
        assertEquals("SUCCEEDED", finished.status, finished.toString())
        assertEquals(2, gateway.calls.count { it == "update 172000001 S05E09" })
    }

    @Test
    fun `what trying again won't change fails at once, with why`() {
        val job = mcp.update("1", "S05E09", null, null, null)
        assertEquals("FAILED", job.status, job.toString())
        assertEquals("Patreon has no /api/posts/1", job.error)
        assertEquals(1, gateway.calls.count { it == "update 1 S05E09" })
    }

    @Test
    fun `an episode's file in MetaOS goes to the gateway as a link to it, named as it is`() {
        val episode = episodes.saveAndFlush(Episode(title = "Poletna vročica", code = "S05E70"))
        val file = mediaFiles.saveAndFlush(
            MediaFile(
                episodeId = checkNotNull(episode.id), kind = MediaKind.FULL_AUDIO, title = null, name = "S05E70.mp3",
                path = "episode-media/${UUID.randomUUID()}/S05E70.mp3", contentType = "audio/mpeg",
                size = 1000, md5 = null, crc32c = null, uploaderId = "ana-id", uploader = "ana",
                info = MediaInfo(
                    duration = 3600.0, bitrate = null, container = null, videoCodec = null, videoProfile = null, width = null,
                    height = null, frameRate = null, bitDepth = null, color = null, audioCodec = "mp3", sampleRate = 48000,
                    channels = 2, audioBitrate = null, audioTracks = 1, madeAt = null, encoder = null,
                ),
                probe = null,
            )
        )
        val job = done(mcp.setMedia("172923221", PatreonMediaKind.AUDIO, file.id.toString(), null, null, null, null))
        assertEquals("SUCCEEDED", job.status, job.toString())
        val sent = gateway.media.last()
        assertEquals(PatreonMediaKind.AUDIO, sent.kind)
        assertEquals("S05E70.mp3", sent.fileName)
        assertTrue(sent.url.startsWith("https://storage.example.com/") && file.path in sent.url, sent.url)

        // Another name on Patreon, if asked; and not as video, which it isn't
        done(mcp.setMedia("172923221", PatreonMediaKind.AUDIO, file.id.toString(), null, null, null, "Poletna vročica.mp3"))
        assertEquals("Poletna vročica.mp3", gateway.media.last().fileName)
        assertFailsWith<IllegalArgumentException> { mcp.setMedia("172923221", PatreonMediaKind.VIDEO, file.id.toString(), null, null, null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.setMedia("172923221", PatreonMediaKind.AUDIO, UUID.randomUUID().toString(), null, null, null, null) }
    }

    @Test
    fun `an episode's picture in MetaOS becomes a post's thumbnail, by its public URL`() {
        episodes.saveAndFlush(Episode(title = "Thumbnail", code = "S09E80"))
        dropbox.put("2001", "/S09E80", null)
        dropbox.put("2001", "/S09E80/ogrodje-s09e80-thumb1.png", picture(1920, 1080, "png"))
        dropbox.put("2001", "/S09E80/ogrodje-s09e80-podcast.png", picture(3000, 3000, "png"))
        val thumbnail = images.setEpisodeImage("S09E80", EpisodeImage.THUMBNAIL, ImageSource.DROPBOX, "Artwork", "/S09E80/ogrodje-s09e80-thumb1.png", null)
        val cover = images.setEpisodeImage("S09E80", EpisodeImage.COVER, ImageSource.DROPBOX, "Artwork", "/S09E80/ogrodje-s09e80-podcast.png", null)

        val job = mcp.setImage("173396086", "S09E80", null, null)
        assertEquals("SUCCEEDED", job.status, job.toString())
        assertEquals("set-post-image", job.kind)
        assertEquals("767881201", job.result!!["media"]["id"].asString())
        assertEquals(PatreonImageRequest(thumbnail.image!!.url, "ogrodje-s09e80-thumb1.png"), gateway.images.last())

        // The cover, named as asked
        done(mcp.setImage("173396086", "S09E80", EpisodeImage.COVER, "cover.png"))
        assertEquals(PatreonImageRequest(cover.image!!.url, "cover.png"), gateway.images.last())
    }

    @Test
    fun `a post's thumbnail needs the episode to have the picture, before anything starts`() {
        episodes.saveAndFlush(Episode(title = "No pictures", code = "S09E82"))
        val calls = gateway.calls.size

        fun refused(call: () -> Unit) = assertFailsWith<IllegalArgumentException> { call() }.message.orEmpty()

        assertTrue("has no thumbnail" in refused { mcp.setImage("173396086", "S09E82", null, null) })
        assertTrue("has no cover" in refused { mcp.setImage("173396086", "S09E82", EpisodeImage.COVER, null) })
        assertTrue("not found" in refused { mcp.setImage("173396086", "S09E99", null, null) })
        refused { mcp.setImage("../jobs", "S09E82", null, null) }
        assertEquals(calls, gateway.calls.size)
    }

    @Test
    fun `a draft is deleted`() {
        val job = mcp.delete("172000002")
        assertEquals("SUCCEEDED", job.status, job.toString())
        assertEquals("172000002", job.result!!["deleted"].asString())
    }

    @Test
    fun `a draft for some tiers needs them, and a post's ID is a number, before anything starts`() {
        assertFailsWith<IllegalArgumentException> { mcp.createDraft("S05E08", null, PatreonAccess.TIERS, emptyList()) }
        assertFailsWith<IllegalArgumentException> { mcp.update("../jobs", "S05E08", null, null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.job("job-1") }
        assertFailsWith<IllegalArgumentException> { mcp.job("patreon-create-draft-${UUID.randomUUID()}") }
    }

    @Test
    fun `a draft is ready for a person to publish once it has a title, and media Patreon processed`() {
        val waiting = mcp.prepare("172923221")
        assertFalse(waiting.ready)
        assertEquals(listOf("Its audio, which Patreon may still be processing"), waiting.missing)

        val ready = mcp.prepare("172923222")
        assertTrue(ready.ready, ready.toString())
        assertEquals("https://www.patreon.com/posts/172923222/edit", ready.post.editUrl)
        assertEquals("VIDEO", ready.media.single().kind)

        assertEquals(listOf("Its title", "Its audio or video (set_patreon_post_media)"), mcp.prepare("172923223").missing)
    }

    @Test
    fun `publishing publishes nothing, it says what prepare_patreon_post does`() {
        val calls = gateway.calls.size
        assertEquals(mcp.prepare("172923222"), mcp.publish("172923222", null, true, "S05E08", Platform.PATREON))
        assertEquals(calls, gateway.calls.size)
        assertNull(gateway.calls.firstOrNull { it.startsWith("publish") })
    }
}
