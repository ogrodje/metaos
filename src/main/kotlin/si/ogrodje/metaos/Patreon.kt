package si.ogrodje.metaos

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.patreon.gateway-token:}' != ''")
annotation class ConditionalOnPatreon

data class PatreonTier(val id: String, val title: String, val amountCents: Int, val published: Boolean, val patronCount: Int?)

/** A published post, as Patreon's API has it. [tiers] are who may see it when it isn't [public]: none, every patron. */
data class PatreonPost(
    val id: String,
    val title: String?,
    val url: String,
    val publishedAt: OffsetDateTime?,
    val public: Boolean,
    val tiers: List<String>,
    val content: String?,
)

/**
 * A member of Ogrodje's Patreon: [status] ACTIVE_PATRON, DECLINED_PATRON (their last charge failed), FORMER_PATRON,
 * or null for a free member; the [tiers] they're entitled to; amounts in cents, in euros.
 */
data class PatreonMember(
    val id: String,
    val name: String?,
    val email: String?,
    val status: String?,
    val tiers: List<String>,
    val pledgeCents: Int,
    val lifetimeSupportCents: Int,
    val since: OffsetDateTime?,
    val lastChargeDate: OffsetDateTime?,
    val lastChargeStatus: String?,
    val freeTrial: Boolean,
    val gifted: Boolean,
)

enum class PatreonMemberStatus(val gateway: String) { ACTIVE_PATRON("active_patron"), DECLINED_PATRON("declined_patron"), FORMER_PATRON("former_patron"), FREE("free") }

data class PatreonMembers(val members: List<PatreonMember>, val total: Int)

/** A post as Patreon's web app has it, drafts too. */
data class PatreonDraft(
    val id: String,
    val title: String?,
    val url: String?,
    val editUrl: String,
    val publishedAt: OffsetDateTime?,
    val scheduledFor: OffsetDateTime?,
    val draft: Boolean,
)

/** Something the gateway does in Patreon's web app; [result] is what it made once it [status] SUCCEEDED. */
data class PatreonJob(
    val id: String,
    val kind: String,
    val status: String,
    val result: JsonNode?,
    val error: String?,
    val attempts: Int,
    val retryAt: OffsetDateTime?,
) {
    val done: Boolean get() = status == "SUCCEEDED" || status == "FAILED"
}

enum class PatreonAccess { PUBLIC, PATRONS, TIERS }

enum class PatreonMediaKind { AUDIO, VIDEO }

class PatreonGatewayException(message: String) : RuntimeException(message)

/**
 * Ogrodje's Patreon, through patreon-gateway (patreon-gateway/README.md), a service of its own that MetaOS alone may
 * call, with its token: it reads through Patreon's API and makes and changes posts in Patreon's web app, signed in as the
 * bot, which takes from seconds to minutes, so those are jobs. `metaos.patreon.gateway-url` and `-token` turn it on.
 */
