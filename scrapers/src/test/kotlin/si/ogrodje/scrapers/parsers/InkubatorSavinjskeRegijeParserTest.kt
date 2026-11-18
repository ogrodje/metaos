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

class InkubatorSavinjskeRegijeParserTest {

    private val host = "https://www.inkubatorsr.si"

    // As Elementor renders it: each field of the event is a `.date` widget, and the menu shows the newest event's day
    // the same way on every page, which isn't this event's
    private fun detail(date: String, location: String, description: String) =
        """<!DOCTYPE html><html><head><meta name="description" content="$description" /></head><body>
        <nav><div class="elementor-element date elementor-widget elementor-widget-text-editor">
          <div class="elementor-widget-container">31. 12. 2099</div></div></nav>
        <main>
          <div class="elementor-element date elementor-widget elementor-widget-text-editor">
            <div class="elementor-widget-container">$date</div></div>
          <div class="e-con-full e-flex e-con e-child">
            <div class="elementor-element date elementor-widget elementor-widget-text-editor">
              <div class="elementor-widget-container"><p>Lokacija dogodka</p></div></div>
            <div class="elementor-element date elementor-widget elementor-widget-text-editor">
              <div class="elementor-widget-container">$location</div></div>
          </div>
        </main></body></html>"""

    private fun post(id: Long, slug: String, title: String, content: String) = mapOf(
        "id" to id,
        "link" to "$host/dogodki/$slug/",
        "title" to mapOf("rendered" to title),
        "content" to mapOf("rendered" to content),
    )

    // A full first page, then a second; the third isn't read, as only the two newest are
    private val firstPage = listOf(
        post(
            5112, "klub-ai", "KLUB AI NAVDU&#352;ENCEV: Od AI pomo&#269;nikov do avtonomnih agentov",
            "<p><strong> 📅 26. november 2026 | 🕘 9.00 &#8211; 10.30 | 📍 Inkubator Savinjske regije</strong></p>",
        ),
        post(5104, "pitchaj", "Pitchaj prepričljivo", "<p>📅 12. november 2026 | 🕓 16.00–19.00 | 📍 OOZ Celje</p>"),
    ) + (1..18).map { post(4000L + it, "brez-strani-$it", "Brez strani $it", "<p>ob 10.00</p>") }

    private val secondPage = listOf(
        post(5090, "brez-ure", "Dogodek brez ure", "<p>Ideja lahko zveni odlično. Ampak ali se številke izidejo?</p>"),
        post(5080, "pokvarjen", "Pokvarjen dogodek", "<p>📅 1. november 2026</p>"),
    ) + (1..18).map { post(3000L + it, "brez-strani-2-$it", "Brez strani 2 $it", "<p>ob 10.00</p>") }

    private fun api(page: Int) = "https://inkubatorsr.si/wp-json/wp/v2/dogodki?per_page=20&page=$page&_fields=id,link,title,content"

    private val web = FakeWeb(
        mapOf(
            api(1) to Web.MAPPER.writeValueAsString(firstPage),
            api(2) to Web.MAPPER.writeValueAsString(secondPage),
            api(3) to Web.MAPPER.writeValueAsString(listOf(post(1000, "na-tretji-strani", "Star dogodek", "<p>ob 10.00</p>"))),
            "$host/dogodki/klub-ai/" to detail("26. 11. 2026", "Inkubator Savinjske regije", "Mesečna srečanja o AI."),
            "$host/dogodki/pitchaj/" to detail("12. 11. 2026", "OOZ Celje, Cesta na Ostrožno 4, Celje", "Pitch delavnica."),
            "$host/dogodki/brez-ure/" to detail("05. 11. 2026", "Inkubator Savinjske regije", "Dogodek brez ure."),
            "$host/dogodki/pokvarjen/" to "<html><body><h1>Nič</h1></body></html>",
            "$host/dogodki/na-tretji-strani/" to detail("01. 01. 2024", "Inkubator Savinjske regije", "Stran 3."),
        )
    )

    // The organisation's website has no www, its posts' links do
    private val events = InkubatorSavinjskeRegijeParser.parse(context(web), URI("https://inkubatorsr.si"))

    @Test
    fun `reads the two newest pages of posts, without pages that don't read`() {
        assertEquals(
            setOf("KLUB AI NAVDUŠENCEV: Od AI pomočnikov do avtonomnih agentov", "Pitchaj prepričljivo", "Dogodek brez ure"),
            events.map { it.title }.toSet(),
        )
        assertEquals(setOf("inkubatorsr-5112", "inkubatorsr-5104", "inkubatorsr-5090"), events.map { it.externalId }.toSet())
        assertTrue(events.all { it.source == Source.INKUBATOR_SAVINJSKE_REGIJE })
        assertFalse(web.asked.contains(api(3)))
    }

