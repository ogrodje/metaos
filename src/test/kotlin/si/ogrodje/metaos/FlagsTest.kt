package si.ogrodje.metaos

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MetaOS's feature flags, against an API that answers as Unleash's does, and without it */
class FlagsTest {

    private val features = """
        {"version":2,"features":[
          {"name":"on","enabled":true,"strategies":[{"name":"default","parameters":{}}]},
          {"name":"off","enabled":false,"strategies":[{"name":"default","parameters":{}}]},
          {"name":"for-one","enabled":true,"strategies":[{"name":"userWithId","parameters":{"userIds":"u-1"}}]}
        ]}
    """.trimIndent()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val (status, body) = when {
                exchange.requestHeaders.getFirst("Authorization") != "default:development.secret" -> 401 to "{}"
                exchange.requestURI.path == "/api/client/features" -> 200 to features
                else -> 202 to ""
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val backup = Files.createTempFile("unleash-", ".json").also { Files.delete(it) }
    private val made = mutableListOf<UnleashFlags>()

    private fun flags(token: String = "default:development.secret", url: String = "http://127.0.0.1:${server.address.port}/api") =
        UnleashFlags(url, token, "metaos-test-${made.size}", backup.toString()).also { made += it }

    @AfterEach
    fun stop() {
        made.forEach { it.destroy() }
        server.stop(0)
        Files.deleteIfExists(backup)
    }

    @Test
    fun `reads the flags from Unleash, as they are when MetaOS starts`() {
        val flags = flags()
        assertTrue(flags.enabled("on"))
        assertFalse(flags.enabled("off", default = true))
        // Unleash's strategies are evaluated in MetaOS: for one user only, and nobody's signed in here
        assertFalse(flags.enabled("for-one"))
        // One Unleash doesn't know is its default
        assertFalse(flags.enabled("unknown"))
        assertTrue(flags.enabled("unknown", default = true))

        val health = UnleashHealthIndicator(flags, SimpleMeterRegistry()).health()
        assertEquals(Status.UP, health.status)
        assertEquals(3, health.details["flags"])
    }

    @Test
    fun `without a token, or while Unleash is down, each flag is its default`() {
        val off = flags(token = "")
        assertFalse(off.enabled("on"))
        assertTrue(off.enabled("on", default = true))

        server.stop(0)
        val down = flags()
        assertFalse(down.enabled("on"))
        assertTrue(down.enabled("off", default = true))
        val registry = SimpleMeterRegistry()
        val health = UnleashHealthIndicator(down, registry).health()
        assertEquals(Status.DOWN, health.status)
        assertEquals(0.0, registry.get("metaos.unleash.connected").gauge().value())
    }

    @Test
    fun `a token Unleash refuses leaves the defaults, and says so`() {
        val flags = flags(token = "default:development.wrong")
        assertFalse(flags.enabled("on"))
        assertEquals(Status.DOWN, UnleashHealthIndicator(flags, SimpleMeterRegistry()).health().status)
    }

    @Test
    fun `while Unleash is down, the flags are as it last said, from the SDK's file`() {
        assertTrue(flags().enabled("on"))
        server.stop(0)
        assertTrue(flags().enabled("on"))
    }
}
