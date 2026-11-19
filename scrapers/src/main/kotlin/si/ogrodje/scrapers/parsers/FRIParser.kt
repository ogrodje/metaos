package si.ogrodje.scrapers.parsers

import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/**
 * FRI's calendar (https://fri.uni-lj.si/sl/koledar-dogodkov): its cards, `div.dogodek-item`, each with a day and a
 * month but no year, which is the one that puts it closest to today, and an hour, if any. A card of one day has
 * `.date-single-top` and `.date-single-bottom`, one of several days `.date-top` and `.date-bottom`, each with `.dan` and
 * `.mesec`. A card that doesn't read is left out, as are doctoral defences and the events DataScience@UL-FRI lists
 * itself ([DataScienceParser]), unless its page doesn't answer. Its ID is the SHA-1 of the event's URL, fri-<sha1>.
 *
 * It reads FRI from [homepage] alone, whichever of FRI's addresses it's given (with www. or without, its calendar or
 * any other page), and its events' URLs are [normalised]: an organisation with FRI in two of its links, e.g.
 * https://www.fri.uni-lj.si/ and https://fri.uni-lj.si, had each event twice, by two IDs (OG-67).
 */
object FRIParser : Parser {
    override val source = Source.FRI

    private val log = LoggerFactory.getLogger(javaClass)

    internal data class DayMonth(val day: String, val month: String)

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "maj" to 5, "jun" to 6,
        "jul" to 7, "avg" to 8, "sep" to 9, "okt" to 10, "nov" to 11, "dec" to 12,
    )
    private val dayNumber = Regex("""(\d{1,2})""")
    private val clockTime = Regex("""(\d{1,2})[:.](\d{2})""")

    /** Where FRI is read from, without www., as the IDs of the events Goo found have it */
    val homepage = URI("https://fri.uni-lj.si")

    /** Exactly FRI: its subdomains, e.g. datascience.fri.uni-lj.si, are other sites */
    override fun accepts(field: SourceField, url: URI) =
        (url.host ?: "").lowercase().let { it == "fri.uni-lj.si" || it == "www.fri.uni-lj.si" }

    /** FRI's events, from [homepage], whichever of FRI's addresses [url] is */
    override fun parse(context: ScrapeContext, url: URI) = parse(context, LocalDate.now(LJUBLJANA))

    internal fun parse(context: ScrapeContext, today: LocalDate): List<ScrapedEvent> {
        val url = homepage
        val calendarUrl = url.at("/sl/koledar-dogodkov")
        val calendar = context.web.document(calendarUrl).selectFirst("div#vsi-dogodki")
            ?: throw NoSuchElementException("No calendar at $calendarUrl")
        val events = calendar.select("div.dogodek-item").mapNotNull { card ->
            try {
                event(url, today, card)
            } catch (e: Exception) {
                log.warn("Failed to parse FRI event: {}", e.message)
                null
            }
        }
        val ownedByDataScience = try {
            DataScienceParser.listed(context.web, DataScienceParser.homepage, today)
        } catch (e: Exception) {
            log.warn("Could not read DataScience events, keeping all: {}", e.message)
            emptyList()
        }
        return events
            .filterNot { URI(it.eventUrl!!).path.contains("javna-predstavitev") }
            .filterNot { e ->
                val day = e.start.atZone(LJUBLJANA).toLocalDate()
                ownedByDataScience.any { DataScienceParser.sameEvent(day, e.title, it) }
            }
            .mapNotNull { event ->
                val detail = URI(checkNotNull(event.eventUrl))
                if (detail.scheme !in setOf("https", "http") || detail.host != homepage.host) return@mapNotNull event
                try {
                    val body = context.web.document(detail).getElementById("eventOpis") ?: return@mapNotNull event
                    val paragraphs = body.select("p").map { it.text() }.filter { it.isNotBlank() }
                    val description = paragraphs.joinToString("\n\n").ifEmpty { body.text() }
                    event.copy(description = description.ifBlank { null })
                } catch (e: IOException) {
                    // Skip this record for this refresh so a failed fetch cannot erase a saved description.
                    log.warn("Could not read FRI event {}: {}", detail, e.message)
                    null
                }
            }
    }

    private fun event(url: URI, today: LocalDate, card: Element): ScrapedEvent {
        val anchor = card.selectFirst(".event-title a[href]") ?: card.selectFirst("a[href]")
            ?: throw IllegalStateException("No link found")
        val link = url.resolve(anchor.attr("href").trim()).normalised().toString()
        val title = anchor.text().trim().takeIf { it.isNotEmpty() } ?: throw IllegalStateException("No title")
        val (startDay, endDay) = dates(card) ?: throw IllegalStateException("No date found for '$title'")
        val startDate = dateOf(startDay, today) ?: throw IllegalStateException("Could not read date '$startDay' for '$title'")
        val endDate = endDay?.let { dateOf(it, today) }?.let { if (it.isBefore(startDate)) it.plusYears(1) else it }
        val startTime = card.selectFirst(".dogodki-hour")?.let { timeIn(it.text()) }
        val time = startTime ?: LocalTime.MIDNIGHT
        return ScrapedEvent(
            source = source,
            externalId = "fri-${sha1(link)}",
            sourceUrl = url.toString(),
            title = title,
            start = startDate.atTime(time).atZone(LJUBLJANA).toInstant(),
            // The calendar has no end time; an event of several days ends on its last
            end = endDate?.takeIf { it.isAfter(startDate) }?.atTime(time)?.atZone(LJUBLJANA)?.toInstant(),
            hasStartTime = startTime != null,
            hasEndTime = false,
            eventUrl = link,
            locationName = "Fakulteta za računalništvo in informatiko (UL)",
            locationAddress = "Večna pot 113, 1000 Ljubljana",
        )
    }

    private fun text(e: Element, query: String) = e.selectFirst(query)?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /** A card's first (and, over several days, last) day and month, in either layout */
    internal fun dates(card: Element): Pair<DayMonth, DayMonth?>? {
        val singleDay = text(card, ".date-single-top")
        val singleMonth = text(card, ".date-single-bottom")
        if (singleDay != null && singleMonth != null) return DayMonth(singleDay, singleMonth) to null
        val startDay = text(card, ".date-top .dan") ?: return null
        val startMonth = text(card, ".date-top .mesec") ?: return null
        val endDay = text(card, ".date-bottom .dan")
        val endMonth = text(card, ".date-bottom .mesec")
        return DayMonth(startDay, startMonth) to if (endDay != null && endMonth != null) DayMonth(endDay, endMonth) else null
    }

    /** The calendar has no year: it's the one that puts the day closest to [today], across New Year too */
    internal fun dateOf(dayMonth: DayMonth, today: LocalDate): LocalDate? {
        val day = dayNumber.find(dayMonth.day)?.groupValues?.get(1)?.toInt() ?: return null
        val month = months[dayMonth.month.trim().lowercase().take(3)] ?: return null
        return listOf(today.year - 1, today.year, today.year + 1)
            .mapNotNull { runCatching { LocalDate.of(it, month, day) }.getOrNull() }
            .minByOrNull { abs(it.toEpochDay() - today.toEpochDay()) }
    }

    /** "ob 17:15" is 17:15 */
    internal fun timeIn(raw: String): LocalTime? =
        clockTime.find(raw)?.let { runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
