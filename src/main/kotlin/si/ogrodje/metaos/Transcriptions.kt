package si.ogrodje.metaos

import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import jakarta.persistence.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

/**
 * How far a transcription is: waiting for a turn at its provider, starting it, the provider hearing it; then done,
 * failed or cancelled
 */
enum class TranscriptionStatus {
    QUEUED, STARTING, RECOGNIZING, DONE, FAILED, CANCELLED;

    /** Still on: waiting for its turn, starting, or being heard */
    val on get() = this in ON

    companion object {
        val ON = setOf(QUEUED, STARTING, RECOGNIZING)
    }
}

/**
 * One of an episode's files to be transcribed by a provider (V33__transcriptions.sql), from when it's asked for till
 * it's done, failed or cancelled, as its workflow goes (Transcription.kt), which keeps this up to date: how far it is,
 * for MetaOS's pages and tools, and what was transcribed when, by whom. The episode and the file are linked by ID, as
 * transcripts are.
 */
@Entity
@Table(name = "transcriptions")
class Transcription(
    @Column(name = "episode_id", nullable = false) val episodeId: UUID,
    @Column(name = "media_id", nullable = false) val mediaId: UUID,
    @Column(nullable = false, columnDefinition = "text") val mediaName: String,
    @Column(nullable = false, columnDefinition = "text") val provider: String,
    @Column(nullable = false, columnDefinition = "text") val language: String,
    @Column(nullable = false) val batch: Boolean,
    @Column(nullable = false, columnDefinition = "text") val workflowId: String,
    // Who asked for it: their Keycloak user ID and username then
    @Column(columnDefinition = "text") val requesterId: String?,
    @Column(columnDefinition = "text") val requester: String?,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    // The provider's, once it's started
    @Column(columnDefinition = "text") var model: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var status: TranscriptionStatus = TranscriptionStatus.QUEUED

    // How far the provider got, where it says, by tens
    var percent: Int? = null

    @Column(columnDefinition = "text") var error: String? = null

    // The transcript it made; it may have been replaced or removed since
    var transcriptId: UUID? = null

    @Column(nullable = false) val createdAt: OffsetDateTime = now()
    var startedAt: OffsetDateTime? = null
    var finishedAt: OffsetDateTime? = null

    override fun equals(other: Any?) = this === other || (other is Transcription && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Transcription(id=$id, mediaName=$mediaName, provider=$provider, status=$status)"
}

interface TranscriptionsRepository : JpaRepository<Transcription, UUID> {
    fun findByEpisodeIdOrderByCreatedAtDesc(episodeId: UUID): List<Transcription>
    fun findByStatusInOrderByCreatedAtDesc(statuses: Collection<TranscriptionStatus>, page: Pageable): List<Transcription>
    fun findByMediaIdAndProviderAndStatusIn(mediaId: UUID, provider: String, statuses: Collection<TranscriptionStatus>): Transcription?

    /** Ends one that's still on, as [status], and says so; not one that's ended meanwhile */
    @Modifying
    @Transactional
    @Query(
        "update Transcription t set t.status = :status, t.error = :error, t.percent = null, t.finishedAt = :at " +
            "where t.id = :id and t.status in :on"
    )
    fun endIfOn(id: UUID, status: TranscriptionStatus, error: String?, at: OffsetDateTime, on: Collection<TranscriptionStatus> = TranscriptionStatus.ON): Int
}

/** A transcription as MCP clients get it: what it's of, by whom, how far it is, and what it made */
data class TranscriptionDto(
    val id: UUID,
    val episodeId: UUID,
    val mediaId: UUID,
    val media: String,
    // Who hears it, google or soniox, and with which of its models, once it's started
    val provider: String,
    val model: String?,
    val language: String,
    val batch: Boolean,
    val status: TranscriptionStatus,
    // While it's QUEUED: its place in line, 1 next
    val queued: Int?,
    // While the provider hears it, how far it got, where it says
    val percent: Int?,
    // Why it failed
    val error: String?,
    // The transcript it made, for get_episode_transcript and remove_transcript
    val transcriptId: UUID?,
    // Their person's name, or else their username
    val requestedBy: String?,
    val createdAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
    val note: String? = null,
)

/** A provider MetaOS has: its name, model, and how many it hears at once (metaos.slots.<name>) */
data class SpeechProviderDto(val name: String, val model: String, val slots: Int)

/**
 * Transcribing episodes' files, for MCP (TranscriptsMCP) and GraphQL (TranscriptsGraphQL) alike: starting one, by
 * Google's Speech-to-Text or Soniox, in its workflow (Transcription.kt), and its row in transcriptions; listing and
 * cancelling them; reading and removing the transcripts they made. Transcribing needs Temporal (TEMPORAL_ADDRESS) and
 * the bucket; reading needs neither.
 */
@Component
class Transcriptions(
    private val client: ObjectProvider<WorkflowClient>,
    private val speech: ObjectProvider<SpeechProviders>,
    private val episodes: EpisodesRepository,
    private val episodePeople: EpisodePeopleRepository,
    private val media: MediaFilesRepository,
    private val transcripts: TranscriptsRepository,
    private val texts: TranscriptTextsRepository,
    private val jobs: TranscriptionsRepository,
    private val accounts: Accounts,
    private val jsonMapper: JsonMapper,
    private val environment: Environment,
    @Value("\${metaos.transcription.poll-every:30s}") private val pollEvery: Duration,
) {

    private fun workflows() = checkNotNull(client.ifAvailable) { "MetaOS runs no workflows (TEMPORAL_ADDRESS), so it can't transcribe" }

    private fun providers() =
        checkNotNull(speech.ifAvailable) { "MetaOS has no bucket for video and audio (GOOGLE_STORAGE_KEY), so nothing to transcribe" }

    fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    // The one asked for, else the episode's full audio, else its full video, which the provider hears the audio of
    private fun pick(episode: Episode, mediaId: UUID?): MediaFile {
        val files = media.findByEpisodeIdInOrderByUploadedAtAsc(listOf(checkNotNull(episode.id)))
        if (mediaId != null) return requireNotNull(files.find { it.id == mediaId }) { "${episode.code ?: episode.title} has no file $mediaId" }
        return requireNotNull(files.find { it.kind == MediaKind.FULL_AUDIO } ?: files.find { it.kind == MediaKind.FULL_VIDEO }) {
            "${episode.code ?: episode.title} has no full audio or video to transcribe; upload one on its page"
        }
    }

    /** The providers MetaOS has, e.g. for a page to choose from */
    fun providerList(): List<SpeechProviderDto> = speech.ifAvailable?.all.orEmpty().map {
        SpeechProviderDto(it.provider, it.model, environment.getProperty("metaos.slots.${it.provider}", Int::class.java, 5))
    }.sortedBy { it.name }

    /**
     * Starts transcribing the episode's file by [provider], else MetaOS's (metaos.speech.provider), as spoken in
     * [language] (BCP-47), else its show's language; one that has a transcript by that provider only when it's to be
     * replaced, and none that it's transcribing
     */
    fun start(ref: String, mediaId: UUID?, provider: String?, language: String?, batch: Boolean, replace: Boolean): Transcription {
        val workflows = workflows()
        val providers = providers()
        val by = providers.of(provider?.trim()?.lowercase()?.ifEmpty { null } ?: providers.default).provider
        val episode = findEpisode(ref)
        val file = pick(episode, mediaId)
        val fileId = checkNotNull(file.id)
        transcripts.findSpoken(fileId, by)?.let {
            require(replace) { "${file.name} has a transcript by $by already, from ${it.createdAt.toLocalDate()}; replace it, or keep that one" }
        }
        jobs.findByMediaIdAndProviderAndStatusIn(fileId, by, TranscriptionStatus.ON)?.let { on ->
            reconcile(listOf(on))
            require(jobs.findByIdOrNull(checkNotNull(on.id))?.status?.on != true) { "${file.name} is being transcribed by $by already" }
        }

        val jwt = currentJwt()
        val workflowId = TranscriptionWorkflow.workflowId(fileId, by)
        val spoken = language?.let(::languageTag) ?: episode.language
        val job = jobs.saveAndFlush(
            Transcription(
                checkNotNull(episode.id), fileId, file.name, by, spoken, batch, workflowId,
                jwt?.subject, jwt?.getClaimAsString("preferred_username"),
            )
        )
        val request = TranscriptionRequest(
            transcriptionId = checkNotNull(job.id),
            episodeId = checkNotNull(episode.id),
            mediaId = fileId,
            language = spoken,
            provider = by,
            hints = SpeechHints(
                title = episode.title,
                show = episode.show?.name,
                people = episodePeople.findByEpisodeWithPerson(episode).filter { it.role != Role.CANDIDATE }
                    .map { it.person.name }.distinct(),
            ),
            batch = batch,
            pollMillis = pollEvery.toMillis(),
            requesterId = job.requesterId,
            requester = job.requester,
        )
        val workflow = workflows.newWorkflowStub(
            TranscriptionWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(WORKFLOWS_QUEUE)
                .setWorkflowId(workflowId)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build(),
        )
        try {
            WorkflowClient.start(workflow::transcribe, request)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            jobs.endIfOn(checkNotNull(job.id), TranscriptionStatus.FAILED, "It was being transcribed already", now())
            throw IllegalArgumentException("${file.name} is being transcribed by $by already")
        } catch (e: RuntimeException) {
            jobs.endIfOn(checkNotNull(job.id), TranscriptionStatus.FAILED, "Its workflow didn't start: ${e.message}", now())
            throw e
        }
        return job
    }

    /** Cancels a transcription that's on: its workflow stops, and with it the provider, where it can */
    fun cancel(id: UUID): Transcription {
        val job = requireNotNull(jobs.findByIdOrNull(id)) { "No transcription $id" }
        require(job.status.on) { "The transcription of ${job.mediaName} by ${job.provider} is ${job.status.name.lowercase()} already" }
        val workflow = client.ifAvailable?.newUntypedWorkflowStub(job.workflowId)
        val cancelling = workflow != null && !ended(job) && try {
            workflow.cancel()
            true
        } catch (e: WorkflowNotFoundException) {
            false
        }
        if (!cancelling) jobs.endIfOn(id, TranscriptionStatus.CANCELLED, null, now())
        return checkNotNull(jobs.findByIdOrNull(id))
    }

    /**
     * Transcriptions, the latest first: those in [statuses], by [provider] and of [episode] where they're given, at most
     * [limit]; those on whose workflow ended without saying so (e.g. terminated) as failed
     */
    fun list(statuses: Collection<TranscriptionStatus>, provider: String?, episode: UUID?, limit: Int): List<Transcription> {
        fun find(): List<Transcription> {
            val found = if (episode != null) jobs.findByEpisodeIdOrderByCreatedAtDesc(episode).filter { it.status in statuses }
            else jobs.findByStatusInOrderByCreatedAtDesc(statuses, PageRequest.of(0, limit.coerceIn(1, 500)))
            return found.filter { provider == null || it.provider == provider.trim().lowercase() }.take(limit)
        }
        val found = find()
        return if (reconcile(found.filter { it.status.on }) > 0) find() else found
    }

    /** A transcription by its ID, as it is now: one on whose workflow ended without saying so (terminated) as failed */
    fun get(id: UUID): Transcription {
        val job = requireNotNull(jobs.findByIdOrNull(id)) { "No transcription $id" }
        return if (job.status.on && reconcile(listOf(job)) > 0) checkNotNull(jobs.findByIdOrNull(id)) else job
    }

    /**
     * Whether a transcription's workflow ended: its run, the latest of its ID, which started once it was asked for, no
     * longer runs; or, with no such run (none yet, or an earlier one's), a minute after it was asked for, as its workflow
     * starts right after its row is in
     */
    private fun ended(job: Transcription): Boolean {
        val workflows = client.ifAvailable ?: return false
        val run = try {
            workflows.newUntypedWorkflowStub(job.workflowId).describe()
        } catch (e: WorkflowNotFoundException) {
            null
        }
        // Give or take Temporal's clock against MetaOS's
        val ours = run != null && !run.startTime.isBefore(job.createdAt.toInstant().minusSeconds(5))
        return if (ours) run.status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        else job.createdAt.isBefore(now().minusMinutes(1))
    }

    // Those on whose workflow ended without saying how it went, e.g. terminated, as failed. How many.
    private fun reconcile(on: List<Transcription>): Int {
        if (client.ifAvailable == null) return 0
        return on.filter(::ended).sumOf {
            jobs.endIfOn(checkNotNull(it.id), TranscriptionStatus.FAILED, "Its workflow ended without saying how it went", now())
        }
    }

    /** Their places in line, 1 next, of those QUEUED, as their providers' slots have them (Slots.kt) */
    fun places(of: Collection<Transcription>): Map<UUID, Int> {
        val queued = of.filter { it.status == TranscriptionStatus.QUEUED }
        val workflows = client.ifAvailable ?: return emptyMap()
        return queued.groupBy { it.provider }.flatMap { (provider, those) ->
            val waiting = runCatching {
                workflows.newWorkflowStub(SlotsWorkflow::class.java, SlotsWorkflow.workflowId(provider)).state().waiting
            }.getOrDefault(emptyList())
            those.mapNotNull { job -> waiting.indexOf(job.workflowId).takeIf { it >= 0 }?.let { checkNotNull(job.id) to it + 1 } }
        }.toMap()
    }

    /** Who asked for each, by their Keycloak user ID: their person's name, or else their username */
    fun requesters(of: Collection<Pair<String?, String?>>): Map<String, String> {
        val people = accounts.people(of.mapNotNull { it.first })
        return of.mapNotNull { (id, username) -> id?.let { it to (people[it]?.name ?: username ?: "Someone") } }.toMap()
    }

    fun dtos(of: List<Transcription>, note: String? = null): List<TranscriptionDto> {
        val places = places(of)
        val names = requesters(of.map { it.requesterId to it.requester })
        return of.map { job ->
            TranscriptionDto(
                checkNotNull(job.id), job.episodeId, job.mediaId, job.mediaName, job.provider, job.model, job.language,
                job.batch, job.status, places[job.id], job.percent, job.error, job.transcriptId,
                job.requesterId?.let { names[it] } ?: job.requester, job.createdAt, job.startedAt, job.finishedAt, note,
            )
        }
    }

    /** What the transcript says, from the database */
    fun content(transcript: Transcript): TranscriptContent {
        val stored = checkNotNull(texts.findByIdOrNull(checkNotNull(transcript.id))) { "The transcript of ${transcript.mediaName} has no text" }
        return jsonMapper.readValue<TranscriptContent>(stored.content)
    }

    fun speakers(transcript: Transcript): List<TranscriptSpeaker> = jsonMapper.readValue(transcript.speakers)

    /** What was said, a line a stretch, each from where it starts: [h:mm:ss] Speaker 1: text, by their names once named */
    fun text(transcript: Transcript): String {
        val names = speakers(transcript).associate { it.label to it.name }
        return content(transcript).segments.joinToString("\n") { segment ->
            val who = segment.speaker?.let { "${names[it] ?: "Speaker $it"}: " }.orEmpty()
            "[${timestamp(segment.start)}] $who${segment.text}"
        }
    }

    /** Removes a transcript, and what it says with it; the bucket keeps its files, and the history that it was there */
    @Transactional
    fun removeTranscript(id: UUID): Transcript {
        val transcript = requireNotNull(transcripts.findByIdOrNull(id)) { "No transcript $id" }
        transcripts.delete(transcript)
        return transcript
    }
}
