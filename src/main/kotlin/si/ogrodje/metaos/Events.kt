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
    @Column(nullable = false) var descriptionIsFallback: Boolean = false
    @Column(columnDefinition = "text") var url: String? = null
    var endsAt: OffsetDateTime? = null
    @Column(nullable = false) var hasStartTime: Boolean = true
    @Column(nullable = false) var hasEndTime: Boolean = true
    @Column(columnDefinition = "text") var locationName: String? = null
    @Column(columnDefinition = "text") var locationAddress: String? = null
    var hiddenAt: OffsetDateTime? = null
    var promotedAt: OffsetDateTime? = null

    // Its own region: told from where it says it is (Regions), or the team's ([regionManual]), which its scraper leaves
    @Enumerated(EnumType.STRING) @Column(name = "region", columnDefinition = "text") var ownRegion: Region? = null
    @Column(nullable = false) var regionManual: Boolean = false

    /** Where it is, for the Events page's filter: its own region, else its organisation's, as its place is its venue's */
    fun region(): Region? = ownRegion ?: organisation.region

    val manual: Boolean get() = source == MANUAL_SOURCE

    /** It's listed until it ends, or until it starts when it has no end */
    fun upcoming(now: Instant) = (endsAt ?: startsAt).toInstant() >= now

    /**
     * Where it is, as it says, else at its organisation's venue: one with no address of its own whose place is none, its
     * organisation's name (as some sources give it) or the venue's name gets the venue's address, and its name where it
     * has none. One elsewhere, e.g. online, keeps what it says.
     */
    fun place(): EventPlace? {
        val name = locationName?.trim()?.ifEmpty { null }
        val address = locationAddress?.trim()?.ifEmpty { null }
        if (address != null) return EventPlace(name, address, fromOrganisation = false)
        val venue = organisation.venueName?.trim()?.ifEmpty { null }
        val venueAddress = organisation.venueAddress?.trim()?.ifEmpty { null }
        val atVenue = name == null || fold(name) == fold(organisation.name) || (venue != null && fold(name) == fold(venue))
        return when {
            atVenue && (venue != null || venueAddress != null) ->
                EventPlace(venue ?: name?.takeIf { fold(it) != fold(organisation.name) }, venueAddress, fromOrganisation = true)
            name != null -> EventPlace(name, null, fromOrganisation = false)
            else -> null
        }
    }

    override fun equals(other: Any?) = this === other || (other is Event && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Event(id=$id, title=$title)"
}

// A place that isn't one, e.g. Online, Na spletu or Zoom, as fe/src/events.ts's isOnline
private val ONLINE = Regex("""\b(online|on-line|splet\w*|virtual\w*|virtualn\w*|zoom|teams|youtube|discord|webinar\w*)\b""", RegexOption.IGNORE_CASE)

/** Whether [name], an event's place without an address, is online */
fun isOnline(name: String) = ONLINE.containsMatchIn(name)

/**
 * Where an organisation's events usually are, from those that say it (a place, an address, or both, as many sources
 * put the whole address in the place's name), but online or its organisation's name: the one most of them have, by its address, else its name, at
 * least two and three in five, with the name and address they give it most often; none when they're all over the place.
 */
fun usualVenue(events: List<Event>): Pair<String?, String?>? {
    val located = events.mapNotNull { e ->
        val name = e.locationName?.trim()?.ifEmpty { null }
        val address = e.locationAddress?.trim()?.ifEmpty { null }
        // Its organisation's name alone, as some sources give it, says nothing
        (name to address).takeIf { address != null || (name != null && !isOnline(name) && fold(name) != fold(e.organisation.name)) }
    }
    val (_, most) = located.groupBy { fold(it.second ?: checkNotNull(it.first)) }.maxByOrNull { it.value.size } ?: return null
    if (most.size < 2 || most.size * 5 < located.size * 3) return null
    fun <T> usual(values: List<T>) = values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return usual(most.mapNotNull { it.first }) to usual(most.mapNotNull { it.second })
}

/** Where an event is: its place and address, either may be missing, and whether they're its organisation's venue's */
data class EventPlace(val name: String?, val address: String?, val fromOrganisation: Boolean)

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

    @Query("select e from Event e join fetch e.organisation where e.organisation = :organisation and e.hiddenAt is null and e.id <> :id")
    fun findVisibleOfOrganisationBut(organisation: Organisation, id: UUID): List<Event>

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
    // Its region, by its key or name ([Region.parse]), a scraper's too; empty for the one its place tells
    val region: String? = null,
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
    // The scraper knew only its days this time, not its times (e.g. its page didn't answer), its end the day after the
    // last, as calendars give them: times it has on those days stay (OG-75)
    val onlyDays: Boolean = false,
    // True when the text comes from the listing rather than the detail page.
    val descriptionIsFallback: Boolean = false,
    // The detail source explicitly returned an empty description.
    val descriptionConfirmedEmpty: Boolean = false,
)

/** How many of what a scraper found were new, and how many changed */
data class FoundCounts(val added: Int, val changed: Int)

