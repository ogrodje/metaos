package si.ogrodje.metaos

import org.junit.jupiter.api.Test
import org.springframework.aop.Advisor
import org.springframework.aop.framework.Advised
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import kotlin.test.assertTrue

// What NewRelic.kt's own test (si.ogrodje.newrelic) can't see: that the app's tools get the advisor
@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class NewRelicConfigTest @Autowired constructor(
    @Qualifier("mcpToolErrorReportsAdvisor") private val advisor: Advisor,
    private val shows: ShowsMCP,
    private val audit: AuditMCP,
) {

    @Test
    fun `reports the MCP tools' errors, whether their beans were proxied for transactions or not`() {
        assertTrue((shows as Advised).advisors.contains(advisor))
        assertTrue((audit as Advised).advisors.contains(advisor))
    }
}
