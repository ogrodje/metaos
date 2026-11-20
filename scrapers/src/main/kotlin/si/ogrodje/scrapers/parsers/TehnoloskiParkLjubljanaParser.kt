package si.ogrodje.scrapers.parsers

import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Instant
import java.time.LocalDate

/**
 * Tehnološki park Ljubljana (https://www.tp-lj.si/sl/koledar-dogodkov): its calendar is drawn from the JSON in a script,
 * `dogodkiJSON`, with each event's days. Those from a month ago on are read from their own pages too, for their times
 * (`p.eventDate`, [TehnoloskiParkLjubljanaDurationParser]) and its place, where its text says ("Kje:"); one whose
 * page can't be read keeps its days. Its ID is the calendar's, tp-<id>.
 */
object TehnoloskiParkLjubljanaParser : Parser {
    override val source = Source.TEHNOLOSKI_PARK_LJUBLJANA

    private val log = LoggerFactory.getLogger(javaClass)
    private val payload = Regex("dogodkiJSON = (.*);\\n")

    override fun accepts(field: SourceField, url: URI) = (url.host ?: "").lowercase().contains("tp-lj.si")

    override fun parse(context: ScrapeContext, url: URI) = parse(context, url, Instant.now())

    internal fun parse(context: ScrapeContext, url: URI, now: Instant): List<ScrapedEvent> {
        val eventsUrl = url.at("/sl/koledar-dogodkov")
        val script = context.web.document(eventsUrl).select("script").map { it.data() }
            .firstOrNull { it.contains("dogodkiJSON") } ?: throw NoSuchElementException("No dogodkiJSON at $eventsUrl")
        val json = payload.find(script)?.groupValues?.get(1)
            ?: throw NoSuchElementException("No dogodkiJSON payload at $eventsUrl")
        val since = now.atZone(LJUBLJANA).minusMonths(1).toInstant()
        val events = Web.MAPPER.readTree(json)
        require(events.isArray) { "dogodkiJSON at $eventsUrl isn't a list" }
        return events.values().map { listed(url, eventsUrl, it) }
            .filter { it.start.isAfter(since) }
            .map { withDetails(context, it) }
    }

    private fun listed(url: URI, eventsUrl: URI, node: JsonNode): ScrapedEvent {
        fun field(name: String) = requireNotNull(node.get(name)?.takeUnless { it.isNull }) { "No $name in $node" }
        return ScrapedEvent(
            source = source,
            externalId = "tp-${field("id").asInt()}",
            sourceUrl = eventsUrl.toString(),
            title = field("title").asString(),
            start = day(field("start").asString()),
            end = day(field("end").asString()),
            hasStartTime = false,
            hasEndTime = false,
            eventUrl = url.resolve(field("link").asString()).toString(),
        )
    }

    private fun withDetails(context: ScrapeContext, event: ScrapedEvent): ScrapedEvent {
        val page = try {
            context.web.document(URI(event.eventUrl!!))
        } catch (e: Exception) {
            log.warn("Failed getting duration details from {}: {}", event.eventUrl, e.message)
            return event
        }
        val location = locationIn(page)
        val body = page.selectFirst(".eventDetail")?.clone()
        body?.select(".pageTitle, .figure, .eventDate, .btn, .eli")?.remove()
        val description = body?.let { EventText.linesOf(it.html()).joinToString("\n").ifBlank { null } }
        val placed = event.copy(description = description, locationName = location.name, locationAddress = location.address)
        return try {
            val raw = page.selectFirst("p.eventDate")?.text()
                ?.replace("Datum dogodka: ", "")?.trim() ?: throw NoSuchElementException("No duration found")
            val duration = TehnoloskiParkLjubljanaDurationParser.parse(raw)
            placed.copy(
                start = duration.start,
                hasStartTime = duration.hasStartTime,
                end = duration.end,
                hasEndTime = duration.hasEndTime,
            )
        } catch (e: Exception) {
            log.warn("Failed getting duration details from {}: {}", event.eventUrl, e.message)
            placed
        }
    }

    /**
     * Where the page's text says the event is ("Kje: Tehnološki park Ljubljana", "KJE? G-Gravity Incubator, Milano"),
     * if it says: the calendar has no place, nor do most pages
     */
    internal fun locationIn(page: Document): Location {
        val text = page.selectFirst("p.eventDate")?.parent() ?: return Location.NONE
        return Locations.parse(EventText.locationIn(EventText.linesOf(text.html())))
    }

    private fun day(text: String) = LocalDate.parse(text).atStartOfDay(LJUBLJANA).toInstant()
}
