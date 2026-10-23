package si.ogrodje.metaos

import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.UUID

/** Who wrote a comment, as GraphQL has them: their person's name, or else their username */
data class CommentAuthorDto(val name: String, val username: String?, val person: Person?)

// Episodes' and sessions' discussions (Comments.kt) are the team's, as their history is, so anyone else gets none. Lists
// may ask for them too, so they're batched, as are their authors and the tools they were written with.
@Controller
class CommentsGraphQL(
    private val comments: Comments,
    private val episodes: EpisodesRepository,
    private val sessions: SessionsRepository,
    private val auditLog: AuditLog,
) {

    @BatchMapping(typeName = "Episode", field = "comments")
    fun episodeComments(episodes: List<Episode>): Map<Episode, List<Comment>> =
        if (signedIn()) comments.onEpisodes(episodes) else episodes.associateWith { emptyList() }

    @BatchMapping(typeName = "Session", field = "comments")
    fun sessionComments(sessions: List<Session>): Map<Session, List<Comment>> =
        if (signedIn()) comments.onSessions(sessions) else sessions.associateWith { emptyList() }

    @BatchMapping(typeName = "Comment", field = "author")
    fun authors(of: List<Comment>): Map<Comment, CommentAuthorDto> {
        val people = comments.people(of)
        return of.associateWith { CommentAuthorDto(it.authorName(people), it.author, people[it.authorId]) }
    }

    // Those written another way than with an MCP tool are left out, and so null
    @BatchMapping(typeName = "Comment", field = "tool")
    fun tools(of: List<Comment>): Map<Comment, String> {
        val tools = auditLog.creatingTools("comments", of.mapNotNull { it.id })
        return of.mapNotNull { comment -> tools[comment.id]?.let { comment to it } }.toMap()
    }

    @SchemaMapping(typeName = "Comment")
    fun mine(comment: Comment): Boolean = comment.authorId == currentJwt()?.subject

    @MutationMapping
    @PreAuthorize(WRITES)
    fun commentOnEpisode(@Argument ref: String, @Argument body: String): Comment =
        comments.comment(requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }, body)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun commentOnSession(@Argument id: UUID, @Argument body: String): Comment =
        comments.comment(requireNotNull(sessions.findByIdOrNull(id)) { "Session $id not found" }, body)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun editComment(@Argument id: UUID, @Argument body: String): Comment = comments.edit(id, body)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun deleteComment(@Argument id: UUID): UUID = checkNotNull(comments.delete(id).id)
}
