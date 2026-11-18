package si.ogrodje.metaos

import org.springframework.beans.factory.ObjectProvider
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.time.LocalDate
import java.util.UUID

/** What createOrganisation and updateOrganisation take, as OrganisationChanges */
data class OrganisationInput(
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
) {
    fun changes() = OrganisationChanges(
        name, description, tier, supportStartedAt, supportEndedAt, websiteUrl, linkedinUrl, twitterUrl, instagramUrl,
        facebookUrl, vatId, companyId, address, contactPerson, meetupUrl, eventbriteUrl, icalUrl, lumaUrl, discordUrl,
        kompotUrl, mainColor, backgroundColor, collectsEvents, archived, venueName, venueAddress,
    )
}

/**
 * Organisations over GraphQL (Organisations.kt). Who supports Ogrodje is public, for the home page and the footer, as is
 * who supported a released episode; the rest, their finance details and who works where, is the team's.
 */
@Controller
class OrganisationsGraphQL(
    private val organisations: Organisations,
    private val imagesRepository: ImagesRepository,
    private val images: ObjectProvider<Images>,
) {

    @QueryMapping
    fun supporters(): List<Organisation> = organisations.supporters()

    @QueryMapping("organisations")
    @PreAuthorize(SIGNED_IN)
    fun allOrganisations(@Argument archived: Boolean?): List<Organisation> = organisations.all(archived)

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun organisation(@Argument id: UUID): Organisation? = runCatching { organisations.find(id) }.getOrNull()

    @SchemaMapping(typeName = "Organisation")
    fun supporting(organisation: Organisation): Boolean = organisation.supports(ogrodjeToday())

    @SchemaMapping(typeName = "Organisation")
    fun supportStartedAt(organisation: Organisation): LocalDate? = organisation.supportStartedAt.takeIf { signedIn() }

    @SchemaMapping(typeName = "Organisation")
    fun supportEndedAt(organisation: Organisation): LocalDate? = organisation.supportEndedAt.takeIf { signedIn() }

    @SchemaMapping(typeName = "Organisation")
    fun vatId(organisation: Organisation): String? = organisation.vatId.takeIf { signedIn() }

    @SchemaMapping(typeName = "Organisation")
    fun companyId(organisation: Organisation): String? = organisation.companyId.takeIf { signedIn() }

    @SchemaMapping(typeName = "Organisation")
    fun address(organisation: Organisation): String? = organisation.address.takeIf { signedIn() }

    @SchemaMapping(typeName = "Organisation")
    fun contactPerson(organisation: Organisation): Person? = organisation.contactPerson.takeIf { signedIn() }

    @BatchMapping(typeName = "Organisation", field = "people")
    fun people(organisations: List<Organisation>): Map<Organisation, List<PersonOrganisation>> =
        if (signedIn()) this.organisations.peopleOf(organisations) else organisations.associateWith { emptyList() }

    @BatchMapping(typeName = "Organisation", field = "episodes")
    fun episodes(organisations: List<Organisation>): Map<Organisation, List<Episode>> {
        val signedIn = signedIn()
        return this.organisations.supportedBy(organisations)
            .mapValues { (_, episodes) -> episodes.filter { signedIn || it.status == EpisodeStatus.PUBLISHED } }
    }

    @BatchMapping(typeName = "Organisation", field = "logo")
    fun logos(organisations: List<Organisation>): Map<Organisation, ImageDto> {
        val images = images.ifAvailable ?: return emptyMap()
        val byId = imagesRepository.findAllById(organisations.mapNotNull { it.logoImageId }.toSet()).associateBy { it.id }
        return organisations.mapNotNull { o -> o.logoImageId?.let(byId::get)?.let { o to images.toDto(it) } }.toMap()
    }

    // Whoever may see the episode may see who supported it
    @BatchMapping(typeName = "Episode", field = "supportedBy")
    fun supportedBy(episodes: List<Episode>): Map<Episode, List<Organisation>> = organisations.supportersOf(episodes)

    @BatchMapping(typeName = "Person", field = "organisations")
    fun work(people: List<Person>): Map<Person, List<PersonOrganisation>> =
        if (signedIn()) organisations.workOf(people) else people.associateWith { emptyList() }

    @SchemaMapping(typeName = "OrganisationPerson")
    fun current(entry: PersonOrganisation): Boolean = entry.current(ogrodjeToday())

    @MutationMapping
    @PreAuthorize(WRITES)
    fun createOrganisation(@Argument input: OrganisationInput): Organisation =
        organisations.create(requireNotNull(input.name) { "An organisation needs its name" }, input.changes())

    @MutationMapping
    @PreAuthorize(WRITES)
    fun updateOrganisation(@Argument id: UUID, @Argument input: OrganisationInput): Organisation =
        organisations.update(id, input.changes())

    @MutationMapping
    @PreAuthorize(WRITES)
    fun deleteOrganisation(@Argument id: UUID): UUID = checkNotNull(organisations.delete(id).id)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun addPersonToOrganisation(
        @Argument person: UUID,
        @Argument organisation: UUID,
        @Argument title: String?,
        @Argument startedAt: String?,
        @Argument endedAt: String?,
    ): PersonOrganisation = organisations.addWork(person, organisation, WorkChanges(title, startedAt, endedAt))

    @MutationMapping
    @PreAuthorize(WRITES)
    fun updatePersonOrganisation(
        @Argument id: UUID,
        @Argument title: String?,
        @Argument startedAt: String?,
        @Argument endedAt: String?,
    ): PersonOrganisation = organisations.updateWork(id, WorkChanges(title, startedAt, endedAt))

    @MutationMapping
    @PreAuthorize(WRITES)
    fun removePersonFromOrganisation(@Argument id: UUID): UUID = checkNotNull(organisations.removeWork(id).id)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun addEpisodeSupporter(@Argument ref: String, @Argument organisation: UUID): Episode =
        organisations.addSupporter(ref, organisation)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun removeEpisodeSupporter(@Argument ref: String, @Argument organisation: UUID): Episode =
        organisations.removeSupporter(ref, organisation)
}
