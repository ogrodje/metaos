package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupSiParserTest {

    private val list = """<html><body><div class="next-six-events">
        <a class="event-card" href="/sl-si/dogodki/podjetniski-zajtrk">x</a>
        </div></body></html>"""

    private val event = """<html><body>
        <div class="addeventatc"><span class="title"> Podjetniški zajtrk </span>
        <span class="start">17-11-2026 08:30</span><span class="end">17-11-2026 10:00</span></div>
        <div class="location-html"><p>Tehnološki park</p><p>Ljubljana</p></div></body></html>"""

    @Test
    fun `reads each of the next events from its own page`() {
        val web = FakeWeb(
            mapOf(
                "https://www.startup.si/sl-si/dogodki" to list,
                "https://www.startup.si/sl-si/dogodki/podjetniski-zajtrk" to event,
            )
        )
        val events = StartupSiParser.parse(context(web), URI("https://www.startup.si/"))

        val only = events.single()
        assertEquals("Podjetniški zajtrk", only.title)
        assertEquals(Instant.parse("2026-11-17T07:30:00Z"), only.start)
        assertEquals(Instant.parse("2026-11-17T09:00:00Z"), only.end)
        assertEquals("Tehnološki park, Ljubljana", only.locationName)
        assertEquals("https://www.startup.si/sl-si/dogodki/podjetniski-zajtrk", only.eventUrl)
        assertTrue(only.externalId.startsWith("startup-si-"))
        assertEquals(Source.STARTUP_SI, only.source)
    }

    private fun place(name: String) = StartupSiParser.locationIn(org.jsoup.Jsoup.parse(FakeWeb.fixture("startup-si-$name.html")))

    @Test
    fun `reads the saved pages' places`() {
        assertEquals(Location("Ljubljana.tech", "Trg mladih 9, 1000 Ljubljana"), place("forum"))
        // The venue only in the map link's text
        assertEquals(Location("Knjižnica Mirana Jarca", "Rozmanova ulica 28, 8000 Novo mesto"), place("gladiator"))
        // No box: the calendar's line
        assertEquals(Location("Sodni stolp", "Pristan 8, Maribor"), place("pozabi"))
        assertEquals(Location.ONLINE, place("online"))
        // Two places: the first
        assertEquals(Location("Maribox", "Loška ulica 13, 2000 Maribor"), place("podim"))
    }

    @Test
    fun `takes startup si's urls only`() {
        assertTrue(StartupSiParser.accepts(SourceField.WEBSITE, URI("https://www.startup.si/sl-si")))
        assertTrue(!StartupSiParser.accepts(SourceField.WEBSITE, URI("https://www.meetup.com/x")))
    }
}
