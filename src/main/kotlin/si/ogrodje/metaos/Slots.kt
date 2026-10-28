package si.ogrodje.metaos

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.spring.boot.ActivityImpl
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalExternalWorkflowException
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.Duration

/** Agents' turns (Agents.kt): two at once, as each reads a whole transcript through a model, which costs */
const val AGENTS_SLOTS = "agents"

// For what metaos.slots.<resource> doesn't name, e.g. the tests' providers
private const val DEFAULT_SLOTS = 5

/**
 * A resource's turns: how many may hold one at once, the workflows that do, and those waiting, first first; and how often
 * it checks that those holding one still run (metaos.slots.check-every), in milliseconds
 */
data class SlotsState(
    val limit: Int,
    val holders: List<String> = emptyList(),
    val waiting: List<String> = emptyList(),
    val checkEveryMillis: Long = Duration.ofMinutes(10).toMillis(),
)

/**
 * Turns at something only so many may do at once, e.g. a speech-to-text provider's transcriptions or agents, whichever
 * pod asks: a workflow of its own for each (slots-<resource>), which gives turns out in the order they're asked for,
 * up to its limit (metaos.slots.<resource>), and the next when one is given back. Workflows wait for theirs without
 * holding anything ([withSlot]); one that ends without giving its turn back (terminated) has it taken back at the next
 * check (metaos.slots.check-every, ten minutes). It ends when no one holds or waits for a turn, and the next ask starts
 * it again.
 */
@WorkflowInterface
interface SlotsWorkflow {
    @WorkflowMethod
    fun run(state: SlotsState)

    /** [holder] (a workflow's ID) wants a turn; [limit] and [checkEveryMillis] are as configured now */
    @SignalMethod
    fun acquire(holder: String, limit: Int, checkEveryMillis: Long)

    /** [holder] is done with its turn, or no longer wants one */
    @SignalMethod
    fun release(holder: String)

    @QueryMethod
    fun state(): SlotsState

    companion object {
        fun workflowId(resource: String) = "slots-$resource"
    }
}

/** A workflow that waits for turns ([withSlot]): told when it has one, at which resource */
interface SlotHolder {
    @SignalMethod
    fun granted(resource: String)
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class SlotsWorkflowImpl : SlotsWorkflow {

    private var limit = DEFAULT_SLOTS
    private var checkEvery = Duration.ofMinutes(10)
    private val holders = LinkedHashSet<String>()
    private val waiting = ArrayDeque<String>()
    private var changed = false

    private val activities = Workflow.newActivityStub(
        SlotActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build(),
    )

    override fun run(state: SlotsState) {
        limit = state.limit
        checkEvery = Duration.ofMillis(state.checkEveryMillis)
        holders += state.holders
        waiting += state.waiting.filterNot(holders::contains)
        while (true) {
            grant()
            if (holders.isEmpty() && waiting.isEmpty()) return
            // A history that's grown long goes on in a new run, with the turns as they are
            if (Workflow.getInfo().isContinueAsNewSuggested || Workflow.getInfo().historyLength > MAX_HISTORY) {
                Workflow.continueAsNew(state())
            }
            // Something new, or else, every so often, whether those holding a turn still run
            if (!Workflow.await(checkEvery) { changed } && holders.isNotEmpty()) {
                activities.finished(holders.toList()).forEach(holders::remove)
            }
            changed = false
        }
    }

    // To those first in line, while there are turns; one that's gone by then doesn't get it
    private fun grant() {
        val resource = Workflow.getInfo().workflowId.removePrefix("slots-")
        while (holders.size < limit && waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            holders += next
            try {
                Workflow.newUntypedExternalWorkflowStub(next).signal("granted", resource)
            } catch (gone: SignalExternalWorkflowException) {
                holders -= next
            }
        }
    }

    override fun acquire(holder: String, limit: Int, checkEveryMillis: Long) {
        this.limit = limit
        checkEvery = Duration.ofMillis(checkEveryMillis)
        if (holder in holders) {
            // Asked again by one holding a turn: another run of the same workflow ID, e.g. a file transcribed again after
            // one that was terminated, or the same run asking twice; first in line, it has the turn again at once
            holders -= holder
            waiting.addFirst(holder)
        } else if (holder !in waiting) {
            waiting += holder
        }
        changed = true
    }

    override fun release(holder: String) {
        holders -= holder
        waiting -= holder
        changed = true
    }

    override fun state() = SlotsState(limit, holders.toList(), waiting.toList(), checkEvery.toMillis())

    private companion object {
        const val MAX_HISTORY = 2000
    }
}

/**
 * In a workflow that's a [SlotHolder], whose granted signal adds to [granted]: runs [block] once it has a turn at
 * [resource], which it asks for (starting slots-<resource> if it isn't running), waiting on Temporal, which holds
 * nothing; and gives the turn back after, also when [block] fails or the workflow is cancelled, or it stops waiting
 */
fun <T> withSlot(resource: String, granted: Set<String>, block: () -> T): T {
    val self = Workflow.getInfo().workflowId
    try {
        Workflow.newActivityStub(SlotActivities::class.java, SLOT_CALLS).requestSlot(resource, self)
        Workflow.await { resource in granted }
        return block()
    } finally {
        Workflow.newDetachedCancellationScope {
            try {
                Workflow.newExternalWorkflowStub(SlotsWorkflow::class.java, SlotsWorkflow.workflowId(resource)).release(self)
            } catch (gone: SignalExternalWorkflowException) {
                // Its slots ended, so it held none
            }
        }.run()
    }
}

private val SLOT_CALLS: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build()

@ActivityInterface
interface SlotActivities {
    /** Asks [resource]'s slots for a turn for [holder], starting them if they aren't running, at their limit */
    fun requestSlot(resource: String, holder: String)

    /** Those of [holders] that no longer run */
    fun finished(holders: List<String>): List<String>
}

@Component
@ConditionalOnTemporal
@ActivityImpl(taskQueues = [WORKFLOWS_QUEUE])
class SlotActivitiesImpl(
    private val client: WorkflowClient,
    private val environment: Environment,
    @Value("\${metaos.slots.check-every:10m}") private val checkEvery: Duration,
) : SlotActivities {

    override fun requestSlot(resource: String, holder: String) {
        val limit = environment.getProperty("metaos.slots.$resource", Int::class.java, DEFAULT_SLOTS)
        val check = checkEvery.toMillis()
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(WORKFLOWS_QUEUE)
            .setWorkflowId(SlotsWorkflow.workflowId(resource))
            .build()
        client.newUntypedWorkflowStub("SlotsWorkflow", options)
            .signalWithStart("acquire", arrayOf<Any>(holder, limit, check), arrayOf<Any>(SlotsState(limit, checkEveryMillis = check)))
    }

    override fun finished(holders: List<String>) = holders.filter { holder ->
        try {
            client.newUntypedWorkflowStub(holder).describe().status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        } catch (e: WorkflowNotFoundException) {
            true
        }
    }
}
