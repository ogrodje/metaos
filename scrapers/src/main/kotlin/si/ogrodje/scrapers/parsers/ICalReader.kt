package si.ogrodje.scrapers.parsers

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal

/** An event of an iCal calendar, as [ICalReader] reads it */
data class ICalEvent(
    val id: String,
    val title: String,
    val start: Instant,
    val description: String? = null,
    val eventUrl: String? = null,
    val end: Instant? = null,
    val hasStartTime: Boolean = true,
    val hasEndTime: Boolean = true,
    val location: Location = Location.NONE,
)

/**
 * Reads an iCal calendar's events with ical4j, as Goo's ICalReader: its ID is the event's UID without Google's
 * `@google.com`, its link the event's URL, else the first link in its description. A day without a time starts at
 * 00:01 and ends at 23:59 of its last day; a time without a zone, or with a TZID, is Ljubljana's. Its LOCATION is one
 * line, its venue's name and its address ("Saša Inkubator, Šaleška cesta 2A, 3320 Velenje"), or a meeting's link
 * (online), which [Locations.parse] tells apart. An event it can't read is skipped; the rest are those that start
 * after [ICalReader.read]'s `after`, by start.
 */
object ICalReader {
    private val zone = ZoneId.of("Europe/Ljubljana")
    private val dayFormat = DateTimeFormatter.ofPattern("yyyyMMdd[ [HH][:mm][:ss][.SSS]]")
    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX")
    private val offsetFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXXXXX")
    private val localFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val offset = Regex(""".*[+-]\d{2}:?\d{2}$""")
    private val href = Regex("""href=["']([^"']+)["']""")
    private val plainUrl = Regex("""(https?://[^\s"'<>()]+)""")

    /** Goo kept the events of the last two months on */
    fun defaultAfter(): Instant = ZonedDateTime.now(ZoneId.of("Europe/Paris")).minusMonths(2).toInstant()

    /** The events of the calendar [raw] that start after [after] (and before [before]); throws ical4j's ParserException */
    fun read(raw: String, after: Instant = defaultAfter(), before: Instant? = null): List<ICalEvent> {
        val calendar = StringReader(raw).use { CalendarBuilder().build(it) }
        return calendar.getComponents<VEvent>(Component.VEVENT)
            .mapNotNull { runCatching { event(it) }.getOrNull() }
            .filter { it.start > after && (before == null || it.start < before) }
            .sortedBy { it.start }
    }

    private fun event(event: VEvent): ICalEvent {
        val uid = event.uid.orElseThrow().value.replace("@google.com", "")
        val (start, hasStartTime) = time(requireNotNull(event.getDateTimeStart<Temporal>()).value, beginning = true)
        val end = event.getEndDate<Temporal>(true).map { time(it.value, beginning = false) }.orElse(null)
        val details = event.description?.value?.trim()
        val eventUrl = runCatching { event.url!!.uri.toString() }.getOrNull()
        val link = (listOfNotNull(eventUrl) + urls(details)).distinct().firstOrNull()
        return ICalEvent(
            id = uid,
            title = requireNotNull(event.summary).value.trim(),
            start = start,
            description = details,
            eventUrl = link?.takeIf { runCatching { URI(it) }.isSuccess },
            end = end?.first,
            hasStartTime = hasStartTime,
            hasEndTime = end?.second ?: false,
            location = Locations.parse(event.getProperty<Property>(Property.LOCATION).map { it.value }.orElse(null)),
        )
    }

    private fun urls(details: String?): List<String> {
        if (details == null) return emptyList()
        val hrefs = href.findAll(details).map { it.groupValues[1] }.filter { it.startsWith("http://") || it.startsWith("https://") }
        return (hrefs + plainUrl.findAll(details).map { it.value }).distinct().toList()
    }

    /** An iCal date or time, and whether it has a time of day */
    private fun time(raw: String, beginning: Boolean): Pair<Instant, Boolean> = when {
        raw.length == 8 ->
            LocalDateTime.parse(raw + if (beginning) " 00:01:00" else " 23:59:00", dayFormat)
                .minusHours(if (beginning) 0 else 24).atZone(zone).toInstant() to false
        raw.endsWith("Z") -> ZonedDateTime.parse(raw, utcFormat).toInstant() to true
        offset.matches(raw) -> OffsetDateTime.parse(raw, offsetFormat).toInstant() to true
        else -> LocalDateTime.parse(raw, localFormat).atZone(zone).toInstant() to true
    }
}
