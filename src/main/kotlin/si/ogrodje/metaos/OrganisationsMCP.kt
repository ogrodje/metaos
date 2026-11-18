package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

data class OrganisationDto(
    val id: UUID?,
    val name: String,
    val description: String?,
    val tier: OrganisationTier,
    val supportStartedAt: LocalDate?,
    val supportEndedAt: LocalDate?,
    // Whether it's listed as a supporter or partner today
    val supporting: Boolean,
    val websiteUrl: String?,
    val linkedinUrl: String?,
    val twitterUrl: String?,
    val instagramUrl: String?,
    val facebookUrl: String?,
    val vatId: String?,
    val companyId: String?,
    val address: String?,
    val contactPersonId: UUID?,
    val contactPerson: String?,
    val meetupUrl: String?,
    val eventbriteUrl: String?,
    val icalUrl: String?,
    val lumaUrl: String?,
    val discordUrl: String?,
    val kompotUrl: String?,
    val mainColor: String?,
    val backgroundColor: String?,
    val collectsEvents: Boolean,
    val archived: Boolean,
)

fun Organisation.toDto() = OrganisationDto(
    id, name, description, tier, supportStartedAt, supportEndedAt, supports(ogrodjeToday()), websiteUrl, linkedinUrl,
    twitterUrl, instagramUrl, facebookUrl, vatId, companyId, address, contactPerson?.id, contactPerson?.name, meetupUrl,
    eventbriteUrl, icalUrl, lumaUrl, discordUrl, kompotUrl, mainColor, backgroundColor, collectsEvents, archived,
)

/** How one of an organisation's links was last scraped */
data class EventScrapeDto(val field: EventField, val url: String, val source: String?, val scrapedAt: java.time.Instant, val events: Int, val error: String?)

/** A person's work at an organisation: its ID, which update_person_organisation and unlink_person_from_organisation take */
data class WorkDto(
    val id: UUID?,
    val personId: UUID?,
    val person: String,
    val organisationId: UUID?,
    val organisation: String,
    val title: String?,
    val startedAt: LocalDate?,
    val endedAt: LocalDate?,
)

fun PersonOrganisation.toDto() =
    WorkDto(id, person.id, person.name, organisation.id, organisation.name, title, startedAt, endedAt)

/** An organisation, with who works at it, the episodes it supported, and how its links were last scraped for events */
data class OrganisationDetailsDto(
    val organisation: OrganisationDto,
    val people: List<WorkDto>,
    val supportedEpisodes: List<EpisodeDto>,
    val eventScrapes: List<EventScrapeDto>,
)

private const val ORGANISATION_ID = "Organisation ID (list_organisations)"
private const val CLEARS = "empty to clear"
private const val TIER =
    "How it supports Ogrodje: SUPPORTER (Podporniki) or PARTNER (Partnerji), listed with its logo on the home page and in the footer while it does, or NONE"
private const val DAY = "a day, e.g. 2026-11-16"

@Component
class OrganisationsMCP(private val organisations: Organisations, private val events: Events) {

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_organisations",
        annotations = McpTool.McpAnnotations(
            title = "List organisations",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List all organisations by name: Ogrodje's supporters and partners, the companies people work at, " +
            "and those whose events are on the Events page"
    )
    fun list(
        @McpToolParam(description = "Only those listed as supporters or partners today", required = false) supporting: Boolean? = null,
        @McpToolParam(description = "Only those archived (true) or not (false); all by default", required = false) archived: Boolean? = null,
    ): List<OrganisationDto> =
        (if (supporting == true) organisations.supporters() else organisations.all(archived)).map { it.toDto() }

