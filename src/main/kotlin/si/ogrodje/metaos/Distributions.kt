package si.ogrodje.metaos

import jakarta.persistence.*
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The most a distribution's note may say, in characters */
const val MAX_DISTRIBUTION_NOTE_LENGTH = 2000

/** Ogrodje's, for a day without a time: when posts went out */
private val OGRODJE_ZONE: ZoneId = ZoneId.of("Europe/Ljubljana")

// To the microsecond, as Postgres keeps it
private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

/**
 * Where an episode went out: by the host of its URL, the first that has it (a subdomain too), else OTHER, which goes by
 * its page's site name. Mastodon has no host of its own, so it's chosen by hand, as is anything else a host doesn't say.
 */
enum class Channel(val label: String, private vararg val hosts: String) {
    YOUTUBE("YouTube", "youtube.com", "youtu.be"),
    PATREON("Patreon", "patreon.com"),
    SPOTIFY("Spotify", "spotify.com", "spotify.link"),
    APPLE_PODCASTS("Apple Podcasts", "podcasts.apple.com"),
    DISCORD("Discord", "discord.com", "discord.gg", "discordapp.com"),
    LINKEDIN("LinkedIn", "linkedin.com", "lnkd.in"),
    FACEBOOK("Facebook", "facebook.com", "fb.com", "fb.watch"),
    INSTAGRAM("Instagram", "instagram.com"),
    REDDIT("Reddit", "reddit.com", "redd.it"),
    X("X", "x.com", "twitter.com"),
    BLUESKY("Bluesky", "bsky.app"),
    MASTODON("Mastodon"),
    THREADS("Threads", "threads.net", "threads.com"),
    MONITOR("Monitor", "monitor.si"),
    NEWSLETTER("Newsletter", "substack.com"),
    OTHER("Other");

    fun has(host: String) = hosts.any { host == it || host.endsWith(".$it") }

    companion object {
        fun of(url: String): Channel {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return OTHER
            return entries.firstOrNull { it.has(host) } ?: OTHER
        }
    }
}

/** The channel each of an episode's links is on */
val Platform.channel: Channel
    get() = when (this) {
        Platform.YOUTUBE -> Channel.YOUTUBE
        Platform.SPOTIFY -> Channel.SPOTIFY
        Platform.APPLE_PODCASTS -> Channel.APPLE_PODCASTS
        Platform.PATREON, Platform.PATREON_AUDIO, Platform.PATREON_BONUS, Platform.PATREON_BONUS_AUDIO -> Channel.PATREON
    }

/** Whether a distribution has its preview: being fetched (or not yet), fetched, or not to be had, e.g. behind a login */
enum class PreviewStatus { PENDING, OK, UNAVAILABLE }

/**
 * Somewhere an episode went out (V34__distributions.sql): one of its own links ([platform]), kept as the episode has
 * it, or a post that promotes it, e.g. on LinkedIn, with what its page's OpenGraph tags said (DistributionPreview.kt).
 * [title] and [note] are what someone gave it, and outlast the preview.
 */
