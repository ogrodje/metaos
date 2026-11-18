package si.ogrodje.metaos

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Where an organisation announces its events, by the field of the organisation the link is in, as Goo's meetups had
 * them (the scrapers' SourceField, scrapers/Model.kt): iCal and Luma by their field, the rest by the link's site
 */
enum class EventField { WEBSITE, MEETUP, EVENTBRITE, ICAL, LUMA, KOMPOT }

/** The team's own events; the rest are the scrapers' parsers' (their Source, scrapers/Model.kt), e.g. MEETUP */
const val MANUAL_SOURCE = "MANUAL"

/** Ljubljana's, where the events are and the days are told */
val LJUBLJANA: ZoneId = ZoneId.of("Europe/Ljubljana")

/**
 * An event (V36__events.sql): one of Slovenia's tech, developer and startup events, by an organisation, found by a
 * scraper on its links ([source], [externalId]) or added by the team ([MANUAL_SOURCE]). Its start and end are instants,
 * with a time of day only where [hasStartTime] and [hasEndTime] say. Never deleted: hidden, or promoted as Ogrodje's pick.
 */
@Entity
@Table(name = "events")
class Event(
    @ManyToOne(optional = false)
    @JoinColumn(name = "organisation_id")
    var organisation: Organisation,

    @Column(nullable = false, columnDefinition = "text") val source: String,
    @Column(columnDefinition = "text") val externalId: String?,
    @Column(nullable = false, columnDefinition = "text") var title: String,
    @Column(nullable = false) var startsAt: OffsetDateTime,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Column(columnDefinition = "text") var sourceUrl: String? = null
    @Column(columnDefinition = "text") var description: String? = null
    @Column(columnDefinition = "text") var url: String? = null
    var endsAt: OffsetDateTime? = null
    @Column(nullable = false) var hasStartTime: Boolean = true
    @Column(nullable = false) var hasEndTime: Boolean = true
    @Column(columnDefinition = "text") var locationName: String? = null
    @Column(columnDefinition = "text") var locationAddress: String? = null
    var hiddenAt: OffsetDateTime? = null
    var promotedAt: OffsetDateTime? = null

    val manual: Boolean get() = source == MANUAL_SOURCE

    /** It's listed until it ends, or until it starts when it has no end */
    fun upcoming(now: Instant) = (endsAt ?: startsAt).toInstant() >= now

    override fun equals(other: Any?) = this === other || (other is Event && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Event(id=$id, title=$title)"
}

/** How one of an organisation's links was last scraped: when, by which parser, how many events, or why it failed */
@Entity
@Table(name = "event_scrapes")
@IdClass(EventScrapeKey::class)
class EventScrape(
    @Id @Column(name = "organisation_id") val organisationId: UUID,
    @Id @Enumerated(EnumType.STRING) @Column(columnDefinition = "text") val field: EventField,
    @Column(nullable = false, columnDefinition = "text") var url: String,
    @Column(columnDefinition = "text") var source: String?,
    @Column(nullable = false) var scrapedAt: OffsetDateTime,
    @Column(nullable = false) var events: Int,
    @Column(columnDefinition = "text") var error: String?,
)

data class EventScrapeKey(val organisationId: UUID? = null, val field: EventField? = null) : java.io.Serializable

interface EventsRepository : JpaRepository<Event, UUID> {
    @Query("select e from Event e join fetch e.organisation where coalesce(e.endsAt, e.startsAt) >= :since order by e.startsAt")
    fun findEndingSince(since: OffsetDateTime): List<Event>

    @Query("select e from Event e join fetch e.organisation where e.organisation in :organisations order by e.startsAt desc")
    fun findByOrganisations(organisations: Collection<Organisation>): List<Event>

    fun findByOrganisationAndExternalIdIn(organisation: Organisation, externalIds: Collection<String>): List<Event>
}

interface EventScrapesRepository : JpaRepository<EventScrape, EventScrapeKey> {
    fun findByOrganisationIdIn(ids: Collection<UUID>): List<EventScrape>
}

/**
 * What an event is to be, for creating and changing one: what's null stays as it is, and empty text clears it. Its
 * start and end are a time (2026-11-16T18:00, in Ljubljana, or with its offset) or a day (2026-11-16), which has no
 * time of day.
 */
data class EventChanges(
    val organisation: UUID? = null,
    val title: String? = null,
    val start: String? = null,
    val end: String? = null,
    val description: String? = null,
    val url: String? = null,
    val locationName: String? = null,
    val locationAddress: String? = null,
    val hidden: Boolean? = null,
    val promoted: Boolean? = null,
)

/** One that a scraper found, as MetaOS keeps it (the scrapers' ScrapedEvent, of the same shape) */
data class FoundEvent(
    val source: String,
    val externalId: String,
    val sourceUrl: String?,
    val title: String,
    val start: Instant,
    val end: Instant? = null,
    val hasStartTime: Boolean = true,
    val hasEndTime: Boolean = true,
    val description: String? = null,
    val eventUrl: String? = null,
    val locationName: String? = null,
    val locationAddress: String? = null,
)

/** How many of what a scraper found were new, and how many changed */
data class FoundCounts(val added: Int, val changed: Int)

/** How far back events are gathered: a year (OG-64) */
val EVENTS_KEPT_FOR: Duration = Duration.ofDays(365)

/**
 * Events, for GraphQL (EventsGraphQL), MCP (EventsMCP) and the scrapers' workflow (EventsSync.kt) alike, as
 * Organisations are. Those anyone sees are the upcoming ones that aren't hidden, of organisations that aren't deleted.
 */
@Component
class Events(
    private val events: EventsRepository,
    private val scrapes: EventScrapesRepository,
    private val organisations: Organisations,
) {

    fun find(id: UUID): Event = requireNotNull(events.findByIdOrNull(id)) { "Event $id not found" }

    /** Those that haven't ended yet (or started, without an end), the soonest first; the hidden ones only when asked */
    fun upcoming(now: Instant = Instant.now(), hidden: Boolean = false): List<Event> =
        events.findEndingSince(now.atOffset(java.time.ZoneOffset.UTC)).filter { hidden || it.hiddenAt == null }

    /** Every event of each of [organisations], the latest first */
    fun of(organisations: List<Organisation>): Map<Organisation, List<Event>> {
        val byOrganisation = events.findByOrganisations(organisations).groupBy { it.organisation }
        return organisations.associateWith { byOrganisation[it].orEmpty() }
    }

    fun scrapesOf(organisations: List<Organisation>): Map<Organisation, List<EventScrape>> {
        val byId = scrapes.findByOrganisationIdIn(organisations.mapNotNull { it.id }).groupBy { it.organisationId }
        return organisations.associateWith { o -> byId[o.id].orEmpty().sortedBy { it.field.ordinal } }
    }

    @Transactional
    fun create(changes: EventChanges): Event {
        val organisation = organisations.find(requireNotNull(changes.organisation) { "An event needs its organisation" })
        val title = requireNotNull(changes.title?.trim()?.ifEmpty { null }) { "An event needs its title" }
        val (start, hasStart) = requireNotNull(changes.start?.let { time(it, "Its start") }) { "An event needs its start" }
        val event = Event(organisation, MANUAL_SOURCE, null, title, start).apply { hasStartTime = hasStart }
        change(event, changes.copy(organisation = null, title = null, start = null))
        return events.save(event)
    }

    /** Changes [id]: the team's own wholly, a scraper's only whether it's hidden or promoted, as its scraper keeps the rest */
    @Transactional
    fun update(id: UUID, changes: EventChanges): Event {
        val event = find(id)
        require(event.manual || listOf(changes.title, changes.start, changes.end, changes.description, changes.url,
            changes.locationName, changes.locationAddress, changes.organisation).all { it == null }) {
            "${event.title} is found by a scraper (${event.source}), which keeps what it says: only whether it's hidden or promoted changes"
        }
        change(event, changes)
        return event
    }

    private fun change(event: Event, changes: EventChanges) {
        changes.organisation?.let { event.organisation = organisations.find(it) }
        changes.title?.let { title -> event.title = requireNotNull(title.trim().ifEmpty { null }) { "Its title must not be blank" } }
        changes.start?.let { text ->
            val (start, hasTime) = requireNotNull(time(text, "Its start")) { "An event needs its start" }
            event.startsAt = start
            event.hasStartTime = hasTime
        }
        changes.end?.let { text ->
            val end = time(text, "Its end", end = true)
            event.endsAt = end?.first
            event.hasEndTime = end?.second ?: true
        }
        require(event.endsAt == null || !event.endsAt!!.isBefore(event.startsAt)) { "It can't end before it starts" }
        changes.description?.let { event.description = it.trim().ifEmpty { null } }
        changes.url?.let { url ->
            event.url = url.trim().ifEmpty { null }?.also { require(it.matches(Regex("^https?://.+"))) { "Its link is http or https, not $it" } }
        }
        changes.locationName?.let { event.locationName = it.trim().ifEmpty { null } }
        changes.locationAddress?.let { event.locationAddress = it.trim().ifEmpty { null } }
        changes.hidden?.let { hidden -> if (hidden != (event.hiddenAt != null)) event.hiddenAt = if (hidden) OffsetDateTime.now() else null }
        changes.promoted?.let { promoted -> if (promoted != (event.promotedAt != null)) event.promotedAt = if (promoted) OffsetDateTime.now() else null }
    }

    /**
     * Keeps what a scraper found on [organisation]'s links: new ones added, those it has already changed where what
     * the scraper says changed (the team's hiding and promoting stay), nothing written for the rest. Older than a year
     * isn't kept, and none is deleted.
     */
    @Transactional
    fun keep(organisationId: UUID, found: List<FoundEvent>, now: Instant = Instant.now()): FoundCounts {
        val organisation = organisations.find(organisationId)
        val since = now.minus(EVENTS_KEPT_FOR)
        val wanted = found.filter { (it.end ?: it.start) >= since }.distinctBy { it.externalId }
        if (wanted.isEmpty()) return FoundCounts(0, 0)
        val known = events.findByOrganisationAndExternalIdIn(organisation, wanted.map { it.externalId }).associateBy { it.externalId }
        var added = 0
        var changed = 0
        wanted.forEach { f ->
            val event = known[f.externalId]
            if (event == null) {
                events.save(Event(organisation, f.source, f.externalId, f.title.trim(), f.start.atOffset(java.time.ZoneOffset.UTC)).also { found(it, f) })
                added++
            } else if (differs(event, f)) {
                found(event, f)
                changed++
            }
        }
        return FoundCounts(added, changed)
    }

    private fun differs(event: Event, f: FoundEvent) =
        event.title != f.title.trim() || event.startsAt.toInstant() != f.start || event.endsAt?.toInstant() != f.end ||
            event.hasStartTime != f.hasStartTime || event.hasEndTime != f.hasEndTime || event.description != f.description?.trim()?.ifEmpty { null } ||
            event.url != f.eventUrl || event.locationName != f.locationName?.trim()?.ifEmpty { null } ||
            event.locationAddress != f.locationAddress?.trim()?.ifEmpty { null } || event.sourceUrl != f.sourceUrl

    private fun found(event: Event, f: FoundEvent) {
        event.title = f.title.trim()
        event.startsAt = f.start.atOffset(java.time.ZoneOffset.UTC)
        // A scraper's end before its start is no end
        event.endsAt = f.end?.takeIf { it >= f.start }?.atOffset(java.time.ZoneOffset.UTC)
        event.hasStartTime = f.hasStartTime
        event.hasEndTime = f.hasEndTime
        event.description = f.description?.trim()?.ifEmpty { null }
        event.url = f.eventUrl?.takeIf { it.matches(Regex("^https?://.+")) }
        event.locationName = f.locationName?.trim()?.ifEmpty { null }
        event.locationAddress = f.locationAddress?.trim()?.ifEmpty { null }
        event.sourceUrl = f.sourceUrl
    }

    /** That [field] of [organisationId] was scraped just now, at [url], finding [count] events, or failing with [error] */
    @Transactional
    fun scraped(organisationId: UUID, field: EventField, url: String, source: String?, count: Int, error: String?) {
        val scrape = scrapes.findByIdOrNull(EventScrapeKey(organisationId, field))
            ?: EventScrape(organisationId, field, url, source, OffsetDateTime.now(), count, error)
        scrape.url = url
        scrape.source = source
        scrape.scrapedAt = OffsetDateTime.now()
        scrape.events = count
        scrape.error = error?.take(2000)
        scrapes.save(scrape)
    }

    companion object {
        /**
         * A time as the API takes one: an instant with its offset (2026-11-16T18:00:00+01:00, or Z), a time in Ljubljana
         * (2026-11-16T18:00), or a day (2026-11-16), which has no time of day: its start then, or its end's last minute.
         * Null for empty text.
         */
        fun time(text: String, what: String, end: Boolean = false): Pair<OffsetDateTime, Boolean>? {
            val trimmed = text.trim().ifEmpty { return null }
            runCatching { return OffsetDateTime.parse(trimmed) to true }
            runCatching { return LocalDateTime.parse(trimmed).atZone(LJUBLJANA).toOffsetDateTime() to true }
            return try {
                val day = LocalDate.parse(trimmed)
                (if (end) day.atTime(23, 59) else day.atStartOfDay()).atZone(LJUBLJANA).toOffsetDateTime() to false
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("$what is a time, e.g. 2026-11-16T18:00, or a day, e.g. 2026-11-16, not $text")
            }
        }
    }
}
