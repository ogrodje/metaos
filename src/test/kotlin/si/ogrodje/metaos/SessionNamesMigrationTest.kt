package si.ogrodje.metaos

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

// V29 on a database of its own, as it was before: sessions are named after their location, without that being a change
@SpringBootTest
@Import(TestcontainersConfig::class)
class SessionNamesMigrationTest @Autowired constructor(
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `names each session after its location, and leaves its history and updated_at be`() {
        val app = dataSource.unwrap(HikariDataSource::class.java)
        jdbcTemplate.execute("create database v29_session_names")
        try {
            val url = app.jdbcUrl.replaceAfterLast('/', "v29_session_names")
            val before = DriverManagerDataSource(url, app.username, app.password)
            fun flyway(target: String) =
                Flyway.configure().dataSource(before).locations("classpath:db/migration").target(target).load()
            flyway("27").migrate()

            val db = JdbcTemplate(before)
            val named = UUID.randomUUID()
            val unnamed = UUID.randomUUID()
            db.update("insert into sessions (id, date, location, address) values (?, '2026-05-09', 'Kino Šiška', 'Trg 3')", named)
            db.update("insert into sessions (id, date) values (?, '2026-06-01')", unnamed)
            val updatedAt = db.queryForObject<OffsetDateTime>("select updated_at from sessions where id = ?", named)

            flyway("29").migrate()

            assertEquals("Kino Šiška", db.queryForObject<String>("select name from sessions where id = ?", named))
            assertEquals(null, db.queryForObject("select name from sessions where id = ?", String::class.java, unnamed))
            assertEquals(updatedAt, db.queryForObject<OffsetDateTime>("select updated_at from sessions where id = ?", named))
            assertEquals(
                listOf("INSERT", "INSERT"),
                db.queryForList("select operation from audit_log where table_name = 'sessions' order by id", String::class.java),
            )
            // And the triggers are back on afterwards
            db.update("update sessions set name = 'AWS Community Day' where id = ?", named)
            assertEquals(1, db.queryForObject<Int>("select count(*) from audit_log where operation = 'UPDATE' and row_id = ?", named))
        } finally {
            jdbcTemplate.execute("drop database v29_session_names with (force)")
        }
    }
}
