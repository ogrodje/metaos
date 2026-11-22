package si.ogrodje.metaos

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal fun digestWeek(now: Instant): LocalDate? {
    val local = now.atZone(LJUBLJANA)
    return when {
        local.dayOfWeek == DayOfWeek.SUNDAY && local.hour >= 19 -> local.toLocalDate().plusDays(1)
        local.dayOfWeek == DayOfWeek.MONDAY && local.hour < 12 -> local.toLocalDate()
        else -> null
    }
}

data class DigestField(val name: String, val value: String)
data class DigestEmbed(val title: String, val url: String, val fields: List<DigestField>)
data class DigestMessage(val content: String, val embeds: List<DigestEmbed>)

private fun OffsetDateTime.digestTime(timed: Boolean): String {
    val local = atZoneSameInstant(LJUBLJANA)
    return if (timed) local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) else local.toLocalDate().toString()
}

internal fun digestMessages(monday: LocalDate, upcoming: List<Event>): List<DigestMessage> {
    val from = monday.atStartOfDay(LJUBLJANA).toInstant()
    val end = monday.plusWeeks(1).atStartOfDay(LJUBLJANA).toInstant()
    val (new, ongoing) = upcoming.filter {
        !it.organisation.archived && it.hiddenAt == null && it.startsAt.toInstant() < end && it.upcoming(from)
    }.partition { it.startsAt.toInstant() >= from }
    val ordered = new.sortedWith(compareBy({ it.startsAt.toInstant() }, { it.id })) +
        ongoing.sortedWith(compareBy({ it.endsAt!!.toInstant() }, { it.id }))
    val period = "od **$monday** do **${monday.plusDays(6)}**"
    if (ordered.isEmpty()) return listOf(DigestMessage("Za obdobje $period nismo našli nobenega dogodka.", emptyList()))
    return ordered.chunked(5).mapIndexed { index, batch ->
        val embeds = batch.map { event ->
            val start = event.startsAt.digestTime(event.hasStartTime)
            val finish = event.endsAt?.digestTime(event.hasEndTime)
            val fields = buildList {
                add(DigestField("Pričetek / Konec", if (finish == null || finish == start) start else "$start – $finish"))
                event.place()?.let { place ->
                    // Bounds the five embeds together below Discord's 6000-character message limit.
                    add(DigestField("Lokacija", listOfNotNull(place.name, place.address).joinToString(", ").take(800)))
                }
            }
            val star = if (event.promotedAt != null) "🌟 " else ""
            DigestEmbed(("$star${event.organisation.name} / ${event.title}").take(256),
                event.url ?: "https://ogrodje.si/dogodki/${checkNotNull(event.id)}", fields)
        }
        DigestMessage(if (index == 0) "Dogodki za obdobje $period." else "Paket ${index + 1}.", embeds)
    }
}

@ActivityInterface
interface DigestActivities {
    fun prepareDigest(monday: String): List<DigestMessage>
    /** Milliseconds before a rejected, rate-limited request may be retried, or zero on success. */
    fun postDigest(message: DigestMessage, sample: Boolean): Long
}

@Component
@ConditionalOnTemporal
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class DigestActivitiesImpl(
    private val events: Events, private val json: JsonMapper,
    @Value("\${DISCORD_EVENTS_WEBHOOK:}") private val liveUrl: String,
    @Value("\${DISCORD_EVENTS_TEST_WEBHOOK:}") private val testUrl: String,
) : DigestActivities {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun prepareDigest(monday: String): List<DigestMessage> {
        val week = LocalDate.parse(monday)
        return digestMessages(week, events.upcoming(week.atStartOfDay(LJUBLJANA).toInstant()))
    }

    override fun postDigest(message: DigestMessage, sample: Boolean): Long {
        val url = if (sample) testUrl else liveUrl
        require(url.isNotBlank()) { "Discord webhook is missing for the selected destination" }
        val request = HttpRequest.newBuilder(URI.create(url + (if ('?' in url) "&" else "?") + "wait=true"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message))).build()
        // Exceptions from the HTTP client may include the secret URL. Never put them in workflow history.
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: java.net.ConnectException) {
            return 3000
        } catch (_: java.io.IOException) {
            throw ApplicationFailure.newNonRetryableFailure("Check the Discord channel before continuing", "Unconfirmed")
        }
        return when (val status = response.statusCode()) {
            in 200..299 -> 0
            429 -> (json.readTree(response.body()).path("retry_after").asDouble(3.0) * 1000).toLong().coerceAtLeast(1000)
            in 400..499 -> throw ApplicationFailure.newNonRetryableFailure("Discord rejected the post (HTTP $status)", "Rejected")
            else -> throw ApplicationFailure.newNonRetryableFailure("Discord response was HTTP $status", "Unconfirmed")
        }
    }
}

@WorkflowInterface
interface EventsDigestWorkflow {
    @WorkflowMethod fun publish(monday: String, sample: Boolean)
    @QueryMethod fun pendingBatch(): Int?
    @SignalMethod fun confirmBatch(batch: Int, alreadyPosted: Boolean)
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class EventsDigestWorkflowImpl : EventsDigestWorkflow {
    private fun activities(attempts: Int) = Workflow.newActivityStub(DigestActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(45))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(attempts).build()).build())
    private var awaitingBatch: Int? = null
    private var posted: Boolean? = null

    override fun pendingBatch(): Int? = awaitingBatch

    override fun confirmBatch(batch: Int, alreadyPosted: Boolean) {
        if (batch == awaitingBatch) posted = alreadyPosted
    }

    override fun publish(monday: String, sample: Boolean) {
        val week = LocalDate.parse(monday)
        require(week.dayOfWeek == DayOfWeek.MONDAY)
        val cutoff = week.atTime(12, 0).atZone(LJUBLJANA).toInstant().toEpochMilli()
        val messages = activities(5).prepareDigest(monday)
        val sender = activities(1)
        for ((index, message) in messages.withIndex()) {
            while (sample || Workflow.currentTimeMillis() < cutoff) {
                val delay = try {
                    sender.postDigest(message, sample)
                } catch (e: ActivityFailure) {
                    val cause = e.cause
                    if (cause is ApplicationFailure && cause.type != "Unconfirmed") throw e
                    awaitingBatch = index + 1
                    posted = null
                    Workflow.await { posted != null }
                    awaitingBatch = null
                    if (posted == true) break
                    continue
                }
                if (delay == 0L) break
                Workflow.sleep(Duration.ofMillis(delay))
            }
            if (!sample && Workflow.currentTimeMillis() >= cutoff) return
            if (index < messages.lastIndex) Workflow.sleep(Duration.ofSeconds(3))
        }
    }
}

@Component
class EventsDigests(
    private val client: ObjectProvider<WorkflowClient>,
    @Value("\${DISCORD_EVENTS_WEBHOOK:}") private val liveUrl: String,
) {
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    fun weekly() {
        if (liveUrl.isBlank()) return
        val monday = digestWeek(Instant.now()) ?: return
        val workflows = client.ifAvailable ?: return
        val workflow = workflows.newWorkflowStub(EventsDigestWorkflow::class.java, WorkflowOptions.newBuilder()
            .setTaskQueue(WORKFLOWS_QUEUE).setWorkflowId("events-digest-$monday")
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build())
        try {
            WorkflowClient.start(workflow::publish, monday.toString(), false)
        } catch (_: WorkflowExecutionAlreadyStarted) {
            // Both pods, and every later tick in the catch-up window, share this week's ID.
        }
    }
}
