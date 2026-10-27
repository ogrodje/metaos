package si.ogrodje.patreon

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import kotlin.text.equals
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

/**
 * Signs the bot in to Patreon by hand, in a browser you can see (2FA and captchas need a person), and saves the
 * browser's session (cookies and local storage) to the file given, for the gateway: `./gradlew -p patreon-gateway
 * login`, then `PUT /session` with it (`patreon-gateway/login.sh`). It waits until Patreon says someone is signed in.
 */
fun main(args: Array<String>) {
    val target = Path.of(args.firstOrNull() ?: "state/session.json").toAbsolutePath()
    val account = args.getOrNull(1) ?: System.getenv("PATREON_ACCOUNT") ?: "iva.ogrodje@gmail.com"
    Playwright.create().use { playwright ->
        val browser = launch(playwright)
        val context = browser.newContext(Browser.NewContextOptions().setLocale("en-US"))
        val page = context.newPage()
        page.navigate("https://www.patreon.com/login")
        println("Sign in to Patreon as $account in the browser that opened. Waiting up to 15 minutes...")
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000
        var email: String? = null
        var warned: String? = null
        while (email == null && System.currentTimeMillis() < deadline) {
            page.waitForTimeout(3000.0)
            // Quickly, without a page, until someone is signed in; then who it is, in a page of its own
            val user = runCatching { currentUser(context.request())?.let { whoIsSignedIn(context) } }
                .onFailure { System.err.println("Couldn't ask Patreon who's signed in: ${it.message}") }.getOrNull()
            val signedIn = user?.email
            // Anyone else's session isn't kept: sign out in that browser and in again as the bot
            if (user != null && !account.equals(signedIn, ignoreCase = true)) {
                if (warned != user.id) System.err.println("Signed in as ${user.name} <$signedIn> (user ${user.id}), not $account: sign out and in again as $account.")
                warned = user.id
                // Each check loads a page: not every 3 s, as Patreon limits how often
                page.waitForTimeout(15_000.0)
            } else {
                email = signedIn
            }
        }
        if (email == null) {
            System.err.println("$account didn't sign in, nothing saved.")
            exitProcess(1)
        }
        Files.createDirectories(target.parent)
        Files.writeString(target, context.storageState())
        runCatching { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------")) }
        println("Signed in as $email; the session is in $target")
        browser.close()
    }
}

// Chrome itself where it's installed, as Google refuses its sign-in in a browser it takes for automated
private fun launch(playwright: Playwright): Browser {
    fun options() = BrowserType.LaunchOptions().setHeadless(false).setArgs(listOf("--disable-blink-features=AutomationControlled"))
    return runCatching { playwright.chromium().launch(options().setChannel("chrome")) }
        .getOrElse { playwright.chromium().launch(options()) }
}
