package si.ogrodje.metaos

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A vote on an idea: up, down, or none, which takes one's vote back */
enum class Vote(val value: Int) {
    UP(1), DOWN(-1), NONE(0);

    companion object {
        fun of(value: Int) = entries.first { it.value == value }
    }
}

/**
 * One user's vote on an episode idea (V30__idea_votes.sql). The idea is linked by ID, as a comment's episode is, and its
 * voter by their Keycloak user ID; their person is their account's (Accounts), when it's shown.
 */
@Entity
@Table(name = "idea_votes")
class IdeaVote(
    @Column(name = "episode_id", nullable = false) val episodeId: UUID,
    @Column(nullable = false, columnDefinition = "text") val voterId: String,
    @Column(columnDefinition = "text") val voter: String?,
    // 1 or -1
    @Column(nullable = false) var value: Short,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    override fun equals(other: Any?) = this === other || (other is IdeaVote && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "IdeaVote(id=$id, episodeId=$episodeId, value=$value)"
}

interface IdeaVotesRepository : JpaRepository<IdeaVote, UUID> {
    fun findByEpisodeIdIn(episodeIds: Collection<UUID>): List<IdeaVote>

    // One statement, so two votes at once by the same user (two tabs, a double click) can't both insert. The same vote
    // again only bumps updated_at, which the audit log leaves out.
    @Modifying(clearAutomatically = true)
    @Query(
        "insert into idea_votes (id, episode_id, voter_id, voter, value) values (gen_random_uuid(), :episodeId, :voterId, :voter, :value) " +
            "on conflict (episode_id, voter_id) do update set value = excluded.value",
        nativeQuery = true,
    )
    fun upsert(episodeId: UUID, voterId: String, voter: String?, value: Short)

    @Modifying(clearAutomatically = true)
    @Query("delete from idea_votes where episode_id = :episodeId and voter_id = :voterId", nativeQuery = true)
    fun remove(episodeId: UUID, voterId: String)

    // An episode's status as it is now, held until the vote's transaction ends, so it can't be planned or deleted
    // between the check and the vote; null for one deleted
    @Query("select status from episodes where id = :episodeId and deleted_at is null for share", nativeQuery = true)
    fun lockedStatus(episodeId: UUID): String?
}

/** Who voted which way, by their person's name, or else their username */
data class VoterDto(val name: String, val username: String?, val person: Person?, val vote: Vote)

/** An idea's votes: ups minus downs, how many each, the signed-in user's own, and who voted, ups first */
data class VotesDto(val score: Int, val up: Int, val down: Int, val mine: Vote, val voters: List<VoterDto>) {
    companion object {
        val NONE = VotesDto(0, 0, 0, Vote.NONE, emptyList())
    }
}

/**
 * Votes on episode ideas, for their MCP tool (VotesMCP) and GraphQL (VotesGraphQL), so both follow the same rules:
 * anyone approved votes as themselves, once per idea, which they may change or take back while it's an idea.
 */
@Component
class Votes(private val votes: IdeaVotesRepository, private val accounts: Accounts) {

    /** The votes on several episodes, with their voters' people, in a query each */
    fun onEpisodes(episodes: Collection<Episode>): Map<Episode, VotesDto> {
        val byEpisode = votes.findByEpisodeIdIn(episodes.mapNotNull { it.id }).groupBy { it.episodeId }
        val people = accounts.people(byEpisode.values.flatten().map { it.voterId })
        val me = currentJwt()?.subject
        return episodes.associateWith { episode ->
            val of = byEpisode[episode.id].orEmpty().sortedByDescending { it.value }
            if (of.isEmpty()) VotesDto.NONE
            else VotesDto(
                score = of.sumOf { it.value.toInt() },
                up = of.count { it.value > 0 },
                down = of.count { it.value < 0 },
                mine = of.find { it.voterId == me }?.let { Vote.of(it.value.toInt()) } ?: Vote.NONE,
                voters = of.map { VoterDto(people[it.voterId]?.name ?: it.voter ?: "Someone", it.voter, people[it.voterId], Vote.of(it.value.toInt())) },
            )
        }
    }

    fun of(episode: Episode): VotesDto = onEpisodes(listOf(episode)).getValue(episode)

    /** Votes on an idea as the signed-in user, changes their vote, or takes it back with [Vote.NONE]; its votes then */
    @Transactional
    fun vote(episode: Episode, vote: Vote): VotesDto {
        val jwt = checkNotNull(currentJwt()) { "Not signed in" }
        val voterId = checkNotNull(jwt.subject) { "The token doesn't say who's signed in" }
        val episodeId = checkNotNull(episode.id)
        // As it is in the database, not as it was loaded, before this transaction
        val status = requireNotNull(votes.lockedStatus(episodeId)) { "Episode ${episode.code ?: episode.title} has been deleted" }
        require(status == EpisodeStatus.IDEA.name) {
            "Only ideas are voted on; ${episode.code ?: episode.title} is ${status.lowercase().replace('_', ' ')}"
        }
        // Straight to the database, so the audit log has it within the transaction, e.g. for the tool that voted (VotesMCP)
        if (vote == Vote.NONE) votes.remove(episodeId, voterId)
        else votes.upsert(episodeId, voterId, jwt.getClaimAsString("preferred_username"), vote.value.toShort())
        return of(episode)
    }
}
