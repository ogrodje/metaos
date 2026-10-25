package si.ogrodje.patreon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserTest {
    @TempDir
    lateinit var state: Path

    private fun browser() = Browser(PatreonProperties(stateDir = state, failuresKept = Duration.ofDays(14)), JsonMapper.builder().build())

    private fun failed(name: String, age: Duration) {
        val failures = Files.createDirectories(state.resolve("failures"))
        for (kind in listOf("png", "html")) {
            val file = Files.writeString(failures.resolve("$name.$kind"), "what the page showed")
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)))
        }
    }

    @Test
    fun `what failed jobs showed is deleted once it's older than kept, the rest stays`() {
        failed("old-job", Duration.ofDays(15))
        failed("new-job", Duration.ofDays(2))
        val browser = browser()
        assertEquals(2, browser.forgetFailures())
        assertNull(browser.failure("old-job", "png"))
        assertFalse(Files.exists(state.resolve("failures/old-job.html")))
        assertNotNull(browser.failure("new-job", "png"))
        assertTrue(Files.exists(state.resolve("failures/new-job.html")))
    }

    @Test
    fun `nothing failed, nothing to delete`() {
        assertEquals(0, browser().forgetFailures())
    }

    @Test
    fun `a failure's name can't reach out of its folder`() {
        failed("job", Duration.ZERO)
        assertNull(browser().failure("../failures/job", "png"))
    }
}
