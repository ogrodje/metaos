package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import si.ogrodje.scrapers.parsers.EventText.Details
import si.ogrodje.scrapers.parsers.EventText.details
import si.ogrodje.scrapers.parsers.EventText.linesOf
import si.ogrodje.scrapers.parsers.EventText.similarTitles
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordPressEventsParserTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun t(h: Int, m: Int) = LocalTime.of(h, m)
    private fun local(instant: Instant): ZonedDateTime = instant.atZone(LJUBLJANA)

    // LUI's posts, as it writes them (copied from live posts, trimmed)
    private val inline = """<p>Aktivnosti se izvajajo v okviru operacije JR podporno okolje 2026 – 2029.</p>
        <p><strong>KDAJ: </strong>27. oktober 2026, med 13:00 – 16:00<br><strong>KJE:</strong> LUI – Ljubljanski univerzitetni inkubator, Vojkova cesta 63, Ljubljana</p>
        <p>PRIJAVI SE</p>"""
    private val separate = """<p>Kdaj in kje:</p><p>Datum: torek, 13. oktober 2026</p><p>Čas: 14.00–17.00</p>
        <p>Lokacija: Ljubljanski univerzitetni inkubator (LUI), Vojkova cesta 63, Ljubljana</p>"""
    private val noYear =
        """<p><strong>Kdaj:</strong> Ponedeljek, 31. 8. ob 9.00 (do 12.00)<br><strong>Kje:</strong> Ljubljanski univerzitetni inkubator (LUI)</p>"""
    private val twoDays = """<p>Čas za sproščen pogovor in spoznavanje novih ljudi.</p>
        <p><strong>KDAJ:</strong> petek, 25. september in sobota, 26. september 2026<br><strong>LOKACIJA:</strong> LUI, Vojkova cesta 63, Ljubljana (2. nadstropje)</p>"""
    private val emoji = """<p>📅 7. oktober 2026, 10.00–13.00</p><p>📍 LUI, Vojkova cesta 63, Ljubljana</p>"""
    // A question with "kdaj" in it, then a heading with the details on the following lines
    private val heading = """<p>Kateri procesi so ozko grlo? In kdaj se investicija dejansko izplača?</p>
        <p><strong>KDAJ:</strong></p><p>Petek, 30. oktober 2026 – od 12.00 do 24.00</p><p>Sobota, 31. oktober 2026 – od 9.00 do 16.00</p>
        <p><strong>KJE:</strong></p><p>Petek, 16. oktober 2026 – zbirno mesto je LUI</p>"""
    private val wrongYear = """<p>Kdaj: Sklop 1: 3. februar 2025, 8.00–16.00 Sklop 2: 4. februar 2025, 8.00–16.00</p>"""
    private val numeric = """<p>Kdaj: 16.5.2026 ob 19.00 Kje: LUI, Vojkova cesta 63, Ljubljana</p>"""
    private val programme = """<p>Labs to Market je program za raziskovalne skupine. Prijave do 10. marca 2026.</p>"""

    // The other sites' posts (copied from live posts, trimmed)
    private val ptechMarked = """<p><strong>Vabimo vas na delavnico, ki bo 19. novembra 2026, s pričetkom ob 16.00 uri v prostorih Pomurskega tehnološkega parka.</strong></p>
        <p class="wp-block-paragraph"><strong>📅</strong> <strong>Datum:&nbsp;19. november 2026</strong></p>
        <p class="wp-block-paragraph"><strong>⏰</strong> <strong>Čas:&nbsp;16.00&nbsp;– 19.00</strong></p>
        <p class="wp-block-paragraph"><strong>📍</strong> <strong>Kraj:&nbsp;Pomurski tehnološki park, Plese 9a, 9000 Murska Sobota</strong></p>"""
    private val ptechQuestion = """<p class="wp-block-paragraph">Dogodek bo tudi priložnost za vaša vprašanja.</p>
        <p class="wp-block-paragraph"><strong>KDAJ?</strong> 21.10.26 ob 16.00</p>
        <p class="wp-block-paragraph"><strong>KJE?</strong> Občina Ljutomer (3. nadstropje sejna soba)</p>"""
    private val ptechSentence = """<p>Vabimo vas na info dogodek »Kako do sredstev za investicije in razvoj?«, ki bo dne 27.8.2026 s pričetkom ob 9.30 uri v Pomurskem tehnološkem parku.</p>
        <p>9.30–9.45 : Uvodni pozdrav organizatorja dogodka</p>
        <p>Prijave sprejemamo najkasneje do 25.8.2026.</p>"""
    private val ptechOld = """<p>Termin: Sreda, 20. 11. 2024, 10:00 – 12:00</p>"""
    private val belaChanged = """<p style="text-align: center;"><span>📅 <strong>KDAJ:</strong> petek, 20. november 2026, ob 17.00 &#8211; 19.00.</span><span><br />
        </span><span> 📍<strong>LOKACIJA:</strong> Podjetniški inkubator Bela krajina, Belokranjska cesta 36,&nbsp; 8340 Črnomelj.</span></p>
        <h3 style="text-align: center;">PRIJAVA NA DOGODEK TUKAJ:</h3>"""
    private val belaFirst =
        """<p>📅 <strong>KDAJ:</strong> petek, 20. november 2026, ob 17.00 &#8211; 19.00.<br />📍<strong>LOKACIJA:</strong> Podjetniški inkubator Bela krajina</p>"""
    private val belaNews = """<p>Na tekmovanju POPRI 2026 so se v finale uvrstile tri belokranjske ekipe. Čestitamo!</p>"""
    private val rogaskaEmoji = """<p class="wp-block-paragraph">📅 Četrtek, 19.11.2026 ob 13.00<br>📍 Preko spleta</p>
        <h3 class="wp-block-heading">O predavateljici</h3>"""
    private val rogaskaSentence = """<p>Prijave zbiramo do ponedeljka, 23. novembra 2026, oziroma do zapolnitve mest.</p>
        <p class="has-text-align-center">ki bo v četrtek<strong>, 26. novembra 2026, ob 12. uri</strong><strong>,</strong></p>
        <p class="has-text-align-center">v sejni sobi PC Vrelec, Celjska cesta 30, Rogaška Slatina.</p>"""

    private fun post(id: Long, published: String, link: String, title: String, content: String, description: String? = null) =
        buildMap {
            put("id", id)
            put("date", "${published}T10:00:00")
            put("link", link)
            put("title", mapOf("rendered" to title))
            put("content", mapOf("rendered" to content))
            if (description != null) put("yoast_head_json", mapOf("description" to description))
        }

    private fun json(posts: List<Map<String, Any>>) = Web.MAPPER.writeValueAsString(posts)

    private fun site(home: String, vararg pages: List<Map<String, Any>>) = FakeWeb(
        mapOf("$home/wp-json/wp/v2/categories?slug=dogodki" to """[{"id":27,"slug":"dogodki"}]""") +
            pages.withIndex().associate { (i, posts) -> "$home/wp-json/wp/v2/posts?categories=27&per_page=20&page=${i + 1}&" to json(posts) }
    )

    private fun lui(id: Long, published: String, slug: String, title: String, content: String) =
        post(id, published, "https://lui.si/dogodki/$slug/", title, content, "Opis: $title")

    // Three pages; the first one full, so the second is read too, and the third never
    private val luiWeb = site(
        "https://lui.si",
        listOf(
            lui(5101, "2026-10-05", "canva-masterclass-2", "Canva Masterclass", inline),
            lui(5102, "2026-10-02", "labs-to-market-pitch", "Labs to Market: Kako pripraviti dober pitch", separate),
        ) + (1..18).map { lui(5000L + it, "2026-09-01", "novica-$it", "Novica $it", "<p>Novica brez datuma.</p>") },
        listOf(
            lui(5103, "2026-02-06", "labs-to-market-2026", "Labs to Market 2026", programme),
            lui(5104, "2026-09-07", "startup-vikend", "Startup vikend &#8211; Prebudi potencial", twoDays),
        ),
        listOf(lui(4000, "2024-01-01", "star", "Star dogodek", inline)),
    )

    private fun luiEvents() = WordPressEventsParser.LUI.parse(context(luiWeb), URI("https://lui.si/"))

    private fun listingResponse(page: Int, response: () -> String): List<ScrapedEvent> {
        val source = luiWeb.replaceGet({ it.path.endsWith("/posts") && "page=$page&" in it.query }, response)
        return WordPressEventsParser.LUI.parse(context(source), URI("https://lui.si"))
    }

    @Test
    fun `HTTP 400 fails the first listing page and ends a later page`() {
        val badRequest = { throw WebException(400, "Bad request") }
        assertEquals(400, assertFailsWith<WebException> { listingResponse(1, badRequest) }.status)
        assertEquals(listOf("lui-5101", "lui-5102"), listingResponse(2, badRequest).map { it.externalId })
    }

    @Test
    fun `a malformed second listing page fails the scrape`() {
        assertFailsWith<IllegalStateException> { listingResponse(2) { "{}" } }
        assertFailsWith<tools.jackson.core.exc.StreamReadException> { listingResponse(2) { "invalid json" } }
    }

    @Test
    fun `LUI lists the dogodki category's two newest pages and skips posts without an event date`() {
        val events = luiEvents()
        assertEquals(setOf("lui-5101", "lui-5102", "lui-5104"), events.map { it.externalId }.toSet())
        assertTrue(events.all { it.source == Source.LUI })
        assertTrue(events.any { it.title == "Startup vikend – Prebudi potencial" })
        assertTrue(luiWeb.asked.none { "page=3" in it })
    }

    @Test
    fun `LUI's event with a time range has its start, end, place, description and link`() {
        val event = luiEvents().single { it.externalId == "lui-5101" }
        val start = local(event.start)
        assertEquals(d(2026, 10, 27), start.toLocalDate())
        assertEquals(t(13, 0), start.toLocalTime())
        assertEquals(3600, start.offset.totalSeconds) // CET: after the change on 25 October
        assertEquals(t(16, 0), local(event.end!!).toLocalTime())
        assertTrue(event.hasStartTime)
        assertTrue(event.hasEndTime)
        assertEquals("LUI – Ljubljanski univerzitetni inkubator", event.locationName)
        assertEquals("Vojkova cesta 63, Ljubljana", event.locationAddress)
        assertEquals("Opis: Canva Masterclass", event.description)
        assertEquals("https://lui.si/dogodki/canva-masterclass-2/", event.eventUrl)
        assertEquals("https://lui.si/aktualno/", event.sourceUrl)
    }

    @Test
    fun `a two-day event without a time is all-day and ends on its second day`() {
        val event = luiEvents().single { it.externalId == "lui-5104" }
        assertEquals(d(2026, 9, 25), local(event.start).toLocalDate())
        assertFalse(event.hasStartTime)
        assertEquals(d(2026, 9, 26), local(event.end!!).toLocalDate())
        assertFalse(event.hasEndTime)
    }

    @Test
    fun `reads the ways LUI writes when and where`() {
        fun of(html: String, published: LocalDate) = details(linesOf(html), published)
        assertEquals(
            Details(d(2026, 10, 27), t(13, 0), t(16, 0), null, "LUI – Ljubljanski univerzitetni inkubator, Vojkova cesta 63, Ljubljana"),
            of(inline, d(2026, 10, 5)),
        )
        assertEquals(
            Details(d(2026, 10, 13), t(14, 0), t(17, 0), null, "Ljubljanski univerzitetni inkubator (LUI), Vojkova cesta 63, Ljubljana"),
            of(separate, d(2026, 10, 2)),
        )
        // No year: the publish year
        assertEquals(Details(d(2026, 8, 31), t(9, 0), t(12, 0), null, "Ljubljanski univerzitetni inkubator (LUI)"), of(noYear, d(2026, 8, 8)))
        of(twoDays, d(2026, 9, 7))!!.let {
            assertEquals(d(2026, 9, 25), it.startDate)
            assertEquals(d(2026, 9, 26), it.endDate)
        }
        assertEquals(Details(d(2026, 10, 7), t(10, 0), t(13, 0), null, "LUI, Vojkova cesta 63, Ljubljana"), of(emoji, d(2026, 9, 25)))
        // The question's "kdaj" is not a marker; "do 24.00" is the end of the day; per-day directions aren't a place
        assertEquals(Details(d(2026, 10, 30), t(12, 0), t(23, 59), d(2026, 10, 31), null), of(heading, d(2026, 10, 5)))
        // "2025" in a post published in January 2026 for February: next year
        of(wrongYear, d(2026, 1, 12))!!.let {
            assertEquals(d(2026, 2, 3), it.startDate)
            assertEquals(t(8, 0), it.startTime)
        }
        assertEquals(Details(d(2026, 5, 16), t(19, 0), null, null, "LUI, Vojkova cesta 63, Ljubljana"), of(numeric, d(2026, 4, 30)))
        assertNull(of(programme, d(2026, 2, 6)))
    }

    private val asOf = d(2026, 11, 12)

    @Test
    fun `Pomurski tehnoloski park's marked, KDAJ-KJE and sentence posts, and none older than 12 months`() {
        val web = site(
            "https://www.p-tech.si",
            listOf(
                post(2681, "2026-10-19", "https://www.p-tech.si/google-ads/", "Google Ads od osnov do B2B lead generationa", ptechMarked),
                post(2656, "2026-10-07", "https://www.p-tech.si/ljutomer/", "Odprt razpis za razvoj podjetij v Občini Ljutomer", ptechQuestion),
                post(2517, "2026-06-22", "https://www.p-tech.si/info-dogodek/", "Info dogodek: Kako do sredstev za investicije in razvoj?", ptechSentence),
                post(1800, "2024-11-05", "https://www.p-tech.si/staro/", "Vabilo na dogodek iz leta 2024", ptechOld),
            ),
        )
        val events = WordPressEventsParser.PomurskiTehnoloskiPark.asOf(asOf).parse(context(web), URI("https://www.p-tech.si"))
        assertEquals(setOf("ptech-2681", "ptech-2656", "ptech-2517"), events.map { it.externalId }.toSet())
        assertTrue(events.all { it.source == Source.POMURSKI_TEHNOLOSKI_PARK })

        val ads = events.single { it.externalId == "ptech-2681" }
        assertEquals(d(2026, 11, 19), local(ads.start).toLocalDate())
        assertEquals(t(16, 0), local(ads.start).toLocalTime())
        assertEquals(3600, local(ads.start).offset.totalSeconds)
        assertEquals(t(19, 0), local(ads.end!!).toLocalTime())
        assertEquals("Pomurski tehnološki park", ads.locationName)
        assertEquals("Plese 9a, 9000 Murska Sobota", ads.locationAddress)
        assertEquals("https://www.p-tech.si/google-ads/", ads.eventUrl)
        assertEquals("https://www.p-tech.si/kategorija/dogodki/", ads.sourceUrl)

        val ljutomer = events.single { it.externalId == "ptech-2656" }
        assertEquals(d(2026, 10, 21), local(ljutomer.start).toLocalDate())
        assertEquals(t(16, 0), local(ljutomer.start).toLocalTime())
        assertEquals("Občina Ljutomer (3. nadstropje sejna soba)", ljutomer.locationName)

        val info = events.single { it.externalId == "ptech-2517" }
        assertEquals(d(2026, 8, 27), local(info.start).toLocalDate())
        assertEquals(t(9, 30), local(info.start).toLocalTime())
        assertEquals(7200, local(info.start).offset.totalSeconds)
        assertNull(info.locationName)
    }

    @Test
    fun `Bela krajina has one event for a post and its date-change post, the newer, and skips news`() {
        val web = site(
            "https://inkubator-belakrajina.si",
            listOf(
                post(5692, "2026-11-02", "https://inkubator-belakrajina.si/sprememba/", "SPREMEMBA DATUMA DOGODKA: Zakaj je kmetijstvo tudi podjetništvo?", belaChanged),
                post(5650, "2026-10-20", "https://inkubator-belakrajina.si/kmetijstvo/", "ZAKAJ JE KMETIJSTVO TUDI PODJETNIŠTVO?", belaFirst),
                post(5600, "2026-03-18", "https://inkubator-belakrajina.si/popri/", "Bela krajina ima 3 finaliste tekmovanja POPRI", belaNews),
            ),
        )
        val events = WordPressEventsParser.InkubatorBelaKrajina.asOf(asOf).parse(context(web), URI("https://inkubator-belakrajina.si"))
        val event = events.single()
        assertEquals("belakrajina-5692", event.externalId)
        assertEquals("SPREMEMBA DATUMA DOGODKA: Zakaj je kmetijstvo tudi podjetništvo?", event.title)
        assertEquals(d(2026, 11, 20), local(event.start).toLocalDate())
        assertEquals(t(17, 0), local(event.start).toLocalTime())
        assertEquals(t(19, 0), local(event.end!!).toLocalTime())
        assertEquals("Podjetniški inkubator Bela krajina", event.locationName)
        assertEquals("Belokranjska cesta 36, 8340 Črnomelj", event.locationAddress)
        assertEquals(Source.INKUBATOR_BELA_KRAJINA, event.source)
    }

    @Test
    fun `Rogaska Slatina's line with a calendar, and a sentence with a full hour after a registration deadline`() {
        val web = site(
            "https://www.inkubator-rs.si",
            listOf(
                post(1500, "2026-11-09", "https://www.inkubator-rs.si/bolniske/", "Zakonodajni in praktični vidiki obvladovanja ter nadzora bolniških odsotnosti", rogaskaSentence),
                post(1490, "2026-11-05", "https://www.inkubator-rs.si/sp-ali-doo/", "S.p. ali d.o.o.? Koliko podjetniku dejansko ostane?", rogaskaEmoji),
            ),
        )
        val events = WordPressEventsParser.InkubatorRogaskaSlatina.asOf(asOf).parse(context(web), URI("https://www.inkubator-rs.si"))
        val emoji = events.single { it.externalId == "inkubatorrs-1490" }
        assertEquals(d(2026, 11, 19), local(emoji.start).toLocalDate())
        assertEquals(t(13, 0), local(emoji.start).toLocalTime())
        assertEquals("Online", emoji.locationName)
        assertNull(emoji.locationAddress)
        val hour = events.single { it.externalId == "inkubatorrs-1500" }
        assertEquals(d(2026, 11, 26), local(hour.start).toLocalDate())
        assertEquals(t(12, 0), local(hour.start).toLocalTime())
        assertTrue(hour.hasStartTime)
        assertFalse(hour.hasEndTime)
        assertTrue(events.all { it.source == Source.INKUBATOR_ROGASKA_SLATINA })
    }

    @Test
    fun `sentences are read only where the site says so`() {
        val lines = linesOf(ptechSentence)
        assertNull(details(lines, d(2026, 6, 22)))
        assertEquals(d(2026, 8, 27), details(lines, d(2026, 6, 22), sentences = true)?.startDate)
        assertNull(details(linesOf(belaNews), d(2026, 3, 18), sentences = true))
    }

    @Test
    fun `the same event, by its title`() {
        assertTrue(similarTitles("SPREMEMBA DATUMA DOGODKA: Zakaj je kmetijstvo tudi podjetništvo?", "ZAKAJ JE KMETIJSTVO TUDI PODJETNIŠTVO?"))
        assertFalse(similarTitles("Pitch trening", "Labs to Market: Kako pripraviti dober pitch"))
        assertFalse(similarTitles("Canva Masterclass", "Uvodni tečaj Canva"))
        assertTrue(similarTitles("Izdelava spletnih strani z uporabo AI orodij", "Izdelava spletnih strani z uporabo AI orodij – novembrski termin"))
    }

    @Test
    fun `fails when the site has no events category`() {
        val web = FakeWeb(mapOf("https://lui.si/wp-json/wp/v2/categories?slug=dogodki" to "[]"))
        assertFailsWith<IllegalStateException> { WordPressEventsParser.LUI.parse(context(web), URI("https://lui.si/")) }
    }

    @Test
    fun `takes each site's hosts, as Goo did`() {
        assertTrue(WordPressEventsParser.LUI.accepts(SourceField.WEBSITE, URI("https://lui.si/")))
        assertTrue(WordPressEventsParser.LUI.accepts(SourceField.WEBSITE, URI("https://www.lui.si/aktualno/")))
        assertFalse(WordPressEventsParser.LUI.accepts(SourceField.WEBSITE, URI("https://flui.si/")))
        assertTrue(WordPressEventsParser.PomurskiTehnoloskiPark.accepts(SourceField.WEBSITE, URI("https://www.p-tech.si")))
        assertFalse(WordPressEventsParser.PomurskiTehnoloskiPark.accepts(SourceField.WEBSITE, URI("https://shop.p-tech.si")))
        assertTrue(WordPressEventsParser.InkubatorBelaKrajina.accepts(SourceField.WEBSITE, URI("https://inkubator-belakrajina.si")))
        assertTrue(WordPressEventsParser.InkubatorRogaskaSlatina.accepts(SourceField.WEBSITE, URI("https://www.inkubator-rs.si/")))
        assertFalse(WordPressEventsParser.InkubatorRogaskaSlatina.accepts(SourceField.WEBSITE, URI("https://inkubator-rs.com/")))
    }
}
