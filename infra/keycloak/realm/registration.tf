# What MCP clients registering themselves (Dynamic Client Registration) may ask for: Keycloak's default policies, with
# the trusted hosts MetaOS's clients redirect to. Whoever registers still has to sign in as an approved user to get a
# token MetaOS takes.
locals {
  allowed_protocol_mapper_types = [
    "oidc-full-name-mapper",
    "oidc-sha256-pairwise-sub-mapper",
    "oidc-address-mapper",
    "oidc-usermodel-property-mapper",
    "oidc-usermodel-attribute-mapper",
    "saml-user-property-mapper",
    "saml-user-attribute-mapper",
    "saml-role-list-mapper",
  ]

  registration_policies = {
    anonymous_trusted_hosts = {
      sub_type    = "anonymous"
      name        = "Trusted Hosts"
      provider_id = "trusted-hosts"
      config = {
        "trusted-hosts"                                = join(",", var.trusted_hosts)
        "host-sending-registration-request-must-match" = "false"
        "client-uris-must-match"                       = "true"
      }
    }
    anonymous_consent_required = {
      sub_type    = "anonymous"
      name        = "Consent Required"
      provider_id = "consent-required"
      config      = {}
    }
    anonymous_full_scope_disabled = {
      sub_type    = "anonymous"
      name        = "Full Scope Disabled"
      provider_id = "scope"
      config      = {}
    }
    anonymous_max_clients = {
      sub_type    = "anonymous"
      name        = "Max Clients Limit"
      provider_id = "max-clients"
      config      = { "max-clients" = "200" }
    }
    anonymous_client_scopes = {
      sub_type    = "anonymous"
      name        = "Allowed Client Scopes"
      provider_id = "allowed-client-templates"
      config      = { "allow-default-scopes" = "true" }
    }
    anonymous_protocol_mappers = {
      sub_type    = "anonymous"
      name        = "Allowed Protocol Mapper Types"
      provider_id = "allowed-protocol-mappers"
      config      = { "allowed-protocol-mapper-types" = join(",", local.allowed_protocol_mapper_types) }
    }
    anonymous_web_origins = {
      sub_type    = "anonymous"
      name        = "Allowed Registration Web Origins"
      provider_id = "registration-web-origins"
      config      = {}
    }
    authenticated_client_scopes = {
      sub_type    = "authenticated"
      name        = "Allowed Client Scopes"
      provider_id = "allowed-client-templates"
      config      = { "allow-default-scopes" = "true" }
    }
    authenticated_protocol_mappers = {
      sub_type    = "authenticated"
      name        = "Allowed Protocol Mapper Types"
      provider_id = "allowed-protocol-mappers"
      config      = { "allowed-protocol-mapper-types" = join(",", local.allowed_protocol_mapper_types) }
    }
    authenticated_web_origins = {
      sub_type    = "authenticated"
      name        = "Allowed Registration Web Origins"
      provider_id = "registration-web-origins"
      config      = {}
    }
  }
}

resource "keycloak_realm_client_registration_policy" "metaos" {
  for_each = local.registration_policies

  realm_id    = keycloak_realm.metaos.id
  sub_type    = each.value.sub_type
  name        = each.value.name
  provider_id = each.value.provider_id
  config      = each.value.config
}
