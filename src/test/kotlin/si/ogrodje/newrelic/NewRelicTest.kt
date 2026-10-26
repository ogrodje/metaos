// Not in si.ogrodje.metaos, where UsageToolsTest and ToolHintsTest would take its tools for MetaOS's
package si.ogrodje.newrelic

import graphql.Scalars
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.schema.DataFetchingEnvironmentImpl
import org.junit.jupiter.api.Test
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.aop.framework.ProxyFactory
import org.springframework.security.access.AccessDeniedException
import si.ogrodje.metaos.ErrorReport
import si.ogrodje.metaos.GraphQLErrorReports
import si.ogrodje.metaos.mcpToolErrorReports
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NewRelicTest {

    private val reported = mutableListOf<Pair<Throwable, Map<String, String>>>()
    private val report: ErrorReport = { error, attributes -> reported += error to attributes }

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
    fun `reports what an MCP tool failed with, as it was thrown, but not the caller's mistake`() {
        val tools = ProxyFactory(Tools()).apply { isProxyTargetClass = true; addAdvisor(mcpToolErrorReports(report)) }.proxy as Tools

        val down = assertFailsWith<IllegalStateException> { tools.broken() }
        assertFailsWith<IllegalArgumentException> { tools.picky() }
        assertEquals("ok", tools.fine())

        assertEquals(listOf<Pair<Throwable, Map<String, String>>>(down to mapOf("mcp.tool" to "broken_tool")), reported)
    }

    @Test
    fun `reports what a GraphQL field failed with, and leaves it to the other resolvers`() {
        val resolver = GraphQLErrorReports(report)
        val environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo().type(Scalars.GraphQLString)
                .path(ResultPath.parse("/episode/media")).build())
            .build()
        val down = IllegalStateException("Cloud Storage is down")

        assertNull(resolver.resolveException(down, environment).block())
        assertNull(resolver.resolveException(IllegalArgumentException("Not an episode code"), environment).block())
        assertNull(resolver.resolveException(AccessDeniedException("Not on the team"), environment).block())

        assertEquals(listOf<Pair<Throwable, Map<String, String>>>(down to mapOf("graphql.path" to "/episode/media")), reported)
    }
}
