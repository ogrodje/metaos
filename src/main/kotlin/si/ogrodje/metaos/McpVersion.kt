package si.ogrodje.metaos

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.schema.JsonSchemaValidator
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse
import org.springframework.ai.mcp.customizer.McpStatelessSyncServerCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/*
 * MetaOS's MCP tools have a version (spring.ai.mcp.server.version, in mcp.properties), which `just mcp-tools` works out
 * from what changed in them (McpToolsTest). Assistants keep the tools they were given whatever it says, until they're
 * refreshed: Claude's apps until the connector is, ChatGPT's workspace apps until an admin does it. So an assistant may
 * call a tool as it was, without a parameter it has got since or with a value it no longer takes, which the MCP SDK
 * turns down, as the call doesn't fit the tool's schema; this says why, and how to refresh them.
 */

/**
 * The SDK's [validator], which also says, of a call that doesn't fit its tool's schema, that the assistant may have older
 * tools than MetaOS's. It checks what tools answer too, against their output schemas, but none has one
 * (generateOutputSchema)
 */
class StaleToolInputs(private val validator: JsonSchemaValidator, version: String, refreshUrl: String) : JsonSchemaValidator {

    private val older = "If the assistant called it as its tools say, they're older than MetaOS's, at version $version: " +
        "refresh them as $refreshUrl says"

    override fun validate(schema: Map<String, Any>, structuredContent: Any): ValidationResponse =
        validator.validate(schema, structuredContent).let {
            if (it.valid) it else ValidationResponse.asInvalid("${it.errorMessage}. $older")
        }

    override fun validateSchema(schema: Map<String, Any>): ValidationResponse = validator.validateSchema(schema)
}

@Configuration(proxyBeanMethods = false)
class McpVersionConfig {

    @Bean
    fun staleToolInputs(
        @Value("\${spring.ai.mcp.server.version}") version: String,
        @Value("\${metaos.mcp.refresh-url}") refreshUrl: String,
    ) = McpStatelessSyncServerCustomizer {
        it.jsonSchemaValidator(StaleToolInputs(McpJsonDefaults.getSchemaValidator(), version, refreshUrl))
    }
}
