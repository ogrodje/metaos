package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.context.event.EventListener
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** A Koofr folder linked to an episode or to its recording session; missing once nothing is at its path, e.g. moved in the Koofr app */
data class KoofrFolderDto(
    val place: String,
    val placeName: String?,
    val path: String,
    val modified: OffsetDateTime?,
    val missing: Boolean,
    val linkedTo: LinkedTo,
)

private data class FolderLink(val place: String, val path: String, val linkedTo: LinkedTo)

@Component
@ConditionalOnKoofr
class KoofrFoldersMCP(
    private val koofr: Koofr,
    private val episodes: EpisodesRepository,
    private val sessions: SessionsRepository,
    private val episodeFolders: EpisodeKoofrFoldersRepository,
    private val sessionFolders: SessionKoofrFoldersRepository,
) {

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun findSession(id: UUID) = requireNotNull(sessions.findByIdOrNull(id)) { "Session $id not found" }

    // A folder that is there, in a place shared with the bot
    private fun folder(placeRef: String, path: String): KoofrFolderDto {
        val place = koofr.sharedPlace(placeRef)
        val folder = requireNotNull(koofr.file(place.id, koofrPath(path))) { "There's no $path in ${place.name}" }
        require(folder.folder) { "$path is a file, not a folder" }
        return KoofrFolderDto(place.id, place.name, folder.path, folder.modified, missing = false, LinkedTo.EPISODE)
    }

    // Unlinking also works for a place that isn't shared with the bot anymore, by its ID
    private fun placeId(ref: String) = runCatching { koofr.sharedPlace(ref).id }.getOrDefault(ref)

    private fun toDtos(links: List<FolderLink>): List<KoofrFolderDto> {
        val names = koofr.places().associate { it.id to it.name }
        return links.map { link ->
            val folder = if (link.place in names) koofr.file(link.place, link.path)?.takeIf { it.folder } else null
            KoofrFolderDto(link.place, names[link.place], link.path, folder?.modified, folder == null, link.linkedTo)
        }.sortedWith(compareBy({ it.placeName }, { it.path }))
    }

    @McpTool(
        name = "list_episode_koofr_folders",
        annotations = McpTool.McpAnnotations(
            title = "List episode Koofr folders",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the Koofr folders of an episode: those linked to the episode and those linked to its recording " +
            "session; linkedTo says which. Folders moved outside MetaOS are listed as missing"
    )
    fun listEpisodeFolders(@McpToolParam(description = EPISODE_REF) code: String): List<KoofrFolderDto> {
        val episode = findEpisode(code)
        val ofEpisode = episodeFolders.findByEpisode(episode).map { FolderLink(it.place, it.path, LinkedTo.EPISODE) }
        // A folder linked to both is the episode's
        val ofSession = episode.session?.let(sessionFolders::findBySession).orEmpty()
            .map { FolderLink(it.place, it.path, LinkedTo.SESSION) }
            .filter { link -> ofEpisode.none { it.place == link.place && it.path == link.path } }
        return toDtos(ofEpisode + ofSession)
    }

    @Transactional
    @McpTool(
        name = "link_episode_koofr_folder",
        annotations = McpTool.McpAnnotations(
            title = "Link Koofr folder to episode",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Link a Koofr folder to an episode it is about, e.g. the folder with its recordings. A folder for " +
            "a whole recording session goes to the session (link_session_koofr_folder)"
    )
    fun linkEpisode(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = KOOFR_PATH) path: String,
    ): KoofrFolderDto {
        val episode = findEpisode(code)
        val folder = folder(place, path)
        if (episodeFolders.findByEpisodeAndPlaceAndPath(episode, folder.place, folder.path) == null) {
            episodeFolders.save(EpisodeKoofrFolder(episode, folder.place, folder.path))
        }
        return folder
    }

    @Transactional
    @McpTool(
        name = "unlink_episode_koofr_folder",
        annotations = McpTool.McpAnnotations(
            title = "Unlink Koofr folder from episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Unlink a Koofr folder from an episode. The folder stays in Koofr"
    )
    fun unlinkEpisode(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = KOOFR_PATH) path: String,
    ): KoofrFolderDto {
        val episode = findEpisode(code)
        val link = requireNotNull(episodeFolders.findByEpisodeAndPlaceAndPath(episode, placeId(place), koofrPath(path))) {
            "$path isn't linked to episode ${episode.label}"
        }
        episodeFolders.delete(link)
        return toDtos(listOf(FolderLink(link.place, link.path, LinkedTo.EPISODE))).single()
    }

    @McpTool(
        name = "list_session_koofr_folders",
        annotations = McpTool.McpAnnotations(
            title = "List session Koofr folders",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the Koofr folders linked to a recording session. Folders moved outside MetaOS are listed as missing"
    )
    fun listSessionFolders(@McpToolParam(description = "Session ID") sessionId: UUID): List<KoofrFolderDto> =
        toDtos(sessionFolders.findBySession(findSession(sessionId)).map { FolderLink(it.place, it.path, LinkedTo.SESSION) })

    @Transactional
    @McpTool(
        name = "link_session_koofr_folder",
        annotations = McpTool.McpAnnotations(
            title = "Link Koofr folder to session",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Link a Koofr folder to a recording session it is about, e.g. the folder with everything recorded that day"
    )
    fun linkSession(
        @McpToolParam(description = "Session ID") sessionId: UUID,
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = KOOFR_PATH) path: String,
    ): KoofrFolderDto {
        val session = findSession(sessionId)
        val folder = folder(place, path)
        if (sessionFolders.findBySessionAndPlaceAndPath(session, folder.place, folder.path) == null) {
            sessionFolders.save(SessionKoofrFolder(session, folder.place, folder.path))
        }
        return folder.copy(linkedTo = LinkedTo.SESSION)
    }

    @Transactional
    @McpTool(
        name = "unlink_session_koofr_folder",
        annotations = McpTool.McpAnnotations(
            title = "Unlink Koofr folder from session",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Unlink a Koofr folder from a recording session. The folder stays in Koofr"
    )
    fun unlinkSession(
        @McpToolParam(description = "Session ID") sessionId: UUID,
        @McpToolParam(description = KOOFR_PLACE) place: String,
        @McpToolParam(description = KOOFR_PATH) path: String,
    ): KoofrFolderDto {
        val session = findSession(sessionId)
        val link = requireNotNull(sessionFolders.findBySessionAndPlaceAndPath(session, placeId(place), koofrPath(path))) {
            "$path isn't linked to session $sessionId"
        }
        sessionFolders.delete(link)
        return toDtos(listOf(FolderLink(link.place, link.path, LinkedTo.SESSION))).single()
    }

    // move_koofr_file moved something: the links to it, and to the folders in it, follow - in its transaction
    @Transactional
    @EventListener
    fun moved(move: KoofrMoved) {
        episodeFolders.moved(move.fromPlace, move.fromPath, move.toPlace, move.toPath)
        sessionFolders.moved(move.fromPlace, move.fromPath, move.toPlace, move.toPath)
    }
}
