package si.ogrodje.metaos

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** YouTube is off until the bot's YouTube refresh token is set (google/refresh_token.sh youtube) */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.google.youtube-refresh-token:}' != ''")
annotation class ConditionalOnYouTube

/**
 * Sending videos there takes the bucket they're in too (GOOGLE_STORAGE_KEY). One condition, as Spring takes only the
 * first of two of these on a class.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.google.youtube-refresh-token:}' != '' and '\${metaos.storage.key:}' != ''")
annotation class ConditionalOnYouTubeUploads

/** YouTube couldn't be asked, or answered with an error that asking again may not have: busy, down, out of quota */
open class YouTubeException(message: String) : RuntimeException(message)

/**
 * The project is out of its quota for the day (10,000 units, which YouTube starts over at midnight Pacific time; an
 * upload is the dearest call), or the channel may upload no more today: asking again later does it
 */
class YouTubeQuota(message: String) : YouTubeException(message)

/** An upload's session is gone, as YouTube keeps one only so long: the upload starts over */
class YouTubeSessionGone(message: String) : YouTubeException(message)

/** YouTube turned the request down, e.g. as invalid, or as the bot may not do it on the channel: asking again won't do */
class YouTubeRefused(message: String) : RuntimeException(message)

/** Who may see a video. MetaOS only ever uploads PRIVATE ones; it reads the rest. */
enum class YouTubePrivacy {
    PUBLIC, UNLISTED, PRIVATE;

    companion object {
        /** YouTube's privacyStatus, e.g. public; anything it doesn't say is taken for private, the careful one */
        fun of(status: String?) = entries.find { it.name.equals(status, ignoreCase = true) } ?: PRIVATE
    }
}

/** The channel the bot's token is for: its ID, its name, its handle (@ogrodje) and the playlist of all it uploaded */
data class YouTubeChannel(val id: String, val title: String, val handle: String?, val uploads: String)

/** A video as YouTube's API says it is */
data class YouTubeVideoInfo(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val categoryId: String?,
    val language: String?,
    val privacy: YouTubePrivacy,
    // uploaded (and being processed), processed, failed, rejected or deleted, and why it failed or was rejected
    val uploadStatus: String?,
    val problem: String?,
    val publishAt: Instant?,
    val publishedAt: Instant?,
    // Seconds; none until it's processed
    val duration: Int?,
    val live: Boolean,
    val thumbnailUrl: String?,
)

/** What a video says about itself, as MetaOS sets it: on a new one, or in place of what one has */
data class YouTubeDetails(
    val title: String,
    val description: String,
    val tags: List<String>,
    val categoryId: String? = null,
    // What's spoken in it, as YouTube takes it: sl, en
    val language: String? = null,
)

/** How far an upload is: the bytes YouTube has, and the video once it has them all */
data class YouTubeSent(val offset: Long, val videoId: String?)

const val MAX_YOUTUBE_TITLE = 100
const val MAX_YOUTUBE_DESCRIPTION = 5000
const val MAX_YOUTUBE_TAGS = 500

// What YouTube takes as a thumbnail
const val MAX_YOUTUBE_THUMBNAIL_BYTES = 2 * 1024 * 1024

/** A title as YouTube takes it: up to 100 characters, without < and > */
fun youtubeTitle(title: String): String = title.trim().also {
    require(it.isNotEmpty()) { "A YouTube video needs a title" }
    require(it.length <= MAX_YOUTUBE_TITLE) { "A YouTube title is at most $MAX_YOUTUBE_TITLE characters; this one is ${it.length}" }
    require('<' !in it && '>' !in it) { "A YouTube title can't have < or >" }
}

/** A description as YouTube takes it: up to 5,000 bytes, without < and > */
fun youtubeDescription(description: String): String = description.trim().also {
    require(it.toByteArray().size <= MAX_YOUTUBE_DESCRIPTION) { "A YouTube description is at most $MAX_YOUTUBE_DESCRIPTION bytes; this one is ${it.toByteArray().size}" }
    require('<' !in it && '>' !in it) { "A YouTube description can't have < or >" }
}

