package si.ogrodje.metaos

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat
import java.util.UUID

/**
 * Postgres for the tests: each Spring context gets a database of its own, as fresh as a container of its own would be,
 * copied from a template the migrations made once (TestPostgres). Tests that change nothing else (no beans or
 * properties of their own) say @AutoConfigureMockMvc even when they don't use it, so they share one context, the GraphQL
 * tests'.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

    @Bean
    fun postgres() = TestDatabase(TestPostgres.newDatabase())
}

/** A database of the tests' Postgres, which the context connects to as it would to a @ServiceConnection's; dropped with it */
class TestDatabase(private val name: String) : JdbcConnectionDetails, DisposableBean {
    override fun getJdbcUrl() = TestPostgres.url(name)
    override fun getUsername(): String = TestPostgres.container.username
    override fun getPassword(): String = TestPostgres.container.password
    override fun destroy() = TestPostgres.drop(name)
}

/**
 * The tests' Postgres server: one a test JVM, and with Testcontainers' reuse on (TESTCONTAINERS_REUSE_ENABLE=true, as
 * CI and devenv have it, or testcontainers.reuse.enable=true in ~/.testcontainers.properties) one for all of them,
 * which stays up between runs. The migrations run once into a template, named after them, so a changed migration makes
 * a new one; each context's database is a copy of it, which takes a tenth of a second where a container and the
 * migrations took seconds. CI pulls the image while Gradle starts, by its name here (.github/actions/toolchain).
 */
object TestPostgres {

    val container: PostgreSQLContainer = PostgreSQLContainer("postgres:18-alpine")
        // Each context the test JVMs keep open has a pool of ten: more than Postgres's 100 connections between them
        .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500")
        .withReuse(true)
        .also { container ->
            // One JVM at a time, so those starting at once find the one the first started rather than each start one
            FileChannel.open(Path.of(System.getProperty("java.io.tmpdir"), "metaos-tests-postgres.lock"), CREATE, WRITE)
                .use { channel -> channel.lock().use { container.start() } }
        }

    private val template: String by lazy {
        val name = "metaos_template_${migrationsHash()}"
        admin { db ->
            db.locked {
                val exists = db.prepareStatement("select 1 from pg_database where datname = ?")
                    .apply { setString(1, name) }.executeQuery().use { it.next() }
                if (!exists) {
                    // Under another name until it's done, so a JVM that dies halfway leaves no template half made
                    val making = "${name}_${unique()}"
                    db.execute("create database \"$making\"")
                    Flyway.configure().dataSource(url(making), container.username, container.password)
                        .locations("classpath:db/migration").load().migrate()
                    db.execute("alter database \"$making\" rename to \"$name\"")
                    // Those of migrations since changed
                    db.createStatement().executeQuery(
                        "select datname from pg_database where datname like 'metaos\\_template\\_%' and datname <> '$name'"
                    ).use { old -> generateSequence { if (old.next()) old.getString(1) else null }.toList() }
                        .forEach { db.execute("drop database if exists \"$it\" with (force)") }
                }
            }
        }
        name
    }

    fun newDatabase(): String {
        val from = template
        val name = "metaos_test_${unique()}"
        admin { db -> db.locked { db.execute("create database \"$name\" template \"$from\"") } }
        return name
    }

    fun drop(name: String) {
        runCatching { admin { it.execute("drop database if exists \"$name\" with (force)") } }
    }

    fun url(database: String) =
        "jdbc:postgresql://${container.host}:${container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$database?loggerLevel=OFF"

    private fun <T> admin(block: (Connection) -> T): T =
        DriverManager.getConnection(url("postgres"), container.username, container.password).use(block)

    // Across the JVMs sharing the server: a template is made once, and copied while no one is connected to it
    private fun <T> Connection.locked(block: () -> T): T {
        execute("select pg_advisory_lock(37037)")
        try {
            return block()
        } finally {
            execute("select pg_advisory_unlock(37037)")
        }
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun migrationsHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*").sortedBy { it.filename }.forEach {
            digest.update(it.filename.orEmpty().toByteArray())
            digest.update(it.contentAsByteArray)
        }
        return HexFormat.of().formatHex(digest.digest()).take(16)
    }

    private fun unique() = UUID.randomUUID().toString().replace("-", "").take(12)
}
