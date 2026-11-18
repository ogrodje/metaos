package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.*
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ICalParserTest {
    private fun at(year: Int, month: Int, day: Int) = LocalDateTime.of(year, month, day, 0, 0).atOffset(ZoneOffset.UTC)

    @Test
    fun `reads finance ikt's calendar`() {
        val after = at(2025, 4, 10).minusMonths(2)
        val events = ICalReader.read(FakeWeb.fixture("ikt.ics"), after.toInstant(), after.plusMonths(4).toInstant())
        assertEquals(25, events.size)
        assertTrue(events.map { it.title }.containsAll(setOf("SRC Tech day 2025", "Dnevi slovenske informatike 2025")))
        assertTrue(events.none { it.id.endsWith("@google.com") })
        assertEquals(events.sortedBy { it.start }, events)
    }

    @Test
    fun `reads sasa's calendar`() {
        val after = at(2025, 8, 8).minusMonths(2)
        val events = ICalReader.read(FakeWeb.fixture("sasa.ical"), after.toInstant(), after.plusMonths(6).toInstant())
        assertEquals(setOf("STARTUP CHALLENGE 2025", "SAŠA za mlade"), events.map { it.title }.toSet())
    }

    private val calendar = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:abc123@google.com
DTSTAMP:20260101T000000Z
DTSTART;VALUE=DATE:20991010
DTEND;VALUE=DATE:20991012
SUMMARY: Konferenca
DESCRIPTION:Več na <a href="https://example.si/konferenca">strani</a> in https://example.si/druga
END:VEVENT
BEGIN:VEVENT
UID:def456
DTSTAMP:20260101T000000Z
DTSTART:20991101T170000Z
DTEND:20991101T190000Z
SUMMARY:Meetup
URL:https://example.si/meetup
END:VEVENT
BEGIN:VEVENT
UID:old
DTSTAMP:20260101T000000Z
DTSTART:20200101T170000Z
SUMMARY:Old
END:VEVENT
END:VCALENDAR
""".replace("\n", "\r\n")

    @Test
    fun `days, times, ids and links as goo had them`() {
        val url = URI("https://calendar.example.si/basic.ics")
        val events = ICalParser.parse(context(FakeWeb(mapOf(url.toString() to calendar))), url)
        assertEquals(listOf("abc123", "def456"), events.map { it.externalId })

        val day = events[0]
        assertEquals("Konferenca", day.title)
        assertEquals(Instant.parse("2099-10-09T22:01:00Z"), day.start)
        assertEquals(Instant.parse("2099-10-11T21:59:00Z"), day.end)
        assertFalse(day.hasStartTime)
        assertFalse(day.hasEndTime)
        assertEquals("https://example.si/konferenca", day.eventUrl)
        assertEquals(Source.ICAL, day.source)
        assertEquals(url.toString(), day.sourceUrl)

        val time = events[1]
        assertEquals(Instant.parse("2099-11-01T17:00:00Z"), time.start)
        assertEquals(Instant.parse("2099-11-01T19:00:00Z"), time.end)
        assertTrue(time.hasStartTime && time.hasEndTime)
        assertEquals("https://example.si/meetup", time.eventUrl)
        assertNull(time.description)
        assertNull(time.locationName)
    }

    @Test
    fun `a calendar that doesn't parse is no events`() {
        val url = URI("https://example.si/broken.ics")
        assertTrue(ICalParser.parse(context(FakeWeb(mapOf(url.toString() to "BEGIN:VCALENDAR\r\nnonsense"))), url).isEmpty())
    }

    @Test
    fun `takes the ical field only`() {
        assertTrue(ICalParser.accepts(SourceField.ICAL, URI("https://sasainkubator.si/dogodki/seznam/?ical=1")))
        assertTrue(!ICalParser.accepts(SourceField.WEBSITE, URI("https://sasainkubator.si/dogodki/seznam/?ical=1")))
    }
}
