package si.ogrodje.patreon

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Every call needs `Authorization: Bearer <gateway.token>`, a secret only MetaOS has, but `/health` and the actuator's
 * (probes and metrics). Without a token set, the gateway answers no one.
 */
@Component
class GatewayAuth(@Value("\${gateway.token:}") private val token: String) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == "/health" || path.startsWith("/actuator/")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val given = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        if (token.isBlank() || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"A bearer token is needed: the gateway's own (PATREON_GATEWAY_TOKEN)"}""")
            return
        }
        chain.doFilter(request, response)
    }
}
