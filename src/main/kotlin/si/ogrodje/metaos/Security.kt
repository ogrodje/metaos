package si.ogrodje.metaos

import jakarta.servlet.DispatcherType
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer.mcpServerOAuth2
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.stereotype.Component

const val MCP_SCOPE = "mcp:tools"

/** For reading what isn't public through /graphql; the front-end's tokens have it (keycloak/frontend_client.sh) */
const val API_READ_SCOPE = "metaos:read"

/** For changes through /graphql, e.g. comments; the front-end's tokens have it too */
const val API_WRITE_SCOPE = "metaos:write"

/**
 * The realm role an admin grants to approve a user (keycloak/approval_gate.sh). MetaOS checks it as well as the scope,
 * as Keycloak can issue a token with the scope to more than approved users: a client registered through DCR for its own
 * service account (client_credentials: no user, no login, so no approval gate), or a user whose approval was taken back
 * while their session lives on. keycloak/approved_scopes.sh keeps the scopes out of their tokens on Keycloak's side too.
 * The role is read from the signed token, so a token issued before the role was taken away still works until it expires
 * (an access token's lifespan; the next refresh drops the role); end the user's sessions to cut that short.
 */
const val APPROVED_ROLE = "metaos-user"

/** The token's realm roles, from Keycloak's realm_access claim */
val Jwt.realmRoles: Set<String>
    get() = (getClaimAsMap("realm_access")?.get("roles") as? Collection<*>).orEmpty().filterIsInstance<String>().toSet()

/** Whether this is an approved user's token with [scope] */
fun Authentication?.approvedFor(scope: String): Boolean {
    val token = this as? JwtAuthenticationToken ?: return false
    return token.authorities.any { it.authority == "SCOPE_$scope" } && APPROVED_ROLE in token.token.realmRoles
}

/** [approvedFor] for routes */
private fun approved(scope: String) =
    AuthorizationManager<RequestAuthorizationContext> { authentication, _ ->
        AuthorizationDecision(authentication.get().approvedFor(scope))
    }

/** [approvedFor] for @PreAuthorize, e.g. `@approval.reads(authentication)` (GraphQL.kt) */
@Component("approval")
class Approval {
    fun reads(authentication: Authentication?) = authentication.approvedFor(API_READ_SCOPE)

    // GraphQL's mutations (CommentsGraphQL.kt)
    fun writes(authentication: Authentication?) = authentication.approvedFor(API_WRITE_SCOPE)
}

/** Keycloak access token of the user calling the MCP tools. */
fun currentJwt(): Jwt? = (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token

/**
 * An account is linked to a [Person] by email (Accounts.kt) - only once Keycloak has verified it, so nobody can claim
 * someone else's.
 */
val Jwt.verifiedEmail: String?
    get() = getClaimAsString("email")?.takeIf { getClaimAsBoolean("email_verified") == true }

/**
 * MCP endpoint is an OAuth2 resource server; Keycloak (see compose.yml) is the authorization server.
 * Unauthenticated clients get a 401 pointing at /.well-known/oauth-protected-resource/mcp,
 * from where they discover Keycloak, register themselves (DCR) and log the user in.
 */
@Configuration
// @PreAuthorize on what /graphql answers only signed-in users (GraphQL.kt)
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") issuerUri: String,
        // Boot's decoder: resolves Keycloak lazily and checks `aud` against ...resourceserver.jwt.audiences
        jwtDecoder: JwtDecoder,
    ): SecurityFilterChain = http
        .authorizeHttpRequests {
            // In k8s actuator is on its own port (MANAGEMENT_SERVER_PORT), which no Ingress routes
            it.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
            // The k8s probes, on the server port - public through the Ingress, but they only say UP or DOWN
            it.requestMatchers("/livez", "/readyz").permitAll()
            it.requestMatchers("/mcp/**").access(approved(MCP_SCOPE))
            // Anyone may ask for what's public, e.g. the released episodes; the rest needs a token (GraphQL.kt)
            it.requestMatchers("/graphql").permitAll()
            // Which tables changed, for the front-end's pages to load again (Changes.kt)
            it.requestMatchers(HttpMethod.GET, "/events").permitAll()
            // Thumbnails of the pictures in episodes' and sessions' Koofr and Dropbox folders (KoofrPhotos.kt, DropboxPictures.kt)
            it.requestMatchers("/koofr/**", "/dropbox/**").access(approved(API_READ_SCOPE))
            // The error page of a request let in, e.g. a 404, which would otherwise become a 401
            it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            // The front-end's pages are its own service's (fe/), which the Ingress sends everything else to
            it.anyRequest().authenticated()
        }
        // Bearer tokens only - no cookies, so no CSRF and no HTTP sessions
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .with(mcpServerOAuth2()) { mcp ->
            mcp.authorizationServer(issuerUri)
            mcp.jwtDecoder(jwtDecoder)
            mcp.protectedResourceMetadataCustomizer { metadata ->
                metadata.authorizationServer(issuerUri).resourceName("MetaOS").scope(MCP_SCOPE)
            }
        }
        .build()
}