@Entity
@Table(name = "distributions")
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
class Distribution(
    @Column(name = "episode_id", nullable = false) val episodeId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var platform: Platform?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var channel: Channel,
    @Column(nullable = false, columnDefinition = "text") var url: String,
    // Who added it: their Keycloak user ID and their username then; for a link, whoever set it
    @Column(columnDefinition = "text") val addedById: String?,
    @Column(columnDefinition = "text") val addedBy: String?,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    @Column(columnDefinition = "text") var title: String? = null
    @Column(columnDefinition = "text") var note: String? = null
    var postedAt: OffsetDateTime? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var previewStatus: PreviewStatus = PreviewStatus.PENDING
    @Column(columnDefinition = "text") var previewTitle: String? = null
    @Column(columnDefinition = "text") var previewDescription: String? = null
    @Column(columnDefinition = "text") var previewSite: String? = null
    var previewPublishedAt: OffsetDateTime? = null
    var previewImageId: UUID? = null
    @Column(columnDefinition = "text") var previewError: String? = null
    var previewedAt: OffsetDateTime? = null

    @Column(nullable = false) val createdAt: OffsetDateTime = now()

    // Postgres's, by its trigger
    @Column(insertable = false, updatable = false) val updatedAt: OffsetDateTime? = null

    /** Its preview is to be fetched again, e.g. for a new URL; what it had stays until then */
    fun repreview() {
        previewStatus = PreviewStatus.PENDING
        previewError = null
    }

    override fun equals(other: Any?) = this === other || (other is Distribution && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Distribution(id=$id, episodeId=$episodeId, channel=$channel)"
}

interface DistributionsRepository : JpaRepository<Distribution, UUID> {
    fun findByEpisodeIdInOrderByCreatedAtAsc(episodeIds: Collection<UUID>): List<Distribution>

    fun findByEpisodeIdAndPlatform(episodeId: UUID, platform: Platform): Distribution?

    // The latest first, by when they went out, else when they were added; of episodes that are there, on the channels
    @Query(
        """
        select d from Distribution d
        where d.channel in :channels
          and coalesce(d.postedAt, d.previewPublishedAt, d.createdAt) >= :since
          and exists (select 1 from Episode e where e.id = d.episodeId)
        order by coalesce(d.postedAt, d.previewPublishedAt, d.createdAt) desc
        """
    )
    fun findLatest(channels: Collection<Channel>, since: OffsetDateTime, pageable: Pageable): List<Distribution>

    // Those whose preview hasn't been fetched since before [before], e.g. as the workflow never started
    fun findByPreviewStatusAndUpdatedAtBefore(status: PreviewStatus, before: OffsetDateTime, pageable: Pageable): List<Distribution>
}

// Not part of what a URL points to, but who shared it or how it was found
private val TRACKING = setOf(
    "fbclid", "gclid", "dclid", "msclkid", "igsh", "igshid", "si", "mc_cid", "mc_eid", "ref_src", "ref_url", "_hsenc",
    "_hsmi", "trk", "trackingid", "rcm",
)

/** A post's URL as it's kept: https, its host in lower case, without tracking parameters (utm_*, fbclid, ...) */
fun distributionUrl(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull()
    require(uri != null && uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()) { "$url is not an https URL" }
    val query = uri.rawQuery?.split('&')?.filter { part ->
        val name = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8).lowercase()
        part.isNotEmpty() && !name.startsWith("utm_") && name !in TRACKING
    }?.joinToString("&")?.ifEmpty { null }
    return buildString {
        append("https://").append(uri.host.lowercase())
        if (uri.port != -1 && uri.port != 443) append(':').append(uri.port)
        append(uri.rawPath ?: "")
        query?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}

/** When something went out: a day, e.g. 2026-11-10, at its start in Ljubljana, or an ISO 8601 date and time */
fun postedAt(value: String): OffsetDateTime {
    val text = value.trim()
    return try {
        OffsetDateTime.parse(text)
    } catch (_: DateTimeParseException) {
        try {
            LocalDate.parse(text).atStartOfDay(OGRODJE_ZONE).toOffsetDateTime()
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("$value is not a date (2026-11-10) or a date and time (2026-11-10T09:30:00+01:00)")
        }
    }
}

/**
 * Episodes' distribution, for MCP (DistributionsMCP) and GraphQL (DistributionsGraphQL) alike: its links, which follow
 * the episode's (every change to a link goes through [setLink]), and its posts. A post's URL is an episode's once; the
 * same again is the one it has. Each new URL gets its preview fetched once it's in (DistributionPreviews).
 */
@Component
class Distributions(
    private val distributions: DistributionsRepository,
    private val episodes: EpisodesRepository,
    private val accounts: Accounts,
    private val previews: DistributionPreviews,
) {

    fun findEpisode(ref: String) = requireNotNull(episodes.findByRef(ref)) { "Episode $ref not found" }

    /** An episode's distribution: its links, in Platform's order, then its posts, the latest first */
    fun of(episode: Episode): List<Distribution> = onEpisodes(listOf(episode))[episode].orEmpty()

    /** The distribution of several episodes, in one query */
    fun onEpisodes(episodes: Collection<Episode>): Map<Episode, List<Distribution>> {
        val byId = distributions.findByEpisodeIdInOrderByCreatedAtAsc(episodes.mapNotNull { it.id }).groupBy { it.episodeId }
        return episodes.associateWith { episode -> byId[episode.id].orEmpty().sortedWith(ORDER) }
    }

    /** The latest posts and links, of every episode, on [channel] or any, at most [limit] */
    fun latest(channel: Channel?, since: OffsetDateTime?, limit: Int): List<Distribution> =
        distributions.findLatest(channel?.let(::listOf) ?: Channel.entries, since ?: OffsetDateTime.parse("2000-01-01T00:00:00Z"), Pageable.ofSize(limit.coerceIn(1, 500)))

    /** The people who added them, by their Keycloak user IDs; those not linked are left out */
    fun people(of: Collection<Distribution>): Map<String, Person> = accounts.people(of.mapNotNull { it.addedById })

    /**
     * Sets or clears one of the episode's links, as Episode.setLink checks it, and its distribution with it: a new link
     * is a new entry, or the post with its URL, which becomes the link; a changed one its entry with the new URL; a
     * cleared one goes
     */
    @Transactional
    fun setLink(episode: Episode, platform: Platform, url: String?): Distribution? {
        episode.setLink(platform, url?.trim()?.ifEmpty { null })
        val episodeId = checkNotNull(episode.id)
        val link = episode.link(platform)
        val row = distributions.findByEpisodeIdAndPlatform(episodeId, platform)
        if (link == null) {
            row?.let(distributions::delete)
            return null
        }
        if (row?.url == link) return row
        val entry = row?.apply { this.url = link; repreview() }
            ?: post(episodeId, link)?.apply { this.platform = platform; channel = platform.channel; this.url = link; repreview() }
            ?: added(episodeId, platform, platform.channel, link)
        return distributions.saveAndFlush(entry).also(::preview)
    }

    /**
     * Adds where the episode went out, or, with [link], makes the URL that one of its links. A URL it has already is
     * the entry it has, with what's given changed.
     */
    @Transactional
    fun add(
        episode: Episode,
        url: String,
        channel: Channel? = null,
        postedAt: String? = null,
        note: String? = null,
        title: String? = null,
        link: Platform? = null,
    ): Distribution {
        val episodeId = checkNotNull(episode.id)
        val entry = if (link != null) {
            require(channel == null || channel == link.channel) { "An episode's ${link.name} link is on ${link.channel.label}" }
            checkNotNull(setLink(episode, link, url))
        } else {
            val normalized = distributionUrl(url)
            existing(episode, normalized)?.also { had -> channel?.let { change(had, channel = it) } }
                ?: distributions.saveAndFlush(added(episodeId, null, channel ?: Channel.of(normalized), normalized)).also(::preview)
        }
        return change(entry, postedAt = postedAt, note = note, title = title)
    }

    /**
     * Changes an entry: what's left out stays as it is, and an empty note, title or date clears it. A link's URL is the
     * episode's link, which changes with it; its channel is its platform's.
     */
    @Transactional
    fun update(
        id: UUID,
        url: String? = null,
        channel: Channel? = null,
        postedAt: String? = null,
        note: String? = null,
        title: String? = null,
    ): Distribution {
        val entry = find(id)
        url?.let { new ->
            val platform = entry.platform
            if (platform != null) {
                setLink(episode(entry), platform, new.trim().ifEmpty { throw IllegalArgumentException("A link needs its URL; remove it to clear it") })
            } else {
                val normalized = distributionUrl(new)
                if (normalized != entry.url) {
                    val other = existing(episode(entry), normalized)
                    require(other == null) { "The episode has $normalized already" }
                    entry.url = normalized
                    entry.repreview()
                    distributions.saveAndFlush(entry).also(::preview)
                }
            }
        }
        return change(find(id), channel = channel, postedAt = postedAt, note = note, title = title)
    }

    /** Takes an entry off the episode; a link is cleared on the episode too. The history keeps it. */
    @Transactional
    fun remove(id: UUID): Distribution {
        val entry = find(id)
        val platform = entry.platform
        if (platform != null) setLink(episode(entry), platform, null) else distributions.delete(entry)
        return entry
    }

    /** Fetches an entry's preview again, e.g. as the post changed, or its page let MetaOS in this time */
    @Transactional
    fun refresh(id: UUID): Distribution {
        val entry = find(id)
        entry.repreview()
        return distributions.saveAndFlush(entry).also(::preview)
    }

    private fun find(id: UUID): Distribution {
        val entry = requireNotNull(distributions.findByIdOrNull(id)) { "Distribution $id not found" }
        require(episodes.existsById(entry.episodeId)) { "The distribution's episode has been deleted" }
        return entry
    }

    private fun episode(entry: Distribution) =
        requireNotNull(episodes.findByIdOrNull(entry.episodeId)) { "The distribution's episode has been deleted" }

    // The post or link of the episode at that URL, however it was written
    private fun existing(episode: Episode, normalized: String): Distribution? =
        distributions.findByEpisodeIdInOrderByCreatedAtAsc(listOf(checkNotNull(episode.id)))
            .firstOrNull { runCatching { distributionUrl(it.url) }.getOrNull() == normalized }

    private fun post(episodeId: UUID, url: String): Distribution? {
        val normalized = runCatching { distributionUrl(url) }.getOrNull() ?: return null
        return distributions.findByEpisodeIdInOrderByCreatedAtAsc(listOf(episodeId))
            .firstOrNull { it.platform == null && it.url == normalized }
    }

    private fun added(episodeId: UUID, platform: Platform?, channel: Channel, url: String): Distribution {
        val jwt = currentJwt()
        return Distribution(episodeId, platform, channel, url, jwt?.subject, jwt?.getClaimAsString("preferred_username"))
    }

    private fun change(
        entry: Distribution,
        channel: Channel? = null,
        postedAt: String? = null,
        note: String? = null,
        title: String? = null,
    ): Distribution {
        channel?.let {
            require(entry.platform == null || it == entry.channel) { "An episode's ${entry.platform} link is on ${entry.channel.label}" }
            entry.channel = it
        }
        postedAt?.let { entry.postedAt = it.trim().ifEmpty { null }?.let(::postedAt)?.truncatedTo(ChronoUnit.MICROS) }
        note?.let {
            val text = it.trim().ifEmpty { null }
            require(text == null || text.length <= MAX_DISTRIBUTION_NOTE_LENGTH) {
                "A note can be at most $MAX_DISTRIBUTION_NOTE_LENGTH characters long"
            }
            entry.note = text
        }
        title?.let { entry.title = it.trim().ifEmpty { null } }
        return distributions.saveAndFlush(entry)
    }

    // Once the transaction's in, so the workflow finds the entry as it is
    private fun preview(entry: Distribution) {
        val id = checkNotNull(entry.id)
        // Whoever asked for it, else whoever added it, e.g. for a preview started again later
        val jwt = currentJwt()
        val actor = if (jwt != null) Actor(jwt.subject, jwt.getClaimAsString("preferred_username")) else Actor(entry.addedById, entry.addedBy)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = previews.start(id, actor)
            })
        } else {
            previews.start(id, actor)
        }
    }

    private companion object {
        // Its links first, as the episode lists them, then its posts, the latest first
        val ORDER = compareBy<Distribution> { it.platform == null }
            .thenBy { it.platform?.ordinal }
            .thenByDescending { it.postedAt ?: it.previewPublishedAt ?: it.createdAt }
    }
}
