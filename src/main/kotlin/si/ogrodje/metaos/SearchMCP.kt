package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

data class EpisodeHitDto(val episode: EpisodeDto, val matchedIn: List<SearchField>, val snippet: String?)
data class PersonHitDto(val person: PersonDto, val matchedIn: List<SearchField>)
data class SessionHitDto(val session: SessionDto, val matchedIn: List<SearchField>)

data class SearchResultsDto(
    val episode: EpisodeDto?,
    val season: Int?,
    val episodes: List<EpisodeHitDto>,
    val people: List<PersonHitDto>,
    val sessions: List<SessionHitDto>,
    val episodeCount: Int,
    val personCount: Int,
    val sessionCount: Int,
)

// How many of each kind the tool lists, unless asked for another number
private const val SEARCH_LIMIT = 10

@Component
class SearchMCP(private val search: Search) {

    @McpTool(
        name = "search",
        annotations = McpTool.McpAnnotations(
            title = "Search MetaOS",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Find episodes (ideas too), people and recording sessions by an episode code, a name or the words " +
            "in their titles, alternative titles, people, show, summaries (not the AI one), bio, place, address or " +
            "date, the best first, with where each matched and, for a summary, the text around the match. An episode " +
            "code, however it's typed (S05E08, s5e8, 5x08), or an episode's ID finds that episode on its own, and a " +
            "season (S04) its episodes. Case and diacritics don't matter, and the start of a word is enough"
    )
    fun search(
        @McpToolParam(description = "What to look for: an episode code, a name or words") text: String,
        @McpToolParam(description = "How many of each kind to list, $SEARCH_LIMIT by default, at most $MAX_SEARCH_RESULTS", required = false) limit: Int?,
    ): SearchResultsDto {
        require(text.isNotBlank()) { "Nothing to search for" }
        val found = search.search(text, limit ?: SEARCH_LIMIT)
        return SearchResultsDto(
            episode = found.episode?.toDto(),
            season = found.season,
            episodes = found.episodes.map { EpisodeHitDto(it.episode.toDto(), it.matchedIn, it.snippet) },
            people = found.people.map { PersonHitDto(it.person.toDto(), it.matchedIn) },
            sessions = found.sessions.map { SessionHitDto(it.session.toDto(), it.matchedIn) },
            episodeCount = found.episodeCount,
            personCount = found.personCount,
            sessionCount = found.sessionCount,
        )
    }
}
