package si.ogrodje.metaos

import jakarta.persistence.*
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.net.URI
import java.util.UUID

@Entity
@Table(name = "people")
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
class Person(
    @Column(nullable = false, columnDefinition = "text") var name: String,
    @Column(nullable = true, columnDefinition = "citext") var email: String?,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    // A line about who they are, e.g. their role and company
    @Column(columnDefinition = "text") var bio: String? = null

    @Column(columnDefinition = "text") var homepageUrl: String? = null
    @Column(columnDefinition = "text") var linkedinUrl: String? = null
    @Column(columnDefinition = "text") var twitterUrl: String? = null
    @Column(columnDefinition = "text") var instagramUrl: String? = null
    @Column(columnDefinition = "text") var facebookUrl: String? = null

    // Their picture in the public bucket (Images.kt)
    @Column(name = "avatar_image_id") var avatarImageId: UUID? = null

    fun setSocialLink(network: SocialNetwork, url: String?) {
        require(url == null || network.accepts(url)) { "$url is not a $network URL" }
        when (network) {
            SocialNetwork.HOMEPAGE -> homepageUrl = url
            SocialNetwork.LINKEDIN -> linkedinUrl = url
            SocialNetwork.TWITTER -> twitterUrl = url
            SocialNetwork.INSTAGRAM -> instagramUrl = url
            SocialNetwork.FACEBOOK -> facebookUrl = url
        }
    }

    override fun equals(other: Any?) = this === other || (other is Person && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Person(id=$id, name=$name)"
}

/**
 * Where a person can be found online. A homepage can be any http or https URL; the others have to be https URLs on the
 * network's domain or a subdomain of it (www.linkedin.com, si.linkedin.com, ...).
 */
enum class SocialNetwork(private vararg val domains: String) {
    HOMEPAGE,
    LINKEDIN("linkedin.com"),
    TWITTER("twitter.com", "x.com"),
    INSTAGRAM("instagram.com"),
    FACEBOOK("facebook.com");

    fun accepts(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        return if (domains.isEmpty()) uri.scheme in setOf("http", "https")
        else uri.scheme == "https" && domains.any { host == it || host.endsWith(".$it") }
    }
}

// In the order an episode's people are listed. A candidate could be the episode's guest, until confirm_guest makes
// them one; only the team sees them.
enum class Role { HOST, CO_HOST, GUEST, PRODUCER, CONSULTANT, DESIGNER, CANDIDATE }

@Entity
@Table(name = "episode_people")
class EpisodePerson(
    // Eager, because Hibernate can't proxy a to-one whose target uses @SoftDelete.
    @ManyToOne(optional = false)
    @JoinColumn(name = "episode_id")
    val episode: Episode,

    @ManyToOne(optional = false)
    @JoinColumn(name = "person_id")
    val person: Person,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var role: Role,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    override fun equals(other: Any?) = this === other || (other is EpisodePerson && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "EpisodePerson(id=$id, role=$role)"
}

interface PeopleRepository : JpaRepository<Person, UUID> {
    fun findByEmail(email: String): Person?

    // Soft-deleted people are hidden from JPA queries, so finding and restoring one takes native SQL
    @Query("select count(*) > 0 from people where id = :id and deleted_at is not null", nativeQuery = true)
    fun isDeleted(id: UUID): Boolean

    @Query("select cast(email as text) from people where id = :id and deleted_at is not null", nativeQuery = true)
    fun findDeletedEmail(id: UUID): String?

    @Modifying(clearAutomatically = true)
    @Query("update people set deleted_at = null where id = :id", nativeQuery = true)
    fun restore(id: UUID): Int

    // A deleted person's email is free for a live one, so they may come back with another, or none
    @Modifying(clearAutomatically = true)
    @Query("update people set email = :email where id = :id and deleted_at is not null", nativeQuery = true)
    fun setDeletedEmail(id: UUID, email: String?): Int
}

interface EpisodePeopleRepository : JpaRepository<EpisodePerson, UUID> {
    fun findByEpisodeAndPerson(episode: Episode, person: Person): List<EpisodePerson>
    fun findByEpisodeAndPersonAndRole(episode: Episode, person: Person, role: Role): EpisodePerson?

    @Query("select ep from EpisodePerson ep join fetch ep.person where ep.episode = :episode")
    fun findByEpisodeWithPerson(episode: Episode): List<EpisodePerson>

    @Query("select ep from EpisodePerson ep join fetch ep.person where ep.episode in :episodes")
    fun findByEpisodesWithPerson(episodes: Collection<Episode>): List<EpisodePerson>

    @Query("select ep from EpisodePerson ep join fetch ep.episode e where ep.person = :person order by $LATEST_FIRST")
    fun findByPersonWithEpisode(person: Person): List<EpisodePerson>

    @Query("select ep from EpisodePerson ep join fetch ep.episode e join fetch ep.person where ep.person in :people order by $LATEST_FIRST")
    fun findByPeopleWithEpisode(people: Collection<Person>): List<EpisodePerson>

    // Who's on the live episodes, for search
    @Query("select ep from EpisodePerson ep join fetch ep.episode join fetch ep.person")
    fun findAllWithEpisodeAndPerson(): List<EpisodePerson>
}
