package si.ogrodje.metaos

import com.newrelic.api.agent.NewRelic
import graphql.GraphQLError
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
import reactor.core.publisher.Mono

/*
 * New Relic (newrelic/newrelic.yml): its Java agent, which the image runs the app with, sees requests, the database,
 * calls out and the errors that make a request fail on its own. GraphQL and MCP answer 200 with the error in their
 * body, so these report theirs: those that aren't the caller's mistake. Without the agent, its API does nothing.
 */

/** Tells New Relic about an error, with what it was in */
typealias ErrorReport = (Throwable, Map<String, String>) -> Unit

val newRelicReport: ErrorReport = { error, attributes -> NewRelic.noticeError(error, attributes) }

/** The caller's mistake, which they're told about: an argument require(...) refused, or what they may not do */
fun callersMistake(error: Throwable) =
    error is IllegalArgumentException || error is AccessDeniedException || error is AuthenticationException

/**
 * Reports what a GraphQL field failed with, and leaves it to the other resolvers, or GraphQL's INTERNAL_ERROR: first,
 * as the first resolver to resolve an exception is the last to see it
 */
class GraphQLErrorReports(private val report: ErrorReport = newRelicReport) : DataFetcherExceptionResolver, Ordered {

    override fun resolveException(exception: Throwable, environment: DataFetchingEnvironment): Mono<List<GraphQLError>> {
        if (!callersMistake(exception)) report(exception, mapOf("graphql.path" to environment.executionStepInfo.path.toString()))
        return Mono.empty()
    }

    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE
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
            if (!callersMistake(error)) {
                val tool = AnnotationUtils.findAnnotation(it.method, McpTool::class.java)?.name.orEmpty()
                report(error, mapOf("mcp.tool" to tool.ifEmpty { it.method.name }))
            }
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
