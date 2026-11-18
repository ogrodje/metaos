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

class SpletneUriceParserTest {

    private val host = "https://wwwhmb.si"

    // Since 2026: a subtitle paragraph with the year
    private fun newFormat(whenIs: String, lede: String) = """<div class="wp-block-group is-content-justification-center is-nowrap is-layout-flex">
        <p class="has-text-align-center is-style-text-subtitle is-style-text-subtitle--1 wp-block-paragraph" style="font-weight:700">$whenIs</p>
        <p class="has-text-align-center wp-block-paragraph">(<a href="https://www.google.com/maps/dir//Vetrinjc+BarCoda">Kam?</a>)</p>
        </div>
        <p class="is-style-default has-x-large-font-size wp-block-paragraph"><em>$lede</em></p>
        <p class="wp-block-paragraph">Pri programiranju ima AI jasno povratno informacijo.</p>"""

    // Before 2026: a heading without the year, then a "+ + +" divider
    private fun oldFormat(whenIs: String, place: String, lede: String) = """<h2 class="wp-block-heading has-accent-color has-text-color">$whenIs <br><strong>// $place //</strong></h2>
        <p class="has-text-align-left wp-block-paragraph">+ + + + + + + + + + + + + + + + + + + + + + + + + + + + + + + +</p>
        <div style="height:16px" aria-hidden="true" class="wp-block-spacer"></div>
        <p class="is-style-default wp-block-paragraph"><strong>$lede</strong></p>"""

    private fun post(id: Long, date: String, slug: String, title: String, content: String) = mapOf(
        "id" to id, "date" to date, "link" to "$host/$slug/",
        "title" to mapOf("rendered" to title), "content" to mapOf("rendered" to content),
    )

    private val lede = "Programirati z AI zna že skoraj vsak. A kaj, ko je treba z njim voditi še podjetje?"

    private val posts = listOf(
        post(2432, "2026-11-11T14:17:05", "176-firma-na-avtopilotu", "#176: Firma na avtopilotu?", newFormat("1. december 2026 ob 19:05 // BarCoda, Vetrinjc", lede)),
        post(2398, "2026-05-25T11:53:35", "173-skletne-urice", "#173: S&#8217;kletne Urice!", newFormat("2. junij 2026 ob 19:05 // KGB, Maribor", "Poletni zaključek sezone v kletnih prostorih kluba KGB.")),
        post(
            2052, "2026-03-03T19:05:23", "170-od-maribora-do-orbite", "#170: Od Maribora do orbite",
            oldFormat("torek, 3. marec @ 19:05", "Vetrinjc &#8211; BarCoda, Mb", "Razvoj programske opreme ni več samo zemeljski. Inženirji pišejo kodo, ki zakroži okoli Zemlje."),
        ),
        // Published in November, the talk is in December of the same year
        post(1900, "2025-11-24T10:00:00", "167-pure", "#167: Pure: od prvega swipa", oldFormat("torek, 2. december @ 19:05", "Vetrinjc &#8211; BarCoda, Mb", "Kako je nastala aplikacija in kaj smo se pri tem naučili.")),
        // Published in late December, the talk is in January of the next year
        post(1880, "2024-12-20T09:00:00", "159-rootkiti", "#159: Rootkiti", oldFormat("torek, 7. januar @ 19:05", "Pisarna, Mb", "Kako globoko v sistem seže napadalec in kako ga od tam spravimo ven.")),
        // An old post that later got a copied 2026 subtitle: not believable, skipped
        post(900, "2022-02-15T10:00:00", "137-zero-day-design", "#137: Zero Day Design", newFormat("3. marec 2026 ob 19:05 // BarCoda, Vetrinjc", "Startup playbook za oblikovalce, ki gradijo produkte od nule.")),
        // No date line at all: skipped
        post(880, "2022-04-05T10:00:00", "wwwhmb-retrospektiva", "#wwwhmb RETROspektiva", "<p>V duhu zaključevanja kvartalov.</p>"),
    )

    private val web = FakeWeb(mapOf("$host/wp-json/wp/v2/posts?categories=2&per_page=20&" to Web.MAPPER.writeValueAsString(posts)))

