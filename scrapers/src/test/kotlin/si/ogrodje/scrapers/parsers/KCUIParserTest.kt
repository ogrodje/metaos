package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KCUIParserTest {

    private fun detail(title: String, lede: String, whenText: String, where: String) =
        """<!DOCTYPE html><html><body>
        <h1 class="text-3xl font-bold">$title</h1>
        <p class="text-xl text-muted">$lede</p>
        <div class="bg-surface rounded-lg border p-6">
          <div class="flex items-start gap-4">
            <span class="block text-sm font-semibold uppercase tracking-wider">Kdaj</span>
            <span class="text-base text-muted">$whenText</span>
          </div>
          <div class="flex items-start gap-4">
            <span class="block text-sm font-semibold uppercase tracking-wider">Kje</span>
            <span class="text-base text-muted">$where</span>
          </div>
        </div>
        </body></html>"""

    private val listing = """<!DOCTYPE html><html><body>
        <section><h2>Aktualna izobraževanja</h2>
          <a class="group" href="/izobrazevanja/agentna-ui">Agentna UI</a>
          <a class="group" href="/izobrazevanja/brez-ure">Brez ure</a>
          <a class="group" href="/izobrazevanja/pokvarjen">Pokvarjen</a>
        </section>
        <section><h2>Pretekla izobraževanja (1)</h2>
          <a class="group" href="/izobrazevanja/inferencna-ui">Inferenčna UI</a>
        </section>
        <footer>
          <a href="/izobrazevanja">Vsa izobraževanja</a>
          <a href="/o-nas">O nas</a>
          <a href="https://kcui.si/izobrazevanja/agentna-ui">Duplicate absolute link</a>
        </footer>
        </body></html>"""

    private val web = FakeWeb(
        mapOf(
            "https://kcui.si/izobrazevanja" to listing,
            "https://kcui.si/izobrazevanja/agentna-ui" to detail(
                "Agentna umetna inteligenca: od pričakovanj do resničnih učinkov",
                "Spoznajte prehod od pasivnih digitalnih orodij k avtonomnim AI agentom.",
                "Petek, 17. april 2026 ob 10:00",
                "Spletni dogodek",
            ),
            "https://kcui.si/izobrazevanja/inferencna-ui" to detail(
                "Inferenčna UI infrastruktura za rast",
                "Posvet za oblikovanje arhitekture slovenske inferenčne UI infrastrukture.",
                "Sreda, 10. junij 2026 ob 10:00",
                "GZS, dvorana G, Dimičeva ulica 13, Ljubljana",
            ),
            "https://kcui.si/izobrazevanja/brez-ure" to detail(
                "Dogodek brez ure", "Ta dogodek nima navedene ure.", "Torek, 2. junij 2026", "Zoom dogodek",
            ),
            "https://kcui.si/izobrazevanja/pokvarjen" to "<html><body><h1>Pokvarjen</h1></body></html>",
        )
    )

    private val events = KCUIParser.parse(context(web), URI("https://kcui.si"))

    @Test
    fun `reads current and past events from the listing, without a page that doesn't read`() {
        assertEquals(3, events.size)
        assertTrue(events.any { it.title.startsWith("Agentna umetna inteligenca") })
        assertTrue(events.any { it.title.startsWith("Inferenčna UI infrastruktura") })
        assertTrue(events.all { it.source == Source.KCUI && it.sourceUrl == "https://kcui.si/izobrazevanja" })
    }

    @Test
    fun `reads the day, time and place from an event's page`() {
        val event = events.single { it.title.startsWith("Agentna") }
        assertEquals(LocalDateTime.of(2026, 4, 17, 10, 0), event.start.atZone(LJUBLJANA).toLocalDateTime())
        assertTrue(event.hasStartTime)
        assertFalse(event.hasEndTime)
        assertEquals("Online", event.locationName)
        assertEquals(null, event.locationAddress)
        assertEquals("https://kcui.si/izobrazevanja/agentna-ui", event.eventUrl)
        assertTrue(event.description!!.contains("avtonomnim AI agentom"))
    }

    @Test
    fun `places the saved pages' events`() {
        // Three trainings' pages, saved on 18 November 2026
        val pages = mapOf("ui-kot-pomocnik" to "kcui-ui-kot-pomocnik.html", "razumevanje-llm" to "kcui-razumevanje-llm.html", "trendi" to "kcui-trendi-dostopnosti.html")
        val web = FakeWeb(
            mapOf("https://kcui.si/izobrazevanja" to pages.keys.joinToString("") { """<a href="/izobrazevanja/$it">x</a>""" }) +
                pages.map { (slug, file) -> "https://kcui.si/izobrazevanja/$slug" to FakeWeb.fixture(file) },
        )
        val events = KCUIParser.parse(context(web), URI("https://kcui.si/")).associateBy { it.eventUrl!!.substringAfterLast('/') }
        // "Gospodarska zbornica Slovenije, Dimičeva 13, 1000 Ljubljana, dvorana A (hibridno)"
        assertEquals("Gospodarska zbornica Slovenije, dvorana A (hibridno)", events.getValue("ui-kot-pomocnik").locationName)
        assertEquals("Dimičeva 13, 1000 Ljubljana", events.getValue("ui-kot-pomocnik").locationAddress)
        assertEquals("Fakulteta za računalništvo in informatiko, predavalnica P03", events.getValue("razumevanje-llm").locationName)
        assertEquals("Večna pot 113, 1000 Ljubljana", events.getValue("razumevanje-llm").locationAddress)
        // "Na spletu (Zoom)"
        assertEquals("Online", events.getValue("trendi").locationName)
        assertEquals(null, events.getValue("trendi").locationAddress)
    }

    @Test
    fun `an event without a time is all day`() {
        val event = events.single { it.title == "Dogodek brez ure" }
        assertFalse(event.hasStartTime)
        assertEquals(LocalDateTime.of(2026, 6, 2, 0, 0), event.start.atZone(LJUBLJANA).toLocalDateTime())
    }

    @Test
    fun `IDs are the sha1 of the event's URL`() {
        assertTrue(events.all { it.externalId.matches(Regex("kcui-[0-9a-f]{40}")) })
        assertEquals(events.size, events.map { it.externalId }.toSet().size)
    }

    @Test
    fun `takes kcui si's urls`() {
        assertTrue(KCUIParser.accepts(SourceField.WEBSITE, URI("https://www.kcui.si/")))
        assertFalse(KCUIParser.accepts(SourceField.WEBSITE, URI("https://www.gzs.si/")))
    }
}
