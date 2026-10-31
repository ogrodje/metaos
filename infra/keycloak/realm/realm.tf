# Keycloak never gives secrets back, and takes this in their place as "keep the one you have": a secret that isn't set is
# left as it is, and one that is set replaces it. A new realm or sign-in provider needs its real secrets.
locals {
  kept_secret = "**********"
}

resource "keycloak_realm" "metaos" {
  realm        = "metaos"
  display_name = "Ogrodje MetaOS"
  enabled      = true
  ssl_required = "external"

  # Users come from Discord and Google, and wait for approval (identity_providers.tf)
  registration_allowed     = false
  login_with_email_allowed = true
  duplicate_emails_allowed = false
  reset_password_allowed   = false
  # Usernames come from Discord, or are Google's addresses. Without this, nobody renames a user, not even an admin in the
  # admin console; with it, users also rename themselves in the Account Console. MetaOS knows them by their ID (`sub`)
  edit_username_allowed = true
  # Wherever Keycloak can send mail: a user whose provider doesn't vouch for their address, as Discord doesn't, confirms it
  verify_email = var.smtp != null

  # A sign-in lasts 7 days without use and 30 at most, and refresh tokens as long (keycloak/sessions.sh had the why)
  sso_session_idle_timeout = var.sso_session_idle_timeout
  sso_session_max_lifespan = var.sso_session_max_lifespan
  access_token_lifespan    = var.access_token_lifespan

  # Sign-ins through Discord and Google are remembered, as with Remember me on the login form, which they never see (the
  # last step of their post-login flows, identity_providers.tf), so Keycloak's sign-in cookie outlives the browser: 30
  # days without use and 90 at most. Off, the step does nothing, and users sign in again whenever their browser restarts.
  remember_me                          = true
  sso_session_idle_timeout_remember_me = var.sso_session_idle_timeout_remember_me
  sso_session_max_lifespan_remember_me = var.sso_session_max_lifespan_remember_me

  default_signature_algorithm = "RS256"

  # Keycloak's own, said here as the realm resource would otherwise turn brute-force protection off and drop the headers.
  # The front-end's silent sign-in iframe needs frame-src and frame-ancestors 'self'.
  security_defenses {
    brute_force_detection {
      brute_force_strategy             = "MULTIPLE"
      max_login_failures               = 30
      wait_increment_seconds           = 60
      max_failure_wait_seconds         = 900
      failure_reset_time_seconds       = 43200
      minimum_quick_login_wait_seconds = 60
      quick_login_check_milli_seconds  = 1000
      permanent_lockout                = false
      max_temporary_lockouts           = 0
    }
    headers {
      x_frame_options                     = "SAMEORIGIN"
      content_security_policy             = "frame-src 'self'; frame-ancestors 'self'; object-src 'none';"
      content_security_policy_report_only = ""
      x_content_type_options              = "nosniff"
      x_robots_tag                        = "none"
      referrer_policy                     = "no-referrer"
      strict_transport_security           = "max-age=31536000; includeSubDomains"
      # Not sent: browsers have dropped the XSS filter it turned on
      x_xss_protection = ""
    }
  }

  dynamic "smtp_server" {
    for_each = var.smtp[*]
    content {
      host                  = smtp_server.value.host
      port                  = smtp_server.value.port
      starttls              = smtp_server.value.starttls
      from                  = smtp_server.value.from
      from_display_name     = smtp_server.value.from_display_name
      reply_to              = smtp_server.value.reply_to
      reply_to_display_name = smtp_server.value.reply_to_display_name
      allow_utf8            = true
      auth {
        username = smtp_server.value.username
        password = coalesce(var.smtp_password, local.kept_secret)
      }
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

# Approved users have it: MetaOS requires it on /mcp, /graphql's team data and changes, and the thumbnails (Security.kt),
# and the sign-in providers' post-login flows let no one in without it. Granted by hand in the admin console.
resource "keycloak_role" "metaos_user" {
  realm_id    = keycloak_realm.metaos.id
  name        = "metaos-user"
  description = "Approved: may use MetaOS. MetaOS itself requires it on /mcp, /graphql's team data and the thumbnails"
}

resource "keycloak_user" "dev" {
  count = var.dev_user ? 1 : 0

  realm_id       = keycloak_realm.metaos.id
  username       = "dev"
  email          = "dev@metaos.local"
  email_verified = true
  first_name     = "Dev"
  last_name      = "User"
  enabled        = true

  initial_password {
    value     = "dev"
    temporary = false
  }
}

resource "keycloak_user_roles" "dev" {
  count = var.dev_user ? 1 : 0

  realm_id = keycloak_realm.metaos.id
  user_id  = keycloak_user.dev[0].id
  role_ids = [keycloak_role.metaos_user.id]
  # Keeps default-roles-metaos, which every user has
  exhaustive = false
}
