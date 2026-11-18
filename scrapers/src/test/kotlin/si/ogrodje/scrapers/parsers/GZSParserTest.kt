package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class GZSParserTest {
    private val url = URI("https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki")
    private val now = ZonedDateTime.parse("2026-11-18T12:00:00+01:00[Europe/Ljubljana]")

    private fun box(id: String, title: String, dates: String, place: String = "GZS", summary: String = "") = """
        <div class="CKDMainEventBox" id="row">
          <div class="CKDMainEventDate" id="left"><input type="hidden" name="hfDatumOd" value="18. 11. 2026 14:00:00" /></div>
          <div class="CKDMainEventDetail nothumbnail" id="right">
            <div id="naslov"><input type="hidden" name="hfID" value=" $id " />
              <a href="https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki?ID=$id">$title</a></div>
            <div id="povzetek"><span>$summary</span></div>
            <div id="datum"><strong>Datum:</strong> <span>$dates</span></div>
            <div id="kraj"><strong>Kraj: </strong><span>$place</span></div>
          </div>
        </div>"""

    private val list = """<div id="middle"><div id="Seznam">
        ${box("6784", "Akademija umetne inteligence", "18.11.2026 14:00 - 25.11.2026 16:30", summary = "Za podjetja")}
        ${box("6790", "Dan informatike", "03.12.2026 09:00 - 03.12.2026 15:00", place = "Ljubljana")}
        ${box("6001", "Old event", "01.05.2026 09:00 - 01.05.2026 15:00")}
        </div></div>"""

    @Test
    fun `reads the monthly list's events, leaving out those over three months old`() {
        val events = GZSParser.events(url, list, now)
        assertEquals(listOf("gzs-6784", "gzs-6790"), events.map { it.externalId })

        val first = events.first()
        assertEquals("Akademija umetne inteligence", first.title)
        assertEquals(Instant.parse("2026-11-18T13:00:00Z"), first.start)
        assertEquals(Instant.parse("2026-11-25T15:30:00Z"), first.end)
        assertEquals("Za podjetja", first.description)
        assertEquals("GZS", first.locationName)
        assertEquals("https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki?ID=6784", first.eventUrl)
        assertEquals(url.toString(), first.sourceUrl)
        assertEquals(Source.GZS, first.source)
        assertEquals("Ljubljana", events[1].locationName)
    }

    @Test
    fun `an event without its dates fails`() {
        assertFails { GZSParser.events(url, box("1", "No dates", "kmalu"), now) }
    }

    @Test
    fun `takes gzs's urls`() {
        assertTrue(GZSParser.accepts(SourceField.WEBSITE, URI("https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/")))
        assertTrue(!GZSParser.accepts(SourceField.WEBSITE, URI("https://www.tp-lj.si/")))
    }
}
