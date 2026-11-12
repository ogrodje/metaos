# MetaOS's client scopes. Each puts a MetaOS URL into the token's audience, which MetaOS checks, and Keycloak puts one in
# a token only when its user has the scope's role (the role scope mappings): metaos-user, staff, for all but
# metaos:account, which every member has, so that /graphql takes a member's token, e.g. for `me`. So a member gets
# neither MCP nor the team's data nor changes, and a client's own service account none of them (no client it may
# register has metaos:account).
locals {
  scopes = {
    "mcp:tools" = {
      description         = "Access to the MetaOS MCP server tools"
      consent_screen_text = "Use MetaOS tools on your behalf"
      consent_screen      = true
      mapper              = "metaos-mcp-audience"
      audience            = var.metaos_mcp_url
      role                = keycloak_role.metaos_user.id
    }
    "metaos:read" = {
      description         = "Read MetaOS through its GraphQL API"
      consent_screen_text = null
      consent_screen      = false
      mapper              = "metaos-api-audience"
      audience            = var.metaos_api_url
      role                = keycloak_role.metaos_user.id
    }
    "metaos:account" = {
      description         = "Sign in to MetaOS through its GraphQL API, as a member"
      consent_screen_text = null
      consent_screen      = false
      mapper              = "metaos-api-audience"
      audience            = var.metaos_api_url
      role                = keycloak_role.metaos_member.id
    }
    "metaos:write" = {
      description         = "Change MetaOS through its GraphQL API"
      consent_screen_text = null
      consent_screen      = false
      mapper              = "metaos-api-audience"
      audience            = var.metaos_api_url
      role                = keycloak_role.metaos_user.id
    }
  }
}

resource "keycloak_openid_client_scope" "metaos" {
  for_each = local.scopes

  realm_id               = keycloak_realm.metaos.id
  name                   = each.key
  description            = each.value.description
  include_in_token_scope = true
  consent_screen_text    = each.value.consent_screen_text
}

resource "keycloak_openid_audience_protocol_mapper" "metaos" {
  for_each = local.scopes

  realm_id                   = keycloak_realm.metaos.id
  client_scope_id            = keycloak_openid_client_scope.metaos[each.key].id
  name                       = each.value.mapper
  included_custom_audience   = each.value.audience
  add_to_access_token        = true
  add_to_token_introspection = true
  add_to_id_token            = false
}

resource "keycloak_generic_role_mapper" "metaos_scope_needs_approval" {
  for_each = local.scopes

  realm_id        = keycloak_realm.metaos.id
  client_scope_id = keycloak_openid_client_scope.metaos[each.key].id
  role_id         = each.value.role
}

# MCP clients registering themselves get mcp:tools when they ask for it. metaos:read and metaos:write are metaos-ui's
# own, not realm defaults, as a client that registers itself may ask for any realm default.
resource "keycloak_realm_optional_client_scopes" "metaos" {
  realm_id = keycloak_realm.metaos.id
  optional_scopes = [
    "address", "phone", "offline_access", "microprofile-jwt", "organization",
    keycloak_openid_client_scope.metaos["mcp:tools"].name,
  ]
}
