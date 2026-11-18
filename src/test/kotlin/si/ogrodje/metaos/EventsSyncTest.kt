package si.ogrodje.metaos

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scrapers as their worker of the sync's activity: a Meetup link has one event, an iCal one fails as a site that's
 * down does, the rest have none, as a link no parser takes
 */
@ActivityImpl(taskQueues = [SCRAPERS_QUEUE])
class FakeScrapers : ScraperActivities {
    val asked = CopyOnWriteArrayList<ScrapeRequest>()

    override fun scrape(request: ScrapeRequest): ScrapeResult {
        asked += request
        return when (request.field) {
            EventField.MEETUP -> {
                val start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
                ScrapeResult("MEETUP", listOf(FoundEvent("MEETUP", "meetup-${request.organisationName}", request.url, "Meetup of ${request.organisationName}", start)))
            }
            EventField.ICAL -> throw ApplicationFailure.newNonRetryableFailure("${request.url} answered 503", "WebException")
            else -> ScrapeResult(null, emptyList())
        }
    }
}

@SpringBootTest(properties = ["spring.temporal.test-server.enabled=true"])
@Import(TestcontainersConfig::class, EventsSyncTest.Config::class)
class EventsSyncTest @Autowired constructor(
    private val client: WorkflowClient,
    private val scrapers: FakeScrapers,
    private val organisationsMCP: OrganisationsMCP,
    private val eventsMCP: EventsMCP,
    private val syncs: EventsSyncs,
) {

    @TestConfiguration
    class Config {
        @Bean
        fun fakeScrapers() = FakeScrapers()
    }

    private fun sync(request: SyncRequest): SyncSummary = client.newWorkflowStub(
        EventsSyncWorkflow::class.java,
        WorkflowOptions.newBuilder().setTaskQueue(WORKFLOWS_QUEUE).setWorkflowId("events-sync-test-${UUID.randomUUID()}").build(),
    ).sync(request)

    @Test
    fun `scrapes every link of those that gather events, keeps what's found, and says how each link went`() {
        val gathering = checkNotNull(organisationsMCP.create(name = "Sync Gathering", websiteUrl = "https://gathering.test",
            meetupUrl = "https://www.meetup.com/gathering/", icalUrl = "https://gathering.test/cal.ics").id)
        val notCollecting = checkNotNull(organisationsMCP.create(name = "Sync Quiet", meetupUrl = "https://www.meetup.com/quiet/").id)
        organisationsMCP.update(notCollecting, collectsEvents = false)
        val archived = checkNotNull(organisationsMCP.create(name = "Sync Archived", meetupUrl = "https://www.meetup.com/archived/").id)
        organisationsMCP.update(archived, archived = true)

        val summary = sync(SyncRequest(gathering.toString()))
        assertEquals(SyncSummary(links = 3, failed = 1, added = 1, changed = 0), summary)
        assertEquals(listOf("Meetup of Sync Gathering"), eventsMCP.list(organisationId = gathering).map { it.title })

        val scrapes = organisationsMCP.get(gathering).eventScrapes.associateBy { it.field }
        assertEquals("MEETUP", scrapes.getValue(EventField.MEETUP).source)
        assertEquals(1, scrapes.getValue(EventField.MEETUP).events)
        assertTrue(scrapes.getValue(EventField.ICAL).error!!.contains("503"))
        assertEquals(null, scrapes.getValue(EventField.WEBSITE).source)

        // Again: nothing new; and the whole sync leaves out who doesn't gather events
        sync(SyncRequest())
        assertTrue(scrapers.asked.none { it.organisationName == "Sync Quiet" || it.organisationName == "Sync Archived" })
        assertEquals(1, eventsMCP.list(organisationId = gathering).size)
    }

    @Test
    fun `the team refreshes one organisation's events, once at a time`() {
        val id = checkNotNull(organisationsMCP.create(name = "Sync Refreshed", meetupUrl = "https://www.meetup.com/refreshed/").id)
        syncs.refresh(id)
        client.newUntypedWorkflowStub("events-refresh-$id").getResult(30, java.util.concurrent.TimeUnit.SECONDS, SyncSummary::class.java)
        assertEquals(false, syncs.status(id).running)
        assertEquals(listOf("Meetup of Sync Refreshed"), eventsMCP.list(organisationId = id).map { it.title })
    }
}
