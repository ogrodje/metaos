package si.ogrodje.scrapers.parsers

import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import java.net.URI
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Katapult, the hardware startup accelerator in Trbovlje (https://katapult.si/dogodki): its events page lists cards
 * linking to posts, `/novice/<slug>`, 8 a page (`?page=N`), of which it reads the first two pages, and then each
 * post. A post's side column says when and where,
 * ```
 * Kdaj? Torek, 17. november 2026, od 9.00 do 12.00
 * Kje? Katapult in Dewesoft, Trbovlje
 * ```
 * written in many ways: "Četrtek, 22.10.2026, 9.00 - 12.00", "Četrtek, 22. oktobra, ob 17.30–20.30" (no year), "Od
 * četrtka, 26., do sobote, 28. novembra 2026", "23., 27. in 29. oktober 2026, vsak dan od 9.00 do 16.00". The date
 * under a post's title is when it was published. A post without a "Kdaj" line naming a day (a course over months, a
 * workshop in two terms), or that doesn't load, is skipped. Its ID is the post's slug, as Goo's.
 */
object KatapultParser : Parser {
    override val source = Source.KATAPULT

    private val log = LoggerFactory.getLogger(javaClass)

    // 8 posts a page: two pages reach back about half a year
    private const val MAX_PAGES = 2

    override fun accepts(field: SourceField, url: URI) = url.host.orEmpty().let { it == "katapult.si" || it == "www.katapult.si" }

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> =
        listing(context.web, url).mapNotNull { slug ->
            try {
                val post = postIn(context.web.document(url.at("/novice/$slug")))
                    ?: throw IllegalStateException("no 'Kdaj' line with a day")
                event(url, slug, post)
            } catch (e: Exception) {
                log.warn("Skipping Katapult post $slug: ${e.message}")
                null
            }
        }

    private fun listing(web: Web, url: URI): List<String> {
        val slugs = mutableListOf<String>()
        for (page in 1..MAX_PAGES) {
            val pageUrl = if (page == 1) url.at("/dogodki") else URI("${url.at("/dogodki")}?page=$page")
            val found = slugsIn(web.document(pageUrl))
            slugs += found
            if (found.isEmpty()) break
        }
        return slugs.distinct()
    }

    private fun event(base: URI, slug: String, post: Post): ScrapedEvent {
        val whenIs = post.whenIs
        val start = at(whenIs.startDate, whenIs.startTime ?: LocalTime.MIDNIGHT)
        val end = if (whenIs.endDate != null) {
            at(whenIs.endDate, whenIs.endTime ?: LocalTime.MIDNIGHT)
        } else {
            whenIs.endTime?.let { at(whenIs.startDate, it) }
        }?.takeIf { it.isAfter(start) }
        return ScrapedEvent(
            source = source,
            externalId = "katapult-$slug",
            sourceUrl = base.at("/dogodki").toString(),
            title = post.title,
            start = start,
            end = end,
            hasStartTime = whenIs.startTime != null,
            hasEndTime = end != null && whenIs.endTime != null,
            description = post.description,
            eventUrl = base.at("/novice/$slug").toString(),
            locationName = post.place.name,
            locationAddress = post.place.address,
        )
    }

    /** When an event happens: its first (and last, for several days) day, and the time of day */
    data class When(val startDate: LocalDate, val startTime: LocalTime?, val endDate: LocalDate?, val endTime: LocalTime?)

    data class Post(val title: String, val whenIs: When, val place: Location, val description: String?)

    /** The posts a listing page links to, in order */
    fun slugsIn(doc: Document): List<String> =
        doc.select("h3.headline3 a[href^=/novice/], h3.headline3 a[href*=katapult.si/novice/]")
            .map { a -> a.attr("href").substringAfterLast("/novice/").takeWhile { it != '?' && it != '#' }.removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .distinct()

    private val publishedFormat = DateTimeFormatter.ofPattern("d.M.yyyy")

    /** A post's title, when and where; null without a "Kdaj" line naming a day */
    fun postIn(doc: Document): Post? {
        val paragraphs = doc.select("div.e-text p").map { it.text().replace(' ', ' ').trim() }
        val published = doc.selectFirst(".blog-single-info-meta span.text-left")
            ?.let { runCatching { LocalDate.parse(it.text().trim(), publishedFormat) }.getOrNull() }
            ?: LocalDate.now(LJUBLJANA)
        val title = doc.selectFirst("h1")?.let { cleanTitle(it.text()) }?.ifEmpty { null } ?: return null
        val kdaj = paragraphs.firstNotNullOfOrNull { whenLine.matchEntire(it)?.groupValues?.get(1) } ?: return null
        val whenIs = whenOf(kdaj, published) ?: return null
        return Post(
            title = title,
            whenIs = whenIs,
            place = Locations.parse(paragraphs.firstNotNullOfOrNull { whereLine.matchEntire(it)?.groupValues?.get(1) }),
            description = doc.selectFirst(".blog-single-subtitle")?.text()?.trim()?.ifEmpty { null },
        )
    }

    // "Kdaj? …", "Kdaj: …", "Datum: …"; "Kje? …", "Kje: …", "Lokacija: …"; not "Kje se najpogosteje zatakne: …"
    private val whenLine = Regex("""(?iu)^(?:kdaj|datum)\s*[?:]\s*(.+)$""")
    private val whereLine = Regex("""(?iu)^(?:kje|lokacija|kraj)\s*[?:]\s*(.+)$""")

    // "Dogodek: Obisk Katapulta …" is "Obisk Katapulta …"; "… I 17.11.2026", "… 22.10.2026", "… (23., 27. in 29.
    // oktober)" are without the date, which is in the event's fields
    private val datedSuffix = Regex("""\s+(?:I\s+)?\d{1,2}\.[\d.\s–—-]*\d{4}\s*$""")
    private val bracketedDays = Regex("""\s*\(\d{1,2}\.[^)]*\)\s*$""")
    private val dogodekPrefix = Regex("""(?iu)^(?:brezplačni\s+)?dogodek\s*:\s*""")

    fun cleanTitle(raw: String): String =
        dogodekPrefix.replaceFirst(raw.trim().replace(datedSuffix, "").replace(bracketedDays, ""), "").trim()

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "maj" to 5, "jun" to 6,
        "jul" to 7, "avg" to 8, "sep" to 9, "okt" to 10, "nov" to 11, "dec" to 12,
    )

