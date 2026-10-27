package si.ogrodje.patreon

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.HexFormat

data class Campaign(
    val id: String,
    val name: String?,
    val url: String,
    val patronCount: Int?,
    val imageUrl: String?,
    val publishedAt: OffsetDateTime?,
)

data class Tier(
    val id: String,
    val title: String,
    val amountCents: Int,
    val published: Boolean,
    val patronCount: Int?,
)

/**
 * A published post, as Patreon's API has it: drafts aren't in it. [public] is anyone's, else [tiers] says who may see
 * it (none: every patron). [content] is HTML.
 */
data class Post(
    val id: String,
    val title: String?,
    val url: String,
    val publishedAt: OffsetDateTime?,
    val public: Boolean,
    val paid: Boolean,
    val tiers: List<String>,
    val content: String?,
    val embedUrl: String?,
)

/**
 * A member of the campaign: a patron, paying or not, or someone who was. [status] is Patreon's `active_patron`,
 * `declined_patron` (their last charge failed) or `former_patron`, and null for a free member; [tiers] are the tiers
 * they're entitled to now, by title. Amounts in cents, in the campaign's currency.
 */
data class Member(
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

/** When the creator token runs out, where known, and when it was last refreshed; never the tokens themselves. */
data class TokenInfo(val configured: Boolean, val expiresAt: Instant?, val refreshedAt: Instant?)

class PatreonException(message: String, val status: Int? = null) : RuntimeException(message)

/** Patreon's OAuth tokens for the creator, with when the access token runs out, where known. */
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant? = null,
    val seed: String? = null,
    val refreshedAt: Instant? = null,
)

/**
 * The creator's tokens, kept in `tokens.json` in the state directory: Patreon gives a new refresh token with each
 * refresh, so the one from the environment works only until the first. A different one in the environment (a new seed,
 * e.g. from the developer portal) takes over from what's kept.
 */
@Component
class TokenStore(private val properties: PatreonProperties, private val mapper: JsonMapper) {
    private val file: Path get() = properties.stateDir.resolve("tokens.json")

    @Volatile
    private var current: Tokens? = null

    fun get(): Tokens? = current ?: synchronized(this) { current ?: load().also { current = it } }

