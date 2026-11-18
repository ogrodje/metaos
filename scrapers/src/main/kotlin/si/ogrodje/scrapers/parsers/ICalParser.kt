package si.ogrodje.scrapers.parsers

import net.fortuna.ical4j.data.ParserException
import org.slf4j.LoggerFactory
import si.ogrodje.scrapers.*
import java.net.URI

/**
 * An organisation's iCal calendar, from its iCal link, whatever site it's on, read by [ICalReader]: the events of the
 * last two months on, by their UIDs. A calendar ical4j can't parse is no events, as Goo had it.
 */
object ICalParser : Parser {
    override val source = Source.ICAL
    private val log = LoggerFactory.getLogger(javaClass)

    override fun accepts(field: SourceField, url: URI) = field == SourceField.ICAL

    override fun parse(context: ScrapeContext, url: URI): List<ScrapedEvent> = events(url, context.web.get(url))

    /** The events in the calendar [raw], from [url] */
    fun events(url: URI, raw: String): List<ScrapedEvent> {
        val events = try {
            ICalReader.read(raw)
        } catch (e: ParserException) {
            log.warn("The calendar at {} doesn't parse: {}", url, e.message)
            return emptyList()
        }
        return events.map {
            ScrapedEvent(
                source = source,
                externalId = it.id,
                sourceUrl = url.toString(),
                title = it.title,
                start = it.start,
                end = it.end,
                hasStartTime = it.hasStartTime,
                hasEndTime = it.hasEndTime,
                description = it.description,
                eventUrl = it.eventUrl,
                locationName = it.location.name,
                locationAddress = it.location.address,
            )
        }
    }
}
