package si.ogrodje.metaos

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.UUID

// Accounts are the team's (Accounts.kt): who signs in, and as whom. Lists of people and accounts have them, so they're
// batched.
@Controller
class AccountsGraphQL(private val accounts: Accounts, private val people: PeopleRepository) {

    @QueryMapping("accounts")
    @PreAuthorize(SIGNED_IN)
    fun list(@Argument unlinked: Boolean): List<Account> = accounts.list(unlinked)

    @BatchMapping(typeName = "Person", field = "accounts")
    fun personAccounts(people: List<Person>): Map<Person, List<Account>> =
        if (signedIn()) accounts.of(people) else people.associateWith { emptyList() }

    // Those not linked are left out, and so null
    @BatchMapping(typeName = "Account", field = "person")
    fun persons(of: List<Account>): Map<Account, Person> {
        val byId = people.findAllById(of.mapNotNull { it.personId }.toSet()).associateBy { it.id }
        return of.mapNotNull { account -> account.personId?.let(byId::get)?.let { account to it } }.toMap()
    }

    @BatchMapping(typeName = "Account", field = "linkedBy")
    fun linkedBy(of: List<Account>): Map<Account, String> {
        val names = accounts.names(of.mapNotNull { it.linkedBy })
        return of.mapNotNull { account -> account.linkedBy?.let(names::get)?.let { account to it } }.toMap()
    }

    @MutationMapping
    @PreAuthorize(WRITES)
    fun linkAccount(@Argument id: UUID, @Argument person: UUID): Account = accounts.link(id, person)

    @MutationMapping
    @PreAuthorize(WRITES)
    fun unlinkAccount(@Argument id: UUID): Account = accounts.unlink(id)
}
