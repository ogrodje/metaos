package si.ogrodje.patreon

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.temporal.activity.Activity
import io.temporal.activity.ActivityExecutionContext
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.client.ActivityCompletionException
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.failure.ApplicationFailure
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The task queue the gateway works on: MetaOS's workflows' changes in Patreon (PatreonPosts.kt in MetaOS) */
const val PATREON_QUEUE = "patreon"

/**
 * Changes in Patreon's web app, as activities of MetaOS's workflows, which Temporal keeps and retries through restarts
 * of either: MetaOS has the same interface, by the same names, with its own types of the same shape. Nothing here
 * publishes: a person does, in Patreon's editor.
 */
@ActivityInterface
interface PatreonActivities {
    /** A new draft, and the post it is */
    @ActivityMethod(name = "PatreonCreateDraft")
    fun createDraft(spec: PostSpec): WebPost

    /** Changes a post as [spec] says, a draft or a published one; what it leaves null stays */
    @ActivityMethod(name = "PatreonUpdatePost")
    fun update(id: String, spec: PostSpec): WebPost

    /** Downloads [request]'s file and puts it on the draft as its audio or video: the post, and its media */
    @ActivityMethod(name = "PatreonAttachMedia")
    fun attachMedia(id: String, request: MediaRequest): Map<String, Any?>

    /** Downloads [request]'s picture and makes it the post's image, its thumbnail: the post, and its media */
    @ActivityMethod(name = "PatreonSetImage")
    fun setImage(id: String, request: ImageRequest): Map<String, Any?>

    @ActivityMethod(name = "PatreonDeletePost")
    fun delete(id: String): Map<String, Any?>
}

/**
 * The activities, one at a time and so many a second (application.properties, spring.temporal.workers), as there's one
 * browser and Patreon turns the gateway away when it's asked too often. What Patreon turned away for now
 * ([PatreonBusyException]), a browser that isn't there or signed out, and Patreon's own errors (5xx) fail so the
 * workflow tries again, later and later; what trying again won't change (a request that's wrong, Patreon refusing it)
 * fails for good. What a try did in Patreon, a draft made or a file or picture uploaded, is in its heartbeat, so the next try goes
 * on from there instead of doing it again, also after a restart.
 */
@Component("patreonActivities")
class PatreonActivitiesImpl(private val web: PatreonWeb) : PatreonActivities {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun createDraft(spec: PostSpec): WebPost = activity { context, name ->
        require(spec.access?.let { it.kind != Access.Kind.TIERS || it.tiers.isNotEmpty() } ?: true) { "Access TIERS needs the tiers' IDs" }
        val created = context.getHeartbeatDetails(String::class.java).orElse(null)
        web.createDraft(name, spec, created) { context.heartbeat(it) }
    }

    override fun update(id: String, spec: PostSpec): WebPost = activity { _, name -> web.update(name, id, spec) }

    override fun attachMedia(id: String, request: MediaRequest): Map<String, Any?> = activity { context, name ->
        val uploaded = context.getHeartbeatDetails(UploadedMedia::class.java).orElse(null)
        // An episode's video takes long to download and send: the activity says it's alive meanwhile, with what it uploaded
        val progress = AtomicReference(uploaded)
        val beat = Executors.newSingleThreadScheduledExecutor { Thread(it, "heartbeat-$name").apply { isDaemon = true } }
        beat.scheduleAtFixedRate({ runCatching { context.heartbeat(progress.get()) } }, 0, 20, TimeUnit.SECONDS)
        try {
            web.attachMedia(name, id, request, uploaded, onUploaded = { progress.set(it); context.heartbeat(it) })
        } finally {
            beat.shutdownNow()
        }
    }

    override fun setImage(id: String, request: ImageRequest): Map<String, Any?> = activity { context, name ->
        val uploaded = context.getHeartbeatDetails(String::class.java).orElse(null)
        web.setImage(name, id, request, uploaded) { context.heartbeat(it) }
    }

    override fun delete(id: String): Map<String, Any?> = activity { _, name -> web.delete(name, id) }

    // Named after the workflow and the try, which what the page showed is kept as (Browser.use), and failed as Temporal
    // tells a try again from the end
    private fun <T> activity(work: (ActivityExecutionContext, String) -> T): T {
        val context = Activity.getExecutionContext()
        val info = context.info
        val name = "${info.workflowId}-${info.attempt}".replace(Regex("[^A-Za-z0-9-]"), "-")
        try {
            return work(context, name)
        } catch (e: ActivityCompletionException) {
            // Cancelled, or timed out, as Temporal says: as it is
            throw e
        } catch (e: Exception) {
            val final = when (e) {
                is PatreonBusyException, is SignedOutException, is IOException -> false
                is PatreonException -> e.status == null || e.status < 500
                // Else Playwright's timeouts and the like, which may well work the next time
                else -> e is IllegalArgumentException
            }
            log.warn("{} ({}, try {}) failed{}: {}", info.activityType, info.workflowId, info.attempt, if (final) "" else ", to try again", e.message)
            val message = e.message ?: e.javaClass.simpleName
            val type = e.javaClass.simpleName
            throw if (final) ApplicationFailure.newNonRetryableFailure(message, type) else ApplicationFailure.newFailure(message, type)
        }
    }
}

@Configuration
class WorkflowsConfig {
    // Activities' arguments are Kotlin data classes, which Jackson 2's Kotlin module constructs; as MetaOS's converter
    @Bean
    fun temporalDataConverter(): DataConverter = DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
        JacksonJsonPayloadConverter(JacksonJsonPayloadConverter.newDefaultObjectMapper().registerModule(KotlinModule.Builder().build()))
    )
}
