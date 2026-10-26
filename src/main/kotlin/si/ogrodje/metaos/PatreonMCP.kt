package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime

private const val POST_ID = "The Patreon post's ID, the number at the end of its URL, e.g. 171593625"
private const val ACCESS = "Who may see it: PUBLIC (anyone), PATRONS (every patron, free ones too) or TIERS (only the tiers in tiers)"
private const val TIERS = "For TIERS: the tiers' IDs (list_patreon_tiers)"
private const val TEXT = "The post's text: paragraphs between blank lines; addresses become links"
private const val JOB = "Changes in Patreon run as jobs: what's quick comes back done, else as a job to follow with get_patreon_job"

/**
 * Ogrodje's Patreon, through patreon-gateway ([Patreon]): its tiers and posts, and posts made as drafts, given an
 * episode's audio or video, and published, which is what patrons see and get emailed about, so it's its own step.
 */
@Component
@ConditionalOnPatreon
class PatreonMCP(
    private val patreon: Patreon,
    private val episodes: EpisodesRepository,
    private val dropbox: ObjectProvider<Dropbox>,
    private val transactions: TransactionTemplate,
) {

    // What's quick in Patreon's web app (not an upload) is waited for, so the caller gets the post itself
    private fun quick(job: PatreonJob) = patreon.await(job, Duration.ofSeconds(90))

    @McpTool(
        name = "list_patreon_tiers",
        annotations = McpTool.McpAnnotations(
            title = "List Patreon tiers",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the tiers of Ogrodje's Patreon, cheapest first, with their prices and patrons; a post can be " +
            "limited to some of them"
    )
    fun tiers(): List<PatreonTier> = patreon.tiers()

    @McpTool(
        name = "list_patreon_members",
        annotations = McpTool.McpAnnotations(
            title = "List Patreon members",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the members of Ogrodje's Patreon, patrons first, with their status, tiers, monthly pledge, " +
            "what they've given in all, since when, and their last charge; with their names and emails, which are " +
            "theirs: don't pass them on. With the total that match"
    )
    fun members(
        @McpToolParam(description = "Words in the name or email", required = false) query: String?,
        @McpToolParam(
            description = "Only ACTIVE_PATRON, DECLINED_PATRON (their last charge failed), FORMER_PATRON or FREE ones",
            required = false
        ) status: PatreonMemberStatus?,
        @McpToolParam(description = "Only those in a tier, by its title, e.g. Engineer", required = false) tier: String?,
        @McpToolParam(description = "How many, 50 when omitted, at most 500", required = false) limit: Int?,
        @McpToolParam(description = "How many to skip, for the next page", required = false) offset: Int?,
    ): PatreonMembers = patreon.members(
        query?.trim()?.ifEmpty { null }, status, tier?.trim()?.ifEmpty { null }, (limit ?: 50).coerceIn(1, 500), (offset ?: 0).coerceAtLeast(0),
    )

    @McpTool(
        name = "list_patreon_posts",
        annotations = McpTool.McpAnnotations(
            title = "List Patreon posts",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the published posts on Ogrodje's Patreon, newest first, with who may see them; drafts aren't listed"
    )
    fun posts(
        @McpToolParam(description = "Words in the title, e.g. S05E07", required = false) query: String?,
        @McpToolParam(description = "How many, 20 when omitted, at most 200", required = false) limit: Int?,
    ): List<PatreonPost> = patreon.posts(query?.trim()?.ifEmpty { null }, (limit ?: 20).coerceIn(1, 200))

    @McpTool(
        name = "list_patreon_drafts",
        annotations = McpTool.McpAnnotations(
            title = "List Patreon drafts",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the drafts on Ogrodje's Patreon, scheduled ones too, last edited first: what they are (text, " +
            "audio or video post) and where to edit them. Only the team sees them until they're published"
    )
    fun drafts(): List<PatreonDraft> = patreon.drafts()

    @McpTool(
        name = "get_patreon_post",
        annotations = McpTool.McpAnnotations(
            title = "Get Patreon post",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "A post on Ogrodje's Patreon: a published one with its text and who may see it, or a draft, with " +
            "where to edit it"
    )
    fun post(@McpToolParam(description = POST_ID) id: String): Any = patreon.post(id) ?: patreon.draft(id)

    @McpTool(
        name = "get_patreon_post_media",
        annotations = McpTool.McpAnnotations(
            title = "Get Patreon post media",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Download links for a Patreon post's audio and video, a draft's too: AUDIO is the MP3 as it was " +
            "uploaded, VIDEO the best MP4 Patreon keeps of the video (not the file uploaded). The links are signed and " +
            "give the file to anyone who has them until expiresAt (a day for video, a few for audio), so give them only to " +
            "the team. A video's link needs its referer sent as the Referer header, else it's refused (403), e.g. " +
            "curl -L -H 'Referer: https://www.patreon.com/' -o '<fileName>' '<url>'"
    )
    fun media(@McpToolParam(description = POST_ID) id: String): List<PatreonMediaDownload> = patreon.downloads(id)

    @McpTool(
        name = "create_patreon_draft",
        annotations = McpTool.McpAnnotations(
            title = "Create Patreon draft",
            readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false
        ),
        description = "Make a draft post on Ogrodje's Patreon, which only the team sees until it's published " +
            "(publish_patreon_post); give it the episode's audio or video with set_patreon_post_media. $JOB"
    )
    fun createDraft(
        @McpToolParam(description = "Its title, e.g. S05E08 Miha Zidar: Pametnejši razvoj v dobi umetne inteligence") title: String,
        @McpToolParam(description = TEXT, required = false) text: String?,
        @McpToolParam(description = "$ACCESS; PATRONS when omitted", required = false) access: PatreonAccess?,
        @McpToolParam(description = TIERS, required = false) tiers: List<String>?,
    ): PatreonJob = quick(patreon.createDraft(title, text, access ?: PatreonAccess.PATRONS, tiers.orEmpty()))

    @McpTool(
        name = "update_patreon_post",
        annotations = McpTool.McpAnnotations(
            title = "Update Patreon post",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Change a Patreon post's title, text or who may see it, a draft's or a published one's; what's " +
            "omitted stays. $JOB"
    )
    fun update(
        @McpToolParam(description = POST_ID) id: String,
        @McpToolParam(description = "Its new title", required = false) title: String?,
        @McpToolParam(description = "$TEXT; replaces all of it", required = false) text: String?,
        @McpToolParam(description = ACCESS, required = false) access: PatreonAccess?,
        @McpToolParam(description = TIERS, required = false) tiers: List<String>?,
    ): PatreonJob = quick(patreon.update(id, title, text, access, tiers.orEmpty()))

    @McpTool(
        name = "set_patreon_post_media",
        annotations = McpTool.McpAnnotations(
            title = "Set Patreon post media",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Upload an episode's audio or video, from Dropbox or an https URL, to a Patreon draft: with AUDIO it " +
            "becomes a podcast post, in the campaign's podcast feed, with VIDEO a video post. The gateway downloads the " +
            "file itself and Patreon processes it, which takes minutes for an episode: this returns a job to follow " +
            "with get_patreon_job"
    )
    fun setMedia(
        @McpToolParam(description = POST_ID) id: String,
        @McpToolParam(description = "AUDIO or VIDEO") kind: PatreonMediaKind,
        @McpToolParam(description = "For a file in Dropbox: a folder shared with the bot, by name or ID (list_dropbox_places)", required = false) place: String?,
        @McpToolParam(description = "For a file in Dropbox: its path in the place, e.g. /S05E08/ogrodje-s05e08.mp3", required = false) path: String?,
        @McpToolParam(description = "Else an https URL the file can be downloaded from", required = false) url: String?,
        @McpToolParam(description = "The file's name on Patreon; the path's or URL's last part when omitted", required = false) name: String?,
    ): PatreonJob {
        val fileUrl = when {
            !place.isNullOrBlank() || !path.isNullOrBlank() -> {
                val box = dropbox.ifAvailable ?: throw IllegalStateException("Dropbox isn't set up in MetaOS")
                val shared = box.sharedPlace(requireNotNull(place?.trim()?.ifEmpty { null }) { "A file in Dropbox needs its place" })
                box.temporaryLink(shared.id, dropboxPath(requireNotNull(path?.trim()?.ifEmpty { null }) { "A file in Dropbox needs its path" }))
            }
            else -> requireNotNull(url?.trim()?.ifEmpty { null }) { "Say where the file is: place and path in Dropbox, or url" }
                .also { require(it.startsWith("https://")) { "The file's URL has to be https" } }
        }
        val fileName = name?.trim()?.ifEmpty { null } ?: path?.substringAfterLast('/')?.ifEmpty { null }
        return patreon.attachMedia(id, fileUrl, kind, fileName)
    }

    @McpTool(
        name = "publish_patreon_post",
        annotations = McpTool.McpAnnotations(
            title = "Publish Patreon post",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true
        ),
        description = "Publish a Patreon draft, now or at a set time: who it's for see it, and are emailed when notify is " +
            "set. Refused for a post that's already published. With an episode, the post becomes that episode's Patreon " +
            "link (link: PATREON, PATREON_AUDIO, PATREON_BONUS or PATREON_BONUS_AUDIO) when the job is done in this " +
            "call; when it comes back still to finish, set the link with set_episode_link to its result's url once " +
            "get_patreon_job says SUCCEEDED. Check the draft with get_patreon_post first; ask before publishing. $JOB"
    )
    fun publish(
        @McpToolParam(description = POST_ID) id: String,
        @McpToolParam(description = "When to publish it, e.g. 2026-11-03T06:00:00+01:00; now when omitted", required = false) at: String?,
        @McpToolParam(description = "Email the patrons who may see it; false when omitted", required = false) notify: Boolean?,
        @McpToolParam(description = "$EPISODE_REF, whose Patreon link the post becomes", required = false) episode: String?,
        @McpToolParam(description = "Which of the episode's Patreon links; PATREON when omitted", required = false) link: Platform?,
    ): PatreonJob {
        val platform = link ?: Platform.PATREON
        require(platform.name.startsWith("PATREON")) { "$platform isn't one of the episode's Patreon links" }
        episode?.let { ref -> requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" } }
        val job = quick(patreon.publish(id, at?.trim()?.ifEmpty { null }?.let(OffsetDateTime::parse), notify ?: false))
        val url = job.result?.get("url")?.asString()
        if (episode != null && job.status == "SUCCEEDED" && url != null) {
            transactions.executeWithoutResult { requireNotNull(episodes.findByRef(episode)).setLink(platform, url) }
        }
        return job
    }

    @McpTool(
        name = "delete_patreon_post",
        annotations = McpTool.McpAnnotations(
            title = "Delete Patreon post",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Delete a post from Ogrodje's Patreon, e.g. a draft that isn't needed; Patreon can't bring it back. $JOB"
    )
    fun delete(@McpToolParam(description = POST_ID) id: String): PatreonJob = quick(patreon.delete(id))

    @McpTool(
        name = "get_patreon_job",
        annotations = McpTool.McpAnnotations(
            title = "Get Patreon job",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "How a change in Patreon is going: QUEUED, RUNNING, SUCCEEDED with what it made, or FAILED and why. " +
            "A job Patreon turned away for now waits and tries again by itself (retryAt)"
    )
    fun job(@McpToolParam(description = "The job's ID") id: String): PatreonJob = patreon.job(id)
}
