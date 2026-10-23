package si.ogrodje.metaos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
class AccountsTest @Autowired constructor(
    private val accounts: Accounts,
    private val accountsMCP: AccountsMCP,
    private val peopleMCP: PeopleMCP,
    private val episodesMCP: EpisodesMCP,
    private val commentsMCP: CommentsMCP,
    private val auditMCP: AuditMCP,
    private val history: History,
) {

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    // Signed in, as a request through AccountsFilter would be: the account written down as the token says it
    private fun signInAs(username: String, email: String? = null, verified: Boolean = true, name: String? = null): Jwt {
        val jwt = Jwt.withTokenValue("token").header("alg", "none")
            .subject("$username-id").claim("preferred_username", username).claim("azp", "claude-code")
            .apply { if (email != null) claim("email", email).claim("email_verified", verified) }
            .apply { if (name != null) claim("name", name) }
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        accounts.seen(jwt)
        return jwt
    }

    // The one user who links accounts by hand
    private fun signInAsLinker() = signInAs("acc-oto", email = ACCOUNT_LINKER)

    private fun account(username: String) = accountsMCP.list().single { it.subject == "$username-id" }

    @Test
    fun `writes down an account and links it by its verified email`() {
        val person = signInAsLinker().let { peopleMCP.create(name = "Nina Horvat", email = "nina.accounts@example.com") }

        signInAs("acc-nina", email = "nina.accounts@example.com", name = "Nina H.")

        val account = account("acc-nina")
        assertEquals(listOf("acc-nina", "Nina H.", "nina.accounts@example.com", true), listOf(account.username, account.name, account.email, account.emailVerified))
        assertEquals(person.id, account.personId)
        assertEquals("Nina Horvat", account.person)
        assertEquals(AccountLink.EMAIL, account.linkedHow)
        assertNull(account.linkedBy)
        assertEquals("Nina Horvat", peopleMCP.whoami().person?.name)
        assertEquals(account.id, peopleMCP.whoami().account?.id)
    }

    @Test
    fun `doesn't link by an unverified email, as Discord may send one`() {
        signInAsLinker()
        peopleMCP.create(name = "Luka Zupan", email = "luka.accounts@example.com")

        signInAs("acc-luka", email = "luka.accounts@example.com", verified = false)

        assertNull(account("acc-luka").personId)
        assertNull(accounts.signedInPerson())
    }

    @Test
    fun `links by hand, never moves an account silently, and unlinks it for good`() {
        signInAsLinker()
        val ana = peopleMCP.create(name = "Ana Kos", email = null)
        val bojan = peopleMCP.create(name = "Bojan Kos", email = null)
        val id = checkNotNull(signInAs("acc-ana").let { account("acc-ana").id })

        signInAsLinker()
        val linked = accountsMCP.link(id, ana.id!!)
        assertEquals(listOf(AccountLink.MANUAL, "acc-oto"), listOf(linked.linkedHow, linked.linkedBy))
        // Again changes nothing; to someone else it has to be unlinked first
        assertTrue(linked.linkedAt!!.isEqual(accountsMCP.link(id, ana.id).linkedAt))
        val error = assertFailsWith<IllegalArgumentException> { accountsMCP.link(id, bojan.id!!) }
        assertEquals("Account acc-ana is linked to Ana Kos; unlink it first", error.message)

        accountsMCP.unlink(id)
        // Its email doesn't link it again, nor does a person getting that email
        peopleMCP.setEmail(ana.id, "ana.accounts@example.com")
        signInAs("acc-ana", email = "ana.accounts@example.com")
        assertNull(account("acc-ana").personId)
        assertNull(accounts.signedInPerson())
    }

    @Test
    fun `a person getting an email links the accounts that sign in with it`() {
        signInAs("acc-maja", email = "maja.accounts@example.com")
        assertNull(account("acc-maja").personId)

        signInAsLinker()
        val maja = peopleMCP.create(name = "Maja Kovač", email = null)
        peopleMCP.setEmail(maja.id!!, "Maja.Accounts@example.com")

        assertEquals(listOf<Any?>(maja.id, AccountLink.EMAIL), account("acc-maja").let { listOf(it.personId, it.linkedHow) })
    }

    @Test
    fun `a linked account wins over the email in the token`() {
        signInAsLinker()
        val oto = peopleMCP.create(name = "Oto Brglez", email = "dev.accounts@example.com")
        val other = peopleMCP.create(name = "Someone Else", email = "google.accounts@example.com")
        signInAs("acc-dev", email = "dev.accounts@example.com")
        assertEquals(oto.id, account("acc-dev").personId)

        // The same Keycloak user, signing in with an email that's someone else's
        signInAs("acc-dev", email = "google.accounts@example.com")

        assertEquals(oto.id, accounts.signedInPerson()?.id)
        assertEquals(oto.id, account("acc-dev").personId)
        assertTrue(other.id != oto.id)
    }

    @Test
    fun `names comments and history by the person, also what was done before the link`() {
        signInAs("acc-cene")
        episodesMCP.create(code = "acc-101", title = "Named later")
        commentsMCP.commentOnEpisode("acc-101", "Before I was linked")
        val episodeId = checkNotNull(episodesMCP.get("acc-101")?.id)
        assertEquals("acc-cene", commentsMCP.listEpisodeComments("acc-101").single().author)
        assertEquals("acc-cene", history.of(episodeId).last().actor)

        signInAsLinker()
        val cene = peopleMCP.create(name = "Cene Novak", email = null)
        accountsMCP.link(account("acc-cene").id!!, cene.id!!)

        assertEquals("Cene Novak", commentsMCP.listEpisodeComments("acc-101").single().author)
        assertEquals(cene.id, commentsMCP.listEpisodeComments("acc-101").single().personId)
        assertEquals(listOf("Cene Novak", "Cene Novak"), history.of(episodeId).map { it.actor })
        // The person's history has the link, as does get_history
        assertEquals("Linked acc-cene to Cene Novak", history.of(cene.id).last().summary)
        assertTrue(auditMCP.history(cene.id).any { it.table == "accounts" && it.tool == "link_account" })
    }

    @Test
    fun `only ACCOUNT_LINKER links accounts by hand, and only with that email verified`() {
        signInAsLinker()
        val person = peopleMCP.create(name = "Tina Kralj", email = null)
        val id = checkNotNull(signInAs("acc-tina").let { account("acc-tina").id })

        signInAs("acc-bojan", email = "bojan.accounts@example.com")
        assertFailsWith<AccessDeniedException> { accountsMCP.link(id, person.id!!) }
        signInAs("acc-fake", email = ACCOUNT_LINKER, verified = false)
        assertFailsWith<AccessDeniedException> { accountsMCP.link(id, person.id!!) }
        assertEquals(false, peopleMCP.whoami().canLinkAccounts)

        signInAsLinker()
        assertEquals(true, peopleMCP.whoami().canLinkAccounts)
        accountsMCP.link(id, person.id!!)
        signInAs("acc-bojan", email = "bojan.accounts@example.com")
        assertFailsWith<AccessDeniedException> { accountsMCP.unlink(id) }
        assertEquals(person.id, account("acc-tina").personId)
    }

    @Test
    fun `lists the accounts nobody is linked to`() {
        signInAs("acc-unlinked")

        assertTrue(accountsMCP.list(unlinked = true).any { it.subject == "acc-unlinked-id" })
        assertTrue(accountsMCP.list(unlinked = true).all { it.personId == null })
    }
}
