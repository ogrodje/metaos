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
                override fun prepareDigest(monday: String) = listOf("one", "two").map { DigestMessage(it, emptyList()) }
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
