package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
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
    // Who heard it, e.g. google, and with which of its models
    val provider: String,
    val model: String,
    // Seconds
    val duration: Double?,
    val segments: Int,
    val words: Int,
    val speakers: List<TranscriptSpeaker>,
    // Their person's name, or else their username; none for one a workflow made on its own
    val requestedBy: String?,
    val createdAt: OffsetDateTime,
    // "[h:mm:ss] what was said", from where each stretch starts
    val text: String,
)

/** An episode's transcripts, and its files' transcriptions that are on, or that failed or were cancelled last */
data class EpisodeTranscriptsDto(val transcripts: List<TranscriptDto>, val transcriptions: List<TranscriptionDto>)

/** A transcript that was removed: what it was of, and by whom */
data class RemovedTranscriptDto(val id: UUID, val media: String, val provider: String, val language: String)

/** h:mm:ss, as YouTube's chapters have it */
fun timestamp(seconds: Double): String {
    val total = seconds.toLong()
    return "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
}

/**
 * Episodes' transcriptions and transcripts over MCP (Transcriptions.kt, as TranscriptsGraphQL has them): starting,
 * listing and cancelling transcriptions by Google's Speech-to-Text or Soniox, and reading and removing the transcripts
 * they made. Transcribing needs Temporal (TEMPORAL_ADDRESS) and the bucket.
 */
