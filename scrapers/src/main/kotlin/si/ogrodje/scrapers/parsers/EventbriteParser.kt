package si.ogrodje.scrapers.parsers

import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.WaitForSelectorState
import si.ogrodje.scrapers.*
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * An Eventbrite organizer (https://www.eventbrite.com/o/<organizer>): its page, in a browser, as Eventbrite turns away
 * plain requests, has its upcoming events in Next.js's `__NEXT_DATA__`, each with its date, time and time zone apart.
 * A page with neither the organizer nor its events is Eventbrite's bot check, which fails so it's tried again.
 */
object EventbriteParser : Parser {
    override val source = Source.EVENTBRITE

    /** Eventbrite showed its bot check instead of the organizer's page */
    class Blocked(url: URI) : IOException("Eventbrite served a bot check instead of events for $url")

    override fun accepts(field: SourceField, url: URI) = url.site.contains("eventbrite.com")

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val raw = try {
            context.withPage { page ->
                page.navigate(url.toString())
                page.waitForSelector(
                    "script#__NEXT_DATA__",
                    Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(10_000.0),
                )
                page.locator("script#__NEXT_DATA__").first().innerHTML()
            }
        } catch (_: TimeoutError) {
            throw Blocked(url)
        }
        return events(url, raw)
    }

    /** The events in the organizer page's `__NEXT_DATA__`, [raw]; fails on invalid JSON and on the bot check */
    fun events(url: URI, raw: String): List<ScrapedEvent> {
        val json = try {
            Web.MAPPER.readTree(raw)
        } catch (e: JacksonException) {
            throw IOException("Eventbrite's page at $url has no valid __NEXT_DATA__: ${e.originalMessage}", e)
        }
        val props = json.path("props").path("pageProps")
        val hasOrganizer = props.has("organizer") && !props.get("organizer").isNull
        if (!hasOrganizer && !props.has("upcomingEvents")) throw Blocked(url)
        val items = props.path("upcomingEvents").takeIf { it.isArray }?.values().orEmpty()
        return items.mapNotNull { runCatching { event(url, it) }.getOrNull() }
    }

    private fun event(url: URI, item: JsonNode): ScrapedEvent {
        val zone = requireNotNull(item.text("timezone"))
        val start = time(requireNotNull(item.text("start_date")), requireNotNull(item.text("start_time")), zone)
        val name = requireNotNull(item.text("name"))
        val id = requireNotNull(item.text("id"))
        val eventUrl = URI(requireNotNull(item.text("url")))
        val end = runCatching { time(item.text("end_date")!!, item.text("end_time")!!, zone) }.getOrNull()
        val online = item.path("is_online_event").booleanValue(false)
        val venue = item.path("primary_venue")
        return ScrapedEvent(
            source = source,
            externalId = "eventbrite-$id",
            sourceUrl = url.toString(),
            title = name,
            start = start,
            end = end,
            hasStartTime = !item.path("hide_start_date").booleanValue(false),
            hasEndTime = !item.path("hide_end_date").booleanValue(false),
            description = item.text("summary")?.takeIf(String::isNotEmpty),
            eventUrl = eventUrl.toString(),
            locationName = if (online) null else venue.text("name")?.takeIf(String::isNotEmpty),
            locationAddress = if (online) null else venue.path("address").text("localized_address_display")?.takeIf(String::isNotEmpty),
        )
    }

    private fun time(date: String, time: String, zone: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(ZoneId.of(zone)).toInstant()

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.stringValue()
}
