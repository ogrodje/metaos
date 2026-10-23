package si.ogrodje.metaos

import jakarta.persistence.*
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The most a comment may say, in characters */
const val MAX_COMMENT_LENGTH = 20_000

// To the microsecond, as Postgres keeps it, so a comment says the same time before and after it's saved
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

/**
 * A comment in an episode's or a recording session's discussion, as on GitHub: Markdown, by whoever was signed in,
 * through MetaOS's pages or MCP (V24__comments.sql). What it's on is linked by ID rather than as a to-one, which would be
 * eager (Hibernate can't proxy a to-one whose target uses @SoftDelete) and fail on one deleted. Its author's person is
 * their account's (Accounts), when it's shown.
 */
@Entity
@Table(name = "comments")
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
class Comment(
    // On one of the two
    @Column(name = "episode_id") val episodeId: UUID?,
    @Column(name = "session_id") val sessionId: UUID?,
    // Who wrote it: their Keycloak user ID, which their account is by, and their username then
    @Column(nullable = false, columnDefinition = "text") val authorId: String,
    @Column(columnDefinition = "text") val author: String?,
    // Markdown
    @Column(nullable = false, columnDefinition = "text") var body: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Column(nullable = false) val createdAt: OffsetDateTime = now()

    // When its text last changed; null if it never did
    var editedAt: OffsetDateTime? = null

    override fun equals(other: Any?) = this === other || (other is Comment && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Comment(id=$id, episodeId=$episodeId, sessionId=$sessionId)"
}

interface CommentsRepository : JpaRepository<Comment, UUID> {
    fun findByEpisodeIdInOrderByCreatedAtAsc(episodeIds: Collection<UUID>): List<Comment>
    fun findBySessionIdInOrderByCreatedAtAsc(sessionIds: Collection<UUID>): List<Comment>
}

// Blank lines before the text; its first line's indent stays, as it may start a code block
private val LEADING_BLANK_LINES = Regex("^(?:[ \\t]*\\r?\\n)+")

/**
 * Episodes' and sessions' discussions, for their MCP tools (CommentsMCP) and GraphQL (CommentsGraphQL), so both follow
 * the same rules: anyone approved writes as themselves, on an episode or session that's there, and only the author
 * changes or deletes a comment.
 */
@Component
class Comments(
    private val comments: CommentsRepository,
    private val episodes: EpisodesRepository,
    private val sessions: SessionsRepository,
    private val accounts: Accounts,
) {

    /** An episode's discussion, oldest first */
    fun of(episode: Episode): List<Comment> = onEpisodes(listOf(episode))[episode].orEmpty()

    fun of(session: Session): List<Comment> = onSessions(listOf(session))[session].orEmpty()

    /** The discussions of several episodes, in one query */
    fun onEpisodes(episodes: Collection<Episode>): Map<Episode, List<Comment>> {
        val byId = comments.findByEpisodeIdInOrderByCreatedAtAsc(episodes.mapNotNull { it.id }).groupBy { it.episodeId }
        return episodes.associateWith { byId[it.id].orEmpty() }
    }

    fun onSessions(sessions: Collection<Session>): Map<Session, List<Comment>> {
        val byId = comments.findBySessionIdInOrderByCreatedAtAsc(sessions.mapNotNull { it.id }).groupBy { it.sessionId }
        return sessions.associateWith { byId[it.id].orEmpty() }
    }

    /** The people the comments' authors are, by their Keycloak user IDs; those not linked, or deleted since, are left out */
    fun people(comments: Collection<Comment>): Map<String, Person> = accounts.people(comments.map { it.authorId })

    @Transactional
    fun comment(episode: Episode, body: String): Comment = write(episodeId = checkNotNull(episode.id), sessionId = null, body)

    @Transactional
    fun comment(session: Session, body: String): Comment = write(episodeId = null, sessionId = checkNotNull(session.id), body)

    /** Changes the text of one of the signed-in user's comments; the same text again changes nothing */
    @Transactional
    fun edit(id: UUID, body: String): Comment {
        val comment = own(id)
        val text = text(body)
        if (text != comment.body) {
            comment.body = text
            comment.editedAt = now()
        }
        return comment
    }

    /** Soft-deletes one of the signed-in user's comments; the audit log keeps it */
    @Transactional
    fun delete(id: UUID): Comment = own(id).also(comments::delete)

    private fun write(episodeId: UUID?, sessionId: UUID?, body: String): Comment {
        val jwt = user()
        val comment = Comment(
            episodeId = episodeId,
            sessionId = sessionId,
            authorId = checkNotNull(jwt.subject) { "The token doesn't say who's signed in" },
            author = jwt.getClaimAsString("preferred_username"),
            body = text(body),
        )
        // Flushed, so the audit log has it within the transaction too, e.g. for the tool that wrote it (CommentsMCP)
        return comments.saveAndFlush(comment)
    }

    // The signed-in user's, on an episode or session that's still there
    private fun own(id: UUID): Comment {
        val comment = requireNotNull(comments.findByIdOrNull(id)) { "Comment $id not found" }
        require(comment.authorId == user().subject) { "Only whoever wrote a comment can change or delete it" }
        val on = comment.episodeId?.let(episodes::existsById) ?: comment.sessionId?.let(sessions::existsById) ?: false
        require(on) { "The comment's episode or session has been deleted" }
        return comment
    }

    private fun user(): Jwt = checkNotNull(currentJwt()) { "Not signed in" }

    private fun text(body: String): String = body.replace(LEADING_BLANK_LINES, "").trimEnd().also {
        require(it.isNotBlank()) { "A comment needs some text" }
        require(it.length <= MAX_COMMENT_LENGTH) { "A comment can be at most $MAX_COMMENT_LENGTH characters long" }
    }
}
