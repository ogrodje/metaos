package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.net.URI
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime

/**
 * DataScience@UL-FRI (https://datascience.fri.uni-lj.si/events/), FRI's data science initiative: its events page draws
 * its calendar from the JSON in `script#events-data`, an item a post, from a year ago on. News, competitions and
 * counselling slots aren't events. The end time and the place come from the excerpt's "When?" and "Where?" lines, else
 * a meetup's `meta` is its place. Its ID is the post's slug, datascience-<slug>. FRI's calendar repeats some of these,
 * which [FRIParser] leaves out ([sameEvent]).
 */
object DataScienceParser : Parser {
    override val source = Source.DATA_SCIENCE

    /** Where DataScience@UL-FRI lists its events; [FRIParser] reads it too */
    val homepage = URI("https://datascience.fri.uni-lj.si")

    private val notEvents = setOf("news", "competition", "counseling", "counselling")
    private const val MONTHS_BACK = 12L

    override fun accepts(field: SourceField, url: URI) = (url.host ?: "").lowercase() == "datascience.fri.uni-lj.si"

    override fun parse(context: ScrapeContext, url: URI) = parse(context, url, LocalDate.now(LJUBLJANA))

    internal fun parse(context: ScrapeContext, url: URI, today: LocalDate): List<ScrapedEvent> =
        listed(context.web, url, today).map { event(url, it) }

    private fun event(base: URI, item: Item): ScrapedEvent {
        val whenTimes = timesOf(item.whenLine)
        val start = item.time?.let(::clock) ?: whenTimes?.first
        val end = whenTimes?.takeIf { it.first == start }?.second
        val startAt = at(item.date, start ?: LocalTime.MIDNIGHT)
        val endAt = end?.let { at(item.date, it) }?.takeIf { it.isAfter(startAt) }
        return ScrapedEvent(
            source = source,
            externalId = "datascience-${item.slug}",
            sourceUrl = base.at("/events/").toString(),
            title = item.title,
            start = startAt,
            end = endAt,
            hasStartTime = start != null,
            hasEndTime = endAt != null,
            description = item.description,
            eventUrl = base.resolve(item.path).toString(),
            locationName = item.location.name,
            locationAddress = item.location.address,
        )
    }

    private val sentence = Regex("""\.\s+(?=\p{Lu})""")

    internal data class Item(
        val path: String,
        val date: LocalDate,
        val kind: String,
        val title: String,
        val time: String?,
        val meta: String?,
        val excerpt: String?,
    ) {
        val slug: String
            get() = (path.split('/').lastOrNull { it.isNotEmpty() } ?: path).replace(Regex("[^A-Za-z0-9_-]"), "-")

        private val lines: List<String>
            get() = excerpt?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        private fun line(label: String) =
            lines.firstOrNull { it.startsWith(label) }?.removePrefix(label)?.trim()?.takeIf { it.isNotEmpty() }

        val whenLine: String? get() = line("When?")

        /**
         * "Where? Faculty of Computer and Information Science, Večna pot 113, Ljubljana. Lecture room 3.", its room
         * with the place; else the `meta` after its time ("16:00 · FRI, lecture room 03"), or a meetup's, which is
         * its place ("FRI, Večna pot 113, Ljubljana"), where a workshop's is its speakers
         */
        val location: Location
            get() {
                val where = line("Where?")?.split(sentence)?.joinToString(", ") { it.trim().removeSuffix(".") }
                return Locations.parse(
                    where
                        ?: meta?.takeIf { it.contains(" · ") }?.split(" · ", limit = 2)?.get(1)
                        ?: meta?.takeIf { kind == "meetup" },
                )
            }

        /** The excerpt's "What?" part, else the excerpt itself when it has no When/Where */
        val description: String?
            get() {
                val what = lines.dropWhile { it != "What?" }.drop(1)
                val text = when {
                    what.isNotEmpty() -> what.joinToString("\n")
                    whenLine == null && line("Where?") == null -> lines.joinToString("\n")
                    else -> ""
                }
                return text.trim().takeIf { it.isNotEmpty() }
            }
    }

    /** The items of the events page's JSON; those that don't read are left out */
    internal fun itemsIn(html: String): List<Item> {
        val script = Jsoup.parse(html).selectFirst("script#events-data")
            ?: throw IllegalStateException("No events-data on the DataScience events page")
        val json = Web.MAPPER.readTree(script.data())
        require(json.isArray) { "DataScience's events-data isn't a list" }
        return json.values().mapNotNull(::item)
    }

    private fun item(node: JsonNode): Item? = runCatching {
        fun optional(name: String): String? = node.get(name)?.takeUnless { it.isNull }?.let {
            check(it.isString) { "$name isn't text" }
            it.asString()
        }
        fun required(name: String) = checkNotNull(optional(name)) { "No $name" }
        fun nonEmpty(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
        Item(
            path = required("url"),
            date = LocalDate.parse(required("iso")),
            kind = optional("kind")?.trim()?.lowercase() ?: "",
            title = required("title").trim(),
            time = nonEmpty(optional("time")),
            meta = nonEmpty(optional("meta")),
            excerpt = nonEmpty(optional("excerpt")),
        )
    }.getOrNull()

    /** The events DataScience lists, from [MONTHS_BACK] months ago on */
    internal fun listed(web: Web, url: URI, today: LocalDate): List<Item> =
        itemsIn(web.get(url.at("/events/")))
            .filterNot { it.kind in notEvents }
            .filterNot { it.date.isBefore(today.minusMonths(MONTHS_BACK)) }

    private fun at(date: LocalDate, time: LocalTime) = date.atTime(time).atZone(LJUBLJANA).toInstant()

    private val clockTime = Regex("""^(\d{1,2})[:.](\d{2})$""")

    private fun clock(raw: String): LocalTime? = clockTime.find(raw.trim())?.let { time(it.groupValues[1], it.groupValues[2]) }

    private fun time(hour: String, minute: String) = runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull()

    private val range = Regex("""(?<![\d.])(\d{1,2})[:.](\d{2})(?:\s*[–—-]\s*(\d{1,2})[:.](\d{2}))?""")

    /** "Thursday, 26 November 2026, 17:00–20:00." is 17:00 to 20:00; "… at 19:00." 19:00 */
    internal fun timesOf(whenLine: String?): Pair<LocalTime, LocalTime?>? {
        val m = range.find(whenLine ?: return null) ?: return null
        val start = time(m.groupValues[1], m.groupValues[2]) ?: return null
        val end = m.groups[3]?.let { time(it.value, m.groupValues[4]) }
        return start to end
    }

    private val noise = setOf(
        "data", "science", "datascience", "ul", "fri", "the", "and", "with", "for", "by", "in", "of", "on", "na", "za",
        "pri", "workshop", "delavnica",
    )

    private fun words(title: String): Set<String> =
        Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .split(Regex("[^a-z]+"))
            .filter { it.length >= 2 && it !in noise }
            .toSet()

    /**
     * Whether an event on FRI's calendar is one DataScience lists: on the same day, and at least half the words of the
     * shorter title (but those every title may have) in the other, e.g. "Data Science: Spark Sessions" and "Spark
     * sessions 007"
     */
    internal fun sameEvent(date: LocalDate, title: String, item: Item): Boolean {
        if (date != item.date) return false
        val a = words(title)
        val b = words(item.title)
        val shared = (a intersect b).size
        return shared > 0 && shared * 2 >= minOf(a.size, b.size)
    }
}
