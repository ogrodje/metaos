package si.ogrodje.metaos

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.time.format.TextStyle
import java.util.Locale

/*
 * Search over episodes (ideas too), people and recording sessions, for the front-end's search (GraphQL's search) and
 * the search MCP tool. It's the team's, so it looks at everything but the AI summary.
 *
 * An episode code, however it's typed (S05E08, s5e8, S5 E8, 5x08), finds that episode on its own, and a season (S04,
 * season 4, sezona 4) its episodes. Anything else is words, each of which has to be in what's searched: at the start of
 * a word it counts in full, inside one (three letters or more) half, as Slovenian bends its words (jezikovni,
 * jezikovnega). Case and diacritics don't matter. Each word counts the weight of the field it's best in; ties go to the
 * newer episode or session, and to people by name.
 */

const val MAX_SEARCH_RESULTS = 100

/** Where a result matched, and how much a word there counts */
enum class SearchField(val weight: Int) {
    CODE(0),
    TITLE(10),
    ALTERNATIVE_TITLE(6),
    PEOPLE(8),
    SHOW(5),
    TINY_SUMMARY(4),
    SUMMARY(2),
    SESSION(4),
    NAME(10),
    BIO(4),
    LOCATION(10),
    ADDRESS(6),
    DATE(5),
}

/** An episode found by its words: where it matched, and the text around a match its row wouldn't otherwise show */
data class EpisodeHit(val episode: Episode, val matchedIn: List<SearchField>, val snippet: String? = null)

data class PersonHit(val person: Person, val matchedIn: List<SearchField>)

data class SessionHit(val session: Session, val matchedIn: List<SearchField>)

/**
 * What a search found: the episode whose code or ID the text is, alone; a season's episodes; or the best matches of
 * each kind, at most the limit of each, and how many there are in all
 */
data class SearchResults(
    val episode: Episode? = null,
    val season: Int? = null,
    val episodes: List<EpisodeHit> = emptyList(),
    val people: List<PersonHit> = emptyList(),
    val sessions: List<SessionHit> = emptyList(),
    val episodeCount: Int = episodes.size,
    val personCount: Int = people.size,
    val sessionCount: Int = sessions.size,
)

private val MARKS = Regex("\\p{M}+")
private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

/** Lower case and without diacritics, so cuhalev is Čuhalev; đ, which doesn't come apart, is d */
fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).replace('đ', 'd')

/** The words of a text, folded */
fun searchWords(text: String): List<String> = fold(text).split(NOT_WORD).filter { it.isNotEmpty() }

private val EPISODE_CODE = Regex("^S(\\d{1,2})E(\\d{1,3})$|^(\\d{1,2})X(\\d{1,3})$")
private val SEASON = Regex("^(?:SEASON|SEZONA|S)(\\d{1,2})E?$")

private fun twoDigits(number: String) = number.toInt().toString().padStart(2, '0')

/** The episode code a text is, however it's typed: s5e8, S5 E8, S05e08 and 5x08 are S05E08 */
fun episodeCode(text: String): String? =
    EPISODE_CODE.matchEntire(text.filterNot { it.isWhitespace() }.uppercase())?.groupValues?.let { groups ->
        val (season, episode) = if (groups[1].isNotEmpty()) groups[1] to groups[2] else groups[3] to groups[4]
        "S${twoDigits(season)}E${twoDigits(episode)}"
    }

/** The season a text names: S04, s4, S04E, season 4 and sezona 4 are 4 */
fun seasonOf(text: String): Int? = SEASON.matchEntire(text.filterNot { it.isWhitespace() }.uppercase())?.groupValues?.get(1)?.toInt()

// Summaries are Markdown: what's searched and shown of them is their text, links by what they say
private val MARKDOWN_LINK = Regex("\\[([^]]*)]\\([^)]*\\)")
private val MARKDOWN_MARKS = Regex("[*_`#>]+")
private val SPACES = Regex("\\s+")

private fun plainText(markdown: String) =
    markdown.replace(MARKDOWN_LINK, "$1").replace(MARKDOWN_MARKS, "").replace(SPACES, " ").trim()

// A field's text, folded once
private class Text(val field: SearchField, val text: String) {
    val folded = fold(text)
}

// 1 when a word of the text starts with the word, ½ when it's inside one and has three letters or more, else 0
private fun quality(folded: String, word: String): Double {
    var inside = 0.0
    var at = folded.indexOf(word)
    while (at >= 0) {
        if (at == 0 || !folded[at - 1].isLetterOrDigit()) return 1.0
        if (word.length >= 3) inside = 0.5
        at = folded.indexOf(word, at + 1)
    }
    return inside
}

private class Match<T>(val item: T, val score: Double, val texts: List<Text>) {
    val matchedIn = texts.map { it.field }.distinct().sortedByDescending { it.weight }
}

// Every word has to be somewhere, and counts the weight of the field it's best in
private fun <T> T.match(texts: List<Text>, words: List<String>, bonus: Double = 0.0): Match<T>? {
    var score = bonus
    val best = mutableListOf<Text>()
    for (word in words) {
        val (text, value) = texts.map { it to quality(it.folded, word) * it.field.weight }.maxByOrNull { it.second } ?: return null
        if (value == 0.0) return null
        score += value
        best += text
    }
    return Match(this, score, best)
}

private fun <T> List<Match<T>>.best(ties: Comparator<T>): List<Match<T>> =
    sortedWith(compareByDescending<Match<T>> { it.score }.thenBy(ties) { it.item })