    @Test
    fun `reads the event's own day, not the menu's, its times and place`() {
        val event = events.single { it.title.startsWith("KLUB AI") }
        val start = event.start.atZone(LJUBLJANA)
        assertEquals(LocalDate.of(2026, 11, 26), start.toLocalDate())
        assertEquals(LocalTime.of(9, 0), start.toLocalTime())
        assertEquals(3600, start.offset.totalSeconds)
        assertEquals(LocalTime.of(10, 30), event.end!!.atZone(LJUBLJANA).toLocalTime())
        assertTrue(event.hasStartTime && event.hasEndTime)
        assertEquals("Inkubator Savinjske regije", event.locationName)
        assertEquals("Mesečna srečanja o AI.", event.description)
        assertEquals("$host/dogodki/klub-ai/", event.eventUrl)
        assertEquals("https://inkubatorsr.si/aktualno/dogodki/", event.sourceUrl)
    }

    @Test
    fun `keeps a place other than the incubator`() {
        val event = events.single { it.title == "Pitchaj prepričljivo" }
        assertEquals("OOZ Celje, Cesta na Ostrožno 4, Celje", event.locationName)
        assertTrue(event.hasEndTime)
    }

    @Test
    fun `an event without a time is all day`() {
        val event = events.single { it.title == "Dogodek brez ure" }
        assertFalse(event.hasStartTime || event.hasEndTime)
        assertNull(event.end)
        assertEquals(LocalDate.of(2026, 11, 5), event.start.atZone(LJUBLJANA).toLocalDate())
    }

    @Test
    fun `a page that isn't full is the last`() {
        val web = FakeWeb(
            mapOf(
                api(1) to Web.MAPPER.writeValueAsString(firstPage.take(2)),
                "$host/dogodki/klub-ai/" to detail("26. 11. 2026", "Inkubator", "AI."),
                "$host/dogodki/pitchaj/" to detail("12. 11. 2026", "Celje", "Pitch."),
            )
        )
        assertEquals(2, InkubatorSavinjskeRegijeParser.parse(context(web), URI("https://inkubatorsr.si")).size)
        assertFalse(web.asked.contains(api(2)))
    }

    @Test
    fun `reads times as the site writes them, and never a date as a time`() {
        fun t(h: Int, m: Int) = LocalTime.of(h, m)
        fun times(text: String) = InkubatorSavinjskeRegijeParser.timesFrom(text)
        assertEquals(t(9, 0) to t(10, 30), times("📅 26. november 2026 | 🕘 9.00 – 10.30 | 📍 Inkubator"))
        assertEquals(t(14, 0) to null, times("📅 18. 2. 2026 | 🕘 14.00 | 📍 Inkubator"))
        assertEquals(t(10, 0) to t(13, 0), times("📅 Sreda, 10. september 2025 ⏰ 10.00–13.00 📍 Celje"))
        assertEquals(t(11, 0) to null, times("Termin: 9. oktober, ob 11:00 Cena: brezplačno"))
        assertEquals(t(14, 0) to t(17, 0), times("četrtek, 11. december 2025, od 14.00 do 17.00 ure"))
        assertEquals(t(9, 0) to t(10, 30), times("📅 24. avgust 2026 (9:00 – 10:30): 1. srečanje"))
        assertEquals(t(11, 30) to null, times("📅 May 27, 2026, at 11:30 📍 Celje"))
        assertEquals(t(10, 0) to t(15, 30), times("Date: 03.12.2025 Time: 10:00–15:30 Venue: Celje"))
        assertEquals(t(10, 0) to t(14, 0), times("Datumi srečanj: 16.03.2026, 17.03.2026 – od 10.00 – 14.00 ure"))
        assertEquals(t(9, 0) to null, times("ki bo v sredo, 16. julija 2025 ob 9.00. Kaj boš pridobil"))
        assertNull(times("Ideja lahko zveni odlično. 24.9.2025"))
        assertNull(times("od 16.03.2026 naprej"))
    }

    @Test
    fun `takes inkubatorsr si's urls`() {
        assertTrue(InkubatorSavinjskeRegijeParser.accepts(SourceField.WEBSITE, URI("https://inkubatorsr.si")))
        assertFalse(InkubatorSavinjskeRegijeParser.accepts(SourceField.WEBSITE, URI("https://inkubator-rs.si")))
    }
}
