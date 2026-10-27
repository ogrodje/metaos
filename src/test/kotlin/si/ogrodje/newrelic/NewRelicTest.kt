// Not in si.ogrodje.metaos, where UsageToolsTest and ToolHintsTest would take its tools for MetaOS's
package si.ogrodje.newrelic

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphqlErrorBuilder
import graphql.Scalars
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import org.junit.jupiter.api.Test
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.aop.framework.ProxyFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.BindException
import si.ogrodje.metaos.ErrorReport
import si.ogrodje.metaos.GraphQLErrorReports
import si.ogrodje.metaos.mcpToolErrorReports
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NewRelicTest {

    data class Reported(val error: Throwable, val attributes: Map<String, String>, val expected: Boolean)

    private val reported = mutableListOf<Reported>()
    private val report: ErrorReport = { error, attributes, expected -> reported += Reported(error, attributes, expected) }

    open class Tools {
        @McpTool(name = "broken_tool", description = "Fails as something it calls is down")
        open fun broken(): String = error("Dropbox is down")

        @McpTool(name = "picky_tool", description = "Fails as it's asked the wrong thing")
        open fun picky(): String {
            require(false) { "No such episode" }
            return "found"
        }

        @McpTool(description = "Works")
        open fun fine() = "ok"
    }

    @Test
    fun `reports what an MCP tool failed with, as it was thrown, the caller's mistake as expected`() {
        val tools = ProxyFactory(Tools()).apply { isProxyTargetClass = true; addAdvisor(mcpToolErrorReports(report)) }.proxy as Tools

        val down = assertFailsWith<IllegalStateException> { tools.broken() }
        val wrong = assertFailsWith<IllegalArgumentException> { tools.picky() }
        assertEquals("ok", tools.fine())

        assertEquals(
            listOf(
                Reported(down, mapOf("mcp.tool" to "broken_tool"), expected = false),
                Reported(wrong, mapOf("mcp.tool" to "picky_tool"), expected = true),
            ),
            reported,
        )
    }

    private var ignored = 0
    private val errorReports = GraphQLErrorReports(report) { ignored++ }

    private val schema = GraphQLSchema.newSchema()
        .query(GraphQLObjectType.newObject().name("Query").field { it.name("episode").type(Scalars.GraphQLString) })
        .build()

    // A request: its fields fail with these, at these paths, and its response has errors, or not
    private fun request(vararg failures: Pair<Throwable, String>, errors: Boolean = failures.isNotEmpty()) {
        val input = ExecutionInput.newExecutionInput("{ episode }").build()
        failures.forEach { (exception, path) ->
            val environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .graphQLContext(input.graphQLContext)
                .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo().type(Scalars.GraphQLString).path(ResultPath.parse(path)).build())
                .build()
            // Left to the other resolvers
            assertNull(errorReports.resolveException(exception, environment).block())
        }
        val result = ExecutionResult.newExecutionResult()
            .apply { if (errors) addError(GraphqlErrorBuilder.newError().message("failed").build()) }.build()
        errorReports.instrumentExecutionResult(result, InstrumentationExecutionParameters(input, schema), null).join()
    }

    @Test
    fun `reports a GraphQL request's first error that isn't the caller's mistake, once it's done`() {
        val down = IllegalStateException("Cloud Storage is down")
        request(IllegalArgumentException("Not an episode code") to "/episode", down to "/episode/media", IllegalStateException("And Koofr") to "/sessions")
        assertEquals(listOf(Reported(down, mapOf("graphql.path" to "/episode/media"), expected = false)), reported)
        assertEquals(0, ignored)
    }

    @Test
    fun `reports a GraphQL request's caller's mistake as expected, and ignores the agent's errors of an invalid query`() {
        val wrong = BindException(Any(), "UUID")
        request(wrong to "/person", AccessDeniedException("Not on the team") to "/ideas")
        assertEquals(listOf(Reported(wrong, mapOf("graphql.path" to "/person"), expected = true)), reported)

        reported.clear()
        request(errors = true)
        assertEquals(emptyList(), reported)
        assertEquals(1, ignored)

        request(errors = false)
        assertEquals(1, ignored)
    }
}
