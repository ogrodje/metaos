package si.ogrodje.metaos

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowFailedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
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
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A YouTube channel in memory: its videos as they are [onChannel], uploads that arrive chunk by chunk, each video
 * processed once it's asked after [processAfter] times, and the ways YouTube doesn't answer: [dropping] a connection
 * mid-upload, out of [quota], [holding] an upload, or [rejecting] what it got
 */
class FakeYouTube : YouTube {
    class Session(val details: YouTubeDetails, val contentType: String, val size: Long, val bytes: ByteArrayOutputStream = ByteArrayOutputStream())

    val channel = YouTubeChannel("UC-ogrodje", "Ogrodje", "@ogrodje", "UU-ogrodje")
    val onChannel = ConcurrentHashMap<String, YouTubeVideoInfo>()
    val sessions = ConcurrentHashMap<String, Session>()
    val uploaded = ConcurrentHashMap<String, Session>()
    val thumbnails = ConcurrentHashMap<String, Pair<String, Int>>()
    val starts = AtomicInteger()
    val chunks = AtomicInteger()
    private val checks = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile var processAfter = 1
    @Volatile var dropping = 0
    @Volatile var quota = 0
    @Volatile var holding = false
    @Volatile var rejecting: String? = null
    // How many sessions it lets go of, each as it's asked where it is
    @Volatile var forgetting = 0
    @Volatile var noThumbnails = false

    fun video(id: String, title: String, privacy: YouTubePrivacy = YouTubePrivacy.PUBLIC, description: String = "", status: String = "processed") =
        YouTubeVideoInfo(
            id, channel.id, title, description, emptyList(), "28", "sl", privacy, status, null, null,
            Instant.parse("2026-11-01T10:00:00Z"), 3600, false, "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
        ).also { onChannel[id] = it }

    override fun channel() = channel

    override fun playlistVideoIds(playlist: String): List<String> {
        if (quota > 0) {
            quota--
            throw YouTubeQuota("YouTube answered 403 (quotaExceeded): out for the day")
        }
        return onChannel.keys.sorted()
    }

    // One that's just uploaded is processed once it's been asked after enough
    override fun videos(ids: Collection<String>): List<YouTubeVideoInfo> = ids.mapNotNull { id ->
        val video = onChannel[id] ?: return@mapNotNull null
        if (video.uploadStatus == "uploaded" && uploaded.containsKey(id) && checks.getValue(id).incrementAndGet() > processAfter) {
            val rejected = rejecting
            onChannel[id] = if (rejected != null) video.copy(uploadStatus = "rejected", problem = rejected) else video.copy(uploadStatus = "processed", duration = 3600)
        }
        onChannel[id]
    }

    override fun startUpload(details: YouTubeDetails, contentType: String, size: Long): String {
        if (quota > 0) {
            quota--
            throw YouTubeQuota("YouTube answered 403 (quotaExceeded): out for the day")
        }
        starts.incrementAndGet()
        return "session-${UUID.randomUUID()}".also { sessions[it] = Session(details, contentType, size) }
    }

    private fun session(id: String) = sessions[id] ?: throw YouTubeSessionGone("YouTube no longer has the upload's session")

    // Asked where an upload is, as after it broke off: its session may be gone by then
    override fun sent(session: String, size: Long): YouTubeSent {
        if (forgetting > 0) {
            forgetting--
            sessions.remove(session)
        }
        return YouTubeSent(session(session).bytes.size().toLong(), null)
    }

    override fun send(session: String, offset: Long, chunk: ByteArray, size: Long): YouTubeSent {
        val upload = session(session)
        while (holding) Thread.sleep(20)
        check(offset == upload.bytes.size().toLong()) { "Sent from $offset, while ${upload.bytes.size()} are here" }
        check(size == upload.size)
        // As a connection that broke after YouTube got the bytes
        upload.bytes.write(chunk)
        chunks.incrementAndGet()
        if (dropping > 0) {
            dropping--
            throw YouTubeException("YouTube couldn't be reached: Connection reset")
        }
        if (upload.bytes.size().toLong() < size) return YouTubeSent(upload.bytes.size().toLong(), null)
        val id = "yt" + UUID.randomUUID().toString().replace("-", "").take(9)
        uploaded[id] = upload
        checks[id] = AtomicInteger()
        onChannel[id] = YouTubeVideoInfo(
            id, channel.id, upload.details.title, upload.details.description, upload.details.tags, upload.details.categoryId,
            upload.details.language, YouTubePrivacy.PRIVATE, "uploaded", null, null, Instant.now(), null, false, null,
        )
        return YouTubeSent(size, id)
    }

