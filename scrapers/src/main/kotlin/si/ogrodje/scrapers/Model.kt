package si.ogrodje.scrapers

import java.net.URI
import java.time.Instant
import java.time.ZoneId

/** Ogrodje's days and the sites' times are Ljubljana's */
val LJUBLJANA: ZoneId = ZoneId.of("Europe/Ljubljana")

/**
 * Where an organisation's events are announced, by the field of the organisation the URL is in, as Goo's meetups had
 * them: iCal and Luma by their field alone, the rest by the URL's host, whichever field it's in
 */
enum class SourceField { WEBSITE, MEETUP, EVENTBRITE, ICAL, LUMA, KOMPOT }

/**
 * One URL of one organisation to scrape, as MetaOS's events workflow asks for it (EventsSync.kt in MetaOS, which has
 * the same type by the same shape). [since] is the oldest start that's still wanted: older events aren't scraped.
 */
data class ScrapeRequest(
    val organisationId: String,
    val organisationName: String,
    val field: SourceField,
    val url: String,
    val since: Instant,
)

/**
 * An event as a parser found it. [externalId] is the parser's own, as Goo had it (e.g. meetup-123, gzs-456, an
 * iCal UID), so the same event found again is the same event; [source] says which parser found it. Its start and end
 * are instants, with a time of day only where [hasStartTime] and [hasEndTime] say.
 */
data class ScrapedEvent(
    val source: Source,
    val externalId: String,
    val sourceUrl: String,
    val title: String,
    val start: Instant,
    val end: Instant? = null,
    val hasStartTime: Boolean = true,
    val hasEndTime: Boolean = true,
    val description: String? = null,
    val eventUrl: String? = null,
    val locationName: String? = null,
    val locationAddress: String? = null,
)

/** What scraping a URL found: by which parser ([source], none when no parser takes the URL), and its events */
data class ScrapeResult(val source: Source?, val events: List<ScrapedEvent>)

/** Every parser, as Goo's Source had them; MANUAL is the team's own, which no parser makes */
enum class Source {
    MEETUP, EVENTBRITE, TEHNOLOSKI_PARK_LJUBLJANA, PRIMORSKI_TEHNOLOSKI_PARK, GZS, STARTUP_SI, ICAL, MANUAL, FRI, KCUI,
    INKUBATOR_SAVINJSKE_REGIJE, RACUNALNISKI_MUZEJ, LUI, SPLETNE_URICE, SLAIF, LUMA, DATA_SCIENCE,
    POMURSKI_TEHNOLOSKI_PARK, INKUBATOR_BELA_KRAJINA, INKUBATOR_ROGASKA_SLATINA, KATAPULT,
    PODJETNISKI_INKUBATOR_PODBREZNIK, MPIK_KOROSKA,
}

/** A URL's host without www., lowercased, e.g. tp-lj.si */
val URI.site: String get() = (host ?: "").lowercase().removePrefix("www.")

/** The same site at another path, as Goo's parsers ask a site's own events page whatever page the URL is */
fun URI.at(path: String): URI = URI(scheme, authority, path, null, null)

/**
 * One way of writing a page's URL, so the same page is the same event however a link wrote it (OG-67): its host
 * lowercased and without www., and without a slash at the end of its path or a fragment.
 */
fun URI.normalised(): URI {
    val path = (rawPath ?: "").trimEnd('/')
    val port = if (port == -1) "" else ":$port"
    return URI("${(scheme ?: "https").lowercase()}://$site$port$path${rawQuery?.let { "?$it" } ?: ""}")
}
