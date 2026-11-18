package si.ogrodje.scrapers.parsers

import org.jsoup.Jsoup
import si.ogrodje.scrapers.Web
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Locations as the sources write them (real ones, from the sources' pages and calendars) */
class LocationsTest {
    private fun parse(text: String) = Locations.parse(text)

    @Test
    fun `splits one line into the venue and its address, without Slovenia`() {
        assertEquals(
            Location("Kongresni center Brdo", "Predoslje 39, 4000 Kranj"),
            parse("Kongresni center Brdo, Predoslje 39, 4000 Kranj, Slovenija"),
        )
        assertEquals(Location("D'place", "Leskoškova cesta 3, 1000 Ljubljana"), parse("D'place, Leskoškova cesta 3, 1000 Ljubljana, Slovenija"))
        assertEquals(Location("Sodni stolp", "Pristan 8, Maribor"), parse("Sodni stolp, Pristan 8, Maribor"))
        assertEquals(Location("Katapult", "Gabrsko 12, Trbovlje"), parse("Katapult, Gabrsko 12, Trbovlje"))
        // A company's name with commas in it
        assertEquals(
            Location("Avtenta, napredne poslovne rešitve, d.o.o.", "Stegne 19, 1000 Ljubljana"),
            parse("Avtenta, napredne poslovne rešitve, d.o.o., Stegne 19, 1000 Ljubljana, Slovenija"),
        )
        assertEquals(
            Location("Multimedijska predavalnica, Institut »Jožef Stefan«", "Teslova 30, Ljubljana"),
            parse("Multimedijska predavalnica, Institut »Jožef Stefan«, Teslova 30, Ljubljana"),
        )
        // Abroad, the country stays
        assertEquals(Location("BMW Welt", "Am Olympiapark 1, 80809 München, Nemčija"), parse("BMW Welt, Am Olympiapark 1, 80809 München, Nemčija"))
    }

    @Test
    fun `an address alone has no name, and a name alone no address`() {
        assertEquals(Location(null, "Baragova ulica 5, 1000 Ljubljana"), parse("Baragova ulica 5, 1000 Ljubljana, Slovenija"))
        assertEquals(Location(null, "Ameriška ulica, 1000 Ljubljana"), parse("Ameriška ulica, 1000 Ljubljana, Slovenija"))
        assertEquals(Location("GZS, dvorana C", null), parse("GZS, dvorana C"))
        assertEquals(Location("Grand Palais, Pariz", null), parse("Grand Palais, Pariz"))
        assertEquals(Location("Cankarjev dom", null), parse("Cankarjev dom, Slovenia"))
        assertEquals(Location("Kovačnica - podjetniški inkubator Kranj", null), parse("Kovačnica - podjetniški inkubator Kranj"))
        assertEquals(Location.NONE, parse("  , "))
    }

    @Test
    fun `a city its postcode names isn't repeated`() {
        assertEquals(Location("Saša Inkubator", "Šaleška cesta 2A, 3320 Velenje"), parse("Saša Inkubator, Šaleška cesta 2A, Velenje, 3320 Velenje"))
        assertEquals(
            Location("Koroška KGZ", "Trg 67, 2391 Prevalje"),
            parse("Koroška KGZ, Prevalje, Trg 67, 2391 Prevalje"),
        )
    }

    @Test
    fun `rooms after the address, and in brackets, are the place's`() {
        assertEquals(
            Location("Fakulteta za računalništvo in informatiko, predavalnica P03", "Večna pot 113, 1000 Ljubljana"),
            parse("Fakulteta za računalništvo in informatiko, Večna pot 113, 1000 Ljubljana, predavalnica P03"),
        )
        assertEquals(
            Location("Obrtno-podjetniška zbornica Slovenije, Velika sejna soba Obrtnik", "Celovška cesta 71, Ljubljana"),
            parse("Obrtno-podjetniška zbornica Slovenije, Celovška cesta 71, Ljubljana (Velika sejna soba Obrtnik)"),
        )
        assertEquals(
            Location("PC Vrelec, Sejna soba v 1. nadstropju", "Celjska cesta 30, 3250 Rog. Slatina"),
            parse("PC Vrelec, Celjska cesta 30, 3250 Rog. Slatina – Sejna soba v 1. nadstropju"),
        )
        assertEquals(Location("MPI Vrelec", "Prvomajska ulica 35"), parse("MPI Vrelec – Prvomajska ulica 35"))
        assertEquals(
            Location("Vrt Celjskega mladinskega centra", "Mariborska cesta 2, Celje"),
            parse("Vrt Celjskega mladinskega centra (Mariborska cesta 2, Celje)"),
        )
    }

    @Test
    fun `online is Online, with no address`() {
        for (text in listOf(
            "Online", "Zoom dogodek", "Na spletu", "Na spletu (Zoom)", "Spletni seminar", "Spletni dogodek", "Preko spleta",
            "Microsoft Teams", "https://us02web.zoom.us/j/123", "Online event", "Webinar",
        )) {
            assertEquals(Location.ONLINE, parse(text), text)
        }
        assertFalse(Locations.isOnline("Spletne urice, Vetrinjc"))
        assertFalse(Locations.isOnline("BarCoda, Vetrinjc"))
        assertEquals(Location.ONLINE, Locations.of("Online event", ""))
    }

    @Test
    fun `a venue apart from its address doesn't repeat its name`() {
        // Luma's geo_address_info
        assertEquals(
            Location("Celtra, razvoj informacijskih tehnologij, d.o.o.", "Kongresni trg 3, 1000 Ljubljana"),
            Locations.of(
                "Celtra, razvoj informacijskih tehnologij, d.o.o.",
                "Celtra, razvoj informacijskih tehnologij, d.o.o., Park Zvezda, Kongresni trg 3, 1000 Ljubljana, Slovenia",
            ),
        )
        // Meetup's venues
        assertEquals(Location("Studio Dansa", "Vilharjeva cesta 3, 1000 Ljubljana"), Locations.of("Studio Dansa", "Vilharjeva cesta 3, 1000 Ljubljana, Slovenia, Ljubljana"))
        assertEquals(Location(null, "Vilharjeva cesta 40, Ljubljana"), Locations.of("Vilharjeva cesta 40", "Vilharjeva cesta 40, Ljubljana"))
        assertEquals(Location("Visionect office", "Trzaska cesta 118, Ljubljana"), Locations.of("Visionect office", Locations.address("Trzaska cesta 118,", "Ljubljana")))
        assertEquals(
            Location("Knjižnica Mirana Jarca", "Rozmanova ulica 28, 8000 Novo mesto"),
            Locations.of("Knjižnica Mirana Jarca, Novo mesto", "Rozmanova ulica 28, 8000 Novo mesto, Slovenija"),
        )
        assertEquals(Location("Kino Šiška", null), Locations.of("Kino Šiška", "Kino Šiška"))
        // The venue in English, its address with its Slovenian name
        assertEquals(
            Location("Faculty of Computer and Information Science", "Večna pot 113, 1000 Ljubljana"),
            Locations.of("Faculty of Computer and Information Science", "Fakulteta za računalništvo in informatiko, Večna pot 113, 1000 Ljubljana, Slovenia"),
        )
        // A hybrid event (Indico's "Online and on premise") is at its place
        assertEquals(
            Location("Fakulteta za informacijske študije", "Ljubljanska cesta 31a, 8000 Novo mesto"),
            Locations.of("Online and on premise, Fakulteta za informacijske študije", "Ljubljanska cesta 31a, 8000 Novo mesto"),
        )
        assertEquals(Location("Epilog", null), Locations.of(" Epilog&nbsp;", null))
    }

    @Test
    fun `an address of its parts`() {
        assertEquals("Tržaška cesta 2, 1000 Ljubljana", Locations.address("Tržaška cesta 2", Locations.postcodeCity("1000", "Ljubljana"), "Slovenia"))
        assertEquals("Velika pot 33, 5250 Solkan", Locations.address("Velika pot 33", Locations.postcodeCity("5000", "5250 Solkan")))
        assertEquals("Ljubljana", Locations.address(null, Locations.postcodeCity(null, "Ljubljana"), ""))
        assertEquals(null, Locations.address(null, " ", "SI"))
    }

    private fun event(json: String) = Web.MAPPER.readTree(json)

    @Test
    fun `reads schema org's places, addresses and virtual locations`() {
        val place = event(
            """{"@type":"Event","location":{"@type":"Place","name":"Pomurski tehnološki park",
            "address":{"@type":"PostalAddress","streetAddress":"Plese 9a","postalCode":"9000","addressLocality":"Murska Sobota",
            "addressCountry":{"@type":"Country","name":"Slovenija"}}}}""",
        )
        assertEquals(Location("Pomurski tehnološki park", "Plese 9a, 9000 Murska Sobota"), Locations.fromJsonLd(place))

        val text = event("""{"@type":"Event","location":{"@type":"Place","name":"Kovačnica","address":"Poštna ulica 4, 4000 Kranj, Slovenija"}}""")
        assertEquals(Location("Kovačnica", "Poštna ulica 4, 4000 Kranj"), Locations.fromJsonLd(text))

        val virtual = event("""{"@type":"Event","location":{"@type":"VirtualLocation","url":"https://zoom.us/j/1"}}""")
        assertEquals(Location.ONLINE, Locations.fromJsonLd(virtual))
        val mode = event("""{"@type":"Event","eventAttendanceMode":"https://schema.org/OnlineEventAttendanceMode"}""")
        assertEquals(Location.ONLINE, Locations.fromJsonLd(mode))

        val hybrid = event(
            """{"@type":"Event","location":[{"@type":"VirtualLocation","url":"https://zoom.us/j/1"},
            {"@type":"Place","name":"LUI","address":{"streetAddress":"Vojkova cesta 63","addressLocality":"Ljubljana"}}]}""",
        )
        assertEquals(Location("LUI", "Vojkova cesta 63, Ljubljana"), Locations.fromJsonLd(hybrid))
        assertEquals(Location.NONE, Locations.fromJsonLd(event("""{"@type":"Event","name":"Nowhere"}""")))
    }

    @Test
    fun `finds the events in a page's JSON-LD, in lists and graphs too`() {
        val page = Jsoup.parse(
            """<script type="application/ld+json">{"@graph":[{"@type":"WebPage"},{"@type":"Event","name":"A"}]}</script>
            <script type="application/ld+json">[{"@type":"Event","name":"B"},{"@type":"BusinessEvent","name":"C"}]</script>
            <script type="application/ld+json">{ broken</script>""",
        )
        assertEquals(listOf("A", "B", "C"), Locations.jsonLdEvents(page).map { it.get("name").stringValue() })
    }

    @Test
    fun `tells the parts of an address`() {
        for (part in listOf("Tržaška cesta 2", "1000 Ljubljana", "Na Strži 2097/63", "Šaleška cesta 2A", "Ameriška ulica", "Beograd 11000")) {
            assertTrue(Locations.isAddressPart(part), part)
        }
        for (part in listOf("Kovačnica", "13|37 Ljubljana", "Lecture room 3", "dvorana B", "Situla building - 1st floor", "Ljubljana")) {
            assertFalse(Locations.isAddressPart(part), part)
        }
    }
}
