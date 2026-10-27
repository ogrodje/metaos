# MetaOS's client scopes. Each puts a MetaOS URL into the token's audience, which MetaOS checks, and Keycloak puts one in
# a token only when its user has metaos-user (the role scope mappings), so an unapproved user, or a client's own
# service account, gets none of them.
locals {
  scopes = {
    "mcp:tools" = {
      description         = "Access to the MetaOS MCP server tools"
      consent_screen_text = "Use MetaOS tools on your behalf"
      consent_screen      = true
      mapper              = "metaos-mcp-audience"
      audience            = var.metaos_mcp_url
    }
    "metaos:read" = {
      description         = "Read MetaOS through its GraphQL API"
      consent_screen_text = null
      consent_screen      = false
      mapper              = "metaos-api-audience"
      audience            = var.metaos_api_url
    }
    "metaos:write" = {
      description         = "Change MetaOS through its GraphQL API"
      consent_screen_text = null
      consent_screen      = false
      mapper              = "metaos-api-audience"
      audience            = var.metaos_api_url
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
  role_id         = keycloak_role.metaos_user.id
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