@Component
@ConditionalOnPatreon
class Patreon(
    @Value("\${metaos.patreon.gateway-url}") private val url: String,
    @Value("\${metaos.patreon.gateway-token}") private val token: String,
    private val mapper: JsonMapper,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun tiers(): List<PatreonTier> = read("/tiers")

    /** Published posts, newest first; [query] finds words in their titles. */
    fun posts(query: String?, limit: Int): List<PatreonPost> =
        call("GET", "/posts?limit=$limit" + (query?.let { "&query=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""), null)
            .let { mapper.treeToValue(it["items"], mapper.typeFactory.constructCollectionType(List::class.java, PatreonPost::class.java)) }

    /** Members, patrons first; [query] finds words in their names and emails. */
    fun members(query: String?, status: PatreonMemberStatus?, tier: String?, limit: Int, offset: Int): PatreonMembers {
        fun param(name: String, value: String?) = value?.let { "&$name=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""
        val page = call("GET", "/members?limit=$limit&offset=$offset" + param("query", query) + param("status", status?.gateway) + param("tier", tier), null)
        val members: List<PatreonMember> = mapper.treeToValue(page["items"], mapper.typeFactory.constructCollectionType(List::class.java, PatreonMember::class.java))
        return PatreonMembers(members.map { it.copy(status = it.status?.uppercase()) }, page["total"].asInt())
    }

    /** A published post through Patreon's API, or null. */
    fun post(id: String): PatreonPost? = try {
        read("/posts/${postId(id)}")
    } catch (e: PatreonGatewayException) {
        if (e.message?.startsWith("404") == true) null else throw e
    }

    /** A post, drafts too, through Patreon's web app. */
    fun draft(id: String): PatreonDraft = read("/posts/${postId(id)}/web")

    fun createDraft(title: String, text: String?, access: PatreonAccess, tiers: List<String>): PatreonJob =
        job("POST", "/posts", spec(title, text, access, tiers))

    fun update(id: String, title: String?, text: String?, access: PatreonAccess?, tiers: List<String>): PatreonJob =
        job("PATCH", "/posts/${postId(id)}", spec(title, text, access, tiers))

    fun attachMedia(id: String, fileUrl: String, kind: PatreonMediaKind, fileName: String?): PatreonJob =
        job("POST", "/posts/${postId(id)}/media", mapOf("url" to fileUrl, "kind" to kind.name, "fileName" to fileName))

    fun publish(id: String, at: OffsetDateTime?, notify: Boolean): PatreonJob =
        job("POST", "/posts/${postId(id)}/publish", mapOf("at" to at?.toString(), "notify" to notify))

    fun delete(id: String): PatreonJob = job("DELETE", "/posts/${postId(id)}", null)

    fun job(id: String): PatreonJob = read("/jobs/${URLEncoder.encode(id, Charsets.UTF_8)}")

    /** Waits up to [timeout] for [job] to finish, and gives it back as it is then. */
    fun await(job: PatreonJob, timeout: Duration): PatreonJob {
        val deadline = System.nanoTime() + timeout.toNanos()
        var current = job
        while (!current.done && System.nanoTime() < deadline) {
            Thread.sleep(1000)
            current = job(job.id)
        }
        return current
    }

    /** Whom the gateway's browser is signed in as; throws when it, or Patreon's API, doesn't work. */
    fun health(): String {
        val health = call("GET", "/health", null, okOnly = false)
        if (health["ok"]?.asBoolean() != true) {
            // Its own errors, or why it answered as it did (e.g. another token than its own)
            val why = listOfNotNull(health["apiError"], health["browserError"], health["error"]).filterNot { it.isNull }
                .joinToString("; ") { it.asString() }
            throw PatreonGatewayException(why.ifEmpty { "patreon-gateway didn't say it works" })
        }
        return health["signedInAs"].asString()
    }

    private fun spec(title: String?, text: String?, access: PatreonAccess?, tiers: List<String>): Map<String, Any?> {
        require(access != PatreonAccess.TIERS || tiers.isNotEmpty()) { "TIERS needs the tiers' IDs (list_patreon_tiers)" }
        return mapOf(
            "title" to title,
            "text" to text,
            "access" to access?.let { mapOf("kind" to it.name, "tiers" to tiers) },
        )
    }

    private inline fun <reified T> read(path: String): T = mapper.treeToValue(call("GET", path, null), mapper.typeFactory.constructType(object : tools.jackson.core.type.TypeReference<T>() {}))

    private fun job(method: String, path: String, body: Any?): PatreonJob = mapper.treeToValue(call(method, path, body), PatreonJob::class.java)

    private fun call(method: String, path: String, body: Any?, okOnly: Boolean = true): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + path))
            .header("Authorization", "Bearer $token")
            .timeout(Duration.ofMinutes(2))
            .apply {
                if (body == null) method(method, HttpRequest.BodyPublishers.noBody())
                else header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            }
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw PatreonGatewayException("patreon-gateway can't be reached: ${e.message}")
        }
        val tree = runCatching { mapper.readTree(response.body()) }.getOrNull() ?: mapper.createObjectNode()
        if (okOnly && response.statusCode() !in 200..299) {
            throw PatreonGatewayException("${response.statusCode()}: ${tree["error"]?.asString() ?: response.body().take(200)}")
        }
        return tree
    }

    private fun postId(id: String) = id.trim().also { require(it.isNotEmpty() && it.all(Char::isDigit)) { "A Patreon post's ID is a number, e.g. 171593625" } }
}

@Component
@ConditionalOnPatreon
class PatreonHealthIndicator(private val patreon: Patreon, registry: MeterRegistry) : ConnectionHealthIndicator("patreon", registry) {
    override fun probe() = "signedInAs" to patreon.health()
}
