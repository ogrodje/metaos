# Sign in with Google and Discord. Anyone with either can sign in, and is a member then (metaos-member, a default role,
# realm.tf), once their email is verified; staff are those an admin grants metaos-user.
#
# Google only hands out addresses it has verified, so its emails are trusted, and a member's account is linked to their
# person by email right away (Accounts.kt). Discord also returns unverified ones, so its aren't: Keycloak mails the
# address a link, which the user follows before their sign-in goes through (verify_email, realm.tf), and has them give
# one when Discord has none (the email is required, user_profile.tf).
#
# "Email verified" decides who a user is in MetaOS: their account is linked to the person with that address, and the
# address ACCOUNT_LINKER (Accounts.kt) is the one that links accounts by hand. So it's on only for an address the user
# has shown is theirs, by following Keycloak's link or by signing in with Google as it. An admin who changes a user's
# email (user_profile.tf: only admins can) turns it off, as Keycloak keeps it on, unless the new address is known to be
# theirs too.

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

# A provider's post-login flow, which remembers the sign-in (below). Without a step that succeeds, the flow would fail as
# "Invalid username or password", hence Allow access first. It denied users without metaos-user until members came.
locals {
  post_login = {
    for alias, provider in { google = var.google, discord = var.discord } :
    alias => title(alias) if provider != null
  }
}

resource "keycloak_authentication_flow" "post_login" {
  for_each = local.post_login

  realm_id    = keycloak_realm.metaos.id
  alias       = "${each.key}-post-login"
  provider_id = "basic-flow"
  description = "Remembers ${each.value} logins"
}

resource "keycloak_authentication_execution" "allow" {
  for_each = local.post_login

  realm_id          = keycloak_realm.metaos.id
  parent_flow_alias = keycloak_authentication_flow.post_login[each.key].alias
  authenticator     = "allow-access-authenticator"
  requirement       = "REQUIRED"
  priority          = 1
}

# Last, MetaOS's step, which remembers the sign-in while the realm has Remember me on (realm.tf). Keycloak has it from
# MetaOS's image (docker/keycloak.Dockerfile, keycloak/remember-me); one without it refuses to create the step.
resource "keycloak_authentication_execution" "remember" {
  for_each = local.post_login

  realm_id          = keycloak_realm.metaos.id
  parent_flow_alias = keycloak_authentication_flow.post_login[each.key].alias
  authenticator     = "metaos-remember-me"
  requirement       = "REQUIRED"
  priority          = 2

  depends_on = [keycloak_authentication_execution.allow]
}
