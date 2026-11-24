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

private fun weekEvents(monday: LocalDate, upcoming: List<Event>): List<Event> {
    val from = monday.atStartOfDay(LJUBLJANA).toInstant()
    val end = monday.plusWeeks(1).atStartOfDay(LJUBLJANA).toInstant()
    val (new, ongoing) = upcoming.filter {
        !it.organisation.archived && it.hiddenAt == null && it.startsAt.toInstant() < end && it.upcoming(from)
    }.partition { it.startsAt.toInstant() >= from }
    return new.sortedWith(compareBy({ it.startsAt.toInstant() }, { it.id })) +
        ongoing.sortedWith(compareBy({ it.endsAt!!.toInstant() }, { it.id }))
}

internal fun digestMessages(monday: LocalDate, upcoming: List<Event>): List<DigestMessage> {
    val ordered = weekEvents(monday, upcoming)
    val period = "**$monday** do **${monday.plusDays(6)}**"
    if (ordered.isEmpty()) return listOf(DigestMessage("Za obdobje od $period nismo našli nobenega dogodka.", emptyList()))
    return ordered.chunked(5).mapIndexed { index, batch ->
        val embeds = batch.map { event ->
            val start = event.startsAt.digestTime(event.hasStartTime)
            val finish = event.endsAt?.let { endTime ->
                val local = endTime.atZoneSameInstant(LJUBLJANA)
                if (local.toLocalDate() != event.startsAt.atZoneSameInstant(LJUBLJANA).toLocalDate()) {
                    endTime.digestTime(event.hasEndTime)
                } else if (event.hasEndTime && !endTime.isEqual(event.startsAt)) {
                    local.format(DateTimeFormatter.ofPattern("HH:mm"))
                } else null
            }
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

internal fun previewMessages(monday: LocalDate, upcoming: List<Event>): List<DigestMessage> {
    val events = weekEvents(monday, upcoming)
    if (events.isEmpty()) return digestMessages(monday, emptyList())
    val heading = "Dogodki za obdobje **$monday** do **${monday.plusDays(6)}**."
    val posts = mutableListOf<DigestMessage>()
    var content = heading
    var previousDay = ""
    val dayFormat = DateTimeFormatter.ofPattern("EEEE, d. M.", java.util.Locale.forLanguageTag("sl"))
    for (event in events) {
        val start = event.startsAt.atZoneSameInstant(LJUBLJANA)
        val ongoing = start.toLocalDate() < monday
        val day = if (ongoing) "Še traja" else start.format(dayFormat)
        val time = if (!ongoing && event.hasStartTime) start.format(DateTimeFormatter.ofPattern("HH:mm ")) else ""
        val title = event.title.replace(Regex("""\s+"""), " ").take(240)
            .map { if (it in """\[]*_~`|""") "\\$it" else it.toString() }.joinToString("")
        val link = event.url?.takeIf { url ->
            url.length <= 900 && (url.startsWith("https://") || url.startsWith("http://")) &&
                url.none { it.isWhitespace() || it in "<>" }
        }
            ?: "https://ogrodje.si/dogodki/${checkNotNull(event.id)}"
        val line = "$time[$title](<$link>)"
        val section = "\n\n**$day**"
        val prefix = if (previousDay == day) "" else section
        if (content.length + prefix.length + line.length + 1 > 2000) {
            posts.add(DigestMessage(content, emptyList()))
            content = "**$day**"
        } else content += prefix
        content += "\n$line"
        previousDay = day
    }
    return posts + DigestMessage(content, emptyList())
}

@ActivityInterface
interface DigestActivities {
    fun prepareDigest(monday: String): List<DigestMessage>
    fun preparePreview(monday: String): List<DigestMessage>
    /** Milliseconds before a rejected, rate-limited request may be retried, or zero on success. */
    fun postDigest(message: DigestMessage, sample: Boolean): Long
}

@Component
@ConditionalOnTemporal
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class DigestActivitiesImpl(
    private val events: Events, private val discord: DigestWebhook,
    @Value("\${DISCORD_EVENTS_WEBHOOK:}") private val liveUrl: String,
    @Value("\${DISCORD_EVENTS_TEST_WEBHOOK:}") private val testUrl: String,
) : DigestActivities {
    override fun prepareDigest(monday: String): List<DigestMessage> {
        val week = LocalDate.parse(monday)
        return digestMessages(week, events.upcoming(week.atStartOfDay(LJUBLJANA).toInstant()))
    }

    override fun preparePreview(monday: String): List<DigestMessage> {
        val week = LocalDate.parse(monday)
        return previewMessages(week, events.upcoming(week.atStartOfDay(LJUBLJANA).toInstant()))
    }

    override fun postDigest(message: DigestMessage, sample: Boolean): Long {
        val url = if (sample) testUrl else liveUrl
        require(url.isNotBlank()) { "Discord webhook is missing for the selected destination" }
        return discord.send(url, message)
    }
}

@Component
class DigestWebhook(private val json: JsonMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun send(url: String, message: DigestMessage): Long {
        val request = HttpRequest.newBuilder(URI.create(url + (if ('?' in url) "&" else "?") + "wait=true"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("content" to message.content, "embeds" to message.embeds,
                "allowed_mentions" to mapOf("parse" to emptyList<String>()))))).build()
        // Exceptions from the HTTP client may include the secret URL. Never put them in workflow history.
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApplicationFailure.newNonRetryableFailure("Discord delivery was interrupted", "Unconfirmed")
        } catch (_: java.net.ConnectException) {
            return 3000
        } catch (_: java.io.IOException) {
            throw ApplicationFailure.newNonRetryableFailure("Check the Discord channel before continuing", "Unconfirmed")
        }
        return when (val status = response.statusCode()) {
            in 200..299 -> 0
            429 -> {
                val bodyDelay = try {
                    json.readTree(response.body()).path("retry_after").asDouble(-1.0)
                } catch (_: tools.jackson.core.JacksonException) {
                    -1.0
                }
                val seconds = bodyDelay.takeIf { it.isFinite() && it >= 0 }
                    ?: response.headers().firstValue("Retry-After").orElse("").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?: 3.0
                (seconds * 1000).toLong().coerceAtLeast(1000)
            }
            400, 413, 422 -> throw ApplicationFailure.newNonRetryableFailure("Discord rejected the batch (HTTP $status)", "InvalidBatch")
            in 400..499 -> throw ApplicationFailure.newNonRetryableFailure("Discord rejected the webhook (HTTP $status)", "Rejected")
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
    private val log = Workflow.getLogger(EventsDigestWorkflowImpl::class.java)
    private fun activities(attempts: Int, window: Duration) = Workflow.newActivityStub(DigestActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(45)).setScheduleToCloseTimeout(window)
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(attempts).setMaximumInterval(Duration.ofMinutes(5)).build()).build())
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
        fun remaining() = Duration.ofMillis((cutoff - Workflow.currentTimeMillis()).coerceAtLeast(0))
        if (!sample && remaining().isZero) return
        val lookup = activities(if (sample) 5 else 0, if (sample) Duration.ofMinutes(10) else remaining())
        val compact = sample && Workflow.getVersion("compact-preview", Workflow.DEFAULT_VERSION, 1) == 1
        val messages = if (compact) lookup.preparePreview(monday) else lookup.prepareDigest(monday)
        for ((index, message) in messages.withIndex()) {
            while (sample || Workflow.currentTimeMillis() < cutoff) {
                val delay = try {
                    activities(1, if (sample) Duration.ofMinutes(5) else remaining()).postDigest(message, sample)
                } catch (e: ActivityFailure) {
                    val cause = e.cause
                    if (cause is ApplicationFailure && cause.type == "InvalidBatch") {
                        log.warn("Discord rejected digest {} batch {}. Skipping this batch.", monday, index + 1)
                        break
                    }
                    if (cause is ApplicationFailure && cause.type != "Unconfirmed") {
                        log.warn("Discord digest {} stopped at batch {} ({})", monday, index + 1, cause.type)
                        throw e
                    }
                    log.warn("Check Discord for digest {} batch {} and signal confirmBatch", monday, index + 1)
                    awaitingBatch = index + 1
                    posted = null
                    if (sample) Workflow.await { posted != null } else Workflow.await(remaining()) { posted != null }
                    awaitingBatch = null
                    if (posted == true) break
                    continue
                }
                if (delay == 0L) break
                val wait = Duration.ofMillis(delay)
                Workflow.sleep(if (sample) wait else minOf(wait, remaining()))
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
    @Value("\${DISCORD_EVENTS_TEST_WEBHOOK:}") private val testUrl: String,
) {
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    fun weekly() {
        if (liveUrl.isBlank()) return
        val monday = digestWeek(Instant.now()) ?: return
        start(monday, "events-digest-$monday", false)
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    fun dailyPreview() {
        if (testUrl.isBlank()) return
        val now = Instant.now().atZone(LJUBLJANA)
        if (now.hour < 9) return
        val today = now.toLocalDate()
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        start(monday, "events-digest-preview-$today", true)
    }

    private fun start(monday: LocalDate, id: String, sample: Boolean) {
        val workflows = client.ifAvailable ?: return
        val workflow = workflows.newWorkflowStub(EventsDigestWorkflow::class.java, WorkflowOptions.newBuilder()
            .setTaskQueue(WORKFLOWS_QUEUE).setWorkflowId(id)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build())
        try {
            WorkflowClient.start(workflow::publish, monday.toString(), sample)
        } catch (_: WorkflowExecutionAlreadyStarted) {
            // Both pods, and every later tick in the catch-up window, share the same ID.
        }
    }
}
