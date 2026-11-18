package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import si.ogrodje.scrapers.FakeWeb.Companion.fixture
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TehnoloskiParkLjubljanaParserTest {

    private val site = "https://www.tp-lj.si"

    private val web = FakeWeb(
        mapOf(
            "$site/sl/koledar-dogodkov" to fixture("tp-koledar-dogodkov.html"),
            "$site/sl/dogodki/nacionalna-financna-klinika-spodbuda-za-digitalno-transformacijo-p4d-2025-04-15" to
                fixture("tp-nacionalna-financna-klinika.html"),
            "$site/sl/dogodki/podjetniska-akademija-inovacije-in-rast-na-b2b-in-b2g-trgih-2025-04-04" to
                fixture("tp-podjetniska-akademija.html"),
            "$site/sl/dogodki/arena-tehnologij-se-vraca-2025-03-11" to fixture("tp-arena-tehnologij.html"),
        )
    )

    private val events = TehnoloskiParkLjubljanaParser.parse(context(web), URI(site), Instant.parse("2025-04-17T10:00:00Z"))

    private fun event(id: String) = events.single { it.externalId == id }

    @Test
    fun `reads the calendar's events from a month ago on`() {
        assertEquals(13, events.size)
        assertTrue(events.all { it.source == Source.TEHNOLOSKI_PARK_LJUBLJANA && it.sourceUrl == "$site/sl/koledar-dogodkov" })
        assertTrue(events.all { it.start >= Instant.parse("2025-03-17T00:00:00Z") })
    }

    @Test
    fun `a day with a time lasts two hours`() {
        val clinic = event("tp-9008")
        assertEquals("Nacionalna finančna klinika – Spodbuda za digitalno transformacijo P4D", clinic.title)
        assertEquals(Instant.parse("2025-04-17T09:00:00Z"), clinic.start)
        assertEquals(Instant.parse("2025-04-17T11:00:00Z"), clinic.end)
        assertTrue(clinic.hasStartTime)
        assertFalse(clinic.hasEndTime)
        assertEquals(
            "$site/sl/dogodki/nacionalna-financna-klinika-spodbuda-za-digitalno-transformacijo-p4d-2025-04-15",
            clinic.eventUrl,
        )
    }

    @Test
    fun `a range of days and a single day are all day`() {
        val academy = event("tp-9002")
        assertEquals(Instant.parse("2025-05-06T22:00:00Z"), academy.start)
        assertEquals(Instant.parse("2025-05-16T21:59:00Z"), academy.end)
        assertFalse(academy.hasStartTime)

        val arena = event("tp-8990")
        assertEquals(Instant.parse("2025-09-24T22:00:00Z"), arena.start)
        assertEquals(Instant.parse("2025-09-25T21:59:00Z"), arena.end)
    }

    @Test
    fun `an event whose page doesn't answer keeps the calendar's days`() {
        val others = events.filterNot { it.externalId in setOf("tp-9008", "tp-9002", "tp-8990") }
        assertTrue(others.isNotEmpty())
        others.forEach {
            assertFalse(it.hasStartTime)
            assertEquals(it.start.atZone(LJUBLJANA).toLocalTime(), java.time.LocalTime.MIDNIGHT)
        }
    }

    private fun place(page: String) = TehnoloskiParkLjubljanaParser.locationIn(org.jsoup.Jsoup.parse(fixture(page)))

    @Test
    fun `an event is where its page says, when it says`() {
        // Pages saved on 18 November 2026
        assertEquals(Location("Tehnološki park Ljubljana"), place("tp-arena-podjetnistva.html"))
        // Without the remark in brackets after it
        assertEquals(Location("G-Gravity Incubator, Milano, Italija"), place("tp-tiger-hackathon.html"))
        // A webinar that doesn't say where, and "Kje začeti z avtomatizacijo:" isn't a place
        assertEquals(Location.NONE, place("tp-avtomatizacija-marketinga.html"))
        assertTrue(events.all { it.locationName == null && it.locationAddress == null })
    }

    @Test
    fun `a page without the calendar's JSON fails`() {
        val web = FakeWeb(mapOf("$site/sl/koledar-dogodkov" to "<html><body>Vzdrževanje</body></html>"))
        assertFailsWith<NoSuchElementException> { TehnoloskiParkLjubljanaParser.parse(context(web), URI(site)) }
    }

    @Test
    fun `reads the three ways a page says when`() {
        val range = TehnoloskiParkLjubljanaDurationParser.parse("07. 05. 2025 - 16. 05. 2025")
        assertEquals(Instant.parse("2025-05-06T22:00:00Z"), range.start)
        assertEquals(Instant.parse("2025-05-16T21:59:00Z"), range.end)
        assertFalse(range.hasStartTime || range.hasEndTime)

        val withTime = TehnoloskiParkLjubljanaDurationParser.parse("17. 04. 2025 ob 11:00")
        assertEquals(Instant.parse("2025-04-17T09:00:00Z"), withTime.start)
        assertTrue(withTime.hasStartTime)

        val day = TehnoloskiParkLjubljanaDurationParser.parse("25. 12. 2025")
        assertEquals(Instant.parse("2025-12-24T23:00:00Z"), day.start)

        assertFailsWith<IllegalArgumentException> { TehnoloskiParkLjubljanaDurationParser.parse("jutri") }
    }

    @Test
    fun `takes tp-lj si's urls only`() {
        assertTrue(TehnoloskiParkLjubljanaParser.accepts(SourceField.WEBSITE, URI("https://www.tp-lj.si")))
        assertFalse(TehnoloskiParkLjubljanaParser.accepts(SourceField.WEBSITE, URI("https://www.gzs.si")))
    }
}
