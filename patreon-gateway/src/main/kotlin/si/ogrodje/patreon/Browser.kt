package si.ogrodje.patreon

import com.microsoft.playwright.APIRequestContext
import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class SignedOutException(message: String) : RuntimeException(message)

/**
 * Patreon's web app in headless Chromium, signed in as the bot with the session [Login] saved (`session.json` in the
 * state directory). Playwright isn't thread-safe, so everything it does runs on one thread of its own, one thing at a
 * time; each use gets a fresh context from the session and saves the session again after, as Patreon renews cookies.
 */
@Component
class Browser(private val properties: PatreonProperties, private val mapper: JsonMapper) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "browser").apply { isDaemon = true } }
    private var playwright: Playwright? = null
    private var browser: com.microsoft.playwright.Browser? = null

    private val sessionFile: Path get() = properties.stateDir.resolve("session.json")
    private val failures: Path get() = properties.stateDir.resolve("failures")

    val hasSession: Boolean get() = Files.exists(sessionFile)

    /**
     * Runs [work] with a page signed in as the bot, on the browser's thread. When it fails, what the page showed is kept
     * as `failures/<name>.png` and `.html` ([failure]).
     */
    fun <T> use(name: String, work: (Page) -> T): T = onThread { open(name, work) }

    // On the browser's thread, with what failed there thrown here as it was
    private fun <T> onThread(work: () -> T): T {
        try {
            return thread.submit(Callable { work() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun <T> open(name: String, work: (Page) -> T): T {
        if (!hasSession) throw SignedOutException("The bot isn't signed in to Patreon: run patreon-gateway/login.sh")
        val context = browser().newContext(
            NewContextOptions().setStorageStatePath(sessionFile).setLocale("en-US").setViewportSize(1440, 1000),
        )
        context.setDefaultTimeout(30_000.0)
        quiet(context)
        val page = context.newPage()
        try {
            val result = try {
                work(page)
            } catch (e: Throwable) {
                runCatching {
                    Files.createDirectories(failures)
                    page.screenshot(Page.ScreenshotOptions().setPath(failures.resolve("$name.png")).setFullPage(true))
                    Files.writeString(failures.resolve("$name.html"), page.content())
                    // The page may show who's signed in, and its HTML holds the session's CSRF signature
                    listOf("png", "html").forEach { kind ->
                        runCatching { Files.setPosixFilePermissions(failures.resolve("$name.$kind"), PosixFilePermissions.fromString("rw-------")) }
                    }
                }.onFailure { log.warn("Couldn't keep what the page showed for {}: {}", name, it.message) }
                throw e
            }
            // What the work did in Patreon is done: a session not saved now is saved after the next use
            runCatching { keep(context) }.onFailure { log.warn("Couldn't save the browser's session after {}: {}", name, it.message) }
            return result
        } finally {
            context.close()
        }
    }

    /**
     * What Patreon's pages load of their own accord - their feed, every post's comments, chat, tracking, pictures - counts
     * against Patreon's rate limit as the gateway's own calls do, some twenty a page, and the gateway needs none of it:
     * only a page's HTML (its CSRF signature, who's signed in) and the calls it makes itself, which say so ([OWN_CALL]).
     */
    private fun quiet(context: BrowserContext) {
        context.route("**/*") { route ->
            val request = route.request()
            when {
                request.url().contains("patreon.com/api/") && request.headers()[OWN_CALL] == null -> route.abort()
                request.resourceType() in setOf("image", "media", "font") -> route.abort()
                else -> route.resume()
            }
        }
    }

    /** What the page showed when [name] failed, if kept. */
    fun failure(name: String, extension: String): Path? =
        failures.resolve("$name.$extension").takeIf { name.matches(SAFE_NAME) && Files.exists(it) }

    /**
     * Deletes what failed jobs' pages showed once it's older than [PatreonProperties.failuresKept], every hour: it's
     * for finding out why a job failed, while that's asked, and it may show who's signed in. Returns how many it deleted.
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60 * 60 * 1000)
    fun forgetFailures(): Int {
        if (!Files.isDirectory(failures)) return 0
        val before = Instant.now().minus(properties.failuresKept)
        val old = Files.list(failures).use { files ->
            files.filter { Files.isRegularFile(it) && Files.getLastModifiedTime(it).toInstant().isBefore(before) }.toList()
        }
        old.forEach { runCatching { Files.deleteIfExists(it) }.onFailure { e -> log.warn("Couldn't delete {}: {}", it.fileName, e.message) } }
        if (old.isNotEmpty()) log.info("Deleted {} files of failed jobs older than {}", old.size, properties.failuresKept)
        return old.size
    }

    /** Who the browser is signed in as (their email), or null when it isn't: its session ran out or there's none. */
    fun signedInAs(): String? = if (!hasSession) null else use("whoami") { signedInAs(it.context()) }

    /** Takes a session [Login] saved elsewhere (Playwright's storage state), once it's checked to be signed in. */
    fun replaceSession(storageState: String): String {
        val parsed = runCatching { mapper.readTree(storageState) }.getOrNull()
        require(parsed != null && parsed["cookies"]?.isArray == true) { "That's not a browser session: it has no cookies" }
        Files.createDirectories(properties.stateDir)
        val candidate = Files.createTempFile(properties.stateDir, "session", ".json")
        Files.writeString(candidate, storageState)
        try {
            // Checked and put in place on the browser's thread, so no use that starts in between saves the old one over it
            return onThread {
                val email = browser().newContext(NewContextOptions().setStorageStatePath(candidate).setLocale("en-US"))
                    .use { quiet(it); signedInAs(it) }
                    ?: throw SignedOutException("That session isn't signed in to Patreon: Patreon says no one is, with its cookies")
                runCatching { Files.setPosixFilePermissions(candidate, PosixFilePermissions.fromString("rw-------")) }
                Files.move(candidate, sessionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                email
            }
        } finally {
            Files.deleteIfExists(candidate)
        }
    }

    private fun keep(context: BrowserContext) {
        val temporary = Files.createTempFile(properties.stateDir, "session", ".json")
        runCatching { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")) }
        Files.writeString(temporary, context.storageState())
        Files.move(temporary, sessionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Chromium: Playwright's browser server's at [PatreonProperties.browserUrl] (in k8s a container of its own beside
     * the gateway, from Microsoft's image, so the gateway's has no browser), or else one the gateway starts itself.
     */
    private fun browser(): com.microsoft.playwright.Browser {
        browser?.takeIf { it.isConnected }?.let { return it }
        val playwright = playwright ?: Playwright.create().also { playwright = it }
        val url = properties.browserUrl.trim()
        if (url.isEmpty()) {
            return playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(!properties.headed).setArgs(CHROMIUM_ARGS))
                .also { browser = it }
        }
        // The server starts a Chromium for each connection, with the options the gateway would start its own with
        val options = mapOf("headless" to true, "args" to CHROMIUM_ARGS)
        return try {
            playwright.chromium().connect(
                url,
                BrowserType.ConnectOptions().setTimeout(30_000.0)
                    .setHeaders(mapOf("x-playwright-launch-options" to mapper.writeValueAsString(options))),
            ).also { browser = it }
        } catch (e: PlaywrightException) {
            // Its container may be (re)starting: the job tries again later, as when Patreon turns it away
            throw PatreonBusyException("The browser at $url isn't there: ${e.message?.lineSequence()?.firstOrNull()}")
        }
    }

    override fun destroy() {
        thread.submit {
            runCatching { browser?.close() }
            runCatching { playwright?.close() }
        }
        thread.shutdown()
    }

    private companion object {
        val SAFE_NAME = Regex("[A-Za-z0-9-]+")
        val CHROMIUM_ARGS = listOf("--disable-blink-features=AutomationControlled", "--disable-dev-shm-usage")
    }
}

/** The header the gateway's own calls from a page carry ([PatreonWeb]), which its browser lets through to Patreon's API. */
const val OWN_CALL = "x-patreon-gateway"

/** The email of who is signed in to Patreon's web app in [context], or null when no one is ([whoIsSignedIn]). */
fun signedInAs(context: BrowserContext): String? = whoIsSignedIn(context)?.email

data class CurrentUser(val id: String, val email: String?, val name: String?)

private val ACTOR = Regex("\"actor\"\\s*:\\s*(\\{[^{}]*\\})")

/**
 * Who is signed in to Patreon's web app in [context]: the person, not whom they act as. A team member acting as the
 * creator (the bot, [PatreonWeb]) is the creator to Patreon's `/api/current_user`, so they're found as the "actor" in a
 * page's own data, which Patreon has only while someone acts as another; else `/api/current_user` says.
 */
fun whoIsSignedIn(context: BrowserContext): CurrentUser? {
    // From a page of Patreon's, as Chromium, as the jobs do: Cloudflare turns away Patreon's API asked from elsewhere,
    // e.g. by Playwright's own requests from a server's address
    val page = context.newPage()
    try {
        val response = page.navigate("https://www.patreon.com/home")
        val status = response?.status() ?: 0
        if (status == 403 || status == 429 || page.title().contains("Just a moment")) {
            throw PatreonBusyException("Patreon's bot check turned the browser away ($status): try again in a few minutes")
        }
        val mapper = JsonMapper.builder().build()
        ACTOR.find(page.content())?.groupValues?.get(1)?.let { mapper.readTree(it) }?.let { actor ->
            actor["id"]?.asString()?.let { id ->
                return CurrentUser(
                    id = id,
                    email = actor["email"]?.takeUnless { it.isNull }?.asString(),
                    name = actor["full_name"]?.takeUnless { it.isNull }?.asString(),
                )
            }
        }
        @Suppress("UNCHECKED_CAST")
        val answer = page.evaluate(CURRENT_USER) as List<Any?>
        val code = (answer[0] as Number).toInt()
        if (code == 403 || code == 429) {
            throw PatreonBusyException("Patreon's bot check turned the browser away ($code): try again in a few minutes")
        }
        return if (code == 200) user(mapper.readTree(answer[1] as String)) else null
    } finally {
        page.close()
    }
}

// Patreon's own /api/current_user, from the page, with its cookies; the header lets it through Browser.quiet
private const val CURRENT_USER = """async () => {
    const response = await fetch('/api/current_user?fields%5Buser%5D=email,full_name&json-api-version=1.0',
        { credentials: 'include', headers: { '$OWN_CALL': '1' } });
    return [response.status, await response.text()];
}"""

private fun user(body: tools.jackson.databind.JsonNode): CurrentUser? {
    val data = body["data"] ?: return null
    val attributes = data["attributes"]
    return CurrentUser(
        id = data["id"]?.asString() ?: return null,
        email = attributes?.get("email")?.takeUnless { it.isNull }?.asString(),
        name = attributes?.get("full_name")?.takeUnless { it.isNull }?.asString(),
    )
}

/**
 * Whom Patreon's web app answers as, asked by Playwright itself, without a page: the user in `data` of its own
 * `/api/current_user`, not anyone in `included`; the creator, while a team member acts as them. Quick, and so for
 * [Login] while it waits on a desktop; from a server's address Cloudflare may turn it away, so the gateway asks from a
 * page ([whoIsSignedIn]).
 */
fun currentUser(request: APIRequestContext): CurrentUser? {
    val response = request.get("https://www.patreon.com/api/current_user?fields%5Buser%5D=email,full_name")
    if (response.status() != 200) return null
    return user(JsonMapper.builder().build().readTree(response.text()))
}
