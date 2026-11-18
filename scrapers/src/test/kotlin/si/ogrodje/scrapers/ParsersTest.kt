package si.ogrodje.scrapers

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which parser takes which of Goo's meetups' links, as Goo's EventsSync.runParser picked them */
class ParsersTest {

    private fun source(url: String, field: SourceField = SourceField.WEBSITE) = parserFor(field, URI(url))?.source

    @Test
    fun `picks each of Goo's meetups' links' parser`() {
        assertEquals(Source.MEETUP, source("https://www.meetup.com/js-ljubljana/", SourceField.MEETUP))
        assertEquals(Source.EVENTBRITE, source("https://www.eventbrite.com/o/kovacnica-8627636641", SourceField.EVENTBRITE))
        assertEquals(Source.TEHNOLOSKI_PARK_LJUBLJANA, source("https://www.tp-lj.si"))
        assertEquals(Source.GZS, source("https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/"))
        assertEquals(Source.PRIMORSKI_TEHNOLOSKI_PARK, source("https://www.primorski-tp.si/"))
        assertEquals(Source.STARTUP_SI, source("https://www.startup.si"))
        assertEquals(Source.ICAL, source("https://sasainkubator.si/dogodki/seznam/?ical=1", SourceField.ICAL))
        assertEquals(Source.LUMA, source("https://luma.com/sloai", SourceField.LUMA))
        assertEquals(Source.DATA_SCIENCE, source("https://datascience.fri.uni-lj.si"))
        assertEquals(Source.FRI, source("https://fri.uni-lj.si"))
        assertEquals(Source.KCUI, source("https://kcui.si/"))
        assertEquals(Source.INKUBATOR_SAVINJSKE_REGIJE, source("https://inkubatorsr.si"))
        assertEquals(Source.RACUNALNISKI_MUZEJ, source("https://www.racunalniski-muzej.si"))
        assertEquals(Source.LUI, source("https://lui.si/"))
        assertEquals(Source.SPLETNE_URICE, source("https://wwwhmb.si"))
        assertEquals(Source.POMURSKI_TEHNOLOSKI_PARK, source("https://www.p-tech.si"))
        assertEquals(Source.INKUBATOR_BELA_KRAJINA, source("https://inkubator-belakrajina.si"))
        assertEquals(Source.INKUBATOR_ROGASKA_SLATINA, source("https://www.inkubator-rs.si"))
        assertEquals(Source.SLAIF, source("https://www.slaif.si"))
        assertEquals(Source.KATAPULT, source("https://katapult.si"))
        assertEquals(Source.PODJETNISKI_INKUBATOR_PODBREZNIK, source("https://www.rc-nm.si"))
        assertEquals(Source.MPIK_KOROSKA, source("https://www.mpik-koroska.si"))
    }

    @Test
    fun `leaves links no parser takes, e g a website or Kompot, to none`() {
        assertNull(source("https://ogrodje.si"))
        assertNull(source("https://dogodki.kompot.si", SourceField.KOMPOT))
        // A calendar's link in the website field isn't read as one
        assertNull(source("https://example.com/cal.ics"))
        val result = scrape(ScrapeRequest("o", "Ogrodje", SourceField.WEBSITE, "https://ogrodje.si", Instant.now()), FakeWeb(emptyMap()), null)
        assertEquals(ScrapeResult(null, emptyList()), result)
    }

    @Test
    fun `keeps only what ends at the request's since or later`() {
        val ical = """BEGIN:VCALENDAR
            |VERSION:2.0
            |PRODID:test
            |BEGIN:VEVENT
            |UID:old@test
            |DTSTAMP:20260101T000000Z
            |DTSTART:20200101T180000Z
            |SUMMARY:Old
            |END:VEVENT
            |BEGIN:VEVENT
            |UID:new@test
            |DTSTAMP:20260101T000000Z
            |DTSTART:20991101T180000Z
            |SUMMARY:New
            |END:VEVENT
            |END:VCALENDAR
            |""".trimMargin().replace("\n", "\r\n")
        val web = FakeWeb(mapOf("https://example.com/cal.ics" to ical))
        val result = scrape(ScrapeRequest("o", "Test", SourceField.ICAL, "https://example.com/cal.ics", aYearAgo()), web, null)
        assertEquals(Source.ICAL, result.source)
        assertEquals(listOf("New"), result.events.map { it.title })
    }
}
