package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import si.ogrodje.scrapers.*
import si.ogrodje.scrapers.parsers.FRIParser.DayMonth
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FRIParserTest {

    private fun single(day: String, month: String, slug: String, title: String, hour: String) =
        """<div class="dogodek-item" style="margin-bottom: 20px;">
          <div class="date" style="float:left">
            <div class="date-single-top"> $day </div>
            <div class="date-single-bottom" style="font-size: 18px;"> $month </div>
          </div>
          <div class="dogodki-opis">
            <div class="event-title" style="width: 100%;"><a href="/sl/dogodek/$slug" style="color:#000">$title</a></div>
            <div class="dogodki-hour" style="color: #7f7f7f;">$hour</div>
            <div class="dogodki-taxonomy"><span class="kategorija"> Obvestila </span></div>
          </div>
        </div>"""

    // The layout of several days that broke Goo's parser on 2026-11-03, from the live calendar
    private val multiDay = """<div class="dogodek-item" style="margin-bottom: 20px;">
          <div class="date" style="float:left">
            <div class="date-top"> <span class="dan">24. </span> <span class="mesec">nov</span> </div>
            <div class="date-middle">-</div>
            <div class="date-bottom"> <span class="dan">26. </span> <span class="mesec">nov</span> </div>
          </div>
          <div class="dogodki-opis">
            <div class="event-title" style="width: 100%;">
              <a href="/sl/dogodek/uporabna-orodja-za-slovenscino" style="color:#000">Uporabna orodja za slovenščino</a>
            </div>
            <div class="dogodki-hour" style="color: #7f7f7f;">ob 17:00</div>
          </div>
        </div>"""

    private val broken = """<div class="dogodek-item"><div class="dogodki-opis">
          <div class="event-title"><a href="/sl/dogodek/brez-datuma">Brez datuma</a></div>
        </div></div>"""

    private val calendar = """<!DOCTYPE html><html><body><div id="vsi-dogodki">
        ${single("11.", "nov", "predavanje-asef", "Predavanje ASEF: Veliki jezikovni modeli", "ob 17:15")}
        ${single("16.", "nov", "slovenski-okusi-na-fri", "Slovenski okusi na FRI", "ob 10:00")}
        $multiDay
        $broken
        ${single("25.", "nov", "javna-predstavitev-doktorske-disertacije", "Javna predstavitev", "ob 11:00")}
        </div></body></html>"""

    private val today = LocalDate.of(2026, 11, 11)

    private val web = FakeWeb(mapOf("https://fri.uni-lj.si/sl/koledar-dogodkov" to calendar))

    private val events = FRIParser.parse(context(web), today)

    @Test
    fun `reads events of one day and of several, without a card that doesn't read or doctoral defences`() {
        assertEquals(
            listOf("Predavanje ASEF: Veliki jezikovni modeli", "Slovenski okusi na FRI", "Uporabna orodja za slovenščino"),
            events.map { it.title }.sorted(),
        )
        assertTrue(events.all { it.source == Source.FRI && it.locationAddress == "Večna pot 113, 1000 Ljubljana" })
    }

    @Test
    fun `an event of several days starts on its first and ends on its last`() {
        val event = events.single { it.title == "Uporabna orodja za slovenščino" }
        val start = event.start.atZone(LJUBLJANA)
        assertEquals(LocalDate.of(2026, 11, 24), start.toLocalDate())
        assertEquals(LocalTime.of(17, 0), start.toLocalTime())
        assertEquals(LocalDate.of(2026, 11, 26), event.end!!.atZone(LJUBLJANA).toLocalDate())
        assertTrue(event.hasStartTime)
        assertFalse(event.hasEndTime)
    }

    @Test
    fun `times are Ljubljana's, and IDs Goo's, the sha1 of the link`() {
        val event = events.single { it.title == "Slovenski okusi na FRI" }
        assertEquals(LocalTime.of(10, 0), event.start.atZone(LJUBLJANA).toLocalTime())
        assertEquals("fri-0dd4e911e9b6cad40f42721c65df2b9c5aedb6bc", event.externalId)
        assertEquals("https://fri.uni-lj.si/sl/dogodek/slovenski-okusi-na-fri", event.eventUrl)
    }

    @Test
    fun `reads FRI from one address, with www or without, so its events are the same (OG-67)`() {
        val web = FakeWeb(mapOf("https://fri.uni-lj.si/sl/koledar-dogodkov" to calendar))
        val www = FRIParser.parse(context(web), URI("https://www.fri.uni-lj.si/"))
        val bare = FRIParser.parse(context(web), URI("https://fri.uni-lj.si"))
        assertEquals(bare.map { it.externalId to it.sourceUrl }, www.map { it.externalId to it.sourceUrl })
        assertTrue(www.all { it.sourceUrl == "https://fri.uni-lj.si" && it.eventUrl!!.startsWith("https://fri.uni-lj.si/sl/dogodek/") })
        assertTrue(web.asked.filter { "koledar" in it }.all { it == "https://fri.uni-lj.si/sl/koledar-dogodkov" })
    }

    @Test
    fun `an event's link is the same without www and a slash at its end`() {
        val linked = calendar.replace("href=\"/sl/dogodek/slovenski-okusi-na-fri\"", "href=\"https://www.fri.uni-lj.si/sl/dogodek/slovenski-okusi-na-fri/\"")
        val events = FRIParser.parse(context(FakeWeb(mapOf("https://fri.uni-lj.si/sl/koledar-dogodkov" to linked))), today)
        val event = events.single { it.title == "Slovenski okusi na FRI" }
        assertEquals("https://fri.uni-lj.si/sl/dogodek/slovenski-okusi-na-fri", event.eventUrl)
        assertEquals("fri-0dd4e911e9b6cad40f42721c65df2b9c5aedb6bc", event.externalId)
    }

    @Test
    fun `a page without the calendar fails`() {
        val web = FakeWeb(mapOf("https://fri.uni-lj.si/sl/koledar-dogodkov" to "<html><body>Napaka</body></html>"))
        assertFailsWith<NoSuchElementException> { FRIParser.parse(context(web), URI("https://fri.uni-lj.si")) }
    }

    @Test
    fun `picks the year closest to today, across New Year`() {
        val december = LocalDate.of(2026, 12, 20)
        val january = LocalDate.of(2027, 1, 5)
        assertEquals(LocalDate.of(2026, 11, 16), FRIParser.dateOf(DayMonth("16.", "nov"), LocalDate.of(2026, 11, 11)))
        assertEquals(LocalDate.of(2027, 1, 8), FRIParser.dateOf(DayMonth("8.", "jan"), december))
        assertEquals(LocalDate.of(2026, 12, 28), FRIParser.dateOf(DayMonth("28.", "dec"), january))
        assertEquals(LocalDate.of(2026, 5, 1), FRIParser.dateOf(DayMonth("1.", "maj"), LocalDate.of(2026, 4, 1)))
        assertEquals(LocalDate.of(2026, 8, 31), FRIParser.dateOf(DayMonth("31.", "avg"), LocalDate.of(2026, 8, 1)))
        assertNull(FRIParser.dateOf(DayMonth("16.", "xyz"), december))
    }

    @Test
    fun `reads both layouts of a card and the hour`() {
        fun card(html: String) = Jsoup.parse(html).selectFirst(".dogodek-item")!!
        assertEquals(DayMonth("16.", "nov") to null, FRIParser.dates(card(single("16.", "nov", "x", "X", "ob 10:00"))))
        assertEquals(DayMonth("24.", "nov") to DayMonth("26.", "nov"), FRIParser.dates(card(multiDay)))
        assertNull(FRIParser.dates(card(broken)))
        assertEquals(LocalTime.of(17, 15), FRIParser.timeIn("ob 17:15"))
        assertEquals(LocalTime.of(9, 0), FRIParser.timeIn("ob 9.00"))
        assertNull(FRIParser.timeIn(""))
    }

    @Test
    fun `takes FRI's own host only, not its subdomains`() {
        assertTrue(FRIParser.accepts(SourceField.WEBSITE, URI("https://fri.uni-lj.si")))
        assertTrue(FRIParser.accepts(SourceField.WEBSITE, URI("https://www.fri.uni-lj.si/sl")))
        assertFalse(FRIParser.accepts(SourceField.WEBSITE, URI("https://datascience.fri.uni-lj.si")))
    }
}
