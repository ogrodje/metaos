package si.ogrodje.patreon

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobsTest {
    @TempDir
    lateinit var state: Path

    private val jobs by lazy { Jobs(Browser(PatreonProperties(stateDir = state), JsonMapper.builder().build()), Clock.systemUTC()) }

    @AfterEach
    fun stop() = jobs.destroy()

    private fun finished(id: String): Job {
        repeat(200) {
            val job = jobs.get(id)!!
            if (job.status == JobStatus.SUCCEEDED || job.status == JobStatus.FAILED) return job
            Thread.sleep(20)
        }
        error("Job $id didn't finish")
    }

    @Test
    fun `a job runs and keeps what it made`() {
        val job = jobs.submit("test", null) { "made" }
        val done = finished(job.id)
        assertEquals(JobStatus.SUCCEEDED, done.status)
        assertEquals("made", done.result)
        assertEquals(1, done.attempts)
    }

    @Test
    fun `the same idempotency key gives back the first job and doesn't run again`() {
        val runs = AtomicInteger()
        val first = jobs.submit("test", "key") { runs.incrementAndGet() }
        finished(first.id)
        val again = jobs.submit("test", "key") { runs.incrementAndGet() }
        assertEquals(first.id, again.id)
        assertEquals(1, runs.get())
    }

    @Test
    fun `a failed job says why`() {
        val job = jobs.submit("test", null) { throw IllegalArgumentException("Patreon has no /api/posts/1") }
        val done = finished(job.id)
        assertEquals(JobStatus.FAILED, done.status)
        assertEquals("Patreon has no /api/posts/1", done.error)
    }

    @Test
    fun `a job Patreon turns away tries again, and fails once the tries run out`() {
        jobs.retries = listOf(Duration.ofMillis(50), Duration.ofMillis(50))
        val runs = AtomicInteger()
        val succeeds = jobs.submit("test", null) { if (runs.incrementAndGet() < 3) throw PatreonBusyException("busy") else "made" }
        val done = finished(succeeds.id)
        assertEquals(JobStatus.SUCCEEDED, done.status)
        assertEquals(3, done.attempts)
        assertNull(done.error)

        val gives = jobs.submit("test", null) { throw PatreonBusyException("still busy") }
        val failed = finished(gives.id)
        assertEquals(JobStatus.FAILED, failed.status)
        assertEquals(3, failed.attempts)
        assertTrue(failed.error!!.contains("still busy"))
    }
}
