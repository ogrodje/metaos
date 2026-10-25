package si.ogrodje.metaos

import jakarta.persistence.*
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "sessions")
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
class Session(
    @Column(nullable = false) var date: LocalDate,
    @Column(nullable = true, columnDefinition = "text") var location: String?,
    @Column(nullable = true, columnDefinition = "text") var address: String?,
    // What it's called, e.g. AWS Community Day Adria 2026, apart from where it is. Without one it goes by its location,
    // else by its date (History.kt, the front-end's sessionTitle)
    @Column(nullable = true, columnDefinition = "text") var name: String? = null,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    override fun equals(other: Any?) = this === other || (other is Session && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Session(id=$id, date=$date, name=$name, location=$location)"
}

interface SessionsRepository : JpaRepository<Session, UUID> {
    fun findAllByOrderByDateDesc(): List<Session>

    // Soft-deleted sessions are hidden from JPA queries, so restoring one takes native SQL
    @Modifying(clearAutomatically = true)
    @Query("update sessions set deleted_at = null where id = :id and deleted_at is not null", nativeQuery = true)
    fun restore(id: UUID): Int
}

/**
 * Changes to sessions, for MCP (SessionsMCP) and GraphQL (SessionsGraphQL) alike. Text is trimmed, and empty text clears
 * a field; what's left out (null) is left as it is.
 */
@Component
class Sessions(private val sessions: SessionsRepository) {

    @Transactional
    fun create(date: LocalDate, name: String?, location: String?, address: String?): Session =
        sessions.save(Session(date = date, location = location.cleared(), address = address.cleared(), name = name.cleared()))

    @Transactional
    fun update(id: UUID, date: LocalDate? = null, name: String? = null, location: String? = null, address: String? = null): Session {
        val session = requireNotNull(sessions.findByIdOrNull(id)) { "Session $id not found" }
        date?.let { session.date = it }
        name?.let { session.name = it.cleared() }
        location?.let { session.location = it.cleared() }
        address?.let { session.address = it.cleared() }
        return session
    }

    private fun String?.cleared(): String? = this?.trim()?.ifEmpty { null }
}
