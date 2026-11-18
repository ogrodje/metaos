package si.ogrodje.scrapers.parsers

import si.ogrodje.scrapers.LJUBLJANA
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * When an event of Tehnološki park Ljubljana is, from its page's "Datum dogodka:", in one of three ways: a range of
 * days ("07. 05. 2025 - 16. 05. 2025"), a day and a time ("17. 04. 2025 ob 11:00", which lasts two hours), or a day.
 * Only a day with a time has its start's time; no end has one.
 */
object TehnoloskiParkLjubljanaDurationParser {
    /** A start and an end, each with whether it has its time */
    data class Duration(val start: Instant, val hasStartTime: Boolean, val end: Instant, val hasEndTime: Boolean)

    private const val DAY = """\d{2}\.\s\d{2}\.\s\d{4}"""
    private val range = Regex("""($DAY)\s*-\s*($DAY)""")
    private val dayWithTime = Regex("""($DAY)\s*ob\s*(\d{2}:\d{2})""")
    private val day = Regex("($DAY)")
    private val format = DateTimeFormatter.ofPattern("dd. MM. yyyy HH:mm")

    private fun at(day: String, time: String) = LocalDateTime.parse("$day $time", format)

    private fun LocalDateTime.instant() = atZone(LJUBLJANA).toInstant()

    fun parse(raw: String): Duration {
        range.matchEntire(raw)?.let { m ->
            return Duration(at(m.groupValues[1], "00:00").instant(), false, at(m.groupValues[2], "23:59").instant(), false)
        }
        dayWithTime.matchEntire(raw)?.let { m ->
            val start = at(m.groupValues[1], m.groupValues[2]).instant()
            return Duration(start, true, start.plusSeconds(2 * 60 * 60), false)
        }
        day.matchEntire(raw)?.let { m ->
            return Duration(at(m.groupValues[1], "00:00").instant(), false, at(m.groupValues[1], "23:59").instant(), false)
        }
        throw IllegalArgumentException("Invalid date format: $raw")
    }
}
