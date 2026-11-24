package si.ogrodje.metaos

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MetaOS's YouTube client, against an API that answers as YouTube's Data API v3 does */
class YouTubeClientTest {

    private val mapper = JsonMapper.builder().build()
    private val asked = mutableListOf<String>()
    private val bodies = mutableMapOf<String, String>()
    private val headers = mutableMapOf<String, Map<String, String>>()
    private val received = ByteArrayOutputStream()
    private var sessionGone = false
    private var outOfQuota = false
    private var thumbnail: Pair<String?, Int>? = null

    private fun video(id: String, title: String, privacy: String, extra: String = "") = """
        {"id":"$id","snippet":{"channelId":"UC-ogrodje","title":"$title","description":"O $title","tags":["ogrodje","podcast"],
          "categoryId":"28","defaultAudioLanguage":"sl","publishedAt":"2026-11-02T08:00:00Z","liveBroadcastContent":"none",
          "thumbnails":{"default":{"url":"https://i.ytimg.com/vi/$id/default.jpg"},"maxres":{"url":"https://i.ytimg.com/vi/$id/maxresdefault.jpg"}}},
         "status":{"privacyStatus":"$privacy","uploadStatus":"processed"$extra},
         "contentDetails":{"duration":"PT1H2M3S"}}
    """.trimIndent()

