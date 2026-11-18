package si.ogrodje.scrapers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A site answered with something other than a page, e.g. 404 or 503 */
class WebException(val status: Int, message: String) : IOException(message)

/** What a parser fetches: pages, JSON and calendars, by GET, or by POST where a site's API takes one */
interface Web {
    /** The body of [url], as text, after redirects; fails with [WebException] on anything but 2xx */
    fun get(url: URI, headers: Map<String, String> = emptyMap()): String

    /** [body] POSTed to [url] as [contentType], and what it answered */
    fun post(url: URI, body: String, contentType: String, headers: Map<String, String> = emptyMap()): String

    /** [url]'s page, parsed, with links resolved against it (`abs:href`) */
    fun document(url: URI, headers: Map<String, String> = emptyMap()): Document = Jsoup.parse(get(url, headers), url.toString())

    fun json(url: URI, headers: Map<String, String> = emptyMap()): JsonNode = MAPPER.readTree(get(url, headers + ("Accept" to "application/json")))

    companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}

/**
 * The web as a browser on a desktop sees it: a realistic user agent and language, as some sites turn away what looks
 * like a bot, redirects followed, 20 s a request
 */
class HttpWeb(private val timeout: Duration = Duration.ofSeconds(20)) : Web {
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun get(url: URI, headers: Map<String, String>): String = send(request(url, headers).GET().build())

    override fun post(url: URI, body: String, contentType: String, headers: Map<String, String>): String =
        send(request(url, headers).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build())

    private fun request(url: URI, headers: Map<String, String>) = HttpRequest.newBuilder(url).timeout(timeout).apply {
        header("User-Agent", USER_AGENT)
        header("Accept-Language", "sl-SI,sl;q=0.9,en;q=0.8")
        if ("Accept" !in headers) header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        headers.forEach { (name, value) -> header(name, value) }
    }

    private fun send(request: HttpRequest): String {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw WebException(response.statusCode(), "${request.uri()} answered ${response.statusCode()}")
        }
        return response.body()
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    }
}
