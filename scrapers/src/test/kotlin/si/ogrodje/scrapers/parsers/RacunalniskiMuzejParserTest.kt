package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RacunalniskiMuzejParserTest {

    private val host = "https://www.racunalniski-muzej.si"

    // An archive's card as the "ova-events" plugin renders it: a day and a month, no year
    private fun card(slug: String, title: String) = """<div class="content">
          <div class="date-event"><span class="date-month">02<span class="month">december</span></span></div>
          <div class="desc"><div class="event_post">
            <h2 class="second_font event_title"><a href="$host/event/$slug/">$title</a></h2>
            <div class="button_event"><a class="view_detail" href="$host/event/$slug/">Podrobnosti</a></div>
          </div></div>
        </div>"""

    private fun archive(vararg cards: String) =
        """<!DOCTYPE html><html><body class="archive post-type-archive post-type-archive-event">
        <div class="archive_event type1">${cards.joinToString("\n")}</div>
        <a href="$host/event/page/2/">2</a>
        </body></html>"""

    // An event's page: the order box with its place and the addtocalendar.com links, month first
    private fun detail(postId: Int, title: String, start: String, end: String, location: String?, calendar: Boolean = true): String {
        val atc = "http://addtocalendar.com/atc/ical?utz=420&amp;uln=en-US&amp;vjs=1.5&amp;e[0][date_start]=$start" +
            "&amp;e[0][date_end]=$end&amp;e[0][timezone]=&amp;e[0][title]=$title&amp;e[0][description]=Opis &amp; več"
        val place = location?.let { """<li><span class="label">Lokacija:</span><span>$it</span></li>""" } ?: ""
        val sync = if (calendar) """<div class="calendar-sync"><span class="sync"><a href="$atc">+ Ical Export</a></span></div>""" else ""
        return """<!DOCTYPE html><html><head><meta name="description" content="Opis dogodka $title." /></head>
        <body class="event-template-default single single-event postid-$postId wp-theme-muzze">
        <div class="single_event"><div class="container">
          <div class="title_top"><h1 class="second_font">$title</h1>
            <div class="value_mid"><span class="event-date">sreda,&nbsp;02/12/2026</span></div></div>
          $sync
          <div class="wrapper_order"><div class="order_ticket"><ul class="info_order">
            <li><span class="label">Vrsta:</span> <span>Posebni dogodki</span></li>
            $place
          </ul></div></div>
        </div></div></body></html>"""
    }

    // Four pages, of which three are read; dedek-mraz is on two
    private val web = FakeWeb(
        mapOf(
            "$host/event/" to archive(card("dedek-mraz", "Obisk Dedka Mraza"), card("ars-electronica", "Ars Electronica")),
            "$host/event/page/2/" to archive(card("arena", "Arena tehnologij"), card("dedek-mraz", "Obisk Dedka Mraza")),
            "$host/event/page/3/" to archive(card("brez-ure", "Dogodek brez ure"), card("brez-koledarja", "Brez koledarja")),
            "$host/event/page/4/" to archive(card("na-cetrti", "Star dogodek")),
            "$host/event/dedek-mraz/" to detail(18922, "Obisk Dedka Mraza", "12/02/2026 17:00", "12/02/2026 19:00", "Računalniški muzej"),
            "$host/event/ars-electronica/" to detail(18001, "Ars Electronica", "09/09/2026 10:00", "09/13/2026 16:00", "Linz"),
            // The site has one like it: it ends two days before it starts
            "$host/event/arena/" to detail(18002, "Arena tehnologij", "10/21/2026 08:00", "10/19/2026 17:00", null),
            "$host/event/brez-ure/" to detail(18003, "Dogodek brez ure", "11/05/2026", "11/05/2026", null),
            "$host/event/brez-koledarja/" to detail(18004, "Brez koledarja", "", "", null, calendar = false),
            "$host/event/na-cetrti/" to detail(17000, "Star dogodek", "01/10/2024 10:00", "01/10/2024 12:00", null),
        )
    )

    private val events = RacunalniskiMuzejParser.parse(context(web), URI(host))

    private fun event(title: String) = events.single { it.title == title }

    @Test
    fun `reads the three newest pages of the archive, each event once, without those that don't read`() {
        assertEquals(listOf("Arena tehnologij", "Ars Electronica", "Dogodek brez ure", "Obisk Dedka Mraza"), events.map { it.title }.sorted())
        assertTrue(events.all { it.source == Source.RACUNALNISKI_MUZEJ && it.sourceUrl == "$host/event/" })
        assertFalse(web.asked.contains("$host/event/page/4/"))
    }

    @Test
    fun `reads the year, times, place and description from the event's page`() {
        val event = event("Obisk Dedka Mraza")
        val start = event.start.atZone(LJUBLJANA)
        assertEquals(LocalDateTime.of(2026, 12, 2, 17, 0), start.toLocalDateTime())
        assertEquals(3600, start.offset.totalSeconds)
        assertEquals(LocalTime.of(19, 0), event.end!!.atZone(LJUBLJANA).toLocalTime())
        assertTrue(event.hasStartTime && event.hasEndTime)
        assertEquals("Računalniški muzej", event.locationName)
        assertEquals("Opis dogodka Obisk Dedka Mraza.", event.description)
        assertEquals("$host/event/dedek-mraz/", event.eventUrl)
    }

    @Test
    fun `keeps an event of several days with its last, in summer time`() {
        val event = event("Ars Electronica")
        assertEquals(7200, event.start.atZone(LJUBLJANA).offset.totalSeconds)
        assertEquals(LocalDateTime.of(2026, 9, 13, 16, 0), event.end!!.atZone(LJUBLJANA).toLocalDateTime())
        assertEquals("Linz", event.locationName)
    }

    @Test
    fun `leaves out an end before the start, and a missing place`() {
        val event = event("Arena tehnologij")
        assertEquals(LocalDate.of(2026, 10, 21), event.start.atZone(LJUBLJANA).toLocalDate())
        assertNull(event.end)
        assertFalse(event.hasEndTime)
        assertNull(event.locationName)
    }

    @Test
    fun `an event without a time is all day`() {
        val event = event("Dogodek brez ure")
        assertFalse(event.hasStartTime || event.hasEndTime)
        assertEquals(LocalDate.of(2026, 11, 5), event.start.atZone(LJUBLJANA).toLocalDate())
    }

    @Test
    fun `IDs are the WordPress posts'`() {
        assertEquals(
            setOf("racunalniski-muzej-18922", "racunalniski-muzej-18001", "racunalniski-muzej-18002", "racunalniski-muzej-18003"),
            events.map { it.externalId }.toSet(),
        )
    }

    @Test
    fun `an archive that ends early ends at its 404`() {
        val web = FakeWeb(
            mapOf(
                "$host/event/" to archive(card("dedek-mraz", "Obisk Dedka Mraza")),
                "$host/event/dedek-mraz/" to detail(18922, "Obisk Dedka Mraza", "12/02/2026 17:00", "12/02/2026 19:00", null),
            )
        )
        assertEquals(1, RacunalniskiMuzejParser.parse(context(web), URI(host)).size)
    }

    @Test
    fun `reads calendar values month first, plain or percent-encoded`() {
        val plain = "http://addtocalendar.com/atc/ical?utz=420&e[0][date_start]=12/02/2026 17:00&e[0][date_end]=12/02/2026 19:00"
        val encoded = "http://addtocalendar.com/atc/google?e%5B0%5D%5Bdate_start%5D=09%2F09%2F2026%2010%3A00&x=1"
        assertEquals("12/02/2026 17:00", RacunalniskiMuzejParser.calendarParamIn(plain, "date_start"))
        assertEquals("12/02/2026 19:00", RacunalniskiMuzejParser.calendarParamIn(plain, "date_end"))
        assertEquals("09/09/2026 10:00", RacunalniskiMuzejParser.calendarParamIn(encoded, "date_start"))
        assertNull(RacunalniskiMuzejParser.calendarParamIn(plain, "timezone"))
        assertEquals(LocalDate.of(2026, 12, 2) to LocalTime.of(17, 0), RacunalniskiMuzejParser.calendarDateTime("12/02/2026 17:00"))
        assertEquals(LocalDate.of(2026, 6, 20) to LocalTime.of(23, 59), RacunalniskiMuzejParser.calendarDateTime("06/20/2026 23:59"))
        assertEquals(LocalDate.of(2026, 11, 5) to null, RacunalniskiMuzejParser.calendarDateTime("11/05/2026"))
        assertNull(RacunalniskiMuzejParser.calendarDateTime("13/40/2026 10:00"))
    }

    @Test
    fun `takes racunalniski-muzej si's urls`() {
        assertTrue(RacunalniskiMuzejParser.accepts(SourceField.WEBSITE, URI("https://racunalniski-muzej.si")))
        assertFalse(RacunalniskiMuzejParser.accepts(SourceField.WEBSITE, URI("https://www.muzej.si")))
    }
}
