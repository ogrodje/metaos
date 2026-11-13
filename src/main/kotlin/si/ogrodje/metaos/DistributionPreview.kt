package si.ogrodje.metaos

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What a page says about itself, from its OpenGraph tags (or Twitter's, or its title), for a distribution's preview */
data class LinkPreview(
    val title: String?,
    val description: String?,
    val site: String?,
    val publishedAt: OffsetDateTime?,
    // Where its picture is, absolute
    val image: String?,
)

/** There's no preview to be had, e.g. a page behind a login, and asking again won't change that */
class PreviewUnavailable(message: String) : IllegalArgumentException(message)

/** The page didn't answer as it might later, e.g. too many requests (429) or down (5xx); it's asked again */
class PreviewNotYet(message: String) : IOException(message)

/** What a page answered: from where, after redirects, and up to as much of it as was asked for */
class WebPage(val url: URI, val status: Int, val contentType: String?, val body: ByteArray) {
    val host: String get() = url.host

    fun charset(): Charset? = contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim('"', ' ')
        ?.ifEmpty { null }?.let { runCatching { Charset.forName(it) }.getOrNull() }
}

/**
 * Whether MetaOS may fetch from an address: not its own or its network's (loopback, private, link-local, e.g. Google's
 * metadata server at 169.254.169.254, the tailnet's 100.64.0.0/10, IPv6's unique local addresses), so a URL someone
 * adds can't make it ask what's inside the cluster
 */
fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) return false
    val bytes = address.address
    return when (address) {
        is Inet4Address -> {
            val (a, b) = bytes[0].toInt() and 0xff to (bytes[1].toInt() and 0xff)
            // 0.0.0.0/8, carrier-grade NAT (Tailscale's), and the benchmarking and reserved ranges
            !(a == 0 || (a == 100 && b in 64..127) || (a == 198 && b in 18..19) || a >= 240)
        }
        // Unique local addresses, fc00::/7
        is Inet6Address -> (bytes[0].toInt() and 0xfe) != 0xfc
        else -> false
    }
}

/**
 * The web, as MetaOS fetches previews from it: https only, from public addresses only ([isPublicAddress], checked again
 * at every redirect, of which it follows a few), within seconds, and only so much of a page. [allowLocal] (tests only,
 * `metaos.previews.allow-local`) lets it fetch from a stand-in server on this machine, over http too.
 */
@Component
class PublicWeb(@Value("\${metaos.previews.allow-local:false}") private val allowLocal: Boolean) {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun get(url: String, accept: String, maxBytes: Int): WebPage {
        var current = runCatching { URI(url) }.getOrElse { throw PreviewUnavailable("$url is not a URL") }
        repeat(MAX_REDIRECTS + 1) {
            check(current)
            val request = HttpRequest.newBuilder(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "sl,en;q=0.8")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: IOException) {
                throw IOException("${current.host} can't be reached: ${e.message ?: e.javaClass.simpleName}", e)
            }
            val location = response.headers().firstValue("Location").orElse(null)
            if (response.statusCode() in 300..399 && location != null) {
                response.body().close()
                current = runCatching { current.resolve(location.trim()) }.getOrElse { throw PreviewUnavailable("${current.host} redirects to $location") }
                return@repeat
            }
            val body = response.body().use { it.readNBytes(maxBytes) }
            return WebPage(current, response.statusCode(), response.headers().firstValue("Content-Type").orElse(null), body)
        }
        throw PreviewUnavailable("${current.host} redirects more than $MAX_REDIRECTS times")
    }

    private fun check(uri: URI) {
        val local = allowLocal && uri.scheme == "http"
        if (uri.scheme != "https" && !local) throw PreviewUnavailable("MetaOS fetches previews over https only, not $uri")
        val host = uri.host ?: throw PreviewUnavailable("$uri has no host")
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: IOException) {
            throw PreviewUnavailable("$host isn't on the internet: ${e.message}")
        }
        if (!allowLocal && addresses.any { !isPublicAddress(it) }) {
            throw PreviewUnavailable("$host is a private address, which MetaOS doesn't fetch from")
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 4
        const val USER_AGENT = "Mozilla/5.0 (compatible; MetaOS/1.0; +https://ogrodje.si)"
    }
}

/**
 * Previews of pages, from their OpenGraph tags, Twitter's where they have none, and their title and description; a
 * YouTube video's from YouTube's oEmbed, as its pages ask Europe's visitors to consent first
 */
@Component
class LinkPreviews(private val web: PublicWeb, private val mapper: JsonMapper) {

