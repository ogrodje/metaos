package si.ogrodje.metaos

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class EventsDigestWorkflowTest {
    @org.junit.jupiter.api.Test
    fun `live lookup survives a transient outage and a bad batch does not discard later batches`() {
        val options = TestEnvironmentOptions.newBuilder()
            .setInitialTime(java.time.Instant.parse("2026-03-29T17:00:00Z"))
            .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                .setDataConverter(WorkflowsConfig().temporalDataConverter()).build()).build()
        TestWorkflowEnvironment.newInstance(options).use { env ->
            val calls = CopyOnWriteArrayList<String>()
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val worker = env.newWorker(WORKFLOWS_QUEUE)
            worker.registerWorkflowImplementationTypes(EventsDigestWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(object : DigestActivities {
                override fun preparePreview(monday: String) = error("Unexpected preview")
                override fun prepareDigest(monday: String): List<DigestMessage> {
                    if (attempts.incrementAndGet() <= 5) error("Database temporarily unavailable")
                    return listOf("one", "two", "three").map { DigestMessage(it, emptyList()) }
                }
                override fun postDigest(message: DigestMessage, sample: Boolean): Long {
                    calls.add(message.content)
                    if (message.content == "two") throw ApplicationFailure.newNonRetryableFailure("bad embed", "InvalidBatch")
                    return 0
                }
            })
            env.start()
            env.workflowClient.newWorkflowStub(EventsDigestWorkflow::class.java,
                WorkflowOptions.newBuilder().setTaskQueue(WORKFLOWS_QUEUE).build()).publish("2026-03-30", false)
            assertEquals(6, attempts.get())
            assertEquals(listOf("one", "two", "three"), calls.toList())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `only confirmed rejections retry without a channel check`(alreadyPosted: Boolean) {
        val options = TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(
            WorkflowClientOptions.newBuilder().setDataConverter(WorkflowsConfig().temporalDataConverter()).build()
        ).build()
        TestWorkflowEnvironment.newInstance(options).use { env ->
            val calls = CopyOnWriteArrayList<String>()
            val worker = env.newWorker(WORKFLOWS_QUEUE)
            worker.registerWorkflowImplementationTypes(EventsDigestWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(object : DigestActivities {
                override fun prepareDigest(monday: String) = error("Unexpected live digest")
                override fun preparePreview(monday: String) = listOf("one", "two").map { DigestMessage(it, emptyList()) }
                override fun postDigest(message: DigestMessage, sample: Boolean): Long {
                    check(sample)
                    calls.add(message.content)
                    return when (calls.size) {
                        1 -> 1000
                        2 -> throw ApplicationFailure.newNonRetryableFailure("lost receipt", "Unconfirmed")
                        else -> 0
                    }
                }
            })
            env.start()
            val workflow = env.workflowClient.newWorkflowStub(EventsDigestWorkflow::class.java,
                WorkflowOptions.newBuilder().setTaskQueue(WORKFLOWS_QUEUE).build())
            WorkflowClient.start(workflow::publish, "2026-03-30", true)
            for (attempt in 1..50) {
                env.sleep(Duration.ofSeconds(1))
                if (workflow.pendingBatch() == 1) break
            }
            assertEquals(1, workflow.pendingBatch())
            assertEquals(listOf("one", "one"), calls.toList())
            workflow.confirmBatch(1, alreadyPosted)
            WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, Void::class.java)
            assertEquals(if (alreadyPosted) listOf("one", "one", "two") else listOf("one", "one", "one", "two"), calls.toList())
        }
    }
}
