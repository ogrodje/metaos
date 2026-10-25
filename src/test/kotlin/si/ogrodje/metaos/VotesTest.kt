package si.ogrodje.metaos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
class VotesTest @Autowired constructor(
    private val votesMCP: VotesMCP,
    private val episodesMCP: EpisodesMCP,
    private val history: History,
    private val episodes: EpisodesRepository,
    private val votes: Votes,
) {

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun signInAs(username: String) {
        val jwt = Jwt.withTokenValue("token").header("alg", "none")
            .subject("$username-id").claim("preferred_username", username).claim("azp", "claude-code").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `votes once each as the signed-in user, who changes or takes back their own`() {
        signInAs("ana")
        episodesMCP.create(code = "vt-101", title = "Passkeys v praksi")
        assertEquals(VotesOutDto(1, 1, 0, Vote.UP, listOf(VoterOutDto("ana", "ana", null, Vote.UP))), votesMCP.voteOnIdea("vt-101", Vote.UP))
        // The same vote again changes nothing
        assertEquals(1, votesMCP.voteOnIdea("vt-101", Vote.UP).score)

        signInAs("bojan")
        votesMCP.voteOnIdea("vt-101", Vote.UP)
        signInAs("cene")
        val votes = votesMCP.voteOnIdea("vt-101", Vote.DOWN)
        assertEquals(1, votes.score)
        assertEquals(2 to 1, votes.up to votes.down)
        assertEquals(Vote.DOWN, votes.mine)
        assertEquals(listOf(Vote.UP, Vote.UP, Vote.DOWN), votes.voters.map { it.vote })

        // Each changes only their own: Ana's vote is still hers
        signInAs("ana")
        assertEquals(Vote.UP, votesMCP.listIdeaVotes("vt-101").mine)
        val back = votesMCP.voteOnIdea("vt-101", Vote.NONE)
        assertEquals(Vote.NONE, back.mine)
        assertEquals(listOf("bojan", "cene"), back.voters.map { it.username })
        // Nothing to take back changes nothing
        assertEquals(back, votesMCP.voteOnIdea("vt-101", Vote.NONE))
    }

    @Test
    fun `only ideas take votes, and one that moves on keeps its own`() {
        signInAs("ana")
        episodesMCP.create(code = "vt-102", title = "On hold")
        votesMCP.voteOnIdea("vt-102", Vote.UP)
        episodesMCP.setStatus("vt-102", EpisodeStatus.ON_HOLD)

        val refused = assertFailsWith<IllegalArgumentException> { votesMCP.voteOnIdea("vt-102", Vote.DOWN) }
        assertEquals("Only ideas are voted on; vt-102 is on hold", refused.message)
        assertFailsWith<IllegalArgumentException> { votesMCP.voteOnIdea("vt-102", Vote.NONE) }
        assertEquals(1, votesMCP.listIdeaVotes("vt-102").score)

        assertFailsWith<IllegalArgumentException> { votesMCP.voteOnIdea("vt-404", Vote.UP) }
        episodesMCP.delete("vt-102")
        assertFailsWith<IllegalArgumentException> { votesMCP.voteOnIdea("vt-102", Vote.UP) }
    }

    @Test
    fun `the same user voting at once from several places has one vote`() {
        signInAs("ana")
        episodesMCP.create(code = "vt-105", title = "Two tabs")
        val auth = SecurityContextHolder.getContext().authentication
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val votes = (1..8).map {
                pool.submit<VotesOutDto> {
                    SecurityContextHolder.getContext().authentication = auth
                    start.await()
                    try { votesMCP.voteOnIdea("vt-105", Vote.UP) } finally { SecurityContextHolder.clearContext() }
                }
            }
            start.countDown()
            votes.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
        assertEquals(1 to 1, votesMCP.listIdeaVotes("vt-105").let { it.score to it.voters.size })
    }

    @Test
    fun `checks the idea as it is now, not as it was loaded`() {
        signInAs("ana")
        episodesMCP.create(code = "vt-106", title = "Moved on meanwhile")
        val loaded = checkNotNull(episodes.findByCode("vt-106"))
        episodesMCP.setStatus("vt-106", EpisodeStatus.ON_HOLD)

        assertEquals(EpisodeStatus.IDEA, loaded.status)
        assertFailsWith<IllegalArgumentException> { votes.vote(loaded, Vote.UP) }
        episodesMCP.delete("vt-106")
        val deleted = assertFailsWith<IllegalArgumentException> { votes.vote(loaded, Vote.UP) }
        assertEquals("Episode vt-106 has been deleted", deleted.message)
    }

    @Test
    fun `turns down a vote from no one`() {
        signInAs("ana")
        episodesMCP.create(code = "vt-103", title = "Anonymous")
        SecurityContextHolder.clearContext()
        assertFailsWith<IllegalStateException> { votesMCP.voteOnIdea("vt-103", Vote.UP) }
    }

    @Test
    fun `the idea's history tells the votes, with the tool they were cast with`() {
        signInAs("ana")
        episodesMCP.create(code = "vt-104", title = "History")
        votesMCP.voteOnIdea("vt-104", Vote.UP)
        votesMCP.voteOnIdea("vt-104", Vote.DOWN)
        votesMCP.voteOnIdea("vt-104", Vote.NONE)

        val told = history.of(checkNotNull(episodes.findByCode("vt-104")?.id)).filter { it.kind == ChangeKind.VOTE }
        assertEquals(listOf("Voted up", "Changed a vote to down", "Took back a vote down"), told.map { it.summary })
        assertTrue(told.all { it.tool == "vote_on_idea" && it.actor == "ana" })
        // Among the latest changes too, as Home shows them, about the idea
        assertTrue(history.recent(50).any { it.kind == ChangeKind.VOTE && it.about?.code == "vt-104" })
    }
}
