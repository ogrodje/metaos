package si.ogrodje.metaos

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MetaOS's client of patreon-gateway, against a gateway that answers as the real one does */
class PatreonTest {

    private val asked = mutableListOf<String>()
    private val jobPolls = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            asked += "${exchange.requestMethod} $path ${exchange.requestHeaders.getFirst("Authorization")}"
            val (status, body) = when {
                exchange.requestHeaders.getFirst("Authorization") != "Bearer secret" -> 401 to """{"error":"A bearer token is needed"}"""
                path == "/tiers" -> 200 to """[{"id":"9650087","title":"Developer","amountCents":300,"published":true,"patronCount":18}]"""
                path.startsWith("/posts?") -> 200 to """{"items":[{"id":"171593625","title":"Domen Vreš","url":"https://www.patreon.com/ogrodje/posts/x-171593625","publishedAt":"2026-10-13T06:35:26Z","public":true,"paid":false,"tiers":[],"content":"<p>Hi</p>","embedUrl":null}],"total":1}"""
                path == "/posts/404" -> 404 to """{"error":"No published post 404"}"""
                path.startsWith("/members?") -> 200 to """{"items":[{"id":"m-1","name":"Ana","email":"ana@example.com","status":"active_patron","tiers":["Engineer"],"pledgeCents":1000,"lifetimeSupportCents":12000,"since":"2024-10-11T09:30:23.301Z","lastChargeDate":"2026-10-11T00:35:23Z","lastChargeStatus":"Paid","freeTrial":false,"gifted":false}],"total":30}"""
                path == "/posts" -> 202 to job("QUEUED", null)
                path.startsWith("/jobs/") -> 200 to if (jobPolls.incrementAndGet() < 2) job("RUNNING", null) else
                    job("SUCCEEDED", """{"id":"173396086","title":"S05E08","url":"https://www.patreon.com/ogrodje/posts/s05e08-173396086","editUrl":"https://www.patreon.com/posts/173396086/edit","publishedAt":null,"scheduledFor":null,"draft":true}""")
                path == "/posts/1/publish" -> 400 to """{"error":"Post 1 is already published (2026-10-13T06:35:26Z)"}"""
                path == "/health" -> 503 to """{"api":true,"apiError":null,"signedIn":false,"signedInAs":null,"browserError":"The bot isn't signed in to Patreon: run patreon-gateway/login.sh","ok":false}"""
                else -> 404 to """{"error":"No $path"}"""
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun job(status: String, result: String?) =
        """{"id":"job-1","kind":"create-draft","status":"$status","result":$result,"error":null,"attempts":1,"retryAt":null}"""

    private val patreon = Patreon("http://127.0.0.1:${server.address.port}", "secret", JsonMapper.builder().build())

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `reads tiers and posts with the gateway's token`() {
        assertEquals("Developer", patreon.tiers().single().title)
        val post = patreon.posts("GaMS vreš", 5).single()
        assertEquals("171593625", post.id)
        assertTrue(post.public)
        assertTrue(asked.any { it.startsWith("GET /posts?limit=5&query=GaMS+vre%C5%A1 Bearer secret") }, asked.toString())
    }

    @Test
    fun `members come filtered as asked, their status as MetaOS names it`() {
        val page = patreon.members("ana", PatreonMemberStatus.ACTIVE_PATRON, "Engineer", 10, 0)
        assertEquals(30, page.total)
        assertEquals("ACTIVE_PATRON", page.members.single().status)
        assertEquals(listOf("Engineer"), page.members.single().tiers)
        assertTrue(asked.any { it.startsWith("GET /members?limit=10&offset=0&query=ana&status=active_patron&tier=Engineer ") }, asked.toString())
    }

    @Test
    fun `a post that isn't published is null`() {
        assertNull(patreon.post("404"))
    }

    @Test
    fun `a draft's job is waited for until it's done`() {
        val job = patreon.await(patreon.createDraft("S05E08", "Text", PatreonAccess.TIERS, listOf("9650087")), Duration.ofSeconds(10))
        assertEquals("SUCCEEDED", job.status)
        assertEquals("173396086", job.result!!["id"].asString())
        assertTrue(asked.first().startsWith("POST /posts"), asked.toString())
    }

    @Test
    fun `the gateway's refusal comes back with its reason`() {
        val e = assertThrows<PatreonGatewayException> { patreon.publish("1", null, false) }
        assertEquals("400: Post 1 is already published (2026-10-13T06:35:26Z)", e.message)
    }

    @Test
    fun `health says why the gateway can't work`() {
        val e = assertThrows<PatreonGatewayException> { patreon.health() }
        assertEquals("The bot isn't signed in to Patreon: run patreon-gateway/login.sh", e.message)
    }

    @Test
    fun `a post's ID is a number`() {
        assertThrows<IllegalArgumentException> { patreon.draft("../jobs") }
        assertTrue(asked.isEmpty())
    }
}
