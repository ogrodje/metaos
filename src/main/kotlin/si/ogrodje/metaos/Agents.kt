package si.ogrodje.metaos

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.springai.chat.TemporalChatClient
import io.temporal.springai.mcp.ActivityMcpClient
import io.temporal.springai.mcp.McpToolCallback
import io.temporal.springai.model.ActivityChatModel
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.springframework.ai.chat.prompt.ChatOptions
import java.time.Duration

/**
 * Something for an agent to do: what it is ([system]) and what to do now ([prompt]), with which of MetaOS's MCP tools
 * ([tools], by name, e.g. get_episode; no others), and, other than the default (metaos.agents.model), which model, as
 * OpenRouter names it, e.g. anthropic/claude-sonnet-5.5
 */
data class AgentTask(
    val system: String,
    val prompt: String,
    val tools: List<String>,
    val model: String? = null,
)

/** What the agent answered, once it was done */
data class AgentAnswer(val text: String)

/**
 * An agent, as a workflow: a model, through OpenRouter, using MetaOS's own MCP tools until it's done what it was asked
 * (Spring AI's ChatClient, with Temporal's plugin). Each model call and each tool call is an activity, so an agent that
 * a restart interrupts goes on from its last step, with what it had, rather than doing it all again; the tools are
 * called as metaos-agents (Workflows.kt), so they check, and the history says, what agents do as for anyone. Other
 * workflows start one, e.g. the Editor, to come. So many work at once (Slots.kt, metaos.slots.agents, 2), as each
 * may read a whole transcript through a model, which costs; the rest wait their turn.
 */
@WorkflowInterface
interface AgentWorkflow : SlotHolder {
    @WorkflowMethod
    fun run(task: AgentTask): AgentAnswer
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class AgentWorkflowImpl : AgentWorkflow {

    private val granted = mutableSetOf<String>()

    override fun granted(resource: String) {
        granted += resource
    }

    override fun run(task: AgentTask): AgentAnswer = withSlot(AGENTS_SLOTS, granted) { work(task) }

    private fun work(task: AgentTask): AgentAnswer {
        // MCP's tools are named after the server, e.g. metaos_get_episode
        val tools = McpToolCallback.fromMcpClient(ActivityMcpClient.create(MCP_CALLS))
            .filter { tool ->
                task.tools.any {
                    tool.toolDefinition.name() == it || tool.toolDefinition.name().endsWith("_$it")
                }
            }
        val missing = task.tools.filter { name -> tools.none { it.toolDefinition.name().endsWith(name) } }
        require(missing.isEmpty()) { "MetaOS has no MCP tools $missing" }

        val client = TemporalChatClient.builder(ActivityChatModel.forDefault(MODEL_CALLS))
            .defaultSystem(task.system)
            .defaultTools(*tools.toTypedArray())
            .build()
        val prompt = client.prompt().user(task.prompt)
        task.model?.let { prompt.options(ChatOptions.builder().model(it)) }
        return AgentAnswer(prompt.call().content().orEmpty())
    }

    private companion object {
        // A long transcript makes for a long call; a model that's down is tried again a few times, an invalid request isn't
        val MODEL_CALLS: ActivityOptions =
            ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(
                    RetryOptions.newBuilder(ActivityChatModel.defaultActivityOptions().retryOptions)
                        .setMaximumAttempts(4)
                        .setInitialInterval(Duration.ofSeconds(10))
                        .build()
                )
                .build()

        val MCP_CALLS: ActivityOptions =
            ActivityOptions.newBuilder(ActivityMcpClient.defaultActivityOptions())
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .build()
    }
}