    // A day, then maybe a month (a number with a dot, or a name), then maybe a year: "17. november 2026", "22.10.2026",
    // "16. 12. 2025", "16.6. 2026", "22. oktobra", or a bare "26." whose month comes later
    private val dayToken =
        Regex("""(?iu)(?<![\d.])(\d{1,2})\.\s*(?:(\d{1,2})\.|(jan|feb|mar|apr|maj|jun|jul|avg|sep|okt|nov|dec)\p{L}*)?(?:\s*,?\s*(\d{4}))?""")

    // "od 9.00 do 12.00", "9.00 - 12.00", "ob 17.30–20.30", "15.00–18.00", "17.30"
    private val timeRange = Regex("""(\d{1,2})[:.](\d{2})(?!\d|\.\d)(?:\s*(?:–|—|-|do)\s*(\d{1,2})[:.](\d{2})(?!\d))?""")

    private data class Token(val day: Int, val month: Int?, val year: Int?, val end: Int)

    private fun tokens(text: String): List<Token> =
        dayToken.findAll(text).mapNotNull { m ->
            val month = m.groups[2]?.value?.toIntOrNull() ?: m.groups[3]?.value?.let { months[it.lowercase().take(3)] }
            val year = m.groups[4]?.value?.toIntOrNull()
            val day = m.groups[1]!!
            // "9.00", "17.30": a time, not a day: the dot is followed by digits that aren't a month
            val isTime = month == null && year == null && text.getOrNull(day.range.first + day.value.length + 1)?.isDigit() == true
            if (isTime || (month != null && (month < 1 || month > 12))) null else Token(day.value.toInt(), month, year, m.range.last + 1)
        }.toList()

    private fun clock(h: String, m: String) = EventText.clock(h, m)

    /**
     * When, from a "Kdaj" line, given when the post was published. A day without a month takes the next day's month
     * ("26., do sobote, 28. novembra"); a missing year is the next day's, else the publish year. A date two months
     * before publishing is taken to be next year: posts announce, and one says "9.3.2025" for March 2026.
     */
    fun whenOf(text: String, published: LocalDate): When? {
        // A missing month and year from the days after it
        val filled = tokens(text).foldRight(emptyList<Token>()) { t, later ->
            val next = later.firstOrNull()
            listOf(t.copy(month = t.month ?: next?.month, year = t.year ?: next?.year)) + later
        }
        val dates = filled.mapNotNull { t ->
            val month = t.month ?: return@mapNotNull null
            val date = try {
                LocalDate.of(t.year ?: published.year, month, t.day)
            } catch (_: DateTimeException) {
                return@mapNotNull null
            }
            (if (date.isBefore(published.minusDays(60))) date.plusYears(1) else date) to t.end
        }
        val startDate = dates.firstOrNull()?.first ?: return null
        val lastDay = dates.last()
        val endDate = lastDay.first.takeIf { it.isAfter(startDate) && !it.isAfter(startDate.plusDays(31)) }
        // The time of day follows the (last) day: "…, od 9.00 do 12.00", "… 2026, vsak dan od 9.00 do 16.00"
        val times = timeRange.find(text.substring(lastDay.second))
        val start = times?.let { clock(it.groupValues[1], it.groupValues[2]) }
        val end = times?.groups?.get(3)?.value?.let { clock(it, times.groupValues[4]) }?.takeIf { start != null }
        return When(startDate, start, endDate, end)
    }

    private fun at(date: LocalDate, time: LocalTime): Instant {
        val local = LocalDateTime.of(date, time)
        return local.toInstant(LJUBLJANA.rules.getOffset(local))
    }
}
