package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.io.IOException
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LumaParserTest {
    private val calendar = "cal-n3XMAUsSIWCgyC9"
    private val items = "https://api.luma.com/calendar/get-items?calendar_api_id=$calendar"

    private fun entry(
        id: String, name: String, path: String, start: String, end: String,
        location: String = "offline", visibility: String = "public", geo: String = "null",
    ) = """{"api_id":"calev-$id","event":{"api_id":"evt-$id","name":"$name","url":"$path","start_at":"$start",
        "end_at":"$end","timezone":"Europe/Belgrade","location_type":"$location","visibility":"$visibility",
        "geo_address_info":$geo}}"""

    // The live SLO AI entry (trimmed), as /calendar/get-items returns it
    private val techSales = """{"api_id":"calev-F5B8Yzki8vBOqn3","event":{"api_id":"evt-wtCE4YZv1za4d5F",
        "name":"SLOAI Tech Sales #1: How Tech Sales Merged Five Roles Into One","url":"rfb719i8",
        "start_at":"2026-11-12T17:00:00.000Z","end_at":"2026-11-12T19:00:00.000Z","timezone":"Europe/Belgrade",
        "location_type":"offline","visibility":"public","geo_address_info":{"address":"Celtra, razvoj informacijskih tehnologij, d.o.o.",
        "full_address":"Celtra, razvoj informacijskih tehnologij, d.o.o., Park Zvezda, Kongresni trg 3, 1000 Ljubljana, Slovenia",
        "city":"Ljubljana"}}}"""
    private val online = entry("online1", "SLOAI Online: Agents", "agents1", "2026-12-03T17:00:00.000Z", "2026-12-03T18:30:00.000Z", location = "online")
    private val summer = entry("summer1", "SLOAI Summer Meetup", "summer1", "2027-06-10T16:00:00.000Z", "2027-06-10T18:00:00.000Z")
    private val private = entry("priv1", "Organizers only", "priv1", "2026-12-10T17:00:00.000Z", "2026-12-10T18:00:00.000Z", visibility = "private")
    private val broken = """{"api_id":"calev-x","event":{"name":"No dates"}}"""
    private val past = entry("past1", "SLOAI Meetup #12", "meetup12", "2026-09-17T16:00:00.000Z", "2026-09-17T18:00:00.000Z")

    private fun page(entries: List<String>, next: String?) =
        """{"entries":[${entries.joinToString(",")}],"has_more":${next != null},"next_cursor":${next?.let { "\"$it\"" } ?: "null"}}"""

    private val web = FakeWeb(
        mapOf(
            "https://api.luma.com/url?url=sloai" to """{"kind":"calendar","data":{"calendar":{"api_id":"$calendar"}}}""",
            "https://api.luma.com/url?url=rfb719i8" to """{"kind":"event","data":{"calendar":{"api_id":"$calendar"}}}""",
            "$items&period=future&pagination_limit=50" to page(listOf(techSales, online, private), "cursor-2"),
            "$items&period=future&pagination_limit=50&pagination_cursor=cursor-2" to page(listOf(summer, broken, techSales), null),
            "$items&period=past&pagination_limit=50" to page(listOf(past), null),
        )
    )

    private fun events(link: String) = LumaParser.parse(context(web), URI(link))

    @Test
    fun `finds a calendar by its slug, reads every page of future events and the recent past, public ones once`() {
        val events = events("https://luma.com/sloai")
        assertEquals(
            listOf("luma-evt-wtCE4YZv1za4d5F", "luma-evt-online1", "luma-evt-summer1", "luma-evt-past1"),
            events.map { it.externalId },
        )
        assertTrue(events.all { it.source == Source.LUMA })
    }

    @Test
    fun `an event's exact start and end, venue and address, and its luma page`() {
        val events = events("https://luma.com/sloai").associateBy { it.externalId }
        val event = events.getValue("luma-evt-wtCE4YZv1za4d5F")
        assertEquals("SLOAI Tech Sales #1: How Tech Sales Merged Five Roles Into One", event.title)
        assertEquals(Instant.parse("2026-11-12T17:00:00Z"), event.start)
        assertEquals(Instant.parse("2026-11-12T19:00:00Z"), event.end)
        assertTrue(event.hasStartTime && event.hasEndTime)
        assertEquals("Celtra, razvoj informacijskih tehnologij, d.o.o.", event.locationName)
        assertTrue(event.locationAddress!!.contains("Kongresni trg 3, 1000 Ljubljana"))
        assertEquals("https://luma.com/rfb719i8", event.eventUrl)
        assertEquals("https://luma.com/sloai", event.sourceUrl)

        val online = events.getValue("luma-evt-online1")
        assertEquals("Online", online.locationName)
        assertNull(online.locationAddress)
    }

    @Test
    fun `a calendar's id in the link needs no lookup`() {
        assertEquals(4, events("https://luma.com/calendar/$calendar").size)
        assertTrue(web.asked.none { it.contains("/url?") })
    }

    @Test
    fun `a link to an event, not a calendar, fails`() {
        assertFailsWith<IOException> { events("https://luma.com/rfb719i8") }
    }

    @Test
    fun `links and lookups`() {
        assertEquals(calendar, LumaParser.idIn(URI("https://luma.com/calendar/$calendar")))
        assertNull(LumaParser.idIn(URI("https://luma.com/sloai")))
        assertEquals("sloai", LumaParser.slugOf(URI("https://luma.com/sloai")))
        assertEquals("sloai", LumaParser.slugOf(URI("https://lu.ma/sloai/")))
        assertNull(LumaParser.slugOf(URI("https://luma.com/")))
        assertEquals(calendar, LumaParser.calendarOf(Web.MAPPER.readTree("""{"kind":"calendar","data":{"calendar":{"api_id":"$calendar"}}}""")))
        assertFailsWith<IOException> { LumaParser.calendarOf(Web.MAPPER.readTree("""{"kind":"event","data":{}}""")) }
    }

    @Test
    fun `takes luma's field only`() {
        assertTrue(LumaParser.accepts(SourceField.LUMA, URI("https://luma.com/sloai")))
        assertTrue(!LumaParser.accepts(SourceField.WEBSITE, URI("https://luma.com/sloai")))
    }
}
