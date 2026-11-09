package si.ogrodje.metaos

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MetaOS's client of Goo's timeline, against a Goo that answers as the real one does */
class GooTest {

    private var asked = 0
    private var status = 200
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/timeline") { exchange ->
            asked++
            val bytes = (if (status == 200) TIMELINE else """{"error":"down"}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val goo = Goo("http://127.0.0.1:${server.address.port}/", JsonMapper.builder().build())
    private val now = Instant.parse("2026-11-10T12:00:00Z")

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `lists what hasn't ended, or started when it has no end, the soonest first`() {
        assertEquals(listOf("multi-day", "no-end", "promoted", "no-meetup"), goo.upcoming(now).map { it.id })
    }

    @Test
    fun `an event has its meetup, with its colours, and is promoted when Goo says`() {
        val events = goo.upcoming(now).associateBy { it.id }
        val promoted = events.getValue("promoted")
        assertTrue(promoted.promoted)
        assertEquals(Meetup("Kovačnica", "https://eu-central-1-shared-euc1-02.graphassets.com/a/resize=fit:clip,height:100/logo", "#ffffff", "#000000"), promoted.meetup)
        assertEquals("4 Poštna ulica, 4000 Kranj", promoted.locationAddress)
        assertFalse(events.getValue("multi-day").hasStartTime)
        assertNull(events.getValue("no-end").end)
        // Without a meetup of its own, its name is Goo's meetupName, without colours
        assertEquals(Meetup("Tech Leads", null, null, null), events.getValue("no-meetup").meetup)
        assertNull(events.getValue("no-meetup").url)
    }

    @Test
    fun `keeps the timeline a minute, and what it had while Goo is down`() {
        goo.upcoming(now)
        goo.upcoming(now.plusSeconds(30))
        assertEquals(1, asked)
        status = 503
        assertEquals(4, goo.upcoming(now.plusSeconds(120)).size)
        assertEquals(2, asked)
    }

    @Test
    fun `says so when Goo is down and it has nothing yet`() {
        status = 503
        assertThrows<GooException> { goo.upcoming(now) }
    }

    companion object {
        private fun event(id: String, start: String, end: String?, extra: String = "") =
            """{"id":"$id","meetupID":"m","source":"ICal","title":"Event $id","startDateTime":"$start","eventURL":"https://example.com/$id",""" +
                """"endDateTime":${end?.let { "\"$it\"" } ?: "null"},"hasStartTime":true,"hasEndTime":true,"locationName":null,""" +
                """"locationAddress":null,"hiddenAt":null,"promotedAt":null,"rank":-1.0,"meetupName":"Kovačnica","meetup":null$extra}"""

        private val TIMELINE = "[" + listOf(
            event("ended", "2026-11-10T09:00:00Z", "2026-11-10T10:00:00Z"),
            event("started-no-end", "2026-11-10T08:00:00Z", null),
            event("hidden", "2026-11-12T08:00:00Z", null).replace(""""hiddenAt":null""", """"hiddenAt":"2026-11-01T00:00:00Z""""),
            event("no-meetup", "2026-11-20T17:00:00Z", "2026-11-20T19:00:00Z")
                .replace(""""meetupName":"Kovačnica"""", """"meetupName":"Tech Leads"""")
                .replace(""""eventURL":"https://example.com/no-meetup"""", """"eventURL":"javascript:alert(1)""""),
            event("promoted", "2026-11-17T16:00:00Z", "2026-11-17T16:00:00Z")
                .replace(""""promotedAt":null""", """"promotedAt":"2026-11-09T09:00:00Z"""")
                .replace(""""locationAddress":null""", """"locationAddress":"4 Poštna ulica, 4000 Kranj"""")
                .replace(""""meetup":null""", """"meetup":{"id":"m","name":"Kovačnica","logoImage":"https://eu-central-1-shared-euc1-02.graphassets.com/a/resize%3Dfit:clip%2Cheight:100/logo","mainColor":"#ffffff","backgroundColor":"#000000"}"""),
            event("no-end", "2026-11-12T10:00:00Z", null),
            event("multi-day", "2026-11-09T23:00:00Z", "2026-11-11T23:00:00Z")
                .replace(""""hasStartTime":true,"hasEndTime":true""", """"hasStartTime":false,"hasEndTime":false"""),
        ).joinToString(",") + "]"
    }
}
