package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import org.jsoup.parser.Parser.unescapeEntities
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Primorski tehnološki park (https://www.primorski-tp.si/vsi-dogodki/): the events in its page's JSON-LD, the lists of
 * them, with their times and offsets, from two months ago on. Its JSON-LD has no place, so where each is comes from
 * The Events Calendar's REST API, by the event's URL: its venue, else the "Kje:" of its description; without the API,
 * nowhere. Its ID is the SHA-1 of the event's URL, ptp-<sha1>, and its URL is its source too, as Goo's.
 */
object PrimorskiTehnoloskiParkParser : Parser {
    override val source = Source.PRIMORSKI_TEHNOLOSKI_PARK

    private val log = LoggerFactory.getLogger(javaClass)

    override fun accepts(field: SourceField, url: URI) = (url.host ?: "").lowercase().contains("primorski-tp.si")

    override fun parse(context: ScrapeContext, url: URI) = parse(context, url, Instant.now())

    internal fun parse(context: ScrapeContext, url: URI, now: Instant): List<ScrapedEvent> {
        val since = now.atZone(LJUBLJANA).minusMonths(2)
        val events = context.web.document(url.at("/vsi-dogodki/")).select("script[type=application/ld+json]")
            .map { it.data() }
            .filter { it.contains("\"Event\"") }
            .map { Web.MAPPER.readTree(it) }
            .filter { it.isArray }
            .flatMap { lds -> lds.values().map(::event) }
            .filter { it.start.isAfter(since.toInstant()) }
        val places = try {
            places(context.web, url, since.toLocalDate())
        } catch (e: Exception) {
            log.warn("No places of Primorski tehnološki park's events: {}", e.message)
            emptyMap()
        }
        return events.map { event ->
            val place = places[key(event.eventUrl!!)] ?: return@map event
            if (event.locationName != null || event.locationAddress != null) event
            else event.copy(locationName = place.name, locationAddress = place.address)
        }
    }

    private const val PER_PAGE = 50
    private const val MAX_PAGES = 3

    private fun key(url: String) = url.trimEnd('/')

    /**
     * Where the events are, by their URLs, from The Events Calendar's REST API that the site has too: an event's
     * venue (its name, street, postcode and city), else where its text says ("Kje: soba N19, prvo nadstropje …")
     */
    private fun places(web: Web, url: URI, since: LocalDate): Map<String, Location> {
        val all = mutableMapOf<String, Location>()
        for (page in 1..MAX_PAGES) {
            val json = web.json(URI("${url.at("/wp-json/tribe/events/v1/events")}?start_date=$since&per_page=$PER_PAGE&page=$page"))
            json.path("events").values().forEach { event ->
                val link = event.get("url")?.takeIf { it.isString }?.stringValue() ?: return@forEach
                placeOf(event).takeUnless { it.isEmpty }?.let { all[key(link)] = it }
            }
            if (page >= json.path("total_pages").asInt(1)) break
        }
        return all
    }

    /** An event of The Events Calendar's API: its venue, else the place its description names */
    internal fun placeOf(event: JsonNode): Location {
        val venue = event.get("venue")?.takeIf { it.isObject }
        if (venue != null) {
            fun text(name: String) = venue.get(name)?.takeIf { it.isString }?.stringValue()
            val place = Locations.of(text("venue"), Locations.address(text("address"), Locations.postcodeCity(text("zip"), text("city")), text("country")))
            if (!place.isEmpty) return place
        }
        val description = event.get("description")?.takeIf { it.isString }?.stringValue() ?: return Location.NONE
        return Locations.parse(EventText.locationIn(EventText.linesOf(description)))
    }

    private fun event(node: JsonNode): ScrapedEvent {
        fun text(name: String) = node.get(name)?.takeUnless { it.isNull }?.asString()
        fun time(name: String) = text(name)?.let { OffsetDateTime.parse(it).toInstant() }
        val url = requireNotNull(text("url")) { "No url in $node" }
        val location = Locations.fromJsonLd(node)
        return ScrapedEvent(
            source = source,
            externalId = "ptp-${sha1(url)}",
            sourceUrl = url,
            title = requireNotNull(text("name")) { "No name in $node" },
            start = requireNotNull(time("startDate")) { "No startDate in $node" },
            end = time("endDate"),
            description = text("description")?.let { raw ->
                val html = if (raw.trimStart().startsWith("&lt;")) unescapeEntities(raw, false) else raw
                EventText.description(Jsoup.parseBodyFragment(html).body())
            },
            eventUrl = url,
            locationName = location.name,
            locationAddress = location.address,
        )
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
