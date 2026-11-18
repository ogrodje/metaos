package si.ogrodje.scrapers

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.temporal.activity.Activity
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.failure.ApplicationFailure
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URISyntaxException

/** The task queue the scrapers work on: MetaOS's events workflow's scraping (EventsSync.kt in MetaOS) */
const val SCRAPERS_QUEUE = "scrapers"

/**
 * Scraping, as an activity of MetaOS's events workflow, which Temporal keeps and retries: MetaOS has the same
 * interface, by the same name, with its own types of the same shape. The scrapers only read the web and answer with
 * what they found; MetaOS keeps it.
 */
@ActivityInterface
interface ScraperActivities {
    @ActivityMethod(name = "ScrapeEvents")
    fun scrape(request: ScrapeRequest): ScrapeResult
}

/**
 * A site that doesn't answer, or answers 5xx or 429, and a page a parser doesn't know (e.g. a site that changed), fail
 * so the workflow tries again; a URL that isn't one fails for good. Several at once (application.properties), the
 * browser's one page at a time.
 */
@Component("scraperActivities")
class ScraperActivitiesImpl(private val browser: Browser) : ScraperActivities {
    private val log = LoggerFactory.getLogger(javaClass)
    private val web = HttpWeb()

    override fun scrape(request: ScrapeRequest): ScrapeResult {
        val attempt = runCatching { Activity.getExecutionContext().info.attempt }.getOrDefault(1)
        try {
            return scrape(request, web, browser).also {
                log.info("{} for {}: {} events ({})", request.url, request.organisationName, it.events.size, it.source ?: "no parser")
            }
        } catch (e: Exception) {
            val final = e is URISyntaxException || e is IllegalArgumentException ||
                (e is WebException && e.status in 400..499 && e.status != 429)
            log.warn("{} for {} (try {}) failed{}: {}", request.url, request.organisationName, attempt, if (final) "" else ", to try again", e.message)
            val message = e.message ?: e.javaClass.simpleName
            throw if (final) ApplicationFailure.newNonRetryableFailure(message, e.javaClass.simpleName)
            else ApplicationFailure.newFailure(message, if (e is IOException) "IOException" else e.javaClass.simpleName)
        }
    }
}

@Configuration
class WorkflowsConfig {
    // Activities' arguments are Kotlin data classes, which Jackson 2's Kotlin module constructs, with instants as
    // ISO 8601; as MetaOS's converter
    @Bean
    fun temporalDataConverter(): DataConverter = DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
        JacksonJsonPayloadConverter(
            JacksonJsonPayloadConverter.newDefaultObjectMapper().registerModule(KotlinModule.Builder().build()).registerModule(JavaTimeModule())
        )
    )
}
