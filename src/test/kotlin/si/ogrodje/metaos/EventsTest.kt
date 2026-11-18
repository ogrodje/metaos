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
    fun `a venue is where most of an organisation's events are, and none when they're anywhere`() {
        val by = Organisation("Usual")
        fun at(name: String?, address: String) = Event(by, "MEETUP", null, "x", java.time.OffsetDateTime.now()).apply {
            locationName = name; locationAddress = address
        }
        assertNull(usualVenue(listOf(at("A", "Street 1"))))
        assertNull(usualVenue(listOf(at("A", "Street 1"), at("A", "Street 1"), at("B", "Street 2"), at("C", "Street 3"))))
        assertEquals("A" to "Street 1", usualVenue(listOf(at("A", "Street 1"), at(null, "street 1"), at("A", "Street 1"), at("B", "Street 2"))))
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
