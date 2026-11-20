package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime

/**
 * Inkubator Savinjske regije (https://www.inkubatorsr.si/aktualno/dogodki/): WordPress, whose REST API lists its
 * `dogodki` posts (`/wp-json/wp/v2/dogodki`), the newest published 40. The post's date is when it was published, so
 * each event's day and place are read from its page's Elementor fields (`.elementor-widget.date`: the day right before
 * "Lokacija dogodka", then the place), and its times from the post, after a marker such as 🕘, "ob" or "od", if any.
 * A page that doesn't read is left out. Its ID is the post's, inkubatorsr-<id>.
 */
object InkubatorSavinjskeRegijeParser : Parser {
    override val source = Source.INKUBATOR_SAVINJSKE_REGIJE

    private val log = LoggerFactory.getLogger(javaClass)

    private const val PER_PAGE = 20
    // Older posts describe events that have already passed.
    private const val MAX_PAGES = 2
    private const val LOCATION_LABEL = "Lokacija dogodka"

    private val date = Regex("""(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})""")

    // Only after a marker the site writes a time with, so that a date such as "16.03.2026" isn't one
    private val time = Regex(
        """(?iu)(?:[\x{1F550}-\x{1F567}\x{23F0}]\x{FE0F}?|\bob|\bod|\bura:|\btime:|\bat|\()\s*(\d{1,2})[.:](\d{2})(?!\d|\.\d)""" +
            """(?:\s*(?:h|uri?)?\s*(?:[–—-]|do)\s*(\d{1,2})[.:](\d{2})(?!\d|\.\d))?""",
    )

    internal data class Post(val id: Long, val link: URI, val title: String, val contentText: String)

    override fun accepts(field: SourceField, url: URI) = (url.host ?: "").lowercase().contains("inkubatorsr.si")

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val sourceUrl = url.at("/aktualno/dogodki/")
        return posts(context.web, url).mapNotNull { post ->
            try {
                event(context, sourceUrl, post)
            } catch (e: Exception) {
                log.warn("Failed to parse ISR event {}: {}", post.link, e.message)
                null
            }
        }
    }

    private fun listingUrl(url: URI, page: Int) =
        URI("${url.at("/wp-json/wp/v2/dogodki")}?per_page=$PER_PAGE&page=$page&_fields=id,link,title,content")

    private fun posts(web: Web, url: URI): List<Post> =
        wordPressPages(MAX_PAGES, PER_PAGE) { page ->
            val listing = listingUrl(url, page)
            val json = web.json(listing)
            require(json.isArray) { "$listing isn't a list of posts" }
            json.values().map(::post)
        }

    private fun post(node: JsonNode): Post {
        fun text(vararg path: String) = path.fold(node as JsonNode?) { n, name -> n?.get(name) }
            ?.takeIf { it.isString }?.asString() ?: throw IllegalArgumentException("No ${path.joinToString(".")} in a post")
        val id = node.get("id")?.takeIf { it.isIntegralNumber }?.asLong() ?: throw IllegalArgumentException("No id in a post")
        return Post(
            id = id,
            link = URI(text("link")),
            title = Jsoup.parse(text("title", "rendered")).text().trim(),
            contentText = Jsoup.parse(text("content", "rendered")).text().replace(' ', ' '),
        )
    }

    private fun event(context: ScrapeContext, sourceUrl: URI, post: Post): ScrapedEvent {
        val page = context.web.document(post.link)
        val (day, location) = fields(page)
        val times = timesFrom(post.contentText)
        val place = Locations.parse(location)
        return ScrapedEvent(
            source = source,
            externalId = "inkubatorsr-${post.id}",
            sourceUrl = sourceUrl.toString(),
            title = post.title,
            start = at(day, times?.first ?: LocalTime.MIDNIGHT),
            end = times?.second?.let { at(day, it) },
            hasStartTime = times != null,
            hasEndTime = times?.second != null,
            description = page.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() },
            eventUrl = post.link.toString(),
            locationName = place.name,
            locationAddress = place.address,
        )
    }

    /**
     * Elementor renders each field of the event as a `.date` widget, in order: ..., the day, "Lokacija dogodka", the
     * place. The menu shows the newest event's day the same way on every page, so the day is the one before the label.
     */
    private fun fields(page: Document): Pair<LocalDate, String?> {
        val widgets = page.select(".elementor-widget.date").map { it.text().replace(' ', ' ').trim() }
        val labelAt = widgets.indexOfFirst { it.equals(LOCATION_LABEL, ignoreCase = true) }
        check(labelAt >= 0) { "No '$LOCATION_LABEL' field found" }
        val raw = widgets.take(labelAt).lastOrNull { date.matches(it) } ?: throw IllegalStateException("No event date found")
        val (d, m, y) = date.matchEntire(raw)!!.destructured
        return LocalDate.of(y.toInt(), m.toInt(), d.toInt()) to widgets.getOrNull(labelAt + 1)?.takeIf { it.isNotEmpty() }
    }

    private fun at(day: LocalDate, time: LocalTime) = day.atTime(time).atZone(LJUBLJANA).toInstant()

    private fun localTime(hour: String, minute: String): LocalTime? {
        val h = hour.toIntOrNull() ?: return null
        val m = minute.toIntOrNull() ?: return null
        return if (h < 24 && m < 60) LocalTime.of(h, m) else null
    }

    /** The start and, if it says, the end time, from the first marked time in a post */
    internal fun timesFrom(text: String): Pair<LocalTime, LocalTime?>? =
        time.findAll(text).firstNotNullOfOrNull { m ->
            localTime(m.groupValues[1], m.groupValues[2])?.let { start ->
                start to m.groups[3]?.let { localTime(it.value, m.groupValues[4]) }
            }
        }
}
