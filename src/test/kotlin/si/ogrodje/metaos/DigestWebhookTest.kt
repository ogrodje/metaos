package si.ogrodje.metaos

import com.sun.net.httpserver.HttpServer
import io.temporal.failure.ApplicationFailure
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DigestWebhookTest {
    private fun send(status: Int, body: String, retryAfter: String? = null): Long {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/") { exchange ->
                exchange.use {
                    it.requestBody.readAllBytes()
                    if (retryAfter != null) it.responseHeaders.add("Retry-After", retryAfter)
                    val bytes = body.toByteArray()
                    it.sendResponseHeaders(status, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            server.start()
            return DigestWebhook(JsonMapper.builder().build()).send("http://127.0.0.1:${server.address.port}/", DigestMessage("sample", emptyList()))
        } finally {
            server.stop(0)
        }
    }

    @Test fun `interrupted send reports uncertain delivery`() {
        val received = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                it.requestBody.readAllBytes()
                received.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        server.start()
        val result = java.util.concurrent.FutureTask {
            assertFailsWith<ApplicationFailure> {
                DigestWebhook(JsonMapper.builder().build()).send("http://127.0.0.1:${server.address.port}/", DigestMessage("sample", emptyList()))
            }.type
        }
        val thread = Thread(result)
        try {
            thread.start()
            kotlin.test.assertTrue(received.await(5, java.util.concurrent.TimeUnit.SECONDS))
            thread.interrupt()
            assertEquals("Unconfirmed", result.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test fun `rate limit prefers the body then header then default`() {
        assertEquals(1500L, send(429, """{"retry_after":1.5}""", "5"))
        assertEquals(5000L, send(429, "<html>rate limited</html>", "5"))
        assertEquals(3000L, send(429, ""))
    }

    @Test fun `content rejection is distinct from a broken webhook and uncertain delivery`() {
        assertEquals("InvalidBatch", assertFailsWith<ApplicationFailure> { send(400, "{}") }.type)
        assertEquals("Rejected", assertFailsWith<ApplicationFailure> { send(403, "{}") }.type)
        assertEquals("Unconfirmed", assertFailsWith<ApplicationFailure> { send(503, "{}") }.type)
    }
}
