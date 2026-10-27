package si.ogrodje.patreon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/**
 * Ogrodje's Patreon behind a small HTTP API, for MetaOS: Patreon's own API where it can, and its web app, signed in as
 * the bot, where it can't (creating, changing and publishing posts, uploading media). Callers see posts, tiers and
 * jobs, not how each is done.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class PatreonGateway {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<PatreonGateway>(*args)
}

@ConfigurationProperties("patreon")
data class PatreonProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val account: String = "",
    val stateDir: Path = Path.of("state"),
    val headed: Boolean = false,
    // Playwright's browser server to use (ws://...), e.g. its container beside the gateway; blank: start Chromium itself
    val browserUrl: String = "",
    // The proxy Chromium goes through (socks5://... or http://...), e.g. Tailscale's to an exit node; blank: none
    val browserProxy: String = "",
    // Where files are downloaded on their way to Patreon; the state directory's downloads when blank
    val downloadsDir: String = "",
    // How long what failed jobs' pages showed is kept (Browser.forgetFailures)
    val failuresKept: Duration = Duration.ofDays(14),
)
