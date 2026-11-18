package si.ogrodje.scrapers.parsers

import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Računalniški muzej (https://www.racunalniski-muzej.si/event/): WordPress with the "ova-events" plugin, whose events
 * aren't in the REST API, so its archive's three newest pages are read, newest first. Their cards have no year, so each
 * event's own page is read: its "add to calendar" link has its start and end, month first (`e[0][date_start]=12/02/2026
 * 17:00`), and its order box the place. An end before the start is left out; a page that doesn't read too. Its ID is
 * the WordPress post's, racunalniski-muzej-<id>, else its URL's last part.
 */
object RacunalniskiMuzejParser : Parser {
    override val source = Source.RACUNALNISKI_MUZEJ

    private val log = LoggerFactory.getLogger(javaClass)

    private const val MAX_PAGES = 3
    private val postIdClass = Regex("""\bpostid-(\d+)\b""")
    private val calendarDate = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})(?:\s+(\d{1,2}):(\d{2}))?""")

    override fun accepts(field: SourceField, url: URI) = (url.host ?: "").lowercase().contains("racunalniski-muzej.si")

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val sourceUrl = url.at("/event/")
        return eventUrls(context.web, url).mapNotNull { eventUrl ->
            try {
                event(context, sourceUrl, eventUrl)
            } catch (e: Exception) {
                log.warn("Failed to parse Računalniški muzej event {}: {}", eventUrl, e.message)
                null
            }
        }
    }

    private fun archiveUrl(url: URI, page: Int) = if (page == 1) url.at("/event/") else url.at("/event/page/$page/")

    /** The events of the archive's newest pages; a page past the end is a 404, which ends it too */
    private fun eventUrls(web: Web, url: URI): List<URI> {
        val all = mutableListOf<URI>()
        for (page in 1..MAX_PAGES) {
            val document = try {
                web.document(archiveUrl(url, page))
            } catch (e: WebException) {
                if (page > 1 && e.status == 404) break else throw e
            }
            val urls = document.select(".archive_event .event_title a[href]").map { it.attr("abs:href") }.distinct().map(::URI)
            all += urls.filterNot { it in all }
            if (urls.isEmpty()) break
        }
        return all
    }

    private fun event(context: ScrapeContext, sourceUrl: URI, eventUrl: URI): ScrapedEvent {
        val page = context.web.document(eventUrl)
        val title = (page.selectFirst(".single_event h1") ?: throw NoSuchElementException("No element found for .single_event h1"))
            .text().trim()
        val rawStart = calendarParam(page, "date_start") ?: throw IllegalStateException("No calendar start date found")
        val (startDate, startTime) = calendarDateTime(rawStart)
            ?: throw IllegalStateException("Could not parse start date from '$rawStart'")
        val start = startDate.atTime(startTime ?: LocalTime.MIDNIGHT)
        // Only an end with a time after the start: one event on the site ends two days before it begins
        val end: LocalDateTime? = calendarParam(page, "date_end")?.let(::calendarDateTime)
            ?.let { (date, time) -> time?.let { date.atTime(it) } }
            ?.takeIf { it.isAfter(start) }
        val id = page.selectFirst("body")?.let { postIdClass.find(it.className())?.groupValues?.get(1) }
            ?: eventUrl.path.removeSuffix("/").split('/').last()
        return ScrapedEvent(
            source = source,
            externalId = "racunalniski-muzej-$id",
            sourceUrl = sourceUrl.toString(),
            title = title,
            start = start.atZone(LJUBLJANA).toInstant(),
            end = end?.atZone(LJUBLJANA)?.toInstant(),
            hasStartTime = startTime != null,
            hasEndTime = end != null,
            description = page.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() },
            eventUrl = eventUrl.toString(),
            locationName = info(page, "Lokacija:"),
        )
    }

    /** The value by a label in the order box, e.g. "Lokacija:" */
    private fun info(page: Document, label: String): String? =
        page.select(".single_event .info_order li")
            .firstOrNull { li -> li.selectFirst(".label")?.text()?.trim()?.equals(label, ignoreCase = true) == true }
            ?.selectFirst(".label + span")?.text()?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() }

    private fun calendarParam(page: Document, name: String): String? =
        page.selectFirst(".single_event a[href*=addtocalendar.com/atc/]")?.let { calendarParamIn(it.attr("href"), name) }

    /** The value of `e[0][<name>]` in an addtocalendar.com link, plain or percent-encoded */
    internal fun calendarParamIn(href: String, name: String): String? {
        val keys = setOf("e[0][$name]", "e%5B0%5D%5B$name%5D")
        return href.split('&').asSequence()
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0].split('?').last() in keys }
            ?.let { (_, value) -> runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value).trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    /** The day and, if it has one, the time of a calendar value such as "12/02/2026 17:00" */
    internal fun calendarDateTime(raw: String): Pair<LocalDate, LocalTime?>? {
        val m = calendarDate.find(raw.trim()) ?: return null
        val (month, day, year, hour, minute) = m.destructured
        val date = runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull() ?: return null
        val time = if (hour.isEmpty()) null else runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull()
        return date to time
    }
}