/** How many of its organisation's other events an event's page lists */
const val MORE_FROM_ORGANISER = 3

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
    private val regions: Regions,
) {

    fun find(id: UUID): Event = requireNotNull(events.findByIdOrNull(id)) { "Event $id not found" }

    /** Those that haven't ended yet (or started, without an end), the soonest first; the hidden ones only when asked */
    fun upcoming(now: Instant = Instant.now(), hidden: Boolean = false): List<Event> =
        events.findEndingSince(now.atOffset(java.time.ZoneOffset.UTC)).filter { hidden || it.hiddenAt == null }

    /**
     * Up to [limit] of [event]'s organisation's other events that aren't hidden, for its page's More from this
     * organiser: those that haven't ended yet, the soonest first, then the past ones, the latest first
     */
    fun moreFromOrganiser(event: Event, limit: Int = MORE_FROM_ORGANISER, now: Instant = Instant.now()): List<Event> {
        val (upcoming, past) = events.findVisibleOfOrganisationBut(event.organisation, checkNotNull(event.id)).partition { it.upcoming(now) }
        return (upcoming.sortedBy { it.startsAt } + past.sortedByDescending { it.startsAt }).take(limit)
    }

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

    /** Changes [id]: the team's own wholly, a scraper's only whether it's hidden or promoted and its region, as its scraper keeps the rest */
    @Transactional
    fun update(id: UUID, changes: EventChanges): Event {
        val event = find(id)
        require(event.manual || listOf(changes.title, changes.start, changes.end, changes.description, changes.url,
            changes.locationName, changes.locationAddress, changes.organisation).all { it == null }) {
            "${event.title} is found by a scraper (${event.source}), which keeps what it says: only whether it's hidden or promoted and its region change"
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
        changes.region?.let { region ->
            event.ownRegion = region.trim().ifEmpty { null }?.let(Region::parse)
            event.regionManual = event.ownRegion != null
        }
        tellRegion(event)
    }

    // Its region as its place tells it, unless the team gave it one
    private fun tellRegion(event: Event) {
        if (!event.regionManual) event.ownRegion = regions.of(event.locationName, event.locationAddress)
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
        wanted.forEach { scraped ->
            val event = known[scraped.externalId]
            val incoming = event?.let { withKnownTimes(it, scraped) } ?: scraped
            val keepDescription = incoming.descriptionIsFallback && !incoming.descriptionConfirmedEmpty &&
                event?.description != null && !event.descriptionIsFallback
            val f = if (keepDescription) incoming.copy(description = event.description, descriptionIsFallback = false) else incoming
            val description = f.description?.trim()?.ifEmpty { null }
            if (event == null) {
                events.save(Event(organisation, f.source, f.externalId, f.title.trim(), f.start.atOffset(java.time.ZoneOffset.UTC)).also { found(it, f, description) })
                added++
            } else if (differs(event, f, description)) {
                found(event, f, description)
                changed++
            }
        }
        fillVenue(organisation)
        return FoundCounts(added, changed)
    }

    /**
     * Gives [organisation] the venue its events usually are at ([usualVenue]), where it has none: so those that don't
     * say where they are say that. Whether it did.
     */
    @Transactional
    fun fillVenue(organisation: Organisation): Boolean {
        if (organisation.venueName != null || organisation.venueAddress != null) return false
        val (name, address) = usualVenue(events.findByOrganisations(listOf(organisation))) ?: return false
        organisation.venueName = name
        organisation.venueAddress = address
        if (!organisation.regionManual) organisation.region = regions.of(name, address)
        return true
    }

    /**
     * [f] with [event]'s times, when its scraper knew only its days this time ([FoundEvent.onlyDays]) and [event] has
     * a time on one of them (its end's day not one, as calendars give it): so a page that didn't answer doesn't make it all day, nor change it, until it answers
     * again. Days that moved are the scraper's.
     */
    private fun withKnownTimes(event: Event, f: FoundEvent): FoundEvent {
        if (!f.onlyDays || !(event.hasStartTime || event.hasEndTime)) return f
        fun day(instant: Instant) = instant.atZone(LJUBLJANA).toLocalDate()
        // Its days are as calendars give them, the end's excluded: 17 to 18 April is the 17th alone
        val first = day(f.start)
        val last = f.end?.let(::day)?.takeIf { it > first }?.minusDays(1) ?: first
        val known = day(event.startsAt.toInstant())
        if (known < first || known > last) return f
        return f.copy(start = event.startsAt.toInstant(), end = event.endsAt?.toInstant(),
            hasStartTime = event.hasStartTime, hasEndTime = event.hasEndTime)
    }

    private fun differs(event: Event, f: FoundEvent, description: String?) =
        event.title != f.title.trim() || event.startsAt.toInstant() != f.start || event.endsAt?.toInstant() != f.end ||
            event.hasStartTime != f.hasStartTime || event.hasEndTime != f.hasEndTime || event.description != description ||
            event.descriptionIsFallback != f.descriptionIsFallback ||
            event.url != f.eventUrl || event.locationName != f.locationName?.trim()?.ifEmpty { null } ||
            event.locationAddress != f.locationAddress?.trim()?.ifEmpty { null } || event.sourceUrl != f.sourceUrl

    private fun found(event: Event, f: FoundEvent, description: String?) {
        event.title = f.title.trim()
        event.startsAt = f.start.atOffset(java.time.ZoneOffset.UTC)
        // A scraper's end before its start is no end
        event.endsAt = f.end?.takeIf { it >= f.start }?.atOffset(java.time.ZoneOffset.UTC)
        event.hasStartTime = f.hasStartTime
        event.hasEndTime = f.hasEndTime
        event.description = description
        event.descriptionIsFallback = f.descriptionIsFallback
        event.url = f.eventUrl?.takeIf { it.matches(Regex("^https?://.+")) }
        event.locationName = f.locationName?.trim()?.ifEmpty { null }
        event.locationAddress = f.locationAddress?.trim()?.ifEmpty { null }
        event.sourceUrl = f.sourceUrl
        tellRegion(event)
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
