package si.ogrodje.patreon

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** What a post's media is: its audio (a podcast post) or its video. */
enum class MediaKind(val relationship: String, val postType: String) {
    AUDIO("audio", "podcast"),
    VIDEO("video", "video_external_file"),
}

/**
 * A file to put on a post, from where the gateway downloads it: an https [url], e.g. a signed Cloud Storage URL, so
 * MetaOS never streams an episode through itself. [fileName] is what Patreon shows, else the URL's last part.
 */
data class MediaRequest(val url: String, val kind: MediaKind, val fileName: String? = null)

data class UploadedMedia(val id: String, val kind: MediaKind, val fileName: String, val sizeBytes: Long, val state: String)

/**
 * Moving files to Patreon as its editor does: Patreon is told about the file (`/api/media`, as the creator, in the
 * browser), and answers where to put it - S3 with a signed form for audio, Mux with a PUT for video - which the gateway
 * sends from here, not through the browser, as an episode is gigabytes. Then Patreon processes it until it's ready.
 */
@Component
class Media(private val properties: PatreonProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    // Downloads follow redirects themselves, each checked as the first address is ([fetch])
    private val downloader = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build()

    private val downloads: Path
        get() = properties.downloadsDir.takeIf { it.isNotBlank() }?.let(Path::of) ?: properties.stateDir.resolve("downloads")

    /** What downloads a restart cut short left on the volume, episodes of gigabytes; one gateway uses it at a time. */
    @PostConstruct
    fun forgetDownloads() {
        runCatching {
            if (Files.isDirectory(downloads)) Files.list(downloads).use { files -> files.forEach(Files::deleteIfExists) }
        }.onFailure { log.warn("Couldn't delete what earlier downloads left: {}", it.message) }
    }

    /** Downloads [request]'s file to [downloads], from the internet only, for [use]; it's deleted after. */
    fun <T> downloaded(request: MediaRequest, use: (Path, String) -> T): T {
        val uri = URI.create(request.url)
        onTheInternet(uri)
        val name = (request.fileName ?: uri.path.substringAfterLast('/')).ifBlank { "episode" }
        require(name.none { it == '/' || it == '\\' || it == '\r' || it == '\n' }) { "A file's name can't have slashes or line breaks" }
        val dir = Files.createDirectories(downloads)
        val file = dir.resolve(UUID.randomUUID().toString())
        try {
            val response = fetch(uri, file)
            if (response.statusCode() !in 200..299) throw IllegalArgumentException("Couldn't download the file: ${response.statusCode()} from ${uri.host}")
            // A site that turns the download away often answers with a page instead, which Patreon would take and never process
            val type = response.headers().firstValue("Content-Type").orElse("")
            if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
                throw IllegalArgumentException("${uri.host} gave a $type page, not the file: is the link right, and still good?")
            }
            log.info("Downloaded {} ({} bytes) from {}", name, Files.size(file), uri.host)
            return use(file, name)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    // Up to five redirects, each to an address on the internet as the first: a link given to the gateway mustn't reach
    // what's beside it in the cluster, e.g. by a redirect from a site that allows any
    private fun fetch(start: URI, file: Path): HttpResponse<Path> {
        var uri = start
        repeat(6) {
            onTheInternet(uri)
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofHours(1))
                .header("User-Agent", "Ogrodje patreon-gateway (https://metaos.ogrodje.si)").GET().build()
            val response = downloader.send(request, HttpResponse.BodyHandlers.ofFile(file))
            if (response.statusCode() !in 300..399) return response
            uri = uri.resolve(response.headers().firstValue("Location").orElseThrow { IllegalArgumentException("${uri.host} redirected nowhere") })
        }
        throw IllegalArgumentException("${start.host} redirected too often")
    }

    /** Refuses a URL that isn't https, or whose host isn't on the internet: the cluster's, the node's, the tailnet's. */
    internal fun onTheInternet(uri: URI) {
        require(uri.scheme == "https") { "The file has to come from an https URL" }
        val host = uri.host ?: throw IllegalArgumentException("The file's URL has no host")
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { throw IllegalArgumentException("$host can't be found") }
        require(addresses.none(::private)) { "$host isn't on the internet: the gateway downloads only from there" }
    }

    private fun private(address: InetAddress): Boolean {
        val bytes = address.address
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress ||
            // Shared address space (100.64.0.0/10), Tailscale's among it, and IPv6's unique local addresses (fc00::/7)
            (address is Inet4Address && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 64) ||
            (address is Inet6Address && (bytes[0].toInt() and 0xFE) == 0xFC)
    }

    /** Sends [file], named [name], where Patreon's answer to creating the media ([created], its `data`) says. */
    fun upload(created: JsonNode, file: Path, name: String, contentType: String) {
        val attributes = created["attributes"]
        val url = attributes["upload_url"]?.takeUnless { it.isNull }?.asString()
            ?: throw PatreonException("Patreon gave no address to upload the file to")
        val response = if (URI.create(url).host.endsWith("mux.com")) {
            // Mux's direct upload: the file as it is
            http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofHours(2)).header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofFile(file)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } else {
            // S3's POST with a signed form: its fields, then the file, by its own name (not the download's)
            val fields = attributes["upload_parameters"]?.properties()?.associate { (k, v) -> k to v.asString() }.orEmpty()
            val form = Multipart(fields, file, name, contentType)
            http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofHours(2)).header("Content-Type", form.contentType)
                    .POST(form.publisher()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        if (response.statusCode() !in 200..299) {
            throw PatreonException("Uploading the file failed: ${response.statusCode()} ${response.body().take(300)}", response.statusCode())
        }
    }

    fun contentType(name: String, kind: MediaKind): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> if (kind == MediaKind.AUDIO) "audio/mpeg" else "video/mp4"
    }

    /** multipart/form-data with [fields] first and the file last, as S3 wants it, streamed from disk. */
    private class Multipart(val fields: Map<String, String>, val file: Path, val name: String, val fileType: String) {
        private val boundary = "metaos-" + UUID.randomUUID()
        val contentType = "multipart/form-data; boundary=$boundary"

        fun publisher(): HttpRequest.BodyPublisher {
            val head = buildString {
                fields.forEach { (k, v) -> append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
                append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "")}\"\r\nContent-Type: $fileType\r\n\r\n")
            }
            return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(head),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofString("\r\n--$boundary--\r\n"),
            )
        }
    }
}
