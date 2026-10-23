package si.ogrodje.metaos

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The Keycloak users MetaOS has seen and the people they are (Accounts.kt). Only ACCOUNT_LINKER links and unlinks them
 * by hand; every link and unlink is in the person's history.
 */
@Component
class AccountsMCP(private val accounts: Accounts) {

    @McpTool(
        name = "list_accounts",
        annotations = McpTool.McpAnnotations(
            title = "List accounts",
            readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "List the Keycloak users MetaOS has seen, the newest first, with the person each is and how it was " +
            "linked: by its verified email or by someone. Optionally only those not linked to anyone yet"
    )
    fun list(
        @McpToolParam(description = "Only those not linked to a person", required = false) unlinked: Boolean? = null,
    ): List<AccountDto> = accounts.dtos(accounts.list(unlinked == true))

    @Transactional
    @McpTool(
        name = "link_account",
        annotations = McpTool.McpAnnotations(
            title = "Link account",
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false
        ),
        description = "Link an account to the person it is, so their comments and changes go by that person's name, also " +
            "those made before. An account linked to someone else has to be unlinked first (unlink_account). Only " +
            "$ACCOUNT_LINKER may"
    )
    fun link(
        @McpToolParam(description = "Account ID, from list_accounts") accountId: UUID,
        @McpToolParam(description = "Person ID") personId: UUID,
    ): AccountDto = accounts.dtos(listOf(accounts.link(accountId, personId))).single()

    @Transactional
    @McpTool(
        name = "unlink_account",
        annotations = McpTool.McpAnnotations(
            title = "Unlink account",
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false
        ),
        description = "Unlink an account from its person, e.g. one linked to the wrong person. Its email doesn't link it " +
            "again; link_account does. Only $ACCOUNT_LINKER may"
    )
    fun unlink(@McpToolParam(description = "Account ID, from list_accounts") accountId: UUID): AccountDto =
        accounts.dtos(listOf(accounts.unlink(accountId))).single()
}
