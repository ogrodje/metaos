package si.ogrodje.metaos

import com.newrelic.api.agent.NewRelic
import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.schema.DataFetchingEnvironment
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.aop.Advisor
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.graphql.execution.DataFetcherExceptionResolver
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.BindException
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture

/*
 * New Relic (newrelic/newrelic.yml): its Java agent, which the image runs the app with, sees requests, the database,
 * calls out and the errors that make a request fail on its own. GraphQL and MCP answer 200 with the error in their
 * body, so these report theirs, the caller's mistakes as expected: New Relic shows those, but doesn't count them as
 * errors. Without the agent, its API does nothing.
 */

/** Tells New Relic about an error, with what it was in, and whether it's expected */
typealias ErrorReport = (Throwable, Map<String, String>, Boolean) -> Unit

val newRelicReport: ErrorReport = { error, attributes, expected -> NewRelic.noticeError(error, attributes, expected) }

/**
 * The caller's mistake, which they're told about: an argument require(...) refused, or GraphQL couldn't convert (an ID
 * that isn't a UUID), or what they may not do
 */
fun callersMistake(error: Throwable) = error is IllegalArgumentException || error is BindException ||
    error is AccessDeniedException || error is AuthenticationException

/**
 * GraphQL's errors, as New Relic is to have them. Its agent reports them itself, and a request keeps the last: each
 * field's exception (wrapped, a CompletionException) before the resolvers see it, the null a non-null field's error
 * leaves after them, and invalid queries, the caller's mistakes too. So this notes each field's exception, as the
 * first resolver, which leaves it to the others or GraphQL's INTERNAL_ERROR; and once the request is done reports the
 * first that isn't the caller's mistake, else the first that is, as expected, else, when the response has errors
 * anyway, such as an invalid query's, has the agent ignore its own.
 */
class GraphQLErrorReports(
    private val report: ErrorReport = newRelicReport,
    private val ignoreErrors: () -> Unit = { NewRelic.getAgent().transaction.ignoreErrors() },
) : DataFetcherExceptionResolver, Instrumentation, Ordered {

    private class Noted(val exception: Throwable, val path: String)

    override fun resolveException(exception: Throwable, environment: DataFetchingEnvironment): Mono<List<GraphQLError>> {
        val noted = Noted(exception, environment.executionStepInfo.path.toString())
        environment.graphQlContext.compute<MutableList<Noted>>(NOTED) { _, list -> (list ?: mutableListOf()).apply { add(noted) } }
        return Mono.empty()
    }

    override fun instrumentExecutionResult(
        result: ExecutionResult, parameters: InstrumentationExecutionParameters, state: InstrumentationState?,
    ): CompletableFuture<ExecutionResult> {
        val noted = parameters.graphQLContext.get<List<Noted>?>(NOTED).orEmpty()
        val worst = noted.firstOrNull { !callersMistake(it.exception) } ?: noted.firstOrNull()
        when {
            worst != null -> report(worst.exception, mapOf("graphql.path" to worst.path), callersMistake(worst.exception))
            result.errors.isNotEmpty() -> ignoreErrors()
        }
        return CompletableFuture.completedFuture(result)
    }

    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE

    private companion object {
        const val NOTED = "si.ogrodje.metaos.GraphQLErrorReports.noted"
    }
}

/**
 * Reports what an MCP tool failed with, before Spring AI makes it the tool's result (an error the client is shown), as
 * it's thrown: with its stack trace, which the result hasn't
 */
fun mcpToolErrorReports(report: ErrorReport = newRelicReport): Advisor =
    DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(McpTool::class.java), MethodInterceptor {
        try {
            it.proceed()
        } catch (error: Throwable) {
            val tool = AnnotationUtils.findAnnotation(it.method, McpTool::class.java)?.name.orEmpty()
            report(error, mapOf("mcp.tool" to tool.ifEmpty { it.method.name }), callersMistake(error))
            throw error
        }
    })

@Configuration(proxyBeanMethods = false)
class NewRelicConfig {

    @Bean
    fun graphQLErrorReports() = GraphQLErrorReports()

    // An infrastructure bean, as @Transactional's advisor is, so it applies without AspectJ: the tools' beans are
    // proxied, as many are for their transactions already
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun mcpToolErrorReportsAdvisor() = mcpToolErrorReports()
}
