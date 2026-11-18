package si.ogrodje.metaos

import org.springframework.beans.factory.ObjectProvider
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.time.Instant
import java.util.UUID

/** Who an event is by, as the Events page shows it: its name, its logo, and its badge's colours, the text's and its background's */
data class Meetup(val name: String, val logo: String?, val mainColor: String?, val backgroundColor: String?)

/** What createEvent and updateEvent take, as EventChanges */
data class EventInput(
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
) {
    fun changes() = EventChanges(organisation, title, start, end, description, url, locationName, locationAddress, hidden, promoted)
}

/**
 * Events over GraphQL (Events.kt): the upcoming ones that aren't hidden are anyone's, as they were on ogrodje.si; the
 * hidden ones, an organisation's past ones (but on an event's own page), how its links were scraped, and every change, the team's.
 */
@Controller
class EventsGraphQL(
    private val events: Events,
    private val organisations: Organisations,
    private val syncs: EventsSyncs,
    private val goo: GooImport,
    private val imagesRepository: ImagesRepository,
    private val images: ObjectProvider<Images>,
) {

    @QueryMapping("events")
    fun upcoming(@Argument hidden: Boolean?): List<Event> = events.upcoming(hidden = hidden == true && signedIn())

    // Its page is anyone's, a past one's too, unless it's hidden
    @QueryMapping
    fun event(@Argument id: UUID): Event? =
        runCatching { events.find(id) }.getOrNull()?.takeIf { signedIn() || it.hiddenAt == null }

    @SchemaMapping(typeName = "Event")
    fun moreFromOrganiser(event: Event): List<Event> = events.moreFromOrganiser(event)

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun eventsRefresh(@Argument organisation: UUID?): EventsRefresh = syncs.status(organisation)

    @QueryMapping
    fun eventOrganisers(): Int = organisations.all(archived = false).count { it.gathersEvents && it.eventSources().isNotEmpty() }

    @SchemaMapping(typeName = "Event")
    fun place(event: Event): EventPlace? = event.place()

    @SchemaMapping(typeName = "Event")
    fun start(event: Event): String = event.startsAt.toInstant().toString()

    @SchemaMapping(typeName = "Event")
    fun end(event: Event): String? = event.endsAt?.toInstant()?.toString()

    @SchemaMapping(typeName = "Event")
    fun promoted(event: Event): Boolean = event.promotedAt != null

    @SchemaMapping(typeName = "Event")
    fun hidden(event: Event): Boolean = event.hiddenAt != null

    @SchemaMapping(typeName = "Event")
    fun sourceUrl(event: Event): String? = event.sourceUrl.takeIf { signedIn() }

    // The badge, as Goo's meetups had it: its name, its logo, and its colours
    @BatchMapping(typeName = "Event", field = "meetup")
    fun meetups(events: List<Event>): Map<Event, Meetup> {
        val images = images.ifAvailable
        val logos = if (images == null) emptyMap()
        else imagesRepository.findAllById(events.mapNotNull { it.organisation.logoImageId }.toSet()).associateBy({ it.id }) { images.toDto(it) }
        return events.associateWith { e ->
            val o = e.organisation
            val logo = o.logoImageId?.let(logos::get)?.let { dto -> dto.sizes.firstOrNull()?.url ?: dto.url }
            Meetup(o.name, logo, o.mainColor, o.backgroundColor)
        }
    }

    // Anyone's organisation is the upcoming ones that aren't hidden; the team gets every one, the latest first
    @BatchMapping(typeName = "Organisation", field = "events")
    fun organisationEvents(organisations: List<Organisation>): Map<Organisation, List<Event>> {
        val signedIn = signedIn()
        val now = Instant.now()
        return events.of(organisations).mapValues { (_, list) ->
            if (signedIn) list else list.filter { it.hiddenAt == null && it.upcoming(now) }.sortedBy { it.startsAt }
        }
    }

    @BatchMapping(typeName = "Organisation", field = "eventScrapes")
    fun scrapes(organisations: List<Organisation>): Map<Organisation, List<EventScrape>> =
        if (signedIn()) events.scrapesOf(organisations) else organisations.associateWith { emptyList() }

    @SchemaMapping(typeName = "EventScrape")
    fun scrapedAt(scrape: EventScrape): String = scrape.scrapedAt.toInstant().toString()

    @SchemaMapping(typeName = "EventsRefresh")
    fun startedAt(refresh: EventsRefresh): String? = refresh.startedAt?.toString()

    @MutationMapping
    @PreAuthorize(WRITES)
    fun createEvent(@Argument input: EventInput): Event = events.create(input.changes())

    @MutationMapping
    @PreAuthorize(WRITES)
    fun updateEvent(@Argument id: UUID, @Argument input: EventInput): Event = events.update(id, input.changes())

    @MutationMapping
    @PreAuthorize(WRITES)
    fun refreshEvents(@Argument organisation: UUID?): EventsRefresh = syncs.refresh(organisation)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun importFromGoo(): GooImportSummary = goo.import()
}