    fun of(url: String): LinkPreview {
        if (Channel.of(url) == Channel.YOUTUBE) youtube(url)?.let { return it }
        val page = web.get(url, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5", MAX_PAGE_BYTES)
        answered(page)
        val type = page.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type != "text/html" && type != "application/xhtml+xml") {
            throw PreviewUnavailable("${page.host} answered with $type, not a web page")
        }
        val preview = parse(Jsoup.parse(ByteArrayInputStream(page.body), page.charset()?.name(), page.url.toString()), page.host)
        if (preview.title == null && preview.description == null && preview.image == null) {
            throw PreviewUnavailable("${page.host}'s page says nothing about itself; it may need signing in")
        }
        return preview
    }

    // Only for videos, which oEmbed knows; a channel or a playlist is a page as any other
    private fun youtube(url: String): LinkPreview? {
        val oembed = "https://www.youtube.com/oembed?format=json&url=${URLEncoder.encode(url, Charsets.UTF_8)}"
        val page = web.get(oembed, "application/json", MAX_PAGE_BYTES)
        if (page.status != 200) return null
        val json = runCatching { mapper.readTree(page.body) }.getOrNull() ?: return null
        fun text(name: String) = json.get(name)?.takeUnless { it.isNull }?.asString()?.trim()?.ifEmpty { null }
        val title = text("title") ?: return null
        // oEmbed's is 480 × 360, letterboxed; the video's own 1280 × 720, where it has one
        val thumbnail = text("thumbnail_url")
        val large = thumbnail?.replace("/hqdefault.", "/maxresdefault.")?.takeIf { it != thumbnail }
            ?.takeIf { runCatching { web.get(it, "image/*", 1).status == 200 }.getOrDefault(false) }
        return LinkPreview(title, text("author_name"), "YouTube", null, large ?: thumbnail)
    }

    companion object {
        // A page's head is in its first few hundred kilobytes, however long the page
        const val MAX_PAGE_BYTES = 1024 * 1024

        /** A page that didn't answer with what it has: asked again later for what may pass, not for what won't */
        fun answered(page: WebPage) {
            when (page.status) {
                200, 203 -> return
                429, in 500..599 -> throw PreviewNotYet("${page.host} answered ${page.status}")
                401, 403, 999 -> throw PreviewUnavailable("${page.host} answered ${page.status}; it may need signing in")
                else -> throw PreviewUnavailable("${page.host} answered ${page.status}")
            }
        }

        fun parse(document: Document, host: String): LinkPreview {
            fun meta(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
                document.select("meta[property=$name], meta[name=$name]").firstNotNullOfOrNull { element ->
                    element.attr("content").trim().ifEmpty { null }
                }
            }
            fun image(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
                document.select("meta[property=$name], meta[name=$name]").firstNotNullOfOrNull { element ->
                    element.absUrl("content").ifEmpty { null }?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                }
            }
            return LinkPreview(
                title = (meta("og:title", "twitter:title") ?: document.title().trim().ifEmpty { null })?.take(500),
                description = meta("og:description", "twitter:description", "description")?.take(2000),
                site = (meta("og:site_name") ?: host.removePrefix("www."))?.take(200),
                publishedAt = meta("article:published_time", "og:article:published_time", "datePublished")?.let(::instant),
                image = image("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src"),
            )
        }

        private fun instant(value: String): OffsetDateTime? = try {
            OffsetDateTime.parse(value).truncatedTo(ChronoUnit.MICROS)
        } catch (_: DateTimeParseException) {
            runCatching { java.time.LocalDate.parse(value.take(10)).atStartOfDay().atOffset(ZoneOffset.UTC) }.getOrNull()
        }
    }
}

/** A distribution's preview to fetch, for whom: whoever asked for it, in the audit log */
data class PreviewRequest(val distributionId: UUID, val actorId: String?, val actor: String?)

/**
 * A distribution's preview (Distributions.kt): its page's OpenGraph tags, and its picture copied to the public bucket
 * (Images), as the networks' own picture URLs expire. A page that's busy or down is asked again a few times; one that
 * won't say, e.g. behind a login, isn't, and the distribution says why. Its ID is the distribution's ([workflowId]), so
 * one is fetched at a time.
 */
@WorkflowInterface
interface DistributionPreviewWorkflow {
    @WorkflowMethod
    fun preview(request: PreviewRequest)

    companion object {
        fun workflowId(distributionId: UUID) = "preview-$distributionId"
    }
}

@ActivityInterface
interface DistributionPreviewActivities {
    /** Fetches the page and its picture, and the distribution has them; again while its URL changed meanwhile */
    fun fetchPreview(request: PreviewRequest)

