package si.ogrodje.metaos

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Goo's meetups from a Hygraph, and its timeline from a Goo, that answer as the real ones do */
@SpringBootTest
@Import(TestcontainersConfig::class)
class GooImportTest @Autowired constructor(
    private val goo: GooImport,
    private val organisationsMCP: OrganisationsMCP,
    private val eventsMCP: EventsMCP,
) {

    companion object {
        private val soon = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        private val past = Instant.now().minus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

        private val MEETUPS = """{"data":{"meetups":[
          {"id":"goo-kotlin","name":"Kotlin Import Test","stage":"PUBLISHED","hidden":false,"homePageUrl":null,
           "meetupUrl":"https://www.meetup.com/kotlin-import/","eventbriteUrl":null,"discordUrl":null,"linkedInUrl":null,
           "facebookUrl":null,"instagramUrl":null,"kompotUrl":null,"icalUrl":null,"lumaUrl":null,
           "mainColor":{"hex":"#7F52FFFF"},"backgroundColor":{"hex":"#ffffff"},"logoImage":null},
          {"id":"goo-muzej","name":"Računalniški muzej (Goo)","stage":"PUBLISHED","hidden":false,
           "homePageUrl":"https://www.racunalniski-muzej.si","meetupUrl":null,"eventbriteUrl":null,"discordUrl":null,
           "linkedInUrl":null,"facebookUrl":null,"instagramUrl":null,"kompotUrl":null,"icalUrl":null,"lumaUrl":null,
           "mainColor":null,"backgroundColor":null,"logoImage":null},
          {"id":"goo-hidden","name":"Hidden Import Test","stage":"PUBLISHED","hidden":true,"homePageUrl":"https://hidden.test",
           "meetupUrl":null,"eventbriteUrl":null,"discordUrl":null,"linkedInUrl":"http://not-https.test","facebookUrl":null,
           "instagramUrl":null,"kompotUrl":null,"icalUrl":"webcal://hidden.test/cal","lumaUrl":null,"mainColor":null,
           "backgroundColor":null,"logoImage":null}
        ]}}"""

        private val TIMELINE = """[
          {"id":"meetup-301","meetupID":"goo-kotlin","source":"Meetup","sourceURL":"https://www.meetup.com/kotlin-import/events",
           "title":"Kotlin Coroutines","startDateTime":"$soon","endDateTime":null,"hasStartTime":true,"hasEndTime":false,
           "eventURL":"https://www.meetup.com/kotlin-import/events/301","locationName":"Kovačnica","locationAddress":null,
           "description":null,"hiddenAt":null,"promotedAt":"2026-11-01T10:00:00Z"},
          {"id":"0b6f8a6e-manual","meetupID":"goo-muzej","source":"Manual","sourceURL":"https://ogrodje.si",
           "title":"Retro night","startDateTime":"$soon","endDateTime":"$soon","hasStartTime":true,"hasEndTime":true,
           "eventURL":null,"locationName":null,"locationAddress":null,"description":"Old computers","hiddenAt":null,"promotedAt":null},
          {"id":"meetup-300","meetupID":"goo-kotlin","source":"Meetup","sourceURL":"https://www.meetup.com/kotlin-import/events",
           "title":"Already over","startDateTime":"$past","endDateTime":null,"hasStartTime":true,"hasEndTime":false,
           "eventURL":null,"locationName":null,"locationAddress":null,"description":null,"hiddenAt":null,"promotedAt":null}
        ]"""

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = (if (exchange.requestURI.path == "/timeline") TIMELINE else MEETUPS).toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("metaos.hygraph.endpoint") { "http://127.0.0.1:${server.address.port}/hygraph" }
            registry.add("metaos.goo.url") { "http://127.0.0.1:${server.address.port}/" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }

    @Test
    fun `makes organisations of Goo's meetups, merges those MetaOS has, and adds the upcoming events, once`() {
        val first = goo.import()
        assertEquals(GooImportSummary(created = 2, updated = 1, events = 2, logos = 0), first)

        val organisations = organisationsMCP.list().associateBy { it.name }
        val kotlin = organisations.getValue("Kotlin Import Test")
        assertEquals("https://www.meetup.com/kotlin-import/", kotlin.meetupUrl)
        assertEquals("#7f52ff", kotlin.mainColor)
        assertFalse(kotlin.archived)

        // V35's Računalniški muzej, by its website, keeps its name and gets nothing it had
        val muzej = organisations.getValue("Računalniški muzej")
        assertEquals("https://www.racunalniski-muzej.si/", muzej.websiteUrl)
        assertTrue("Računalniški muzej (Goo)" !in organisations)
        // ... and gathers its events now, as Goo did, as do Goo's own; V35's others don't (V37)
        assertTrue(muzej.collectsEvents)
        assertTrue(kotlin.collectsEvents)
        assertFalse(organisations.getValue("Koofr").collectsEvents)

        val hidden = organisations.getValue("Hidden Import Test")
        assertTrue(hidden.archived)
        assertNull(hidden.linkedinUrl)
        assertEquals("webcal://hidden.test/cal", hidden.icalUrl)

        val kotlinEvents = eventsMCP.list(organisationId = kotlin.id)
        assertEquals(listOf("Kotlin Coroutines"), kotlinEvents.map { it.title })
        assertTrue(kotlinEvents.single().promoted)
        assertEquals("MEETUP", kotlinEvents.single().source)
        assertEquals(MANUAL_SOURCE, eventsMCP.list(organisationId = muzej.id).single().source)

        // Again: nothing new
        assertEquals(GooImportSummary(created = 0, updated = 0, events = 0, logos = 0), goo.import())
    }
}
