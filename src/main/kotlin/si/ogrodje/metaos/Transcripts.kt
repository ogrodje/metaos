package si.ogrodje.metaos

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

/** One of a transcript's speakers: the provider's [label], and who it is, once someone knew */
data class TranscriptSpeaker(val label: String, val name: String? = null, val personId: UUID? = null)

/**
 * A word as it was said, from [start] to [end], in seconds from the start of the file, and how sure the provider was,
 * where it says (Google's long doesn't); without it in JSON then, as an episode has some 8,000 words an hour
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TranscriptWord(val start: Double, val end: Double, val text: String, val confidence: Double? = null)

/**
 * A stretch of what one speaker said, from [start] to [end], in seconds from the start of the file, with its [words]
 * where the provider timed them (none where it didn't)
 */
data class TranscriptSegment(
    val speaker: String?,
    val start: Double,
    val end: Double,
    val text: String,
    val words: List<TranscriptWord> = emptyList(),
)

/**
 * What was said, in MetaOS's own format, whichever provider heard it: in the database (transcript_contents, JSONB) and
 * in the bucket (transcript.json). The provider, its model and the language are in it too, so the bucket's copy says
 * what it is without the database.
 */
data class TranscriptContent(
    val provider: String,
    val model: String,
    val language: String,
    val segments: List<TranscriptSegment>,
) {
    // A function, not a property, which JSON would have too
    fun words() = segments.sumOf { segment -> segment.words.size.takeIf { it > 0 } ?: wordsIn(segment.text) }
}

private fun wordsIn(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() }

// How MetaOS cuts what a provider heard into stretches, whoever heard it: at most this long, for chapters to the
// quarter-minute rather than to the provider's own pieces (Google's long hears a minute at a time) ...
private const val LONGEST_STRETCH = 20.0
// ... at a pause this long, as a speaker's next thought, ...
private const val PAUSE = 1.0
// ... or at the end of a sentence, where the provider punctuates, once it's this long
private const val SENTENCE = 8.0
private val SENTENCE_ENDS = setOf('.', '?', '!')

/**
 * What a provider heard, as stretches of what was said: its own pieces, cut where they're long, at a pause, the end of
 * a sentence, or else at [LONGEST_STRETCH] seconds; a piece without timed words stays as it is
 */
fun inStretches(heard: List<TranscriptSegment>): List<TranscriptSegment> = heard.flatMap { piece ->
    if (piece.words.isEmpty()) return@flatMap listOf(piece)
    val stretches = mutableListOf<List<TranscriptWord>>()
    var current = mutableListOf<TranscriptWord>()
    for (word in piece.words) {
        val last = current.lastOrNull()
        val cut = last != null && (
            word.start - last.end >= PAUSE ||
                word.end - current.first().start > LONGEST_STRETCH ||
                (last.end - current.first().start >= SENTENCE && last.text.lastOrNull() in SENTENCE_ENDS)
            )
        if (cut) {
            stretches += current
            current = mutableListOf()
        }
        current += word
    }
    stretches += current
    stretches.map { words ->
        TranscriptSegment(piece.speaker, words.first().start, words.last().end, words.joinToString(" ") { it.text }, words)
    }
}

/**
 * A transcript of one of an episode's video or audio files (V32__transcripts.sql), which the transcription workflow
 * made with a speech-to-text [provider] (Transcription.kt). What was said is a [TranscriptText] (JSONB) and in MetaOS's
 * bucket at [path], the same; what the provider answered is next to it, at [rawPath]. Here is what it's of, in which
 * language, how it was made and who's who. Its [language] is what the text is in, its [spokenLanguage] what's said in
 * the file: the same for a transcript, which a file has one of by each provider, to compare them; another for a
 * translation, one in each language (none yet). The episode and the file are linked by ID, as comments are: the file
 * may be removed, its transcripts stay.
 */
@Entity
@Table(name = "transcripts")
class Transcript(
    @Column(name = "episode_id", nullable = false) val episodeId: UUID,
    @Column(name = "media_id", nullable = false) val mediaId: UUID,
    @Column(nullable = false, columnDefinition = "text") val mediaName: String,
    @Column(nullable = false, columnDefinition = "text") val language: String,
    @Column(nullable = false, columnDefinition = "text") val spokenLanguage: String,
    @Column(nullable = false, columnDefinition = "text") val provider: String,
    @Column(nullable = false, columnDefinition = "text") val model: String,
    @Column(nullable = false, columnDefinition = "text") val path: String,
    @Column(columnDefinition = "text") val rawPath: String?,
    val duration: Double?,
    @Column(nullable = false) val segments: Int,
    @Column(nullable = false) val words: Int,
    // [TranscriptSpeaker]s, as JSON, sent as text, which Postgres takes for JSON (stringtype=unspecified)
    @Column(nullable = false, columnDefinition = "jsonb") var speakers: String,
    @Column(nullable = false, columnDefinition = "text") val workflowId: String,
    // Who asked for it: their Keycloak user ID and username then; none when a workflow started on its own
    @Column(columnDefinition = "text") val requesterId: String?,
    @Column(columnDefinition = "text") val requester: String?,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Column(nullable = false) val createdAt: OffsetDateTime = now()

    override fun equals(other: Any?) = this === other || (other is Transcript && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Transcript(id=$id, episodeId=$episodeId, mediaName=$mediaName)"
}

/**
 * What a transcript says: its [TranscriptContent], as JSON (transcript_contents), in a table of its own, which the
 * audit log leaves out, as it would keep the megabyte again; it goes with its transcript, and never changes
 */
@Entity
@Table(name = "transcript_contents")
class TranscriptText(
    @Id @Column(name = "transcript_id") val transcriptId: UUID,
    // Sent as text, which Postgres takes for JSON (stringtype=unspecified)
    @Column(nullable = false, columnDefinition = "jsonb") val content: String,
) {
    override fun equals(other: Any?) = this === other || (other is TranscriptText && transcriptId == other.transcriptId)
    override fun hashCode() = transcriptId.hashCode()
    override fun toString() = "TranscriptText(transcriptId=$transcriptId)"
}

interface TranscriptsRepository : JpaRepository<Transcript, UUID> {
    fun findByMediaId(mediaId: UUID): List<Transcript>
    fun findByEpisodeIdOrderByCreatedAtDesc(episodeId: UUID): List<Transcript>
    fun findByEpisodeIdInOrderByCreatedAtDesc(episodeIds: Collection<UUID>): List<Transcript>

    /** The file's transcript by [provider] in the language spoken in it, rather than a translation */
    @Query("select t from Transcript t where t.mediaId = :mediaId and t.provider = :provider and t.language = t.spokenLanguage")
    fun findSpoken(mediaId: UUID, provider: String): Transcript?
}

interface TranscriptTextsRepository : JpaRepository<TranscriptText, UUID>
