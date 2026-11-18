package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Talks of Spletne urice Maribor (#wwwhmb, https://wwwhmb.si/predavanja/): the newest 20 posts of its WordPress
 * "predavanja" category, through the REST API. A post's date is when it was published; the talk's own date, time and
 * place open its content, in one of two forms:
 *  - since 2026: `1. december 2026 ob 19:05 // BarCoda, Vetrinjc`, with the year;
 *  - before: `torek, 3. marec @ 19:05 // Vetrinjc – BarCoda, Mb //`, without: the first such date on or after the
 *    post was published.
 *
 * Its ID is the post's, as Goo's.
 */
object SpletneUriceParser : Parser {
    override val source = Source.SPLETNE_URICE

    private val log = LoggerFactory.getLogger(javaClass)

    private const val TALKS_CATEGORY = 2
    // One talk a month, so these reach back well over a year
    private const val PER_PAGE = 20

    private val months = listOf(
        "januar", "februar", "marec", "april", "maj", "junij",
        "julij", "avgust", "september", "oktober", "november", "december",
    ).withIndex().associate { (i, name) -> name to i + 1 }

    // "1. december 2026 ob 19:05", "torek, 3. marec @ 19:05", "11. JANUAR @ 19:05"
    private val dateLine = Regex(
        """(?iu)(\d{1,2})\.\s*(januar|februar|marec|april|maj|junij|julij|avgust|september|oktober|november|december)\s*(\d{4})?\s*(?:ob|@)\s*(\d{1,2})[:.](\d{2})"""
    )

    // Some old posts were later given a copied subtitle ("3. marec 2026" on a talk published in 2022): a date with a
    // year has to fall within this window around the publish date to be believed
    private const val EARLIEST_BEFORE_PUBLISH = 60L
    private const val LATEST_AFTER_PUBLISH = 365L

    override fun accepts(field: SourceField, url: URI) = url.host.orEmpty().contains("wwwhmb.si")

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val listing = URI("${url.at("/wp-json/wp/v2/posts")}?categories=$TALKS_CATEGORY&per_page=$PER_PAGE&_fields=id%2Cdate%2Clink%2Ctitle%2Ccontent")
        val sourceUrl = url.at("/predavanja/").toString()
        return context.web.json(listing).items().map(::post).mapNotNull { post ->
            val talk = talkFrom(post.contentHtml, post.published)
            if (talk == null) log.warn("Skipping Spletne urice post ${post.link}: No talk date found")
            talk?.let { event(sourceUrl, post, it) }
        }
    }

    private fun event(sourceUrl: String, post: Post, talk: Talk) = ScrapedEvent(
        source = source,
        externalId = "spletne-urice-${post.id}",
        sourceUrl = sourceUrl,
        title = post.title,
        start = talk.start.toInstant(LJUBLJANA.rules.getOffset(talk.start)),
        end = null,
        hasStartTime = true,
        hasEndTime = false,
        description = talk.description,
        eventUrl = post.link,
        locationName = talk.location.name,
        locationAddress = talk.location.address,
    )

    data class Talk(val start: LocalDateTime, val location: Location, val description: String?)

    /** The talk's start, place and lede from a post's content HTML, given when the post was published */
    fun talkFrom(contentHtml: String, published: LocalDateTime): Talk? {
        val texts = Jsoup.parseBodyFragment(contentHtml).select("h1, h2, h3, h4, p").map { it.text().replace(' ', ' ').trim() }
        val (i, text, m) = texts.withIndex().firstNotNullOfOrNull { (i, text) -> dateLine.find(text)?.let { Triple(i, text, it) } }
            ?: return null
        val month = months[m.groupValues[2].lowercase()] ?: return null
        val day = m.groupValues[1].toInt()
        val time = try {
            LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt())
        } catch (_: DateTimeException) {
            return null
        }
        val year = m.groups[3]?.value
        val date = if (year != null) {
            dateOf(year.toInt(), month, day)?.takeIf { believable(it, published) }
        } else {
            inferYear(day, month, published)
        } ?: return null
        // "date // place //": the place is the segment after the date
        val location = Locations.parse(text.split("//").getOrNull(1))
        // The first real paragraph after it: words, not the "+ + + +" divider of the old posts
        val description = texts.drop(i + 1).find { t -> t.count { it.isLetter() } >= 40 && dateLine.find(t) == null }
        return Talk(date.atTime(time), location, description)
    }

    private fun dateOf(year: Int, month: Int, day: Int) = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun believable(date: LocalDate, published: LocalDateTime): Boolean {
        val publishedOn = published.toLocalDate()
        return !date.isBefore(publishedOn.minusDays(EARLIEST_BEFORE_PUBLISH)) && !date.isAfter(publishedOn.plusDays(LATEST_AFTER_PUBLISH))
    }

    // Without a year: the first such date that is not well before the post was published
    private fun inferYear(day: Int, month: Int, published: LocalDateTime): LocalDate? {
        val publishedOn = published.toLocalDate()
        return listOf(publishedOn.year, publishedOn.year + 1)
            .mapNotNull { dateOf(it, month, day) }
            .find { !it.isBefore(publishedOn.minusDays(30)) }
    }

    private data class Post(val id: Long, val published: LocalDateTime, val link: String, val title: String, val contentHtml: String)

    private fun post(node: JsonNode): Post {
        fun string(value: JsonNode?, name: String): String =
            value?.takeIf { it.isString }?.stringValue() ?: throw IllegalStateException("A post without its $name: $node")
        return Post(
            id = node.get("id")?.takeIf { it.isIntegralNumber }?.longValue() ?: throw IllegalStateException("A post without its id: $node"),
            published = LocalDateTime.parse(string(node.get("date"), "date")),
            link = string(node.get("link"), "link"),
            title = Jsoup.parse(string(node.get("title")?.get("rendered"), "title")).text().trim(),
            contentHtml = string(node.get("content")?.get("rendered"), "content"),
        )
    }
}

/** A JSON array's items; anything else isn't what the site answers */
private fun JsonNode.items(): List<JsonNode> =
    if (isArray) values().toList() else throw IllegalStateException("Not a JSON array: ${toString().take(200)}")
