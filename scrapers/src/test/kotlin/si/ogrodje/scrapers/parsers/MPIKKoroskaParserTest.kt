package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import si.ogrodje.scrapers.*
import si.ogrodje.scrapers.parsers.MPIKKoroskaParser.Day
import si.ogrodje.scrapers.parsers.MPIKKoroskaParser.Details
import si.ogrodje.scrapers.parsers.MPIKKoroskaParser.cardsIn
import si.ogrodje.scrapers.parsers.MPIKKoroskaParser.daysIn
import si.ogrodje.scrapers.parsers.MPIKKoroskaParser.detailsIn
import si.ogrodje.scrapers.parsers.MPIKKoroskaParser.timesIn
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MPIKKoroskaParserTest {

    private val today = LocalDate.now(LJUBLJANA)
    private val icon =
        """<svg role="img" class="icon calendar" title="Datum" aria-labelledby="title"><title>Datum</title><use xlink:href="/images/sprite.134.svg#calendar"></use> </svg>"""
    private val clock =
        """<svg role="img" class="icon clock" title="Ura" aria-labelledby="title"><title>Ura</title><use xlink:href="/images/sprite.134.svg#clock"></use> </svg>"""

    // Real card markup (from the live listing), with the date list and time put in
    private fun card(slug: String, title: String, dates: String, time: String?) =
        """<div class="flow wAktualno inview2"> <div class="wAktualno__cover"><a href="/aktualno/dogodki/$slug"><img src="/img/x.png" alt=""></a></div>
        <h3><a href="/aktualno/dogodki/$slug">$title </a></h3><ul class="groupList" role="list"><li class="group group--podjetnistvo">
        <a href="/aktualno/dogodki/podjetnistvo">Podjetništvo</a> </li></ul><ul role="list" class="wDogodki__info flow">
        <li class="wDogodki__info--date"> $icon<div>$dates</div></li>
        ${time?.let { """<li class="wDogodki__info--time"> $clock$it</li>""" } ?: ""}</ul></div>"""

    // A date list as the site writes it: groups split by dateDivider, the year on the last one only
    private fun dates(vararg days: Pair<LocalDate, LocalDate?>) =
        days.withIndex().joinToString(""" <span class="dateDivider" ></span> """) { (i, day) ->
            val (from, to) = day
            val d = if (to == null) "${from.dayOfMonth}." else "${from.dayOfMonth}.–${to.dayOfMonth}."
            "<span>$d</span>&nbsp;<span>${from.monthValue}.</span>" + if (i == days.size - 1) "&nbsp;<span>${from.year}</span>" else ""
        }

    private val soon = today.plusDays(5)
    private val series = listOf(today.minusDays(14), today.plusDays(5), today.plusDays(12))
    private val longAgo = today.minusMonths(13)
    private val pastWeek = today.minusDays(7)

    private val listingHtml = """<html><body><section class="noviceList ws__A ws--pad">
        ${card("mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca", "MPIK akademija uspeha 2026: Kako vas vidi umetna inteligenca?", dates(soon to null), "16.00 - 19.00")}
        ${card("mpik-akademija-uspeha-2026", "MPIK akademija uspeha 2026", dates(*series.map { it to null }.toTypedArray()), "16.00 - 19.00")}
        ${card("brezplacni-usposabljanji-za-msp-podjetja-in-potencialne-podjetnike", "Brezplačni usposabljanji za MSP podjetja in potencialne podjetnike", dates(pastWeek to null), null)}
        ${card("koroski-zaposlitveni-in-karierni-sejem-2026", "Koroški zaposlitveni in karierni sejem 2026", dates(today.plusDays(20) to today.plusDays(21)), "9.00 - 16.00")}
        ${card("aktualni-podjetniski-dogodki-in-razpisi-november-2026", "Aktualni podjetniški dogodki in razpisi – november 2026", dates(soon to null, today.plusDays(9) to null), null)}
        ${card("mpeak-akademija-uspeha-2024", "MPEAK akademija uspeha 2024", dates(longAgo to null), "16.00 - 19.00")}
        </section></body></html>"""

    // An event page (trimmed): its own info list first, then related events, and the calendar data
    private val detailHtml = """<html><body><header><ul role="list" class="wDogodki__info flow"><li class="wDogodki__info--date"><div><span>17.</span>&nbsp;<span>11.</span>&nbsp;<span>2026</span></div></li>
        <li class="wDogodki__info--time"> 16.00 - 19.00</li>
        <li class="wDogodki__info--place"> <svg role="img" class="icon location" title="Lokacija" aria-labelledby="title"><title>Lokacija</title><use xlink:href="/images/sprite.134.svg#location"></use> </svg>Stara železarna Ravne, Koroška cesta 12, 2390 Ravne na Koroškem</li></ul></header>
        <aside><ul class="wDogodki__info flow"><li class="wDogodki__info--place"> Drugje</li></ul></aside>
        <script>${'$'}(document).ready(function() { ${'$'}.flVars = {"u":{"lang":"si"},"rra":{"aCalendar":{"title":"MPIK akademija uspeha 2026: Kako vas vidi umetna inteligenca? ","start":"November 17, 2026 16:00","end":"November 17, 2026 19:00","address":"Stara železarna Ravne","description":"Komu je namenjena?&nbsp;\r\n\r\nAmbicioznim posameznikom s podjetniško idejo.\r\n"},"aCalendar_settings":{}}}; });</script>
        </body></html>"""

    private val web = FakeWeb(
        mapOf(
            "https://rra-koroska.si/aktualno/dogodki/podjetnistvo" to listingHtml,
            "https://rra-koroska.si/aktualno/dogodki/mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca" to detailHtml,
        )
    )

    private fun events(home: String) = MPIKKoroskaParser.parse(context(web), URI(home))

    private fun local(instant: java.time.Instant) = instant.atZone(LJUBLJANA)

    @Test
    fun `reads the podjetnistvo listing's single events, a range and a series by session, and skips roundups and old events`() {
        val events = events("https://www.mpik-koroska.si/si/")
        assertEquals(
            setOf(
                "mpik-mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca",
                "mpik-mpik-akademija-uspeha-2026-${series[0]}",
                "mpik-mpik-akademija-uspeha-2026-${series[2]}",
                "mpik-brezplacni-usposabljanji-za-msp-podjetja-in-potencialne-podjetnike",
                "mpik-koroski-zaposlitveni-in-karierni-sejem-2026",
            ),
            events.map { it.externalId }.toSet(),
        )
        assertTrue(events.all { it.source == Source.MPIK_KOROSKA })
    }

    @Test
    fun `an event's time range in Ljubljana's time, its place and description from its page, and its link`() {
        val event = events("https://rra-koroska.si/").single { it.externalId == "mpik-mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca" }
        assertEquals("MPIK akademija uspeha 2026: Kako vas vidi umetna inteligenca?", event.title)
        assertEquals(soon.atTime(16, 0), local(event.start).toLocalDateTime())
        assertEquals(LocalTime.of(19, 0), local(event.end!!).toLocalTime())
        assertTrue(event.hasStartTime)
        assertTrue(event.hasEndTime)
        assertEquals("Stara železarna Ravne", event.locationName)
        assertEquals("Koroška cesta 12, 2390 Ravne na Koroškem", event.locationAddress)
        assertEquals("Komu je namenjena? Ambicioznim posameznikom s podjetniško idejo.", event.description)
        assertEquals("https://rra-koroska.si/aktualno/dogodki/mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca", event.eventUrl)
        assertEquals("https://rra-koroska.si/aktualno/dogodki/podjetnistvo", event.sourceUrl)
    }

    @Test
    fun `a range ends on its last day, no time is all-day, and a page that can't be read leaves no place`() {
        val events = events("https://rra-koroska.si/")
        val fair = events.single { it.externalId == "mpik-koroski-zaposlitveni-in-karierni-sejem-2026" }
        val msp = events.single { it.externalId == "mpik-brezplacni-usposabljanji-za-msp-podjetja-in-potencialne-podjetnike" }
        val session = events.single { it.externalId == "mpik-mpik-akademija-uspeha-2026-${series[2]}" }
        assertEquals(today.plusDays(20).atTime(9, 0), local(fair.start).toLocalDateTime())
        assertEquals(today.plusDays(21), local(fair.end!!).toLocalDate())
        assertFalse(fair.hasEndTime)
        assertFalse(msp.hasStartTime)
        assertNull(msp.end)
        assertNull(msp.locationName)
        assertEquals("MPIK akademija uspeha 2026", session.title)
        assertEquals(LocalTime.of(16, 0), local(session.start).toLocalTime())
    }

    @Test
    fun `date lists as the live site writes them`() {
        fun div(html: String) = Jsoup.parse("<div id=d>$html</div>").getElementById("d")!!
        fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
        assertEquals(listOf(Day(d(2026, 11, 17), null)), daysIn(div("<span>17.</span>&nbsp;<span>11.</span>&nbsp;<span>2026</span>")))
        assertEquals(listOf(Day(d(2023, 4, 12), d(2023, 4, 13))), daysIn(div("<span>12.–13.</span> <span>4.</span>&nbsp;<span>2023</span>")))
        assertEquals(
            listOf(Day(d(2026, 9, 29), null), Day(d(2026, 10, 7), d(2026, 10, 8)), Day(d(2026, 11, 24), null)),
            daysIn(div("""<span>29.</span>&nbsp;<span>9.</span> <span class="dateDivider" ></span> <span>7.–8.</span> <span>10.</span> <span class="dateDivider" ></span> <span>24.</span>&nbsp;<span>11.</span>&nbsp;<span>2026</span>""")),
        )
        // Across New Year: the year is only on the last date
        assertEquals(
            listOf(Day(d(2026, 12, 16), null), Day(d(2027, 1, 13), null)),
            daysIn(div("""<span>16.</span>&nbsp;<span>12.</span> <span class="dateDivider" ></span> <span>13.</span>&nbsp;<span>1.</span>&nbsp;<span>2027</span>""")),
        )
        assertTrue(daysIn(div("<span>kmalu</span>")).isEmpty())
    }

    @Test
    fun `times, cards and event pages`() {
        val cards = cardsIn(Jsoup.parse(listingHtml))
        assertEquals(LocalTime.of(16, 0) to LocalTime.of(19, 0), timesIn("16.00 - 19.00"))
        assertEquals(LocalTime.of(8, 30) to null, timesIn("8.30"))
        assertNull(timesIn(""))
        assertEquals(6, cards.size)
        assertEquals(3, cards.single { it.slug == "mpik-akademija-uspeha-2026" }.days.size)
        assertTrue(cards.single { it.slug.startsWith("aktualni") }.isRoundup)
        assertEquals("Stara železarna Ravne, Koroška cesta 12, 2390 Ravne na Koroškem", detailsIn(Jsoup.parse(detailHtml)).place)
        assertEquals(Details(null, null), detailsIn(Jsoup.parse("<html><body><p>Ni podatkov</p></body></html>")))
    }

    @Test
    fun `fails when the listing doesn't load`() {
        kotlin.test.assertFailsWith<WebException> { MPIKKoroskaParser.parse(context(FakeWeb(emptyMap())), URI("https://rra-koroska.si/")) }
    }

    @Test
    fun `takes rra koroska's and mpik's urls`() {
        assertTrue(MPIKKoroskaParser.accepts(SourceField.WEBSITE, URI("https://www.mpik-koroska.si/si/")))
        assertTrue(MPIKKoroskaParser.accepts(SourceField.WEBSITE, URI("https://rra-koroska.si/")))
        assertFalse(MPIKKoroskaParser.accepts(SourceField.WEBSITE, URI("https://koroska.si/")))
    }
}
