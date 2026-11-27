# Elysium (infra/elysium), Ogrodje's software factory, whose agents use MetaOS's MCP tools as a service account of their
# own, beside MetaOS's agents' (agents.tf): a confidential client with client credentials, whose service account is
# staff (metaos-user), so its tokens get mcp:tools. What it changes is in MetaOS' history as elysium (below), through
# the client elysium (azp), and it can be turned off without touching anything else.
#
# Its tokens are the Elysium bridge's (elysium/linear-bridge), which asks for a new one when the last is about to run
# out and puts it on every request it passes on to MetaOS' MCP server for the agents: so no agent holds one, and none
# runs out under a long conversation. Its secret: elysium_client_secret (outputs.tf), ELYSIUM_METAOS_CLIENT_SECRET.
resource "keycloak_openid_client" "elysium" {
  realm_id    = keycloak_realm.metaos.id
  client_id   = "elysium"
  name        = "Elysium"
  description = "Ogrodje's software factory (infra/elysium): its agents, using MetaOS's MCP tools as its own service account"
  enabled     = true

  access_type                  = "CONFIDENTIAL"
  standard_flow_enabled        = false
  implicit_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = true
  full_scope_allowed           = true
}

resource "keycloak_openid_client_default_scopes" "elysium" {
  realm_id  = keycloak_realm.metaos.id
  client_id = keycloak_openid_client.elysium.id
  # Not profile, whose preferred_username would be Keycloak's service-account-elysium: its own, below
  default_scopes = [
    "basic", "roles",
    keycloak_openid_client_scope.metaos["mcp:tools"].name,
  ]
}

# Who it is to MetaOS, which writes down preferred_username in its history and an account's name (Audit.kt,
# Accounts.kt), and knows it by its ID, sub, which stays the service account's
resource "keycloak_openid_hardcoded_claim_protocol_mapper" "elysium" {
  for_each = { preferred_username = "elysium", name = "Elysium" }

  realm_id    = keycloak_realm.metaos.id
  client_id   = keycloak_openid_client.elysium.id
  name        = each.key
  claim_name  = each.key
  claim_value = each.value

  claim_value_type    = "String"
  add_to_access_token = true
  add_to_id_token     = false
  add_to_userinfo     = false
}

resource "keycloak_openid_client_optional_scopes" "elysium" {
  realm_id        = keycloak_realm.metaos.id
  client_id       = keycloak_openid_client.elysium.id
  optional_scopes = []
}

# Approved, as a user would be by hand: without it neither Keycloak (scopes.tf) nor MetaOS lets its tokens in
resource "keycloak_openid_client_service_account_realm_role" "elysium" {
  realm_id                = keycloak_realm.metaos.id
  service_account_user_id = keycloak_openid_client.elysium.service_account_user_id
  role                    = keycloak_role.metaos_user.name
}
