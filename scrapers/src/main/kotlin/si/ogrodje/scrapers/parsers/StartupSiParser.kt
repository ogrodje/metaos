package si.ogrodje.scrapers.parsers

import org.jsoup.nodes.Document
import si.ogrodje.scrapers.*
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * START:UP Slovenija (https://www.startup.si/sl-si/dogodki): the six next events on its events page, each read from its
 * own page's "add to calendar" box, and its place from its "Lokacija" box. Its ID is the SHA-1 of the event's URL, as
 * Goo's.
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
        val location = locationIn(page)
        return ScrapedEvent(
            source = source,
            externalId = "startup-si-${sha1(url.toString())}",
            sourceUrl = root.toString(),
            title = title,
            start = time(start),
            end = text("div.addeventatc span.end")?.let(::time),
            description = page.selectFirst(".event-article .content.size-18")?.let { article ->
                article.select(".share-buttons").remove()
                EventText.description(article)
            }
                ?: text("div.addeventatc span.description")?.ifBlank { null },
            eventUrl = url.toString(),
            locationName = location.name,
            locationAddress = location.address,
        )
    }

    /**
     * Where an event's page says it is: its "Lokacija" box, a line a paragraph (the first place, where it has two),
     * whose venue may be only its map link's text, or one line; else the calendar box's one line
     */
    internal fun locationIn(page: Document): Location {
        val box = page.selectFirst("div.location-html")
        val lines = box?.select("p")?.map { Locations.clean(it.text()) }.orEmpty()
        val first = lines.dropWhile { it == null }.takeWhile { it != null }.filterNotNull()
        val calendar = page.selectFirst("div.addeventatc span.location")?.text()
        if (first.isEmpty()) {
            val line = page.selectFirst("div.location-info")?.clone()?.apply { select(".title").remove() }?.text()
            return Locations.parse(Locations.clean(line) ?: calendar)
        }
        val place = Locations.parse(first)
        if (place.name != null || place.address == null) return place
        val mapLink = box!!.parent()?.selectFirst("> a")?.text()?.let(Locations::clean) ?: Locations.clean(calendar)
        return mapLink?.let { Locations.of(Locations.parse(it).name ?: it, place.address) } ?: place
    }

    private fun time(text: String) = LocalDateTime.parse(text, format).atZone(zone).toInstant()

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
