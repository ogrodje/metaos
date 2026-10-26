package si.ogrodje.metaos

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Postgres for the tests, a container of each Spring context's, which Flyway migrates: so each context costs seconds to
 * start. Tests that change nothing else (no beans or properties of their own) say @AutoConfigureMockMvc even when they
 * don't use it, so they share one context, the GraphQL tests'. CI pulls the image while Gradle starts, by its name
 * here (.github/actions/toolchain).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:18-alpine")
}