/** Tags as YouTube takes them: each once, up to 500 characters in all, as it counts them (a comma each, quotes round one with a space) */
fun youtubeTags(tags: List<String>): List<String> = tags.map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.distinct().also { all ->
    require(all.none { '<' in it || '>' in it || ',' in it }) { "A YouTube tag can't have <, > or a comma" }
    val counted = all.sumOf { it.length + (if (' ' in it) 2 else 0) } + maxOf(all.size - 1, 0)
    require(counted <= MAX_YOUTUBE_TAGS) { "YouTube takes tags of up to $MAX_YOUTUBE_TAGS characters in all; these are $counted" }
}

private val VIDEO_ID = Regex("""[\w-]{11}""")
private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtube-nocookie.com")

/**
 * The video a YouTube address is of, however it's written: youtu.be/<id>, /watch?v=<id>, /live/<id>, /shorts/<id>,
 * /embed/<id>; or the ID itself. None for an address of something else, e.g. a channel or a playlist.
 */
fun youtubeVideoId(address: String): String? {
    val text = address.trim()
    if (VIDEO_ID.matches(text)) return text
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (host !in YOUTUBE_HOSTS) return null
    val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    val id = when {
        host == "youtu.be" -> parts.firstOrNull()
        parts.firstOrNull() == "watch" -> uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
        parts.firstOrNull() in setOf("live", "shorts", "embed", "v") -> parts.getOrNull(1)
        else -> null
    }
    return id?.takeIf(VIDEO_ID::matches)
}

/** Where a video is watched, and where its owner works on it */
fun youtubeUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
fun youtubeStudioUrl(videoId: String) = "https://studio.youtube.com/video/$videoId/edit"

/**
 * Ogrodje's YouTube channel, as its bot account, an owner or manager of it: what's on it, and sending videos there.
 * MetaOS uploads only private videos, and never deletes one or changes who may see it: a person publishes, in YouTube
 * Studio.
 */
interface YouTube {
    /** The channel the bot's token is for */
    fun channel(): YouTubeChannel

    /** The IDs of every video in a playlist, e.g. the channel's uploads, the latest first, private ones too */
    fun playlistVideoIds(playlist: String): List<String>

    /** The videos by their IDs; those that aren't there, or aren't the bot's to see, are left out */
    fun videos(ids: Collection<String>): List<YouTubeVideoInfo>

    /**
     * Starts the upload of a private video of [size] bytes with these details, and answers with its session, where its
     * bytes go ([send]) for as long as YouTube keeps it, which is days
     */
    fun startUpload(details: YouTubeDetails, contentType: String, size: Long): String

    /** How much of an upload YouTube has: where to go on from after it broke off */
    fun sent(session: String, size: Long): YouTubeSent

    /** Sends the bytes from [offset] on, a multiple of 256 KB of them but for the file's last */
    fun send(session: String, offset: Long, chunk: ByteArray, size: Long): YouTubeSent

    /** Changes what a video says about itself, to all of [details]; never who may see it */
    fun update(videoId: String, details: YouTubeDetails): YouTubeVideoInfo

    /** Gives a video its thumbnail: a JPEG or PNG of up to 2 MB, on a channel that's verified */
    fun setThumbnail(videoId: String, contentType: String, picture: ByteArray)
}

