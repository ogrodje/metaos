package si.ogrodje.metaos

import io.getunleash.DefaultUnleash
import io.getunleash.Unleash
import io.getunleash.UnleashContext
import io.getunleash.UnleashException
import io.getunleash.event.ClientFeaturesResponse
import io.getunleash.event.UnleashSubscriber
import io.getunleash.util.UnleashConfig
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * MetaOS's feature flags, from Unleash (CLAUDE.md, "Feature flags"): a change ships turned off and is turned on in
 * Unleash's UI once it's ready. Unleash's SDK keeps the flags in MetaOS and asks Unleash what changed every 15 s, so a
 * check is a lookup, not a request. Without Unleash (no UNLEASH_API_TOKEN) a flag is its default; while Unleash is down,
 * what it was last, which the SDK also keeps in a file for the next start, else its default.
 */
interface Flags {
    /** Whether the flag [name] is on, for whoever's signed in (their Keycloak ID, for Unleash's gradual rollouts) */
    fun enabled(name: String, default: Boolean = false): Boolean
}

/** Unleash is off until MetaOS has a token for it (UNLEASH_API_TOKEN) */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${metaos.unleash.api-token:}' != ''")
annotation class ConditionalOnUnleash

@Component
class UnleashFlags(
    @Value("\${metaos.unleash.url:http://localhost:4242/api}") url: String,
    @Value("\${metaos.unleash.api-token:}") token: String,
    @Value("\${metaos.unleash.app-name:metaos}") appName: String,
    // The SDK's own, ${java.io.tmpdir}/unleash-<app>-repo.json, unless tests keep theirs apart
    @Value("\${metaos.unleash.backup-file:}") backupFile: String,
) : Flags, DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** When Unleash last answered, and what went wrong since, if anything */
    class Status(val answeredAt: Instant?, val flags: Int, val error: String?)

    @Volatile
    private var last = Status(null, 0, null)

    val status get() = last

    private val unleash: Unleash? = token.takeIf { it.isNotBlank() }?.let {
        val config = UnleashConfig.builder()
            .appName(appName)
            .unleashAPI(url)
            .apiKey(it)
            // Asked once while MetaOS starts, so it starts with the flags as they are: briefly, as Unleash being down
            // only leaves them their defaults, which mustn't hold MetaOS up
            .synchronousFetchOnInitialisation(true)
            .startupExceptionHandler { e -> failed(e) }
            .fetchTogglesConnectTimeout(Duration.ofSeconds(2))
            .fetchTogglesReadTimeout(Duration.ofSeconds(5))
            .subscriber(object : UnleashSubscriber {
                override fun togglesFetched(toggleResponse: ClientFeaturesResponse) {
                    if (toggleResponse.status == ClientFeaturesResponse.Status.UNAVAILABLE) {
                        failed(UnleashException("Unleash answered ${toggleResponse.httpStatusCode}", null))
                    } else {
                        val flags = if (toggleResponse.status == ClientFeaturesResponse.Status.CHANGED) toggleResponse.features.size
                        else status.flags
                        last = Status(Instant.now(), flags, null)
                    }
                }

                override fun onError(unleashException: UnleashException) = failed(unleashException)
            })
            .apply { if (backupFile.isNotBlank()) backupFile(backupFile) }
            .build()
        DefaultUnleash(config)
    }

    @Synchronized
    private fun failed(e: UnleashException) {
        val error = e.message ?: e.toString()
        // Once, not every 15 s while it's down
        if (status.error == null) logger.warn("Unleash: {}; the flags stay as they were, else their defaults", error)
        last = Status(status.answeredAt, status.flags, error)
    }

    override fun enabled(name: String, default: Boolean): Boolean {
        val unleash = unleash ?: return default
        val user = (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.subject
        val context = UnleashContext.builder().apply { user?.let { userId(it) } }.build()
        return unleash.isEnabled(name, context, default)
    }

    override fun destroy() {
        unleash?.shutdown()
    }
}

/** Whether MetaOS has Unleash's flags as they are: in the health check and metaos_unleash_connected, as its other connections */
@Component
@ConditionalOnUnleash
class UnleashHealthIndicator(private val flags: UnleashFlags, registry: MeterRegistry) : HealthIndicator {

    init {
        Gauge.builder("metaos.unleash.connected") { if (connected()) 1 else 0 }
            .description("1 while MetaOS has its feature flags from Unleash")
            .register(registry)
    }

    private fun connected() = flags.status.let { it.answeredAt != null && it.error == null }

    override fun health(): Health {
        val status = flags.status
        val health = if (connected()) Health.up() else Health.down().withDetail("error", status.error ?: "not answered yet")
        return health.withDetail("flags", status.flags)
            .apply { status.answeredAt?.let { withDetail("answeredAt", it.toString()) } }
            .build()
    }
}
