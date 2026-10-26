package si.ogrodje.metaos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc // the context the GraphQL tests have, so one fewer to start (TestcontainersConfig)
@Import(TestcontainersConfig::class)
class CommentsTest @Autowired constructor(
    private val commentsMCP: CommentsMCP,
    private val episodesMCP: EpisodesMCP,
    private val sessionsMCP: SessionsMCP,
    private val peopleMCP: PeopleMCP,
    private val auditMCP: AuditMCP,
    private val history: History,
    private val accounts: Accounts,
) {

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun signInAs(username: String, email: String? = null) {
        val jwt = Jwt.withTokenValue("token").header("alg", "none")
            .subject("$username-id").claim("preferred_username", username).claim("azp", "claude-code")
            .apply { if (email != null) claim("email", email).claim("email_verified", true) }
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        // As a request through AccountsFilter would
        accounts.seen(jwt)
    }

    @Test
    fun `comments as the signed-in user and their person, oldest first, with the tool they used`() {
        signInAs("oto", email = ACCOUNT_LINKER)
        peopleMCP.create(name = "Maja Kovač", email = "maja.comments@example.com")
        signInAs("maja", email = "maja.comments@example.com")
        episodesMCP.create(code = "cm-101", title = "Lokalni LLM-ji")
        commentsMCP.commentOnEpisode("cm-101", "\n\n  - first, *indented*  \n\n")
        signInAs("luka")
        commentsMCP.commentOnEpisode("cm-101", "Second")

        val comments = commentsMCP.listEpisodeComments("cm-101")

        // Blank lines and trailing spaces go, the first line's indent stays
        assertEquals(listOf("  - first, *indented*", "Second"), comments.map { it.body })
        assertEquals(listOf("Maja Kovač", "luka"), comments.map { it.author })
        assertEquals(listOf("maja", "luka"), comments.map { it.username })
        assertNotNull(comments[0].personId)
        assertNull(comments[1].personId)
        assertEquals(listOf("comment_on_episode", "comment_on_episode"), comments.map { it.tool })
        assertTrue(comments.all { it.editedAt == null })
    }

    @Test
    fun `turns down a comment with nothing to say, or too much`() {
        signInAs("ana")
        episodesMCP.create(code = "cm-102", title = "Empty")

        assertFailsWith<IllegalArgumentException> { commentsMCP.commentOnEpisode("cm-102", " \n\t ") }
        assertFailsWith<IllegalArgumentException> { commentsMCP.commentOnEpisode("cm-102", "x".repeat(MAX_COMMENT_LENGTH + 1)) }
        commentsMCP.commentOnEpisode("cm-102", "x".repeat(MAX_COMMENT_LENGTH))
        assertFailsWith<IllegalArgumentException> { commentsMCP.commentOnEpisode("cm-404", "On nothing") }
    }

    @Test
    fun `only its author edits or deletes a comment`() {
        signInAs("ana")
        episodesMCP.create(code = "cm-103", title = "Edits")
        val id = checkNotNull(commentsMCP.commentOnEpisode("cm-103", "Frist").id)

        signInAs("bojan")
        assertFailsWith<IllegalArgumentException> { commentsMCP.editComment(id, "Mine now") }
        assertFailsWith<IllegalArgumentException> { commentsMCP.deleteComment(id) }

        signInAs("ana")
        val edited = commentsMCP.editComment(id, "First")
        assertEquals("First", edited.body)
        assertNotNull(edited.editedAt)
        // The same text again changes nothing, so it isn't edited again
        assertTrue(edited.editedAt!!.isEqual(commentsMCP.editComment(id, "First  ").editedAt))

        commentsMCP.deleteComment(id)
        assertEquals(emptyList(), commentsMCP.listEpisodeComments("cm-103"))
        assertFailsWith<IllegalArgumentException> { commentsMCP.editComment(id, "Back") }
    }

    @Test
    fun `an episode's and a session's history tell of their comments`() {
        signInAs("cene")
        episodesMCP.create(code = "cm-104", title = "History")
        val comment = commentsMCP.commentOnEpisode("cm-104", "We should ask   someone\nwho runs them in production. " + "a".repeat(120))
        commentsMCP.editComment(comment.id!!, "We should ask someone who runs them.")
        commentsMCP.deleteComment(comment.id)
        val session = sessionsMCP.create(LocalDate.of(2026, 11, 4), location = "Studio", address = null)
        commentsMCP.commentOnSession(session.id!!, "Bring the good microphones")

        val episode = history.of(checkNotNull(episodesMCP.get("cm-104")?.id))

        assertEquals(listOf("Created the episode", "Commented", "Edited a comment", "Deleted a comment"), episode.map { it.summary })
        val excerpt = episode[1].fields.single()
        assertEquals("Comment", excerpt.field)
        assertTrue(excerpt.to!!.startsWith("We should ask someone who runs them in production. aaa"))
        assertTrue(excerpt.to.endsWith("…") && excerpt.to.length == 101)
        assertEquals(listOf("comment_on_episode", "edit_comment", "delete_comment"), episode.drop(1).map { it.tool })

        assertEquals(listOf("Created the session", "Commented"), history.of(session.id).map { it.summary })
        assertEquals(listOf("sessions", "comments"), auditMCP.history(session.id).map { it.table })
    }

    @Test
    fun `nothing is written on a deleted episode`() {
        signInAs("dora")
        episodesMCP.create(code = "cm-105", title = "Deleted")
        val comment = commentsMCP.commentOnEpisode("cm-105", "Before")
        episodesMCP.delete("cm-105")

        assertFailsWith<IllegalArgumentException> { commentsMCP.commentOnEpisode("cm-105", "After") }
        assertFailsWith<IllegalArgumentException> { commentsMCP.editComment(comment.id!!, "After") }
    }
}
