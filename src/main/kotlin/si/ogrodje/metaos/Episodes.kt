package si.ogrodje.metaos

import jakarta.persistence.*
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import java.net.URI
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "episodes")
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
class Episode(
    @Column(nullable = true, columnDefinition = "text") var title: String,
    // Ideas can do without one, see EpisodeStatus.codeOptional
    @Column(nullable = true, columnDefinition = "citext") var code: String?,
) {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var status: EpisodeStatus = EpisodeStatus.IDEA

    var recordingDate: LocalDate? = null
    var airedDate: LocalDate? = null
    @Column(nullable = false) var addedDate: LocalDate = LocalDate.now()

    // Other titles the title is A/B tested against. Named, as Hibernate would make titleB into titleb.
    @Column(name = "title_b", columnDefinition = "text") var titleB: String? = null
    @Column(name = "title_c", columnDefinition = "text") var titleC: String? = null

    // Eager, because Hibernate can't proxy a to-one whose target uses @SoftDelete.
    @ManyToOne
    @JoinColumn(name = "session_id")
    var session: Session? = null

    // Eager, for the same reason
    @ManyToOne
    @JoinColumn(name = "show_id")
    var show: Show? = null

    // Who recommended it: by default the person of whoever added it. Eager, for the same reason.
    @ManyToOne
    @JoinColumn(name = "recommended_by")
    var recommendedBy: Person? = null

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Column(columnDefinition = "text") var youtubeUrl: String? = null
    @Column(columnDefinition = "text") var spotifyUrl: String? = null
    @Column(columnDefinition = "text") var applePodcastsUrl: String? = null
    // Patreon has four of each episode (Platform): the full episode and its bonus, each as video and audio only
    @Column(columnDefinition = "text") var patreonUrl: String? = null
    @Column(columnDefinition = "text") var patreonAudioUrl: String? = null
    @Column(columnDefinition = "text") var patreonBonusUrl: String? = null
    @Column(columnDefinition = "text") var patreonBonusAudioUrl: String? = null
    val patreonLinks get() = listOf(patreonUrl, patreonAudioUrl, patreonBonusUrl, patreonBonusAudioUrl)

    // Markdown; the tiny summary is a very short one, e.g. a sentence
    @Column(columnDefinition = "text") var summary: String? = null
    @Column(columnDefinition = "text") var tinySummary: String? = null
    @Column(columnDefinition = "text") var machineSummary: String? = null

    // Its pictures in the public bucket (Images.kt), by EpisodeImage
    @Column(name = "thumbnail_image_id") var thumbnailImageId: UUID? = null
    @Column(name = "thumbnail_b_image_id") var thumbnailBImageId: UUID? = null
    @Column(name = "thumbnail_c_image_id") var thumbnailCImageId: UUID? = null
    @Column(name = "web_image_id") var webImageId: UUID? = null
    @Column(name = "cover_image_id") var coverImageId: UUID? = null

    fun imageId(kind: EpisodeImage): UUID? = when (kind) {
        EpisodeImage.THUMBNAIL -> thumbnailImageId
        EpisodeImage.THUMBNAIL_B -> thumbnailBImageId
        EpisodeImage.THUMBNAIL_C -> thumbnailCImageId
        EpisodeImage.WEB -> webImageId
        EpisodeImage.COVER -> coverImageId
    }

    fun setImageId(kind: EpisodeImage, id: UUID?) {
        when (kind) {
            EpisodeImage.THUMBNAIL -> thumbnailImageId = id
            EpisodeImage.THUMBNAIL_B -> thumbnailBImageId = id
            EpisodeImage.THUMBNAIL_C -> thumbnailCImageId = id
            EpisodeImage.WEB -> webImageId = id
            EpisodeImage.COVER -> coverImageId = id
        }
    }

    fun setLink(platform: Platform, url: String?) {
        require(url == null || platform.accepts(url)) { "$url is not a $platform URL" }
        when (platform) {
            Platform.YOUTUBE -> youtubeUrl = url
            Platform.SPOTIFY -> spotifyUrl = url
            Platform.APPLE_PODCASTS -> applePodcastsUrl = url
            Platform.PATREON -> patreonUrl = url
            Platform.PATREON_AUDIO -> patreonAudioUrl = url
            Platform.PATREON_BONUS -> patreonBonusUrl = url
            Platform.PATREON_BONUS_AUDIO -> patreonBonusAudioUrl = url
        }
    }

    override fun equals(other: Any?) = this === other || (other is Episode && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Episode(id=$id, title=$title)"


}

/**
 * Where episodes are published; a link has to be an https URL on one of the platform's hosts. Patreon has four of each
 * episode: PATREON is the full episode as video (the only Patreon link there was at first), PATREON_AUDIO the full
 * episode as audio only, PATREON_BONUS the bonus as video and PATREON_BONUS_AUDIO the bonus as audio only.
 */
enum class Platform(private vararg val hosts: String) {
    YOUTUBE("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"),
    SPOTIFY("open.spotify.com"),
    APPLE_PODCASTS("podcasts.apple.com"),
    PATREON("patreon.com", "www.patreon.com"),
    PATREON_AUDIO("patreon.com", "www.patreon.com"),
    PATREON_BONUS("patreon.com", "www.patreon.com"),
    PATREON_BONUS_AUDIO("patreon.com", "www.patreon.com");

    fun accepts(url: String): Boolean =
        runCatching { URI(url) }.getOrNull()?.let { it.scheme == "https" && it.host in hosts } ?: false
}

/**
 * Where an episode is in its lifecycle. The main path runs IDEA → PLANNED → SCHEDULED → RECORDED → IN_PRODUCTION →
 * READY → EARLY_ACCESS → PUBLISHED: an episode moves one step forward (READY may skip EARLY_ACCESS) or back to any
 * earlier step. Before it is published it can be put ON_HOLD, from where it resumes at any step before PUBLISHED, or
 * CANCELLED, from where it can only become an IDEA again. PUBLISHED is final.
 */
enum class EpisodeStatus {
    IDEA, PLANNED, SCHEDULED, RECORDED, IN_PRODUCTION, READY, EARLY_ACCESS, PUBLISHED, ON_HOLD, CANCELLED;

    val codeOptional get() = this == IDEA || this == ON_HOLD || this == CANCELLED

    fun canMoveTo(target: EpisodeStatus): Boolean = when {
        target == this || this == PUBLISHED -> false
        this == CANCELLED -> target == IDEA
        target == ON_HOLD || target == CANCELLED -> true
        this == ON_HOLD -> target != PUBLISHED
        this == READY && target == PUBLISHED -> true
        else -> target.ordinal == ordinal + 1 || target.ordinal < ordinal
    }
}

const val EPISODE_REF = "Episode code, e.g. S01E03, or the episode's ID (ideas may not have a code yet)"

fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

/** An episode by its code (case-insensitive) or, as ideas may not have one, by its ID */
fun EpisodesRepository.findByRef(ref: String): Episode? =
    ref.toUuidOrNull()?.let { findByIdOrNull(it) } ?: findByCode(ref)

/** The season of one-off bonus episodes (S99E02), which isn't the latest one */
const val BONUS_SEASON = "S99"

// Episodes, as e, by code. Codes are zero-padded (S05E05), so descending puts the latest season and episode first;
// then the bonus season, and ideas without a code last.
const val LATEST_FIRST = "case when e.code is null then 2 when e.code like '${BONUS_SEASON}E%' then 1 else 0 end, e.code desc"

interface EpisodesRepository : JpaRepository<Episode, UUID> {
    fun findByCode(code: String): Episode?

    @Query("select e from Episode e order by $LATEST_FIRST")
    fun findLatestFirst(): List<Episode>

    // The most recently aired first; those without an aired date after them, by code
    @Query("select e from Episode e where e.status = :status order by e.airedDate desc nulls last, $LATEST_FIRST")
    fun findLatestAiredByStatus(status: EpisodeStatus, pageable: Pageable): List<Episode>

    @Query("select e from Episode e where e.status = :status order by $LATEST_FIRST")
    fun findLatestFirstByStatus(status: EpisodeStatus): List<Episode>

    @Query("select e from Episode e where e.status <> :status order by $LATEST_FIRST")
    fun findLatestFirstByStatusNot(status: EpisodeStatus): List<Episode>

    // The most recently added first, e.g. the ideas
    fun findByStatusOrderByAddedDateDescTitleAsc(status: EpisodeStatus): List<Episode>

    fun findByStatusIn(statuses: Collection<EpisodeStatus>): List<Episode>

    @Query("select e from Episode e where e.session = :session order by $LATEST_FIRST")
    fun findBySessionOrderByCodeDesc(session: Session): List<Episode>

    @Query("select e from Episode e where e.show = :show order by $LATEST_FIRST")
    fun findByShowOrderByCodeDesc(show: Show): List<Episode>

    fun findByRecommendedBy(person: Person): List<Episode>

    @Query("select e from Episode e where e.recommendedBy = :person order by $LATEST_FIRST")
    fun findLatestFirstByRecommendedBy(person: Person): List<Episode>

    // Soft-deleted episodes are hidden from JPA queries, so finding and restoring one takes native SQL
    @Query(
        "select id from episodes where code = :code and deleted_at is not null order by deleted_at desc limit 1",
        nativeQuery = true,
    )
    fun findDeletedIdByCode(code: String): UUID?

    @Query("select count(*) > 0 from episodes where id = :id and deleted_at is not null", nativeQuery = true)
    fun isDeleted(id: UUID): Boolean

    @Query("select cast(code as text) from episodes where id = :id and deleted_at is not null", nativeQuery = true)
    fun findDeletedCode(id: UUID): String?

    @Modifying(clearAutomatically = true)
    @Query("update episodes set deleted_at = null where id = :id", nativeQuery = true)
    fun restore(id: UUID): Int

    // A deleted episode's code is free for a live one, so it may come back under another
    @Modifying(clearAutomatically = true)
    @Query("update episodes set code = :code where id = :id and deleted_at is not null", nativeQuery = true)
    fun recodeDeleted(id: UUID, code: String): Int

    // Deleting a session or show takes its episodes out of it in the same transaction, so audit_log has them under
    // the same changed_at as the soft delete, as deleting a person takes them off the episodes they recommended. These
    // put them back, unless they have been deleted or changed since.
    @Modifying(clearAutomatically = true)
    @Query(
        """
        update episodes set recommended_by = :personId
        where deleted_at is null and recommended_by is null and id in (
            select row_id from audit_log
            where table_name = 'episodes'
              and old_row ->> 'recommended_by' = cast(:personId as text) and new_row ->> 'recommended_by' is null
              and changed_at = (select max(changed_at) from audit_log
                                where table_name = 'people' and row_id = :personId
                                  and old_row ->> 'deleted_at' is null and new_row ->> 'deleted_at' is not null))
        """,
        nativeQuery = true,
    )
    fun reattachToRecommender(personId: UUID): Int

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update episodes set session_id = :sessionId
        where deleted_at is null and session_id is null and id in (
            select row_id from audit_log
            where table_name = 'episodes'
              and old_row ->> 'session_id' = cast(:sessionId as text) and new_row ->> 'session_id' is null
              and changed_at = (select max(changed_at) from audit_log
                                where table_name = 'sessions' and row_id = :sessionId
                                  and old_row ->> 'deleted_at' is null and new_row ->> 'deleted_at' is not null))
        """,
        nativeQuery = true,
    )
    fun reattachToSession(sessionId: UUID): Int

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update episodes set show_id = :showId
        where deleted_at is null and show_id is null and id in (
            select row_id from audit_log
            where table_name = 'episodes'
              and old_row ->> 'show_id' = cast(:showId as text) and new_row ->> 'show_id' is null
              and changed_at = (select max(changed_at) from audit_log
                                where table_name = 'shows' and row_id = :showId
                                  and old_row ->> 'deleted_at' is null and new_row ->> 'deleted_at' is not null))
        """,
        nativeQuery = true,
    )
    fun reattachToShow(showId: UUID): Int
}