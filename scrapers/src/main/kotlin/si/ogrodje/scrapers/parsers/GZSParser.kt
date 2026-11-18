package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import si.ogrodje.scrapers.*
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * GZS's Association for Informatics and Telecommunications (https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki):
 * its events page, in a browser, as the list is the page's own script's, switched to the monthly view. Each event
 * has its ID in a hidden field, and its start and end as "dd.MM.yyyy HH:mm - dd.MM.yyyy HH:mm". Events that started
 * over three months ago are left out.
 */
object GZSParser : Parser {
    override val source = Source.GZS

    // Goo read the site's times as Paris's, which is Ljubljana's time zone
    private val zone = ZoneId.of("Europe/Paris")
    private val format = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    override fun accepts(field: SourceField, url: URI) = url.site.contains("gzs.si")

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val start = url.at("/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki")
        val html = context.withPage { page ->
            page.navigate(start.toString())
            page.locator("text=MESEČNI").click()
            page.waitForSelector(".CKDAllEvents")
            page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            page.locator(".CKDAllEvents").innerHTML()
        }
        return events(start, html)
    }

    /** The events in [html], the events list of the page at [url] */
    fun events(url: URI, html: String, now: ZonedDateTime = ZonedDateTime.now(LJUBLJANA)): List<ScrapedEvent> {
        val cutoff = now.minusMonths(3).toInstant()
        return Jsoup.parse(html).select(".CKDMainEventBox").map { box ->
            val id = box.select("#naslov input[type=hidden]").attr("value").trim()
            val link = box.select("#naslov a")
            val dates = box.select("#datum span").text().split("-", limit = 2).map(String::trim)
            require(dates.size == 2) { "No dates for GZS's event $id at $url" }
            ScrapedEvent(
                source = source,
                externalId = "gzs-$id",
                sourceUrl = url.toString(),
                title = link.text(),
                start = time(dates[0]),
                end = time(dates[1]),
                description = box.select("#povzetek").text().trim(),
                eventUrl = link.attr("href").takeIf { runCatching { URI(it) }.isSuccess },
                locationName = box.select("#kraj span").text().trim(),
            )
        }.filter { it.start > cutoff }
    }

    private fun time(text: String) = LocalDateTime.parse(text, format).atZone(zone).toInstant()
}
