package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import java.net.URI
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Mrežni podjetniški inkubator Koroška (MPIK), on RRA Koroška's site (https://rra-koroska.si/aktualno/dogodki/podjetnistvo),
 * whatever the URL: of its three groups of events only podjetništvo (entrepreneurship: MPIK, PONI, trainings) is read.
 * Each card there has a title, a link, its dates and a time:
 * ```
 * <h3><a href="/aktualno/dogodki/mpik-akademija-uspeha-2026-…">MPIK akademija uspeha 2026: …</a></h3>
 * <li class="wDogodki__info--date"><div><span>17.</span>&nbsp;<span>11.</span>&nbsp;<span>2026</span></div></li>
 * <li class="wDogodki__info--time"><svg …/>16.00 - 19.00</li>
 * ```
 * A card may list several dates, split by `span.dateDivider`, with the year on the last one only, and a range as one
 * day ("25.–26."). Monthly roundups ("Aktualni podjetniški dogodki in razpisi …") list other organisers' events and
 * are skipped, as are cards that ended over a year ago. A series (e.g. "MPIK akademija uspeha 2026") is an event a
 * date, but for the dates that have a card of their own ("MPIK akademija uspeha 2026: …").
 *
 * The place is only on the event's own page (`li.wDogodki__info--place`), so the pages of recent and upcoming events
 * are read, 30 at most; the description comes from the page's calendar data (`$.flVars.rra.aCalendar`). Its ID is the
 * card's slug, with the date for a series, as Goo's.
 */
object MPIKKoroskaParser : Parser {
    override val source = Source.MPIK_KOROSKA

    private val log = LoggerFactory.getLogger(javaClass)

    val site = URI("https://rra-koroska.si")
    val listing = site.at("/aktualno/dogodki/podjetnistvo")
    private const val DOGODKI = "/aktualno/dogodki/"

    private const val MONTHS_BACK = 12L
    private const val DETAILS_DAYS_BACK = 30L
    private const val MAX_DETAILS = 30

    private val hosts = setOf("rra-koroska.si", "www.rra-koroska.si", "mpik-koroska.si", "www.mpik-koroska.si")

    override fun accepts(field: SourceField, url: URI) = url.host.orEmpty() in hosts

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> =
        events(context.web.document(listing), LocalDate.now(LJUBLJANA)) { card ->
            try {
                detailsIn(context.web.document(site.at(card.path)))
            } catch (e: Exception) {
                log.warn("MPIK: no details for ${card.path}: ${e.message}")
                Details(null, null)
            }
        }

    /** The events of the listing [document] as of [today], with each recent card's [details] */
    fun events(document: Document, today: LocalDate, details: (Card) -> Details): List<ScrapedEvent> {
        val cards = cardsIn(document).filterNot { it.isRoundup }.filter { it.lastDay.isAfter(today.minusMonths(MONTHS_BACK)) }
        // Pages of recent and upcoming events only; older events keep what the listing says
        val withPages = cards.filter { it.lastDay.isAfter(today.minusDays(DETAILS_DAYS_BACK)) }.take(MAX_DETAILS).toSet()
        return cards.flatMap { card -> toEvents(card, if (card in withPages) details(card) else Details(null, null), cards) }
    }

    private fun toEvents(card: Card, details: Details, ownCards: List<Card>): List<ScrapedEvent> =
        card.days.filterNot { card.days.size > 1 && hasOwnCard(card, it, ownCards) }.map { day ->
            val start = at(day.from, card.start ?: LocalTime.MIDNIGHT)
            val end = if (day.to != null) {
                at(day.to, card.end ?: card.start ?: LocalTime.MIDNIGHT)
            } else {
                card.end?.let { at(day.from, it) }?.takeIf { it.isAfter(start) }
            }
            ScrapedEvent(
                source = source,
                externalId = if (card.days.size > 1) "mpik-${card.slug}-${day.from}" else "mpik-${card.slug}",
                sourceUrl = listing.toString(),
                title = card.title,
                start = start,
                end = end,
                hasStartTime = card.start != null,
                hasEndTime = day.to == null && card.end != null && end != null,
                description = details.description,
                eventUrl = site.at(card.path).toString(),
                locationName = details.place,
            )
        }

    /** One date of a card, or a range of days ("25.–26. 11.") */
    data class Day(val from: LocalDate, val to: LocalDate?)

    data class Card(val path: String, val title: String, val days: List<Day>, val start: LocalTime?, val end: LocalTime?) {
        val slug: String get() = path.removePrefix(DOGODKI).removeSuffix("/").replace(Regex("[^A-Za-z0-9_-]"), "-")
        val isRoundup: Boolean get() = slug.startsWith("aktualni-podjetniski-dogodki")
        val lastDay: LocalDate get() = days.maxOf { it.to ?: it.from }
    }

    data class Details(val place: String?, val description: String?)

    private val spaces = Regex("\\s+")

    private fun text(e: Element): String = e.text().replace(' ', ' ').replace(spaces, " ").trim()

