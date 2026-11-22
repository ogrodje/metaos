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

    @Test fun `five per post and an explicit notice for an empty week`() {
        val messages = digestMessages(monday, List(6) { event("2026-03-31T12:00:00Z") })
        assertEquals(listOf(5, 1), messages.map { it.embeds.size })
        assertEquals("Paket 2.", messages.last().content)
        assertEquals("Za obdobje od **2026-03-30** do **2026-04-05** nismo našli nobenega dogodka.",
            digestMessages(monday, emptyList()).single().content)
    }
}
