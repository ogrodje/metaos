package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventsDigestTest {
    private val monday = LocalDate.parse("2026-03-30")
    private fun event(day: String) = Event(Organisation("Klub"), MANUAL_SOURCE, null, day, OffsetDateTime.parse(day)).apply {
        url = "https://example.org/listing"
    }

    @Test fun `schedule uses Ljubljana time after the spring clock change`() {
        assertNull(digestWeek(Instant.parse("2026-03-29T16:59:59Z")))
        assertEquals(monday, digestWeek(Instant.parse("2026-03-29T17:00:00Z")))
        assertEquals(monday, digestWeek(Instant.parse("2026-03-30T09:59:59Z")))
        assertNull(digestWeek(Instant.parse("2026-03-30T10:00:00Z")))
    }

    @Test fun `public starts precede ongoing events including those lasting beyond the week`() {
        val ongoing = event("2026-03-28T12:00:00Z").apply { endsAt = startsAt.plusWeeks(2) }
        val start = event("2026-04-05T21:59:00Z")
        val hidden = event(start.title).apply { hiddenAt = startsAt }
        val archived = event(start.title).apply { organisation.archivedAt = startsAt }
        val next = event("2026-04-05T22:00:00Z")
        val batch = digestMessages(monday, listOf(hidden, archived, ongoing, next, start)).single()
        assertEquals(listOf(start, ongoing).map { "Klub / ${it.title}" }, batch.embeds.map { it.title })
        assertEquals(start.url, batch.embeds.first().url)
    }

    @Test fun `same day range prints the date once`() {
        val event = event("2026-03-31T16:00:00Z").apply { endsAt = startsAt.plusHours(2) }
        assertEquals("2026-03-31 18:00 – 20:00", digestMessages(monday, listOf(event)).single().embeds.single().fields.first().value)
    }

    @Test fun `compact previews keep all events and repeat the day across message breaks`() {
        val events = (1..30).map { number ->
            event("2026-03-31T16:00:00Z").apply { title = "Dogodek $number " + "x".repeat(100) }
        }
        val posts = previewMessages(monday, events)
        kotlin.test.assertTrue(posts.size > 1)
        for (post in posts) {
            kotlin.test.assertTrue(post.content.length <= 2000)
            kotlin.test.assertTrue("**torek, 31. 3.**" in post.content)
            kotlin.test.assertTrue(post.embeds.isEmpty())
        }
        assertEquals(events.map { "18:00 [${it.title}](<${it.url}>)" },
            posts.flatMap { it.content.lines() }.filter { it.startsWith("18:00") })
    }

    @Test fun `five per post and an explicit notice for an empty week`() {
        val messages = digestMessages(monday, List(6) { event("2026-03-31T12:00:00Z") })
        assertEquals(listOf(5, 1), messages.map { it.embeds.size })
        assertEquals("Dogodki za obdobje **2026-03-30** do **2026-04-05**.", messages.first().content)
        assertEquals("Paket 2.", messages.last().content)
        assertEquals("Za obdobje od **2026-03-30** do **2026-04-05** nismo našli nobenega dogodka.",
            digestMessages(monday, emptyList()).single().content)
    }
}
