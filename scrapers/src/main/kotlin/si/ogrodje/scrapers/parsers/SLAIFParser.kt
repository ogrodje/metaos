package si.ogrodje.scrapers.parsers

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
 * Indico page, where registration is, else to SLAIF's events page. Drafts are left out, and an item that doesn't fit
 * is skipped. Its ID is the item's, as Goo's.
 */
object SLAIFParser : Parser {
    override val source = Source.SLAIF

    private val log = LoggerFactory.getLogger(javaClass)

    override fun accepts(field: SourceField, url: URI) = url.host.orEmpty().let { it == "slaif.si" || it.endsWith(".slaif.si") }

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val eventsPage = url.at("/events").toString()
        return context.web.json(url.at("/api/events")).items().mapNotNull { node ->
            try {
                item(node)
            } catch (e: RuntimeException) {
                log.warn("Skipping SLAIF event ${node.get("id") ?: "?"}: ${e.message}")
                null
            }
        }.filter { it.status == "published" }.map { event(eventsPage, it) }
    }

    private fun event(eventsPage: String, item: Item): ScrapedEvent {
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
            description = item.lecturer?.let { "Izvajalci: $it" },
            eventUrl = item.indicoUrl ?: eventsPage,
            locationName = item.location,
        )
    }

    private data class Item(
        val id: Long,
        val title: String,
        val date: LocalDate,
        val timeRange: String?,
        val location: String?,
        val lecturer: String?,
        val indicoUrl: String?,
        val status: String,
    )

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
