package si.ogrodje.patreon

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/** Patreon turned the gateway away for now (rate limit, bot check): a job to send again in a few minutes. */
class PatreonBusyException(message: String) : RuntimeException(message)

/** Who may see a post: anyone, every patron (free ones too), or patrons of some tiers (by their IDs, [PatreonApi.tiers]). */
data class Access(val kind: Kind, val tiers: List<String> = emptyList()) {
    enum class Kind { PUBLIC, PATRONS, TIERS }
}

/**
 * What a post says. [text] is plain text: paragraphs between blank lines, lines within one kept, and addresses made
 * links. Null fields stay as they are when a draft is changed.
 */
data class PostSpec(
    val title: String? = null,
    val text: String? = null,
    val access: Access? = null,
)

/** A post as Patreon's web app has it, drafts too. */
data class WebPost(
    val id: String,
    val title: String?,
    val url: String?,
    val editUrl: String,
    val publishedAt: OffsetDateTime?,
    val scheduledFor: OffsetDateTime?,
    val draft: Boolean,
)

/**
 * Posts made, changed, published and deleted as Patreon's own post editor does it: through Patreon's web API, from a
 * page of the campaign, signed in as the bot acting as the creator (team members switch to the creator's account,
 * `/api/session/acting-as`), with the page's CSRF signature. Not through the editor itself: Cloudflare checks headless
 * browsers on its page, and the calls are what it makes anyway (patreon-gateway/README.md says how they were found).
 */
