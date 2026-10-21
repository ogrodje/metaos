package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

data class SessionDto(val id: UUID?, val date: LocalDate, val location: String?, val address: String?)

fun Session.toDto() = SessionDto(id, date, location, address)

@Component
class SessionsMCP(
    private val sessions: SessionsRepository,
    private val episodes: EpisodesRepository,
) {

    @McpTool(
        name = "list_sessions",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "List all sessions, newest first"
    )
    fun list(): List<SessionDto> = sessions.findAllByOrderByDateDesc().map { it.toDto() }

    @Transactional
    @McpTool(
        name = "create_session",
        annotations = McpTool.McpAnnotations(destructiveHint = false),
        description = "Create a new session"
    )
    fun create(
        @McpToolParam(description = "Date of the session, e.g. 2026-10-19") date: LocalDate,
        @McpToolParam(description = "Location, e.g. the name of the venue", required = false) location: String?,
        @McpToolParam(description = "Street address of the location", required = false) address: String?,
    ): SessionDto = sessions.save(Session(date = date, location = location, address = address)).toDto()

    @Transactional
    @McpTool(
        name = "update_session",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Change a session's date, location or address. Omitted fields are left as they are"
    )
    fun update(
        @McpToolParam(description = "Session ID") sessionId: UUID,
        @McpToolParam(description = "Date of the session, e.g. 2026-10-19", required = false) date: LocalDate?,
        @McpToolParam(description = "Location, e.g. the name of the venue; empty to clear", required = false) location: String?,
        @McpToolParam(description = "Street address of the location; empty to clear", required = false) address: String?,
    ): SessionDto {
        val session = requireNotNull(sessions.findByIdOrNull(sessionId)) { "Session $sessionId not found" }
        date?.let { session.date = it }
        location?.let { session.location = it.ifBlank { null } }
        address?.let { session.address = it.ifBlank { null } }
        return session.toDto()
    }

    @Transactional
    @McpTool(
        name = "delete_session",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Soft-delete a session. Its episodes are kept and taken out of the session"
    )
    fun delete(@McpToolParam(description = "Session ID") sessionId: UUID): SessionDto? =
        sessions.findByIdOrNull(sessionId)?.also { session ->
            episodes.findBySessionOrderByCodeDesc(session).forEach { it.session = null }
            sessions.delete(session)
        }?.toDto()

    @Transactional
    @McpTool(
        name = "restore_session",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Bring back a soft-deleted session, with the episodes deleting it took out of it " +
            "(unless they have been deleted or moved since)"
    )
    fun restore(@McpToolParam(description = "Session ID") sessionId: UUID): SessionDto {
        require(sessions.restore(sessionId) == 1) { "There is no deleted session $sessionId" }
        episodes.reattachToSession(sessionId)
        return requireNotNull(sessions.findByIdOrNull(sessionId)).toDto()
    }

    @Transactional(readOnly = true)
    @McpTool(
        name = "list_session_episodes",
        annotations = McpTool.McpAnnotations(readOnlyHint = true),
        description = "List the episodes of a session, latest season and episode first"
    )
    fun listEpisodes(@McpToolParam(description = "Session ID") sessionId: UUID): List<EpisodeDto> {
        val session = requireNotNull(sessions.findByIdOrNull(sessionId)) { "Session $sessionId not found" }
        return episodes.findBySessionOrderByCodeDesc(session).map { it.toDto() }
    }

    @Transactional
    @McpTool(
        name = "set_episode_session",
        annotations = McpTool.McpAnnotations(destructiveHint = true),
        description = "Move an episode into a session, or take it out of the session it is in"
    )
    fun setEpisodeSession(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Session ID; omit to take the episode out of its session", required = false) sessionId: UUID?,
    ): EpisodeDto {
        val episode = requireNotNull(episodes.findByRef(code)) { "Episode $code not found" }
        episode.session = sessionId?.let { requireNotNull(sessions.findByIdOrNull(it)) { "Session $it not found" } }
        return episode.toDto()
    }
}
