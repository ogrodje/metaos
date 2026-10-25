package si.ogrodje.patreon

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

data class PublishRequest(val at: OffsetDateTime? = null, val notify: Boolean = false)

/**
 * Making and changing posts, in Patreon's web app as the bot ([PatreonWeb]): each is a job, answered at once with
 * 202 and followed at `/jobs/{id}`. An `Idempotency-Key` header makes a retry give back the job it first started.
 */
@RestController
class PostsApi(private val web: PatreonWeb, private val jobs: Jobs) {
    @PostMapping("/posts")
    fun create(@RequestBody spec: PostSpec, @RequestHeader("Idempotency-Key", required = false) key: String?): ResponseEntity<Job> {
        checked(spec)
        // The draft each try of the job makes, so a try after Patreon turned it away doesn't make another
        val created = AtomicReference<String>()
        return accepted(jobs.submit("create-draft", key) { web.createDraft(it, spec, created) })
    }

    @PatchMapping("/posts/{id}")
    fun update(@PathVariable id: String, @RequestBody spec: PostSpec, @RequestHeader("Idempotency-Key", required = false) key: String?): ResponseEntity<Job> {
        checked(spec)
        return accepted(jobs.submit("update-post", key) { web.update(it, id, spec) })
    }

    /** Publishes a draft now, or at `at`; patrons are emailed only with `notify`. */
    @PostMapping("/posts/{id}/publish")
    fun publish(@PathVariable id: String, @RequestBody(required = false) request: PublishRequest?, @RequestHeader("Idempotency-Key", required = false) key: String?) =
        accepted(jobs.submit("publish-post", key) { web.publish(it, id, request?.at, request?.notify ?: false) })

    /** Puts a file, downloaded from `url`, on a draft as its audio or video. */
    @PostMapping("/posts/{id}/media")
    fun media(@PathVariable id: String, @RequestBody request: MediaRequest, @RequestHeader("Idempotency-Key", required = false) key: String?) =
        // The file each try uploads, so a try after Patreon turned it away doesn't upload it again
        AtomicReference<UploadedMedia>().let { uploaded -> accepted(jobs.submit("attach-media", key) { web.attachMedia(it, id, request, uploaded) }) }

    @DeleteMapping("/posts/{id}")
    fun delete(@PathVariable id: String, @RequestHeader("Idempotency-Key", required = false) key: String?) =
        accepted(jobs.submit("delete-post", key) { web.delete(it, id) })

    /** A post as Patreon's web app has it, drafts too, which its API doesn't show. */
    @GetMapping("/posts/{id}/web")
    fun webPost(@PathVariable id: String) = web.get("get-${id.filter(Char::isLetterOrDigit)}", id)

    private fun accepted(job: Job) = ResponseEntity.status(HttpStatus.ACCEPTED).header("Location", "/jobs/${job.id}").body(job)

    // What can be refused before a job, which would otherwise find out in Patreon
    private fun checked(spec: PostSpec) {
        val access = spec.access ?: return
        require(access.kind != Access.Kind.TIERS || access.tiers.isNotEmpty()) { "Access TIERS needs the tiers' IDs" }
    }
}