@Component
class PatreonWeb(private val browser: Browser, private val api: PatreonApi, private val media: Media, private val mapper: JsonMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A new draft: nobody but the team sees it until it's published. [created] keeps its ID across the job's tries, so
     * one Patreon turned away after the draft was made goes on with that one, not another.
     */
    fun createDraft(job: String, spec: PostSpec, created: AtomicReference<String> = AtomicReference()): WebPost = session(job) { session ->
        // Who may see it first, so a tier the campaign hasn't fails before Patreon has an empty draft
        val rules = spec.access?.let { accessRules(session, it) }
        val id = created.get() ?: session.call("POST", "/api/posts/new?fields%5Bpost%5D=edit_url", jsonApi("post") { it.put("post_type", "text_only") })
            .let { it["data"]["id"].asString() }.also { created.set(it) }
        save(session, id, spec, publish = false, fresh = true, givenRules = rules)
    }

    /** Changes a draft, or a published post, as [spec] says; what it leaves null stays. */
    fun update(job: String, id: String, spec: PostSpec): WebPost = session(job) { save(it, postId(id), spec, publish = false) }

    /**
     * Publishes a draft, now or at [at], and emails the patrons who may see it only when [notify]. Patreon does this
     * with the same save as the editor's Publish, which sends the whole post.
     */
    fun publish(job: String, id: String, at: OffsetDateTime?, notify: Boolean): WebPost = session(job) { session ->
        // Once is enough: publishing again could email the patrons again
        val response = session.call("GET", "/api/posts/${postId(id)}?fields%5Bpost%5D=$WEB_POST_FIELDS&include=media&fields%5Bmedia%5D=state", null)
        val post = webPost(response["data"])
        if (!post.draft) throw IllegalArgumentException("Post $id is already published (${post.publishedAt})")
        if (post.scheduledFor != null && at == null) throw IllegalArgumentException("Post $id is already scheduled for ${post.scheduledFor}")
        require(at == null || at.isAfter(OffsetDateTime.now())) { "A post can only be scheduled for later" }
        // A video Patreon is still processing: it publishes the post once it's done, as its editor's Publish does
        val whenReady = at == null && !mediaReady(response["included"])
        save(session, postId(id), PostSpec(), publish = true, at = at, notify = notify, whenMediaReady = whenReady)
    }

    fun delete(job: String, id: String) = session(job) { session ->
        session.call("DELETE", "/api/posts/${postId(id)}", null)
        api.forgetPosts()
        mapOf("deleted" to id)
    }

    fun get(job: String, id: String): WebPost = session(job) { webPost(current(it, postId(id))) }

    /**
     * Puts a file on a draft as its audio or video, as the editor does: the post becomes a podcast post (and, as in the
     * editor, goes into the campaign's show, its podcast feed) or a video post. The file goes to Patreon from here, not
     * in the browser, whose one thread would otherwise be held for the hours an episode's video can take: Patreon is told
     * about it in one visit to the campaign's page and the post saved in another. That waits up to [ready] for Patreon to
     * process the file; a long video takes longer, and the post is saved anyway (publishing then waits for it).
     * [progress] keeps an uploaded file across the job's tries, so one Patreon turned away later doesn't upload it again.
     */
    fun attachMedia(
        job: String,
        id: String,
        request: MediaRequest,
        progress: AtomicReference<UploadedMedia> = AtomicReference(),
        ready: Duration = Duration.ofMinutes(1),
    ): Map<String, Any?> {
        val post = postId(id)
        val uploaded = progress.get() ?: media.downloaded(request) { file, name ->
            val created = session(job) { session ->
                if (request.kind == MediaKind.VIDEO) {
                    session.call("PATCH", "/api/posts/$post?fields%5Bpost%5D=post_type", mapper.createObjectNode().also {
                        it.putObject("data").put("post_type", MediaKind.VIDEO.postType)
                        it.putObject("meta").put("auto_save", true)
                    })
                }
                session.call("POST", "/api/media", jsonApi("media") {
                    it.put("state", "pending_upload").put("file_name", name).put("size_bytes", Files.size(file))
                        .put("owner_id", post).put("owner_type", "post")
                        .put("owner_relationship", request.kind.relationship).put("media_type", request.kind.relationship)
                })["data"]
            }
            val mediaId = created["id"].asString()
            try {
                media.upload(created, file, name, media.contentType(name, request.kind))
            } catch (e: Exception) {
                // A media Patreon never got the file of stays on the post, and keeps another from being put there
                runCatching { session("$job-undo") { it.call("DELETE", "/api/media/$mediaId", null) } }
                    .onFailure { log.warn("Couldn't remove media {} after its upload failed: {}", mediaId, it.message) }
                throw e
            }
            UploadedMedia(mediaId, request.kind, name, Files.size(file), "uploaded").also(progress::set)
        }
        return session(job) { session ->
            val state = waitUntilReady(session, uploaded.id, ready)
            val show = if (request.kind == MediaKind.AUDIO) show(session) else null
            val saved = save(session, post, PostSpec(), publish = false, postType = request.kind.postType, addCollection = show)
            mapOf("post" to saved, "media" to uploaded.copy(state = state))
        }
    }

    // Asks less and less often, as each ask counts against Patreon's rate limit: a long video isn't ready in minutes
    private fun waitUntilReady(session: Session, mediaId: String, ready: Duration): String {
        val deadline = System.nanoTime() + ready.toNanos()
        var state = "pending_upload"
        for (wait in generateSequence(5L) { minOf(it * 2, 60L) }) {
            state = session.call("GET", "/api/media/$mediaId?fields%5Bmedia%5D=state", null)["data"]["attributes"].text("state") ?: state
            if (state == "ready" || state == "failed" || System.nanoTime() + wait * 1_000_000_000 > deadline) break
            session.page.waitForTimeout(wait * 1000.0)
        }
        if (state == "failed") throw PatreonException("Patreon couldn't process the file (media $mediaId)")
        return state
    }

    // Whether all of a post's media are processed; publishing waits for them otherwise
    private fun mediaReady(included: JsonNode?): Boolean =
        included?.toList().orEmpty().filter { it["type"].asString() == "media" }.all { it["attributes"]?.text("state") == "ready" }

    // The campaign's show: its podcast feed, which the editor puts every audio post in
    private fun show(session: Session): String? = session.call(
        "GET",
        "/api/collection?filter%5Bcampaign_id%5D=${api.campaignId()}&filter%5Bcollection_type%5D=show&fields%5Bcollection%5D=title",
        null,
    )["data"]?.firstOrNull()?.get("id")?.asString()

    private fun save(
        session: Session,
        id: String,
        spec: PostSpec,
        publish: Boolean,
        at: OffsetDateTime? = null,
        notify: Boolean = false,
        postType: String? = null,
        addCollection: String? = null,
        whenMediaReady: Boolean = false,
        fresh: Boolean = false,
        givenRules: List<String>? = null,
    ): WebPost {
        val post = current(session, id)
        val attributes = post["attributes"]
        val title = spec.title ?: attributes.text("title") ?: ""
        val given = spec.text?.let { content(it) }
        val body = jsonApi("post") {
            it.put("title", title)
            if (given != null) {
                it.put("content", given.first)
                it.put("content_json_string", given.second)
            } else {
                // The post's own text, as it is: Patreon keeps it as its document, and often has no HTML of it
                attributes.text("content_json_string")?.let { json -> it.put("content_json_string", json) }
                attributes.text("content")?.let { html -> it.put("content", html) }
            }
            it.put("post_type", postType ?: attributes.text("post_type") ?: "text_only")
            // A new draft's settings, as the editor makes them; a post's own, e.g. one made in the editor, stay as they are
            if (fresh) {
                it.put("is_paid", false)
                it.put("is_monetized", false)
                it.put("comments_write_access_level", "all")
                it.put("new_post_email_type", "preview_only")
                it.put("paywall_display", "post_layout")
                it.put("preview_asset_type", "default")
                it.put("allow_preview_in_rss", true)
            }
            when {
                publish && whenMediaReady -> it.put("auto_publish_when_media_ready", true)
                publish -> {
                    it.putObject("tags").put("publish", true)
                    if (at != null) it.put("scheduled_for", at.withOffsetSameInstant(ZoneOffset.UTC).format(PATREON_TIME))
                }
            }
        }
        val kept = post["relationships"]?.get("access_rules")?.get("data")?.toList().orEmpty().map { it["id"].asString() }
        val rules: List<String> = givenRules ?: spec.access?.let { accessRules(session, it) } ?: kept
        val relationships = (body["data"] as ObjectNode).putObject("relationships")
        // The collections it's in stay; the show is added for audio
        val collections = (post["relationships"]?.get("collections")?.get("data")?.toList().orEmpty().map { it["id"].asString() } +
            listOfNotNull(addCollection)).distinct()
        relationships.putObject("collections").putArray("data").also { list -> collections.forEach { list.add(it) } }
        // As the editor sends them: the first rule, all of them, and each again in "included"
        if (rules.isNotEmpty()) {
            relationships.putObject("access-rule").putObject("data").put("type", "access-rule").put("id", rules.first())
            val list = relationships.putObject("access_rules").putArray("data")
            val included = body.putArray("included")
            rules.forEach { rule ->
                list.addObject().put("id", rule).put("type", "access-rule")
                included.addObject().put("type", "access-rule").put("id", rule).putObject("attributes")
            }
        }
        (body as ObjectNode).putObject("meta").put("auto_save", !publish).put("send_notifications", publish && notify)
        val saved = session.call("PATCH", "/api/posts/$id?fields%5Bpost%5D=$WEB_POST_FIELDS", body)
        if (publish) api.forgetPosts()
        return webPost(saved["data"])
    }

    private fun current(session: Session, id: String): JsonNode =
        session.call("GET", "/api/posts/$id?fields%5Bpost%5D=$WEB_POST_FIELDS,content,content_json_string,post_type&include=access_rules,collections", null)["data"]

    /** The access rules Patreon has for what [access] says: one for everyone, one for all patrons, one a tier. */
    private fun accessRules(session: Session, access: Access): List<String> {
        val campaign = session.call(
            "GET",
            "/api/campaigns/${api.campaignId()}?include=access_rules.tier.null&fields%5Baccess_rule%5D=access_rule_type&fields%5Btier%5D=title",
            null,
        )
        val rules = campaign["included"]?.filter { it["type"].asString() == "access-rule" }.orEmpty()
        fun type(rule: JsonNode) = rule["attributes"]?.text("access_rule_type")
        fun tier(rule: JsonNode) = rule["relationships"]?.get("tier")?.get("data")?.text("id")
        return when (access.kind) {
            Access.Kind.PUBLIC -> listOfNotNull(rules.firstOrNull { type(it) == "public" }?.get("id")?.asString())
            Access.Kind.PATRONS -> listOfNotNull(rules.firstOrNull { type(it) == "patrons" }?.get("id")?.asString())
            Access.Kind.TIERS -> access.tiers.map { wanted ->
                rules.firstOrNull { type(it) == "tier" && tier(it) == wanted }?.get("id")?.asString()
                    ?: throw IllegalArgumentException("The campaign has no tier $wanted")
            }
        }.ifEmpty { throw PatreonException("Patreon has no access rule for ${access.kind.name.lowercase()}") }
    }

    private fun webPost(data: JsonNode): WebPost {
        val attributes = data["attributes"]
        val published = attributes.text("published_at")?.let(OffsetDateTime::parse)
        return WebPost(
            id = data["id"].asString(),
            title = attributes.text("title"),
            url = attributes.text("url")?.let(::absolute),
            editUrl = absolute("/posts/${data["id"].asString()}/edit"),
            publishedAt = published,
            scheduledFor = attributes.text("scheduled_for")?.let(::patreonTime),
            draft = published == null,
        )
    }

    // A time as Patreon gives it, with its offset, or without one in UTC (scheduled_for, as the editor sends it)
    private fun patreonTime(text: String): OffsetDateTime =
        runCatching { OffsetDateTime.parse(text) }.getOrElse { LocalDateTime.parse(text).atOffset(ZoneOffset.UTC) }

    private fun jsonApi(type: String, attributes: (ObjectNode) -> Unit): ObjectNode {
        val root = mapper.createObjectNode()
        val data = root.putObject("data").put("type", type)
        attributes(data.putObject("attributes"))
        return root
    }

    /** [text] as Patreon's editor keeps it: HTML, and its own document (ProseMirror's JSON) of the same paragraphs. */
    internal fun content(text: String): Pair<String, String> {
        val paragraphs = text.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
        val html = paragraphs.joinToString("") { paragraph ->
            "<p>" + paragraph.lines().joinToString("<br>") { line -> inline(line).joinToString("") { (text, href) -> href?.let { "<a href=\"${escape(it)}\" target=\"_blank\">${escape(text)}</a>" } ?: escape(text) } } + "</p>"
        }
        val doc = mapper.createObjectNode().put("type", "doc")
        val content = doc.putArray("content")
        paragraphs.forEach { paragraph ->
            val node = content.addObject().put("type", "paragraph")
            val nodes = node.putArray("content")
            paragraph.lines().forEachIndexed { index, line ->
                if (index > 0) nodes.addObject().put("type", "hardBreak")
                inline(line).filter { it.first.isNotEmpty() }.forEach { (text, href) ->
                    val textNode = nodes.addObject().put("type", "text").put("text", text)
                    href?.let { textNode.putArray("marks").addObject().put("type", "link").putObject("attrs").put("href", it).put("target", "_blank") }
                }
            }
        }
        return html to mapper.writeValueAsString(doc)
    }

    // A line's text and links, in order
    private fun inline(line: String): List<Pair<String, String?>> {
        val parts = mutableListOf<Pair<String, String?>>()
        var at = 0
        for (match in URL.findAll(line)) {
            if (match.range.first > at) parts += line.substring(at, match.range.first) to null
            parts += match.value to match.value
            at = match.range.last + 1
        }
        if (at < line.length) parts += line.substring(at) to null
        return parts
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun postId(id: String) = id.also { require(it.all(Char::isDigit) && it.isNotEmpty()) { "A post's ID is a number" } }

    /** A page of the campaign, signed in and acting as its creator, to call Patreon's web API from. */
    private fun <T> session(job: String, work: (Session) -> T): T = browser.use(job) { page ->
        val campaign = api.campaign()
        val response = page.navigate(campaign.url.replace("www.patreon.com/", "www.patreon.com/c/"))
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)
        if (response != null && (response.status() == 429 || response.status() == 403) || page.title().contains("Just a moment")) {
            throw PatreonBusyException("Patreon is turning the gateway away for now (${response?.status()}): try again in a few minutes")
        }
        dismissCookies(page)
        val csrf = CSRF.find(page.content())?.groupValues?.get(1)
            ?: throw SignedOutException("Patreon's page has no CSRF signature: the bot's session may have run out")
        val session = Session(page, csrf, mapper)
        session.call("POST", "/api/session/acting-as", mapper.createObjectNode().also {
            it.putObject("data").put("acting_as_user_id", api.creatorUserId().toLong())
        })
        work(session)
    }

    // Patreon's cookie banner, should it show up anyway: the session has the bot's answer
    private fun dismissCookies(page: Page) {
        val reject = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, Page.GetByRoleOptions().setName("Reject non-essential"))
        if (runCatching { reject.isVisible }.getOrDefault(false)) reject.click()
    }

    private class Session(val page: Page, val csrf: String, val mapper: JsonMapper) {
        fun call(method: String, path: String, body: JsonNode?): JsonNode {
            @Suppress("UNCHECKED_CAST")
            val result = page.evaluate(FETCH, listOf(method, path, body?.let { mapper.writeValueAsString(it) }, csrf)) as List<Any?>
            val status = (result[0] as Number).toInt()
            val text = result[1] as String? ?: ""
            when {
                status == 429 || (status == 403 && !text.trimStart().startsWith("{")) ->
                    throw PatreonBusyException("Patreon is turning the gateway away for now ($status): try again in a few minutes")
                status == 401 -> throw SignedOutException("The bot's Patreon session ran out: run patreon-gateway/login.sh")
                status == 404 -> throw IllegalArgumentException("Patreon has no ${path.substringBefore('?')}")
                status !in 200..299 -> throw PatreonException("Patreon answered $status to $method ${path.substringBefore('?')}: ${errorDetail(text)}", status)
            }
            return if (text.isBlank()) mapper.createObjectNode() else mapper.readTree(text)
        }

        private fun errorDetail(text: String) = runCatching {
            mapper.readTree(text)["errors"]?.firstOrNull()?.let { it.text("detail") ?: it.text("title") }
        }.getOrNull() ?: text.take(200)
    }

    private companion object {
        val CSRF = Regex("\"csrf_signature\"\\s*:\\s*\"([^\"]+)\"")
        val URL = Regex("https?://[^\\s<>\"]+[^\\s<>\".,;:!?)]")
        val PATREON_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        const val WEB_POST_FIELDS = "title,url,published_at,scheduled_for,edit_url"

        // Patreon's web API, from the page, with its cookies and CSRF signature, as its editor calls it; given up after
        // two minutes, as nothing else times out a page's fetch, and one that hangs would hold the browser's one thread
        const val FETCH = """async ([method, path, body, csrf]) => {
            const response = await fetch(path + (path.includes('?') ? '&' : '?') + 'json-api-version=1.0', {
                method, credentials: 'include', body: body ?? undefined,
                headers: { 'content-type': 'application/vnd.api+json', 'x-csrf-signature': csrf },
                signal: AbortSignal.timeout(120000),
            });
            return [response.status, await response.text()];
        }"""
    }
}

private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()
