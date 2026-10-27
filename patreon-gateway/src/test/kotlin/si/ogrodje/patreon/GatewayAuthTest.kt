package si.ogrodje.patreon

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals

class GatewayAuthTest {
    private fun status(token: String, path: String, header: String?): Int {
        val request = MockHttpServletRequest("GET", path)
        header?.let { request.addHeader("Authorization", it) }
        val response = MockHttpServletResponse()
        GatewayAuth(token).doFilter(request, response, MockFilterChain())
        return response.status
    }

    @Test
    fun `only the gateway's token gets in`() {
        assertEquals(200, status("secret", "/posts", "Bearer secret"))
        assertEquals(401, status("secret", "/posts", "Bearer wrong"))
        assertEquals(401, status("secret", "/posts", null))
    }

    @Test
    fun `without a token set, no one does`() {
        assertEquals(401, status("", "/posts", "Bearer "))
        assertEquals(401, status("", "/posts", null))
    }

    @Test
    fun `the probes and metrics need none, the gateway's own health does`() {
        assertEquals(200, status("secret", "/actuator/health/liveness", null))
        assertEquals(200, status("secret", "/actuator/prometheus", null))
        assertEquals(401, status("secret", "/health", null))
    }
}
