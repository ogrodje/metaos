package si.ogrodje.scrapers

import com.microsoft.playwright.Page
import java.net.URI

/** What a parser has to work with: the organisation it scrapes for, the web, and a browser for the sites that need one */
class ScrapeContext(
    val organisationName: String,
    val web: Web,
    private val browser: Pages?,
) {
    /** A fresh page of the one browser, closed once [work] is done; browsers take turns, one page at a time */
    fun <T> withPage(work: (Page) -> T): T =
        checkNotNull(browser) { "This scraper has no browser" }.use(work)
}

/** A browser's pages, one at a time (Browser.kt), or a stand-in in tests */
interface Pages {
    fun <T> use(work: (Page) -> T): T
}

/**
 * A parser of one kind of site, ported from Goo's (github.com/ogrodje/goo, src/main/scala/si/ogrodje/goo/parsers): it
 * says which URLs it takes ([accepts]) and finds their events ([parse]). It throws when the site doesn't answer or
 * isn't what it knows, so the activity tries again; a site with no events is an empty list. Its events' IDs are
 * Goo's, so those Goo found are the same events.
 */
interface Parser {
    val source: Source

    /** Whether it takes [url], in the organisation's [field] */
    fun accepts(field: SourceField, url: URI): Boolean

    fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent>
}

/** Whether [url] is on one of [hosts], with or without www. */
fun on(url: URI, vararg hosts: String): Boolean = url.site in hosts.map { it.removePrefix("www.") }