    /** The distribution says there's no preview, and why; it keeps the one it had, if any */
    fun previewUnavailable(request: PreviewRequest, why: String)
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class DistributionPreviewWorkflowImpl : DistributionPreviewWorkflow {

    private val fetching = Workflow.newActivityStub(
        DistributionPreviewActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(30)).setBackoffCoefficient(3.0).setMaximumAttempts(4)
                    .setDoNotRetry(IllegalArgumentException::class.java.name, PreviewUnavailable::class.java.name).build()
            )
            .build(),
    )

    private val quick = Workflow.newActivityStub(
        DistributionPreviewActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
            .build(),
    )

    override fun preview(request: PreviewRequest) {
        try {
            fetching.fetchPreview(request)
        } catch (failed: ActivityFailure) {
            quick.previewUnavailable(request, why(failed))
        }
    }

    // What went wrong, as the activity said it, without Temporal's wrapping
    private fun why(failure: Throwable): String {
        var cause: Throwable? = failure
        while (cause is ActivityFailure) cause = cause.cause
        return ((cause as? ApplicationFailure)?.originalMessage ?: cause?.message ?: failure.message ?: "It failed").take(500)
    }
}

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

@Component
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class DistributionPreviewActivitiesImpl(
    private val distributions: DistributionsRepository,
    private val previews: LinkPreviews,
    private val web: PublicWeb,
    private val images: ObjectProvider<Images>,
    transactionManager: PlatformTransactionManager,
) : DistributionPreviewActivities {

    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    override fun fetchPreview(request: PreviewRequest) {
        repeat(3) {
            val url = distributions.findByIdOrNull(request.distributionId)?.url ?: return
            val preview = previews.of(url)
            val imageId = preview.image?.let(::picture)
            val saved = changing(request) { entry ->
                // Its URL changed while this one was fetched, so that one's fetched instead
                if (entry.url != url) return@changing false
                entry.previewStatus = PreviewStatus.OK
                entry.previewTitle = preview.title
                entry.previewDescription = preview.description
                entry.previewSite = preview.site
                entry.previewPublishedAt = preview.publishedAt
                entry.previewImageId = imageId ?: entry.previewImageId.takeIf { preview.image != null }
                entry.previewError = null
                entry.previewedAt = now()
                true
            }
            if (saved != false) return
        }
    }

    override fun previewUnavailable(request: PreviewRequest, why: String) {
        changing(request) { entry ->
            entry.previewStatus = PreviewStatus.UNAVAILABLE
            entry.previewError = why
            entry.previewedAt = now()
        }
    }

    // The preview's picture, in the public bucket; a preview does without one MetaOS can't fetch or read, e.g. an SVG
    private fun picture(url: String): UUID? {
        val images = images.ifAvailable ?: return null
        return try {
            val page = web.get(url, "image/*", MAX_IMAGE_BYTES + 1)
            LinkPreviews.answered(page)
            require(page.body.size <= MAX_IMAGE_BYTES) { "$url is more than ${MAX_IMAGE_BYTES / 1024 / 1024} MB" }
            val name = page.url.path.substringAfterLast('/').ifEmpty { "preview" }
            images.publish(page.url.toString(), name, page.body).id
        } catch (e: PreviewNotYet) {
            throw e
        } catch (e: Exception) {
            log.info("No preview picture from {}: {}", url, e.message)
            null
        }
    }

    // The distribution, changed as whoever asked for its preview, in the audit log; nothing for one that's gone
    private fun <T> changing(request: PreviewRequest, change: (Distribution) -> T): T? =
        actingFor(Actor(request.actorId, request.actor)) {
            transaction.execute {
                distributions.findByIdOrNull(request.distributionId)?.let(change).also { distributions.flush() }
            }
        }
}

/**
 * Starts fetching distributions' previews, each in its workflow ([DistributionPreviewWorkflow]); without Temporal
 * (TEMPORAL_ADDRESS) they wait. Every ten minutes, those that are still to be fetched are started again, e.g. the
 * episodes' links V34 made entries of, or one whose workflow didn't start; one that's on goes on.
 */
@Component
class DistributionPreviews(
    private val client: ObjectProvider<WorkflowClient>,
    private val distributions: ObjectProvider<DistributionsRepository>,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun start(distributionId: UUID, actor: Actor) {
        val workflows = client.ifAvailable ?: return
        val workflow = workflows.newWorkflowStub(
            DistributionPreviewWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(WORKFLOWS_QUEUE)
                .setWorkflowId(DistributionPreviewWorkflow.workflowId(distributionId))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .build(),
        )
        try {
            WorkflowClient.start(workflow::preview, PreviewRequest(distributionId, actor.id, actor.name))
        } catch (e: RuntimeException) {
            // The sweep starts it again
            log.warn("The preview of distribution {} didn't start: {}", distributionId, e.message)
        }
    }

    @Scheduled(fixedDelay = 10, initialDelay = 2, timeUnit = TimeUnit.MINUTES)
    fun startPending() {
        if (client.ifAvailable == null) return
        val repository = distributions.ifAvailable ?: return
        repository.findByPreviewStatusAndUpdatedAtBefore(PreviewStatus.PENDING, OffsetDateTime.now().minusMinutes(5), Pageable.ofSize(50))
            .forEach { start(checkNotNull(it.id), Actor(it.addedById, it.addedBy)) }
    }
}
