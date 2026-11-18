package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.io.IOException
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventbriteParserTest {
    private val url = URI("https://www.eventbrite.com/o/ogrodje-103424018581")

    private val withVenue = """{
        "id": "1986276123354", "name": "After Hours Premier: Pride Celebration",
        "url": "https://www.eventbrite.com/e/after-hours-premier-pride-celebration-tickets-1986276123354",
        "start_date": "2026-07-17", "start_time": "19:00:00", "end_date": "2026-07-17", "end_time": "22:30:00",
        "timezone": "Europe/Ljubljana", "is_online_event": false, "hide_start_date": false, "hide_end_date": false,
        "summary": "An evening celebration.",
        "primary_venue": {"name": "Kino Šiška", "address": {"localized_address_display": "Trg prekomorskih brigad 3, Ljubljana"}}
    }"""

    private val online = """{
        "id": "2000000000001", "name": "Online Webinar",
        "url": "https://www.eventbrite.com/e/online-webinar-tickets-2000000000001",
        "start_date": "2026-08-01", "start_time": "10:00:00", "end_date": "2026-08-01", "end_time": "11:00:00",
        "timezone": "Europe/Ljubljana", "is_online_event": true, "hide_start_date": false, "hide_end_date": true,
        "summary": "", "primary_venue": null
    }"""

    private fun nextData(items: String) = """{"props":{"pageProps":{"upcomingEventsTotal":2,"upcomingEvents":[$items]}}}"""

    @Test
    fun `reads the upcoming events of the next data`() {
        val events = EventbriteParser.events(url, nextData("$withVenue,$online"))
        assertEquals(2, events.size)
        assertEquals(listOf("eventbrite-1986276123354", "eventbrite-2000000000001"), events.map { it.externalId })
        assertTrue(events.all { it.source == Source.EVENTBRITE && it.sourceUrl == url.toString() })
    }

    @Test
    fun `reads the date, time and time zone apart`() {
        val event = EventbriteParser.events(url, nextData(withVenue)).single()
        assertEquals(Instant.parse("2026-07-17T17:00:00Z"), event.start)
        assertEquals(Instant.parse("2026-07-17T20:30:00Z"), event.end)
        assertEquals("An evening celebration.", event.description)
        assertEquals("Kino Šiška", event.locationName)
        assertEquals("Trg prekomorskih brigad 3, Ljubljana", event.locationAddress)
        assertEquals("https://www.eventbrite.com/e/after-hours-premier-pride-celebration-tickets-1986276123354", event.eventUrl)
    }

    @Test
    fun `an online event has no location`() {
        val event = EventbriteParser.events(url, nextData(online)).single()
        assertNull(event.locationName)
        assertNull(event.locationAddress)
        assertNull(event.description)
        assertFalse(event.hasEndTime)
        assertTrue(event.hasStartTime)
    }

    @Test
    fun `no events, and a broken one skipped`() {
        assertTrue(EventbriteParser.events(url, nextData("")).isEmpty())
        val events = EventbriteParser.events(url, nextData("""{"id":"x","name":"No dates"},$withVenue"""))
        assertEquals("After Hours Premier: Pride Celebration", events.single().title)
    }

    @Test
    fun `an organizer without events is no events, the bot check and invalid json fail`() {
        val empty = """{"props":{"pageProps":{"organizer":{"id":"1","name":"Ogrodje"},"upcomingEvents":[],"upcomingEventsTotal":0}}}"""
        assertTrue(EventbriteParser.events(url, empty).isEmpty())

        val challenge = """{"props":{"pageProps":{"locale":"en-US","messages":{"Captcha":"x"},"globalHeaderProps":{},"footerProps":{},"serverUrl":"x"}}}"""
        assertFailsWith<EventbriteParser.Blocked> { EventbriteParser.events(url, challenge) }
        assertFailsWith<IOException> { EventbriteParser.events(url, "not json") }
    }

    @Test
    fun `takes eventbrite's urls`() {
        assertTrue(EventbriteParser.accepts(SourceField.EVENTBRITE, url))
        assertTrue(EventbriteParser.accepts(SourceField.WEBSITE, URI("https://www.eventbrite.co.uk/o/x")).not())
        assertTrue(EventbriteParser.accepts(SourceField.WEBSITE, URI("https://www.eventbrite.com/e/x")))
    }
}
