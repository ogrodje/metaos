package si.ogrodje.metaos

import jakarta.persistence.*
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.UUID

/** What episodes are spoken in, unless their show says otherwise, as a BCP-47 tag */
const val DEFAULT_LANGUAGE = "sl-SI"

/** A language as a BCP-47 tag, e.g. sl-SI or en-US, however it's cased */
fun languageTag(tag: String): String {
    val locale = try {
        Locale.Builder().setLanguageTag(tag.trim()).build()
    } catch (e: IllformedLocaleException) {
        throw IllegalArgumentException("$tag isn't a language tag like sl-SI or en-US", e)
    }
    require(locale.language.isNotEmpty()) { "$tag isn't a language tag like sl-SI or en-US" }
    return locale.toLanguageTag()
}

/** A language's name in English, e.g. Slovenian for sl-SI */
fun languageName(tag: String): String = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)

@Entity
@Table(name = "shows")
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
class Show(
    @Column(nullable = false, columnDefinition = "citext") var name: String,
    @Column(nullable = true, columnDefinition = "text") var color: String?,
    // What its episodes are spoken in (V31__show_languages.sql): Slovenian, but Borderless's are in English
    @Column(nullable = false, columnDefinition = "text") var language: String = DEFAULT_LANGUAGE,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    override fun equals(other: Any?) = this === other || (other is Show && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Show(id=$id, name=$name)"
}

/** What the episode is spoken in: its show's language, else Slovenian */
val Episode.language: String get() = show?.language ?: DEFAULT_LANGUAGE

interface ShowsRepository : JpaRepository<Show, UUID> {
    fun findByName(name: String): Show?
    fun findAllByOrderByName(): List<Show>

    // Soft-deleted shows are hidden from JPA queries, so finding and restoring one takes native SQL
    @Query(
        "select id from shows where name = :name and deleted_at is not null order by deleted_at desc limit 1",
        nativeQuery = true,
    )
    fun findDeletedIdByName(name: String): UUID?

    @Modifying(clearAutomatically = true)
    @Query("update shows set deleted_at = null where id = :id", nativeQuery = true)
    fun restore(id: UUID): Int

    // A deleted show's name is free for a live one, so it may come back under another
    @Modifying(clearAutomatically = true)
    @Query("update shows set name = :name where id = :id and deleted_at is not null", nativeQuery = true)
    fun renameDeleted(id: UUID, name: String): Int
}
