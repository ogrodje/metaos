package si.ogrodje.metaos

import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class EventsTest @Autowired constructor(
    private val mvc: MockMvc,
    private val eventsMCP: EventsMCP,
    private val events: Events,
    private val organisationsMCP: OrganisationsMCP,
    private val history: History,
    private val regions: Regions,
) {

    private val writer = jwt().jwt {
        it.subject("bojan-id").claim("preferred_username", "bojan").claim("azp", "metaos-ui")
            .claim("realm_access", APPROVED).claim("scope", "$API_READ_SCOPE $API_WRITE_SCOPE")
    }

    private val soon = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    private fun organisation(name: String) = checkNotNull(organisationsMCP.create(name = name).id)

    private fun found(id: String, title: String, start: Instant = soon) =
        FoundEvent("MEETUP", id, "https://www.meetup.com/test/events", title, start, start.plus(2, ChronoUnit.HOURS), eventUrl = "https://www.meetup.com/test/events/$id")

    // As the sync's activity keeps them, as the workflows' (EventsSync.kt)
    private fun keep(organisation: UUID, found: List<FoundEvent>) = actingFor(Actor(null, null)) { events.keep(organisation, found) }

    @Test
    fun `a listing excerpt fills a new event and preserves a previously imported abstract`() {
        val by = organisation("Event abstract refresh test")
        val excerpt = found("excerpt-1", "Workshop").copy(description = "Short …", descriptionIsFallback = true)
        val full = excerpt.copy(description = "Full abstract", descriptionIsFallback = false)
        assertEquals(FoundCounts(1, 0), keep(by, listOf(excerpt)))
        assertEquals("Short …", eventsMCP.list(organisationId = by).single().description)
        assertEquals(FoundCounts(0, 1), keep(by, listOf(full)))
        assertEquals(FoundCounts(0, 0), keep(by, listOf(excerpt)))
        assertEquals(FoundCounts(0, 1), keep(by, listOf(excerpt.copy(title = "Updated workshop"))))
        assertEquals("Full abstract", eventsMCP.list(organisationId = by).single().description)
    }

    @Test
    fun `keeps what a scraper found, writes only what changed, and nothing older than a year`() {
        val by = organisation("Scraped Test")
        val first = keep(by, listOf(found("meetup-1", "Kotlin night"), found("meetup-old", "Long ago", Instant.now().minus(400, ChronoUnit.DAYS))))
        assertEquals(FoundCounts(1, 0), first)

        assertEquals(FoundCounts(0, 0), keep(by, listOf(found("meetup-1", "Kotlin night"))))
        assertEquals(FoundCounts(0, 1), keep(by, listOf(found("meetup-1", "Kotlin night, again"))))

        val listed = eventsMCP.list(organisationId = by)
        assertEquals(listOf("Kotlin night, again"), listed.map { it.title })
        assertEquals("MEETUP", listed.single().source)
        // The scrapers' changes aren't the organisation's history
        assertTrue(history.of(by).none { it.kind == ChangeKind.EVENT && it.client == WORKFLOWS_CLIENT })
    }

    @Test
    fun `a scraper that knew only the days this time leaves the times it found before, until it finds them again`() {
        val by = organisation("Only Days Test")
        val day = java.time.LocalDate.now(LJUBLJANA).plusDays(5)
        fun at(hour: Int, minute: Int = 0, on: java.time.LocalDate = day) = on.atTime(hour, minute).atZone(LJUBLJANA).toInstant()
        fun midnight(on: java.time.LocalDate) = on.atStartOfDay(LJUBLJANA).toInstant()
        // As Tehnološki park Ljubljana's calendar has tp-9008: a day, and the next as its end ("2025-04-17" to
        // "2025-04-18"); its page its time (two hours, no end's time)
        fun calendar(id: String, on: java.time.LocalDate = day) = FoundEvent("TEHNOLOSKI_PARK_LJUBLJANA", id,
            "https://www.tp-lj.si/sl/koledar-dogodkov", "Finančna klinika $id", midnight(on), midnight(on.plusDays(1)),
            hasStartTime = false, hasEndTime = false, eventUrl = "https://www.tp-lj.si/sl/dogodki/$id", onlyDays = true)
        fun page(id: String, hour: Int, minute: Int = 0) = calendar(id).copy(start = at(hour, minute),
            end = at(hour + 2, minute), hasStartTime = true, onlyDays = false)
        fun saved(id: String) = eventsMCP.list(organisationId = by).single { it.title == "Finančna klinika $id" }

        assertEquals(FoundCounts(1, 0), keep(by, listOf(page("tp-9008", 11))))
        // Its page didn't answer: its times stay, and it's no change, however often
        assertEquals(FoundCounts(0, 0), keep(by, listOf(calendar("tp-9008"))))
        assertEquals(FoundCounts(0, 0), keep(by, listOf(calendar("tp-9008"))))
        saved("tp-9008").let {
            assertEquals(at(11), it.start)
            assertEquals(at(13), it.end)
            assertTrue(it.hasStartTime)
            assertFalse(it.hasEndTime)
        }
        // Its page answers again, with another time, which it takes
        assertEquals(FoundCounts(0, 1), keep(by, listOf(page("tp-9008", 14, 30))))
        assertEquals(at(14, 30), saved("tp-9008").start)

        // Moved a day earlier, its end the day it was on: the calendar's day, all day, and the same again is no change
        val earlier = calendar("tp-9008", day.minusDays(1))
        assertEquals(midnight(day), earlier.end)
        assertEquals(FoundCounts(0, 1), keep(by, listOf(earlier)))
        saved("tp-9008").let {
            assertEquals(midnight(day.minusDays(1)), it.start)
            assertEquals(midnight(day), it.end)
            assertFalse(it.hasStartTime)
        }
        assertEquals(FoundCounts(0, 0), keep(by, listOf(earlier)))

        // Moved a day later, from a time: the calendar's day, all day
        keep(by, listOf(page("tp-9010", 10)))
        val later = calendar("tp-9010", day.plusDays(1))
        assertEquals(FoundCounts(0, 1), keep(by, listOf(later)))
        assertEquals(midnight(day.plusDays(1)), saved("tp-9010").start)
        assertFalse(saved("tp-9010").hasStartTime)

        // On several days, a time on any of them but the end's stays
        keep(by, listOf(page("tp-9011", 9)))
        val days = calendar("tp-9011", day.minusDays(1)).copy(end = midnight(day.plusDays(1)))
        assertEquals(FoundCounts(0, 0), keep(by, listOf(days)))
        assertEquals(at(9), saved("tp-9011").start)
        assertEquals(FoundCounts(0, 1), keep(by, listOf(days.copy(end = midnight(day)))))
        assertFalse(saved("tp-9011").hasStartTime)

        // Found first without its page, it's the calendar's days
        assertEquals(FoundCounts(1, 0), keep(by, listOf(calendar("tp-9009"))))
        saved("tp-9009").let {
            assertEquals(midnight(day), it.start)
            assertEquals(midnight(day.plusDays(1)), it.end)
            assertFalse(it.hasStartTime)
        }
    }

    @Test
    fun `the team hides and picks a scraper's event, which keeps them when it's found again, and changes only its own wholly`() {
        val by = organisation("Hidden Test")
        keep(by, listOf(found("meetup-2", "Hide me")))
        val id = checkNotNull(eventsMCP.list(organisationId = by).single().id)

        assertTrue(eventsMCP.update(id, hidden = true, promoted = true).hidden)
        assertFailsWith<IllegalArgumentException> { eventsMCP.update(id, title = "Mine now") }
        keep(by, listOf(found("meetup-2", "Hide me, changed")))
        val again = eventsMCP.list(organisationId = by, hidden = true).single()
        assertTrue(again.hidden)
        assertTrue(again.promoted)
        assertEquals("Hide me, changed", again.title)
        assertTrue(eventsMCP.list().none { it.id == id })
        assertTrue(eventsMCP.list(hidden = true).any { it.id == id })

        assertEquals(listOf("Hid the event \"Hide me\""), history.of(by).filter { it.kind == ChangeKind.EVENT }.map { it.summary })
    }

    @Test
    fun `the team adds its own events, by days or times in Ljubljana`() {
        val by = organisation("Manual Test")
        val created = eventsMCP.create(by, "Ogrodje meetup", start = "2030-03-05T18:00", end = "2030-03-05T21:00", url = "https://ogrodje.si")
        assertEquals(Instant.parse("2030-03-05T17:00:00Z"), created.start)
        assertTrue(created.hasStartTime)
        assertEquals(MANUAL_SOURCE, created.source)

        val day = eventsMCP.update(checkNotNull(created.id), start = "2030-03-06", end = "")
        assertFalse(day.hasStartTime)
        assertNull(day.end)
        assertEquals(Instant.parse("2030-03-05T23:00:00Z"), day.start)

        assertFailsWith<IllegalArgumentException> { eventsMCP.create(by, "Bad", start = "5. 3. 2030") }
        assertFailsWith<IllegalArgumentException> { eventsMCP.create(by, "Backwards", start = "2030-03-05T18:00", end = "2030-03-04T18:00") }
        assertFailsWith<IllegalArgumentException> { eventsMCP.create(by, "No link", start = "2030-03-05", url = "ogrodje.si") }
    }

    @Test
    fun `archiving and not collecting stop the scrapers, and change nothing else`() {
        // One gathers events once it has somewhere to read them but its website, unless told otherwise
        assertFalse(organisationsMCP.create(name = "Website Only Test", websiteUrl = "https://example.com").collectsEvents)
        assertTrue(organisationsMCP.create(name = "Meetup Test", meetupUrl = "https://www.meetup.com/meetup-test/").collectsEvents)
        val by = organisation("Archive Test")
        val changed = organisationsMCP.update(by, meetupUrl = "https://www.meetup.com/archive-test/", icalUrl = "webcal://example.com/cal.ics",
            mainColor = "FF00AA", collectsEvents = false)
        assertEquals("#ff00aa", changed.mainColor)
        assertFalse(changed.collectsEvents)
        assertFailsWith<IllegalArgumentException> { organisationsMCP.update(by, mainColor = "pink") }
        assertFailsWith<IllegalArgumentException> { organisationsMCP.update(by, meetupUrl = "meetup.com/x") }

        val archived = organisationsMCP.update(by, archived = true, collectsEvents = true)
        assertTrue(archived.archived)
        assertTrue(organisationsMCP.list(archived = true).any { it.id == by })
        assertTrue(organisationsMCP.list(archived = false).none { it.id == by })
        assertEquals("Archived the organisation", history.of(by).last().summary)
        assertFalse(organisationsMCP.update(by, archived = false).archived)
    }

    @Test
    fun `anyone sees the upcoming events that aren't hidden, with their badges, and the team the rest`() {
        val by = organisation("Public Events Test")
        organisationsMCP.update(by, mainColor = "#ffffff", backgroundColor = "#000000")
        keep(by, listOf(found("meetup-3", "Open to all"), found("meetup-4", "Hidden one")))
        val hidden = checkNotNull(eventsMCP.list(organisationId = by).first { it.title == "Hidden one" }.id)
        eventsMCP.update(hidden, hidden = true)

        graphql("{ events { title meetup { name mainColor backgroundColor } sourceUrl } }")
            .andExpect(jsonPath("$.data.events[*].title", hasItem("Open to all")))
            .andExpect(jsonPath("$.data.events[*].title", not(hasItem("Hidden one"))))
            .andExpect(jsonPath("$.data.events[?(@.title == 'Open to all')].meetup.backgroundColor", hasItem("#000000")))
            .andExpect(jsonPath("$.data.events[?(@.title == 'Open to all')].sourceUrl", hasItem(null as String?)))
        graphql("{ events(hidden: true) { title } }")
            .andExpect(jsonPath("$.data.events[*].title", not(hasItem("Hidden one"))))
        graphql("{ events(hidden: true) { title hidden } }", writer)
            .andExpect(jsonPath("$.data.events[*].title", hasItem("Hidden one")))
        graphql("""{ event(id: "$hidden") { title } }""").andExpect(jsonPath("$.data.event").value(null as Any?))
    }

    @Test
    fun `an event's page is anyone's, a past one's too, with more from its organiser, upcoming first, never hidden`() {
        val by = organisation("Event Page Test")
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        fun daysFrom(days: Long) = now.plus(days, ChronoUnit.DAYS)
        keep(by, listOf(
            found("page-main", "This one", daysFrom(5)),
            found("page-soon", "Soon", daysFrom(2)),
            found("page-later", "Later", daysFrom(30)),
            found("page-hidden", "Hidden soon", daysFrom(1)),
            found("page-past", "Last week", daysFrom(-7)),
            found("page-long-ago", "Long ago", daysFrom(-60)),
        ))
        val ids = eventsMCP.list(organisationId = by).associate { it.title to checkNotNull(it.id) }
        eventsMCP.update(checkNotNull(ids["Hidden soon"]), hidden = true)
        // Another organisation's is never among them
        keep(organisation("Event Page Other Test"), listOf(found("page-other", "Elsewhere", daysFrom(3))))

        val main = events.find(checkNotNull(ids["This one"]))
        assertEquals(listOf("Soon", "Later", "Last week"), events.moreFromOrganiser(main).map { it.title })
        // With fewer upcoming ones, the past fill in, the latest first
        val later = events.find(checkNotNull(ids["Later"]))
        assertEquals(listOf("Soon", "This one", "Last week"), events.moreFromOrganiser(later).map { it.title })
        assertEquals(listOf("Soon", "This one", "Later", "Last week", "Long ago"),
            events.moreFromOrganiser(events.find(checkNotNull(ids["Hidden soon"])), limit = 10).map { it.title })

        // Signed out: a past one's page, with its organiser's others, but no hidden one
        graphql("""{ event(id: "${ids["Long ago"]}") { title description moreFromOrganiser { title } } }""")
            .andExpect(jsonPath("$.data.event.title").value("Long ago"))
            .andExpect(jsonPath("$.data.event.moreFromOrganiser[*].title", not(hasItem("Hidden soon"))))
            .andExpect(jsonPath("$.data.event.moreFromOrganiser[0].title").value("Soon"))
        graphql("""{ event(id: "${ids["Hidden soon"]}") { title } }""").andExpect(jsonPath("$.data.event").value(null as Any?))
        graphql("""{ event(id: "${UUID.randomUUID()}") { title } }""").andExpect(jsonPath("$.data.event").value(null as Any?))
        // The team sees a hidden one's page, without hidden ones among its others either
        graphql("""{ event(id: "${ids["Hidden soon"]}") { title moreFromOrganiser { title } } }""", writer)
            .andExpect(jsonPath("$.data.event.title").value("Hidden soon"))
            .andExpect(jsonPath("$.data.event.moreFromOrganiser[*].title", not(hasItem("Hidden soon"))))
        // The list stays the upcoming ones
        graphql("{ events { title } }").andExpect(jsonPath("$.data.events[*].title", not(hasItem("Last week"))))
    }

    @Test
    fun `the team adds and changes events over GraphQL, and anyone else may not`() {
        val by = organisation("GraphQL Events Test")
        val create = """mutation { createEvent(input: { organisation: "$by", title: "Hekaton", start: "2030-06-01", end: "2030-06-02" }) { id title hasStartTime source } }"""
        graphql(create).andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"))
        val id = JsonMapper.builder().build().readTree(graphql(create, writer).andReturn().response.contentAsString)
            .get("data").get("createEvent").get("id").asString()
        graphql("""mutation { updateEvent(id: "$id", input: { promoted: true, locationName: "Kovačnica" }) { promoted locationName } }""", writer)
            .andExpect(jsonPath("$.data.updateEvent.promoted").value(true))
            .andExpect(jsonPath("$.data.updateEvent.locationName").value("Kovačnica"))
        assertNotNull(events.find(UUID.fromString(id)).promotedAt)
        // Home's latest changes tell them, as the kind GraphQL has
        graphql("{ recentChanges(limit: 5) { kind summary about { kind name } } }", writer)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.recentChanges[*].kind", hasItem("EVENT")))
        // Without Temporal, nothing to refresh with
        graphql("""mutation { refreshEvents { running } }""", writer)
            .andExpect(jsonPath("$.errors[0].message").exists())
    }

    @Test
    fun `an event that doesn't say where it is is at its organisation's venue, which its events give it`() {
        val by = organisation("Venue Test")
        fun at(id: String, name: String?, address: String?) = found(id, "Talk $id").copy(locationName = name, locationAddress = address)
        keep(by, listOf(
            at("v-1", "Kovačnica", "Trubarjeva 5, Ljubljana"), at("v-2", "kovačnica", "Trubarjeva 5, Ljubljana"),
            at("v-3", null, null), at("v-4", "Venue Test", null), at("v-5", "Online", null), at("v-6", "FRI", "Večna pot 113"),
        ))
        // Two of three that say where they are: their venue
        val organisation = organisationsMCP.get(by).organisation
        assertEquals("Kovačnica", organisation.venueName)
        assertEquals("Trubarjeva 5, Ljubljana", organisation.venueAddress)

        val places = eventsMCP.list(organisationId = by).associate { it.title to it.place }
        assertEquals(EventPlace("FRI", "Večna pot 113", false), places["Talk v-6"])
        assertEquals(EventPlace("Kovačnica", "Trubarjeva 5, Ljubljana", true), places["Talk v-3"])
        assertEquals(EventPlace("Kovačnica", "Trubarjeva 5, Ljubljana", true), places["Talk v-4"])
        assertEquals(EventPlace("Online", null, false), places["Talk v-5"])

        // The team's venue isn't changed, and clearing it leaves the events where they say
        organisationsMCP.update(by, venueName = "Hekovnik", venueAddress = "")
        keep(by, listOf(at("v-7", "Kovačnica", "Trubarjeva 5, Ljubljana")))
        assertNull(organisationsMCP.get(by).organisation.venueAddress)
        assertEquals(EventPlace("Hekovnik", null, true), eventsMCP.list(organisationId = by).first { it.title == "Talk v-3" }.place)

        graphql("{ events { title place { name address fromOrganisation } } eventOrganisers }")
            .andExpect(jsonPath("$.data.events[?(@.title == 'Talk v-6')].place.address", hasItem("Večna pot 113")))
            .andExpect(jsonPath("$.data.eventOrganisers").isNumber)
    }

    @Test
    fun `a place's region is its postcode's, else its name's, online or abroad, and none when nothing says`() {
        // A postcode decides, whatever else is said
        assertEquals(Region.GORENJSKA, regions.of("Kovačnica - podjetniški inkubator Kranj", "Poštna ulica 4, 4000 Kranj"))
        assertEquals(Region.JUGOVZHODNA, regions.of("Podjetniški inkubator Bela krajina", "Belokranjska cesta 36, 8340 Črnomelj"))
        assertEquals(Region.SAVINJSKA, regions.of("PC Vrelec, Celjska cesta 30, 3250 Rog. Slatina – Sejna soba v 1. nadstropju", null))
        assertEquals(Region.KOROSKA, regions.of("Stara železarna Ravne", "Koroška cesta 12, 2390 Ravne na Koroškem"))
        assertEquals(Region.POMURSKA, regions.of("Pomurski tehnološki park", "Plese 9a, 9000 Murska Sobota"))
        assertEquals(Region.ZASAVSKA, regions.of(null, "Trg revolucije 1, 1420 Trbovlje"))
        assertEquals(Region.POSAVSKA, regions.of(null, "8270 Krško"))
        assertEquals(Region.PRIMORSKO_NOTRANJSKA, regions.of(null, "6230 Postojna"))
        assertEquals(Region.OBALNO_KRASKA, regions.of(null, "Titov trg 4, 6000 Koper - Capodistria"))
        assertEquals(Region.PODRAVSKA, regions.of("Vetrinjc BarCoda Vetrinjska ulica 30, 2000 Maribor", null))
        // Without one, a municipality's or a post's name, as whole words, whatever its case and diacritics
        assertEquals(Region.OSREDNJESLOVENSKA, regions.of("Tehnološki park Ljubljana", null))
        assertEquals(Region.ZASAVSKA, regions.of("Katapult", "Gabrsko 12, Trbovlje"))
        assertEquals(Region.OSREDNJESLOVENSKA, regions.of("LUI", "Vojkova cesta 63, LJUBLJANA"))
        assertEquals(Region.GORISKA, regions.of("Vinoteka Restavracije Eda v Novi Gorici", null))
        assertEquals(Region.GORENJSKA, regions.of("Hotel v Skofji Loki", null))
        assertEquals(Region.SAVINJSKA, regions.of("Inkubator Savinjske regije", null))
        // A street named after a region or a town isn't there: Maribor's Koroška cesta, Ljubljana's Dunajska
        assertEquals(Region.PODRAVSKA, regions.of(null, "Koroška cesta 160, Maribor"))
        assertEquals(Region.OSREDNJESLOVENSKA, regions.of(null, "Dunajska cesta 5, Ljubljana"))
        assertNull(regions.of(null, "Celovška cesta 111"))
        // Online only by its name alone, and abroad before a postcode, as Austria's have four digits too
        assertEquals(Region.ONLINE, regions.of("Online", null))
        assertEquals(Region.ONLINE, regions.of("Na spletu (Zoom)", " "))
        assertEquals(Region.OSREDNJESLOVENSKA, regions.of("Online in v živo", "Večna pot 113, 1000 Ljubljana"))
        assertEquals(Region.ABROAD, regions.of("G-Gravity Incubator, Milano, Italija", null))
        assertEquals(Region.ABROAD, regions.of("Lakeside Park", "Lakeside B01, 9020 Klagenfurt"))
        // What says nothing has none: a venue alone, a year, a house number
        assertNull(regions.of("Računalniški muzej", null))
        assertNull(regions.of("MCC Kavarna 2026", "Teslova 30"))
        assertNull(regions.of(null, null))
        assertNull(regions.of(" ", ""))
    }

    @Test
    fun `an event's region is its place's, else its organisation's, unless the team gives it one, which its scraper leaves`() {
        val by = organisation("Region Test")
        fun at(id: String, name: String?, address: String?) = found(id, "Talk $id").copy(locationName = name, locationAddress = address)
        keep(by, listOf(
            at("r-1", "Kovačnica", "Poštna ulica 4, 4000 Kranj"), at("r-2", "Kovačnica", "Poštna ulica 4, 4000 Kranj"),
            at("r-3", null, null), at("r-4", "Online", null), at("r-5", "Računalniški muzej", null), at("r-6", "FRI", "Večna pot 113, 1000 Ljubljana"),
            at("r-7", "Kovačnica", "Poštna ulica 4, 4000 Kranj"),
        ))
        // Its venue, which its events gave it, tells the organisation's region
        val organisation = organisationsMCP.get(by).organisation
        assertEquals(Region.GORENJSKA, organisation.region)
        assertFalse(organisation.regionManual)

        fun regions() = eventsMCP.list(organisationId = by).associate { it.title to it.region }
        assertEquals(Region.GORENJSKA, regions()["Talk r-1"])
        assertEquals(Region.OSREDNJESLOVENSKA, regions()["Talk r-6"])
        assertEquals(Region.ONLINE, regions()["Talk r-4"])
        // Those that don't say, or say what tells nothing, go by their organisation's
        assertEquals(Region.GORENJSKA, regions()["Talk r-3"])
        assertEquals(Region.GORENJSKA, regions()["Talk r-5"])
        assertEquals(setOf("Talk r-1", "Talk r-2", "Talk r-3", "Talk r-5", "Talk r-7"), eventsMCP.list(organisationId = by, region = "gorenjska").map { it.title }.toSet())
        assertEquals(listOf("Talk r-4"), eventsMCP.list(organisationId = by, region = "ONLINE").map { it.title })
        assertFailsWith<IllegalArgumentException> { eventsMCP.list(region = "Kranjska") }

        // The team gives a scraper's event its region, which stays when it's found again, elsewhere too
        val museum = checkNotNull(eventsMCP.list(organisationId = by).first { it.title == "Talk r-5" }.id)
        val given = eventsMCP.update(museum, region = "Osrednjeslovenska")
        assertEquals(Region.OSREDNJESLOVENSKA, given.region)
        assertTrue(given.regionManual)
        keep(by, listOf(at("r-5", "Računalniški muzej", "Celovška cesta 111, 4000 Kranj")))
        assertEquals(Region.OSREDNJESLOVENSKA, regions()["Talk r-5"])
        assertTrue(history.of(by).any { it.summary == "Changed the event \"Talk r-5\"" && it.fields.any { f -> f.field == "Region" && f.to == "Osrednjeslovenska" } })
        // Cleared, it's what its place tells again
        val cleared = eventsMCP.update(museum, region = "")
        assertEquals(Region.GORENJSKA, cleared.region)
        assertFalse(cleared.regionManual)

        // The organisation's follows its venue, until the team gives it one
        assertEquals(Region.PODRAVSKA, organisationsMCP.update(by, venueName = "Maribox", venueAddress = "Loška ulica 13, 2000 Maribor").region)
        assertEquals(Region.PODRAVSKA, regions()["Talk r-3"])
        val pinned = organisationsMCP.update(by, region = "ZASAVSKA")
        assertTrue(pinned.regionManual)
        assertEquals(Region.ZASAVSKA, organisationsMCP.update(by, venueAddress = "Poštna ulica 4, 4000 Kranj").region)
        assertEquals(Region.ZASAVSKA, regions()["Talk r-3"])
        assertEquals(Region.GORENJSKA, organisationsMCP.update(by, region = "").region)

        // The team's own says where it is, or is given its region
        val own = eventsMCP.create(by, "Hekaton", "2030-06-01", locationName = "Katapult", locationAddress = "Gabrsko 12, Trbovlje")
        assertEquals(Region.ZASAVSKA, own.region)
        assertEquals(Region.ABROAD, eventsMCP.create(by, "Izlet", "2030-06-02", region = "abroad").region)

        graphql("{ events { title region regionManual } }")
            .andExpect(jsonPath("$.data.events[?(@.title == 'Talk r-6')].region", hasItem("OSREDNJESLOVENSKA")))
            .andExpect(jsonPath("$.data.events[?(@.title == 'Talk r-4')].region", hasItem("ONLINE")))
        graphql("""mutation { updateEvent(id: "$museum", input: { region: "POSAVSKA" }) { region regionManual } }""", writer)
            .andExpect(jsonPath("$.data.updateEvent.region").value("POSAVSKA"))
            .andExpect(jsonPath("$.data.updateEvent.regionManual").value(true))
        graphql("""mutation { updateOrganisation(id: "$by", input: { region: "KOROSKA" }) { region regionManual } }""", writer)
            .andExpect(jsonPath("$.data.updateOrganisation.region").value("KOROSKA"))
            .andExpect(jsonPath("$.data.updateOrganisation.regionManual").value(true))
    }

    @Test
    fun `a venue is where most of an organisation's events are, and none when they're anywhere`() {
        val by = Organisation("Usual")
        fun at(name: String?, address: String?) = Event(by, "MEETUP", null, "x", java.time.OffsetDateTime.now()).apply {
            locationName = name; locationAddress = address
        }
        assertNull(usualVenue(listOf(at("A", "Street 1"))))
        assertNull(usualVenue(listOf(at("A", "Street 1"), at("A", "Street 1"), at("B", "Street 2"), at("C", "Street 3"))))
        assertEquals("A" to "Street 1", usualVenue(listOf(at("A", "Street 1"), at(null, "street 1"), at("A", "Street 1"), at("B", "Street 2"))))
        // The whole address in the place's name, as many sources give it; online ones aren't places
        assertEquals("Katapult, Gabrsko 12, Trbovlje" to null,
            usualVenue(listOf(at("Katapult, Gabrsko 12, Trbovlje", null), at("katapult, gabrsko 12, trbovlje", null), at("Na spletu", null), at("Online", null))))
        assertNull(usualVenue(listOf(at("Online", null), at("Online", null))))
    }

    private fun graphql(query: String, auth: RequestPostProcessor? = null): ResultActions {
        val request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
            .content(JsonMapper.builder().build().writeValueAsString(mapOf("query" to query)))
        auth?.let(request::with)
        val actions = mvc.perform(request)
        val result = actions.andReturn()
        return (if (result.request.isAsyncStarted) mvc.perform(asyncDispatch(result)) else actions)
            .andExpect(status().isOk)
    }
}
