package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import si.ogrodje.scrapers.parsers.DataScienceParser.Item
import si.ogrodje.scrapers.parsers.FRIParser.DayMonth
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataScienceParserTest {

    private val today = LocalDate.now(LJUBLJANA)

    private fun item(
        iso: LocalDate, kind: String, title: String, url: String, time: String = "", meta: String = "", excerpt: String = "",
    ) = mapOf(
        "iso" to iso.toString(), "date" to "${iso}T00:00:00Z", "kind" to kind, "title" to title, "url" to url,
        "time" to time, "meta" to meta, "excerpt" to excerpt, "hasBody" to excerpt.isNotEmpty(),
    )

    // FRI's calendar has no year; the day FRIParser gives "21. okt" is the one DataScience must list it on
    private val careerDay = FRIParser.dateOf(DayMonth("21.", "okt"), today)!!

    private val items = listOf(
        item(
            today.plusDays(14), "workshop", "Workshop: Pair programming with AI", "/news/workshop_2026-11-26/",
            time = "17:00", meta = "17:00 · FRI, Lecture room 3",
            excerpt = "When? Thursday, 26 November 2026, 17:00–20:00.\nWhere? Faculty of Computer and Information Science, " +
                "Večna pot 113, Ljubljana. Lecture room 3.\nWhat?\nGet into the flow of AI-assisted …",
        ),
        item(
            today.plusDays(2), "meetup", "Data Science Talks", "/news/datasciencetalks001/",
            meta = "Teads Ljubljana, Dunajska cesta 5, 1000 Ljubljana",
            excerpt = "The Data Science Talks are a recurring event of the Data Science Initiative …",
        ),
        item(
            today.minusDays(30), "meetup", "Spark sessions 005", "/news/spark005/", meta = "FRI, Večna pot 113, Ljubljana",
            excerpt = "When? November 18 2025 at 18:00.\nWhere? Faculty of Computer and Information Science, Večna pot 113, " +
                "Ljubljana.\nWhat?\nA new and improved traffic model for Slovenia …",
        ),
        item(today.minusDays(40), "workshop", "Predictive Modelling with Python by Jure Žabkar", "/news/workshop_x/", meta = "Jure Žabkar"),
        item(
            careerDay, "job fair, workshops", "Data Science Career Day", "/news/workshop_2026-10-21-3/", meta = "Lobby FRI",
            excerpt = "When? 21 October 2026\nWhere? Lobby FRI\nWhat?\nJoin us on Data Science Career Day 2026.",
        ),
        item(careerDay, "counseling", "Personal counseling: CV & networking", "/news/workshop_2026-10-21-2/", meta = "Zoom"),
        item(today.minusDays(5), "news", "Project competition 2026 results", "/news/pc2026results/"),
        item(today.minusYears(3), "workshop", "Introduction to Network Science in Python", "/news/workshop_2021-12-14/"),
        mapOf("title" to "No date", "url" to "/news/x/"),
    )

    private val eventsHtml = """<!doctype html><html><body><main id=main><div id=events-app></div></main>
        <script type=application/json id=events-data>${Web.MAPPER.writeValueAsString(items)}</script>
        </body></html>"""

    private fun friCard(day: String, month: String, slug: String, title: String, hour: String) =
        """<div class="dogodek-item"><div class="date"><div class="date-single-top"> $day </div>
        <div class="date-single-bottom"> $month </div></div><div class="dogodki-opis">
        <div class="event-title"><a href="/sl/dogodek/$slug">$title</a></div>
        <div class="dogodki-hour">$hour</div></div></div>"""

    // FRI lists DataScience's Career Day too, next to another event on the same day
    private val friHtml = """<html><body><div id="vsi-dogodki">
        ${friCard("21.", "okt", "data-science-career-day", "Data Science Career Day", "ob 10:00")}
        ${friCard("21.", "okt", "arena-tehnologij", "Arena tehnologij", "ob 12:00")}
        </div></body></html>"""

    private fun web(dataScienceUp: Boolean = true) = FakeWeb(
        buildMap {
            if (dataScienceUp) put("https://datascience.fri.uni-lj.si/events/", eventsHtml)
            put("https://fri.uni-lj.si/sl/koledar-dogodkov", friHtml)
            put("https://fri.uni-lj.si/sl/dogodek/", "<div id=eventOpis></div>")
        }
    )

    private fun dataScience() = DataScienceParser.parse(context(web()), DataScienceParser.homepage)

    private fun fri(dataScienceUp: Boolean = true) = FRIParser.parse(context(web(dataScienceUp)), URI("https://fri.uni-lj.si"))

    private fun event(id: String) = dataScience().single { it.externalId == id }

    @Test
    fun `only upcoming truncated abstracts are fetched and missing details remain fallbacks`() {
        val base = DataScienceParser.homepage
        val workshop = "$base/news/workshop_2026-11-26/"
        val meetup = "$base/news/datasciencetalks001/"
        val web = FakeWeb(mapOf(
            "$base/events/" to eventsHtml,
            workshop to "<main>No article</main>",
            meetup to "<main><div class=prose><p>When? Tuesday</p><p>Where? FRI</p><p>Full talk.</p></div></main>",
        ))
        val results = DataScienceParser.parse(context(web), base, today).associateBy { it.eventUrl }
        assertEquals(listOf("$base/events/", workshop, meetup), web.asked)
        assertTrue(results.getValue(workshop).descriptionIsFallback)
        assertEquals("Full talk.", results.getValue(meetup).description)
        assertFalse(results.getValue(meetup).descriptionIsFallback)
        assertTrue(results.getValue("$base/news/spark005/").descriptionIsFallback)
        assertTrue(event("datascience-workshop_2026-11-26").descriptionIsFallback)
    }

    @Test
    fun `expands truncated excerpts from the article body`() {
        val page = """<main><div class="prose"><p>When? Tuesday</p><p><strong>What?</strong></p>
            <p>Full abstract.</p><ul><li>First topic</li><li>Second topic</li></ul></div><nav>Related posts</nav></main>"""
        val web = FakeWeb(mapOf(
            "https://datascience.fri.uni-lj.si/events/" to eventsHtml,
            "https://datascience.fri.uni-lj.si/news/workshop_2026-11-26/" to page,
        ))
        val workshop = DataScienceParser.parse(context(web), DataScienceParser.homepage)
            .single { it.externalId == "datascience-workshop_2026-11-26" }
        assertEquals("Full abstract.\n\nFirst topic\n\nSecond topic", workshop.description)
    }

    @Test
    fun `reads meetups, workshops and the career day, not news, counselling, old posts or broken items`() {
        val events = dataScience()
        assertEquals(
            setOf(
                "datascience-workshop_2026-11-26",
                "datascience-datasciencetalks001",
                "datascience-spark005",
                "datascience-workshop_x",
                "datascience-workshop_2026-10-21-3",
            ),
            events.map { it.externalId }.toSet(),
        )
        assertTrue(events.all { it.source == Source.DATA_SCIENCE })
    }

    @Test
    fun `time, end time and place from the When and Where lines, and a link to the post`() {
        val event = event("datascience-workshop_2026-11-26")
        val start = event.start.atZone(LJUBLJANA)
        assertEquals(today.plusDays(14), start.toLocalDate())
        assertEquals(LocalTime.of(17, 0), start.toLocalTime())
        assertEquals(LocalTime.of(20, 0), event.end!!.atZone(LJUBLJANA).toLocalTime())
        assertTrue(event.hasStartTime && event.hasEndTime)
        assertEquals("Faculty of Computer and Information Science, Lecture room 3", event.locationName)
        assertEquals("Večna pot 113, Ljubljana", event.locationAddress)
        assertEquals("Get into the flow of AI-assisted …", event.description)
        assertEquals("https://datascience.fri.uni-lj.si/news/workshop_2026-11-26/", event.eventUrl)
        assertEquals("https://datascience.fri.uni-lj.si/events/", event.sourceUrl)
    }

    @Test
    fun `without a time, the time from When, else all day, and a meetup's meta is its place, a workshop's isn't`() {
        val spark = event("datascience-spark005")
        assertEquals(LocalTime.of(18, 0), spark.start.atZone(LJUBLJANA).toLocalTime())
        assertTrue(spark.hasStartTime)
        assertFalse(spark.hasEndTime)

        val talks = event("datascience-datasciencetalks001")
        assertFalse(talks.hasStartTime)
        assertEquals("Teads Ljubljana", talks.locationName)
        assertEquals("Dunajska cesta 5, 1000 Ljubljana", talks.locationAddress)
        assertEquals("The Data Science Talks are a recurring event of the Data Science Initiative …", talks.description)

        assertNull(event("datascience-workshop_x").locationName)
        assertNull(event("datascience-workshop_x").locationAddress)
        assertEquals("Faculty of Computer and Information Science", spark.locationName)
        assertEquals("Večna pot 113, Ljubljana", spark.locationAddress)

        val careerDay = event("datascience-workshop_2026-10-21-3")
        assertEquals("Lobby FRI", careerDay.locationName)
        assertFalse(careerDay.hasStartTime)
    }

    @Test
    fun `FRI leaves out the events DataScience lists, and keeps the rest`() {
        assertEquals(listOf("Arena tehnologij"), fri().map { it.title })
    }

    @Test
    fun `FRI keeps everything when DataScience's page can't be read`() {
        assertEquals(setOf("Data Science Career Day", "Arena tehnologij"), fri(dataScienceUp = false).map { it.title }.toSet())
    }

    @Test
    fun `the same event is on the same day, with matching words that aren't generic`() {
        val day = LocalDate.of(2026, 3, 24)
        fun ds(title: String, on: LocalDate = day) = Item("/news/x/", on, "meetup", title, null, null, null)
        assertTrue(DataScienceParser.sameEvent(day, "Data Science: Spark Sessions", ds("Spark sessions 007")))
        assertTrue(DataScienceParser.sameEvent(day, "DataScience Spark Sessions", ds("Spark sessions 004")))
        assertTrue(DataScienceParser.sameEvent(day, "Data Science Career Day", ds("Data Science Career Day")))
        assertFalse(DataScienceParser.sameEvent(day, "Data Science: Spark Sessions", ds("Spark sessions 007", day.plusDays(1))))
        assertFalse(DataScienceParser.sameEvent(day, "Vabljeno predavanje: Taming Big Data", ds("Data Science Talks")))
        assertFalse(DataScienceParser.sameEvent(day, "Arena tehnologij", ds("Data Science Career Day")))
    }

    @Test
    fun `time ranges`() {
        assertEquals(LocalTime.of(17, 0) to LocalTime.of(20, 0), DataScienceParser.timesOf("Thursday, 26 November 2026, 17:00–20:00."))
        assertEquals(LocalTime.of(8, 30) to LocalTime.of(11, 30), DataScienceParser.timesOf("Wednesday, 21 October 2026, 8:30–11:30."))
        assertEquals(LocalTime.of(19, 0) to null, DataScienceParser.timesOf("Wednesday, 11 November 2026, at 19:00."))
        assertNull(DataScienceParser.timesOf("21 October 2026"))
        assertNull(DataScienceParser.timesOf(null))
    }

    @Test
    fun `takes datascience's own host only`() {
        assertTrue(DataScienceParser.accepts(SourceField.WEBSITE, URI("https://datascience.fri.uni-lj.si/")))
        assertFalse(DataScienceParser.accepts(SourceField.WEBSITE, URI("https://fri.uni-lj.si/")))
    }

    @Test
    fun `places the saved page's events`() {
        // The events page as it was on 18 November 2026
        val web = FakeWeb(mapOf("https://datascience.fri.uni-lj.si/events/" to FakeWeb.fixture("datascience-events.html")))
        val events = DataScienceParser.parse(context(web), DataScienceParser.homepage, LocalDate.of(2026, 11, 18))
            .associateBy { it.title }
        val quiz = events.getValue("Data Science Pub Quiz")
        assertEquals("Kazina Jazz Club", quiz.locationName)
        assertEquals("Kongresni trg 1, 1000 Ljubljana", quiz.locationAddress)
        val luxonis = events.values.single { it.title.startsWith("Computer Vision on the Edge") }
        assertEquals("Faculty of Computer and Information Science, Lecture room 03", luxonis.locationName)
        assertEquals("Večna pot 113, Ljubljana", luxonis.locationAddress)
        assertEquals("FRI", events.getValue("Spark sessions 007").locationName)
        assertEquals("Večna pot 113, Ljubljana", events.getValue("Spark sessions 007").locationAddress)
    }
}
