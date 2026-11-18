package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import si.ogrodje.scrapers.parsers.KatapultParser.When
import si.ogrodje.scrapers.parsers.KatapultParser.cleanTitle
import si.ogrodje.scrapers.parsers.KatapultParser.whenOf
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KatapultParserTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    private fun card(slug: String, title: String) = """<div class="e-blog-list-item clean-typography w-full"> <div class="bg-white card-shadow">
        <div class="flex min-h-[360px] flex-col justify-between pt-[10px] px-5 pb-6">
        <div class="w-full flex flex-col justify-start items-start">
        <span class="body-small-bold text-primary-orange capitalize"> dogodek </span>
        <h3 class="headline3"><a href="/novice/$slug">$title</a></h3>
        <div class="py-4 body-small-regular"><p>…</p></div></div>
        <div class="e-button flex "><a class="btn-simple" type="novice" href="/novice/$slug"><span> Preberi več </span></a></div>
        </div></div></div>"""

    private fun listing(vararg cards: String) =
        """<html><body><section><div wire:id="x" wire:name="blog.blog-list"><div class="grid">${cards.joinToString("")}</div></div>
        </section></body></html>"""

    // Posts as the site renders them (copied from the live pages, trimmed)
    private fun post(title: String, subtitle: String, published: String, side: String) = """<html><body><section><h1 class=""> $title </h1>
        <div class="blog-single-info flex flex-col gap-y-[10px]">
        <div class="blog-single-subtitle"> <p>$subtitle</p> </div>
        <div class="blog-single-info-meta flex justify-center"> <div class="flex gap-[10px] items-center justify-center w-full">
        <span class="w-1/2 text-right">Olga Knez</span> <span class="block custom-vertical-separator"></span>
        <span class="w-1/2 text-left">$published</span> </div> </div></div></section>
        <div class="content-holder"><div class="m-columns flex">
        <div class="md:w-2/3 "><div class="e-text"><p>Mentorji Katapulta svoje znanje črpajo iz izkušenj.</p>
        <ul><li><p><strong>Kje se najpogosteje zatakne:</strong> tipične napake startupov</p></li></ul></div></div>
        <div class="md:w-1/3 "><div class="e-text">$side</div></div></div></div></body></html>"""

    private val posts = mapOf(
        "od-ideje-do-izdelka" to post(
            "Od ideje do izdelka: vpogled z mentorji Katapulta I 17.11.2026", "Kako ideja postane izdelek, ki ga kupci res želijo?", "04.11.2026",
            """<p><strong>Kdaj?</strong> Torek, 17. november 2026, od 9.00 do&nbsp;12.00</p><p><strong>Kje?</strong> Katapult in Dewesoft,&nbsp;Trbovlje</p><p><strong>Koliko?</strong>&nbsp;Brezplačno</p>""",
        ),
        "scale-it" to post(
            "SCALE IT! Od prvih prodaj do serijske proizvodnje (23., 27. in 29. oktober)", "Inkubacijski program SCALE IT! je namenjen startupom.", "09.10.2026",
            """<p style="text-align: justify;"><strong>Kdaj?</strong> 23., 27. in 29. oktober 2026, vsak dan od 9.00 do&nbsp;16.00</p><p style="text-align: justify;"><strong>Kje?</strong> Katapult, Gabrsko 12, 1420&nbsp;Trbovlje</p>""",
        ),
        "za-prijaznejso-prihodnost-2026" to post(
            "Za prijaznejšo prihodnost 2026: Človek. Možgani. Roboti. I 26.–28.11.2026", "Tridnevni festival znanosti, robotike in nevrotehnologij.", "04.11.2026",
            """<p><strong>Kdaj?</strong> Od četrtka, 26., do sobote, 28. novembra 2026</p><p><strong>Kje?</strong> Katapult, Gabrsko 12, Trbovlje</p>""",
        ),
        "migy-delavnice" to post(
            "DELAVNICA: MIGY delavnice izdelave DIY robotkov", "Udeleženci bodo sestavili robotka.", "10.06.2026",
            """<p>DVA TERMINA: 📅 20. 7. – 23. 7. 2026 in 📅 24. 8. – 27. 8. 2026</p><p><strong>Kje?</strong> Katapult, Gabrsko 12, 1420 Trbovlje</p>""",
        ),
        "meet-up" to post(
            "BREZPLAČNI DOGODEK: Katapult Podjetniški Meet-up", "Pridružite se nam.", "30.11.2025",
            """<p><strong>Kdaj?</strong> Torek, 16. 12. 2025, od 16.00 do 19.00</p><p><strong>Kje?</strong> Katapult, Gabrsko 12, 1420 Trbovlje</p>""",
        ),
    )

    private val web = FakeWeb(
        mapOf(
            "https://katapult.si/dogodki" to listing(
                card("od-ideje-do-izdelka", "Od ideje do izdelka: vpogled z mentorji Katapulta I 17.11.2026"),
                card("scale-it", "SCALE IT! Od prvih prodaj do serijske proizvodnje (23., 27. in 29. oktober)"),
                card("za-prijaznejso-prihodnost-2026", "Za prijaznejšo prihodnost 2026 I 26.–28.11.2026"),
            ),
            "https://katapult.si/dogodki?page=2" to listing(
                card("migy-delavnice", "DELAVNICA: MIGY delavnice izdelave DIY robotkov"),
                card("meet-up", "BREZPLAČNI DOGODEK: Katapult Podjetniški Meet-up"),
                card("izbrisan", "Izbrisan dogodek"),
            ),
            "https://katapult.si/dogodki?page=3" to listing(card("prestar", "Prestar dogodek")),
        ) + posts.mapKeys { "https://katapult.si/novice/${it.key}" }
    )

    private fun events() = KatapultParser.parse(context(web), URI("https://katapult.si"))

    private fun local(instant: java.time.Instant) = instant.atZone(LJUBLJANA)

    @Test
    fun `reads the first two listing pages and each post, and skips posts without a day and ones that fail`() {
        val events = events()
        assertEquals(
            setOf("katapult-od-ideje-do-izdelka", "katapult-scale-it", "katapult-za-prijaznejso-prihodnost-2026", "katapult-meet-up"),
            events.map { it.externalId }.toSet(),
        )
        assertTrue(events.all { it.source == Source.KATAPULT })
        assertTrue(web.asked.none { "page=3" in it || "prestar" in it })
    }

    @Test
    fun `an event's title without the date, its day and times, place, lede and link`() {
        val event = events().single { it.externalId == "katapult-od-ideje-do-izdelka" }
        assertEquals("Od ideje do izdelka: vpogled z mentorji Katapulta", event.title)
        assertEquals(d(2026, 11, 17), local(event.start).toLocalDate())
        assertEquals(t(9, 0), local(event.start).toLocalTime())
        assertEquals(3600, local(event.start).offset.totalSeconds)
        assertEquals(d(2026, 11, 17).atTime(12, 0), local(event.end!!).toLocalDateTime())
        assertTrue(event.hasStartTime)
        assertTrue(event.hasEndTime)
        assertEquals("Katapult in Dewesoft, Trbovlje", event.locationName)
        assertEquals("Kako ideja postane izdelek, ki ga kupci res želijo?", event.description)
        assertEquals("https://katapult.si/novice/od-ideje-do-izdelka", event.eventUrl)
        assertEquals("https://katapult.si/dogodki", event.sourceUrl)
    }

    @Test
    fun `several days run from the first to the last with the daily times, and a festival without times is all-day`() {
        val events = events()
        val scaleIt = events.single { it.externalId == "katapult-scale-it" }
        val festival = events.single { it.externalId == "katapult-za-prijaznejso-prihodnost-2026" }
        val meetUp = events.single { it.externalId == "katapult-meet-up" }
        assertEquals("SCALE IT! Od prvih prodaj do serijske proizvodnje", scaleIt.title)
        assertEquals(d(2026, 10, 23).atTime(9, 0), local(scaleIt.start).toLocalDateTime())
        assertEquals(7200, local(scaleIt.start).offset.totalSeconds)
        assertEquals(d(2026, 10, 29).atTime(16, 0), local(scaleIt.end!!).toLocalDateTime())
        assertEquals(d(2026, 11, 26), local(festival.start).toLocalDate())
        assertFalse(festival.hasStartTime)
        assertEquals(d(2026, 11, 28), local(festival.end!!).toLocalDate())
        assertFalse(festival.hasEndTime)
        assertEquals("Katapult Podjetniški Meet-up", meetUp.title)
        assertEquals(d(2025, 12, 16), local(meetUp.start).toLocalDate())
    }

    @Test
    fun `the ways Katapult writes when`() {
        val published = d(2026, 10, 1)
        assertEquals(When(d(2026, 11, 17), t(9, 0), null, t(12, 0)), whenOf("Torek, 17. november 2026, od 9.00 do 12.00", published))
        assertEquals(When(d(2026, 10, 22), t(9, 0), null, t(12, 0)), whenOf("Četrtek, 22.10.2026, 9.00 - 12.00", published))
        // No year: the publish year; a genitive month
        assertEquals(When(d(2026, 10, 22), t(17, 30), null, t(20, 30)), whenOf("Četrtek, 22. oktobra, ob 17.30–20.30", d(2026, 9, 22)))
        assertEquals(When(d(2026, 10, 7), t(15, 0), null, t(18, 0)), whenOf("sreda, 7. oktober 2026, 15.00–18.00", published))
        assertEquals(When(d(2026, 6, 16), t(16, 0), null, t(19, 0)), whenOf("Torek, 16.6. 2026 16.00 - 19.00", d(2026, 5, 26)))
        assertEquals(When(d(2026, 1, 22), t(17, 30), null, null), whenOf("Četrtek, 22. januar, 17.30", d(2026, 1, 6)))
        // A day without a month takes the next one's
        assertEquals(When(d(2026, 11, 26), null, d(2026, 11, 28), null), whenOf("Od četrtka, 26., do sobote, 28. novembra 2026", published))
        assertEquals(
            When(d(2026, 10, 23), t(9, 0), d(2026, 10, 29), t(16, 0)),
            whenOf("23., 27. in 29. oktober 2026, vsak dan od 9.00 do 16.00", published),
        )
        // "9.3.2025" in a post from February 2026: March 2026
        assertEquals(When(d(2026, 3, 9), t(10, 0), null, t(12, 0)), whenOf("ponedeljek, 9.3.2025, 10.00 - 12.00", d(2026, 2, 10)))
        assertEquals(When(d(2025, 12, 16), t(16, 0), null, t(19, 0)), whenOf("Torek, 16. 12. 2025, od 16.00 do 19.00", d(2025, 11, 30)))
        assertNull(whenOf("november 2026 – april 2027", published))
    }

    @Test
    fun `titles without the date and the Dogodek label`() {
        assertEquals("Od ideje do izdelka: vpogled z mentorji Katapulta", cleanTitle("Od ideje do izdelka: vpogled z mentorji Katapulta I 17.11.2026"))
        assertEquals("Za prijaznejšo prihodnost 2026: Človek. Možgani. Roboti.", cleanTitle("Za prijaznejšo prihodnost 2026: Človek. Možgani. Roboti. I 26.–28.11.2026"))
        assertEquals("Robotika v oskrbi", cleanTitle("Robotika v oskrbi 22.10.2026"))
        assertEquals("SCALE IT! Od prvih prodaj do serijske proizvodnje", cleanTitle("SCALE IT! Od prvih prodaj do serijske proizvodnje (23., 27. in 29. oktober)"))
        assertEquals("Iskra podjetnosti za vsakega dijaka (Obisk Katapulta)", cleanTitle("Dogodek: Iskra podjetnosti za vsakega dijaka (Obisk Katapulta)"))
        assertEquals("Sodobne namizne igre: razvoj, testiranje in izdaja 2.0", cleanTitle("Sodobne namizne igre: razvoj, testiranje in izdaja 2.0"))
        assertEquals("DELAVNICA: MIGY delavnice", cleanTitle("DELAVNICA: MIGY delavnice"))
    }

    @Test
    fun `fails when the listing doesn't load`() {
        kotlin.test.assertFailsWith<WebException> { KatapultParser.parse(context(FakeWeb(emptyMap())), URI("https://katapult.si")) }
    }

    @Test
    fun `takes katapult's urls`() {
        assertTrue(KatapultParser.accepts(SourceField.WEBSITE, URI("https://www.katapult.si/")))
        assertFalse(KatapultParser.accepts(SourceField.WEBSITE, URI("https://blog.katapult.si/")))
    }
}
