package si.ogrodje.metaos

import jakarta.persistence.*
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.text.Collator
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

/** How an organisation supports Ogrodje, as ogrodje.si's footer had them: Podporniki, Partnerji; or not at all */
enum class OrganisationTier { SUPPORTER, PARTNER, NONE }

/**
 * An organisation (V35__organisations.sql): one of Ogrodje's supporters or partners, listed with its logo while it
 * supports it, or a company people work at. Its finance details are the team's.
 */
@Entity
@Table(name = "organisations")
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
class Organisation(
    @Column(nullable = false, columnDefinition = "citext") var name: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    // What it is, e.g. what it makes
    @Column(columnDefinition = "text") var description: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var tier: OrganisationTier = OrganisationTier.NONE

    // Days, both included: without a start since before MetaOS knew, without an end still
    var supportStartedAt: LocalDate? = null
    var supportEndedAt: LocalDate? = null

    @Column(columnDefinition = "text") var websiteUrl: String? = null
    @Column(columnDefinition = "text") var linkedinUrl: String? = null
    @Column(columnDefinition = "text") var twitterUrl: String? = null
    @Column(columnDefinition = "text") var instagramUrl: String? = null
    @Column(columnDefinition = "text") var facebookUrl: String? = null

    // In the public bucket (Images.kt)
    @Column(name = "logo_image_id") var logoImageId: UUID? = null

    // For finance, the team's
    @Column(columnDefinition = "text") var vatId: String? = null
    @Column(columnDefinition = "text") var companyId: String? = null
    @Column(columnDefinition = "text") var address: String? = null

    // Eager, as Hibernate can't proxy a to-one whose target uses @SoftDelete
    @ManyToOne(optional = true)
    @JoinColumn(name = "contact_person_id")
    var contactPerson: Person? = null

    // Where it announces its events, which the scrapers read (EventsSync.kt), as Goo's meetups had them; its website too
    @Column(columnDefinition = "text") var meetupUrl: String? = null
    @Column(columnDefinition = "text") var eventbriteUrl: String? = null
    @Column(columnDefinition = "text") var icalUrl: String? = null
    @Column(columnDefinition = "text") var lumaUrl: String? = null
    @Column(columnDefinition = "text") var discordUrl: String? = null
    @Column(columnDefinition = "text") var kompotUrl: String? = null

    // Its badge's on the Events page, its text's and its background's, e.g. #ffffff
    @Column(columnDefinition = "text") var mainColor: String? = null
    @Column(columnDefinition = "text") var backgroundColor: String? = null

    // Where its events usually are, e.g. Kovačnica, and its address: an event that doesn't say where it is is there
    @Column(columnDefinition = "text") var venueName: String? = null
    @Column(columnDefinition = "text") var venueAddress: String? = null

    // Where its events usually are, for the Events page's filter: told from its venue (Regions), or the team's
    // ([regionManual]), which a change to its venue leaves. An event without a region of its own goes by it.
    @Enumerated(EnumType.STRING) @Column(columnDefinition = "text") var region: Region? = null
    @Column(nullable = false) var regionManual: Boolean = false

    // Whether its events are gathered from its links: not by default, as most organisations don't announce events
    @Column(nullable = false) var collectsEvents: Boolean = false

    // Archived: listed apart, and its events no longer gathered; nothing of it is deleted
    var archivedAt: OffsetDateTime? = null

    // The meetup in Hygraph it was imported from, once, from Goo (OG-64); kept, though the import is gone (OG-66)
    @Column(columnDefinition = "text") var gooId: String? = null

    val archived: Boolean get() = archivedAt != null

    /** Whether the scrapers gather its events: it isn't archived, and collects them */
    val gathersEvents: Boolean get() = collectsEvents && !archived

    /** Its links the scrapers read, by the field they're in: its website, Meetup, Eventbrite, iCal, Luma and Kompot */
    fun eventSources(): Map<EventField, String> = listOfNotNull(
        websiteUrl?.let { EventField.WEBSITE to it },
        meetupUrl?.let { EventField.MEETUP to it },
        eventbriteUrl?.let { EventField.EVENTBRITE to it },
        icalUrl?.let { EventField.ICAL to it },
        lumaUrl?.let { EventField.LUMA to it },
        kompotUrl?.let { EventField.KOMPOT to it },
    ).toMap()

    /** Whether it's listed as one of Ogrodje's supporters or partners on [day] */
    fun supports(day: LocalDate) = tier != OrganisationTier.NONE &&
        (supportStartedAt == null || !supportStartedAt!!.isAfter(day)) &&
        (supportEndedAt == null || !supportEndedAt!!.isBefore(day))

    fun setLink(network: SocialNetwork, url: String?) {
        require(url == null || network.accepts(url)) { "$url is not a $network URL" }
        when (network) {
            SocialNetwork.HOMEPAGE -> websiteUrl = url
            SocialNetwork.LINKEDIN -> linkedinUrl = url
            SocialNetwork.TWITTER -> twitterUrl = url
            SocialNetwork.INSTAGRAM -> instagramUrl = url
            SocialNetwork.FACEBOOK -> facebookUrl = url
            SocialNetwork.GITHUB -> throw IllegalArgumentException("An organisation has no GitHub link")
        }
    }

    override fun equals(other: Any?) = this === other || (other is Organisation && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Organisation(id=$id, name=$name)"
}

/** A person working at an organisation, with their title, from when till when (days) */
@Entity
@Table(name = "person_organisations")
class PersonOrganisation(
    @ManyToOne(optional = false)
    @JoinColumn(name = "person_id")
    val person: Person,

    @ManyToOne(optional = false)
    @JoinColumn(name = "organisation_id")
    val organisation: Organisation,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Column(columnDefinition = "text") var title: String? = null
    var startedAt: LocalDate? = null
    var endedAt: LocalDate? = null

    /** Whether they still work there: it hasn't ended, or ends today or later */
    fun current(day: LocalDate) = endedAt == null || !endedAt!!.isBefore(day)

    override fun equals(other: Any?) = this === other || (other is PersonOrganisation && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "PersonOrganisation(id=$id, title=$title)"
}

/** An organisation that supported an episode's production */
@Entity
@Table(name = "episode_organisations")
class EpisodeOrganisation(
    @ManyToOne(optional = false)
    @JoinColumn(name = "episode_id")
    val episode: Episode,

    @ManyToOne(optional = false)
    @JoinColumn(name = "organisation_id")
    val organisation: Organisation,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    override fun equals(other: Any?) = this === other || (other is EpisodeOrganisation && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

interface OrganisationsRepository : JpaRepository<Organisation, UUID> {
    fun findByName(name: String): Organisation?

    // Soft-deleted ones are hidden from JPA queries, so finding and restoring one takes native SQL
    @Query("select count(*) > 0 from organisations where id = :id and deleted_at is not null", nativeQuery = true)
    fun isDeleted(id: UUID): Boolean

    @Query("select cast(name as text) from organisations where id = :id and deleted_at is not null", nativeQuery = true)
    fun findDeletedName(id: UUID): String?

    @Modifying(clearAutomatically = true)
    @Query("update organisations set deleted_at = null where id = :id", nativeQuery = true)
    fun restore(id: UUID): Int

    // A deleted one's name is free for a live one, so it may come back under another
    @Modifying(clearAutomatically = true)
    @Query("update organisations set name = :name where id = :id and deleted_at is not null", nativeQuery = true)
    fun renameDeleted(id: UUID, name: String): Int

    fun findByTierNot(tier: OrganisationTier): List<Organisation>
}

interface PersonOrganisationsRepository : JpaRepository<PersonOrganisation, UUID> {
    @Query("select po from PersonOrganisation po join fetch po.person join fetch po.organisation where po.person in :people")
    fun findByPeople(people: Collection<Person>): List<PersonOrganisation>

    @Query("select po from PersonOrganisation po join fetch po.person join fetch po.organisation where po.organisation in :organisations")
    fun findByOrganisations(organisations: Collection<Organisation>): List<PersonOrganisation>
}

interface EpisodeOrganisationsRepository : JpaRepository<EpisodeOrganisation, UUID> {
    fun findByEpisodeAndOrganisation(episode: Episode, organisation: Organisation): EpisodeOrganisation?

    @Query("select eo from EpisodeOrganisation eo join fetch eo.episode join fetch eo.organisation where eo.episode in :episodes")
    fun findByEpisodes(episodes: Collection<Episode>): List<EpisodeOrganisation>

    @Query("select eo from EpisodeOrganisation eo join fetch eo.episode e join fetch eo.organisation where eo.organisation in :organisations order by $LATEST_FIRST")
    fun findByOrganisations(organisations: Collection<Organisation>): List<EpisodeOrganisation>
}

// Ogrodje's days are Ljubljana's
private val OGRODJE: ZoneId = ZoneId.of("Europe/Ljubljana")

fun ogrodjeToday(): LocalDate = LocalDate.now(OGRODJE)

// By name as Slovenian sorts it, as people are
val organisationsByName: Comparator<Organisation> =
    Collator.getInstance(Locale.forLanguageTag("sl")).let { collator -> compareBy(collator) { it.name } }

// Supporters before partners, each by name, as the footer lists them
private val byTier = compareBy<Organisation> { it.tier.ordinal }.then(organisationsByName)

/** A day as the API takes one, e.g. 2026-11-16; empty is none */
fun parseDay(text: String, what: String): LocalDate? =
    text.trim().ifEmpty { null }?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("$what is a day, e.g. 2026-11-16, not $text")
        }
    }

/**
 * What changes about an organisation: what's null stays as it is, and empty text clears it. Days as [parseDay] takes them,
 * links as [SocialNetwork] accepts them, the contact person by ID.
 */
data class OrganisationChanges(
    val name: String? = null,
    val description: String? = null,
    val tier: OrganisationTier? = null,
    val supportStartedAt: String? = null,
    val supportEndedAt: String? = null,
    val websiteUrl: String? = null,
    val linkedinUrl: String? = null,
    val twitterUrl: String? = null,
    val instagramUrl: String? = null,
    val facebookUrl: String? = null,
    val vatId: String? = null,
    val companyId: String? = null,
    val address: String? = null,
    val contactPerson: String? = null,
    val meetupUrl: String? = null,
    val eventbriteUrl: String? = null,
    val icalUrl: String? = null,
    val lumaUrl: String? = null,
    val discordUrl: String? = null,
    val kompotUrl: String? = null,
    val mainColor: String? = null,
    val backgroundColor: String? = null,
    val collectsEvents: Boolean? = null,
    val archived: Boolean? = null,
    val venueName: String? = null,
    val venueAddress: String? = null,
    // Its region, by its key or name ([Region.parse]); empty for the one its venue tells
    val region: String? = null,
)

/** What changes about a person's work at an organisation, as [OrganisationChanges] */
data class WorkChanges(val title: String? = null, val startedAt: String? = null, val endedAt: String? = null)

/**
 * Organisations, the people who work at them and the episodes they supported, for MCP (OrganisationsMCP) and GraphQL
 * (OrganisationsGraphQL) alike, as Sessions are
 */
@Component
class Organisations(
    private val organisations: OrganisationsRepository,
    private val people: PeopleRepository,
    private val work: PersonOrganisationsRepository,
    private val supported: EpisodeOrganisationsRepository,
    private val episodes: EpisodesRepository,
    private val regions: Regions,
) {

    fun find(id: UUID): Organisation = requireNotNull(organisations.findByIdOrNull(id)) { "Organisation $id not found" }

    /** Every organisation by name, or only those [archived] or not */
    fun all(archived: Boolean? = null): List<Organisation> =
        organisations.findAll().filter { archived == null || it.archived == archived }.sortedWith(organisationsByName)

    /** Those listed as Ogrodje's supporters and partners on [day], supporters first, for the home page and the footer */
    fun supporters(day: LocalDate = ogrodjeToday()): List<Organisation> =
        organisations.findByTierNot(OrganisationTier.NONE).filter { !it.archived && it.supports(day) }.sortedWith(byTier)

    @Transactional
    fun create(name: String, changes: OrganisationChanges = OrganisationChanges()): Organisation {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name must not be blank" }
        require(organisations.findByName(trimmed) == null) { "Organisation $trimmed already exists" }
        return organisations.save(Organisation(trimmed).apply {
            change(this, changes.copy(name = null))
            // One made with somewhere to read its events, but its website alone, gathers them unless told otherwise
            if (changes.collectsEvents == null) collectsEvents = eventSources().keys.any { it != EventField.WEBSITE }
        })
    }

    @Transactional
    fun update(id: UUID, changes: OrganisationChanges): Organisation = find(id).also { change(it, changes) }

    private fun change(organisation: Organisation, changes: OrganisationChanges) {
        changes.name?.trim()?.let { name ->
            require(name.isNotEmpty()) { "Name must not be blank" }
            require(organisations.findByName(name).let { it == null || it == organisation }) { "Organisation $name already exists" }
            organisation.name = name
        }
        changes.description?.let { organisation.description = it.cleared() }
        changes.tier?.let { organisation.tier = it }
        changes.supportStartedAt?.let { organisation.supportStartedAt = parseDay(it, "When it started supporting Ogrodje") }
        changes.supportEndedAt?.let { organisation.supportEndedAt = parseDay(it, "When it stopped supporting Ogrodje") }
        require(organisation.supportStartedAt == null || organisation.supportEndedAt == null ||
            !organisation.supportEndedAt!!.isBefore(organisation.supportStartedAt)) { "Its support can't end before it started" }
        changes.websiteUrl?.let { organisation.setLink(SocialNetwork.HOMEPAGE, it.cleared()) }
        changes.linkedinUrl?.let { organisation.setLink(SocialNetwork.LINKEDIN, it.cleared()) }
        changes.twitterUrl?.let { organisation.setLink(SocialNetwork.TWITTER, it.cleared()) }
        changes.instagramUrl?.let { organisation.setLink(SocialNetwork.INSTAGRAM, it.cleared()) }
        changes.facebookUrl?.let { organisation.setLink(SocialNetwork.FACEBOOK, it.cleared()) }
        changes.vatId?.let { organisation.vatId = it.cleared() }
        changes.companyId?.let { organisation.companyId = it.cleared() }
        changes.address?.let { organisation.address = it.cleared() }
        changes.contactPerson?.let { ref ->
            organisation.contactPerson = ref.cleared()?.let { person(uuid(it, "A contact person")) }
        }
        changes.meetupUrl?.let { organisation.meetupUrl = link(it, "Its Meetup page") }
        changes.eventbriteUrl?.let { organisation.eventbriteUrl = link(it, "Its Eventbrite page") }
        changes.icalUrl?.let { organisation.icalUrl = link(it, "Its calendar", setOf("http", "https", "webcal")) }
        changes.lumaUrl?.let { organisation.lumaUrl = link(it, "Its Luma calendar") }
        changes.discordUrl?.let { organisation.discordUrl = link(it, "Its Discord") }
        changes.kompotUrl?.let { organisation.kompotUrl = link(it, "Its Kompot page") }
        changes.mainColor?.let { organisation.mainColor = colour(it, "Its badge's text colour") }
        changes.backgroundColor?.let { organisation.backgroundColor = colour(it, "Its badge's background") }
        changes.collectsEvents?.let { organisation.collectsEvents = it }
        changes.venueName?.let { organisation.venueName = it.cleared() }
        changes.venueAddress?.let { organisation.venueAddress = it.cleared() }
        changes.region?.let { region ->
            organisation.region = region.cleared()?.let(Region::parse)
            organisation.regionManual = organisation.region != null
        }
        tellRegion(organisation)
        changes.archived?.let { archived ->
            if (archived != organisation.archived) organisation.archivedAt = if (archived) OffsetDateTime.now() else null
        }
    }

    /** [organisation]'s region as its venue tells it, unless the team gave it one */
    fun tellRegion(organisation: Organisation) {
        if (!organisation.regionManual) organisation.region = regions.of(organisation.venueName, organisation.venueAddress)
    }

    // A link, or none for empty text
    private fun link(text: String, what: String, schemes: Set<String> = setOf("http", "https")): String? = text.cleared()?.also {
        val scheme = runCatching { java.net.URI(it) }.getOrNull()?.takeIf { uri -> uri.host != null }?.scheme?.lowercase()
        require(scheme in schemes) { "$what is a link (${schemes.joinToString(" or ")}), not $it" }
    }

    // A colour, e.g. #ff00aa, or none for empty text
    private fun colour(text: String, what: String): String? = text.cleared()?.let {
        val hex = if (it.startsWith("#")) it else "#$it"
        require(hex.matches(Regex("#[0-9a-fA-F]{6}"))) { "$what is a colour, e.g. #ff00aa, not $it" }
        hex.lowercase()
    }

    // Its people and the episodes it supported are kept, and come back with it
    @Transactional
    fun delete(id: UUID): Organisation = find(id).also(organisations::delete)

    @Transactional
    fun restore(id: UUID, newName: String? = null): Organisation {
        require(organisations.isDeleted(id)) { "There is no deleted organisation $id" }
        val new = newName?.trim()
        if (new == null) {
            val name = organisations.findDeletedName(id)!!
            require(organisations.findByName(name) == null) { "Organisation $name already exists; restore it under a new name (newName)" }
        } else {
            require(new.isNotEmpty()) { "Name must not be blank" }
            require(organisations.findByName(new) == null) { "Organisation $new already exists" }
            organisations.renameDeleted(id, new)
        }
        organisations.restore(id)
        return find(id)
    }

    /** The people who work or worked at [organisation], those who still do first, each by when they started, the latest first */
    fun peopleOf(organisation: Organisation): List<PersonOrganisation> = peopleOf(listOf(organisation)).getValue(organisation)

    fun peopleOf(organisations: List<Organisation>): Map<Organisation, List<PersonOrganisation>> {
        val day = ogrodjeToday()
        val byOrganisation = work.findByOrganisations(organisations).groupBy { it.organisation }
        return organisations.associateWith { byOrganisation[it].orEmpty().sortedWith(workOrder(day)) }
    }

    /** Where [people] work or worked, as [peopleOf] orders them */
    fun workOf(people: List<Person>): Map<Person, List<PersonOrganisation>> {
        val day = ogrodjeToday()
        val byPerson = work.findByPeople(people).groupBy { it.person }
        return people.associateWith { byPerson[it].orEmpty().sortedWith(workOrder(day)) }
    }

    fun workOf(personId: UUID): List<PersonOrganisation> = person(personId).let { workOf(listOf(it)).getValue(it) }

    fun supportersOf(episodeRef: String): List<Organisation> = episode(episodeRef).let { supportersOf(listOf(it)).getValue(it) }

    private fun workOrder(day: LocalDate) = compareBy<PersonOrganisation> { !it.current(day) }
        .thenByDescending(nullsFirst()) { it.endedAt }
        .thenByDescending(nullsLast()) { it.startedAt }
        .thenBy { it.person.name }

    /** That a person works at an organisation, unless they do already, there and then (with no end yet) */
    @Transactional
    fun addWork(personId: UUID, organisationId: UUID, changes: WorkChanges = WorkChanges()): PersonOrganisation {
        val person = person(personId)
        val organisation = find(organisationId)
        require(work.findByPeople(listOf(person)).none { it.organisation == organisation && it.endedAt == null }) {
            "${person.name} works at ${organisation.name} already; change that (update_person_organisation), or give it an end first"
        }
        return work.save(PersonOrganisation(person, organisation).also { changeWork(it, changes) })
    }

    @Transactional
    fun updateWork(id: UUID, changes: WorkChanges): PersonOrganisation = findWork(id).also { changeWork(it, changes) }

    @Transactional
    fun removeWork(id: UUID): PersonOrganisation = findWork(id).also(work::delete)

    private fun findWork(id: UUID) = requireNotNull(work.findByIdOrNull(id)) { "There is no work $id at an organisation" }

    private fun changeWork(entry: PersonOrganisation, changes: WorkChanges) {
        changes.title?.let { entry.title = it.cleared() }
        changes.startedAt?.let { entry.startedAt = parseDay(it, "When they started") }
        changes.endedAt?.let { entry.endedAt = parseDay(it, "When they left") }
        require(entry.startedAt == null || entry.endedAt == null || !entry.endedAt!!.isBefore(entry.startedAt)) {
            "They can't leave before they started"
        }
    }

    /** The organisations that supported each of [episodes], by name */
    fun supportersOf(episodes: List<Episode>): Map<Episode, List<Organisation>> {
        val byEpisode = supported.findByEpisodes(episodes).groupBy({ it.episode }, { it.organisation })
        return episodes.associateWith { byEpisode[it].orEmpty().sortedWith(organisationsByName) }
    }

    /** The episodes each of [organisations] supported, the latest first */
    fun supportedBy(organisations: List<Organisation>): Map<Organisation, List<Episode>> {
        val byOrganisation = supported.findByOrganisations(organisations).groupBy({ it.organisation }, { it.episode })
        return organisations.associateWith { byOrganisation[it].orEmpty() }
    }

    /** That an organisation supported an episode; once */
    @Transactional
    fun addSupporter(episodeRef: String, organisationId: UUID): Episode {
        val episode = episode(episodeRef)
        val organisation = find(organisationId)
        if (supported.findByEpisodeAndOrganisation(episode, organisation) == null) supported.save(EpisodeOrganisation(episode, organisation))
        return episode
    }

    @Transactional
    fun removeSupporter(episodeRef: String, organisationId: UUID): Episode {
        val episode = episode(episodeRef)
        val organisation = find(organisationId)
        supported.findByEpisodeAndOrganisation(episode, organisation)?.let(supported::delete)
        return episode
    }

    private fun episode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun person(id: UUID) = requireNotNull(people.findByIdOrNull(id)) { "Person $id not found" }

    private fun uuid(text: String, what: String): UUID =
        runCatching { UUID.fromString(text.trim()) }.getOrElse { throw IllegalArgumentException("$what is a person's ID, not $text") }

    private fun String.cleared(): String? = trim().ifEmpty { null }
}
