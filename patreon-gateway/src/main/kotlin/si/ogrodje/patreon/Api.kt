package si.ogrodje.patreon

import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

data class Page<T>(val items: List<T>, val total: Int)

/** Reading Ogrodje's Patreon (its API), the jobs, and the browser's session. Posts are changed in [PostsApi]. */
@RestController
class Api(private val api: PatreonApi, private val browser: Browser, private val jobs: Jobs, private val health: Health) {
    @GetMapping("/health")
    fun health(@RequestParam(defaultValue = "false") check: Boolean): ResponseEntity<GatewayHealth> {
        // Checked now also before the first scheduled check is done, so a caller right after a restart isn't told it's down
        val current = if (check || health.last.checkedAt == null) health.check() else health.last
        return ResponseEntity.status(if (current.ok) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE).body(current)
    }

    @GetMapping("/campaign")
    fun campaign() = api.campaign()

    @GetMapping("/tiers")
    fun tiers() = api.tiers()

    /** Published posts, newest first; [query] finds words in their titles. */
    @GetMapping("/posts")
    fun posts(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Page<Post> {
        val words = query?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
        val found = api.posts().filter { post -> words.all { post.title.orEmpty().lowercase().contains(it) } }
        return Page(found.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 200)), found.size)
    }

    @GetMapping("/posts/{id}")
    fun post(@PathVariable id: String) = api.post(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No published post $id")

    /** The campaign's members: [status] active_patron, declined_patron, former_patron or free; [query] in name or email. */
    @GetMapping("/members")
    fun members(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tier: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Page<Member> {
        val words = query?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
        val found = api.members().filter { member ->
            (status == null || (if (status == "free") member.status == null else member.status == status)) &&
                (tier == null || member.tiers.any { it.equals(tier, ignoreCase = true) }) &&
                words.all { "${member.name.orEmpty()} ${member.email.orEmpty()}".lowercase().contains(it) }
        }
        return Page(found.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)), found.size)
    }

    /** When the creator token runs out, and when it was last refreshed. */
    @GetMapping("/token")
    fun token() = api.tokenInfo()

    /** Refreshes the creator token now, and keeps the new one. */
    @PostMapping("/token/refresh")
    fun refreshToken(): TokenInfo {
        api.refresh()
        return api.tokenInfo()
    }

    @GetMapping("/jobs")
    fun jobs(@RequestParam(defaultValue = "20") limit: Int) = jobs.list().take(limit.coerceIn(1, 500))

    @GetMapping("/jobs/{id}")
    fun job(@PathVariable id: String) = jobs.get(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No job $id")

    /** What the page showed when the job failed. */
    @GetMapping("/jobs/{id}/screenshot", produces = [MediaType.IMAGE_PNG_VALUE])
    fun screenshot(@PathVariable id: String) = FileSystemResource(
        browser.failure(id, "png") ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No screenshot for job $id"),
    )

    /** Takes the bot's browser session from login.sh (Playwright's storage state), once it's signed in. */
    @PutMapping("/session", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun session(@RequestBody storageState: String): Map<String, Any?> {
        val email = browser.replaceSession(storageState)
        return mapOf("signedInAs" to email, "health" to health.check())
    }
}

data class ApiError(val error: String)

@RestControllerAdvice
class Errors {
    @ExceptionHandler(PatreonException::class)
    fun patreon(e: PatreonException) = ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError(e.message ?: "Patreon failed"))

    @ExceptionHandler(PatreonBusyException::class)
    fun busy(e: PatreonBusyException) = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "300")
        .body(ApiError(e.message ?: "Patreon is busy"))

    @ExceptionHandler(SignedOutException::class)
    fun signedOut(e: SignedOutException) = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError(e.message ?: "Signed out"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException) = ResponseEntity.badRequest().body(ApiError(e.message ?: "Invalid request"))

    @ExceptionHandler(ResponseStatusException::class)
    fun status(e: ResponseStatusException) = ResponseEntity.status(e.statusCode).body(ApiError(e.reason ?: e.statusCode.toString()))
}
