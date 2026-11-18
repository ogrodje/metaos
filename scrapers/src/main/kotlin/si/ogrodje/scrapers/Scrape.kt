package si.ogrodje.scrapers

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright

/**
 * Scrapes one URL as the worker would and prints its events, to try a parser against the live site:
 * `./gradlew -p scrapers scrape --args='https://www.meetup.com/some-group/ [MEETUP]'`
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: scrape <url> [WEBSITE|MEETUP|EVENTBRITE|ICAL|LUMA]" }
    val field = args.getOrNull(1)?.let(SourceField::valueOf) ?: SourceField.WEBSITE
    Playwright.create().use { playwright ->
        val browser by lazy { playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)) }
        val pages = object : Pages {
            override fun <T> use(work: (Page) -> T): T = browser.newContext().use { work(it.newPage()) }
        }
        val result = scrape(ScrapeRequest("", "", field, args[0], aYearAgo()), HttpWeb(), pages)
        println("${result.source ?: "No parser takes it"}: ${result.events.size} events")
        result.events.sortedBy { it.start }.forEach { println(it) }
    }
}