    @Transactional(readOnly = true)
    @McpTool(
        name = "get_organisation",
        annotations = McpTool.McpAnnotations(
            title = "Get organisation",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "An organisation, with the people who work or worked at it (those who still do first), the " +
            "episodes it supported, the latest first, and how each of its links was last scraped for events"
    )
    fun get(@McpToolParam(description = ORGANISATION_ID) id: UUID): OrganisationDetailsDto {
        val organisation = organisations.find(id)
        return OrganisationDetailsDto(
            organisation.toDto(),
            organisations.peopleOf(organisation).map { it.toDto() },
            organisations.supportedBy(listOf(organisation)).getValue(organisation).map { it.toDto() },
            events.scrapesOf(listOf(organisation)).getValue(organisation).map {
                EventScrapeDto(it.field, it.url, it.source, it.scrapedAt.toInstant(), it.events, it.error)
            },
        )
    }

    @Transactional
    @McpTool(
        name = "create_organisation",
        annotations = McpTool.McpAnnotations(
            title = "Create organisation",
            readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false
        ),
        description = "Create an organisation, e.g. a new supporter or the company a guest works at. set_organisation_logo " +
            "gives it its logo"
    )
    fun create(
        @McpToolParam(description = "Unique name (case-insensitive)") name: String,
        @McpToolParam(description = "What it is, e.g. what it makes", required = false) description: String? = null,
        @McpToolParam(description = "$TIER; NONE by default", required = false) tier: OrganisationTier? = null,
        @McpToolParam(description = "Its website (http or https)", required = false) websiteUrl: String? = null,
        @McpToolParam(description = "Its Meetup group, whose events the scrapers gather", required = false) meetupUrl: String? = null,
        @McpToolParam(description = "Its Eventbrite organiser page, whose events the scrapers gather", required = false) eventbriteUrl: String? = null,
        @McpToolParam(description = "Its events' calendar (iCal), which the scrapers read", required = false) icalUrl: String? = null,
        @McpToolParam(description = "Its Luma calendar, whose events the scrapers gather", required = false) lumaUrl: String? = null,
    ): OrganisationDto = organisations.create(
        name,
        OrganisationChanges(
            description = description, tier = tier, websiteUrl = websiteUrl, meetupUrl = meetupUrl, eventbriteUrl = eventbriteUrl,
            icalUrl = icalUrl, lumaUrl = lumaUrl,
        ),
    ).toDto()

    @Transactional
    @McpTool(
        name = "update_organisation",
        annotations = McpTool.McpAnnotations(
            title = "Update organisation",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Change an organisation: its name, description, tier and when it supported Ogrodje, its links, " +
            "its finance details (VAT ID, company ID, address, who to contact), which are the team's, where it " +
            "announces its events (its website too, which the scrapers read every hour) and its badge's colours on the " +
            "Events page, and whether it collects events or is archived (listed apart, its events no longer gathered; " +
            "nothing is deleted). Omitted fields are left as they are; empty text clears one. It's listed as a " +
            "supporter or partner while its tier isn't NONE, from when its support started (or always, without a " +
            "start) till when it ended (or for good, without an end)"
    )
    fun update(
        @McpToolParam(description = ORGANISATION_ID) id: UUID,
        @McpToolParam(description = "New unique name", required = false) name: String? = null,
        @McpToolParam(description = "What it is, e.g. what it makes; $CLEARS", required = false) description: String? = null,
        @McpToolParam(description = TIER, required = false) tier: OrganisationTier? = null,
        @McpToolParam(description = "When it started supporting Ogrodje, $DAY; $CLEARS", required = false) supportStartedAt: String? = null,
        @McpToolParam(description = "The last day it supported Ogrodje, $DAY; $CLEARS", required = false) supportEndedAt: String? = null,
        @McpToolParam(description = "Its website (http or https); $CLEARS", required = false) websiteUrl: String? = null,
        @McpToolParam(description = "Its LinkedIn page (https); $CLEARS", required = false) linkedinUrl: String? = null,
        @McpToolParam(description = "Its Twitter/X profile (https); $CLEARS", required = false) twitterUrl: String? = null,
        @McpToolParam(description = "Its Instagram profile (https); $CLEARS", required = false) instagramUrl: String? = null,
        @McpToolParam(description = "Its Facebook page (https); $CLEARS", required = false) facebookUrl: String? = null,
        @McpToolParam(description = "VAT ID, e.g. SI12345678; $CLEARS", required = false) vatId: String? = null,
        @McpToolParam(description = "Company ID in its registry, e.g. a matična številka; $CLEARS", required = false) companyId: String? = null,
        @McpToolParam(description = "Its registered address; $CLEARS", required = false) address: String? = null,
        @McpToolParam(description = "ID of the person to contact there; $CLEARS", required = false) contactPerson: String? = null,
        @McpToolParam(description = "Its Meetup group, whose events the scrapers gather; $CLEARS", required = false) meetupUrl: String? = null,
        @McpToolParam(description = "Its Eventbrite organiser page; $CLEARS", required = false) eventbriteUrl: String? = null,
        @McpToolParam(description = "Its events' calendar (iCal: http, https or webcal); $CLEARS", required = false) icalUrl: String? = null,
        @McpToolParam(description = "Its Luma calendar; $CLEARS", required = false) lumaUrl: String? = null,
        @McpToolParam(description = "Its Discord; $CLEARS", required = false) discordUrl: String? = null,
        @McpToolParam(description = "Its page on Kompot; $CLEARS", required = false) kompotUrl: String? = null,
        @McpToolParam(description = "Its badge's text colour on the Events page, e.g. #ffffff; $CLEARS", required = false) mainColor: String? = null,
        @McpToolParam(description = "Its badge's background, e.g. #000000; $CLEARS", required = false) backgroundColor: String? = null,
        @McpToolParam(description = "Whether its events are gathered from its links", required = false) collectsEvents: Boolean? = null,
        @McpToolParam(description = "Archive it (true), or bring it back (false)", required = false) archived: Boolean? = null,
    ): OrganisationDto = organisations.update(
        id,
        OrganisationChanges(
            name, description, tier, supportStartedAt, supportEndedAt, websiteUrl, linkedinUrl, twitterUrl, instagramUrl,
            facebookUrl, vatId, companyId, address, contactPerson, meetupUrl, eventbriteUrl, icalUrl, lumaUrl, discordUrl,
            kompotUrl, mainColor, backgroundColor, collectsEvents, archived,
        ),
    ).toDto()

    @Transactional
    @McpTool(
        name = "delete_organisation",
        annotations = McpTool.McpAnnotations(
            title = "Delete organisation",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Soft-delete an organisation. It's no longer listed, nor on its people and episodes, until it's restored"
    )
    fun delete(@McpToolParam(description = ORGANISATION_ID) id: UUID): OrganisationDto = organisations.delete(id).toDto()

    @Transactional
    @McpTool(
        name = "restore_organisation",
        annotations = McpTool.McpAnnotations(
            title = "Restore organisation",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Bring back a soft-deleted organisation (list_deleted), with its people and the episodes it " +
            "supported. Under a new name, if its own has been taken since"
    )
    fun restore(
        @McpToolParam(description = "Organisation ID") id: UUID,
        @McpToolParam(description = "New unique name to restore it under, e.g. when its own has been taken since", required = false)
        newName: String? = null,
    ): OrganisationDto = organisations.restore(id, newName).toDto()

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_person_organisations",
        annotations = McpTool.McpAnnotations(
            title = "List person organisations",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List where a person works or worked, with their title and when, where they still work first"
    )
    fun listWork(@McpToolParam(description = "Person ID") personId: UUID): List<WorkDto> =
        organisations.workOf(personId).map { it.toDto() }

    @Transactional
    @McpTool(
        name = "link_person_to_organisation",
        annotations = McpTool.McpAnnotations(
            title = "Link person to organisation",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Say that a person works at an organisation, with their title and from when till when. Not again " +
            "while they work there (without an end): update_person_organisation changes that"
    )
    fun addWork(
        @McpToolParam(description = "Person ID") personId: UUID,
        @McpToolParam(description = ORGANISATION_ID) organisationId: UUID,
        @McpToolParam(description = "Their title there, e.g. CTO", required = false) title: String? = null,
        @McpToolParam(description = "When they started, $DAY", required = false) startedAt: String? = null,
        @McpToolParam(description = "Their last day there, $DAY; omit while they still work there", required = false) endedAt: String? = null,
    ): WorkDto = organisations.addWork(personId, organisationId, WorkChanges(title, startedAt, endedAt)).toDto()

    @Transactional
    @McpTool(
        name = "update_person_organisation",
        annotations = McpTool.McpAnnotations(
            title = "Update person organisation",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Change a person's work at an organisation: their title, or when they started or left. Omitted " +
            "fields are left as they are; empty text clears one"
    )
    fun updateWork(
        @McpToolParam(description = "ID of their work there (list_person_organisations, get_organisation)") id: UUID,
        @McpToolParam(description = "Their title there, e.g. CTO; $CLEARS", required = false) title: String? = null,
        @McpToolParam(description = "When they started, $DAY; $CLEARS", required = false) startedAt: String? = null,
        @McpToolParam(description = "Their last day there, $DAY; $CLEARS", required = false) endedAt: String? = null,
    ): WorkDto = organisations.updateWork(id, WorkChanges(title, startedAt, endedAt)).toDto()

    @Transactional
    @McpTool(
        name = "unlink_person_from_organisation",
        annotations = McpTool.McpAnnotations(
            title = "Unlink person from organisation",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Remove a person's work at an organisation, e.g. one added by mistake; to say they left, give it " +
            "an end (update_person_organisation). The history keeps it, and restore_link brings it back"
    )
    fun removeWork(
        @McpToolParam(description = "ID of their work there (list_person_organisations, get_organisation)") id: UUID,
    ): WorkDto = organisations.removeWork(id).toDto()

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_episode_supporters",
        annotations = McpTool.McpAnnotations(
            title = "List episode supporters",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the organisations that supported an episode's production, by name"
    )
    fun listSupporters(@McpToolParam(description = EPISODE_REF) code: String): List<OrganisationDto> =
        organisations.supportersOf(code).map { it.toDto() }

    @Transactional
    @McpTool(
        name = "link_episode_supporter",
        annotations = McpTool.McpAnnotations(
            title = "Link episode supporter",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Say that an organisation supported an episode's production; its page says Supported by it. Once"
    )
    fun addSupporter(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = ORGANISATION_ID) organisationId: UUID,
    ): List<OrganisationDto> {
        val episode = organisations.addSupporter(code, organisationId)
        return organisations.supportersOf(listOf(episode)).getValue(episode).map { it.toDto() }
    }

    @Transactional
    @McpTool(
        name = "unlink_episode_supporter",
        annotations = McpTool.McpAnnotations(
            title = "Unlink episode supporter",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Take an organisation off the supporters of an episode. The history keeps it, and restore_link brings it back"
    )
    fun removeSupporter(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = ORGANISATION_ID) organisationId: UUID,
    ): List<OrganisationDto> {
        val episode = organisations.removeSupporter(code, organisationId)
        return organisations.supportersOf(listOf(episode)).getValue(episode).map { it.toDto() }
    }
}