    override fun update(videoId: String, details: YouTubeDetails): YouTubeVideoInfo {
        val video = onChannel[videoId] ?: throw YouTubeRefused("The channel has no video $videoId")
        return video.copy(title = details.title, description = details.description, tags = details.tags).also { onChannel[videoId] = it }
    }

    override fun setThumbnail(videoId: String, contentType: String, picture: ByteArray) {
        if (noThumbnails) throw YouTubeRefused("YouTube answered 403 (forbidden): The authenticated user doesn't have permissions to upload and set custom video thumbnails.")
        thumbnails[videoId] = contentType to picture.size
        onChannel.computeIfPresent(videoId) { _, video -> video.copy(thumbnailUrl = "https://i.ytimg.com/vi/$videoId/custom.jpg") }
    }
}

@SpringBootTest(
    properties = [
        "metaos.storage.bucket=ogrodje-metaos-test",
        "metaos.google.youtube-refresh-token=a-token",
        // Temporal in memory, MetaOS its worker
        "spring.temporal.test-server.enabled=true",
        "metaos.youtube.poll-every=100ms",
        "metaos.slots.check-every=200ms",
    ]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, ImagesTest.Config::class, MediaFilesTest.Config::class, YouTubeTest.Config::class)
class YouTubeTest @Autowired constructor(
    private val mvc: MockMvc,
    private val episodes: EpisodesRepository,
    private val media: MediaFilesRepository,
    private val images: ImagesRepository,
    private val catalogue: YouTubeVideosRepository,
    private val jobs: YouTubeUploadsRepository,
    private val storage: Storage,
    private val publicMedia: Media,
    private val youtube: YouTube,
    private val videos: YouTubeVideos,
    private val syncs: YouTubeSyncs,
    private val mcp: YouTubeMCP,
    private val distributions: DistributionsRepository,
    private val client: WorkflowClient,
    private val history: History,
    private val jdbc: JdbcTemplate,
    private val jsonMapper: JsonMapper,
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
        fun fakeYouTube(): YouTube = FakeYouTube()
    }

    private val fake get() = youtube as FakeYouTube
    private val bucket get() = storage as FakeStorage

    private fun signedInAs(username: String) {
        val jwt = Jwt.withTokenValue("token").header("alg", "none").subject("$username-id")
            .claim("preferred_username", username).claim("azp", "claude-code").claim("realm_access", APPROVED).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @AfterEach
    fun reset() {
        SecurityContextHolder.clearContext()
        fake.holding = false
        fake.dropping = 0
        fake.quota = 0
        fake.rejecting = null
        fake.forgetting = 0
        fake.noThumbnails = false
        fake.processAfter = 1
    }

    // One of the episode's files, in the bucket with what's in it
    private fun file(episode: Episode, kind: MediaKind, name: String, bytes: ByteArray = ByteArray(1000) { it.toByte() }, title: String? = null): MediaFile {
        val path = "episode-media/${UUID.randomUUID()}/$name"
        bucket.upload(path, if (kind.video) "video/mp4" else "audio/mpeg", bytes.inputStream(), overwrite = false)
        return media.saveAndFlush(
            MediaFile(
                episodeId = checkNotNull(episode.id), kind = kind, title = title, name = name, path = path,
                contentType = if (kind.video) "video/mp4" else "audio/mpeg", size = bytes.size.toLong(), md5 = null, crc32c = null,
                uploaderId = "ana-id", uploader = "ana",
                info = MediaInfo(
                    duration = 3600.0, bitrate = null, container = null, videoCodec = "h264", videoProfile = null, width = 1920,
                    height = 1080, frameRate = null, bitDepth = null, color = null, audioCodec = "aac", sampleRate = 48000,
                    channels = 2, audioBitrate = null, audioTracks = 1, madeAt = null, encoder = null,
                ),
                probe = null,
            )
        )
    }

    // The episode's thumbnail, as Images would have published it: its original, too big for YouTube, and a copy that isn't
    private fun thumbnail(episode: Episode, kind: EpisodeImage = EpisodeImage.THUMBNAIL): Image {
        val image = images.saveAndFlush(
            Image(
                sha256 = UUID.randomUUID().toString().replace("-", ""), name = "ogrodje-${episode.code}-thumb1.png", contentType = "image/png",
                size = 3_000_000, width = 1920, height = 1080, widths = intArrayOf(320, 640, 1280), format = "jpg",
                source = ImageSource.UPLOAD, sourcePlace = null, sourcePath = "thumb1.png",
            )
        )
        val public = publicMedia.storage
        public.upload(image.originalPath(), "image/png", ByteArray(3_000_000).inputStream(), overwrite = true)
        public.upload(image.path(1280), "image/jpeg", ByteArray(200_000).inputStream(), overwrite = true)
        public.upload(image.path(640), "image/jpeg", ByteArray(60_000).inputStream(), overwrite = true)
        episode.setImageId(kind, image.id)
        episodes.saveAndFlush(episode)
        return image
    }

    private fun await(file: MediaFile): YouTubeUploadResult =
        client.newUntypedWorkflowStub(YouTubeUploadWorkflow.workflowId(checkNotNull(file.id))).getResult(60, TimeUnit.SECONDS, YouTubeUploadResult::class.java)

    private fun until(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Never $what" }
            Thread.sleep(20)
        }
    }

    private fun audited(table: String) = jdbc.queryForObject("select count(*) from audit_log where table_name = ?", Long::class.java, table)!!

    @Test
    fun `keeps the channel's videos, each its episode's by its links, and writes nothing when nothing changed`() {
        val linked = episodes.saveAndFlush(Episode(title = "S povezavo", code = "S10E01").apply { youtubeUrl = "https://youtu.be/linkedVid01?si=x" })
        val posted = episodes.saveAndFlush(Episode(title = "Z objavo", code = "S10E02"))
        distributions.saveAndFlush(Distribution(checkNotNull(posted.id), null, Channel.YOUTUBE, "https://www.youtube.com/shorts/clipVideo01", "ana-id", "ana"))
        fake.video("linkedVid01", "S10E01: S povezavo", description = "Pogovor o povezavah")
        fake.video("clipVideo01", "Izsek", YouTubePrivacy.UNLISTED)
        fake.video("noOnesVid01", "Napovednik", YouTubePrivacy.PRIVATE)

        val first = videos.sync()
        assertTrue(first.added >= 3, first.toString())
        assertEquals(linked.id, videos.find("linkedVid01").episodeId)
        assertEquals(posted.id, videos.find("https://youtu.be/clipVideo01").episodeId)
        assertNull(videos.find("noOnesVid01").episodeId)

        // As it is on YouTube, with where it's watched and where it's published
        val video = mcp.getYouTubeVideo("linkedVid01")
        assertEquals("S10E01: S povezavo", video.title)
        assertEquals(YouTubePrivacy.PUBLIC, video.privacy)
        assertEquals("S10E01", video.episode)
        assertEquals(3600, video.duration)
        assertEquals("https://www.youtube.com/watch?v=linkedVid01", video.url)
        assertEquals("https://studio.youtube.com/video/linkedVid01/edit", video.studioUrl)
        assertEquals(listOf("linkedVid01"), mcp.listYouTubeVideos("S10E01", null, null, null, null, null).map { it.videoId })
        assertTrue("noOnesVid01" in mcp.listYouTubeVideos(null, "private", null, true, null, null).map { it.videoId })
        assertEquals(listOf("linkedVid01"), mcp.listYouTubeVideos(null, null, "pogovor POVEZAV", null, null, null).map { it.videoId })

        // Looked at again with nothing new: not a row written, so not a line in the audit log
        val before = audited("youtube_videos")
        assertEquals(YouTubeSyncSummary(first.videos, 0, 0, 0), videos.sync())
        assertEquals(before, audited("youtube_videos"))

        // What changed on YouTube changes here; what's gone from the channel is kept, as gone, and comes back with it
        fake.video("linkedVid01", "S10E01: Nov naslov", YouTubePrivacy.UNLISTED)
        val gone = fake.onChannel.remove("noOnesVid01")!!
        assertEquals(1 to 1, videos.sync().let { it.changed to it.gone })
        assertEquals("S10E01: Nov naslov" to YouTubePrivacy.UNLISTED, videos.find("linkedVid01").let { it.title to it.privacy })
        assertNotNull(videos.find("noOnesVid01").goneAt)
        assertTrue("noOnesVid01" !in mcp.listYouTubeVideos(null, null, null, null, null, 500).map { it.videoId })
        assertTrue("noOnesVid01" in mcp.listYouTubeVideos(null, null, null, null, true, 500).map { it.videoId })
        fake.onChannel["noOnesVid01"] = gone
        videos.sync()
        assertNull(videos.find("noOnesVid01").goneAt)

        // Whose a video is, once the team says so, a look at the channel leaves, also once the link that said otherwise is there
        signedInAs("ana")
        assertEquals("S10E02", mcp.updateYouTubeVideo("linkedVid01", null, null, null, "S10E02").episode)
        videos.sync()
        assertEquals(posted.id, videos.find("linkedVid01").episodeId)
        assertNull(mcp.updateYouTubeVideo("linkedVid01", null, null, null, "").episode)
        // What the sync found isn't in the episodes' histories; what the team said is
        assertEquals(
            listOf("Said the YouTube video \"S10E01: Nov naslov\" is this episode's", "Said the YouTube video \"S10E01: Nov naslov\" isn't this episode's"),
            history.of(checkNotNull(posted.id)).map { it.summary }.filter { "YouTube video" in it },
        )
        assertTrue(history.of(checkNotNull(linked.id)).none { "Added the YouTube video" in it.summary })
    }

    @Test
    fun `looks at the channel in a workflow, when the team asks`() {
        fake.video("workflowV01", "Prek workflowa")
        signedInAs("ana")
        val state = mcp.refreshYouTubeVideos()
        assertEquals(Triple(true, "UC-ogrodje", "@ogrodje"), Triple(state.connected, state.channelId, state.handle))
        val summary = client.newUntypedWorkflowStub("youtube-refresh").getResult(60, TimeUnit.SECONDS, YouTubeSyncSummary::class.java)
        assertTrue(summary.videos >= 1, summary.toString())
        assertNotNull(catalogue.findByVideoId("workflowV01"))
        assertTrue(!syncs.state().refreshing)
        assertEquals("Ogrodje", mcp.getYouTubeChannel().channel)
    }

    @Test
    fun `sends an episode's full video to YouTube as a private video, with its thumbnail`() {
        val episode = episodes.saveAndFlush(Episode(title = "Kako nastane epizoda", code = "S10E10").apply { summary = "Pogovor o **nastajanju** epizod." })
        val bytes = ByteArray(20_000_000) { (it % 251).toByte() }
        val video = file(episode, MediaKind.FULL_VIDEO, "S10E10.mp4", bytes)
        file(episode, MediaKind.FULL_AUDIO, "S10E10.mp3")
        thumbnail(episode)

        val chunksBefore = fake.chunks.get()
        signedInAs("ana")
        val started = mcp.uploadYouTubeVideo("S10E10", null, null, null, listOf("ogrodje", "#podcast"), null, null)
        assertTrue(started.status.on, started.status.name)
        assertEquals("S10E10.mp4", started.media)
        // By default what the episode says, and its thumbnail; private, whoever asks
        assertEquals("Kako nastane epizoda", started.title)
        assertEquals("Pogovor o **nastajanju** epizod.", started.description)
        assertEquals(listOf("ogrodje", "podcast"), started.tags)
        assertEquals(EpisodeImage.THUMBNAIL, started.thumbnail)
        assertEquals(YouTubePrivacy.PRIVATE, started.privacy)
        assertEquals("ana", started.requestedBy)

        val result = await(video)
        assertEquals("processed", result.uploadStatus)
        val done = mcp.getYouTubeUpload(started.id)
        assertEquals(YouTubeUploadStatus.DONE, done.status)
        assertEquals(result.videoId, done.videoId)
        assertEquals("https://studio.youtube.com/video/${result.videoId}/edit", done.studioUrl)
        assertNotNull(done.startedAt)
        assertNotNull(done.finishedAt)
        assertNull(done.percent)
        assertNull(done.note)
        assertEquals(listOf(done.id), mcp.listYouTubeUploads("done", "S10E10", null).map { it.id })

        // All of the file got there, in chunks, in one session, as what it is and in what's spoken in it
        val sent = fake.uploaded.getValue(result.videoId)
        assertContentEquals(bytes, sent.bytes.toByteArray())
        assertEquals(3, fake.chunks.get() - chunksBefore)
        assertEquals("video/mp4", sent.contentType)
        assertEquals(YouTubeDetails("Kako nastane epizoda", "Pogovor o **nastajanju** epizod.", listOf("ogrodje", "podcast"), "28", "sl"), sent.details)
        // Its thumbnail: the widest copy YouTube takes, not the original, which is over 2 MB
        assertEquals("image/jpeg" to 200_000, fake.thumbnails[result.videoId])

        // On the channel, private, and in MetaOS as the episode's, made from its file
        assertEquals(YouTubePrivacy.PRIVATE, fake.onChannel.getValue(result.videoId).privacy)
        val kept = mcp.listYouTubeVideos("S10E10", null, null, null, null, null).single()
        assertEquals(result.videoId, kept.videoId)
        assertEquals(YouTubePrivacy.PRIVATE, kept.privacy)
        assertEquals(video.id, kept.mediaId)
        assertEquals("processed", kept.uploadStatus)
        assertEquals("https://i.ytimg.com/vi/${result.videoId}/custom.jpg", kept.thumbnailUrl)
        // A look at the channel leaves it the episode's, though no link of the episode's says so
        videos.sync()
        assertEquals(episode.id, videos.find(result.videoId).episodeId)

        // In the episode's history once, as whoever asked for it, through the workflows
        val told = history.of(checkNotNull(episode.id)).filter { "YouTube" in it.summary }
        assertEquals(listOf("Sent \"Kako nastane epizoda\" to YouTube, as a private video"), told.map { it.summary })
        assertEquals("ana" to WORKFLOWS_CLIENT, told.single().let { it.actor to it.client })

        // It's there, so it isn't sent again, unless it's meant to be
        val refused = assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E10", null, null, null, null, null, null) }
        assertTrue("is on YouTube already" in refused.message.orEmpty(), refused.message)
        mcp.uploadYouTubeVideo("S10E10", video.id, "Še enkrat", "", null, "none", true)
        val again = await(video)
        assertEquals(setOf(result.videoId, again.videoId), mcp.listYouTubeVideos("S10E10", null, null, null, null, null).map { it.videoId }.toSet())
        assertNull(fake.thumbnails[again.videoId])
    }

    @Test
    fun `goes on from the chunk YouTube has after a connection broke, and starts over when its session is gone`() {
        val episode = episodes.saveAndFlush(Episode(title = "Prekinjena", code = "S10E11"))
        val bytes = ByteArray(30_000_000) { (it % 241).toByte() }
        val video = file(episode, MediaKind.FULL_VIDEO, "S10E11.mp4", bytes)
        val startsBefore = fake.starts.get()

        // The second chunk's answer is lost, though YouTube has it; out of quota before that
        fake.quota = 1
        fake.dropping = 1
        signedInAs("ana")
        mcp.uploadYouTubeVideo("S10E11", null, null, null, null, null, null)
        val result = await(video)
        assertContentEquals(bytes, fake.uploaded.getValue(result.videoId).bytes.toByteArray())
        // One session: it went on, rather than starting over
        assertEquals(1, fake.starts.get() - startsBefore)

        // A bonus, by its ID, under its own title, with no thumbnail unless asked
        val bonus = file(episode, MediaKind.BONUS_VIDEO, "S10E11-bonus.mp4", ByteArray(9_000_000) { 7 }, title = "Bonus: vprašanja")
        val started = mcp.uploadYouTubeVideo("S10E11", bonus.id, null, null, null, null, null)
        assertEquals("Bonus: vprašanja" to null, started.title to started.thumbnail)
        await(bonus)

        // A session YouTube let go of by the time the upload tries again: nothing to go on from, so it starts over
        val clip = file(episode, MediaKind.BONUS_VIDEO, "S10E11-izsek.mp4", ByteArray(9_000_000) { 9 }, title = "Izsek")
        val startsThen = fake.starts.get()
        fake.dropping = 1
        fake.forgetting = 1
        mcp.uploadYouTubeVideo("S10E11", clip.id, null, null, null, null, null)
        val sent = await(clip)
        assertEquals(2, fake.starts.get() - startsThen)
        assertEquals(9_000_000, fake.uploaded.getValue(sent.videoId).bytes.size())
    }

    @Test
    fun `refuses what YouTube wouldn't take, before anything is sent`() {
        val episode = episodes.saveAndFlush(Episode(title = "Samo zvok <3", code = "S10E12"))
        signedInAs("ana")
        val none = assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E12", null, null, null, null, null, null) }
        assertTrue("has no full video to send to YouTube" in none.message.orEmpty(), none.message)

        val audio = file(episode, MediaKind.FULL_AUDIO, "S10E12.mp3")
        val isAudio = assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E12", audio.id, null, null, null, null, null) }
        assertEquals("S10E12.mp3 is audio; YouTube takes video", isAudio.message)

        file(episode, MediaKind.FULL_VIDEO, "S10E12.mp4")
        // The episode's own title has what YouTube doesn't take
        assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E12", null, null, null, null, null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E12", null, "a".repeat(101), null, null, null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E12", null, "Naslov", "<b>krepko</b>", null, null, null) }
        val noPicture = assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E12", null, "Naslov", null, null, "THUMBNAIL_B", null) }
        assertTrue("has no thumbnail b" in noPicture.message.orEmpty(), noPicture.message)
        assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E12", null, "Naslov", null, null, "COVER", null) }
        assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E99", null, null, null, null, null, null) }
        assertTrue(jobs.findByEpisodeIdInOrderByCreatedAtDesc(listOf(checkNotNull(episode.id))).isEmpty())
    }

    @Test
    fun `uploads take turns, and a cancelled one stops and says so`() {
        val episode = episodes.saveAndFlush(Episode(title = "V vrsti", code = "S10E13"))
        val first = file(episode, MediaKind.FULL_VIDEO, "S10E13.mp4", ByteArray(9_000_000))
        val second = file(episode, MediaKind.BONUS_VIDEO, "S10E13-bonus.mp4", ByteArray(1000))
        signedInAs("ana")
        fake.holding = true
        val sending = mcp.uploadYouTubeVideo("S10E13", first.id, null, null, null, null, null)
        until("sending") { mcp.getYouTubeUpload(sending.id).status == YouTubeUploadStatus.UPLOADING }
        val waiting = mcp.uploadYouTubeVideo("S10E13", second.id, "Bonus", null, null, null, null)
        until("in line") { mcp.getYouTubeUpload(waiting.id).queued == 1 }
        assertEquals(YouTubeUploadStatus.QUEUED, mcp.getYouTubeUpload(waiting.id).status)
        assertEquals(setOf(sending.id, waiting.id), mcp.listYouTubeUploads("on", "S10E13", null).map { it.id }.toSet())

        // While it's on, it isn't asked for again
        val twice = assertFailsWith<IllegalArgumentException> { mcp.uploadYouTubeVideo("S10E13", first.id, null, null, null, null, null) }
        assertEquals("S10E13.mp4 is being sent to YouTube already", twice.message)

        // Cancelled, the first gives its turn to the second, and leaves nothing on the channel
        mcp.cancelYouTubeUpload(sending.id)
        assertFailsWith<WorkflowFailedException> { await(first) }
        until("cancelled") { mcp.getYouTubeUpload(sending.id).status == YouTubeUploadStatus.CANCELLED }
        fake.holding = false
        val done = await(second)
        assertEquals(YouTubeUploadStatus.DONE, mcp.getYouTubeUpload(waiting.id).status)
        assertEquals(listOf(done.videoId), mcp.listYouTubeVideos("S10E13", null, null, null, null, null).map { it.videoId })
        val over = assertFailsWith<IllegalArgumentException> { mcp.cancelYouTubeUpload(sending.id) }
        assertEquals("The upload of S10E13.mp4 is cancelled already", over.message)
    }

    @Test
    fun `says when YouTube rejected a video, and when only its thumbnail didn't go`() {
        val episode = episodes.saveAndFlush(Episode(title = "Zavrnjena", code = "S10E14"))
        val video = file(episode, MediaKind.FULL_VIDEO, "S10E14.mp4")
        thumbnail(episode)
        signedInAs("ana")

        // A channel that isn't verified takes no thumbnails: the video still goes
        fake.noThumbnails = true
        val started = mcp.uploadYouTubeVideo("S10E14", null, null, null, null, null, null)
        await(video)
        val done = mcp.getYouTubeUpload(started.id)
        assertEquals(YouTubeUploadStatus.DONE, done.status)
        assertTrue(done.note.orEmpty().startsWith("Its thumbnail wasn't set: YouTube answered 403 (forbidden)"), done.note)

        // Rejected, e.g. as a duplicate: the upload failed, and says where the video is
        fake.rejecting = "duplicate"
        val again = mcp.uploadYouTubeVideo("S10E14", null, null, null, null, "NONE", true)
        assertFailsWith<WorkflowFailedException> { await(video) }
        until("failed") { mcp.getYouTubeUpload(again.id).status == YouTubeUploadStatus.FAILED }
        val failed = mcp.getYouTubeUpload(again.id)
        assertTrue(failed.error.orEmpty().startsWith("YouTube has the video, but rejected it: duplicate. It's on the channel, private: https://studio.youtube.com/video/"), failed.error)
        assertEquals("rejected", videos.find(checkNotNull(failed.videoId)).uploadStatus)
    }

    @Test
    fun `changes what a video says and its thumbnail, never who may see it`() {
        val episode = episodes.saveAndFlush(Episode(title = "Sprememba", code = "S10E15").apply { youtubeUrl = "https://www.youtube.com/watch?v=changeVid01" })
        fake.video("changeVid01", "Stari naslov", YouTubePrivacy.PUBLIC, "Star opis")
        videos.sync()
        thumbnail(episode, EpisodeImage.THUMBNAIL_B)

        signedInAs("ana")
        val changed = mcp.updateYouTubeVideo("changeVid01", "Nov naslov", null, listOf("nova", "oznaka"), null)
        assertEquals(Triple("Nov naslov", "Star opis", listOf("nova", "oznaka")), Triple(changed.title, changed.description, changed.tags))
        assertEquals(YouTubePrivacy.PUBLIC, fake.onChannel.getValue("changeVid01").privacy)
        assertEquals("Nov naslov", fake.onChannel.getValue("changeVid01").title)
        val told = history.of(checkNotNull(episode.id)).single { "YouTube" in it.summary }
        assertEquals("Changed the YouTube video \"Nov naslov\"", told.summary)
        assertEquals(listOf("Title", "Tags"), told.fields.map { it.field })

        // What's left out stays as YouTube has it now, e.g. a title changed in Studio since MetaOS last looked, so an
        // A/B test of titles that's on there isn't stopped by a change to the description
        fake.onChannel["changeVid01"] = fake.onChannel.getValue("changeVid01").copy(title = "Naslov iz Studia")
        val described = mcp.updateYouTubeVideo("changeVid01", null, "Nov opis", null, null)
        assertEquals("Naslov iz Studia", described.title)
        assertEquals("Naslov iz Studia", fake.onChannel.getValue("changeVid01").title)
        assertEquals(listOf("nova", "oznaka"), described.tags)
        assertFailsWith<IllegalArgumentException> { mcp.updateYouTubeVideo("changeVid01", null, null, null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.updateYouTubeVideo("changeVid01", "<Naslov>", null, null, null) }
        assertFailsWith<IllegalArgumentException> { mcp.updateYouTubeVideo("nobodysVid1", "Naslov", null, null, null) }

        val noPicture = assertFailsWith<IllegalArgumentException> { mcp.setYouTubeVideoThumbnail("changeVid01", null, null) }
        assertEquals("S10E15 has no thumbnail; set it first", noPicture.message)
        val pictured = mcp.setYouTubeVideoThumbnail("changeVid01", "thumbnail_b", null)
        assertEquals("image/jpeg" to 200_000, fake.thumbnails["changeVid01"])
        assertEquals("https://i.ytimg.com/vi/changeVid01/custom.jpg", pictured.thumbnailUrl)
    }

    private fun user(username: String, scope: String, roles: Map<String, Any> = APPROVED) = jwt().jwt {
        it.subject("$username-id").claim("preferred_username", username).claim("azp", "metaos-ui").claim("realm_access", roles).claim("scope", scope)
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions).andExpect(status().isOk)
    }

    @Test
    fun `is the team's over GraphQL, which uploads and lists as MCP does`() {
        val episode = episodes.saveAndFlush(Episode(title = "Prek GraphQL", code = "S10E16").apply { status = EpisodeStatus.PUBLISHED })
        val video = file(episode, MediaKind.FULL_VIDEO, "S10E16.mp4")
        val writer = user("bojan", "$API_READ_SCOPE $API_WRITE_SCOPE")
        val reader = user("ana", API_READ_SCOPE)
        val member = user("cene", "$API_READ_SCOPE $API_WRITE_SCOPE", MEMBER)

        graphql("""mutation { uploadToYouTube(ref: "S10E16", title: "Prek GraphQL") { id status mediaName title requestedBy episode { code } } }""", reader)
            .andExpect(jsonPath("$.errors").exists())
        graphql("""mutation { uploadToYouTube(ref: "S10E16", title: "Prek GraphQL") { id status mediaName title requestedBy episode { code } } }""", member)
            .andExpect(jsonPath("$.errors").exists())
        graphql("""mutation { uploadToYouTube(ref: "S10E16", title: "Prek GraphQL", tags: ["ogrodje"]) { id status mediaName title tags requestedBy episode { code } } }""", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.uploadToYouTube.mediaName").value("S10E16.mp4"))
            .andExpect(jsonPath("$.data.uploadToYouTube.tags[0]").value("ogrodje"))
            .andExpect(jsonPath("$.data.uploadToYouTube.requestedBy").value("bojan"))
            .andExpect(jsonPath("$.data.uploadToYouTube.episode.code").value("S10E16"))
        val result = await(video)

        val fields = """youtubeVideos { videoId title privacy url studioUrl mediaId episode { code } } youtubeUploads { status percent videoId video { videoId uploadStatus } }"""
        graphql("""{ episode(ref: "S10E16") { $fields } }""", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.episode.youtubeVideos[0].videoId").value(result.videoId))
            .andExpect(jsonPath("$.data.episode.youtubeVideos[0].privacy").value("PRIVATE"))
            .andExpect(jsonPath("$.data.episode.youtubeVideos[0].mediaId").value(video.id.toString()))
            .andExpect(jsonPath("$.data.episode.youtubeUploads[0].status").value("DONE"))
            .andExpect(jsonPath("$.data.episode.youtubeUploads[0].video.uploadStatus").value("processed"))
        // A released episode is anyone's, but not its videos on the channel, private ones among them, nor its uploads
        for (anyone in listOf(null, member)) {
            graphql("""{ episode(ref: "S10E16") { title $fields } }""", anyone)
                .andExpect(jsonPath("$.data.episode.title").value("Prek GraphQL"))
                .andExpect(jsonPath("$.data.episode.youtubeVideos").isEmpty)
                .andExpect(jsonPath("$.data.episode.youtubeUploads").isEmpty)
            graphql("""{ youtubeVideos { videoId } }""", anyone).andExpect(jsonPath("$.errors").exists())
            graphql("""{ youtube { connected channel } }""", anyone).andExpect(jsonPath("$.errors").exists())
        }
        graphql("""{ youtube { connected channel handle refreshing } youtubeVideos(query: "prek graphql") { videoId } youtubeUploads(statuses: [DONE]) { videoId } }""", reader)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.youtube.connected").value(true))
            .andExpect(jsonPath("$.data.youtube.channel").value("Ogrodje"))
            .andExpect(jsonPath("$.data.youtubeVideos[0].videoId").value(result.videoId))
        graphql("""mutation { updateYouTubeVideo(id: "${result.videoId}", description: "Nov opis") { description privacy } }""", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.updateYouTubeVideo.description").value("Nov opis"))
            .andExpect(jsonPath("$.data.updateYouTubeVideo.privacy").value("PRIVATE"))
        graphql("""mutation { setYouTubeVideoEpisode(id: "${result.videoId}") { episode { code } } }""", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.setYouTubeVideoEpisode.episode").doesNotExist())
    }
}
