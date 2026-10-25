package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Who voted which way, as MCP clients get it */
data class VoterOutDto(val name: String, val username: String?, val personId: UUID?, val vote: Vote)

/** An idea's votes as MCP clients get them */
data class VotesOutDto(val score: Int, val up: Int, val down: Int, val mine: Vote, val voters: List<VoterOutDto>)

private fun VotesDto.out() = VotesOutDto(score, up, down, mine, voters.map { VoterOutDto(it.name, it.username, it.person?.id, it.vote) })

/**
 * Votes on episode ideas (Votes.kt). Voting is a transaction of its own, so the audit log has the tool's name
 * (AuditingTransactionManager).
 */
@Component
class VotesMCP(private val votes: Votes, private val episodes: EpisodesRepository) {

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    @McpTool(
        name = "list_idea_votes",
        annotations = McpTool.McpAnnotations(
            title = "List idea votes",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "An idea's votes: its score (ups minus downs), how many up and down, the signed-in user's own " +
            "vote, and who voted which way. Episodes planned since keep the votes they had as ideas"
    )
    fun listIdeaVotes(@McpToolParam(description = EPISODE_REF) code: String): VotesOutDto = votes.of(findEpisode(code)).out()

    @Transactional
    @McpTool(
        name = "vote_on_idea",
        annotations = McpTool.McpAnnotations(
            title = "Vote on idea",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Vote on an episode idea as the signed-in user: UP or DOWN, which replaces their earlier vote, or " +
            "NONE, which takes it back. Only ideas take votes; once planned, an episode's votes stay as they were. " +
            "Returns the idea's votes"
    )
    fun voteOnIdea(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "UP, DOWN or NONE") vote: Vote,
    ): VotesOutDto = votes.vote(findEpisode(code), vote).out()
}
