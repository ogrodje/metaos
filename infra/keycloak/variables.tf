variable "keycloak_url" {
  description = "Where Keycloak is, e.g. https://metaos-auth.ogrodje.si"
  type        = string
}

variable "keycloak_admin_username" {
  description = "An admin of realm master (KEYCLOAK_ADMIN_USERNAME)"
  type        = string
  default     = "admin"
}

variable "keycloak_admin_password" {
  description = "Its password (KEYCLOAK_ADMIN_PASSWORD)"
  type        = string
  sensitive   = true
}

variable "metaos_mcp_url" {
  description = "Public URL of /mcp, the audience of mcp:tools tokens (METAOS_MCP_URL)"
  type        = string
}

variable "metaos_api_url" {
  description = "Public URL of /graphql, the audience of metaos:read and metaos:write tokens (METAOS_API_URL)"
  type        = string
}

variable "metaos_ui_url" {
  description = "Where the front-end is, for metaos-ui's redirect URIs (METAOS_UI_URL)"
  type        = string
}

variable "trusted_hosts" {
  description = "Hosts MCP clients may register themselves for (DCR), by their redirect URIs"
  type        = list(string)
  default     = ["localhost", "127.0.0.1", "claude.ai", "claude.com", "chatgpt.com"]
}

variable "sso_session_idle_timeout" {
  description = "A sign-in ends after this long without use"
  type        = string
  default     = "168h"
}

variable "sso_session_max_lifespan" {
  description = "A sign-in ends after this long in any case"
  type        = string
  default     = "720h"
}

variable "access_token_lifespan" {
  description = "How long an access token lasts, and so how long a removed user keeps access"
  type        = string
  default     = "5m"
}

# Sign-in providers, each there when its client ID is set. Secrets left unset keep what Keycloak has (realm.tf).

variable "google" {
  description = "Google's \"Web application\" OAuth client \"Keycloak\" (Google Cloud project ogrodje-metaos)"
  type = object({
    client_id = string
  })
  default = null
}

variable "google_client_secret" {
  description = "Its secret, to create the provider or change the secret (TF_VAR_google_client_secret)"
  type        = string
  sensitive   = true
  default     = null
}

variable "discord" {
  description = "The Discord application's OAuth2 client"
  type = object({
    client_id = string
  })
  default = null
}

variable "discord_client_secret" {
  description = "Its secret, to create the provider or change the secret (TF_VAR_discord_client_secret)"
  type        = string
  sensitive   = true
  default     = null
}

variable "smtp" {
  description = "How Keycloak sends email; null leaves it unset"
  type = object({
    host              = string
    port              = string
    starttls          = bool
    from              = string
    from_display_name = string
    username          = string
  })
  default = null
}

variable "smtp_password" {
  description = "The SMTP user's password, to set up email or change the password (TF_VAR_smtp_password)"
  type        = string
  sensitive   = true
  default     = null
}

variable "dev_user" {
  description = "Whether the realm has the dev / dev user, for development only"
  type        = bool
  default     = false
}
