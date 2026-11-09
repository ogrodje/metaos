package si.ogrodje.metaos

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Who an event is by, as Goo has it: its name, its logo, and its colours, the text's ([mainColor]) and the badge's. */
data class Meetup(val name: String, val logo: String?, val mainColor: String?, val backgroundColor: String?)

/**
 * A tech, developer or startup event, as Goo gathers them from public sources, with when it is: [start] and [end]
 * (none, or the same, for one without an end), each with a time of day only where [hasStartTime] and [hasEndTime] say.
 * [promoted] ones are Ogrodje's picks.
 */
data class GooEvent(
    val id: String,
    val title: String,
    val url: String?,
    val start: Instant,
    val end: Instant?,
    val hasStartTime: Boolean,
    val hasEndTime: Boolean,
    val locationName: String?,
    val locationAddress: String?,
    val promoted: Boolean,
    val meetup: Meetup?,
) {
    /** It's listed until it ends, or until it starts when it has no end. */
    fun upcoming(now: Instant) = (end ?: start) >= now
}

class GooException(message: String) : RuntimeException(message)

/**
 * Goo (https://goo.ogrodje.si, github.com/ogrodje/goo), which gathers the events of Slovenia's tech, developer and
 * startup scene, as ogrodje.si's Dogodki page listed them: its public timeline, which needs no sign-in, at
 * `metaos.goo.url`. Goo refreshes it every 12 hours, so each pod keeps it for [KEEP], and keeps what it had while Goo
 * doesn't answer.
 */
@Component
class Goo(@Value("\${metaos.goo.url}") private val url: String, private val mapper: JsonMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private data class Kept(val events: List<GooEvent>, val at: Instant)

    @Volatile
    private var kept: Kept? = null

    /** The events that haven't ended yet, the soonest first. */
    fun upcoming(now: Instant = Instant.now()): List<GooEvent> = timeline(now).filter { it.upcoming(now) }.sortedBy { it.start }

    private fun timeline(now: Instant): List<GooEvent> {
        kept?.takeIf { Duration.between(it.at, now) < KEEP }?.let { return it.events }
        return try {
            fetch().also { kept = Kept(it, now) }
        } catch (e: GooException) {
            val last = kept ?: throw e
            log.warn("Goo didn't answer, so the events are those from {}: {}", last.at, e.message)
            last.events
        }
    }

    private fun fetch(): List<GooEvent> {
        val request = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + "/timeline"))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw GooException("Goo can't be reached: ${e.message}")
        }
        if (response.statusCode() !in 200..299) throw GooException("Goo answered ${response.statusCode()}: ${response.body().take(200)}")
        val timeline = runCatching { mapper.readTree(response.body()) }.getOrNull()
        if (timeline == null || !timeline.isArray) throw GooException("Goo's timeline isn't a list of events")
        return timeline.mapNotNull(::event)
    }

    // One event, or none for one Goo hides or that has no title or start
    private fun event(node: JsonNode): GooEvent? {
        fun text(of: JsonNode?, name: String) = of?.get(name)?.takeUnless { it.isNull }?.asString()?.trim()?.ifEmpty { null }
        if (text(node, "hiddenAt") != null) return null
        val title = text(node, "title") ?: return null
        val start = text(node, "startDateTime")?.let(Instant::parse) ?: return null
        val meetup = node["meetup"]?.takeUnless { it.isNull }
        val meetupName = text(meetup, "name") ?: text(node, "meetupName")
        return GooEvent(
            id = text(node, "id") ?: return null,
            title = title,
            url = text(node, "eventURL")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            start = start,
            end = text(node, "endDateTime")?.let(Instant::parse),
            hasStartTime = node["hasStartTime"]?.asBoolean() ?: true,
            hasEndTime = node["hasEndTime"]?.asBoolean() ?: true,
            locationName = text(node, "locationName"),
            locationAddress = text(node, "locationAddress"),
            promoted = text(node, "promotedAt") != null,
            meetup = meetupName?.let {
                Meetup(it, text(meetup, "logoImage")?.let(::logoUrl), text(meetup, "mainColor"), text(meetup, "backgroundColor"))
            },
        )
    }

    companion object {
        val KEEP: Duration = Duration.ofMinutes(1)

        // A meetup's logo, on Hygraph's assets, whose URLs Goo has with their transformations' = and , encoded, which
        // Hygraph answers with a 400
        fun logoUrl(url: String): String? =
            url.takeIf { it.startsWith("https://") }?.replace("%3D", "=", ignoreCase = true)?.replace("%2C", ",", ignoreCase = true)
    }
}

// The events are public, as they were on ogrodje.si
@Controller
class EventsGraphQL(private val goo: Goo) {

    @QueryMapping
    fun events(): List<GooEvent> = goo.upcoming()
}
