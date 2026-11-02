package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.data.repository.findByIdOrNull
import java.time.Duration
import java.util.UUID

private const val POST_ID = "The Patreon post's ID, the number at the end of its URL, e.g. 171593625"
private const val ACCESS = "Who may see it: PUBLIC (anyone), PATRONS (every patron, free ones too) or TIERS (only the tiers in tiers)"
private const val TIERS = "For TIERS: the tiers' IDs (list_patreon_tiers)"
private const val TEXT = "The post's text: paragraphs between blank lines; addresses become links"
private const val JOB = "Changes in Patreon run as jobs, one at a time, which try again while Patreon turns them away: " +
    "what's quick comes back done, else as a job to follow with get_patreon_job"

/**
 * Ogrodje's Patreon, through patreon-gateway ([Patreon]): its tiers, members and posts, and drafts made, changed, given
 * an episode's audio or video and its thumbnail, and deleted, as workflows ([PatreonChanges]). Publishing is what patrons see and get
 * emailed about, so a person does it, in Patreon's editor: MetaOS only checks a draft is ready for it.
 */
@Component
@ConditionalOnPatreon
class PatreonMCP(
    private val patreon: Patreon,
    private val changes: ObjectProvider<PatreonChanges>,
    private val mediaFiles: MediaFilesRepository,
    private val storage: ObjectProvider<Storage>,
    private val dropbox: ObjectProvider<Dropbox>,
    private val episodes: EpisodesRepository,
    private val images: ObjectProvider<Images>,
    private val imagesRepository: ImagesRepository,
) {

    private fun changes() = changes.ifAvailable ?: throw IllegalStateException("MetaOS's workflows aren't set up (TEMPORAL_ADDRESS)")

    // What's quick in Patreon's web app (not an upload) is waited for, so the caller gets the post itself, unless it has
    // to wait its turn, or for Patreon
    private fun quick(change: PatreonChange) = changes().let { it.await(it.start(change), Duration.ofSeconds(90)) }

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
        description = "Make a draft post on Ogrodje's Patreon, which only the team sees until a person publishes it in " +
            "Patreon's editor; give it the episode's audio or video with set_patreon_post_media, its thumbnail with " +
            "set_patreon_post_image, and check it with prepare_patreon_post. $JOB"
    )
    fun createDraft(
        @McpToolParam(description = "Its title, e.g. S05E08 Miha Zidar: Pametnejši razvoj v dobi umetne inteligence") title: String,
        @McpToolParam(description = TEXT, required = false) text: String?,
        @McpToolParam(description = "$ACCESS; PATRONS when omitted", required = false) access: PatreonAccess?,
        @McpToolParam(description = TIERS, required = false) tiers: List<String>?,
    ): PatreonJob = quick(PatreonChange(PatreonChangeKind.CREATE_DRAFT, spec = spec(title, text, access ?: PatreonAccess.PATRONS, tiers)))

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
    ): PatreonJob = quick(PatreonChange(PatreonChangeKind.UPDATE_POST, patreonPostId(id), spec(title, text, access, tiers)))

    @McpTool(
        name = "set_patreon_post_media",
        annotations = McpTool.McpAnnotations(
            title = "Set Patreon post media",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Upload one of an episode's video and audio files in MetaOS (media, from list_episode_media) to a " +
            "Patreon draft: with AUDIO it becomes a podcast post, in the campaign's podcast feed, with VIDEO a video " +
            "post. The gateway downloads the file itself and Patreon processes it, which takes minutes for an episode, " +
            "or longer while Patreon turns the gateway away: this returns a job to follow with get_patreon_job"
    )
    fun setMedia(
        @McpToolParam(description = POST_ID) id: String,
        @McpToolParam(description = "AUDIO or VIDEO, as the file is") kind: PatreonMediaKind,
        @McpToolParam(description = "The file's ID in MetaOS (list_episode_media)", required = false) media: String?,
        @McpToolParam(description = "Deprecated: use media. For a file in Dropbox: a folder shared with the bot, by name or ID", required = false) place: String?,
        @McpToolParam(description = "Deprecated: use media. For a file in Dropbox: its path in the place", required = false) path: String?,
        @McpToolParam(description = "Deprecated: use media. Else an https URL the file can be downloaded from", required = false) url: String?,
        @McpToolParam(description = "The file's name on Patreon; its own when omitted", required = false) name: String?,
    ): PatreonJob {
        val post = patreonPostId(id)
        val fileName = name?.trim()?.ifEmpty { null }
        val mediaId = media?.trim()?.ifEmpty { null }
        val change = if (mediaId != null) {
            val file = requireNotNull(runCatching { UUID.fromString(mediaId) }.getOrNull()?.let { mediaFiles.findByIdOrNull(it) }) {
                "No file $mediaId: list_episode_media lists an episode's"
            }
            require(file.kind.video == (kind == PatreonMediaKind.VIDEO)) { "${file.name} is the episode's ${file.kind.label}, not ${kind.name}" }
            checkNotNull(storage.ifAvailable) { "MetaOS's bucket isn't set up" }
            PatreonChange(PatreonChangeKind.ATTACH_MEDIA, post, mediaId = file.id, fileName = fileName)
        } else {
            // Till the deprecated ways go: a file in Dropbox, or a URL
            val fileUrl = when {
                !place.isNullOrBlank() || !path.isNullOrBlank() -> {
                    val box = dropbox.ifAvailable ?: throw IllegalStateException("Dropbox isn't set up in MetaOS")
                    val shared = box.sharedPlace(requireNotNull(place?.trim()?.ifEmpty { null }) { "A file in Dropbox needs its place" })
                    box.temporaryLink(shared.id, dropboxPath(requireNotNull(path?.trim()?.ifEmpty { null }) { "A file in Dropbox needs its path" }))
                }
                else -> requireNotNull(url?.trim()?.ifEmpty { null }) { "Say which file: media, from list_episode_media" }
                    .also { require(it.startsWith("https://")) { "The file's URL has to be https" } }
            }
            PatreonChange(PatreonChangeKind.ATTACH_MEDIA, post, media = PatreonMediaRequest(fileUrl, kind, fileName ?: path?.substringAfterLast('/')?.ifEmpty { null }))
        }
        return changes().start(change)
    }

    @McpTool(
        name = "set_patreon_post_image",
        annotations = McpTool.McpAnnotations(
            title = "Set Patreon post image",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Set the picture a Patreon post shows in the feed and at its top, its thumbnail, a draft's or a " +
            "published one's, replacing the one it had: one of an episode's pictures in MetaOS (list_episode_images), " +
            "its THUMBNAIL unless kind says otherwise; one that isn't in MetaOS yet goes there first with " +
            "set_episode_image. Patreon wants 1920 × 1080, as an episode's thumbnail is, for a video or text post; an " +
            "audio post shows it square, so give it the episode's COVER. $JOB"
    )
    fun setImage(
        @McpToolParam(description = POST_ID) id: String,
        @McpToolParam(description = "$EPISODE_REF, whose picture it is") episode: String,
        @McpToolParam(description = "Which of the episode's pictures; THUMBNAIL when omitted", required = false) kind: EpisodeImage?,
        @McpToolParam(description = "The picture's file name on Patreon; the one it was made from when omitted", required = false) name: String?,
    ): PatreonJob {
        val post = patreonPostId(id)
        val ref = episode.trim()
        val which = kind ?: EpisodeImage.THUMBNAIL
        val found = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }
        val image = requireNotNull(found.imageId(which)?.let(imagesRepository::findByIdOrNull)) {
            "Episode $ref has no ${which.label} in MetaOS: set it with set_episode_image"
        }
        // Its public URL, which never changes, so it's good however long the change waits
        val picture = checkNotNull(images.ifAvailable) { "MetaOS has no public bucket for pictures" }.toDto(image)
        return quick(PatreonChange(PatreonChangeKind.SET_IMAGE, post, image = PatreonImageRequest(picture.url, name?.trim()?.ifEmpty { null } ?: picture.name)))
    }

    @McpTool(
        name = "prepare_patreon_post",
        annotations = McpTool.McpAnnotations(
            title = "Prepare Patreon post",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Check that a Patreon draft is ready for a person to publish: its title, and its audio or video, " +
            "which Patreon has processed. Says what's missing, and where to publish it: in Patreon's editor (editUrl), " +
            "by a person, as MetaOS doesn't publish. Once it's out, set the episode's link with set_episode_link"
    )
    fun prepare(@McpToolParam(description = POST_ID) id: String): PatreonPostReadiness = readiness(id)

    @McpTool(
        name = "publish_patreon_post",
        annotations = McpTool.McpAnnotations(
            title = "Publish Patreon post",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Deprecated: use `prepare_patreon_post`. MetaOS no longer publishes Patreon posts: a person " +
            "publishes them in Patreon's editor. This checks the draft is ready for them, as prepare_patreon_post does, " +
            "and publishes nothing; at, notify, episode and link are ignored"
    )
    fun publish(
        @McpToolParam(description = POST_ID) id: String,
        @McpToolParam(description = "Ignored", required = false) at: String?,
        @McpToolParam(description = "Ignored", required = false) notify: Boolean?,
        @McpToolParam(description = "Ignored", required = false) episode: String?,
        @McpToolParam(description = "Ignored", required = false) link: Platform?,
    ): PatreonPostReadiness = readiness(id)

    @McpTool(
        name = "delete_patreon_post",
        annotations = McpTool.McpAnnotations(
            title = "Delete Patreon post",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Delete a post from Ogrodje's Patreon, e.g. a draft that isn't needed; Patreon can't bring it back. $JOB"
    )
    fun delete(@McpToolParam(description = POST_ID) id: String): PatreonJob = quick(PatreonChange(PatreonChangeKind.DELETE_POST, patreonPostId(id)))

    @McpTool(
        name = "get_patreon_job",
        annotations = McpTool.McpAnnotations(
            title = "Get Patreon job",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "How a change in Patreon is going: QUEUED (waiting its turn, or to try again after Patreon turned " +
            "it away: why, in error, and when, retryAt), RUNNING, SUCCEEDED with what it made, or FAILED and why"
    )
    fun job(@McpToolParam(description = "The job's ID") id: String): PatreonJob = changes().job(id.trim())

    private fun spec(title: String?, text: String?, access: PatreonAccess?, tiers: List<String>?): PatreonPostSpec {
        require(access != PatreonAccess.TIERS || !tiers.isNullOrEmpty()) { "TIERS needs the tiers' IDs (list_patreon_tiers)" }
        return PatreonPostSpec(title, text, access?.let { PatreonPostAccess(it, tiers.orEmpty()) })
    }

    // A draft is ready once it has a title and its audio or video, which Patreon has processed: it gives the links to
    // download them only then
    private fun readiness(id: String): PatreonPostReadiness {
        val post = patreon.draft(id)
        val media = if (post.type in MEDIA_POSTS) patreon.downloads(id) else emptyList()
        val missing = buildList {
            if (!post.draft) add("Nothing: it's published already, ${post.publishedAt}")
            if (post.title.isNullOrBlank()) add("Its title")
            when (post.type) {
                "podcast" -> if (media.none { it.kind == "AUDIO" }) add("Its audio, which Patreon may still be processing")
                "video_external_file" -> if (media.none { it.kind == "VIDEO" }) add("Its video, which Patreon may still be processing")
                else -> add("Its audio or video (set_patreon_post_media)")
            }
        }
        return PatreonPostReadiness(post, post.draft && missing.isEmpty(), missing, media)
    }

    private companion object {
        val MEDIA_POSTS = setOf("podcast", "video_external_file")
    }
}

/**
 * Whether a Patreon draft is ready for a person to publish, in Patreon's editor ([PatreonDraft.editUrl]), and what's
 * [missing] if it isn't; its [media] as Patreon has them
 */
data class PatreonPostReadiness(
    val post: PatreonDraft,
    val ready: Boolean,
    val missing: List<String>,
    val media: List<PatreonMediaDownload>,
    val publishedBy: String = "A person, in Patreon's editor (post.editUrl): MetaOS doesn't publish",
)
