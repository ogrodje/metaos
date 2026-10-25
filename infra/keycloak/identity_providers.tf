# Sign in with Google and Discord. Anyone with either can sign in, so each provider's post-login flow denies its logins
# until an admin grants the user metaos-user (approval_gate below).
#
# Google only hands out addresses it has verified, so its emails are trusted, and an approved user's account is linked to
# their person by email right away (Accounts.kt). Discord also returns unverified ones, so its aren't: approving someone
# from Discord = granting the role and turning on "Email verified" once the address is known to be theirs.

resource "keycloak_oidc_google_identity_provider" "google" {
  count = var.google == null ? 0 : 1

  realm                        = keycloak_realm.metaos.id
  alias                        = "google"
  display_name                 = "Google"
  enabled                      = true
  client_id                    = var.google.client_id
  client_secret                = coalesce(var.google_client_secret, local.kept_secret)
  default_scopes               = "openid profile email"
  trust_email                  = true
  store_token                  = false
  sync_mode                    = "IMPORT"
  post_broker_login_flow_alias = keycloak_authentication_flow.post_login["google"].alias
}

# Keycloak has no Discord provider, so this is its generic OAuth v2 one, against Discord's /users/@me
resource "keycloak_oidc_identity_provider" "discord" {
  count = var.discord == null ? 0 : 1

  realm                        = keycloak_realm.metaos.id
  alias                        = "discord"
  display_name                 = "Discord"
  provider_id                  = "oauth2"
  enabled                      = true
  authorization_url            = "https://discord.com/oauth2/authorize"
  token_url                    = "https://discord.com/api/oauth2/token"
  user_info_url                = "https://discord.com/api/users/@me"
  client_id                    = var.discord.client_id
  client_secret                = coalesce(var.discord_client_secret, local.kept_secret)
  default_scopes               = "identify email"
  trust_email                  = false
  store_token                  = false
  backchannel_supported        = false
  sync_mode                    = "IMPORT"
  post_broker_login_flow_alias = keycloak_authentication_flow.post_login["discord"].alias
  extra_config = {
    clientAuthMethod = "client_secret_post"
    userIDClaim      = "id"
    userNameClaim    = "username"
    fullNameClaim    = "global_name"
    emailClaim       = "email"
  }
}

# The approval gate: a provider's post-login flow, whose conditional subflow denies access to a user without metaos-user.
# Without a step that succeeds, a flow whose condition didn't match would fail as "Invalid username or password", hence
# Allow access after it.
locals {
  gated = {
    for alias, provider in { google = var.google, discord = var.discord } :
    alias => title(alias) if provider != null
  }
}

resource "keycloak_authentication_flow" "post_login" {
  for_each = local.gated

  realm_id    = keycloak_realm.metaos.id
  alias       = "${each.key}-post-login"
  provider_id = "basic-flow"
  description = "Denies ${each.value} logins without the ${keycloak_role.metaos_user.name} role"
}

resource "keycloak_authentication_subflow" "require_role" {
  for_each = local.gated

  realm_id          = keycloak_realm.metaos.id
  parent_flow_alias = keycloak_authentication_flow.post_login[each.key].alias
  alias             = "${each.key}-require-role"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 0
}

resource "keycloak_authentication_execution" "without_role" {
  for_each = local.gated

  realm_id          = keycloak_realm.metaos.id
  parent_flow_alias = keycloak_authentication_subflow.require_role[each.key].alias
  authenticator     = "conditional-user-role"
  requirement       = "REQUIRED"
  priority          = 0
}

resource "keycloak_authentication_execution_config" "without_role" {
  for_each = local.gated

  realm_id     = keycloak_realm.metaos.id
  execution_id = keycloak_authentication_execution.without_role[each.key].id
  # Config aliases are per realm, hence the provider's
  alias = "${each.key}-without-${keycloak_role.metaos_user.name}"
  config = {
    condUserRole = keycloak_role.metaos_user.name
    negate       = "true"
  }
}

resource "keycloak_authentication_execution" "deny" {
  for_each = local.gated

  realm_id          = keycloak_realm.metaos.id
  parent_flow_alias = keycloak_authentication_subflow.require_role[each.key].alias
  authenticator     = "deny-access-authenticator"
  requirement       = "REQUIRED"
  priority          = 1

  depends_on = [keycloak_authentication_execution.without_role]
}

resource "keycloak_authentication_execution_config" "deny" {
  for_each = local.gated

  realm_id     = keycloak_realm.metaos.id
  execution_id = keycloak_authentication_execution.deny[each.key].id
  alias        = "${each.key}-awaiting-approval"
  config = {
    denyErrorMessage = "Your account is waiting for approval by a MetaOS admin."
  }
}

resource "keycloak_authentication_execution" "allow" {
  for_each = local.gated

  realm_id          = keycloak_realm.metaos.id
  parent_flow_alias = keycloak_authentication_flow.post_login[each.key].alias
  authenticator     = "allow-access-authenticator"
  requirement       = "REQUIRED"
  priority          = 1

  depends_on = [keycloak_authentication_subflow.require_role]
}
