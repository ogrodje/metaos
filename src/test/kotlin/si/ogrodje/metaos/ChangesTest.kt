package si.ogrodje.metaos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** What a browser gets from /events: an event, a comment (the heartbeat), how soon to reconnect, or the end */
private data class Received(val type: String, val data: String = "")

private val HEARTBEAT = Received("comment")
private val END = Received("end")

private val codes = AtomicInteger(20_000)

// Real HTTP, as MockMvc doesn't stream; a heartbeat a second, so a test needn't wait 25 s for one
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["metaos.changes.heartbeat=1s"])
@Import(TestcontainersConfig::class)
class ChangesTest @Autowired constructor(
    @Value("\${local.server.port}") private val port: Int,
    private val changes: Changes,
    private val episodesRepository: EpisodesRepository,
    private val peopleRepository: PeopleRepository,
    private val sessionsRepository: SessionsRepository,
    private val transactionTemplate: TransactionTemplate,
    private val jdbcTemplate: JdbcTemplate,
) {

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val streams = mutableListOf<EventStream>()

    /** /events as a browser's EventSource reads it, without a token */
    private inner class EventStream : AutoCloseable {
        private val received = LinkedBlockingQueue<Received>()
        private val response = client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/events")).header("Accept", "text/event-stream").build(),
            BodyHandlers.ofLines(),
        )
        val status = response.statusCode()
        val contentType: String = response.headers().firstValue("Content-Type").orElse("")

        init {
            Thread.ofVirtual().start {
                var type = "message"
                var data: String? = null
                try {
                    response.body().forEach { line ->
                        when {
                            line.isEmpty() -> {
                                data?.let { received.put(Received(type, it)) }
                                type = "message"
                                data = null
                            }
                            line.startsWith(":") -> received.put(HEARTBEAT)
                            line.startsWith("retry:") -> received.put(Received("retry", line.substringAfter(':')))
                            line.startsWith("event:") -> type = line.substringAfter(':').trim()
                            line.startsWith("data:") -> data = line.substringAfter(':').removePrefix(" ")
                        }
                    }
                } catch (_: UncheckedIOException) {
                    // Closed by the test
                } finally {
                    received.put(END)
                }
            }
        }

        /** The next that [matches], by default the next event, or the end of the stream */
        fun next(
            timeout: Duration = Duration.ofSeconds(10),
            matches: (Received) -> Boolean = { it.type in setOf("change", "resync") },
        ): Received {
            val until = Instant.now() + timeout
            while (true) {
                val left = Duration.between(Instant.now(), until).toMillis().coerceAtLeast(0)
                val next = received.poll(left, TimeUnit.MILLISECONDS) ?: fail("Nothing came in $timeout")
                if (matches(next) || next == END) return next
            }
        }

        override fun close() = response.body().close()
    }

    private fun stream() = EventStream().also { streams += it }

    @BeforeEach
    fun awaitListening() {
        val until = Instant.now() + Duration.ofSeconds(30)
        while (!changes.listening) {
            if (Instant.now() > until) fail("Not listening for changes")
            Thread.sleep(50)
        }
    }

    @AfterEach
    fun closeStreams() = streams.forEach { it.close() }

    private fun episode() = Episode(title = "Changing", code = "ep-${codes.getAndIncrement()}")

    @Test
    fun `tells anyone, without a token, which table changed`() {
        val stream = stream()
        assertEquals(200, stream.status)
        assertTrue(stream.contentType.startsWith("text/event-stream"), stream.contentType)

        episodesRepository.saveAndFlush(episode())

        assertEquals(Received("change", "episodes"), stream.next())
    }

    @Test
    fun `starts a stream right away, with how soon to connect again, as EventSource is open only once it has started`() {
        val stream = stream()

        assertEquals(Received("retry", "1000"), stream.next { true })
    }

    @Test
    fun `says a table once a transaction, and nothing that was rolled back`() {
        val stream = stream()

        transactionTemplate.executeWithoutResult {
            episodesRepository.saveAndFlush(episode())
            episodesRepository.saveAndFlush(episode())
            peopleRepository.saveAndFlush(Person(name = "Ana Changes", email = null))
        }
        transactionTemplate.executeWithoutResult { status ->
            episodesRepository.saveAndFlush(episode())
            status.setRollbackOnly()
        }
        // After the rollback, so what it said would have come before
        sessionsRepository.saveAndFlush(Session(LocalDate.of(2026, 10, 21), location = null, address = null))

        assertEquals(
            listOf(Received("change", "episodes"), Received("change", "people"), Received("change", "sessions")),
            List(3) { stream.next() },
        )
    }

    @Test
    fun `tells every browser`() {
        val first = stream()
        val second = stream()

        peopleRepository.saveAndFlush(Person(name = "Bojan Changes", email = null))

        assertEquals(Received("change", "people"), first.next())
        assertEquals(Received("change", "people"), second.next())
    }

    @Test
    fun `keeps a quiet stream open with heartbeats`() {
        val stream = stream()

        assertEquals(HEARTBEAT, stream.next(Duration.ofSeconds(3)) { it == HEARTBEAT })
    }

    @Test
    fun `tells browsers to load everything again once it listens again after its connection broke`() {
        val stream = stream()

        jdbcTemplate.queryForList(
            // This context's listener only: the tests' Postgres has other contexts' databases (TestcontainersConfig)
            "select pg_terminate_backend(pid) from pg_stat_activity where application_name = ? and datname = current_database()",
            CHANGES_APPLICATION_NAME,
        )

        assertEquals(Received("resync", "*"), stream.next(Duration.ofSeconds(15)) { it.type == "resync" })
        awaitListening()
        episodesRepository.saveAndFlush(episode())
        assertEquals(Received("change", "episodes"), stream.next())
    }

    @Test
    fun `ends the streams when it stops, so browsers connect to another pod, and streams again once started`() {
        val stream = stream()

        changes.stop()
        try {
            assertEquals(END, stream.next(Duration.ofSeconds(5)) { it == END })
            // One that connects meanwhile is ended right away, to connect again
            assertEquals(END, stream().next(Duration.ofSeconds(5)) { it == END })
        } finally {
            changes.start()
        }

        awaitListening()
        val again = stream()
        episodesRepository.saveAndFlush(episode())
        assertEquals(Received("change", "episodes"), again.next())
    }
}
