package si.ogrodje.scrapers

import si.ogrodje.scrapers.parsers.*
import java.net.URI
import java.time.Instant

/**
 * Every parser, in the order Goo tried them (EventsSync.runParser): the first that takes a URL scrapes it. Goo's FERI
 * had no parser, and Kompot's links none either, so they're left out.
 */
val PARSERS: List<Parser> = listOf(
    MeetupComParser,
    EventbriteParser,
    TehnoloskiParkLjubljanaParser,
    GZSParser,
    PrimorskiTehnoloskiParkParser,
    StartupSiParser,
    ICalParser,
    LumaParser,
    DataScienceParser,
    FRIParser,
    KCUIParser,
    InkubatorSavinjskeRegijeParser,
    RacunalniskiMuzejParser,
    WordPressEventsParser.LUI,
    SpletneUriceParser,
    WordPressEventsParser.PomurskiTehnoloskiPark,
    WordPressEventsParser.InkubatorBelaKrajina,
    WordPressEventsParser.InkubatorRogaskaSlatina,
    SLAIFParser,
    KatapultParser,
    PodbreznikParser,
    MPIKKoroskaParser,
)

/** The parser that takes [url] in [field], if any */
fun parserFor(field: SourceField, url: URI): Parser? = PARSERS.firstOrNull { it.accepts(field, url) }

/**
 * Scrapes [request]'s URL with the parser that takes it, and keeps the events that start at its `since` or later, or
 * haven't ended by then: older ones aren't wanted (OG-64)
 */
fun scrape(request: ScrapeRequest, web: Web, pages: Pages?): ScrapeResult {
    val url = URI(request.url.trim())
    val parser = parserFor(request.field, url) ?: return ScrapeResult(null, emptyList())
    val events = parser.parse(ScrapeContext(request.organisationName, web, pages), url)
    return ScrapeResult(parser.source, events.filter { (it.end ?: it.start) >= request.since }.distinctBy { it.externalId })
}

/** The start of what's wanted when nothing else says: a year ago */
fun aYearAgo(now: Instant = Instant.now()): Instant = now.minus(java.time.Duration.ofDays(365))
