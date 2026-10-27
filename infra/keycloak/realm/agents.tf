# The agents (agents/), which use MetaOS's MCP tools as MetaOS's own service account rather than a user's: a
# confidential client with client credentials, whose service account is approved (metaos-user), so its tokens get
# mcp:tools. What they change is in the history as service-account-metaos-agents, through metaos-agents (azp).
resource "keycloak_openid_client" "metaos_agents" {
  realm_id    = keycloak_realm.metaos.id
  client_id   = "metaos-agents"
  name        = "MetaOS agents"
  description = "The agents of MetaOS's workflows (agents/), using its MCP tools as its own service account"
  enabled     = true

  access_type                  = "CONFIDENTIAL"
  standard_flow_enabled        = false
  implicit_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = true
  full_scope_allowed           = true

  # An agent's run uses one token throughout, and may take longer than the realm's 5 minutes
  access_token_lifespan = "1800"
}

resource "keycloak_openid_client_default_scopes" "metaos_agents" {
  realm_id  = keycloak_realm.metaos.id
  client_id = keycloak_openid_client.metaos_agents.id
  default_scopes = [
    "basic", "profile", "roles",
    keycloak_openid_client_scope.metaos["mcp:tools"].name,
  ]
}

resource "keycloak_openid_client_optional_scopes" "metaos_agents" {
  realm_id        = keycloak_realm.metaos.id
  client_id       = keycloak_openid_client.metaos_agents.id
  optional_scopes = []
}

# Approved, as a user would be by hand: without it neither Keycloak (scopes.tf) nor MetaOS lets its tokens in
resource "keycloak_openid_client_service_account_realm_role" "metaos_agents" {
  realm_id                = keycloak_realm.metaos.id
  service_account_user_id = keycloak_openid_client.metaos_agents.service_account_user_id
  role                    = keycloak_role.metaos_user.name
}