// When an episode was or will be out: aired, else recorded, else added
private val Episode.date get() = airedDate ?: recordingDate ?: session?.date ?: addedDate

// A match only these would explain, as a row shows the title, the people and the show
private val SNIPPET_FIELDS = setOf(SearchField.ALTERNATIVE_TITLE, SearchField.TINY_SUMMARY, SearchField.SUMMARY)
private const val SNIPPET = 160

/** The part of a text around the first of the words, cut at spaces, with … where it's cut */
fun snippet(text: String, words: List<String>): String {
    if (text.length <= SNIPPET) return text
    // Folded a character at a time, so positions in it are positions in the text
    val folded = text.map { fold(it.toString()).firstOrNull() ?: it }.joinToString("")
    val first = words.mapNotNull { word -> folded.indexOf(word).takeIf { it >= 0 } }.minOrNull() ?: 0
    var start = (first - SNIPPET / 3).coerceAtLeast(0)
    if (start > 0) start = text.indexOf(' ', start).let { if (it in start until first) it + 1 else start }
    var end = (start + SNIPPET).coerceAtMost(text.length)
    if (end < text.length) end = text.lastIndexOf(' ', end).let { if (it > first) it else end }
    return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
}

private val SLOVENIAN = Locale.forLanguageTag("sl")

@Component
class Search(
    private val episodes: EpisodesRepository,
    private val people: PeopleRepository,
    private val episodePeople: EpisodePeopleRepository,
    private val sessions: SessionsRepository,
) {

    @Transactional(readOnly = true)
    fun search(text: String, limit: Int): SearchResults {
        require(limit in 1..MAX_SEARCH_RESULTS) { "limit must be between 1 and $MAX_SEARCH_RESULTS" }
        val asked = text.trim()
        if (asked.isEmpty()) return SearchResults()
        val all = episodes.findAll()

        // A code, or an ID, which ideas without a code are known by
        val code = episodeCode(asked)
        val exact = asked.toUuidOrNull()?.let { id -> all.firstOrNull { it.id == id } }
            ?: all.firstOrNull { it.code.equals(code ?: asked, ignoreCase = true) }
        if (exact != null) return SearchResults(episode = exact)
        if (code != null) return SearchResults()

        seasonOf(asked)?.let { season ->
            val prefix = "S${twoDigits(season.toString())}E"
            val inSeason = all.filter { it.code?.startsWith(prefix, ignoreCase = true) == true }.sortedByDescending { it.code }
            return SearchResults(
                season = season,
                episodes = inSeason.take(limit).map { EpisodeHit(it, listOf(SearchField.CODE)) },
                episodeCount = inSeason.size,
            )
        }

        val words = searchWords(asked)
        if (words.isEmpty()) return SearchResults()
        val namesOn = episodePeople.findAllWithEpisodeAndPerson().groupBy({ it.episode.id }, { it.person.name })

        val episodeMatches = all.mapNotNull { episode ->
            val title = fold(episode.title)
            // The words as typed in the title, and a title that starts with the first of them
            val bonus = (if (words.size > 1 && words.joinToString(" ") in title) 5.0 else 0.0) +
                (if (title.startsWith(words.first())) 2.0 else 0.0)
            episode.match(episode.texts(namesOn[episode.id].orEmpty()), words, bonus)
        }.best(compareByDescending { it.date })
        val personMatches = people.findAll().mapNotNull { person -> person.match(person.texts(), words) }.best(peopleByName)
        val sessionMatches = sessions.findAll().mapNotNull { session -> session.match(session.texts(), words) }
            .best(compareByDescending { it.date })

        return SearchResults(
            episodes = episodeMatches.take(limit).map { match ->
                val unshown = match.texts.filter { it.field in SNIPPET_FIELDS }.maxByOrNull { it.field.weight }
                EpisodeHit(match.item, match.matchedIn, unshown?.let { snippet(it.text, words) })
            },
            people = personMatches.take(limit).map { PersonHit(it.item, it.matchedIn) },
            sessions = sessionMatches.take(limit).map { SessionHit(it.item, it.matchedIn) },
            episodeCount = episodeMatches.size,
            personCount = personMatches.size,
            sessionCount = sessionMatches.size,
        )
    }

    // Everything but the AI summary, and the people on it, candidates too
    private fun Episode.texts(names: List<String>) = buildList {
        add(Text(SearchField.TITLE, title))
        listOfNotNull(titleB, titleC).forEach { add(Text(SearchField.ALTERNATIVE_TITLE, it)) }
        names.forEach { add(Text(SearchField.PEOPLE, it)) }
        show?.let { add(Text(SearchField.SHOW, it.name)) }
        tinySummary?.let { add(Text(SearchField.TINY_SUMMARY, plainText(it))) }
        summary?.let { add(Text(SearchField.SUMMARY, plainText(it))) }
        listOfNotNull(session?.name, session?.location).distinct().forEach { add(Text(SearchField.SESSION, it)) }
    }

    private fun Person.texts() = listOfNotNull(Text(SearchField.NAME, name), bio?.let { Text(SearchField.BIO, it) })

    // Its name first, as it goes by it; its date also by the month's name, in Slovenian and English: oktober, October
    private fun Session.texts() = listOfNotNull(
        name?.let { Text(SearchField.NAME, it) },
        location?.let { Text(SearchField.LOCATION, it) },
        address?.let { Text(SearchField.ADDRESS, it) },
        Text(SearchField.DATE, "$date ${date.month.getDisplayName(TextStyle.FULL, SLOVENIAN)} ${date.month.getDisplayName(TextStyle.FULL, Locale.UK)}"),
    )
}
