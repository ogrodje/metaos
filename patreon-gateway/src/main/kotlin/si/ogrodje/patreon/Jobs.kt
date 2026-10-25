package si.ogrodje.patreon

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }

/**
 * Something the gateway does in Patreon's web app, which can take minutes (an upload): queued, then run one at a
 * time. [result] is what it made, e.g. the post; [error] says why it failed, or why it waits to try again until
 * [retryAt]; [screenshot] whether the page it failed on was kept (`GET /jobs/{id}/screenshot`).
 */
data class Job(
    val id: String,
    val kind: String,
    val status: JobStatus,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val result: Any? = null,
    val error: String? = null,
    val screenshot: Boolean = false,
    val idempotencyKey: String? = null,
    val attempts: Int = 0,
    val retryAt: Instant? = null,
)

/**
 * The jobs, in memory: there's one gateway (one browser session), and a job lost to a restart is one its caller sees
 * not finish, and sends again. An idempotency key gives back the job it was first sent with, so a retried request
 * doesn't make a second post. Patreon turns the gateway away now and then (a rate limit): such a job waits and tries
 * again, 5, 10 and 20 minutes later, before it fails, and the jobs after it wait with it: Patreon would turn them away
 * too, and one for the same post mustn't go first, e.g. publishing it before the change sent before it.
 */
@Component
class Jobs(private val browser: Browser, private val clock: Clock) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val queue = Executors.newSingleThreadExecutor { Thread(it, "jobs").apply { isDaemon = true } }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val byKey = ConcurrentHashMap<String, String>()

    // How long a job Patreon turned away waits before each next try; tests make them short
    internal var retries: List<Duration> = listOf(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(20))

    /** Queues [work], which gets the job's ID (the name of what a failure kept), unless [key] already queued one. */
    fun submit(kind: String, key: String?, work: (String) -> Any?): Job = synchronized(this) {
        key?.let { byKey[it] }?.let { jobs[it] }?.let { return it }
        val job = Job(UUID.randomUUID().toString(), kind, JobStatus.QUEUED, clock.instant(), idempotencyKey = key)
        jobs[job.id] = job
        key?.let { byKey[it] = job.id }
        forgetOld()
        queue.submit { run(job.id, work) }
        job
    }

    fun get(id: String): Job? = jobs[id]

    /** The latest jobs first. */
    fun list(): List<Job> = jobs.values.sortedByDescending { it.createdAt }

    // On the queue's one thread, which a job Patreon turned away holds while it waits, so the jobs keep their order
    private fun run(id: String, work: (String) -> Any?) {
        while (true) {
            val job = jobs.computeIfPresent(id) { _, it ->
                it.copy(status = JobStatus.RUNNING, startedAt = it.startedAt ?: clock.instant(), attempts = it.attempts + 1, retryAt = null)
            } ?: return
            try {
                val result = work(id)
                update(id) { it.copy(status = JobStatus.SUCCEEDED, finishedAt = clock.instant(), result = result, error = null) }
                return
            } catch (e: PatreonBusyException) {
                val wait = retries.getOrNull(job.attempts - 1) ?: return fail(id, e)
                log.info("Job {} ({}): Patreon is busy, trying again in {}", id, job.kind, wait)
                update(id) { it.copy(status = JobStatus.QUEUED, error = e.message, retryAt = clock.instant().plus(wait)) }
                try {
                    Thread.sleep(wait.toMillis())
                } catch (_: InterruptedException) {
                    return fail(id, e)
                }
            } catch (e: Throwable) {
                return fail(id, e)
            }
        }
    }

    private fun fail(id: String, e: Throwable) {
        log.warn("Job {} failed: {}", id, e.message)
        update(id) {
            it.copy(
                status = JobStatus.FAILED, finishedAt = clock.instant(),
                error = e.message ?: e.javaClass.simpleName,
                screenshot = browser.failure(id, "png") != null,
            )
        }
    }

    private fun update(id: String, change: (Job) -> Job) {
        jobs.computeIfPresent(id) { _, it -> change(it) }
    }

    // The latest 500 are plenty: a caller asks about its job within minutes
    private fun forgetOld() {
        if (jobs.size <= 500) return
        jobs.values.filter { it.status == JobStatus.SUCCEEDED || it.status == JobStatus.FAILED }
            .sortedBy { it.createdAt }.take(jobs.size - 500)
            .forEach { old -> jobs.remove(old.id); old.idempotencyKey?.let { byKey.remove(it, old.id) } }
    }

    override fun destroy() {
        queue.shutdownNow()
    }
}
