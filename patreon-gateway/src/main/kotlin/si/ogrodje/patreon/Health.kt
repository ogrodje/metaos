package si.ogrodje.patreon

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** How the gateway's two ways into Patreon are doing, as last checked. */
data class GatewayHealth(
    val api: Boolean,
    val apiError: String?,
    val signedIn: Boolean,
    val signedInAs: String?,
    val browserError: String?,
    val checkedAt: Instant?,
) {
    val ok: Boolean get() = api && signedIn
}

/**
 * Checks Patreon's API (the creator token) and the browser's session every hour, and on demand once the session is
 * replaced: a session runs out, and that's only found by using it. `patreon_gateway_api_connected` and
 * `patreon_gateway_signed_in` say the same to Prometheus.
 */
@Component
class Health(
    private val api: PatreonApi,
    private val browser: Browser,
    private val properties: PatreonProperties,
    private val clock: Clock,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var latest = GatewayHealth(false, "Not checked yet", false, null, "Not checked yet", null)

    val last: GatewayHealth get() = latest

    init {
        Gauge.builder("patreon_gateway_api_connected") { if (last.api) 1.0 else 0.0 }.register(registry)
        Gauge.builder("patreon_gateway_signed_in") { if (last.signedIn) 1.0 else 0.0 }.register(registry)
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 60 * 60 * 1000)
    fun check(): GatewayHealth {
        val apiError = runCatching { api.campaignId() }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        val (email, browserError) = runCatching { browser.signedInAs() }.fold(
            onSuccess = { email ->
                when {
                    email == null -> null to "The bot isn't signed in to Patreon: run patreon-gateway/login.sh"
                    !email.equals(properties.account, ignoreCase = true) ->
                        email to "The browser is signed in as $email, not ${properties.account}"
                    else -> email to null
                }
            },
            onFailure = { null to (it.message ?: it.javaClass.simpleName) },
        )
        latest = GatewayHealth(apiError == null, apiError, browserError == null, email, browserError, clock.instant())
        if (!latest.ok) log.warn("Patreon: API {}, browser {}", apiError ?: "ok", browserError ?: "ok")
        return latest
    }
}
