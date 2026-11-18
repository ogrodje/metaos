package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * A Meetup group (https://www.meetup.com/<group>/events), whatever page of the group the URL is: its events page has
 * them in Next.js's `__NEXT_DATA__`, in the Apollo cache, with their venues as entries of their own (a name, a street
 * and a city). An online event (`isOnline`, `eventType` ONLINE, or Meetup's venue "Online event") is online; a hybrid
 * one is at its venue. A page without that cache is no events, as Goo had it. Events that started over six months ago
 * are left out.
 */
object MeetupComParser : Parser {
    override val source = Source.MEETUP

    override fun accepts(field: SourceField, url: URI) = url.site.contains("meetup.com")

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val events = eventsUrl(url)
        return events(events, context.web.get(events))
    }

    /** The group's events page, e.g. for https://www.meetup.com/group/events/123/ */
    fun eventsUrl(url: URI): URI = url.at("/${url.path.orEmpty().split("/").firstOrNull { it.isNotEmpty() }.orEmpty()}/events")

    /** The events in the [html] of the group's events page at [url] */
    fun events(url: URI, html: String, now: ZonedDateTime = ZonedDateTime.now(LJUBLJANA)): List<ScrapedEvent> {
        val script = Jsoup.parse(html).selectFirst("script[id='__NEXT_DATA__'][type='application/json']")
            ?: return emptyList()
        val json = runCatching { Web.MAPPER.readTree(script.data()) }.getOrNull() ?: return emptyList()
        val state = json.path("props").path("pageProps").path("__APOLLO_STATE__")
        if (!state.isObject) return emptyList()

        val venues = state.properties().filter { it.key.startsWith("Venue") }.mapNotNull { venue(it.value) }.toMap()
        val cutoff = now.minusMonths(6).toInstant()
        return state.properties().filter { it.key.startsWith("Event") }
            .mapNotNull { (_, node) -> runCatching { event(url, node, venues) }.getOrNull() }
            .filter { it.start > cutoff }
    }

    // Its state is a code, often a wrong one ("al" for Ljubljana), and its country one too, so neither is kept
    private fun venue(node: JsonNode): Pair<String, Location>? {
        val id = node.text("id")?.split(":")?.first() ?: return null
        return id to Locations.of(node.text("name"), Locations.address(node.text("address"), node.text("city")))
    }

    private fun event(url: URI, node: JsonNode, venues: Map<String, Location>): ScrapedEvent {
        val id = requireNotNull(node.text("id"))
        val venue = node.path("venue").text("__ref")?.split(":")?.getOrNull(1)?.let(venues::get)
        val online = node.path("isOnline").booleanValue(false) || node.text("eventType") == "ONLINE"
        val location = if (online) Location.ONLINE else venue ?: Location.NONE
        return ScrapedEvent(
            source = source,
            externalId = "meetup-$id",
            sourceUrl = url.toString(),
            title = requireNotNull(node.text("title")).trim(),
            start = OffsetDateTime.parse(requireNotNull(node.text("dateTime"))).toInstant(),
            end = node.text("endTime")?.let { OffsetDateTime.parse(it).toInstant() },
            description = node.text("description")?.trim(),
            eventUrl = node.text("eventUrl")?.also(::URI),
            locationName = location.name,
            locationAddress = location.address,
        )
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.stringValue()
}
