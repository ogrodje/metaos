package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** A comment as MCP clients get it: what it says, who wrote it and when, and the MCP tool they wrote it with */
data class CommentDto(
    val id: UUID?,
    // Markdown
    val body: String,
    // Their person's name, or else their username
    val author: String,
    val username: String?,
    val personId: UUID?,
    val createdAt: OffsetDateTime,
    val editedAt: OffsetDateTime?,
    // Null for MetaOS's own pages
    val tool: String?,
)

/** The name a comment's author goes by: their person's, from [people] by Keycloak user ID, or else their username */
fun Comment.authorName(people: Map<String, Person>) = people[authorId]?.name ?: author ?: "Someone"

/**
 * Episodes' and recording sessions' discussions (Comments.kt). The tools that write are transactions of their own, so
 * the audit log has the tool's name (AuditingTransactionManager).
 */
@Component
class CommentsMCP(
    private val comments: Comments,
    private val episodes: EpisodesRepository,
    private val sessions: SessionsRepository,
    private val auditLog: AuditLog,
) {

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }
    private fun findSession(id: UUID) = requireNotNull(sessions.findByIdOrNull(id)) { "Session $id not found" }

    // With their authors' names and the tools they were written with, in a query each
    private fun dtos(of: List<Comment>): List<CommentDto> {
        val people = comments.people(of)
        val tools = auditLog.creatingTools("comments", of.mapNotNull { it.id })
        return of.map {
            CommentDto(it.id, it.body, it.authorName(people), it.author, people[it.authorId]?.id, it.createdAt, it.editedAt, tools[it.id])
        }
    }

    private fun dto(comment: Comment) = dtos(listOf(comment)).single()

    @McpTool(
        name = "list_episode_comments",
        annotations = McpTool.McpAnnotations(
            title = "List episode comments",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the comments in an episode's discussion (ideas too), oldest first: what each says, in " +
            "Markdown, who wrote it and when, whether it was edited, and the MCP tool it was written with, if any"
    )
    fun listEpisodeComments(@McpToolParam(description = EPISODE_REF) code: String): List<CommentDto> =
        dtos(comments.of(findEpisode(code)))

    @McpTool(
        name = "list_session_comments",
        annotations = McpTool.McpAnnotations(
            title = "List session comments",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the comments in a recording session's discussion, oldest first, as list_episode_comments does"
    )
    fun listSessionComments(@McpToolParam(description = "Session ID") sessionId: UUID): List<CommentDto> =
        dtos(comments.of(findSession(sessionId)))

    @Transactional
    @McpTool(
        name = "comment_on_episode",
        annotations = McpTool.McpAnnotations(
            title = "Comment on episode",
            readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false
        ),
        description = "Comment on an episode or idea as the signed-in user, in Markdown. The team sees it in the " +
            "episode's discussion and history, marked as written with this tool"
    )
    fun commentOnEpisode(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "What to say, in Markdown; at most $MAX_COMMENT_LENGTH characters") body: String,
    ): CommentDto = dto(comments.comment(findEpisode(code), body))

    @Transactional
    @McpTool(
        name = "comment_on_session",
        annotations = McpTool.McpAnnotations(
            title = "Comment on session",
            readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false
        ),
        description = "Comment on a recording session as the signed-in user, in Markdown, as comment_on_episode does"
    )
    fun commentOnSession(
        @McpToolParam(description = "Session ID") sessionId: UUID,
        @McpToolParam(description = "What to say, in Markdown; at most $MAX_COMMENT_LENGTH characters") body: String,
    ): CommentDto = dto(comments.comment(findSession(sessionId), body))

    @Transactional
    @McpTool(
        name = "edit_comment",
        annotations = McpTool.McpAnnotations(
            title = "Edit comment",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Change the text of one of the signed-in user's comments. It shows as edited, and the history " +
            "keeps what it said before"
    )
    fun editComment(
        @McpToolParam(description = "Comment ID") commentId: UUID,
        @McpToolParam(description = "Its new text, in Markdown; at most $MAX_COMMENT_LENGTH characters") body: String,
    ): CommentDto = dto(comments.edit(commentId, body))

    @Transactional
    @McpTool(
        name = "delete_comment",
        annotations = McpTool.McpAnnotations(
            title = "Delete comment",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Delete one of the signed-in user's comments. It leaves the discussion; the history keeps that it " +
            "was there"
    )
    fun deleteComment(@McpToolParam(description = "Comment ID") commentId: UUID): CommentDto =
        dto(comments.delete(commentId))
}
