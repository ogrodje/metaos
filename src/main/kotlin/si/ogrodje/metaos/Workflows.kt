package si.ogrodje.metaos

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.serviceclient.WorkflowServiceStubs
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Component

/** The task queue MetaOS's workers poll: its workflows, its activities, and the agents' model and MCP calls */
const val WORKFLOWS_QUEUE = "metaos"

/** The client registration the agents get their tokens with, as the service account of Keycloak's metaos-agents */
const val AGENTS_CLIENT = "metaos-agents"

/**
 * Workflows are off until MetaOS knows where Temporal is (TEMPORAL_ADDRESS, spring.temporal.connection.target), as
 * Temporal's own auto-configuration is, or the tests start one in memory
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("'\${spring.temporal.connection.target:}' != '' or \${spring.temporal.test-server.enabled:false}")
annotation class ConditionalOnTemporal

/**
 * Durable workflows, on Temporal (https://temporal.io), through its Spring Boot integration: MetaOS is a worker, with
 * the workflows in this package and its activities as its own beans (@WorkflowImpl, @ActivityImpl, on
 * [WORKFLOWS_QUEUE]), so a workflow uses what MetaOS has, e.g. its bucket. A workflow's every step is kept in Temporal,
 * which runs it to the end through restarts and deploys, retrying what failed, e.g. a transcription that waits a day
 * for Speech-to-Text (Transcription.kt). Agents are workflows too (Agents.kt).
 */
@Configuration
class WorkflowsConfig {

    // Workflows' and activities' arguments are Kotlin data classes, which Jackson 2's Kotlin module constructs; the rest
    // as Temporal's default converter has it. As temporal-kotlin's KotlinObjectMapperFactory would, which calls a
    // constructor Jackson 2.21's Kotlin module no longer has (temporalio/sdk-java#2071).
    @Bean
    fun temporalDataConverter(): DataConverter = DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
        JacksonJsonPayloadConverter(JacksonJsonPayloadConverter.newDefaultObjectMapper().registerModule(KotlinModule.Builder().build()))
    )

    /**
     * The agents' tokens, by client credentials: for metaos-agents' service account, which is approved, so they get
     * mcp:tools (infra/keycloak/realm/agents.tf). Kept until they expire, then fetched again; outside any request, as
     * workflows run in their own threads.
     */
    @Bean
    fun agentsClientManager(
        registrations: ClientRegistrationRepository,
        clients: OAuth2AuthorizedClientService,
    ): OAuth2AuthorizedClientManager = AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients).apply {
        setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build())
    }

    /**
     * The agents call MetaOS's own MCP tools (spring.ai.mcp.client.streamable-http.connections.metaos) as metaos-agents,
     * with its bearer token on every request, so its tools check, audit and attribute what agents do as they do for
     * anyone
     */
    /**
     * MetaOS's own MCP client, as a bean of its own: Temporal's plugin (io.temporal:spring-ai 0.1.1) makes activities only
     * of the clients it finds that way, while Spring AI keeps them in a list (mcpSyncClients). Its main branch finds
     * those too; drop this once a release has it.
     */
    @Bean
    fun agentsMcpClient(context: ApplicationContext): McpSyncClient =
        (context.getBean("mcpSyncClients") as List<*>).filterIsInstance<McpSyncClient>().single()

    @Bean
    fun agentsMcpAuthorization(agentsClientManager: OAuth2AuthorizedClientManager) =
        McpSyncHttpClientRequestCustomizer { request, _, _, _, _ ->
            val authorized = agentsClientManager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(AGENTS_CLIENT).principal(AGENTS_CLIENT).build())
            val token = checkNotNull(authorized) { "Keycloak gave $AGENTS_CLIENT no token" }.accessToken.tokenValue
            request.header("Authorization", "Bearer $token")
        }
}

/** Temporal's frontend, every hour, as MetaOS's other connections are */
@Component
@ConditionalOnTemporal
class TemporalHealthIndicator(private val stubs: WorkflowServiceStubs, registry: MeterRegistry) :
    ConnectionHealthIndicator("temporal", registry) {
    override fun probe(): Pair<String, String> =
        "server" to stubs.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance()).serverVersion
}
