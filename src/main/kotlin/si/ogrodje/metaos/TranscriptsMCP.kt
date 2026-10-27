package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.util.UUID

/** A transcript as MCP clients get it: what it's of and how it was made, and what was said, a line a stretch */
data class TranscriptDto(
    val id: UUID?,
    val mediaId: UUID,
    val media: String,
    // What the text is in, and what's said in the file, BCP-47: the same, but for a translation
    val language: String,
    val spokenLanguage: String,
    val model: String,
    // Seconds
    val duration: Double?,
    val words: Int,
    val speakers: List<TranscriptSpeaker>,
    // Their person's name, or else their username; none for one a workflow made on its own
    val requestedBy: String?,
    val createdAt: OffsetDateTime,
    // "[h:mm:ss] what was said", from where each stretch starts
    val text: String,
)

/** An episode's transcripts, and its files' transcriptions that are on or failed */
data class EpisodeTranscriptsDto(val transcripts: List<TranscriptDto>, val transcriptions: List<TranscriptionDto>)

/** h:mm:ss, as YouTube's chapters have it */
fun timestamp(seconds: Double): String {
    val total = seconds.toLong()
    return "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
}

private const val MAX_TRANSCRIPT_BYTES = 32 * 1024 * 1024

/**
 * Episodes' transcripts (Transcripts.kt), which the transcription workflow makes with Speech-to-Text
 * (Transcription.kt): starting one, and reading them, e.g. for an agent that writes an episode's chapters (Agents.kt).
 * Transcribing needs Temporal (TEMPORAL_ADDRESS); reading needs only the bucket.
 */
@Component
@ConditionalOnStorage
class TranscriptsMCP(
    private val transcription: ObjectProvider<Transcription>,
    private val transcripts: TranscriptsRepository,
    private val episodes: EpisodesRepository,
    private val storage: Storage,
    private val accounts: Accounts,
    private val jsonMapper: JsonMapper,
) {

    private fun transcription() =
        checkNotNull(transcription.ifAvailable) { "MetaOS runs no workflows (TEMPORAL_ADDRESS), so it can't transcribe" }

    private fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    private fun toDto(transcript: Transcript, requestedBy: String?): TranscriptDto {
        val content = jsonMapper.readValue<TranscriptContent>(storage.read(transcript.path, MAX_TRANSCRIPT_BYTES))
        val speakers = jsonMapper.readValue<List<TranscriptSpeaker>>(transcript.speakers)
        val names = speakers.associate { it.label to (it.name ?: it.label) }
        val text = content.segments.joinToString("\n") { segment ->
            val who = segment.speaker?.let { "${names[it] ?: it}: " }.orEmpty()
            "[${timestamp(segment.start)}] $who${segment.text}"
        }
        return TranscriptDto(
            transcript.id, transcript.mediaId, transcript.mediaName, transcript.language, transcript.spokenLanguage, transcript.model,
            transcript.duration, transcript.words, speakers, requestedBy, transcript.createdAt, text,
        )
    }

    @McpTool(
        name = "transcribe_episode",
        annotations = McpTool.McpAnnotations(
            title = "Transcribe episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Transcribe one of an episode's files with Google's Speech-to-Text (Chirp 3), in a durable " +
            "workflow on Temporal: by default its full audio, else its full video, in the language its show's episodes " +
            "are spoken in (Slovenian; Borderless's English) unless told another. Speech-to-Text takes minutes " +
            "for an episode, or up to a day in a batch, for a fifth of the price; get_episode_transcript says how it's " +
            "going and, once it's done, reads it. With chapters, an agent then posts the episode's chapters and summary " +
            "as a comment, in the episode's language. Speech-to-Text doesn't tell speakers apart. A file with a transcript is " +
            "transcribed again only with replace, which replaces it"
    )
    fun transcribeEpisode(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "The file's ID, from list_episode_media; by default the full audio, else the full video", required = false) media: UUID?,
        @McpToolParam(description = "The language spoken in it, as a tag, e.g. sl-SI or en-US; by default its show's", required = false) language: String?,
        @McpToolParam(description = "In a batch: within a day, for a fifth of the price; false by default", required = false) batch: Boolean?,
        @McpToolParam(description = "Replace the file's transcript, if it has one; false by default", required = false) replace: Boolean?,
        @McpToolParam(description = "Have an agent post the chapters and summary as a comment once it's done; true by default", required = false) chapters: Boolean?,
    ): TranscriptionDto = transcription().start(code, media, language, batch == true, replace == true, chapters != false)

    @Transactional(readOnly = true)
    @McpTool(
        name = "get_episode_transcript",
        annotations = McpTool.McpAnnotations(
            title = "Get episode transcript",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "An episode's transcripts, latest first, each of one of its files, in a language, with what was " +
            "said, a line a stretch, each from where it starts: [h:mm:ss] text. Without speakers, which Speech-to-Text " +
            "doesn't tell apart. Also its files' transcriptions that are on, with how far each is, or that failed"
    )
    fun getEpisodeTranscript(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Only the transcript of this file, by its ID from list_episode_media", required = false) media: UUID?,
    ): EpisodeTranscriptsDto {
        val episode = findEpisode(code)
        val found = transcripts.findByEpisodeIdOrderByCreatedAtDesc(checkNotNull(episode.id)).filter { media == null || it.mediaId == media }
        val people = accounts.people(found.mapNotNull { it.requesterId })
        val running = transcription.ifAvailable?.of(code).orEmpty()
            .filter { (media == null || it.mediaId == media) && it.status != "COMPLETED" }
        return EpisodeTranscriptsDto(found.map { toDto(it, it.requesterId?.let { id -> people[id]?.name } ?: it.requester) }, running)
    }
}
