package si.ogrodje.metaos

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.support.ResourcePropertySource

/** Which audiences MetaOS takes tokens for, as application.properties has them: its own URLs, and while it moves to
 * another host, its URLs there too (METAOS_OTHER_AUDIENCES, OG-59). */
class AudiencesTest {

    private fun audiences(vararg env: Pair<String, String>): List<String> {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("env", env.toMap()))
        environment.propertySources.addLast(ResourcePropertySource(FileSystemResource("src/main/resources/application.properties")))
        return Binder.get(environment)
            .bind("spring.security.oauth2.resourceserver.jwt.audiences", Bindable.listOf(String::class.java)).get()
    }

    @Test
    fun `its own URLs, and no empty one`() {
        assertThat(audiences("METAOS_MCP_URL" to "https://a.example/mcp", "METAOS_API_URL" to "https://a.example/graphql"))
            .containsOnly("https://a.example/mcp", "https://a.example/graphql")
    }

    @Test
    fun `localhost's by default`() {
        assertThat(audiences()).containsOnly("http://localhost:8080/mcp", "http://localhost:8080/graphql")
    }

    @Test
    fun `the other host's too, while it moves`() {
        assertThat(
            audiences(
                "METAOS_MCP_URL" to "https://a.example/mcp", "METAOS_API_URL" to "https://a.example/graphql",
                "METAOS_OTHER_AUDIENCES" to "https://b.example/mcp,https://b.example/graphql",
            ),
        ).containsOnly("https://a.example/mcp", "https://a.example/graphql", "https://b.example/mcp", "https://b.example/graphql")
    }
}