    private fun events() = SpletneUriceParser.parse(context(web), URI(host))

    private fun local(event: ScrapedEvent) = event.start.atZone(LJUBLJANA)

    @Test
    fun `reads talks from the predavanja category, skipping posts without a believable date`() {
        val events = events()
        assertEquals(
            setOf("spletne-urice-2432", "spletne-urice-2398", "spletne-urice-2052", "spletne-urice-1900", "spletne-urice-1880"),
            events.map { it.externalId }.toSet(),
        )
        assertTrue(events.all { it.source == Source.SPLETNE_URICE && it.hasStartTime && !it.hasEndTime && it.end == null })
    }

    @Test
    fun `reads the new format, a date with its year, time, place and lede`() {
        val event = events().single { it.externalId == "spletne-urice-2432" }
        assertEquals("#176: Firma na avtopilotu?", event.title)
        assertEquals(LocalDate.of(2026, 12, 1), local(event).toLocalDate())
        assertEquals(LocalTime.of(19, 5), local(event).toLocalTime())
        assertEquals(3600, local(event).offset.totalSeconds)
        assertEquals("BarCoda, Vetrinjc", event.locationName)
        assertEquals(lede, event.description)
        assertEquals("$host/176-firma-na-avtopilotu/", event.eventUrl)
        assertEquals("$host/predavanja/", event.sourceUrl)
    }

    @Test
    fun `reads the old format, in summer time, and decodes the title`() {
        val events = events()
        val summer = events.single { it.externalId == "spletne-urice-2398" }
        val old = events.single { it.externalId == "spletne-urice-2052" }
        assertEquals("#173: S’kletne Urice!", summer.title)
        assertEquals(7200, local(summer).offset.totalSeconds)
        assertEquals("KGB, Maribor", summer.locationName)
        assertEquals(LocalDate.of(2026, 3, 3), local(old).toLocalDate())
        assertEquals("Vetrinjc – BarCoda, Mb", old.locationName)
        assertTrue(old.description!!.startsWith("Razvoj programske opreme"))
    }

    @Test
    fun `infers the missing year from the publish date, across New Year`() {
        val events = events()
        assertEquals(LocalDate.of(2025, 12, 2), local(events.single { it.externalId == "spletne-urice-1900" }).toLocalDate())
        assertEquals(LocalDate.of(2025, 1, 7), local(events.single { it.externalId == "spletne-urice-1880" }).toLocalDate())
    }

    @Test
    fun `reads the date line in the forms the site has used`() {
        val published = LocalDateTime.of(2021, 5, 4, 10, 0)
        SpletneUriceParser.talkFrom("<p>11. maj @ 18:30 izjemoma!! //ONLINE// Vsak dan proizvedemo več vsebine.</p>", published)!!.let {
            assertEquals(LocalDateTime.of(2021, 5, 11, 18, 30), it.start)
            assertEquals("ONLINE", it.location)
        }
        assertEquals(LocalDateTime.of(2022, 1, 11, 19, 5), SpletneUriceParser.talkFrom("<p>11. JANUAR @ 19:05 //ONLINE//</p>", LocalDateTime.of(2022, 1, 5, 10, 0))?.start)
        SpletneUriceParser.talkFrom("<p>torek , 10. oktober @ 19:05 // Pisarna, Mb //</p>", LocalDateTime.of(2023, 10, 2, 10, 0))!!.let {
            assertEquals(LocalDate.of(2023, 10, 10), it.start.toLocalDate())
            assertEquals("Pisarna, Mb", it.location)
        }
        assertNull(SpletneUriceParser.talkFrom("<p>Prijave do 15. maja.</p>", published))
        assertNull(SpletneUriceParser.talkFrom("<p>30. februar 2026 ob 19:05 // BarCoda</p>", LocalDateTime.of(2026, 2, 1, 10, 0)))
    }

    @Test
    fun `takes wwwhmb's urls`() {
        assertTrue(SpletneUriceParser.accepts(SourceField.WEBSITE, URI("https://wwwhmb.si/")))
        assertFalse(SpletneUriceParser.accepts(SourceField.WEBSITE, URI("https://hmb.si/")))
    }
}
