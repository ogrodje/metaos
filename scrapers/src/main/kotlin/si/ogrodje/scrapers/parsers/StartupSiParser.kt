package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * START:UP Slovenija (https://www.startup.si/sl-si/dogodki): the six next events on its events page, each read from its
 * own page's "add to calendar" box. Its ID is the SHA-1 of the event's URL, as Goo's.
 */
object StartupSiParser : Parser {
    override val source = Source.STARTUP_SI

    // Goo read the site's times as Paris's, which is Ljubljana's time zone
    private val zone = ZoneId.of("Europe/Paris")
    private val format = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")

    override fun accepts(field: SourceField, url: URI) = url.site.contains("startup.si")

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val root = url.at("/sl-si/dogodki")
        return context.web.document(root).select("div.next-six-events a.event-card")
            .map { url.resolve(it.attr("href")) }
            .map { event(context, root, it) }
    }

    private fun event(context: ScrapeContext, root: URI, url: URI): ScrapedEvent {
        val page = context.web.document(url)
        fun text(query: String) = page.selectFirst(query)?.text()?.trim()
        val title = requireNotNull(text("div.addeventatc span.title")) { "No title at $url" }
        val start = requireNotNull(text("div.addeventatc span.start")) { "No start at $url" }
        return ScrapedEvent(
            source = source,
            externalId = "startup-si-${sha1(url.toString())}",
            sourceUrl = root.toString(),
            title = title,
            start = time(start),
            end = text("div.addeventatc span.end")?.let(::time),
            eventUrl = url.toString(),
            locationName = page.select("div.location-html p").joinToString(" ") { it.text().trim() }.trim(),
        )
    }

    private fun time(text: String) = LocalDateTime.parse(text, format).atZone(zone).toInstant()

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
