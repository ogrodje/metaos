variable "keycloak_admin_username" {
  description = "An admin of realm master (KEYCLOAK_ADMIN_USERNAME)"
  type        = string
}

variable "keycloak_admin_password" {
  description = "Its password (KEYCLOAK_ADMIN_PASSWORD)"
  type        = string
  sensitive   = true
}

# Secrets, each only to create what has it or change it: one left unset keeps what Keycloak has (../realm/realm.tf)

variable "google_client_secret" {
  description = "Google's client secret (TF_VAR_google_client_secret)"
  type        = string
  sensitive   = true
  default     = null
}

variable "discord_client_secret" {
  description = "Discord's client secret (TF_VAR_discord_client_secret)"
  type        = string
  sensitive   = true
  default     = null
}

variable "smtp_password" {
  description = "The SMTP user's password (TF_VAR_smtp_password)"
  type        = string
  sensitive   = true
  default     = null
}
