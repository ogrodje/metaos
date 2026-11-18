package si.ogrodje.metaos

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.TemporalFailure
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The task queue the scrapers work on (scrapers/, their Activities.kt): reading organisations' links for events */
const val SCRAPERS_QUEUE = "scrapers"

/**
 * One link of one organisation to scrape, as the scrapers take it (their ScrapeRequest, of the same shape): [since] is
 * the oldest end that's still wanted, a year ago
 */
data class ScrapeRequest(
    val organisationId: String,
    val organisationName: String,
    val field: EventField,
    val url: String,
    val since: Instant,
)

/** What the scrapers found at a link: by which parser ([source], none when none takes it), and its events */
data class ScrapeResult(val source: String? = null, val events: List<FoundEvent> = emptyList())

/** What a sync did: how many links it scraped, how many failed, and how many events were new or changed */
data class SyncSummary(val links: Int, val failed: Int, val added: Int, val changed: Int)

/** What to sync: one organisation's links, or every organisation's that gathers events */
data class SyncRequest(val organisationId: String? = null)

/**
 * The scrapers' activity, which they work on, on [SCRAPERS_QUEUE]: they have the same interface by the same name, with
 * their own types of the same shape. They only read the web; MetaOS keeps what they found ([EventsActivities]).
 */
@ActivityInterface
interface ScraperActivities {
    @ActivityMethod(name = "ScrapeEvents")
    fun scrape(request: ScrapeRequest): ScrapeResult
}

/** MetaOS's own part of a sync: which links to scrape, and keeping what was found */
@ActivityInterface
interface EventsActivities {
    fun links(request: SyncRequest): List<ScrapeRequest>

    fun keep(request: ScrapeRequest, result: ScrapeResult): FoundCounts

    fun failed(request: ScrapeRequest, error: String)
}

/**
 * Gathers events from organisations' links, as Goo's sync did every hour: every link of every organisation that
 * gathers events (not archived, collecting them), or one organisation's, scraped by the scrapers, six at once on their
 * worker, each kept as it comes. A link that fails is tried again three times, then left for the next sync, the
 * others going on.
 */
@WorkflowInterface
interface EventsSyncWorkflow {
    @WorkflowMethod
    fun sync(request: SyncRequest): SyncSummary
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class EventsSyncWorkflowImpl : EventsSyncWorkflow {

    private val scrapers = Workflow.newActivityStub(
        ScraperActivities::class.java,
        ActivityOptions.newBuilder()
            .setTaskQueue(SCRAPERS_QUEUE)
            // A site's events page and an event's pages, or a browser's page
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            // Waiting its turn among every organisation's links, and for the scrapers to be there
            .setScheduleToCloseTimeout(Duration.ofHours(2))
            .setRetryOptions(
                RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(30)).setBackoffCoefficient(2.0)
                    .setMaximumAttempts(4).build()
            )
            .build(),
    )

    private val events = Workflow.newActivityStub(
        EventsActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).setDoNotRetry(IllegalArgumentException::class.java.name).build())
            .build(),
    )

    override fun sync(request: SyncRequest): SyncSummary {
        val links = events.links(request)
        val results: List<Promise<FoundCounts?>> = links.map { link ->
            Async.function<FoundCounts?> {
                try {
                    events.keep(link, scrapers.scrape(link))
                } catch (e: ActivityFailure) {
                    events.failed(link, why(e))
                    null
                }
            }
        }
        val counts = results.map { it.get() }
        return SyncSummary(
            links = links.size,
            failed = counts.count { it == null },
            added = counts.sumOf { it?.added ?: 0 },
            changed = counts.sumOf { it?.changed ?: 0 },
        )
    }

    private fun why(e: ActivityFailure): String {
        var cause: Throwable? = e.cause
        while (cause is ActivityFailure) cause = cause.cause
        return (cause as? ApplicationFailure)?.originalMessage ?: (cause as? TemporalFailure)?.originalMessage ?: cause?.message ?: "It failed"
    }
}

/**
 * MetaOS's activities of a sync. What they write is in the audit log as the workflows' (metaos-workflows), as nobody
 * asked for it, even when someone pressed Refresh: what a site says isn't theirs.
 */