    private fun answer(exchange: HttpExchange, status: Int, body: String, vararg headers: Pair<String, String>) {
        val bytes = body.toByteArray()
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            val method = exchange.requestMethod
            val body = exchange.requestBody.readBytes()
            asked += "$method $path"
            headers["$method ${exchange.requestURI.path}"] = exchange.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
            val range = exchange.requestHeaders.getFirst("Content-Range")
            when {
                exchange.requestHeaders.getFirst("Authorization") != "Bearer token" ->
                    answer(exchange, 401, """{"error":{"code":401,"message":"Invalid Credentials","errors":[{"reason":"authError"}]}}""")
                outOfQuota ->
                    answer(exchange, 403, """{"error":{"code":403,"message":"The request cannot be completed because you have exceeded your <a href=\"/youtube/v3/getting-started#quota\">quota</a>.","errors":[{"reason":"quotaExceeded","domain":"youtube.quota"}]}}""")
                method == "GET" && path.startsWith("/youtube/v3/channels?") && "mine=true" in path -> answer(
                    exchange, 200,
                    """{"items":[{"id":"UC-ogrodje","snippet":{"title":"Ogrodje","customUrl":"@ogrodje"},"contentDetails":{"relatedPlaylists":{"uploads":"UU-ogrodje"}}}]}""",
                )
                method == "GET" && path.startsWith("/youtube/v3/playlistItems?") && "pageToken=second" in path ->
                    answer(exchange, 200, """{"items":[{"contentDetails":{"videoId":"ccccccccccc"}}]}""")
                method == "GET" && path.startsWith("/youtube/v3/playlistItems?") && "playlistId=UU-ogrodje" in path -> answer(
                    exchange, 200,
                    """{"nextPageToken":"second","items":[{"contentDetails":{"videoId":"aaaaaaaaaaa"}},{"contentDetails":{"videoId":"bbbbbbbbbbb"}}]}""",
                )
                method == "GET" && path.startsWith("/youtube/v3/videos?") -> answer(
                    exchange, 200,
                    """{"items":[${video("aaaaaaaaaaa", "S05E08", "public")},${video("bbbbbbbbbbb", "Kmalu", "private", ""","publishAt":"2026-12-01T17:00:00Z"""")},
                        {"id":"ccccccccccc","snippet":{"channelId":"UC-ogrodje","title":"V živo","liveBroadcastContent":"live"},"status":{"privacyStatus":"unlisted","uploadStatus":"uploaded"},"contentDetails":{"duration":"P0D"},"liveStreamingDetails":{}}]}""",
                )
                method == "POST" && path.startsWith("/upload/youtube/v3/videos?") -> {
                    bodies["start"] = body.decodeToString()
                    answer(exchange, 200, "", "Location" to "http://127.0.0.1:${exchange.localAddress.port}/session/1")
                }
                method == "PUT" && path == "/session/1" && sessionGone -> answer(exchange, 404, "Not Found")
                // How far it is, asked with no bytes
                method == "PUT" && path == "/session/1" && range!!.startsWith("bytes */") ->
                    if (received.size() == 0) answer(exchange, 308, "") else answer(exchange, 308, "", "Range" to "bytes=0-${received.size() - 1}")
                method == "PUT" && path == "/session/1" -> {
                    val (from, rest) = range!!.removePrefix("bytes ").split('-')
                    val (to, total) = rest.split('/')
                    check(from.toInt() == received.size()) { "Sent from $from, while ${received.size()} are here" }
                    check(to.toInt() - from.toInt() + 1 == body.size)
                    received.write(body)
                    if (received.size() < total.toInt()) answer(exchange, 308, "", "Range" to "bytes=0-${received.size() - 1}")
                    else answer(exchange, 200, video("ddddddddddd", "Nova", "private"))
                }
                method == "PUT" && path.startsWith("/youtube/v3/videos?part=snippet") -> {
                    bodies["update"] = body.decodeToString()
                    answer(exchange, 200, video("aaaaaaaaaaa", "S05E08", "public"))
                }
                method == "POST" && path.startsWith("/upload/youtube/v3/thumbnails/set?videoId=aaaaaaaaaaa") -> {
                    thumbnail = exchange.requestHeaders.getFirst("Content-Type") to body.size
                    answer(exchange, 200, """{"items":[{}]}""")
                }
                method == "POST" && path.startsWith("/upload/youtube/v3/thumbnails/set") ->
                    answer(exchange, 403, """{"error":{"code":403,"message":"The authenticated user doesn't have permissions to upload and set custom video thumbnails.","errors":[{"reason":"forbidden"}]}}""")
                else -> answer(exchange, 404, """{"error":{"code":404,"message":"No $path","errors":[{"reason":"notFound"}]}}""")
            }
        }
        start()
    }

    private fun youtube(channel: String? = "UC-ogrodje", token: String = "token") =
        GoogleYouTube("http://127.0.0.1:${server.address.port}/", channel, mapper) { token }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `knows the channel the token is for, and refuses a token for another`() {
        assertEquals(YouTubeChannel("UC-ogrodje", "Ogrodje", "@ogrodje", "UU-ogrodje"), youtube().channel())
        // Any channel will do while none is named
        assertEquals("UC-ogrodje", youtube(channel = null).channel().id)

        val wrong = youtube(channel = "UC-someone-else")
        val refused = assertFailsWith<YouTubeRefused> { wrong.channel() }
        assertTrue("is for the channel Ogrodje (UC-ogrodje), not UC-someone-else" in refused.message.orEmpty(), refused.message)
        // Nothing's uploaded or changed with it either
        assertFailsWith<YouTubeRefused> { wrong.startUpload(YouTubeDetails("Nova", "", emptyList()), "video/mp4", 10) }
        assertFailsWith<YouTubeRefused> { wrong.setThumbnail("aaaaaaaaaaa", "image/jpeg", ByteArray(10)) }
        assertTrue(asked.none { it.startsWith("POST") }, asked.toString())
    }

    @Test
    fun `lists the channel's uploads page by page, and reads its videos as they are`() {
        val youtube = youtube()
        val ids = youtube.playlistVideoIds("UU-ogrodje")
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), ids)
        assertEquals(2, asked.count { it.startsWith("GET /youtube/v3/playlistItems") })

        val (episode, soon, stream) = youtube.videos(ids)
        assertEquals("S05E08", episode.title)
        assertEquals("O S05E08", episode.description)
        assertEquals(listOf("ogrodje", "podcast"), episode.tags)
        assertEquals(YouTubePrivacy.PUBLIC, episode.privacy)
        assertEquals("processed", episode.uploadStatus)
        assertEquals(3723, episode.duration)
        assertEquals("sl", episode.language)
        assertEquals("28", episode.categoryId)
        assertEquals("2026-11-02T08:00:00Z", episode.publishedAt.toString())
        // The largest thumbnail it has
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/maxresdefault.jpg", episode.thumbnailUrl)
        assertTrue(!episode.live)

        assertEquals(YouTubePrivacy.PRIVATE, soon.privacy)
        assertEquals("2026-12-01T17:00:00Z", soon.publishAt.toString())

        assertEquals(YouTubePrivacy.UNLISTED, stream.privacy)
        assertTrue(stream.live)
        assertNull(stream.duration)
        assertEquals("", stream.description)
    }

    @Test
    fun `uploads a private video in chunks, and goes on from where YouTube has it`() {
        val youtube = youtube()
        val file = ByteArray(700_000) { (it % 251).toByte() }
        val session = youtube.startUpload(YouTubeDetails("Nova epizoda", "Opis.", listOf("ogrodje"), "28", "sl"), "video/mp4", file.size.toLong())

        // Private, whatever was asked, with what the file is in the headers YouTube wants
        val started = mapper.readTree(bodies.getValue("start"))
        assertEquals("private", started["status"]["privacyStatus"].asString())
        assertEquals("Nova epizoda", started["snippet"]["title"].asString())
        assertEquals("sl", started["snippet"]["defaultAudioLanguage"].asString())
        assertEquals("28", started["snippet"]["categoryId"].asString())
        val sent = headers.getValue("POST /upload/youtube/v3/videos")
        assertEquals("video/mp4" to "700000", sent["x-upload-content-type"] to sent["x-upload-content-length"])
        assertTrue("uploadType=resumable" in asked.last(), asked.last())

        assertEquals(YouTubeSent(0, null), youtube.sent(session, file.size.toLong()))
        assertEquals(YouTubeSent(262_144, null), youtube.send(session, 0, file.copyOfRange(0, 262_144), file.size.toLong()))
        // As after a restart: where to go on from
        assertEquals(YouTubeSent(262_144, null), youtube.sent(session, file.size.toLong()))
        assertEquals(YouTubeSent(524_288, null), youtube.send(session, 262_144, file.copyOfRange(262_144, 524_288), file.size.toLong()))
        val done = youtube.send(session, 524_288, file.copyOfRange(524_288, file.size), file.size.toLong())
        assertEquals("ddddddddddd", done.videoId)
        assertContentEquals(file, received.toByteArray())

        // A session YouTube let go of says so, so the upload starts over
        sessionGone = true
        assertFailsWith<YouTubeSessionGone> { youtube.sent(session, file.size.toLong()) }
    }

    @Test
    fun `changes what a video says, keeping its category, and sets its thumbnail`() {
        val youtube = youtube()
        youtube.update("aaaaaaaaaaa", YouTubeDetails("Nov naslov", "Nov opis", listOf("a", "b")))
        val update = mapper.readTree(bodies.getValue("update"))
        assertEquals("aaaaaaaaaaa", update["id"].asString())
        assertEquals("Nov naslov", update["snippet"]["title"].asString())
        // The video's own, as a snippet is replaced whole
        assertEquals("28", update["snippet"]["categoryId"].asString())
        assertEquals("sl", update["snippet"]["defaultAudioLanguage"].asString())
        // Who may see it isn't asked to change
        assertTrue(!update.has("status") && "part=snippet" in asked.first { it.startsWith("PUT") }, update.toString())

        youtube.setThumbnail("aaaaaaaaaaa", "image/jpeg", ByteArray(1234))
        assertEquals("image/jpeg" to 1234, thumbnail)
        assertFailsWith<IllegalArgumentException> { youtube.setThumbnail("aaaaaaaaaaa", "image/webp", ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { youtube.setThumbnail("aaaaaaaaaaa", "image/png", ByteArray(MAX_YOUTUBE_THUMBNAIL_BYTES + 1)) }
        // A channel that isn't verified may not
        val refused = assertFailsWith<YouTubeRefused> { youtube.setThumbnail("eeeeeeeeeee", "image/png", ByteArray(10)) }
        assertTrue("403 (forbidden)" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `tells what's worth asking again from what isn't`() {
        // A token YouTube doesn't take: asking again won't do
        val refused = assertFailsWith<YouTubeRefused> { youtube(token = "old").channel() }
        assertTrue("google/refresh_token.sh youtube" in refused.message.orEmpty(), refused.message)

        // Out of quota for the day: later it works. Said without YouTube's HTML.
        val youtube = youtube()
        youtube.channel()
        outOfQuota = true
        val quota = assertFailsWith<YouTubeQuota> { youtube.videos(listOf("aaaaaaaaaaa")) }
        assertEquals("YouTube answered 403 (quotaExceeded): The request cannot be completed because you have exceeded your quota.", quota.message)

        // And one that can't be reached
        server.stop(0)
        assertFailsWith<YouTubeException> { youtube.playlistVideoIds("UU-ogrodje") }
    }

    @Test
    fun `reads a video's ID from its addresses, and takes only what YouTube does`() {
        listOf(
            "dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ?si=abc", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=120s",
            "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ", "https://www.youtube.com/live/dQw4w9WgXcQ",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ", "https://www.youtube.com/embed/dQw4w9WgXcQ",
        ).forEach { assertEquals("dQw4w9WgXcQ", youtubeVideoId(it), it) }
        listOf("https://www.youtube.com/@ogrodje", "https://www.youtube.com/playlist?list=PL123", "https://vimeo.com/123", "S05E08", "")
            .forEach { assertNull(youtubeVideoId(it), it) }

        assertEquals("Naslov", youtubeTitle("  Naslov "))
        assertFailsWith<IllegalArgumentException> { youtubeTitle("a".repeat(101)) }
        assertFailsWith<IllegalArgumentException> { youtubeTitle("Rust <3") }
        assertFailsWith<IllegalArgumentException> { youtubeTitle(" ") }
        assertFailsWith<IllegalArgumentException> { youtubeDescription("č".repeat(2501)) }
        assertEquals(listOf("ogrodje", "rust lang"), youtubeTags(listOf(" #ogrodje", "rust lang", "ogrodje", "")))
        assertFailsWith<IllegalArgumentException> { youtubeTags(List(60) { "oznaka-$it" }) }
        assertFailsWith<IllegalArgumentException> { youtubeTags(listOf("a,b")) }
    }
}
