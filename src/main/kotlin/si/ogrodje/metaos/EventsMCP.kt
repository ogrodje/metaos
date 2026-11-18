package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class EventDto(
    val id: UUID?,
    val title: String,
    val organisationId: UUID?,
    val organisation: String,
    val start: Instant,
    val end: Instant?,
    val hasStartTime: Boolean,
    val hasEndTime: Boolean,
    val description: String?,
    val url: String?,
    val locationName: String?,
    val locationAddress: String?,
    // Where it is: its own location, else its organisation's venue (fromOrganisation)
    val place: EventPlace?,
    val hidden: Boolean,
    val promoted: Boolean,
    // Which scraper found it, or MANUAL for the team's own
    val source: String,
    val sourceUrl: String?,
)

fun Event.toDto() = EventDto(
    id, title, organisation.id, organisation.name, startsAt.toInstant(), endsAt?.toInstant(), hasStartTime, hasEndTime,
    description, url, locationName, locationAddress, place(), hiddenAt != null, promotedAt != null, source, sourceUrl,
)

private const val EVENT_ID = "Event ID (list_events)"
private const val TIME = "a time, e.g. 2026-11-16T18:00 (in Ljubljana) or 2026-11-16T17:00:00Z, or a day, e.g. 2026-11-16, which has no time of day"

@Component
class EventsMCP(private val events: Events, private val organisations: Organisations, private val syncs: EventsSyncs) {

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_events",
        annotations = McpTool.McpAnnotations(
            title = "List events",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List Slovenia's tech, developer and startup events on the Events page: those that haven't ended, " +
            "the soonest first, as the scrapers gather them from organisations' links and the team adds them; or every " +
            "event of one organisation, the latest first"
    )
    fun list(
        @McpToolParam(description = "Only this organisation's, past ones too (list_organisations)", required = false) organisationId: UUID? = null,
        @McpToolParam(description = "The hidden ones too", required = false) hidden: Boolean? = null,
    ): List<EventDto> =
        if (organisationId != null) {
            val organisation = organisations.find(organisationId)
            events.of(listOf(organisation)).getValue(organisation).filter { hidden == true || it.hiddenAt == null }.map { it.toDto() }
        } else events.upcoming(hidden = hidden == true).map { it.toDto() }

    @Transactional
    @McpTool(
        name = "create_event",
        annotations = McpTool.McpAnnotations(
            title = "Create event",
            readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false
        ),
        description = "Add an event the scrapers don't find, by an organisation, to the Events page: the team's own, " +
            "which update_event changes wholly"
    )
    fun create(
        @McpToolParam(description = "ID of the organisation it's by (list_organisations)") organisationId: UUID,
        @McpToolParam(description = "Its title") title: String,
        @McpToolParam(description = "When it starts: $TIME") start: String,
        @McpToolParam(description = "When it ends: $TIME", required = false) end: String? = null,
        @McpToolParam(description = "Where it's announced (http or https)", required = false) url: String? = null,
        @McpToolParam(description = "What it's about", required = false) description: String? = null,
        @McpToolParam(description = "Where it is, e.g. Kovačnica", required = false) locationName: String? = null,
        @McpToolParam(description = "Its address", required = false) locationAddress: String? = null,
        @McpToolParam(description = "One of Ogrodje's picks, which stand out", required = false) promoted: Boolean? = null,
    ): EventDto = events.create(
        EventChanges(organisationId, title, start, end, description, url, locationName, locationAddress, promoted = promoted)
    ).toDto()

    @Transactional
    @McpTool(
        name = "update_event",
        annotations = McpTool.McpAnnotations(
            title = "Update event",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Change an event: hide it from the Events page or show it again, or make it one of Ogrodje's " +
            "picks; and the team's own (source MANUAL) wholly, as a scraper keeps what it found as the site says. " +
            "Omitted fields are left as they are; empty text clears one. Nothing is deleted"
    )
    fun update(
        @McpToolParam(description = EVENT_ID) id: UUID,
        @McpToolParam(description = "Hidden from the Events page", required = false) hidden: Boolean? = null,
        @McpToolParam(description = "One of Ogrodje's picks", required = false) promoted: Boolean? = null,
        @McpToolParam(description = "Its title (the team's own only)", required = false) title: String? = null,
        @McpToolParam(description = "When it starts: $TIME (the team's own only)", required = false) start: String? = null,
        @McpToolParam(description = "When it ends: $TIME; empty for none (the team's own only)", required = false) end: String? = null,
        @McpToolParam(description = "Where it's announced; empty clears it (the team's own only)", required = false) url: String? = null,
        @McpToolParam(description = "What it's about (the team's own only)", required = false) description: String? = null,
        @McpToolParam(description = "Where it is (the team's own only)", required = false) locationName: String? = null,
        @McpToolParam(description = "Its address (the team's own only)", required = false) locationAddress: String? = null,
        @McpToolParam(description = "ID of the organisation it's by (the team's own only)", required = false) organisationId: UUID? = null,
    ): EventDto = events.update(
        id, EventChanges(organisationId, title, start, end, description, url, locationName, locationAddress, hidden, promoted),
    ).toDto()

    @McpTool(
        name = "refresh_events",
        annotations = McpTool.McpAnnotations(
            title = "Refresh events",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Gather events from organisations' links again now, as the scrapers do every hour: every " +
            "organisation's that collects them, or one's. It answers at once and goes on; the events come as they're " +
            "found, and get_organisation says how each link was last scraped. Not again while one is on"
    )
    fun refresh(
        @McpToolParam(description = "Only this organisation's (list_organisations)", required = false) organisationId: UUID? = null,
    ): EventsRefresh = syncs.refresh(organisationId)
}
