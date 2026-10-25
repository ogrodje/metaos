package si.ogrodje.metaos

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller

// Ideas' votes (Votes.kt) are the team's, as their discussions are, so anyone else gets none, also on a released
// episode that was voted on as an idea. Lists ask for them, so they're batched.
@Controller
class VotesGraphQL(private val votes: Votes, private val episodes: EpisodesRepository) {

    @BatchMapping(typeName = "Episode", field = "votes")
    fun episodeVotes(episodes: List<Episode>): Map<Episode, VotesDto> =
        if (signedIn()) votes.onEpisodes(episodes) else episodes.associateWith { VotesDto.NONE }

    // Reads too: the answer names who voted, which Episode.votes shows only readers
    @MutationMapping
    @PreAuthorize("$WRITES and $SIGNED_IN")
    fun voteOnIdea(@Argument ref: String, @Argument vote: Vote): VotesDto =
        votes.vote(requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }, vote)
}
