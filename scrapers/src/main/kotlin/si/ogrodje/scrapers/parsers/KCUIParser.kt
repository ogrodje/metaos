package si.ogrodje.scrapers.parsers

import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate

/**
 * KCUI (https://kcui.si/izobrazevanja): its trainings, current and past on one page, each read from its own page: the
 * title (`h1`), the lede under it, and the "Kdaj" and "Kje" fields, e.g. "Petek, 17. april 2026 ob 10:00", whose time
 * is optional. A page that doesn't read is left out. Its ID is the SHA-1 of the event's URL, kcui-<sha1>.
 */
object KCUIParser : Parser {
    override val source = Source.KCUI

    private val log = LoggerFactory.getLogger(javaClass)

    private val eventHref = Regex("^/izobrazevanja/[a-z0-9-]+$")
    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "maj" to 5, "jun" to 6,
        "jul" to 7, "avg" to 8, "sep" to 9, "okt" to 10, "nov" to 11, "dec" to 12,
    )
    private val dateTime = Regex("""(\d{1,2})\.\s+(\p{L}+)\s+(\d{4})(?:\s+ob\s+(\d{1,2})[:.](\d{2}))?""")

    override fun accepts(field: SourceField, url: URI) = (url.host ?: "").lowercase().contains("kcui.si")

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val listingUrl = url.at("/izobrazevanja")
        return context.web.document(listingUrl).select("a[href]")
            .map { it.attr("href") }
            .filter { eventHref.matches(it) }
            .distinct()
            .map { url.resolve(it) }
            .mapNotNull { eventUrl ->
                try {
                    event(context, listingUrl, eventUrl)
                } catch (e: Exception) {
                    log.warn("Failed to parse KCUI event {}: {}", eventUrl, e.message)
                    null
                }
            }
    }

    private fun event(context: ScrapeContext, listingUrl: URI, eventUrl: URI): ScrapedEvent {
        val page = context.web.document(eventUrl)
        val title = (page.selectFirst("h1") ?: throw NoSuchElementException("No element found for h1")).text().trim()
        val rawWhen = field(page, "Kdaj") ?: throw IllegalStateException("No 'Kdaj' field found")
        val m = dateTime.find(rawWhen) ?: throw IllegalStateException("Could not parse date from '$rawWhen'")
        val month = months[m.groupValues[2].lowercase().take(3)] ?: throw IllegalStateException("Unknown month in '$rawWhen'")
        val date = LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
        val hour = m.groups[4]
        val start = if (hour != null) date.atTime(hour.value.toInt(), m.groupValues[5].toInt()) else date.atStartOfDay()
        return ScrapedEvent(
            source = source,
            externalId = "kcui-${sha1(eventUrl.toString())}",
            sourceUrl = listingUrl.toString(),
            title = title,
            start = start.atZone(LJUBLJANA).toInstant(),
            hasStartTime = hour != null,
            hasEndTime = false,
            description = page.selectFirst("h1 + p")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            eventUrl = eventUrl.toString(),
            locationName = field(page, "Kje"),
        )
    }

    /** The value after a labelled field, e.g. "Kdaj" */
    private fun field(page: Document, label: String): String? =
        page.select("span").firstOrNull { it.text().trim().equals(label, ignoreCase = true) }
            ?.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
