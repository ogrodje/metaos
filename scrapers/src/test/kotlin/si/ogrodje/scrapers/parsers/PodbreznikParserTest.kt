package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodbreznikParserTest {

    private val site = "https://www.rc-nm.si"

    // The sidebar and schema.org Event of real event pages (trimmed: no image, organizer, header or footer)
    private fun page(event: String, sidebar: String) = """<!DOCTYPE html><html><head>
        <script type="application/ld+json" class="yoast-schema-graph">{"@context":"https://schema.org","@graph":[
        {"@type":"WebPage","@id":"$site/dogodki/x/","datePublished":"2026-11-05T14:17:56+00:00"},$event]}</script>
        </head><body><article>…</article><div class="sidebar">$sidebar</div></body></html>"""

    private fun location(place: String, street: String) = """<div class="sidebar-widget-wrap">
        <h3 class="h5 sidebar-widget-title"><span class="icon icon-location"></span> Lokacija</h3>
        <div class="event-location">
        $place<br/>$street<br />			8000 Novo mesto<br />
        </div>
        </div>"""

    private val bilanca = page(
        """{"@type":"Event","name":"Kaj nam pove bilanca?","description":"Ali veste, kaj se v resnici skriva za podatki v bilanci stanja in izkazu poslovnega izida?","startDate":"2026-11-18T13:00:00+00:00","endDate":"2026-11-18T16:00:00+00:00","eventAttendanceMode":"https://schema.org/OfflineEventAttendanceMode","location":{"@type":"Place","address":{"@type":"PostalAddress","streetAddress":"Podbreznik 15","addressLocality":"Novo mesto","postalCode":"8000"}}}""",
        """<div class="sidebar-widget-wrap">
        <h3 class="h5 sidebar-widget-title"><span class="icon icon-calendar"></span> Datum</h3>
        <div class="single-event-meta-schedule">
        <div class="single-event-meta-schedule-date-start"><span class="event-date">18. novembra 2026</span></div>
        <div class="single-event-meta-schedule-time">
        od <span class="single-event-meta-schedule-time-start">13:00</span> do <span class="single-event-meta-schedule-time-end">16:00</span>
        </div></div></div>""" + location("Podjetniški inkubator Podbreznik", "Podbreznik 15"),
    )

    private val gladiator = page(
        """{"@type":"Event","name":"Finale Business Gladiator 2026","description":"Pridružite se zaključnemu dogodku podjetniškega programa Business Gladiator 2026.","startDate":"2026-11-26T18:00:00+00:00","eventAttendanceMode":"https://schema.org/OfflineEventAttendanceMode"}""",
        """<div class="sidebar-widget-wrap">
        <div class="single-event-meta-schedule">
        <div class="single-event-meta-schedule-date-start"><span class="event-date">26. novembra 2026</span></div>
        <div class="single-event-meta-schedule-time">ob <span class="single-event-meta-schedule-time-start">18:00</span></div>
        </div></div>""" + location("Knjižnica Mirana Jarca Novo mesto", "Rozmanova ulica 28"),
    )

    private val kickUp = page(
        """{"@type":"Event","name":"kickUP Your STARTUP 26","description":"kickUP Your STARTUP 26 je tridnevni inkubacijski program za startup podjetja.","startDate":"2026-10-19T11:00:00+00:00","endDate":"2026-10-21T18:00:00+00:00","eventAttendanceMode":"https://schema.org/OfflineEventAttendanceMode"}""",
        """<div class="sidebar-widget-wrap">
        <div class="single-event-meta-schedule">
        <div class="single-event-meta-schedule-date-start">
        <span class="event-date">od 19. oktobra 2026</span><span class="single-event-meta-schedule-time-start"> ob 11:00</span>
        </div>
        <div class="single-event-meta-schedule-date-end">
        <span class="event-date">do 21. oktobra 2026</span><span class="single-event-meta-schedule-time-end"> ob 18:00</span>
        </div></div></div>""" + location("Podjetniški inkubator Podbreznik", "Podbreznik 15"),
    )

    private val online = page(
        """{"@type":"Event","name":"Američani zaupajo našim kadrom, mi pa še vedno dvomimo vase?","startDate":"2026-08-27T16:15:00+00:00","endDate":"2026-08-27T17:45:00+00:00","eventAttendanceMode":"https://schema.org/OnlineEventAttendanceMode","location":{"@type":"VirtualLocation","url":"https://www.rc-nm.si/dogodki/americani/"}}""",
        """<div class="sidebar-widget-wrap"><div class="single-event-meta-schedule">
        <div class="single-event-meta-schedule-date-start"><span class="event-date">27. avgusta 2026</span></div>
        <div class="single-event-meta-schedule-time">
        od <span class="single-event-meta-schedule-time-start">16:15</span> do <span class="single-event-meta-schedule-time-end">17:45</span>
        </div></div></div>""",
    )

    // No time on the page: "00:00" in the Event, nothing in the sidebar's time spans (as on "Evropski dan jezikov")
    private val allDay = page(
        """{"@type":"Event","name":"Dan odprtih vrat","startDate":"2026-12-02T00:00:00+00:00","endDate":"2026-12-03T00:00:00+00:00","eventAttendanceMode":"https://schema.org/OfflineEventAttendanceMode"}""",
        """<div class="sidebar-widget-wrap"><div class="single-event-meta-schedule">
        <div class="single-event-meta-schedule-date-start"><span class="event-date">od 2. decembra 2026</span></div>
        <div class="single-event-meta-schedule-date-end"><span class="event-date">do 3. decembra 2026</span></div>
        </div></div>""",
    )

    private val pages = mapOf(
        "kaj-nam-pove-bilanca" to bilanca,
        "finale-projekta-business-gladiator-2026" to gladiator,
        "kickup-your-startup-26" to kickUp,
        "americani-zaupajo" to online,
        "dan-odprtih-vrat" to allDay,
        "brez-podatkov" to "<html><body><h1>Brez podatkov</h1></body></html>",
    )

    private val posts = listOf(
        4174L to ("kaj-nam-pove-bilanca" to "Kaj nam pove bilanca?"),
        4123L to ("finale-projekta-business-gladiator-2026" to "Finale Business Gladiator 2026"),
        4037L to ("kickup-your-startup-26" to "kickUP Your STARTUP 26"),
        3687L to ("americani-zaupajo" to "Američani zaupajo našim kadrom, mi pa še vedno dvomimo vase?"),
        3001L to ("dan-odprtih-vrat" to "Dan odprtih vrat"),
        3000L to ("brez-podatkov" to "Brez podatkov &#8211; ni dogodka"),
    ).map { (id, post) -> mapOf("id" to id, "link" to "$site/dogodki/${post.first}/", "title" to mapOf("rendered" to post.second)) }

    private val web = FakeWeb(
        mapOf(
            "$site/wp-json/wp/v2/oddelek?slug=oddelek-podjetnistvo" to """[{"id":14,"slug":"oddelek-podjetnistvo"}]""",
            "$site/wp-json/wp/v2/dogodek?oddelek=14&" to Web.MAPPER.writeValueAsString(posts),
        ) + pages.mapKeys { "$site/dogodki/${it.key}/" }
    )

    private fun events() = PodbreznikParser.parse(context(web), URI(site))

    private fun local(instant: java.time.Instant) = instant.atZone(LJUBLJANA)

    @Test
    fun `reads the entrepreneurship department's events and skips a page without an Event`() {
        val events = events()
        assertEquals(setOf("rcnm-4174", "rcnm-4123", "rcnm-4037", "rcnm-3687", "rcnm-3001"), events.map { it.externalId }.toSet())
        assertTrue(events.all { it.source == Source.PODJETNISKI_INKUBATOR_PODBREZNIK })
    }

    @Test
    fun `Ljubljana's time despite the wrong offset, the place and address from the sidebar, the link and lede`() {
        val event = events().single { it.externalId == "rcnm-4174" }
        assertEquals("Kaj nam pove bilanca?", event.title)
        assertEquals(LocalDate.of(2026, 11, 18), local(event.start).toLocalDate())
        assertEquals(LocalTime.of(13, 0), local(event.start).toLocalTime())
        assertEquals(3600, local(event.start).offset.totalSeconds)
        assertEquals(LocalTime.of(16, 0), local(event.end!!).toLocalTime())
        assertTrue(event.hasStartTime)
        assertTrue(event.hasEndTime)
        assertEquals("Podjetniški inkubator Podbreznik", event.locationName)
        assertEquals("Podbreznik 15, 8000 Novo mesto", event.locationAddress)
        assertEquals("$site/dogodki/kaj-nam-pove-bilanca/", event.eventUrl)
        assertEquals("$site/dogodki/", event.sourceUrl)
        assertTrue(event.description!!.startsWith("Ali veste, kaj se v resnici skriva"))
    }

    @Test
    fun `no end, several days with times, online in summer, and all day over two days`() {
        val events = events()
        val gladiator = events.single { it.externalId == "rcnm-4123" }
        val kickUp = events.single { it.externalId == "rcnm-4037" }
        val online = events.single { it.externalId == "rcnm-3687" }
        val allDay = events.single { it.externalId == "rcnm-3001" }
        assertEquals(LocalTime.of(18, 0), local(gladiator.start).toLocalTime())
        assertNull(gladiator.end)
        assertFalse(gladiator.hasEndTime)
        assertEquals("Knjižnica Mirana Jarca Novo mesto", gladiator.locationName)
        assertEquals(LocalDateTime.of(2026, 10, 19, 11, 0), local(kickUp.start).toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 10, 21, 18, 0), local(kickUp.end!!).toLocalDateTime())
        assertTrue(kickUp.hasEndTime)
        assertEquals(LocalTime.of(16, 15), local(online.start).toLocalTime())
        assertEquals(7200, local(online.start).offset.totalSeconds)
        assertEquals("Online", online.locationName)
        assertNull(online.locationAddress)
        assertFalse(allDay.hasStartTime)
        assertEquals(LocalTime.MIDNIGHT, local(allDay.start).toLocalTime())
        assertEquals(LocalDate.of(2026, 12, 3), local(allDay.end!!).toLocalDate())
        assertFalse(allDay.hasEndTime)
    }

    @Test
    fun `a page's Event among Yoast's other JSON-LD, and none is an error`() {
        val page = PodbreznikParser.pageOf(bilanca)
        assertTrue(page.hasStartTime)
        assertEquals("Podjetniški inkubator Podbreznik", page.place)
        assertFailsWith<IllegalArgumentException> { PodbreznikParser.pageOf("<html><script type=\"application/ld+json\">{\"@type\":\"WebPage\"}</script></html>") }
        assertFailsWith<IllegalArgumentException> { PodbreznikParser.pageOf("<html><script type=\"application/ld+json\">not json</script></html>") }
    }

    @Test
    fun `fails without the entrepreneurship department`() {
        val web = FakeWeb(mapOf("$site/wp-json/wp/v2/oddelek?slug=oddelek-podjetnistvo" to "[]"))
        assertFailsWith<IllegalStateException> { PodbreznikParser.parse(context(web), URI(site)) }
    }

    @Test
    fun `takes rc-nm's urls`() {
        assertTrue(PodbreznikParser.accepts(SourceField.WEBSITE, URI("https://www.rc-nm.si/dogodki/")))
        assertTrue(PodbreznikParser.accepts(SourceField.WEBSITE, URI("https://rc-nm.si")))
        assertFalse(PodbreznikParser.accepts(SourceField.WEBSITE, URI("https://old.rc-nm.si")))
    }
}
