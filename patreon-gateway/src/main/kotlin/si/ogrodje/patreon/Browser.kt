package si.ogrodje.patreon

import com.microsoft.playwright.APIRequestContext
import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
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
    fun <T> use(name: String, work: (Page) -> T): T {
        try {
            return thread.submit(Callable { open(name, work) }).get()
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
        val page = context.newPage()
        try {
            return work(page).also { keep(context) }
        } catch (e: Throwable) {
            runCatching {
                Files.createDirectories(failures)
                page.screenshot(Page.ScreenshotOptions().setPath(failures.resolve("$name.png")).setFullPage(true))
                Files.writeString(failures.resolve("$name.html"), page.content())
            }.onFailure { log.warn("Couldn't keep what the page showed for {}: {}", name, it.message) }
            throw e
        } finally {
            context.close()
        }
    }

    /** What the page showed when [name] failed, if kept. */
    fun failure(name: String, extension: String): Path? =
        failures.resolve("$name.$extension").takeIf { name.matches(SAFE_NAME) && Files.exists(it) }

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
            val email = thread.submit(Callable {
                browser().newContext(NewContextOptions().setStorageStatePath(candidate)).use { signedInAs(it) }
            }).get() ?: throw SignedOutException("That session isn't signed in to Patreon")
            runCatching { Files.setPosixFilePermissions(candidate, PosixFilePermissions.fromString("rw-------")) }
            Files.move(candidate, sessionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return email
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

    private fun browser(): com.microsoft.playwright.Browser {
        browser?.takeIf { it.isConnected }?.let { return it }
        val playwright = playwright ?: Playwright.create().also { playwright = it }
        return playwright.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(!properties.headed)
                .setArgs(listOf("--disable-blink-features=AutomationControlled", "--disable-dev-shm-usage")),
        ).also { browser = it }
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
    }
}

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
    val user = currentUser(context.request()) ?: return null
    val page = context.newPage()
    try {
        page.navigate("https://www.patreon.com/home")
        val actor = ACTOR.find(page.content())?.groupValues?.get(1)?.let { JsonMapper.builder().build().readTree(it) }
            ?: return user
        return CurrentUser(
            id = actor["id"]?.asString() ?: return user,
            email = actor["email"]?.takeUnless { it.isNull }?.asString(),
            name = actor["full_name"]?.takeUnless { it.isNull }?.asString(),
        )
    } finally {
        page.close()
    }
}

/**
 * Whom Patreon's web app answers as: the user in `data` of its own `/api/current_user`, not anyone in `included`; the
 * creator, while a team member acts as them.
 */
fun currentUser(request: APIRequestContext): CurrentUser? {
    val response = request.get("https://www.patreon.com/api/current_user?fields%5Buser%5D=email,full_name")
    if (response.status() != 200) return null
    val data = JsonMapper.builder().build().readTree(response.text())["data"] ?: return null
    val attributes = data["attributes"]
    return CurrentUser(
        id = data["id"]?.asString() ?: return null,
        email = attributes?.get("email")?.takeUnless { it.isNull }?.asString(),
        name = attributes?.get("full_name")?.takeUnless { it.isNull }?.asString(),
    )
}
