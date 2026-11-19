package si.ogrodje.metaos

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/** Why two organisations look like one: the same name, one's name in the other's, the same website, or another link */
enum class DuplicateReason { SAME_NAME, NAME_CONTAINS, SAME_WEBSITE, SAME_LINK }

/** Two organisations that look like one, and why, for merging ([OrganisationMerges.merge]) */
data class DuplicatePair(val first: Organisation, val second: Organisation, val reasons: List<DuplicateReason>)

/** A field both had, and differed in: [into]'s is kept, [from]'s dropped */
data class MergeConflict(val field: String, val into: String, val from: String)

/**
 * What merging one organisation into another did, or would do (a dry run): the fields [into] got from [from], those
 * where both had another (into's kept), and the people's work, supported episodes and events moved to it, or dropped as
 * it had them already
 */
data class MergeSummary(
    val into: Organisation,
    val from: Organisation,
    val dryRun: Boolean,
    val filled: List<String>,
    val conflicts: List<MergeConflict>,
    val workMoved: Int,
    val workDropped: Int,
    val episodesMoved: Int,
    val episodesDropped: Int,
    val eventsMoved: Int,
    val eventsDropped: Int,
)

/**
 * Merging an organisation that's there twice, e.g. a partner and the Goo meetup it also was, or one a scraper's name
 * made anew: one is kept, with its name and what it has, and gets the other's: what it hasn't got of its fields, its
 * people's work, the episodes it supported and its events; then the other is soft-deleted, as delete does. Every row
 * that changes is in the audit trail (moved, not made again, so the history tells where it came from), which is how a
 * merge is undone, by hand: restoring the other brings it back without what was moved.
 */
