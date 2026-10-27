package si.ogrodje.patreon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenStoreTest {
    @TempDir
    lateinit var state: Path

    private val mapper = JsonMapper.builder().build()

    private fun store(access: String = "access-1", refresh: String = "refresh-1") =
        TokenStore(PatreonProperties(accessToken = access, refreshToken = refresh, stateDir = state), mapper)

    @Test
    fun `the environment's tokens seed it`() {
        val tokens = store().get()!!
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertNull(tokens.expiresAt)
    }

    @Test
    fun `refreshed tokens are kept, and outlive a restart with the same seed`() {
        store().save(Tokens("access-2", "refresh-2", Instant.parse("2026-11-25T00:00:00Z"), refreshedAt = Instant.parse("2026-10-26T00:00:00Z")))
        assertTrue(Files.exists(state.resolve("tokens.json")))
        val again = store().get()!!
        assertEquals("refresh-2", again.refreshToken)
        assertEquals(Instant.parse("2026-11-25T00:00:00Z"), again.expiresAt)
    }

    @Test
    fun `a new token in the environment takes over from the kept ones`() {
        store().save(Tokens("access-2", "refresh-2"))
        val fresh = store(access = "access-new", refresh = "refresh-new").get()!!
        assertEquals("refresh-new", fresh.refreshToken)
    }

    @Test
    fun `without tokens there's nothing`() {
        assertNull(store(access = "", refresh = "").get())
    }
}