private fun JsonNode?.text(field: String): String? = this?.get(field)?.takeIf { it.isString }?.asString()?.ifEmpty { null }
private fun JsonNode?.instant(field: String): Instant? = text(field)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
private fun encoded(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

/**
 * YouTube's Data API v3 (https://developers.google.com/youtube/v3), called over HTTP as the bot, with an access token
 * of its YouTube refresh token ([token]). An upload is a resumable one, sent in chunks, so a file of gigabytes goes
 * through MetaOS without being in its memory or on its disk, and goes on from where it was after a restart. With
 * [channelId] set (METAOS_YOUTUBE_CHANNEL_ID), a token for any other channel is refused, so a token made for the wrong
 * channel never uploads there.
 */
class GoogleYouTube(
    api: String,
    private val channelId: String?,
    private val mapper: JsonMapper,
    private val token: () -> String,
) : YouTube {

    private val api = api.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile
    private var known: Pair<Instant, YouTubeChannel>? = null

    // Asked once an hour: which channel a token is for doesn't change
    override fun channel(): YouTubeChannel {
        known?.takeIf { it.first.isAfter(Instant.now().minus(Duration.ofHours(1))) }?.let { return it.second }
        val found = get("/youtube/v3/channels", "part" to "snippet,contentDetails", "mine" to "true")["items"]?.toList()?.firstOrNull()
            ?: throw YouTubeRefused(
                "The bot's YouTube token is for an account with no channel. Sign in as the bot and pick Ogrodje's channel: " +
                    "google/refresh_token.sh youtube"
            )
        val channel = YouTubeChannel(
            id = checkNotNull(found.text("id")),
            title = found["snippet"].text("title") ?: "",
            handle = found["snippet"].text("customUrl"),
            uploads = checkNotNull(found["contentDetails"]?.get("relatedPlaylists").text("uploads")) { "The channel has no uploads playlist" },
        )
        if (!channelId.isNullOrBlank() && channel.id != channelId) throw YouTubeRefused(
            "The bot's YouTube token is for the channel ${channel.title} (${channel.id}), not $channelId (METAOS_YOUTUBE_CHANNEL_ID). " +
                "Sign in as the bot and pick the right channel: google/refresh_token.sh youtube"
        )
        known = Instant.now() to channel
        return channel
    }

    override fun playlistVideoIds(playlist: String): List<String> = buildList {
        var page: String? = null
        do {
            val answer = get(
                "/youtube/v3/playlistItems",
                "part" to "contentDetails", "playlistId" to playlist, "maxResults" to "50", "pageToken" to page,
            )
            answer["items"]?.toList()?.forEach { item -> item["contentDetails"].text("videoId")?.let(::add) }
            page = answer.text("nextPageToken")
        } while (page != null && size < MAX_VIDEOS)
    }

    override fun videos(ids: Collection<String>): List<YouTubeVideoInfo> = ids.distinct().chunked(50).flatMap { some ->
        get("/youtube/v3/videos", "part" to VIDEO_PARTS, "id" to some.joinToString(","), "maxResults" to "50")["items"]
            ?.toList()?.map(::video).orEmpty()
    }

    private fun video(node: JsonNode): YouTubeVideoInfo {
        val snippet = node["snippet"]
        val status = node["status"]
        val thumbnails = snippet?.get("thumbnails")
        return YouTubeVideoInfo(
            id = checkNotNull(node.text("id")),
            channelId = snippet.text("channelId") ?: "",
            title = snippet.text("title") ?: "",
            description = snippet.text("description") ?: "",
            tags = snippet?.get("tags")?.toList()?.mapNotNull { it.takeIf { tag -> tag.isString }?.asString() }.orEmpty(),
            categoryId = snippet.text("categoryId"),
            language = snippet.text("defaultAudioLanguage") ?: snippet.text("defaultLanguage"),
            privacy = YouTubePrivacy.of(status.text("privacyStatus")),
            uploadStatus = status.text("uploadStatus"),
            problem = status.text("failureReason") ?: status.text("rejectionReason"),
            publishAt = status.instant("publishAt"),
            publishedAt = snippet.instant("publishedAt"),
            // PT1H2M3S; P0D for a stream that's on
            duration = node["contentDetails"].text("duration")?.let { runCatching { Duration.parse(it).seconds.toInt() }.getOrNull() }?.takeIf { it > 0 },
            live = node.has("liveStreamingDetails") || (snippet.text("liveBroadcastContent") ?: "none") != "none",
            thumbnailUrl = listOf("maxres", "standard", "high", "medium", "default").firstNotNullOfOrNull { thumbnails?.get(it).text("url") },
        )
    }

    private fun snippet(details: YouTubeDetails) = mapOf(
        "title" to details.title,
        "description" to details.description,
        "tags" to details.tags,
        "categoryId" to details.categoryId,
        "defaultLanguage" to details.language,
        "defaultAudioLanguage" to details.language,
    ).filterValues { it != null }

    override fun startUpload(details: YouTubeDetails, contentType: String, size: Long): String {
        // Checked first, so nothing's uploaded with a token for another channel
        channel()
        val body = mapOf(
            "snippet" to snippet(details),
            // Private, always: a person publishes. Not made for kids, as Ogrodje's audience is grown up.
            "status" to mapOf("privacyStatus" to "private", "selfDeclaredMadeForKids" to false),
        )
        val response = send(
            request("$api/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", contentType)
                .header("X-Upload-Content-Length", size.toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))),
        )
        if (response.statusCode() !in 200..299) throw failure(response)
        return response.headers().firstValue("Location").orElseThrow { YouTubeException("YouTube started an upload without saying where it goes") }
    }

    override fun sent(session: String, size: Long): YouTubeSent =
        progress(session, HttpRequest.newBuilder(URI(session)).header("Content-Range", "bytes */$size").PUT(HttpRequest.BodyPublishers.noBody()))

    override fun send(session: String, offset: Long, chunk: ByteArray, size: Long): YouTubeSent {
        require(chunk.isNotEmpty()) { "Nothing to send from $offset on" }
        return progress(
            session,
            HttpRequest.newBuilder(URI(session))
                .header("Content-Range", "bytes $offset-${offset + chunk.size - 1}/$size")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)),
        )
    }

    // 308 while it wants more, with what it has in Range (bytes=0-<last>); 200 or 201 with the video once it has it all
    private fun progress(session: String, request: HttpRequest.Builder): YouTubeSent {
        val response = send(request.header("Authorization", "Bearer ${token()}").timeout(CHUNK_TIMEOUT))
        return when (response.statusCode()) {
            200, 201 -> YouTubeSent(-1, checkNotNull(mapper.readTree(response.body()).text("id")) { "YouTube took the upload without saying which video it is" })
            308 -> YouTubeSent(response.headers().firstValue("Range").map { it.substringAfterLast('-').toLong() + 1 }.orElse(0), null)
            404, 410 -> throw YouTubeSessionGone("YouTube no longer has the upload's session")
            else -> throw failure(response)
        }
    }

    override fun update(videoId: String, details: YouTubeDetails): YouTubeVideoInfo {
        channel()
        // A snippet is replaced whole, and needs its category: the video's own where none is given
        val had = one(videoId)
        val body = mapOf("id" to videoId, "snippet" to snippet(details.copy(categoryId = details.categoryId ?: had.categoryId, language = details.language ?: had.language)))
        val response = send(
            request("$api/youtube/v3/videos?part=snippet")
                .header("Content-Type", "application/json; charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))),
        )
        if (response.statusCode() !in 200..299) throw failure(response)
        return one(videoId)
    }

    private fun one(videoId: String) =
        videos(listOf(videoId)).firstOrNull { it.id == videoId } ?: throw YouTubeRefused("The channel has no video $videoId")

    override fun setThumbnail(videoId: String, contentType: String, picture: ByteArray) {
        require(contentType == "image/jpeg" || contentType == "image/png") { "A YouTube thumbnail is a JPEG or a PNG" }
        require(picture.size <= MAX_YOUTUBE_THUMBNAIL_BYTES) { "A YouTube thumbnail is at most 2 MB" }
        channel()
        val response = send(
            request("$api/upload/youtube/v3/thumbnails/set?videoId=${encoded(videoId)}&uploadType=media")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(picture)),
        )
        if (response.statusCode() !in 200..299) throw failure(response)
    }

    private fun request(url: String) =
        HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer ${token()}").header("Accept", "application/json").timeout(CALL_TIMEOUT)

    private fun get(path: String, vararg query: Pair<String, String?>): JsonNode {
        val asked = query.filter { it.second != null }.joinToString("&") { (name, value) -> "$name=${encoded(value!!)}" }
        val response = send(request("$api$path?$asked").GET())
        if (response.statusCode() !in 200..299) throw failure(response)
        return mapper.readTree(response.body())
    }

    private fun send(request: HttpRequest.Builder): HttpResponse<String> = try {
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
        throw YouTubeException("YouTube couldn't be reached: ${e.message ?: e.javaClass.simpleName}")
    }

    // YouTube says what's wrong as {"error": {"code", "message", "errors": [{"reason", "domain"}]}}
    private fun failure(response: HttpResponse<String>): RuntimeException {
        val status = response.statusCode()
        val error = runCatching { mapper.readTree(response.body())["error"] }.getOrNull()
        val reason = error?.get("errors")?.toList()?.firstOrNull().text("reason")
        val said = error.text("message")?.replace(Regex("<[^>]+>"), "") ?: response.body().take(300).ifBlank { "nothing" }
        val message = "YouTube answered $status${reason?.let { " ($it)" }.orEmpty()}: $said"
        return when {
            reason in QUOTA_REASONS -> YouTubeQuota(message)
            status == 429 || status >= 500 -> YouTubeException(message)
            status == 401 -> YouTubeRefused("$message. The bot's YouTube token no longer works: google/refresh_token.sh youtube")
            else -> YouTubeRefused(message)
        }
    }

    private companion object {
        const val VIDEO_PARTS = "snippet,status,contentDetails,liveStreamingDetails"

        // A channel's uploads playlist lists up to 20,000; Ogrodje's has hundreds
        const val MAX_VIDEOS = 20_000

        val CALL_TIMEOUT: Duration = Duration.ofSeconds(60)
        val CHUNK_TIMEOUT: Duration = Duration.ofMinutes(5)

        // Out for the day, or asked too fast: later it works
        val QUOTA_REASONS = setOf(
            "quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded", "userRateLimitExceeded", "uploadLimitExceeded",
            "uploadRateLimitExceeded",
        )
    }
}