@Component
@ConditionalOnTemporal
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class EventsActivitiesImpl(private val organisations: Organisations, private val events: Events) : EventsActivities {

    override fun links(request: SyncRequest): List<ScrapeRequest> = actingFor(SCRAPERS) {
        val since = Instant.now().minus(EVENTS_KEPT_FOR)
        val which = request.organisationId?.let { listOf(organisations.find(UUID.fromString(it))) } ?: organisations.all(archived = false)
        which.filter { it.gathersEvents }.flatMap { organisation ->
            organisation.eventSources().map { (field, url) ->
                ScrapeRequest(checkNotNull(organisation.id).toString(), organisation.name, field, url, since)
            }
        }
    }

    override fun keep(request: ScrapeRequest, result: ScrapeResult): FoundCounts = actingFor(SCRAPERS) {
        val organisationId = UUID.fromString(request.organisationId)
        val counts = events.keep(organisationId, result.events)
        events.scraped(organisationId, request.field, request.url, result.source, result.events.size, null)
        counts
    }

    override fun failed(request: ScrapeRequest, error: String) = actingFor(SCRAPERS) {
        events.scraped(UUID.fromString(request.organisationId), request.field, request.url, null, 0, error)
    }

    private companion object {
        val SCRAPERS = Actor(null, null)
    }
}

/** Whether a refresh is on: the whole's, or an organisation's, and since when */
data class EventsRefresh(val running: Boolean, val startedAt: Instant?, val organisationId: UUID?)

/**
 * Starts syncs ([EventsSyncWorkflow]): every hour, from whichever pod asks first that hour (by the hour in its
 * workflow's ID), and when the team presses Refresh, for every organisation or one, while one isn't on already.
 * Without Temporal (TEMPORAL_ADDRESS) there's none.
 */
@Component
class EventsSyncs(private val client: ObjectProvider<WorkflowClient>) {

    private val log = LoggerFactory.getLogger(javaClass)

    val available: Boolean get() = client.ifAvailable != null

    @Scheduled(fixedDelay = 10, initialDelay = 3, timeUnit = TimeUnit.MINUTES)
    fun hourly() {
        val workflows = client.ifAvailable ?: return
        val hour = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").format(Instant.now().atOffset(ZoneOffset.UTC))
        try {
            start(workflows, "events-sync-$hour", SyncRequest(), WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        } catch (_: WorkflowExecutionAlreadyStarted) {
            // This hour's is on, or done
        } catch (e: RuntimeException) {
            log.warn("The events sync didn't start: {}", e.message)
        }
    }

    /** Starts a refresh of every organisation's events, or of [organisationId]'s, unless one's on, and answers with it */
    fun refresh(organisationId: UUID? = null): EventsRefresh {
        val workflows = checkNotNull(client.ifAvailable) { "Events can't be refreshed here: MetaOS has no Temporal (TEMPORAL_ADDRESS)" }
        start(workflows, refreshId(organisationId), SyncRequest(organisationId?.toString()), WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
        return status(organisationId)
    }

    /** Whether a refresh of every organisation's events, or of [organisationId]'s, is on */
    fun status(organisationId: UUID? = null): EventsRefresh {
        val workflows = client.ifAvailable ?: return EventsRefresh(false, null, organisationId)
        return try {
            val description = workflows.newUntypedWorkflowStub(refreshId(organisationId)).describe()
            EventsRefresh(
                description.status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING,
                description.startTime,
                organisationId,
            )
        } catch (_: WorkflowNotFoundException) {
            EventsRefresh(false, null, organisationId)
        }
    }

    private fun refreshId(organisationId: UUID?) = organisationId?.let { "events-refresh-$it" } ?: "events-refresh"

    private fun start(workflows: WorkflowClient, id: String, request: SyncRequest, reuse: WorkflowIdReusePolicy) {
        val workflow = workflows.newWorkflowStub(
            EventsSyncWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(WORKFLOWS_QUEUE)
                .setWorkflowId(id)
                .setWorkflowIdReusePolicy(reuse)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .setWorkflowExecutionTimeout(Duration.ofHours(3))
                .build(),
        )
        WorkflowClient.start(workflow::sync, request)
        log.info("Events sync {} started", id)
    }
}
