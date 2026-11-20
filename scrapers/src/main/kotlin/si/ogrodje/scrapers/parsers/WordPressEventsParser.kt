package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A WordPress site that announces events as ordinary posts in an events category ("dogodki"), read through its REST
 * API: the newest [maxPages] pages of the category, 20 posts a page. A post has no event fields, so when and where
 * come from its text ([EventText]; its place, "KJE: LUI, Vojkova cesta 63, Ljubljana", is a line that [Locations]
 * splits); a post that names no date (news, results, calls) is skipped. Of posts about the
 * same event (a changed date, a reminder), one a day and title stays: the newest. Its ID is [idPrefix] and the post's
 * ID, as Goo's.
 *
 * Goo read the total pages from `X-WP-TotalPages`; [Web] gives no headers, so the next page is read when this one was
 * full, and WordPress's 400 for a page past the last ends it.
 */
class WordPressEventsParser(
    override val source: Source,
    private val idPrefix: String,
    private val listingPath: String,
    private val hosts: (String) -> Boolean,
    private val category: String = "dogodki",
    // Read the event from a sentence when no line is marked ("KDAJ:", "📅", …)
    private val sentences: Boolean = false,
    // Leave out events that started more than this long ago (some categories go back years)
    private val monthsBack: Long? = null,
    private val maxPages: Int = 2,
    private val today: () -> LocalDate = { LocalDate.now(LJUBLJANA) },
) : Parser {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun accepts(field: SourceField, url: URI) = hosts(url.host.orEmpty())

    /** The same site, as on [day] */
    fun asOf(day: LocalDate) =
        WordPressEventsParser(source, idPrefix, listingPath, hosts, category, sentences, monthsBack, maxPages) { day }

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val category = categoryId(context.web, url)
        val sourceUrl = url.at(listingPath).toString()
        val events = posts(context.web, url, category).mapNotNull { post ->
            val event = event(sourceUrl, post)
            if (event == null) log.debug("$source post without an event date, skipped: ${post.link}")
            event?.let { post to it }
        }
        val since = monthsBack?.let { today().minusMonths(it) }
        val recent = events.filter { (_, event) -> since == null || !event.start.atZone(LJUBLJANA).toLocalDate().isBefore(since) }
        return onePerEvent(recent)
    }

    private fun categoryId(web: Web, url: URI): Long =
        web.json(api(url, "/wp-json/wp/v2/categories", "slug" to category)).items().firstOrNull()?.get("id")?.longValue()
            ?: throw IllegalStateException("No '$category' category on $url")

    private fun posts(web: Web, url: URI, category: Long): List<Post> =
        wordPressPages(maxPages, PER_PAGE) { page ->
            val listing = api(
                url, "/wp-json/wp/v2/posts", "categories" to "$category", "per_page" to "$PER_PAGE",
                "page" to "$page", "_fields" to "id,date,link,title,content,yoast_head_json.description",
            )
            web.json(listing).items().map(::post)
        }

    private fun event(sourceUrl: String, post: Post): ScrapedEvent? {
        val details = EventText.details(EventText.linesOf(post.contentHtml), post.published, sentences) ?: return null
        val start = at(details.startDate, details.startTime ?: LocalTime.MIDNIGHT)
        val location = Locations.parse(details.location)
        // A time range on one day gives the end time; a second day gives the end date
        val end = if (details.endDate != null) {
            at(details.endDate, LocalTime.MIDNIGHT)
        } else if (details.startTime != null && details.endTime != null) {
            at(details.startDate, details.endTime).takeIf { it.isAfter(start) }
        } else {
            null
        }
        return ScrapedEvent(
            source = source,
            externalId = "$idPrefix-${post.id}",
            sourceUrl = sourceUrl,
            title = post.title,
            start = start,
            end = end,
            hasStartTime = details.startTime != null,
            hasEndTime = details.endDate == null && end != null,
            description = post.description,
            eventUrl = post.link,
            locationName = location.name,
            locationAddress = location.address,
        )
    }

    /** One event a day and title: of posts about the same event, the newest (by post ID) stays */
    private fun onePerEvent(events: List<Pair<Post, ScrapedEvent>>): List<ScrapedEvent> =
        events.filterNot { (post, event) ->
            events.any { (other, e) ->
                other.id > post.id && day(e) == day(event) && EventText.similarTitles(e.title, event.title)
            }
        }.map { it.second }

    private fun day(event: ScrapedEvent) = event.start.atZone(LJUBLJANA).toLocalDate()

    private data class Post(
        val id: Long,
        val published: LocalDate,
        val link: String,
        val title: String,
        val contentHtml: String,
        val description: String?,
    )

    private fun post(node: JsonNode): Post {
        fun string(value: JsonNode?, name: String): String =
            value?.takeIf { it.isString }?.stringValue() ?: throw IllegalStateException("A post without its $name: $node")
        val id = node.get("id")?.takeIf { it.isIntegralNumber }?.longValue()
            ?: throw IllegalStateException("A post without its id: $node")
        val description = node.get("yoast_head_json")?.get("description")?.takeIf { it.isString }?.stringValue()
        return Post(
            id = id,
            published = LocalDate.parse(string(node.get("date"), "date").take(10)),
            link = string(node.get("link"), "link"),
            title = Jsoup.parse(string(node.get("title")?.get("rendered"), "title")).text().trim(),
            contentHtml = string(node.get("content")?.get("rendered"), "content"),
            description = description?.trim()?.ifEmpty { null },
        )
    }

    companion object {
        private const val PER_PAGE = 20

        /** Ljubljanski univerzitetni inkubator (https://lui.si/aktualno/), Goo's LUIParser */
        val LUI = WordPressEventsParser(
            Source.LUI, "lui", "/aktualno/",
            hosts = { it == "lui.si" || it.endsWith(".lui.si") },
        )

        /** Pomurski tehnološki park (https://www.p-tech.si/kategorija/dogodki/) */
        val PomurskiTehnoloskiPark = WordPressEventsParser(
            Source.POMURSKI_TEHNOLOSKI_PARK, "ptech", "/kategorija/dogodki/",
            hosts = { it == "p-tech.si" || it == "www.p-tech.si" }, sentences = true, monthsBack = 12,
        )

        /** Podjetniški inkubator Bela krajina (https://inkubator-belakrajina.si/category/dogodki/) */
        val InkubatorBelaKrajina = WordPressEventsParser(
            Source.INKUBATOR_BELA_KRAJINA, "belakrajina", "/category/dogodki/",
            hosts = { it == "inkubator-belakrajina.si" || it == "www.inkubator-belakrajina.si" }, monthsBack = 12,
        )

        /** Podjetniški inkubator Rogaška Slatina (https://www.inkubator-rs.si/category/dogodki/) */
        val InkubatorRogaskaSlatina = WordPressEventsParser(
            Source.INKUBATOR_ROGASKA_SLATINA, "inkubatorrs", "/category/dogodki/",
            hosts = { it == "inkubator-rs.si" || it == "www.inkubator-rs.si" }, sentences = true, monthsBack = 12,
        )

        private fun api(url: URI, path: String, vararg query: Pair<String, String>): URI =
            URI(url.at(path).toString() + "?" + query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" })

        private fun at(date: LocalDate, time: LocalTime): Instant {
            val local = LocalDateTime.of(date, time)
            return local.toInstant(LJUBLJANA.rules.getOffset(local))
        }
    }
}

/** A JSON array's items; anything else isn't what the site answers */
private fun JsonNode.items(): List<JsonNode> =
    if (isArray) values().toList() else throw IllegalStateException("Not a JSON array: ${toString().take(200)}")
