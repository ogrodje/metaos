package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SLAIFParserTest {

    // Items as /api/events returns them (copied from the live API), and a draft and one that doesn't fit
    private val eventsJson = """[
        {"id":52,"title":"Vzpostavljanje podatkovne kulture: zbiranje, upravljanje in hramba podatkov | Building a data culture: data collection, management and preservation","tags":["Workshop","Online"],"event_date":"2027-01-20","time_range":"13:00–15:00","location":"VK","lecturer":"Marko Drobnjak","difficulty":1,"indico_url":"https://indico.ijs.si/event/3989/","status":"published"},
        {"id":14,"title":"Scaling AI with Multi-GPU","tags":[],"event_date":"2027-01-18","time_range":"","location":"Fakulteta za informacijske študije, Univerza v Novem mestu, Ljubljanska cesta 31a, 8000 Novo mesto","lecturer":"Robi Pritržnik","difficulty":2,"indico_url":"https://indico.ijs.si/event/3993/","status":"published"},
        {"id":44,"title":"Ai act, DGA in upravljanje umetne inteligence – od strategij do prakse | AI Act, DGA and AI governance","tags":["Workshop","On-site"],"event_date":"2026-09-09","time_range":"09:00–13:00","location":"Tehnološki park Ljubljana","lecturer":"Barbi Seme, Matej Kirn","difficulty":1,"indico_url":"","status":"published"},
        {"id":99,"title":"Osnutek | Draft","tags":[],"event_date":"2027-02-01","time_range":"10:00–12:00","location":"Online","lecturer":"","difficulty":1,"indico_url":null,"status":"draft"},
        {"id":98,"title":"Brez datuma","tags":[],"event_date":"kmalu","time_range":"","location":"","lecturer":"","difficulty":1,"indico_url":null,"status":"published"}
        ]"""

    private fun events() = SLAIFParser.parse(context(FakeWeb(mapOf("https://www.slaif.si/api/events" to eventsJson))), URI("https://www.slaif.si"))

    private fun local(instant: java.time.Instant) = instant.atZone(LJUBLJANA)

    @Test
    fun `reads published events, and skips drafts and items that don't fit`() {
        val events = events()
        assertEquals(setOf("slaif-52", "slaif-14", "slaif-44"), events.map { it.externalId }.toSet())
        assertTrue(events.all { it.source == Source.SLAIF })
    }

    @Test
    fun `an event with a time range, in Slovenian, linking to Indico`() {
        val event = events().single { it.externalId == "slaif-52" }
        assertEquals("Vzpostavljanje podatkovne kulture: zbiranje, upravljanje in hramba podatkov", event.title)
        assertEquals(LocalDate.of(2027, 1, 20), local(event.start).toLocalDate())
        assertEquals(LocalTime.of(13, 0), local(event.start).toLocalTime())
        assertEquals(3600, local(event.start).offset.totalSeconds)
        assertEquals(LocalTime.of(15, 0), local(event.end!!).toLocalTime())
        assertTrue(event.hasStartTime)
        assertTrue(event.hasEndTime)
        // "VK", tagged Online
        assertEquals("Online", event.locationName)
        assertEquals("Izvajalci: Marko Drobnjak", event.description)
        assertEquals("https://indico.ijs.si/event/3989/", event.eventUrl)
        assertEquals("https://www.slaif.si/events", event.sourceUrl)
    }

    @Test
    fun `no time is all-day, no Indico link is SLAIF's events page, and summer time`() {
        val events = events()
        val noTime = events.single { it.externalId == "slaif-14" }
        val noIndico = events.single { it.externalId == "slaif-44" }
        assertEquals("Scaling AI with Multi-GPU", noTime.title)
        assertFalse(noTime.hasStartTime)
        assertNull(noTime.end)
        assertTrue(noTime.locationName!!.startsWith("Fakulteta za informacijske študije"))
        assertEquals("https://www.slaif.si/events", noIndico.eventUrl)
        assertEquals(7200, local(noIndico.start).offset.totalSeconds)
    }

    @Test
    fun `titles and time ranges`() {
        assertEquals("Odprta koda za raziskovanje", SLAIFParser.slovenian("Odprta koda za raziskovanje | Open source for research"))
        assertEquals("Hands on Machine Learning", SLAIFParser.slovenian("Hands on Machine Learning"))
        assertEquals("A | B | C", SLAIFParser.slovenian("A | B | C"))
        assertEquals(LocalTime.of(9, 55) to LocalTime.of(12, 0), SLAIFParser.timesOf("09:55–12:00"))
        assertEquals(LocalTime.of(10, 0) to LocalTime.of(11, 20), SLAIFParser.timesOf("10:00 - 11:20"))
        assertEquals(LocalTime.of(10, 0) to null, SLAIFParser.timesOf("10.00"))
        assertNull(SLAIFParser.timesOf(null))
        assertNull(SLAIFParser.timesOf("po dogovoru"))
    }

    @Test
    fun `takes slaif's urls`() {
        assertTrue(SLAIFParser.accepts(SourceField.WEBSITE, URI("https://slaif.si")))
        assertTrue(SLAIFParser.accepts(SourceField.WEBSITE, URI("https://www.slaif.si/events")))
        assertFalse(SLAIFParser.accepts(SourceField.WEBSITE, URI("https://notslaif.si")))
    }

    @Test
    fun `places the saved events by their tags, locations and Indico`() {
        // SLAIF's API and five of its events' Indico exports, as they were on 18 November 2026
        val web = FakeWeb(
            mapOf("https://www.slaif.si/api/events" to FakeWeb.fixture("slaif-events.json")) +
                listOf(3989, 4023, 3969, 3977, 3819).associate { "https://indico.ijs.si/export/event/$it.json" to FakeWeb.fixture("indico-$it.json") },
        )
        val events = SLAIFParser.parse(context(web), URI("https://www.slaif.si"), LocalDate.of(2026, 6, 1)).associateBy { it.externalId }
        fun place(id: Int) = Location(events.getValue("slaif-$id").locationName, events.getValue("slaif-$id").locationAddress)
        fun atIndico(id: Int) = events.values.single { it.eventUrl == "https://indico.ijs.si/event/$id/" }.let { Location(it.locationName, it.locationAddress) }

        // An online course uses the Indico description but keeps its online venue.
        assertEquals(Location.ONLINE, place(52))
        assertEquals(1, web.asked.count { it.endsWith("/3989.json") })
        assertEquals("Learning objectives:\n\nPlanning the data life cycle.", events.getValue("slaif-52").description)
        // Indico's place, room and address
        assertEquals(Location("Teslova, Soba 38-39 \"e-predavalnica\", 1. nadstropje", "Teslova 30"), atIndico(4023))
        assertEquals(Location("IZUM, Mala dvorana", "Prešernova ulica 17, 2000 Maribor"), atIndico(3969))
        assertEquals(Location("UL FRI, P02", "Večna pot 113, 1000 Ljubljana"), atIndico(3819))
        assertEquals(
            Location("Tehnološki park Ljubljana, stavba B, 2. nadstropje, Technology Park Ljubljana d.o.o.", "Tehnološki park 19, 1000 Ljubljana"),
            atIndico(3977),
        )
        // Indico not saved (404): SLAIF's own
        assertEquals(
            Location("Fakulteta za informacijske študije, Univerza v Novem mestu", "Ljubljanska cesta 31a, 8000 Novo mesto"),
            atIndico(3993),
        )
    }
}
