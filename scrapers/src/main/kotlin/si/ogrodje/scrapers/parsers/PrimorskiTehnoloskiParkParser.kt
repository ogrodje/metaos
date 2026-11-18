package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Primorski tehnološki park (https://www.primorski-tp.si/vsi-dogodki/): the events in its page's JSON-LD, the lists of
 * them, with their times and offsets, from two months ago on. Its ID is the SHA-1 of the event's URL, ptp-<sha1>, and
 * its URL is its source too, as Goo's.
 */
object PrimorskiTehnoloskiParkParser : Parser {
    override val source = Source.PRIMORSKI_TEHNOLOSKI_PARK

    override fun accepts(field: SourceField, url: URI) = (url.host ?: "").lowercase().contains("primorski-tp.si")

    override fun parse(context: ScrapeContext, url: URI) = parse(context, url, Instant.now())

    internal fun parse(context: ScrapeContext, url: URI, now: Instant): List<ScrapedEvent> {
        val since = now.atZone(LJUBLJANA).minusMonths(2).toInstant()
        return context.web.document(url.at("/vsi-dogodki/")).select("script[type=application/ld+json]")
            .map { it.data() }
            .filter { it.contains("\"Event\"") }
            .map { Web.MAPPER.readTree(it) }
            .filter { it.isArray }
            .flatMap { lds -> lds.values().map(::event) }
            .filter { it.start.isAfter(since) }
    }

    private fun event(node: JsonNode): ScrapedEvent {
        fun text(name: String) = node.get(name)?.takeUnless { it.isNull }?.asString()
        fun time(name: String) = text(name)?.let { OffsetDateTime.parse(it).toInstant() }
        val url = requireNotNull(text("url")) { "No url in $node" }
        return ScrapedEvent(
            source = source,
            externalId = "ptp-${sha1(url)}",
            sourceUrl = url,
            title = requireNotNull(text("name")) { "No name in $node" },
            start = requireNotNull(time("startDate")) { "No startDate in $node" },
            end = time("endDate"),
            description = text("description"),
            eventUrl = url,
        )
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
