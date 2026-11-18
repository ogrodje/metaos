package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeetupComParserTest {

    private val html = """<!DOCTYPE html><html><body>
        <script id="__NEXT_DATA__" type="application/json">
        {"props": {"pageProps": {"__APOLLO_STATE__": {
          "Event:123": {"__typename": "Event", "id": "123", "title": " Test Meetup Event ",
            "description": "A test event description", "eventUrl": "https://www.meetup.com/test-group/events/123/",
            "dateTime": "2026-06-01T18:00:00+02:00", "endTime": "2026-06-01T20:00:00+02:00", "venue": null},
          "Event:456": {"__typename": "Event", "id": "456", "title": "Event With Venue",
            "description": "Event at a physical venue", "eventUrl": "https://www.meetup.com/test-group/events/456/",
            "dateTime": "2026-07-15T18:00:00+02:00", "endTime": "2026-07-15T20:00:00+02:00", "venue": {"__ref": "Venue:789"}},
          "Venue:789": {"__typename": "Venue", "id": "789", "name": "Test Venue Ljubljana", "address": "Main Street 1",
            "city": "Ljubljana", "state": "Slovenia"},
          "Event:old": {"__typename": "Event", "id": "old", "title": "Old Past Event", "description": "This event is too old",
            "eventUrl": "https://www.meetup.com/test-group/events/old/", "dateTime": "2024-01-01T18:00:00+01:00",
            "endTime": "2024-01-01T20:00:00+01:00", "venue": null},
          "Event:broken": {"__typename": "Event", "id": "broken", "title": "No date"}
        }}}}
        </script></body></html>"""

    private val now = ZonedDateTime.parse("2026-08-01T12:00:00+02:00[Europe/Ljubljana]")
    private val eventsUrl = URI("https://www.meetup.com/test-group/events")

    @Test
    fun `reads the events of the apollo state, leaving out those over six months old`() {
        val events = MeetupComParser.events(eventsUrl, html, now)

        assertEquals(setOf("meetup-123", "meetup-456"), events.map { it.externalId }.toSet())
        val first = events.single { it.externalId == "meetup-123" }
        assertEquals("Test Meetup Event", first.title)
        assertEquals(Instant.parse("2026-06-01T16:00:00Z"), first.start)
        assertEquals(Instant.parse("2026-06-01T18:00:00Z"), first.end)
        assertEquals("A test event description", first.description)
        assertEquals("https://www.meetup.com/test-group/events/123/", first.eventUrl)
        assertEquals("https://www.meetup.com/test-group/events", first.sourceUrl)
        assertEquals(Source.MEETUP, first.source)
        assertNull(first.locationName)
        assertNull(first.locationAddress)
    }

    @Test
    fun `gives an event its venue`() {
        val venue = MeetupComParser.events(eventsUrl, html, now).single { it.externalId == "meetup-456" }
        assertEquals("Test Venue Ljubljana", venue.locationName)
        assertEquals("Main Street 1, Ljubljana, Slovenia", venue.locationAddress)
    }

    @Test
    fun `asks the group's events page, whatever page of the group the url is`() {
        for (url in listOf("https://www.meetup.com/test-group/", "https://www.meetup.com/test-group/events/314159976/")) {
            val web = FakeWeb(mapOf("https://www.meetup.com/test-group/events" to html))
            MeetupComParser.parse(context(web), URI(url))
            assertEquals(listOf("https://www.meetup.com/test-group/events"), web.asked)
        }
    }

    @Test
    fun `a page without the apollo state or next data has no events`() {
        val noState = """<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{}}}</script></body></html>"""
        assertTrue(MeetupComParser.events(eventsUrl, noState, now).isEmpty())
        assertTrue(MeetupComParser.events(eventsUrl, "<html><body>Nothing</body></html>", now).isEmpty())
    }

    @Test
    fun `takes meetup com's urls`() {
        assertTrue(MeetupComParser.accepts(SourceField.MEETUP, URI("https://www.meetup.com/slovenia-kafka/")))
        assertTrue(MeetupComParser.accepts(SourceField.WEBSITE, URI("https://meetup.com/x")))
        assertTrue(!MeetupComParser.accepts(SourceField.MEETUP, URI("https://luma.com/sloai")))
    }
}