    fun save(tokens: Tokens) = synchronized(this) {
        val withSeed = tokens.copy(seed = tokens.seed ?: get()?.seed)
        Files.createDirectories(properties.stateDir)
        val temporary = Files.createTempFile(properties.stateDir, "tokens", ".json")
        runCatching { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")) }
        mapper.writeValue(temporary.toFile(), withSeed)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        current = withSeed
    }

    private fun load(): Tokens? {
        val seed = properties.refreshToken.takeIf { it.isNotBlank() }?.let { sha256(it) }
        val kept = if (Files.exists(file)) mapper.readValue(file.toFile(), Tokens::class.java) else null
        if (kept != null && (seed == null || kept.seed == seed)) return kept
        if (properties.accessToken.isBlank() || properties.refreshToken.isBlank()) return kept
        return Tokens(properties.accessToken, properties.refreshToken, seed = seed)
    }

    private fun sha256(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
}

/**
 * Patreon's API (v2) as the campaign's creator (https://docs.patreon.com/#apiv2-oauth): the campaign, its tiers and
 * its published posts. It can't make or change posts; [Browser] does.
 */
@Component
class PatreonApi(
    private val properties: PatreonProperties,
    private val tokens: TokenStore,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    val configured: Boolean get() = tokens.get() != null

    @Volatile
    private var campaignId: String? = null

    // All the posts, newest first, for a minute: Patreon lists them oldest first, a page at a time
    @Volatile
    private var posts: Pair<Instant, List<Post>>? = null

    fun campaign(): Campaign {
        val body = get("campaigns/${campaignId()}?fields%5Bcampaign%5D=creation_name,vanity,url,patron_count,image_url,published_at")
        val data = body["data"]
        val attributes = data["attributes"]
        return Campaign(
            id = data["id"].asString(),
            name = attributes.text("creation_name") ?: attributes.text("vanity"),
            url = attributes.text("url") ?: "https://www.patreon.com/",
            patronCount = attributes.int("patron_count"),
            imageUrl = attributes.text("image_url"),
            publishedAt = attributes.text("published_at")?.let(OffsetDateTime::parse),
        )
    }

    fun tiers(): List<Tier> {
        val body = get("campaigns/${campaignId()}?include=tiers&fields%5Btier%5D=title,amount_cents,published,patron_count")
        return body["included"].orEmpty().filter { it["type"].asString() == "tier" }.map {
            val attributes = it["attributes"]
            Tier(
                id = it["id"].asString(),
                title = attributes.text("title") ?: "",
                amountCents = attributes.int("amount_cents") ?: 0,
                published = attributes["published"]?.asBoolean() ?: false,
                patronCount = attributes.int("patron_count"),
            )
        }.sortedBy { it.amountCents }
    }

    /** The campaign's published posts, newest first. */
    fun posts(): List<Post> {
        posts?.let { (at, list) -> if (Duration.between(at, clock.instant()) < Duration.ofMinutes(1)) return list }
        val all = mutableListOf<Post>()
        var cursor: String? = null
        do {
            val page = get(
                "campaigns/${campaignId()}/posts?page%5Bcount%5D=100&fields%5Bpost%5D=$POST_FIELDS" +
                    (cursor?.let { "&page%5Bcursor%5D=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""),
            )
            page["data"].orEmpty().mapTo(all, ::post)
            cursor = page["meta"]?.get("pagination")?.get("cursors")?.text("next")
        } while (cursor != null)
        val list = all.sortedByDescending { it.publishedAt }
        posts = clock.instant() to list
        return list
    }

    // All the members, for five minutes: Patreon lists them a page at a time
    @Volatile
    private var members: Pair<Instant, List<Member>>? = null

    /** The campaign's members, patrons first, then by name. */
    fun members(): List<Member> {
        members?.let { (at, list) -> if (Duration.between(at, clock.instant()) < Duration.ofMinutes(5)) return list }
        val all = mutableListOf<Member>()
        var cursor: String? = null
        do {
            val page = get(
                "campaigns/${campaignId()}/members?page%5Bcount%5D=500&include=currently_entitled_tiers" +
                    "&fields%5Bmember%5D=$MEMBER_FIELDS&fields%5Btier%5D=title" +
                    (cursor?.let { "&page%5Bcursor%5D=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""),
            )
            val tiers = page["included"].orEmpty().filter { it["type"].asString() == "tier" }
                .associate { it["id"].asString() to (it["attributes"].text("title") ?: it["id"].asString()) }
            page["data"].orEmpty().mapTo(all) { data ->
                val attributes = data["attributes"]
                Member(
                    id = data["id"].asString(),
                    name = attributes.text("full_name"),
                    email = attributes.text("email"),
                    status = attributes.text("patron_status"),
                    tiers = data["relationships"]?.get("currently_entitled_tiers")?.get("data").orEmpty()
                        .map { tiers[it["id"].asString()] ?: it["id"].asString() },
                    pledgeCents = attributes.int("currently_entitled_amount_cents") ?: 0,
                    lifetimeSupportCents = attributes.int("lifetime_support_cents") ?: 0,
                    since = attributes.text("pledge_relationship_start")?.let(OffsetDateTime::parse),
                    lastChargeDate = attributes.text("last_charge_date")?.let(OffsetDateTime::parse),
                    lastChargeStatus = attributes.text("last_charge_status"),
                    freeTrial = attributes["is_free_trial"]?.asBoolean() ?: false,
                    gifted = attributes["is_gifted"]?.asBoolean() ?: false,
                )
            }
            cursor = page["meta"]?.get("pagination")?.get("cursors")?.text("next")
        } while (cursor != null)
        val list = all.sortedWith(compareBy<Member>({ STATUS_ORDER.indexOf(it.status).let { i -> if (i < 0) STATUS_ORDER.size else i } }, { it.name?.lowercase() }))
        members = clock.instant() to list
        return list
    }

    fun tokenInfo(): TokenInfo = tokens.get().let { TokenInfo(it != null, it?.expiresAt, it?.refreshedAt) }

    /** A published post, or null when Patreon doesn't have one by that ID (or it's a draft). */
    fun post(id: String): Post? {
        require(id.all(Char::isDigit)) { "A post's ID is a number" }
        return try {
            post(get("posts/$id?fields%5Bpost%5D=$POST_FIELDS")["data"])
        } catch (e: PatreonException) {
            if (e.status == 404) null else throw e
        }
    }

    /** Forgets the posts, so the next [posts] asks Patreon, e.g. after one was published. */
    fun forgetPosts() {
        posts = null
    }

    fun campaignId(): String = campaignId ?: identity().let { campaignId!! }

    /** The creator's own user, whom the bot acts as in Patreon's web app ([PatreonWeb]). */
    fun creatorUserId(): String = creatorUserId ?: identity().let { creatorUserId!! }

    @Volatile
    private var creatorUserId: String? = null

    private fun identity() {
        val data = get("identity?include=campaign&fields%5Buser%5D=vanity")["data"]
        campaignId = data.get("relationships")?.get("campaign")?.get("data")?.text("id")
            ?: throw PatreonException("The token's user has no campaign: it has to be the creator's")
        creatorUserId = data.text("id")
    }

    private fun post(data: JsonNode): Post {
        val attributes = data["attributes"]
        return Post(
            id = data["id"].asString(),
            title = attributes.text("title"),
            url = absolute(attributes.text("url") ?: "/posts/${data["id"].asString()}"),
            publishedAt = attributes.text("published_at")?.let(OffsetDateTime::parse),
            public = attributes["is_public"]?.asBoolean() ?: false,
            paid = attributes["is_paid"]?.asBoolean() ?: false,
            tiers = attributes["tiers"].orEmpty().map { it.asString() },
            content = attributes.text("content"),
            embedUrl = attributes.text("embed_url"),
        )
    }

    private fun get(path: String): JsonNode {
        val used = tokens.get()?.accessToken
        val first = send(path)
        val response = if (first.statusCode() == 401) {
            // Unless another call refreshed it meanwhile: each refresh takes the refresh token the one before gave
            synchronized(this) { if (tokens.get()?.accessToken == used) refresh() }
            send(path)
        } else first
        if (response.statusCode() !in 200..299) {
            throw PatreonException("Patreon answered ${response.statusCode()} for ${path.substringBefore('?')}", response.statusCode())
        }
        return mapper.readTree(response.body())
    }

    private fun send(path: String): HttpResponse<String> {
        val token = tokens.get() ?: throw PatreonException("Patreon's API isn't set up: no creator token")
        val request = HttpRequest.newBuilder(URI.create("$API$path"))
            .header("Authorization", "Bearer ${token.accessToken}")
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(30))
            .GET().build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** Swaps the refresh token for new tokens, and keeps them: the old refresh token may not work again. */
    fun refresh(): Unit = synchronized(this) {
        val current = tokens.get() ?: throw PatreonException("Patreon's API isn't set up: no creator token")
        val form = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to current.refreshToken,
            "client_id" to properties.clientId,
            "client_secret" to properties.clientSecret,
        ).entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI.create("https://www.patreon.com/api/oauth2/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw PatreonException("Patreon refused to refresh the creator token (${response.statusCode()}): it needs a new one", response.statusCode())
        }
        val body = mapper.readTree(response.body())
        tokens.save(
            Tokens(
                accessToken = body.text("access_token") ?: throw PatreonException("Patreon's refresh gave no access token"),
                refreshToken = body.text("refresh_token") ?: current.refreshToken,
                expiresAt = body.int("expires_in")?.let { clock.instant().plusSeconds(it.toLong()) },
                refreshedAt = clock.instant(),
            ),
        )
        log.info("Refreshed Patreon's creator token")
    }

    /**
     * Refreshes the token a week before it runs out, so it never does, and once when that isn't known (a token from
     * the developer portal), to learn it. Each refresh gives a new refresh token and may end the old one, so only one
     * gateway may use a creator token: one running elsewhere with the same one would leave the other with a dead one.
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 6 * 60 * 60 * 1000)
    fun refreshAhead() {
        val token = tokens.get() ?: return
        if (token.expiresAt == null || token.expiresAt < clock.instant().plus(Duration.ofDays(7))) {
            runCatching { refresh() }.onFailure { log.warn("Couldn't refresh Patreon's creator token: {}", it.message) }
        }
    }

    private companion object {
        const val API = "https://www.patreon.com/api/oauth2/v2/"
        const val USER_AGENT = "Ogrodje patreon-gateway (https://metaos.ogrodje.si)"
        const val POST_FIELDS = "title,url,published_at,is_public,is_paid,tiers,content,embed_url"
        const val MEMBER_FIELDS = "full_name,email,patron_status,pledge_relationship_start,lifetime_support_cents," +
            "currently_entitled_amount_cents,last_charge_date,last_charge_status,is_free_trial,is_gifted"
        val STATUS_ORDER = listOf("active_patron", "declined_patron", null, "former_patron")
    }
}

fun absolute(url: String) = if (url.startsWith("http")) url else "https://www.patreon.com$url"

private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()
private fun JsonNode.int(field: String): Int? = get(field)?.takeUnless { it.isNull }?.asInt()
private fun JsonNode?.orEmpty(): List<JsonNode> = this?.takeIf { it.isArray }?.toList() ?: emptyList()
