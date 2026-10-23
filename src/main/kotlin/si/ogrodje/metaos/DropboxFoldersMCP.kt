package si.ogrodje.metaos

import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A Dropbox folder linked to an episode or to its recording session, where it is now; missing once it's gone from its
 * place. Its url opens it for anyone with access to it; when there's none, urlError says why Dropbox didn't make one.
 */
data class DropboxFolderDto(
    val place: String,
    val placeName: String?,
    val path: String,
    val missing: Boolean,
    val linkedTo: LinkedTo,
    val url: String?,
    val urlError: String? = null,
)

private data class DropboxLink(val place: String, val folderId: String, val path: String, val url: String?, val linkedTo: LinkedTo)

private const val STAYS_LINKED = "Folders moved or renamed, also in the Dropbox app, stay linked; those deleted or moved out " +
    "of their shared folder are listed as missing"

private const val WITH_URL = "with a link that only those with access to the folder can open (url). Linking a folder " +
    "again makes the link when there's none yet"

@Component
@ConditionalOnDropbox
class DropboxFoldersMCP(
    private val dropbox: Dropbox,
    private val episodes: EpisodesRepository,
    private val sessions: SessionsRepository,
    private val episodeFolders: EpisodeDropboxFoldersRepository,
    private val sessionFolders: SessionDropboxFoldersRepository,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun findSession(id: UUID) = requireNotNull(sessions.findByIdOrNull(id)) { "Session $id not found" }

    // A folder that is there, in a place shared with the bot - not the shared folder itself, which has no ID
    private fun folder(placeRef: String, path: String): Pair<DropboxPlace, DropboxFile> {
        val place = dropbox.sharedPlace(placeRef)
        val folder = requireNotNull(dropbox.file(place.id, dropboxPath(path))) { "There's no $path in ${place.name}" }
        require(folder.folder) { "$path is a file, not a folder" }
        require(folder.id != null) { "${place.name} itself can't be linked, only the folders in it" }
        return place to folder
    }

    // A folder is linked without a link to it when Dropbox won't make one for members only; never with one for anyone else
    private fun membersLink(folderId: String): Result<String> =
        runCatching { dropbox.membersLink(folderId) }.onFailure { logger.warn("No Dropbox link to {}: {}", folderId, it.message) }

    // Unlinking also works for a place that isn't shared with the bot anymore, by its ID
    private fun placeId(ref: String) = runCatching { dropbox.sharedPlace(ref).id }.getOrDefault(ref)

    // The ID of what's at a path now, so a link is found wherever its folder went
    private fun idAt(place: String, path: String) = runCatching { dropbox.file(place, path)?.id }.getOrNull()

    // By ID, so a folder that was moved or renamed is listed where it is now
    private fun toDtos(links: List<DropboxLink>): List<DropboxFolderDto> {
        val names = dropbox.places().associate { it.id to it.name }
        return links.map { link ->
            val folder = if (link.place in names) dropbox.fileById(link.place, link.folderId)?.takeIf { it.folder } else null
            DropboxFolderDto(link.place, names[link.place], folder?.path ?: link.path, folder == null, link.linkedTo, link.url)
        }.sortedWith(compareBy({ it.placeName }, { it.path }))
    }

    @McpTool(
        name = "list_episode_dropbox_folders",
        annotations = McpTool.McpAnnotations(
            title = "List episode Dropbox folders",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the Dropbox folders of an episode, e.g. its artwork: those linked to the episode and those linked " +
            "to its recording session; linkedTo says which. $STAYS_LINKED"
    )
    fun listEpisodeFolders(@McpToolParam(description = EPISODE_REF) code: String): List<DropboxFolderDto> {
        val episode = findEpisode(code)
        val ofEpisode = episodeFolders.findByEpisode(episode).map { DropboxLink(it.place, it.folderId, it.path, it.url, LinkedTo.EPISODE) }
        // A folder linked to both is the episode's
        val ofSession = episode.session?.let(sessionFolders::findBySession).orEmpty()
            .map { DropboxLink(it.place, it.folderId, it.path, it.url, LinkedTo.SESSION) }
            .filter { link -> ofEpisode.none { it.folderId == link.folderId } }
        return toDtos(ofEpisode + ofSession)
    }

    @Transactional
    @McpTool(
        name = "link_episode_dropbox_folder",
        annotations = McpTool.McpAnnotations(
            title = "Link Dropbox folder to episode",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Link a Dropbox folder to an episode it is about, e.g. the folder with its artwork, $WITH_URL. A folder " +
            "for a whole recording session goes to the session (link_session_dropbox_folder)"
    )
    fun linkEpisode(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = DROPBOX_PATH) path: String,
    ): DropboxFolderDto {
        val episode = findEpisode(code)
        val (shared, folder) = folder(place, path)
        // With its link from the start, or one made now for a link without it
        val link = episodeFolders.findByEpisodeAndFolderId(episode, folder.id!!)
        val url = link?.url?.let { Result.success(it) } ?: membersLink(folder.id)
        if (link == null) episodeFolders.save(EpisodeDropboxFolder(episode, shared.id, folder.id, folder.path, url.getOrNull()))
        else if (link.url == null) link.url = url.getOrNull()
        return DropboxFolderDto(shared.id, shared.name, folder.path, missing = false, LinkedTo.EPISODE, url.getOrNull(), url.exceptionOrNull()?.message)
    }

    @Transactional
    @McpTool(
        name = "unlink_episode_dropbox_folder",
        annotations = McpTool.McpAnnotations(
            title = "Unlink Dropbox folder from episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Unlink a Dropbox folder from an episode. The folder stays in Dropbox"
    )
    fun unlinkEpisode(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = "$DROPBOX_PATH; for a missing folder, the path list_episode_dropbox_folders gives") path: String,
    ): DropboxFolderDto {
        val episode = findEpisode(code)
        val (placeId, at) = placeId(place) to dropboxPath(path)
        val link = requireNotNull(
            idAt(placeId, at)?.let { episodeFolders.findByEpisodeAndFolderId(episode, it) }
                ?: episodeFolders.findByEpisodeAndPlaceAndPath(episode, placeId, at)
        ) { "$path isn't linked to episode ${episode.label}" }
        val unlinked = toDtos(listOf(DropboxLink(link.place, link.folderId, link.path, link.url, LinkedTo.EPISODE))).single()
        episodeFolders.delete(link)
        return unlinked
    }

    @McpTool(
        name = "list_session_dropbox_folders",
        annotations = McpTool.McpAnnotations(
            title = "List session Dropbox folders",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the Dropbox folders linked to a recording session. $STAYS_LINKED"
    )
    fun listSessionFolders(@McpToolParam(description = "Session ID") sessionId: UUID): List<DropboxFolderDto> =
        toDtos(sessionFolders.findBySession(findSession(sessionId)).map { DropboxLink(it.place, it.folderId, it.path, it.url, LinkedTo.SESSION) })

    @Transactional
    @McpTool(
        name = "link_session_dropbox_folder",
        annotations = McpTool.McpAnnotations(
            title = "Link Dropbox folder to session",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Link a Dropbox folder to a recording session it is about, e.g. the folder with that day's photos, $WITH_URL"
    )
    fun linkSession(
        @McpToolParam(description = "Session ID") sessionId: UUID,
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = DROPBOX_PATH) path: String,
    ): DropboxFolderDto {
        val session = findSession(sessionId)
        val (shared, folder) = folder(place, path)
        // With its link from the start, or one made now for a link without it
        val link = sessionFolders.findBySessionAndFolderId(session, folder.id!!)
        val url = link?.url?.let { Result.success(it) } ?: membersLink(folder.id)
        if (link == null) sessionFolders.save(SessionDropboxFolder(session, shared.id, folder.id, folder.path, url.getOrNull()))
        else if (link.url == null) link.url = url.getOrNull()
        return DropboxFolderDto(shared.id, shared.name, folder.path, missing = false, LinkedTo.SESSION, url.getOrNull(), url.exceptionOrNull()?.message)
    }

    @Transactional
    @McpTool(
        name = "unlink_session_dropbox_folder",
        annotations = McpTool.McpAnnotations(
            title = "Unlink Dropbox folder from session",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Unlink a Dropbox folder from a recording session. The folder stays in Dropbox"
    )
    fun unlinkSession(
        @McpToolParam(description = "Session ID") sessionId: UUID,
        @McpToolParam(description = DROPBOX_PLACE) place: String,
        @McpToolParam(description = "$DROPBOX_PATH; for a missing folder, the path list_session_dropbox_folders gives") path: String,
    ): DropboxFolderDto {
        val session = findSession(sessionId)
        val (placeId, at) = placeId(place) to dropboxPath(path)
        val link = requireNotNull(
            idAt(placeId, at)?.let { sessionFolders.findBySessionAndFolderId(session, it) }
                ?: sessionFolders.findBySessionAndPlaceAndPath(session, placeId, at)
        ) { "$path isn't linked to session $sessionId" }
        val unlinked = toDtos(listOf(DropboxLink(link.place, link.folderId, link.path, link.url, LinkedTo.SESSION))).single()
        sessionFolders.delete(link)
        return unlinked
    }
}
