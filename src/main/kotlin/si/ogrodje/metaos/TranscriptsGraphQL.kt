package si.ogrodje.metaos

import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.UUID

// Transcriptions and transcripts over GraphQL, for MetaOS's pages, as TranscriptsMCP has them over MCP (Transcriptions.kt).
// They're the team's: an episode's transcripts are empty for anyone else. Lists may ask for their episodes, transcripts,
// who asked for them and their places in line, so those are batched.
@Controller
class TranscriptsGraphQL(
    private val transcriptions: Transcriptions,
    private val transcripts: TranscriptsRepository,
    private val episodes: EpisodesRepository,
) {

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun transcriptions(
        @Argument statuses: List<TranscriptionStatus>?,
        @Argument provider: String?,
        @Argument limit: Int,
    ): List<Transcription> = transcriptions.list(statuses ?: TranscriptionStatus.entries, provider, null, limit.coerceIn(1, 500))

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun transcription(@Argument id: UUID): Transcription? =
        try {
            transcriptions.get(id)
        } catch (_: IllegalArgumentException) {
            null
        }

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun transcript(@Argument id: UUID): Transcript? = transcripts.findByIdOrNull(id)

    @QueryMapping
    @PreAuthorize(SIGNED_IN)
    fun speechProviders(): List<SpeechProviderDto> = transcriptions.providerList()

    @BatchMapping(typeName = "Episode", field = "transcripts")
    fun episodeTranscripts(of: List<Episode>): Map<Episode, List<Transcript>> {
        val byEpisode = if (signedIn()) transcripts.findByEpisodeIdInOrderByCreatedAtDesc(of.mapNotNull { it.id }).groupBy { it.episodeId }
        else emptyMap()
        return of.associateWith { byEpisode[it.id].orEmpty() }
    }

    private fun episodesOf(ids: Collection<UUID>) = episodes.findAllById(ids.toSet()).associateBy { it.id }

    @BatchMapping(typeName = "Transcription", field = "episode")
    fun transcriptionEpisodes(of: List<Transcription>): Map<Transcription, Episode> {
        val byId = episodesOf(of.map { it.episodeId })
        return of.mapNotNull { job -> byId[job.episodeId]?.let { job to it } }.toMap()
    }

    @BatchMapping(typeName = "Transcript", field = "episode")
    fun transcriptEpisodes(of: List<Transcript>): Map<Transcript, Episode> {
        val byId = episodesOf(of.map { it.episodeId })
        return of.mapNotNull { transcript -> byId[transcript.episodeId]?.let { transcript to it } }.toMap()
    }

    @BatchMapping(typeName = "Transcription", field = "queued")
    fun queued(of: List<Transcription>): Map<Transcription, Int> {
        val places = transcriptions.places(of)
        return of.mapNotNull { job -> places[job.id]?.let { job to it } }.toMap()
    }

    @BatchMapping(typeName = "Transcription", field = "transcript")
    fun made(of: List<Transcription>): Map<Transcription, Transcript> {
        val byId = transcripts.findAllById(of.mapNotNull { it.transcriptId }.toSet()).associateBy { it.id }
        return of.mapNotNull { job -> byId[job.transcriptId]?.let { job to it } }.toMap()
    }

    @BatchMapping(typeName = "Transcription", field = "requestedBy")
    fun transcriptionRequesters(of: List<Transcription>): Map<Transcription, String> {
        val names = transcriptions.requesters(of.map { it.requesterId to it.requester })
        return of.mapNotNull { job -> (job.requesterId?.let { names[it] } ?: job.requester)?.let { job to it } }.toMap()
    }

    @BatchMapping(typeName = "Transcript", field = "requestedBy")
    fun transcriptRequesters(of: List<Transcript>): Map<Transcript, String> {
        val names = transcriptions.requesters(of.map { it.requesterId to it.requester })
        return of.mapNotNull { transcript -> (transcript.requesterId?.let { names[it] } ?: transcript.requester)?.let { transcript to it } }.toMap()
    }

    @SchemaMapping(typeName = "Transcript")
    fun speakers(transcript: Transcript): List<TranscriptSpeaker> = transcriptions.speakers(transcript)

    // From the database, one transcript at a time: a page asks for what one says when it shows it
    @SchemaMapping(typeName = "Transcript")
    fun lines(transcript: Transcript): List<TranscriptSegment> = transcriptions.content(transcript).segments

    @MutationMapping
    @PreAuthorize(WRITES)
    fun transcribeEpisode(
        @Argument ref: String,
        @Argument media: UUID?,
        @Argument provider: String?,
        @Argument language: String?,
        @Argument batch: Boolean,
        @Argument replace: Boolean,
    ): Transcription = transcriptions.start(ref, media, provider, language, batch, replace)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun cancelTranscription(@Argument id: UUID): Transcription = transcriptions.cancel(id)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun removeTranscript(@Argument id: UUID): UUID = checkNotNull(transcriptions.removeTranscript(id).id)
}
