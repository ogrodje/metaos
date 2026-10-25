package si.ogrodje.metaos

import jakarta.persistence.*
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.filter.OncePerRequestFilter
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** How an account was linked to its person: by its verified email, or by someone */
enum class AccountLink { EMAIL, MANUAL }

/** The one user who links accounts to people by hand, by their verified email; for everyone else it's their email */
const val ACCOUNT_LINKER = "otobrglez@gmail.com"

/** Whether the signed-in user may link accounts to people and unlink them ([ACCOUNT_LINKER]) */
fun mayLinkAccounts(): Boolean = currentJwt()?.verifiedEmail.equals(ACCOUNT_LINKER, ignoreCase = true)

fun requireLinker() {
    if (!mayLinkAccounts()) throw AccessDeniedException("Only $ACCOUNT_LINKER links accounts to people")
}

/** An account as MCP clients get it, with its person's name and who linked it, by name */
data class AccountDto(
    val id: UUID?,
    // Keycloak's user ID, the token's sub
    val subject: String,
    val username: String?,
    val name: String?,
    val email: String?,
    val emailVerified: Boolean,
    val personId: UUID?,
    val person: String?,
    val linkedHow: AccountLink?,
    val linkedAt: OffsetDateTime?,
    val linkedBy: String?,
    val createdAt: OffsetDateTime?,
)

/**
 * A Keycloak user MetaOS has seen, by its ID (the token's sub), and the person it is (V25__accounts.sql). It may sign in
 * with several identity providers, e.g. Google and Discord: Keycloak links those to one user, so they're one account.
 */
@Entity
@Table(name = "accounts")
class Account(@Column(nullable = false, columnDefinition = "text") val subject: String) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
        protected set

    // As the token said them, the last time they changed
    @Column(columnDefinition = "text") var username: String? = null
    @Column(columnDefinition = "text") var name: String? = null
    @Column(columnDefinition = "citext") var email: String? = null
    @Column(nullable = false) var emailVerified: Boolean = false

    @Column(name = "person_id") var personId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var linkedHow: AccountLink? = null

    // Who linked it, by sub; null when its email did
    @Column(columnDefinition = "text") var linkedBy: String? = null
    var linkedAt: OffsetDateTime? = null

    // Whether its verified email may link it to the person with that email; not once someone has unlinked it
    @Column(nullable = false) var emailLinks: Boolean = true

    @Column(insertable = false, updatable = false) var createdAt: OffsetDateTime? = null
        protected set

    /** What people know it by: its username, or else its email */
    val label get() = username ?: email ?: subject

    override fun equals(other: Any?) = this === other || (other is Account && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Account(id=$id, username=$username)"
}

interface AccountsRepository : JpaRepository<Account, UUID> {
    fun findBySubject(subject: String): Account?
    fun findBySubjectIn(subjects: Collection<String>): List<Account>
    fun findByPersonIdIn(personIds: Collection<UUID>): List<Account>
    fun findAllByOrderByCreatedAtDesc(): List<Account>
    fun findByPersonIdIsNullOrderByCreatedAtDesc(): List<Account>
}

// How long a pod takes a sign-in it has written down as it is, before it looks again, e.g. for an email linked meanwhile
private const val RECHECK_MILLIS = 5 * 60_000L

// The account as the token says it, without a write when nothing changed, so concurrent first requests don't collide
private val UPSERT = """
    insert into accounts (id, subject, username, name, email, email_verified)
    values (gen_random_uuid(), ?, ?, ?, ?, ?)
    on conflict (subject) do update
        set username = excluded.username, name = excluded.name, email = excluded.email, email_verified = excluded.email_verified
        where (accounts.username, accounts.name, accounts.email, accounts.email_verified)
            is distinct from (excluded.username, excluded.name, excluded.email, excluded.email_verified)
""".trimIndent()

// To the person with its verified email, if it isn't linked and was never unlinked. Emails are unique among people.
private val LINK_BY_EMAIL = """
    update accounts a set person_id = p.id, linked_how = 'EMAIL', linked_at = now()
    from people p
    where a.subject = ? and a.person_id is null and a.email_links and a.email_verified
      and p.deleted_at is null and p.email = a.email
""".trimIndent()

private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

/**
 * Who's signed in, as a person: each Keycloak user's account and the person it's linked to. Comments, the history and
 * the tools that act as "you" ask here, so linking an account names everything it did before.
 */
