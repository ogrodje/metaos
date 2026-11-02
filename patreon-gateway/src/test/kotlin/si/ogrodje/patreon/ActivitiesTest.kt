package si.ogrodje.patreon

import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestActivityEnvironment
import io.temporal.testing.TestEnvironmentOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The activities MetaOS's workflows call, against a Patreon web app that answers as it's told to */
class ActivitiesTest {
    private val mapper = JsonMapper.builder().build()

    // What each call was given, and what it does: makes a draft, uploads a file, or fails as [failing] says
    private val calls = CopyOnWriteArrayList<String>()
    private var failing: Exception? = null

    private val web = object : PatreonWeb(
        Browser(PatreonProperties(), mapper),
        PatreonApi(PatreonProperties(), TokenStore(PatreonProperties(), mapper), mapper, java.time.Clock.systemUTC()),
        Media(PatreonProperties()),
        mapper,
    ) {
        override fun createDraft(job: String, spec: PostSpec, created: String?, onCreated: (String) -> Unit): WebPost {
            calls += "create $job ${created ?: "new"}"
            val id = created ?: "173396086".also(onCreated)
            failing?.let { throw it }
            return WebPost(id, spec.title, null, "https://www.patreon.com/posts/$id/edit", null, null, true)
        }

        override fun update(job: String, id: String, spec: PostSpec): WebPost {
            calls += "update $id"
            failing?.let { throw it }
            return WebPost(id, spec.title, null, "https://www.patreon.com/posts/$id/edit", null, null, true)
        }

        override fun attachMedia(
            job: String, id: String, request: MediaRequest, uploaded: UploadedMedia?, onUploaded: (UploadedMedia) -> Unit, ready: Duration,
        ): Map<String, Any?> {
            calls += "attach $id ${uploaded?.id ?: "upload"}"
            val media = uploaded ?: UploadedMedia("767881200", request.kind, "S05E08.mp3", 1000, "uploaded").also(onUploaded)
            failing?.let { throw it }
            return mapOf("media" to media.copy(state = "ready"))
        }
    }

    private val environment = TestActivityEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(WorkflowsConfig().temporalDataConverter()).build())
            .build(),
    ).apply { registerActivitiesImplementations(PatreonActivitiesImpl(web)) }

    private val activities = environment.newActivityStub(
        PatreonActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build()).build(),
    )

    @AfterEach
    fun close() = environment.close()

    @Test
    fun `a draft is made, and its ID is in the heartbeat as soon as Patreon has it`() {
        val beats = CopyOnWriteArrayList<String>()
        environment.setActivityHeartbeatListener(String::class.java) { beats += it }
        val post = activities.createDraft(PostSpec("S05E08", "Text", Access(Access.Kind.PATRONS)))
        assertEquals("173396086", post.id)
        assertEquals(listOf("173396086"), beats)
        assertTrue(calls.single().endsWith(" new"), calls.toString())
    }

    @Test
    fun `a draft a try before made is the one the next try goes on with`() {
        environment.setHeartbeatDetails("173396086")
        activities.createDraft(PostSpec("S05E08"))
        assertTrue(calls.single().endsWith(" 173396086"), calls.toString())
    }

    @Test
    fun `a file a try before uploaded isn't uploaded again`() {
        environment.setHeartbeatDetails(UploadedMedia("767881200", MediaKind.AUDIO, "S05E08.mp3", 1000, "uploaded"))
        val result = activities.attachMedia("173396086", MediaRequest("https://example.com/S05E08.mp3", MediaKind.AUDIO))
        assertEquals("attach 173396086 767881200", calls.single())
        assertEquals("ready", (result["media"] as Map<*, *>)["state"])
    }

    @Test
    fun `Patreon turning the gateway away, or its session run out, is tried again`() {
        listOf(PatreonBusyException("Patreon is turning the gateway away for now (429)"), SignedOutException("Signed out"), PatreonException("Patreon answered 502", 502))
            .forEach { e ->
                failing = e
                val failure = failure { activities.update("173396086", PostSpec(title = "S05E08")) }
                assertFalse(failure.isNonRetryable, e.toString())
                assertEquals(e.javaClass.simpleName, failure.type)
                assertEquals(e.message, failure.originalMessage)
            }
    }

    @Test
    fun `what trying again won't change fails for good`() {
        listOf(IllegalArgumentException("The campaign has no tier 1"), PatreonException("Patreon answered 422", 422), PatreonException("Patreon couldn't process the file"))
            .forEach { e ->
                failing = e
                assertTrue(failure { activities.update("173396086", PostSpec(title = "S05E08")) }.isNonRetryable, e.toString())
            }
    }

    @Test
    fun `a draft for some tiers needs them, before Patreon is asked`() {
        assertTrue(failure { activities.createDraft(PostSpec("S05E08", access = Access(Access.Kind.TIERS))) }.isNonRetryable)
        assertNull(calls.firstOrNull())
    }

    private fun failure(call: () -> Unit): ApplicationFailure = assertThrows<ActivityFailure> { call() }.cause as ApplicationFailure
}
