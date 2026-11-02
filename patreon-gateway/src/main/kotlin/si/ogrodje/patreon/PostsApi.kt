package si.ogrodje.patreon

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Posts as Patreon's web app has them, drafts too, which its API doesn't show. Making and changing them isn't here:
 * those are MetaOS's workflows' activities (Activities.kt).
 */
@RestController
class PostsApi(private val web: PatreonWeb) {
    /** Where to download a post's audio or video, with signed links; a draft's too. */
    @GetMapping("/posts/{id}/media")
    fun downloads(@PathVariable id: String) = web.downloads("media-${id.filter(Char::isLetterOrDigit)}", id)

    /** The campaign's drafts, last edited first, which Patreon's API doesn't list. */
    @GetMapping("/drafts")
    fun drafts() = web.drafts("drafts")

    /** A post as Patreon's web app has it, drafts too, which its API doesn't show. */
    @GetMapping("/posts/{id}/web")
    fun webPost(@PathVariable id: String) = web.get("get-${id.filter(Char::isLetterOrDigit)}", id)
}