@Component
class Accounts(
    private val accounts: AccountsRepository,
    private val people: PeopleRepository,
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {

    // A transaction of its own, also when it's asked within a read-only one
    private val ownTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    // What each sub's token said when this pod last wrote it down, and when
    private val seen = ConcurrentHashMap<String, Pair<List<Any?>, Long>>()

    /**
     * Writes down a signed-in user's account as their token says it, and links it by its verified email when it can.
     * Only when the token says something new, or a few minutes on, so most requests write nothing (AccountsFilter).
     */
    fun seen(jwt: Jwt) {
        val subject = jwt.subject ?: return
        val claims = listOf(
            jwt.getClaimAsString("preferred_username"),
            jwt.getClaimAsString("name"),
            jwt.getClaimAsString("email"),
            jwt.getClaimAsBoolean("email_verified") == true,
        )
        val now = System.currentTimeMillis()
        val last = seen[subject]
        if (last != null && last.first == claims && now - last.second < RECHECK_MILLIS) return
        ownTransaction.executeWithoutResult {
            jdbcTemplate.update(UPSERT, subject, claims[0], claims[1], claims[2], claims[3])
            jdbcTemplate.update(LINK_BY_EMAIL, subject)
        }
        seen[subject] = claims to now
    }

    /**
     * The person the signed-in user is: their account's; or, while it isn't linked, the person with their verified email,
     * unless someone unlinked it. None signed out.
     */
    fun signedInPerson(): Person? {
        val jwt = currentJwt() ?: return null
        val account = jwt.subject?.let(accounts::findBySubject)
        account?.personId?.let { return people.findByIdOrNull(it) }
        return if (account != null && !account.emailLinks) null else jwt.verifiedEmail?.let(people::findByEmail)
    }

    /** The signed-in user's account, once it has been written down */
    fun signedInAccount(): Account? = currentJwt()?.subject?.let(accounts::findBySubject)

    /** The people some subs are, by sub, for naming what they did; those not linked, or deleted since, are left out */
    fun people(subjects: Collection<String>): Map<String, Person> {
        val linked = if (subjects.isEmpty()) emptyList() else accounts.findBySubjectIn(subjects.toSet()).filter { it.personId != null }
        val byId = people.findAllById(linked.mapNotNull { it.personId }.toSet()).associateBy { it.id }
        return linked.mapNotNull { account -> byId[account.personId]?.let { account.subject to it } }.toMap()
    }

    /** What some subs go by, by sub: their person's name, or else their account's username */
    fun names(subjects: Collection<String>): Map<String, String> {
        val people = people(subjects)
        val labels = if (subjects.isEmpty()) emptyMap() else accounts.findBySubjectIn(subjects.toSet()).associate { it.subject to it.label }
        return subjects.toSet().mapNotNull { subject -> (people[subject]?.name ?: labels[subject])?.let { subject to it } }.toMap()
    }

    /** As MCP clients get them, with their people's names and who linked them, in a query each */
    fun dtos(of: List<Account>): List<AccountDto> {
        val persons = people.findAllById(of.mapNotNull { it.personId }.toSet()).associateBy { it.id }
        val linkers = names(of.mapNotNull { it.linkedBy })
        return of.map {
            AccountDto(
                it.id, it.subject, it.username, it.name, it.email, it.emailVerified, it.personId,
                it.personId?.let(persons::get)?.name, it.linkedHow, it.linkedAt, it.linkedBy?.let(linkers::get), it.createdAt,
            )
        }
    }

    fun of(people: Collection<Person>): Map<Person, List<Account>> {
        val byPerson = accounts.findByPersonIdIn(people.mapNotNull { it.id }).groupBy { it.personId }
        return people.associateWith { person -> byPerson[person.id].orEmpty().sortedBy { it.createdAt } }
    }

    /** All the accounts, or those not linked to anyone, the newest first */
    fun list(unlinked: Boolean): List<Account> =
        if (unlinked) accounts.findByPersonIdIsNullOrderByCreatedAtDesc() else accounts.findAllByOrderByCreatedAtDesc()

    /**
     * Links an account to a person, as [ACCOUNT_LINKER] only; one linked to someone else has to be unlinked first, so
     * nothing moves unseen
     */
    @Transactional
    fun link(accountId: UUID, personId: UUID): Account {
        requireLinker()
        val account = find(accountId)
        val person = requireNotNull(people.findByIdOrNull(personId)) { "Person $personId not found" }
        if (account.personId == person.id) return account
        account.personId?.let { linked ->
            val name = people.findByIdOrNull(linked)?.name ?: "someone else"
            throw IllegalArgumentException("Account ${account.label} is linked to $name; unlink it first")
        }
        account.personId = person.id
        account.linkedHow = AccountLink.MANUAL
        account.linkedBy = currentJwt()?.subject
        account.linkedAt = now()
        return account
    }

    /** Unlinks an account from its person, as [ACCOUNT_LINKER] only; its email doesn't link it again */
    @Transactional
    fun unlink(accountId: UUID): Account = find(accountId.also { requireLinker() }).apply {
        personId = null
        linkedHow = null
        linkedBy = null
        linkedAt = null
        emailLinks = false
    }

    /** Links the accounts with [person]'s verified email to them, e.g. once they get it, unless one was unlinked */
    @Transactional
    fun linkByEmail(person: Person) {
        val email = person.email ?: return
        jdbcTemplate.update(
            """
            update accounts set person_id = ?, linked_how = 'EMAIL', linked_at = now()
            where person_id is null and email_links and email_verified and email = ?
            """.trimIndent(),
            person.id, email,
        )
    }

    private fun find(id: UUID) = requireNotNull(accounts.findByIdOrNull(id)) { "Account $id not found" }
}

/**
 * Writes down the account of whoever a request comes from (Accounts.seen), once Spring Security knows who that is: an
 * approved user's, so a client's own service account never gets one.
 */
@Component
class AccountsFilter(private val accounts: Accounts) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        currentJwt()?.takeIf { APPROVED_ROLE in it.realmRoles }?.let { jwt ->
            // A request doesn't fail for want of its account
            runCatching { accounts.seen(jwt) }.onFailure { logger.warn("Couldn't write down the account of ${jwt.subject}", it) }
        }
        chain.doFilter(request, response)
    }
}
