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
 * workflows start one as a child, e.g. for an episode's chapters once it's transcribed (Transcription.kt).
 */
@WorkflowInterface
interface AgentWorkflow {
    @WorkflowMethod
    fun run(task: AgentTask): AgentAnswer
}

@WorkflowImpl(taskQueues = [WORKFLOWS_QUEUE])
class AgentWorkflowImpl : AgentWorkflow {

    override fun run(task: AgentTask): AgentAnswer {
        // MCP's tools are named after the server, e.g. metaos_get_episode
        val tools = McpToolCallback.fromMcpClient(ActivityMcpClient.create(MCP_CALLS))
            .filter { tool -> task.tools.any { tool.toolDefinition.name() == it || tool.toolDefinition.name().endsWith("_$it") } }
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
        val MODEL_CALLS: ActivityOptions = ActivityOptions.newBuilder(ActivityChatModel.defaultActivityOptions())
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(
                RetryOptions.newBuilder(ActivityChatModel.defaultActivityOptions().retryOptions)
                    .setMaximumAttempts(4)
                    .setInitialInterval(Duration.ofSeconds(10))
                    .build()
            )
            .build()

        val MCP_CALLS: ActivityOptions = ActivityOptions.newBuilder(ActivityMcpClient.defaultActivityOptions())
            .setStartToCloseTimeout(Duration.ofMinutes(1))
            .build()
    }
}

/**
 * The episode's chapters and summary, from its transcript, posted as a comment on it, in the [language] it's spoken in
 * (BCP-47): the first thing MetaOS's agents do, once an episode is transcribed (Transcription.kt)
 */
fun chaptersTask(episode: String, language: String) = AgentTask(
    system = """
        You are an editor at Ogrodje, a Slovenian podcast and YouTube show about software, technology and the people who
        make them, mostly in Slovenian, and in English for guests from abroad. You work in MetaOS, Ogrodje's system,
        through its tools. You write in the language an episode is spoken in, as its viewers will read it.
    """.trimIndent(),
    prompt = """
        Episode $episode has just been transcribed. Read the episode (get_episode), its people (list_episode_people) and
        its transcript (get_episode_transcript). Speech-to-Text doesn't tell the speakers apart, and gets names, products
        and technical terms wrong: spell them as the episode's title, summary and people do, or as they're known.

        Then post one comment on the episode (comment_on_episode), in Markdown, in ${languageName(language)}, the
        language the episode is spoken in, with two headings of its own:
        - Chapters: 6 to 15 of them, each on its own line as `h:mm:ss Title`, the first at 0:00:00, in order, each where
          its topic starts in the transcript, titled in a few words, as YouTube's chapters are;
        - Summary: what the episode is about, in three to five sentences.

        Post exactly one comment, then answer with its ID and nothing else.
    """.trimIndent(),
    tools = listOf("get_episode", "list_episode_people", "get_episode_transcript", "comment_on_episode"),
)
