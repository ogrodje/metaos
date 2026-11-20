package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Slovenska tovarna UI (SLAIF, https://www.slaif.si/events): a single-page app that reads its events from a public
 * JSON API, `/api/events`. A title holds both languages, "slovensko | English", of which it takes the first, as the
 * site in Slovenian does. An empty `time_range` is a time not known yet: the event is all-day. The event links to its
 * Indico page, where registration is, else to SLAIF's events page. Its place is its `location` ("Teslova", "Online"),
 * online by its tags too, or, for the recent and upcoming ones, the place, room and address on Indico, where they're
 * filled in. Drafts are left out, and an item that doesn't fit is skipped. Its ID is the item's, as Goo's.
 */
object SLAIFParser : Parser {
    override val source = Source.SLAIF

    private val log = LoggerFactory.getLogger(javaClass)

    override fun accepts(field: SourceField, url: URI) = url.host.orEmpty().let { it == "slaif.si" || it.endsWith(".slaif.si") }

    override fun parse(context: ScrapeContext, url: URI) = parse(context, url, LocalDate.now(LJUBLJANA))

    internal fun parse(context: ScrapeContext, url: URI, today: LocalDate): List<ScrapedEvent> {
        val eventsPage = url.at("/events").toString()
        val items = context.web.json(url.at("/api/events")).items().mapNotNull { node ->
            try {
                item(node)
            } catch (e: RuntimeException) {
                log.warn("Skipping SLAIF event ${node.get("id") ?: "?"}: ${e.message}")
                null
            }
        }.filter { it.status == "published" }
        // Indico's pages of the recent and upcoming ones only: older events keep what SLAIF says
        val recent = items.filter { !it.date.isBefore(today.minusDays(INDICO_DAYS_BACK)) && it.indicoUrl != null }
        // Keep the existing on-site lookup budget for venues and bound online lookups separately.
        val withIndico = recent.groupBy { it.online }.values.flatMap { it.take(MAX_INDICO) }.toSet()
        return items.map { item ->
            val details = if (item in withIndico) indicoDetails(context.web, item) else null
            val place = if (item.online) item.place else details?.let(::indicoPlace) ?: item.place
            val description = details?.get("description")?.takeIf { it.isString }?.stringValue()?.let {
                EventText.description(Jsoup.parseBodyFragment(it).body())
            }
            event(eventsPage, item, place, description)
        }
    }

    private const val INDICO_DAYS_BACK = 30L
    private const val MAX_INDICO = 20
    private val indicoEvent = Regex("""^(https?://[^/]+)/event/(\d+)""")
    // What Indico's form has until it's filled in, as SLAIF's has it: "NASLOV", "SOBA"; and "TBA"
    private val placeholders = setOf("naslov", "soba", "lokacija", "prostor", "tba", "tbd", "n/a", "-")

    private fun indicoDetails(web: Web, item: Item): JsonNode? {
        val (base, id) = indicoEvent.find(item.indicoUrl ?: return null)?.destructured ?: return null
        return try {
            web.json(URI("$base/export/event/$id.json")).path("results").values().firstOrNull()
        } catch (e: Exception) {
            log.warn("No Indico details for SLAIF event ${item.id}: ${e.message}")
            null
        }
    }

    private fun indicoPlace(event: JsonNode): Location? {
        fun text(name: String) = Locations.clean(event.get(name)?.takeIf { it.isString }?.stringValue())
            ?.takeUnless { it.lowercase() in placeholders }
        val address = Locations.parse(text("address"))
        val place = text("location")
        val room = text("room")
        val names = listOfNotNull(if (room != null && place != null && room.startsWith(place)) null else place, room, address.name)
        val location = Locations.of(names.joinToString(", ").ifEmpty { null }, address.address)
        return location.takeUnless { it.isEmpty }
    }

    private fun event(eventsPage: String, item: Item, place: Location, description: String?): ScrapedEvent {
        val times = timesOf(item.timeRange)
        val start = at(item.date, times?.first ?: LocalTime.MIDNIGHT)
        val end = times?.second?.let { at(item.date, it) }?.takeIf { it.isAfter(start) }
        return ScrapedEvent(
            source = source,
            externalId = "slaif-${item.id}",
            sourceUrl = eventsPage,
            title = slovenian(item.title),
            start = start,
            end = end,
            hasStartTime = times != null,
            hasEndTime = end != null,
            descriptionIsFallback = description == null,
            description = description ?: item.lecturer?.let { "Izvajalci: $it" },
            eventUrl = item.indicoUrl ?: eventsPage,
            locationName = place.name,
            locationAddress = place.address,
        )
    }

    internal data class Item(
        val id: Long,
        val title: String,
        val date: LocalDate,
        val timeRange: String?,
        val location: String?,
        val lecturer: String?,
        val indicoUrl: String?,
        val status: String,
        val tags: Set<String> = emptySet(),
    ) {
        /** Online by its tags ("Online", not "On-site"), whatever its location says ("VK") */
        val online: Boolean get() = "online" in tags && "on-site" !in tags

        val place: Location get() = if (online) Location.ONLINE else Locations.parse(location)
    }

    private fun item(node: JsonNode): Item {
        fun optional(name: String): String? {
            val value = node.get(name)
            return when {
                value == null || value.isNull -> null
                value.isString -> value.stringValue()
                else -> throw IllegalArgumentException("$name isn't a string")
            }
        }
        fun required(name: String) = optional(name) ?: throw IllegalArgumentException("No $name")
        fun nonEmpty(value: String?) = value?.trim()?.ifEmpty { null }
        return Item(
            id = node.get("id")?.takeIf { it.isIntegralNumber }?.longValue() ?: throw IllegalArgumentException("No id"),
            title = required("title"),
            date = LocalDate.parse(required("event_date")),
            timeRange = nonEmpty(optional("time_range")),
            location = nonEmpty(optional("location")),
            lecturer = nonEmpty(optional("lecturer")),
            indicoUrl = nonEmpty(optional("indico_url"))?.takeIf { runCatching { URI(it) }.isSuccess },
            status = optional("status") ?: "published",
            tags = node.get("tags")?.takeIf { it.isArray }?.values()?.mapNotNull { it.takeIf { t -> t.isString }?.stringValue()?.lowercase() }
                .orEmpty().toSet(),
        )
    }

    /** "slovensko | English" is "slovensko", as the site shows it in Slovenian; a title in one language is as it is */
    fun slovenian(title: String): String {
        val parts = title.split(" | ").dropLastWhile { it.isEmpty() }
        return if (parts.size == 2 && parts[0].trim().isNotEmpty()) parts[0].trim() else title.trim()
    }

    private val range = Regex("""(\d{1,2})[:.](\d{2})(?:\s*[–—-]\s*(\d{1,2})[:.](\d{2}))?""")

    private fun clock(h: String, m: String) = try {
        LocalTime.of(h.toInt(), m.toInt())
    } catch (_: DateTimeException) {
        null
    }

    /** "09:00–13:00" is 09:00 to 13:00; "10:00" 10:00 with no end */
    fun timesOf(timeRange: String?): Pair<LocalTime, LocalTime?>? {
        val m = range.find(timeRange ?: return null) ?: return null
        val start = clock(m.groupValues[1], m.groupValues[2]) ?: return null
        return start to m.groups[3]?.value?.let { clock(it, m.groupValues[4]) }
    }

    private fun at(date: LocalDate, time: LocalTime): java.time.Instant {
        val local = LocalDateTime.of(date, time)
        return local.toInstant(LJUBLJANA.rules.getOffset(local))
    }
}

/** A JSON array's items; anything else isn't what the site answers */
private fun JsonNode.items(): List<JsonNode> =
    if (isArray) values().toList() else throw IllegalStateException("Not a JSON array: ${toString().take(200)}")