    private val dayMonth = Regex("""(\d{1,2})\.(?:\s*[–—-]\s*(\d{1,2})\.)?""")
    private val number = Regex("""\d{1,4}""")

    private fun dateOf(year: Int, month: Int, day: Int) = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    /**
     * The groups of a date list ("19. | 5." … divided by `span.dateDivider`) as days; the year is on the last group
     * only, and goes back one for a month later than the next one's (a series across New Year)
     */
    fun daysIn(dateDiv: Element): List<Day> {
        val groups = mutableListOf(mutableListOf<String>())
        for (span in dateDiv.children()) {
            if (span.hasClass("dateDivider")) groups += mutableListOf<String>() else groups.last() += text(span)
        }
        val parts = groups.map { group -> group.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        // (day, last day of a range, month) a group
        val parsed = parts.mapNotNull { group ->
            val dm = dayMonth.matchAt(group[0], 0) ?: return@mapNotNull null
            val month = group.getOrNull(1)?.let { number.find(it)?.value?.toIntOrNull() } ?: return@mapNotNull null
            Triple(dm.groupValues[1].toInt(), dm.groups[2]?.value?.toInt(), month)
        }
        var year = parts.lastOrNull()?.getOrNull(2)?.let { number.find(it)?.value?.toIntOrNull() } ?: return emptyList()
        var nextMonth = 13
        val days = ArrayDeque<Day>()
        for ((day, toDay, month) in parsed.asReversed()) {
            if (month > nextMonth) year -= 1
            dateOf(year, month, day)?.let { from ->
                days.addFirst(Day(from, toDay?.let { dateOf(year, month, it) }?.takeIf { it.isAfter(from) }))
            }
            nextMonth = month
        }
        return days.toList()
    }

    private val times = Regex("""(\d{1,2})[.:](\d{2})(?:\s*[–—-]\s*(\d{1,2})[.:](\d{2}))?""")

    private fun clock(h: String, m: String) = try {
        LocalTime.of(h.toInt(), m.toInt())
    } catch (_: DateTimeException) {
        null
    }

    /** "16.00 - 19.00" is 16:00 to 19:00; "8.30" 08:30 with no end */
    fun timesIn(raw: String): Pair<LocalTime, LocalTime?>? {
        val m = times.find(raw) ?: return null
        val start = clock(m.groupValues[1], m.groupValues[2]) ?: return null
        return start to m.groups[3]?.value?.let { clock(it, m.groupValues[4]) }
    }

    fun cardsIn(document: Document): List<Card> =
        document.select("div.wAktualno").mapNotNull { card ->
            val link = card.selectFirst("h3 a[href]") ?: return@mapNotNull null
            val path = link.attr("href").takeIf { it.startsWith(DOGODKI) } ?: return@mapNotNull null
            val title = text(link).ifEmpty { return@mapNotNull null }
            val dates = card.selectFirst("li.wDogodki__info--date div") ?: return@mapNotNull null
            val days = daysIn(dates).ifEmpty { return@mapNotNull null }
            val time = card.selectFirst("li.wDogodki__info--time")?.let { li ->
                li.select("svg").remove()
                timesIn(text(li))
            }
            Card(path, title, days, time?.first, time?.second)
        }.distinctBy { it.path }

    /** A series' date that has a card of its own: "MPIK akademija uspeha 2026: …" on that day */
    private fun hasOwnCard(series: Card, day: Day, cards: List<Card>): Boolean =
        cards.any { other ->
            other.path != series.path && other.days.size == 1 && other.days[0].from == day.from &&
                other.title.lowercase().startsWith(series.title.lowercase().removeSuffix(" "))
        }

    private val calendarData = Regex("""(?s)\$\.flVars\s*=\s*(\{.*?\});\s""")

    /**
     * The event page's place (the first `li.wDogodki__info--place`: the event's own, before related events) and the
     * description of its calendar data, when it has some
     */
    fun detailsIn(document: Document): Details {
        // The icon's <title> ("Lokacija") is not part of the place
        val place = document.selectFirst("ul.wDogodki__info li.wDogodki__info--place")?.let { li ->
            li.select("svg").remove()
            text(li)
        }?.ifEmpty { null }
        val description = document.select("script").asSequence()
            .firstNotNullOfOrNull { calendarData.find(it.data())?.groupValues?.get(1) }
            ?.let { runCatching { Web.MAPPER.readTree(it) }.getOrNull() }
            ?.get("rra")?.get("aCalendar")?.get("description")?.takeIf { it.isString }?.stringValue()
            ?.let { Jsoup.parse(it).text().replace(' ', ' ').replace(spaces, " ").trim() }
            ?.ifEmpty { null }
        return Details(place, description)
    }

    private fun at(date: LocalDate, time: LocalTime): Instant {
        val local = LocalDateTime.of(date, time)
        return local.toInstant(LJUBLJANA.rules.getOffset(local))
    }
}