@Component
class OrganisationMerges(
    private val organisations: Organisations,
    private val repository: OrganisationsRepository,
    private val work: PersonOrganisationsRepository,
    private val supported: EpisodeOrganisationsRepository,
    private val events: EventsRepository,
    private val entityManager: EntityManager,
) {

    /** Pairs of live organisations that look like one, archived ones too, those with the most reasons first */
    fun duplicates(): List<DuplicatePair> {
        val all = organisations.all()
        val pairs = mutableListOf<DuplicatePair>()
        for (i in all.indices) for (j in i + 1 until all.size) {
            val reasons = reasons(all[i], all[j])
            if (reasons.isNotEmpty()) pairs += DuplicatePair(all[i], all[j], reasons)
        }
        return pairs.sortedByDescending { it.reasons.size }
    }

    private fun reasons(a: Organisation, b: Organisation): List<DuplicateReason> = buildList {
        val (na, nb) = words(a.name) to words(b.name)
        when {
            na == nb -> add(DuplicateReason.SAME_NAME)
            contains(na, nb) || contains(nb, na) -> add(DuplicateReason.NAME_CONTAINS)
        }
        if (a.websiteUrl != null && b.websiteUrl != null && site(a.websiteUrl!!) == site(b.websiteUrl!!)) add(DuplicateReason.SAME_WEBSITE)
        val linksA = links(a).map(::linkKey).toSet()
        if (links(b).map(::linkKey).any { it in linksA }) add(DuplicateReason.SAME_LINK)
    }

    // A name's words, folded: case, diacritics and punctuation don't matter
    private fun words(name: String) = fold(name).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    // Whether [whole]'s words have [part]'s, in order, e.g. FRI in FRI - Fakulteta za računalništvo; a word of 3 at least
    private fun contains(whole: List<String>, part: List<String>) =
        part.isNotEmpty() && part.joinToString("").length >= 3 && part.size < whole.size &&
            whole.windowed(part.size).any { it == part }

    private fun site(url: String) = runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    private fun links(o: Organisation) = listOfNotNull(
        o.linkedinUrl, o.twitterUrl, o.instagramUrl, o.facebookUrl, o.meetupUrl, o.eventbriteUrl, o.icalUrl, o.lumaUrl,
        o.discordUrl, o.kompotUrl,
    )

    private fun linkKey(url: String) = url.trim().lowercase().removeSuffix("/").replace(Regex("^(https?|webcal)://(www\\.)?"), "")

    /** Merges [fromId] into [intoId] as the class says; with [dryRun], only says what it would do */
    @Transactional
    fun merge(intoId: UUID, fromId: UUID, dryRun: Boolean = false): MergeSummary {
        require(intoId != fromId) { "An organisation can't be merged into itself" }
        val into = organisations.find(intoId)
        val from = organisations.find(fromId)

        // Its fields: what into hasn't got, from from
        val filled = mutableListOf<String>()
        val conflicts = mutableListOf<MergeConflict>()
        fun <T : Any> field(name: String, get: (Organisation) -> T?, set: (T) -> Unit) {
            val mine = get(into)
            val theirs = get(from) ?: return
            if (mine == null) {
                filled += name
                if (!dryRun) set(theirs)
            } else if (mine != theirs) conflicts += MergeConflict(name, show(mine), show(theirs))
        }
        field("description", { it.description }) { into.description = it }
        field("websiteUrl", { it.websiteUrl }) { into.websiteUrl = it }
        field("linkedinUrl", { it.linkedinUrl }) { into.linkedinUrl = it }
        field("twitterUrl", { it.twitterUrl }) { into.twitterUrl = it }
        field("instagramUrl", { it.instagramUrl }) { into.instagramUrl = it }
        field("facebookUrl", { it.facebookUrl }) { into.facebookUrl = it }
        field("logo", { it.logoImageId }) { into.logoImageId = it }
        field("vatId", { it.vatId }) { into.vatId = it }
        field("companyId", { it.companyId }) { into.companyId = it }
        field("address", { it.address }) { into.address = it }
        field("contactPerson", { it.contactPerson }) { into.contactPerson = it }
        field("meetupUrl", { it.meetupUrl }) { into.meetupUrl = it }
        field("eventbriteUrl", { it.eventbriteUrl }) { into.eventbriteUrl = it }
        field("icalUrl", { it.icalUrl }) { into.icalUrl = it }
        field("lumaUrl", { it.lumaUrl }) { into.lumaUrl = it }
        field("discordUrl", { it.discordUrl }) { into.discordUrl = it }
        field("kompotUrl", { it.kompotUrl }) { into.kompotUrl = it }
        field("mainColor", { it.mainColor }) { into.mainColor = it }
        field("backgroundColor", { it.backgroundColor }) { into.backgroundColor = it }
        field("venueName", { it.venueName }) { into.venueName = it }
        field("venueAddress", { it.venueAddress }) { into.venueAddress = it }
        // A region the team gave it; one its venue tells follows the venue it has now
        field("region", { o -> o.region.takeIf { o.regionManual } }) {
            into.region = it
            into.regionManual = true
        }
        if (!dryRun) organisations.tellRegion(into)
        // Its support, whole: the tier with its dates, where into supports Ogrodje in no way
        if (from.tier != OrganisationTier.NONE) {
            if (into.tier == OrganisationTier.NONE) {
                filled += "tier"
                if (!dryRun) {
                    into.tier = from.tier
                    into.supportStartedAt = from.supportStartedAt
                    into.supportEndedAt = from.supportEndedAt
                }
            } else if (into.tier != from.tier || into.supportStartedAt != from.supportStartedAt || into.supportEndedAt != from.supportEndedAt) {
                conflicts += MergeConflict("tier", support(into), support(from))
            }
        }
        // Goo's meetup it was: only one may have it, so from gives it up first
        val gooId = from.gooId
        if (gooId != null) {
            if (into.gooId == null) {
                filled += "gooId"
                if (!dryRun) {
                    from.gooId = null
                    entityManager.flush()
                    into.gooId = gooId
                }
            } else if (into.gooId != gooId) conflicts += MergeConflict("gooId", into.gooId!!, gooId)
        }
        if (from.collectsEvents && !into.collectsEvents) {
            filled += "collectsEvents"
            if (!dryRun) into.collectsEvents = true
        }

        // Its people's work: what into has already, there and then with the same title, is dropped
        val theirWork = work.findByOrganisations(listOf(from))
        val myWork = work.findByOrganisations(listOf(into))
        val (workDropped, workMoved) = theirWork.partition { w ->
            myWork.any { it.person == w.person && fold(it.title.orEmpty()) == fold(w.title.orEmpty()) && overlap(it, w) }
        }
        // The episodes it supported: those into supported too are dropped
        val theirEpisodes = supported.findByOrganisations(listOf(from))
        val myEpisodes = supported.findByOrganisations(listOf(into)).map { it.episode }.toSet()
        val (episodesDropped, episodesMoved) = theirEpisodes.partition { it.episode in myEpisodes }
        // Its events: those into has by the same scraper's ID are dropped, into's keeping the team's hiding and picks
        val theirEvents = events.findByOrganisations(listOf(from))
        val myEvents = events.findByOrganisations(listOf(into)).filter { it.externalId != null }.associateBy { it.externalId }
        val (eventsDropped, eventsMoved) = theirEvents.partition { it.externalId != null && it.externalId in myEvents }

        if (!dryRun) {
            eventsDropped.forEach { e ->
                val mine = myEvents.getValue(e.externalId)
                if (mine.promotedAt == null && e.promotedAt != null) mine.promotedAt = e.promotedAt
                if (mine.hiddenAt == null && e.hiddenAt != null) mine.hiddenAt = e.hiddenAt
            }
            work.deleteAll(workDropped)
            supported.deleteAll(episodesDropped)
            events.deleteAll(eventsDropped)
            entityManager.flush()
            move("person_organisations", workMoved.mapNotNull { it.id }, into)
            move("episode_organisations", episodesMoved.mapNotNull { it.id }, into)
            eventsMoved.forEach { it.organisation = into }
            entityManager.createNativeQuery("delete from event_scrapes where organisation_id = :id").setParameter("id", fromId).executeUpdate()
            // What's loaded still has the moved rows on from, which the bulk updates didn't tell it
            entityManager.flush()
            entityManager.clear()
            repository.delete(organisations.find(fromId))
            entityManager.flush()
        }
        return MergeSummary(
            if (dryRun) into else organisations.find(intoId), from, dryRun, filled, conflicts, workMoved.size, workDropped.size, episodesMoved.size,
            episodesDropped.size, eventsMoved.size, eventsDropped.size,
        )
    }

    // Their organisation is theirs for good in JPA (a val), so they're moved by an update, which the audit trail keeps
    private fun move(table: String, ids: List<UUID>, into: Organisation) {
        if (ids.isEmpty()) return
        val moved = entityManager.createNativeQuery("update $table set organisation_id = :into where id in (:ids)")
            .setParameter("into", into.id).setParameter("ids", ids).executeUpdate()
        check(moved == ids.size) { "Moved $moved of ${ids.size} in $table" }
    }

    // Whether two spells of work share a day, an open start or end being for ever
    private fun overlap(a: PersonOrganisation, b: PersonOrganisation) =
        (a.startedAt ?: LocalDate.MIN) <= (b.endedAt ?: LocalDate.MAX) && (b.startedAt ?: LocalDate.MIN) <= (a.endedAt ?: LocalDate.MAX)

    private fun support(o: Organisation) = listOfNotNull(o.tier.name, o.supportStartedAt?.toString(), o.supportEndedAt?.toString()).joinToString(" ")

    private fun show(value: Any): String = when (value) {
        is Person -> "${value.name} (${value.id})"
        else -> value.toString()
    }
}
