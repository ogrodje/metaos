package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class OrganisationMergesTest @Autowired constructor(
    private val organisationsMCP: OrganisationsMCP,
    private val eventsMCP: EventsMCP,
    private val events: Events,
    private val peopleMCP: PeopleMCP,
    private val episodesMCP: EpisodesMCP,
    private val episodes: EpisodesRepository,
    private val recoveryMCP: RecoveryMCP,
    private val history: History,
) {

    private val soon = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    private fun found(id: String, title: String) =
        FoundEvent("MEETUP", id, "https://www.meetup.com/merge/events", title, soon, soon.plus(2, ChronoUnit.HOURS))

    @Test
    fun `finds organisations that may be one, by name, website and links`() {
        organisationsMCP.create(name = "Hekovnik Merge Finder", websiteUrl = "https://www.hekovnik-finder.test/")
        organisationsMCP.create(name = "Startup Hekovnik Merge Finder d.o.o.", websiteUrl = "https://hekovnik-finder.test")
        organisationsMCP.create(name = "Kotlin Finder", meetupUrl = "https://www.meetup.com/kotlin-finder/")
        organisationsMCP.create(name = "KUG Finder", meetupUrl = "https://meetup.com/Kotlin-Finder")

        val pairs = organisationsMCP.duplicates().associate { setOf(it.first.name, it.second.name) to it.reasons }
        assertEquals(listOf(DuplicateReason.NAME_CONTAINS, DuplicateReason.SAME_WEBSITE),
            pairs[setOf("Hekovnik Merge Finder", "Startup Hekovnik Merge Finder d.o.o.")])
        assertEquals(listOf(DuplicateReason.SAME_LINK), pairs[setOf("Kotlin Finder", "KUG Finder")])
    }

    @Test
    fun `merges one into the other, which keeps what it has and gets the rest, and deletes it`() {
        val keep = checkNotNull(organisationsMCP.create(name = "Muzej Merge", websiteUrl = "https://muzej-merge.test").id)
        organisationsMCP.update(keep, tier = OrganisationTier.PARTNER, description = "The museum")
        val dupe = checkNotNull(organisationsMCP.create(name = "Muzej Merge (Goo)", meetupUrl = "https://www.meetup.com/muzej-merge/").id)
        organisationsMCP.update(dupe, description = "A meetup", venueName = "Muzej", venueAddress = "Celovška 111", mainColor = "#ffffff")

        val person = checkNotNull(peopleMCP.create(name = "Merge Worker", email = null).id)
        val other = checkNotNull(peopleMCP.create(name = "Merge Curator", email = null).id)
        organisationsMCP.addWork(person, keep, title = "Curator", startedAt = "2024-01-01")
        organisationsMCP.addWork(person, dupe, title = "curator", startedAt = "2025-01-01")
        organisationsMCP.addWork(other, dupe, title = "Volunteer")
        episodesMCP.create(code = "merge-1", title = "Both")
        episodesMCP.create(code = "merge-2", title = "Only the meetup")
        organisationsMCP.addSupporter("merge-1", keep)
        organisationsMCP.addSupporter("merge-1", dupe)
        organisationsMCP.addSupporter("merge-2", dupe)
        actingFor(Actor(null, null)) {
            events.keep(keep, listOf(found("meetup-m1", "Both found")))
            events.keep(dupe, listOf(found("meetup-m1", "Both found"), found("meetup-m2", "The meetup's")))
        }
        val picked = eventsMCP.list(organisationId = dupe).first { it.title == "Both found" }
        eventsMCP.update(checkNotNull(picked.id), promoted = true)

        // A dry run says it all and changes nothing
        val dry = organisationsMCP.merge(keep, dupe, dryRun = true)
        assertTrue(dry.dryRun)
        assertEquals(listOf("meetupUrl", "mainColor", "venueName", "venueAddress", "collectsEvents"), dry.filled)
        assertEquals(listOf(MergeConflict("description", "The museum", "A meetup")), dry.conflicts)
        assertEquals(listOf(1, 1, 1, 1, 1, 1), listOf(dry.workMoved, dry.workDropped, dry.episodesMoved, dry.episodesDropped, dry.eventsMoved, dry.eventsDropped))
        assertEquals(null, organisationsMCP.get(keep).organisation.meetupUrl)

        val merged = organisationsMCP.merge(keep, dupe)
        assertEquals("https://www.meetup.com/muzej-merge/", merged.into.meetupUrl)
        assertEquals("The museum", merged.into.description)
        assertEquals(OrganisationTier.PARTNER, merged.into.tier)
        assertTrue(merged.into.collectsEvents)

        val details = organisationsMCP.get(keep)
        assertEquals(setOf("Merge Worker", "Merge Curator"), details.people.map { it.person }.toSet())
        assertEquals(setOf("merge-1", "merge-2"), details.supportedEpisodes.map { it.code }.toSet())
        val kept = eventsMCP.list(organisationId = keep)
        assertEquals(setOf("Both found", "The meetup's"), kept.map { it.title }.toSet())
        assertTrue(kept.first { it.title == "Both found" }.promoted)
        assertEquals("Muzej", kept.first().place?.name)

        assertFailsWith<IllegalArgumentException> { organisationsMCP.get(dupe) }
        assertTrue(recoveryMCP.listDeleted(DeletedKind.ORGANISATION).any { it.id == dupe })
        assertFailsWith<IllegalArgumentException> { organisationsMCP.merge(keep, dupe) }
        assertFailsWith<IllegalArgumentException> { organisationsMCP.merge(keep, keep) }

        // The history tells where what moved came from, on each
        val told = history.of(keep).map { it.summary }
        assertTrue("Moved Merge Curator's work here from Muzej Merge (Goo)" in told, told.toString())
        assertTrue("Moved the event \"The meetup's\" here from Muzej Merge (Goo)" in told, told.toString())
        assertTrue(history.of(dupe).any { it.summary == "Moved its support of merge-2 to Muzej Merge" })
        assertTrue(history.of(checkNotNull(episodes.findByCode("merge-2")?.id)).any {
            it.summary == "Moved its supporter from Muzej Merge (Goo) to Muzej Merge"
        })
        assertTrue(history.of(other).any { it.summary == "Moved their work from Muzej Merge (Goo) to Muzej Merge" })
    }
}
