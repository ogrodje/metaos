locals {
  ui_urls = concat([var.metaos_ui_url], var.metaos_ui_other_urls, var.metaos_other_urls)
}

# The front-end (fe/), signing users in with keycloak-js. Public, as a browser can't keep a secret, so with PKCE.
resource "keycloak_openid_client" "metaos_ui" {
  realm_id    = keycloak_realm.metaos.id
  client_id   = "metaos-ui"
  name        = "MetaOS"
  description = "The front-end (fe/), signing users in with keycloak-js"
  enabled     = true

  access_type                  = "PUBLIC"
  standard_flow_enabled        = true
  implicit_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = false
  pkce_code_challenge_method   = "S256"
  use_refresh_tokens           = true
  full_scope_allowed           = true

  backchannel_logout_session_required = false

  valid_redirect_uris             = [for url in local.ui_urls : "${url}/*"]
  valid_post_logout_redirect_uris = ["+"]
  web_origins                     = local.ui_urls
}

# Its tokens carry metaos:account, metaos:read and metaos:write, which let them into /graphql (scopes.tf), every user's,
# a member's too: MetaOS gives only staff (metaos-user) the team's data and changes. mcp:tools is no business of its
resource "keycloak_openid_client_default_scopes" "metaos_ui" {
  realm_id  = keycloak_realm.metaos.id
  client_id = keycloak_openid_client.metaos_ui.id
  default_scopes = [
    "basic", "acr", "web-origins", "profile", "email", "roles",
    keycloak_openid_client_scope.metaos["metaos:account"].name,
    keycloak_openid_client_scope.metaos["metaos:read"].name,
    keycloak_openid_client_scope.metaos["metaos:write"].name,
  ]
}

resource "keycloak_openid_client_optional_scopes" "metaos_ui" {
  realm_id        = keycloak_realm.metaos.id
  client_id       = keycloak_openid_client.metaos_ui.id
  optional_scopes = []
}
