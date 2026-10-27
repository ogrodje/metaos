package si.ogrodje.metaos

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

/** One of a transcript's speakers: Speech-to-Text's [label], and who it is, once someone knew */
data class TranscriptSpeaker(val label: String, val name: String? = null, val personId: UUID? = null)

/** A stretch of what one speaker said, from [start] to [end], in seconds from the start of the file */
data class TranscriptSegment(val speaker: String?, val start: Double, val end: Double, val text: String)

/** What a transcript's file in the bucket has: its speakers and what they said, in order */
data class TranscriptContent(val speakers: List<TranscriptSpeaker>, val segments: List<TranscriptSegment>)

/**
 * A transcript of one of an episode's video or audio files (V32__transcripts.sql), which the transcription workflow
 * made with Speech-to-Text (Transcription.kt). What was said is in MetaOS's private bucket at [path]
 * ([TranscriptContent], as JSON); here is what it's of, in which language, how it was made and who's who. Its
 * [language] is what the text is in, its [spokenLanguage] what's said in the file: the same for a transcript, which
 * a file has one of; another for a translation, one in each language (none yet). The episode and the file are linked
 * by ID, as comments are: the file may be removed, its transcripts stay.
 */
@Entity
@Table(name = "transcripts")
class Transcript(
    @Column(name = "episode_id", nullable = false) val episodeId: UUID,
    @Column(name = "media_id", nullable = false) val mediaId: UUID,
    @Column(nullable = false, columnDefinition = "text") val mediaName: String,
    @Column(nullable = false, columnDefinition = "text") val path: String,
    @Column(nullable = false, columnDefinition = "text") val language: String,
    @Column(nullable = false, columnDefinition = "text") val spokenLanguage: String,
    @Column(nullable = false, columnDefinition = "text") val model: String,
    val duration: Double?,
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

interface TranscriptsRepository : JpaRepository<Transcript, UUID> {
    fun findByMediaId(mediaId: UUID): List<Transcript>
    fun findByEpisodeIdOrderByCreatedAtDesc(episodeId: UUID): List<Transcript>

    /** The file's transcript in the language spoken in it, rather than a translation */
    @Query("select t from Transcript t where t.mediaId = :mediaId and t.language = t.spokenLanguage")
    fun findSpoken(mediaId: UUID): Transcript?
}
