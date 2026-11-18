package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

/**
 * A Luma calendar, from an organisation's Luma link: https://luma.com/sloai (its slug) or
 * https://luma.com/calendar/cal-… (its ID). It reads Luma's API, the one its own pages use: `/url?url=<slug>` says
 * which calendar a slug is, and `/calendar/get-items` lists its events, future then past, in pages. Only public
 * events are kept, each at its page, https://luma.com/<url>.
 */
object LumaParser : Parser {
    override val source = Source.LUMA

    private val api = URI("https://api.luma.com")
    private const val SITE = "https://luma.com"
    private const val PAGE_SIZE = 50
    private val calendarId = Regex("^cal-[A-Za-z0-9]+$")
    private val onlineTypes = setOf("online", "zoom", "virtual")

    override fun accepts(field: SourceField, url: URI) = field == SourceField.LUMA

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> {
        val calendar = idIn(url) ?: run {
            val slug = slugOf(url) ?: throw IOException("No Luma calendar in $url")
            calendarOf(context.web.json(api("/url", "url" to slug)))
        }
        // Recent past events too, as other sources keep them; one page is plenty for a meetup
        val entries = items(context.web, calendar, "future", maxPages = 4) + items(context.web, calendar, "past", maxPages = 1)
        return entries.mapNotNull { runCatching { item(it) }.getOrNull() }
            .filter { it.public }
            .distinctBy { it.id }
            .map { event(url, it) }
    }

    /** The calendar's ID where the link has it, e.g. https://luma.com/calendar/cal-… */
    fun idIn(url: URI): String? = segments(url).firstOrNull { calendarId.matches(it) }

    /** The calendar's slug, e.g. sloai for https://luma.com/sloai */
    fun slugOf(url: URI): String? = segments(url).singleOrNull()

    /** The calendar's ID in Luma's answer about a link, which fails for a link to anything but a calendar */
    fun calendarOf(json: JsonNode): String {
        val kind = json.text("kind") ?: throw IOException("Not a Luma link")
        if (kind != "calendar") throw IOException("The Luma link is a(n) $kind, not a calendar")
        return json.path("data").path("calendar").text("api_id") ?: throw IOException("No calendar id")
    }

    private fun segments(url: URI) = url.path.orEmpty().split("/").filter(String::isNotEmpty)

    private fun items(web: Web, calendar: String, period: String, maxPages: Int): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        var cursor: String? = null
        for (page in 1..maxPages) {
            val params = listOf("calendar_api_id" to calendar, "period" to period, "pagination_limit" to "$PAGE_SIZE") +
                listOfNotNull(cursor?.let { "pagination_cursor" to it })
            val json = web.json(api("/calendar/get-items", *params.toTypedArray()))
            val entries = json.get("entries")?.takeIf { it.isArray } ?: throw IOException("Luma's $calendar has no entries")
            all += entries.values()
            cursor = json.text("next_cursor")
            if (!json.path("has_more").booleanValue(false) || cursor == null) break
        }
        return all
    }

    private fun api(path: String, vararg params: Pair<String, String>) =
        URI("$api$path?" + params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" })

    private class Item(
        val id: String, val name: String, val path: String, val start: Instant, val end: Instant?,
        val public: Boolean, val place: String?, val address: String?,
    )

    private fun item(entry: JsonNode): Item {
        val event = entry.path("event")
        val geo = event.path("geo_address_info")
        val venue = geo.text("address")?.trim()?.takeIf(String::isNotEmpty)
        val full = geo.text("full_address")?.trim()?.takeIf(String::isNotEmpty)
        val online = event.text("location_type") in onlineTypes
        return Item(
            id = requireNotNull(event.text("api_id")),
            name = requireNotNull(event.text("name")).trim(),
            path = requireNotNull(event.text("url")),
            start = OffsetDateTime.parse(requireNotNull(event.text("start_at"))).toInstant(),
            end = event.text("end_at")?.let { OffsetDateTime.parse(it).toInstant() },
            public = event.text("visibility").let { it == null || it == "public" },
            place = venue ?: "Online".takeIf { online },
            address = full?.takeIf { it != venue },
        )
    }

    private fun event(url: URI, item: Item): ScrapedEvent {
        val end = item.end?.takeIf { it > item.start }
        return ScrapedEvent(
            source = source,
            externalId = "luma-${item.id}",
            sourceUrl = url.toString(),
            title = item.name,
            start = item.start,
            end = end,
            hasStartTime = true,
            hasEndTime = end != null,
            eventUrl = "$SITE/${item.path}",
            locationName = item.place,
            locationAddress = item.address,
        )
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.stringValue()
}
