package si.ogrodje.scrapers

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.temporal.common.converter.JacksonJsonPayloadConverter
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the activity answers, as MetaOS reads it: its converter is Temporal's default with Kotlin's module (Workflows.kt) */
class ActivitiesTest {

    private val converter = WorkflowsConfig().temporalDataConverter()
    private val metaos = JacksonJsonPayloadConverter.newDefaultObjectMapper().registerModule(KotlinModule.Builder().build())

    @Test
    fun `answers with instants and names MetaOS reads`() {
        val start = Instant.parse("2026-11-20T17:00:00Z")
        val result = ScrapeResult(Source.MEETUP, listOf(ScrapedEvent(Source.MEETUP, "meetup-1", "https://x.test", "Title", start)))
        val json = converter.toPayload(result).get().data.toStringUtf8()
        assertTrue(json.contains("\"start\":\"2026-11-20T17:00:00Z\""), json)
        assertTrue(json.contains("\"source\":\"MEETUP\""), json)
        // MetaOS's own types, of the same shape
        assertEquals(start, metaos.readTree(json)["events"][0]["start"].asText().let(Instant::parse))
        val request = """{"organisationId":"o","organisationName":"O","field":"ICAL","url":"https://x.test","since":"2025-11-20T17:00:00Z"}"""
        val parsed = converter.fromPayload(
            io.temporal.api.common.v1.Payload.newBuilder()
                .putMetadata("encoding", com.google.protobuf.ByteString.copyFromUtf8("json/plain"))
                .setData(com.google.protobuf.ByteString.copyFromUtf8(request)).build(),
            ScrapeRequest::class.java, ScrapeRequest::class.java,
        )
        assertEquals(ScrapeRequest("o", "O", SourceField.ICAL, "https://x.test", Instant.parse("2025-11-20T17:00:00Z")), parsed)
    }
}
