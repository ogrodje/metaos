package si.ogrodje.scrapers

import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Chromium, for the few sites that show their events only to a browser (Eventbrite, GZS): Playwright's
 * browser server at `scrapers.browser-url` (in k8s a container of its own beside the scrapers, from Microsoft's image,
 * as patreon-gateway's), or else one it starts itself. Playwright's connection is single-threaded, so every page is
 * on one thread, one at a time, each in a fresh context of its own, closed once it's done.
 */
@Component
class Browser(@Value("\${scrapers.browser-url:}") private val url: String, private val mapper: JsonMapper) : Pages, DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "browser").apply { isDaemon = true } }
    private var playwright: Playwright? = null
    private var browser: com.microsoft.playwright.Browser? = null

    override fun <T> use(work: (Page) -> T): T = try {
        thread.submit(Callable {
            browser().newContext(NewContextOptions().setUserAgent(HttpWeb.USER_AGENT).setLocale("en-US")).use { context ->
                context.setDefaultTimeout(30_000.0)
                work(context.newPage())
            }
        }).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun browser(): com.microsoft.playwright.Browser {
        browser?.takeIf { it.isConnected }?.let { return it }
        val playwright = playwright ?: Playwright.create().also { playwright = it }
        val server = url.trim()
        if (server.isEmpty()) {
            log.info("Starting Chromium")
            return playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true).setArgs(CHROMIUM_ARGS)).also { browser = it }
        }
        val options = mapOf("headless" to true, "args" to CHROMIUM_ARGS)
        return try {
            playwright.chromium().connect(
                server,
                BrowserType.ConnectOptions().setTimeout(30_000.0)
                    .setHeaders(mapOf("x-playwright-launch-options" to mapper.writeValueAsString(options))),
            ).also { browser = it }
        } catch (e: PlaywrightException) {
            // Its container may be (re)starting: the activity tries again
            throw IOException("The browser at $server isn't there: ${e.message?.lineSequence()?.firstOrNull()}")
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
        val CHROMIUM_ARGS = listOf("--disable-blink-features=AutomationControlled", "--disable-dev-shm-usage")
    }
}
