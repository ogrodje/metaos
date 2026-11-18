package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Podjetniški inkubator Podbreznik, the incubator of Razvojni center Novo mesto (https://www.rc-nm.si/dogodki/): the
 * newest 20 of its WordPress site's events (post type `dogodek`, through the REST API) of the entrepreneurship
 * department (`oddelek-podjetnistvo`) only, as the others' are regional development and community events.
 *
 * The REST API has no event fields, so each event's page is read: its schema.org `Event` (`startDate`, `endDate`,
 * `eventAttendanceMode`), whose times are Ljubljana's with a wrong "+00:00", so the offset is ignored; and its sidebar,
 * whose `.single-event-meta-schedule-time-start/-end` say whether there's a time at all (one without has "00:00" in its
 * `startDate`), and whose `.event-location` has the place and its address, a line each. A page without an `Event` is
 * skipped. Its ID is the post's, as Goo's.
 */
object PodbreznikParser : Parser {
    override val source = Source.PODJETNISKI_INKUBATOR_PODBREZNIK

    private val log = LoggerFactory.getLogger(javaClass)

    private const val DEPARTMENT = "oddelek-podjetnistvo"
    // Newest published first; a couple of entrepreneurship events a month, so this reaches back half a year or more
    private const val PER_PAGE = 20

    override fun accepts(field: SourceField, url: URI) = url.host.orEmpty().let { it == "rc-nm.si" || it == "www.rc-nm.si" }

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val department = context.web.json(URI("${url.at("/wp-json/wp/v2/oddelek")}?slug=$DEPARTMENT")).items()
            .firstNotNullOfOrNull { it.get("id")?.takeIf { id -> id.isIntegralNumber }?.longValue() }
            ?: throw IllegalStateException("No '$DEPARTMENT' department on $url")
        val posts = context.web.json(URI("${url.at("/wp-json/wp/v2/dogodek")}?oddelek=$department&per_page=$PER_PAGE&_fields=id%2Clink%2Ctitle")).items()
            .map(::post)
        val sourceUrl = url.at("/dogodki/").toString()
        return posts.mapNotNull { post ->
            try {
                event(sourceUrl, post, pageOf(context.web.get(URI(post.link))))
            } catch (e: Exception) {
                log.warn("Skipping RC Novo mesto event ${post.link}: ${e.message}")
                null
            }
        }
    }

    private fun event(sourceUrl: String, post: Post, page: Page): ScrapedEvent {
        val start = if (page.hasStartTime) page.start else page.start.toLocalDate().atStartOfDay()
        val startAt = at(start)
        val end = page.end?.let { end ->
            if (page.hasEndTime) {
                at(end).takeIf { it.isAfter(startAt) }
            } else if (end.toLocalDate().isAfter(start.toLocalDate())) {
                // All day, over several days: it ends on its last day
                at(end.toLocalDate().atStartOfDay())
            } else {
                null
            }
        }
        return ScrapedEvent(
            source = source,
            externalId = "rcnm-${post.id}",
            sourceUrl = sourceUrl,
            title = post.title,
            start = startAt,
            end = end,
            hasStartTime = page.hasStartTime,
            hasEndTime = page.hasEndTime && end != null,
            description = page.description,
            eventUrl = post.link,
            locationName = page.place,
            locationAddress = page.address,
        )
    }

    private data class Post(val id: Long, val link: String, val title: String)

    private fun post(node: JsonNode): Post {
        fun string(value: JsonNode?, name: String): String =
            value?.takeIf { it.isString }?.stringValue() ?: throw IllegalStateException("An event without its $name: $node")
        return Post(
            id = node.get("id")?.takeIf { it.isIntegralNumber }?.longValue() ?: throw IllegalStateException("An event without its id: $node"),
            link = string(node.get("link"), "link"),
            title = Jsoup.parse(string(node.get("title")?.get("rendered"), "title")).text().trim(),
        )
    }

    /** What an event's page says: when (Ljubljana's time), whether it has a start and end time, where, and its lede */
    data class Page(
        val start: LocalDateTime,
        val end: LocalDateTime?,
        val hasStartTime: Boolean,
        val hasEndTime: Boolean,
        val place: String?,
        val address: String?,
        val description: String?,
    )

    private val clockTime = Regex("""(\d{1,2})[:.](\d{2})""")

    private fun hasTime(document: Document, query: String) =
        document.selectFirst(query)?.let { clockTime.find(it.text()) != null } ?: false

    // "2026-11-25T16:00:00+00:00" is 2026-11-25T16:00, as the offset is wrong
    private fun localOf(raw: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(raw.take(19)) }.recoverCatching { LocalDate.parse(raw.take(10)).atStartOfDay() }.getOrNull()

    /** The schema.org `Event` among the page's JSON-LD blocks (Yoast puts it in an `@graph`) */
    fun eventIn(document: Document): JsonNode? =
        document.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { Web.MAPPER.readTree(it.data()) }.getOrNull() }
            .flatMap { json -> json.get("@graph")?.takeIf { it.isArray }?.items() ?: listOf(json) }
            .find { it.get("@type")?.takeIf { type -> type.isString }?.stringValue() == "Event" }

    private fun JsonNode.string(name: String): String? = get(name)?.takeIf { it.isString }?.stringValue()

    /** An event's page; it fails without a schema.org `Event` with its `startDate` */
    fun pageOf(html: String): Page {
        val document = Jsoup.parse(html)
        val event = eventIn(document) ?: throw IllegalArgumentException("No schema.org Event on the page")
        val start = event.string("startDate")?.let(::localOf) ?: throw IllegalArgumentException("No startDate")
        val online = event.string("eventAttendanceMode")?.contains("Online") == true
        val lines = document.selectFirst(".event-location")?.html()?.split(Regex("(?i)<br\\s*/?>")).orEmpty()
            .map { Jsoup.parse(it).text().replace(' ', ' ').trim() }
            .filter { it.isNotEmpty() }
        return Page(
            start = start,
            end = event.string("endDate")?.let(::localOf),
            hasStartTime = hasTime(document, ".single-event-meta-schedule-time-start"),
            hasEndTime = hasTime(document, ".single-event-meta-schedule-time-end"),
            place = lines.firstOrNull() ?: if (online) "Online" else null,
            address = lines.drop(1).joinToString(", ").ifEmpty { null },
            description = event.string("description")?.trim()?.ifEmpty { null },
        )
    }

    private fun at(local: LocalDateTime): Instant = local.toInstant(LJUBLJANA.rules.getOffset(local))
}

/** A JSON array's items; anything else isn't what the site answers */
private fun JsonNode.items(): List<JsonNode> =
    if (isArray) values().toList() else throw IllegalStateException("Not a JSON array: ${toString().take(200)}")
