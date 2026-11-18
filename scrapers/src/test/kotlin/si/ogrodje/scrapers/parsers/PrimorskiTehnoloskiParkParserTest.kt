package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrimorskiTehnoloskiParkParserTest {

    private fun ld(name: String, slug: String, start: String, end: String?) =
        """{"@context":"http://schema.org","@type":"Event","name":"$name","description":"&lt;p&gt;Opis&lt;/p&gt;",
        "url":"https://www.primorski-tp.si/dogodek/$slug/","startDate":"$start"${end?.let { ",\"endDate\":\"$it\"" } ?: ""}}"""

    private val page = """<html><head>
        <script type="application/ld+json" class="yoast-schema-graph">{"@context":"https://schema.org","@graph":[]}</script>
        </head><body>
        <script type="application/ld+json">[${ld("Optimizacija davčne osnove 2026", "optimizacija", "2026-11-11T15:00:00+01:00", "2026-11-11T17:00:00+01:00")},
        ${ld("Brez konca", "brez-konca", "2026-10-01T09:00:00+02:00", null)},
        ${ld("Star dogodek", "star", "2026-08-01T09:00:00+02:00", null)}]</script>
        </body></html>"""

    private val events = PrimorskiTehnoloskiParkParser.parse(
        context(FakeWeb(mapOf("https://www.primorski-tp.si/vsi-dogodki/" to page))),
        URI("https://www.primorski-tp.si"),
        Instant.parse("2026-11-18T10:00:00Z"),
    )

    @Test
    fun `reads the JSON-LD events from two months ago on`() {
        assertEquals(listOf("Optimizacija davčne osnove 2026", "Brez konca"), events.map { it.title })
        val event = events.first()
        assertEquals(Instant.parse("2026-11-11T14:00:00Z"), event.start)
        assertEquals(Instant.parse("2026-11-11T16:00:00Z"), event.end)
        assertTrue(event.hasStartTime && event.hasEndTime)
        assertEquals("&lt;p&gt;Opis&lt;/p&gt;", event.description)
        assertEquals("https://www.primorski-tp.si/dogodek/optimizacija/", event.eventUrl)
        assertEquals(event.eventUrl, event.sourceUrl)
        assertNull(events[1].end)
    }

    @Test
    fun `without the events calendar's API, its events are nowhere`() {
        assertTrue(events.all { it.locationName == null && it.locationAddress == null })
    }

    @Test
    fun `places its events by the events calendar's API, its venues or where their text says`() {
        val web = FakeWeb(
            mapOf(
                "https://www.primorski-tp.si/vsi-dogodki/" to page,
                "https://www.primorski-tp.si/wp-json/tribe/events/v1/events?start_date=2026-09-18&per_page=50&page=1" to
                    FakeWeb.fixture("ptp-tribe-events.json").replace("optimizacija-davcne-osnove-2026", "optimizacija"),
            )
        )
        val event = PrimorskiTehnoloskiParkParser.parse(context(web), URI("https://www.primorski-tp.si"), Instant.parse("2026-11-18T10:00:00Z")).first()
        assertEquals("soba N19, prvo nadstropje Primorskega tehnološkega parka", event.locationName)
        assertNull(event.locationAddress)

        // The API as it was on 18 November 2026
        val api = Web.MAPPER.readTree(FakeWeb.fixture("ptp-tribe-events.json")).path("events").values().associateBy { it.get("title").stringValue() }
        fun place(title: String) = PrimorskiTehnoloskiParkParser.placeOf(api.getValue(title))
        assertEquals(Location("Epic Nova Gorica", "Kolodvorska pot 10, Nova Gorica"), place("FUCKUP NIGHTS: PRIMORSKA EDITION 26"))
        assertEquals(
            Location("Primorski tehnološki park", "Mednarodni prehod 6, 5290 Šempeter pri Gorici"),
            place("Founders Talk: Jure Adlešič, ErgoHide d. o. o."),
        )
        assertEquals(
            Location("Primorski tehnološki park, prvo nadstropje, soba N19", "Mednarodni prehod 6, Vrtojba"),
            place("TikTok za podjetnike: Moč sodobne znamke"),
        )
        assertEquals(Location("Šolski center Nova Gorica"), place("Startup izziv: Od ideje do podjetja"))
        assertEquals(Location.NONE, place("Web Summit"))
    }

    @Test
    fun `its id is the sha1 of its url`() {
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest("https://www.primorski-tp.si/dogodek/optimizacija/".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("ptp-$sha1", events.first().externalId)
    }

    @Test
    fun `takes primorski-tp si's urls only`() {
        assertTrue(PrimorskiTehnoloskiParkParser.accepts(SourceField.WEBSITE, URI("https://www.primorski-tp.si/")))
        assertFalse(PrimorskiTehnoloskiParkParser.accepts(SourceField.WEBSITE, URI("https://www.tp-lj.si/")))
    }
}