@Component
@ConditionalOnStorage
class TranscriptsMCP(
    private val transcriptions: Transcriptions,
    private val transcripts: TranscriptsRepository,
) {

    private fun toDto(transcript: Transcript, requestedBy: String?) = TranscriptDto(
        transcript.id, transcript.mediaId, transcript.mediaName, transcript.language, transcript.spokenLanguage,
        transcript.provider, transcript.model, transcript.duration, transcript.segments, transcript.words,
        transcriptions.speakers(transcript), requestedBy, transcript.createdAt, transcriptions.text(transcript),
    )

    @McpTool(
        name = "transcribe_episode",
        annotations = McpTool.McpAnnotations(
            title = "Transcribe episode",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Transcribe one of an episode's files, in a durable workflow on Temporal, by a provider: google, " +
            "Google's Speech-to-Text (model long, in the EU), which doesn't punctuate Slovenian or tell speakers apart, " +
            "or soniox, Soniox (stt-async-v5, in the US), which does both. By default its full audio, else its full " +
            "video, in the language its show's episodes are spoken in (Slovenian; Borderless's English) unless told " +
            "another. It takes minutes for an episode, or with Google up to a day in a batch, for a fifth of the price; a " +
            "provider transcribes a few files at once, and the rest wait their turn. list_transcriptions and " +
            "get_episode_transcript say how it's going, and the latter reads it once it's done; cancel_transcription " +
            "stops it. A file has a transcript by each provider, to compare them; one it has is transcribed again by " +
            "that provider only with replace, which replaces it"
    )
    fun transcribeEpisode(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "The file's ID, from list_episode_media; by default the full audio, else the full video", required = false) media: UUID?,
        @McpToolParam(description = "Who transcribes it: google or soniox; by default MetaOS's, google", required = false) provider: String?,
        @McpToolParam(description = "The language spoken in it, as a tag, e.g. sl-SI or en-US; by default its show's", required = false) language: String?,
        @McpToolParam(description = "With Google, in a batch: within a day, for a fifth of the price; false by default", required = false) batch: Boolean?,
        @McpToolParam(description = "Replace the file's transcript by that provider, if it has one; false by default", required = false) replace: Boolean?,
    ): TranscriptionDto {
        val started = transcriptions.start(code, media, provider, language, batch == true, replace == true)
        val note = if (batch == true && started.provider == "google") "In a batch, Speech-to-Text takes up to a day" else null
        return transcriptions.dtos(listOf(started), note).single()
    }

    @McpTool(
        name = "list_transcriptions",
        annotations = McpTool.McpAnnotations(
            title = "List transcriptions",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Transcriptions of episodes' files, the latest first: each with its episode and file, its " +
            "provider, and how far it is: QUEUED (waiting for its turn at the provider, with its place in line), " +
            "STARTING, RECOGNIZING (with how far the provider got, where it says), DONE (with the transcript it made, " +
            "for get_episode_transcript), FAILED (with why) or CANCELLED"
    )
    fun listTranscriptions(
        @McpToolParam(description = "Which: on (queued, starting or being heard), done, failed, cancelled or all; all by default", required = false) status: String?,
        @McpToolParam(description = "Only those by this provider: google or soniox", required = false) provider: String?,
        @McpToolParam(description = "Only those of this episode: $EPISODE_REF", required = false) code: String?,
        @McpToolParam(description = "At most this many, up to 500; 20 by default", required = false) limit: Int?,
    ): List<TranscriptionDto> {
        val statuses = when (status?.trim()?.lowercase()) {
            null, "", "all" -> TranscriptionStatus.entries
            "on" -> TranscriptionStatus.ON
            else -> listOf(
                requireNotNull(TranscriptionStatus.entries.find { it.name.equals(status.trim(), ignoreCase = true) }) {
                    "A transcription is on, done, failed or cancelled, not $status"
                }
            )
        }
        val episode = code?.let { transcriptions.findEpisode(it).id }
        return transcriptions.dtos(transcriptions.list(statuses, provider, episode, (limit ?: 20).coerceIn(1, 500)))
    }

    @McpTool(
        name = "get_transcription",
        annotations = McpTool.McpAnnotations(
            title = "Get transcription",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "One transcription by its ID, from transcribe_episode or list_transcriptions, as it is now, to " +
            "watch it: QUEUED (with its place in line), STARTING, RECOGNIZING (with how far the provider got, where it " +
            "says), DONE (with the transcript it made, transcriptId, which get_episode_transcript reads), FAILED (with " +
            "why) or CANCELLED. Speech-to-text takes minutes, so asking every minute or so is enough"
    )
    fun getTranscription(@McpToolParam(description = "The transcription's ID, from transcribe_episode or list_transcriptions") id: UUID): TranscriptionDto =
        transcriptions.dtos(listOf(transcriptions.get(id))).single()

    @McpTool(
        name = "cancel_transcription",
        annotations = McpTool.McpAnnotations(
            title = "Cancel transcription",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Cancel a transcription that's on (queued, starting or being heard): its workflow stops, and asks " +
            "the provider to stop too, which it may not have time to; the file keeps the transcript it had"
    )
    fun cancelTranscription(@McpToolParam(description = "The transcription's ID, from list_transcriptions") id: UUID): TranscriptionDto {
        val cancelled = transcriptions.cancel(id)
        return transcriptions.dtos(listOf(cancelled), if (cancelled.status.on) "Cancelling: it stops in a moment" else null).single()
    }

    @McpTool(
        name = "get_episode_transcript",
        annotations = McpTool.McpAnnotations(
            title = "Get episode transcript",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "An episode's transcripts, latest first, each of one of its files, in a language, by a provider " +
            "(google or soniox), with what was said, a line a stretch, each from where it starts: [h:mm:ss] text, or " +
            "[h:mm:ss] Speaker 1: text where the provider tells speakers apart (Soniox), by their names once they're " +
            "named. Also its files' transcriptions that are on (QUEUED, with its place in line, STARTING, RECOGNIZING), " +
            "or that failed or were cancelled last"
    )
    fun getEpisodeTranscript(
        @McpToolParam(description = EPISODE_REF) code: String,
        @McpToolParam(description = "Only the transcript of this file, by its ID from list_episode_media", required = false) media: UUID?,
        @McpToolParam(description = "Only the transcript by this provider: google or soniox", required = false) provider: String?,
    ): EpisodeTranscriptsDto {
        val episode = transcriptions.findEpisode(code)
        val by = provider?.trim()?.lowercase()
        val found = transcripts.findByEpisodeIdOrderByCreatedAtDesc(checkNotNull(episode.id))
            .filter { (media == null || it.mediaId == media) && (by == null || it.provider == by) }
        val names = transcriptions.requesters(found.map { it.requesterId to it.requester })
        // Those on, and each file's latest by each provider where it ended without a transcript
        val jobs = transcriptions.list(TranscriptionStatus.entries, by, episode.id, 500)
            .filter { media == null || it.mediaId == media }
            .groupBy { it.mediaId to it.provider }.values.mapNotNull { it.firstOrNull() }
            .filter { it.status.on || it.status == TranscriptionStatus.FAILED || it.status == TranscriptionStatus.CANCELLED }
        return EpisodeTranscriptsDto(
            found.map { toDto(it, it.requesterId?.let { id -> names[id] } ?: it.requester) },
            transcriptions.dtos(jobs),
        )
    }

    @McpTool(
        name = "remove_transcript",
        annotations = McpTool.McpAnnotations(
            title = "Remove transcript",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Remove one of an episode's transcripts, and what it says; the bucket keeps its files, and the " +
            "episode's history that it was there. Transcribing the file again makes another"
    )
    fun removeTranscript(@McpToolParam(description = "The transcript's ID, from get_episode_transcript") id: UUID): RemovedTranscriptDto {
        val removed = transcriptions.removeTranscript(id)
        return RemovedTranscriptDto(id, removed.mediaName, removed.provider, removed.language)
    }
}
