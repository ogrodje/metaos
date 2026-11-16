package si.ogrodje.metaos

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.concurrent.thread

// Where ogrodje.si had them, for where each logo came from
private const val OGRODJE_SITE = "https://github.com/ogrodje/ogrodje-site/blob/f74d26261de239521a5dabd9a514e0e42b568bb8/src/assets/icons"

/** The logos of the organisations V35__organisations.sql seeded from ogrodje.si's footer: their files here, and there */
private val SEEDED = listOf(
    Triple("6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0001", "kaldi.png", "kaldi-logo-symbol.svg"),
    Triple("6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0002", "visionect.png", "Visionect_logo-icon_green@3x.png"),
    Triple("6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0003", "koofr.png", "koofr_512px.png"),
    Triple("6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0004", "ngen-group.png", "ngen-group.png"),
    Triple("6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0005", "monitor.png", "monitor-logo-2010.png"),
    Triple("6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0006", "spletne-urice-maribor.png", "wwwhmb-logo.png"),
    Triple("6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0007", "racunalniski-muzej.jpg", "racunalniski-muzej-square.jpg"),
    Triple("6f0b7c1e-0a3d-4c55-9b35-0d1c6a2b0008", "slo-ai.png", "sloai-square.png"),
)

/**
 * Gives the organisations seeded from ogrodje.si their logos, from the files it had (resources/organisations; Kaldi's
 * SVG as a PNG), once the public bucket is there: a migration can't publish pictures. Only to one nobody has changed
 * yet, as its history has no change, so a logo someone took off or replaced doesn't come back. Both pods may do it at
 * once; the same file is the same picture, so one of them finds the other's.
 */
@Component
@ConditionalOnMedia
class OrganisationLogos(
    private val images: Images,
    private val organisations: OrganisationsRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val transactions: TransactionTemplate,
    // Off in tests, which publish them when they mean to
    @Value("\${metaos.organisations.seed-logos:true}") private val onStart: Boolean,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Not to keep the app from being ready
    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        if (onStart) thread(name = "organisation-logos", isDaemon = true) { publish() }
    }

    fun publish() {
        SEEDED.forEach { (id, file, original) ->
            runCatching {
                transactions.executeWithoutResult {
                    val organisation = organisations.findByIdOrNull(UUID.fromString(id)) ?: return@executeWithoutResult
                    val untouched = jdbcTemplate.queryForObject(
                        "select count(*) = 0 from audit_log where table_name = 'organisations' and row_id = ?",
                        Boolean::class.java, organisation.id,
                    )
                    if (organisation.logoImageId != null || untouched != true) return@executeWithoutResult
                    val bytes = ClassPathResource("organisations/$file").inputStream.use { it.readAllBytes() }
                    organisation.logoImageId = images.publish("$OGRODJE_SITE/$original", file, bytes).id
                }
            }.onFailure { log.warn("Couldn't give organisation {} its logo {}", id, file, it) }
        }
    }
}