@Configuration
class YouTubeConfig {

    @Bean
    @ConditionalOnYouTube
    fun youTube(
        @Value("\${metaos.google.client-id:}") clientId: String,
        @Value("\${metaos.google.client-secret:}") clientSecret: String,
        @Value("\${metaos.google.youtube-refresh-token}") refreshToken: String,
        @Value("\${metaos.youtube.api-url:https://www.googleapis.com}") api: String,
        @Value("\${metaos.youtube.channel-id:}") channelId: String,
        mapper: JsonMapper,
    ): YouTube {
        val account = GoogleAccount(
            clientId, clientSecret, refreshToken,
            renew = "Get a new one with google/refresh_token.sh youtube, signed in as the bot and with Ogrodje's channel " +
                "picked, put it in GOOGLE_YOUTUBE_REFRESH_TOKEN (secretspec, then k8s/set_secrets.sh) and restart MetaOS",
        )
        return GoogleYouTube(api, channelId.ifBlank { null }, mapper, account::accessToken)
    }
}

/** The channel, every hour: that the bot's token still works, and is for the channel it should be */
@Component
@ConditionalOnYouTube
class YouTubeHealthIndicator(private val youtube: YouTube, registry: MeterRegistry) : ConnectionHealthIndicator("youtube", registry) {
    override fun probe(): Pair<String, String> = youtube.channel().let { "channel" to "${it.title} (${it.id})" }
}

/** A time as MetaOS's tools say it, from YouTube's */
fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
