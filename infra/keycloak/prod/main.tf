# Production: https://metaos-auth.ogrodje.si. Its state is in Cloud Storage (../../gcp), as it has the realm in it,
# with any secret that was set.
terraform {
  required_version = ">= 1.9"

  required_providers {
    keycloak = {
      source  = "keycloak/keycloak"
      version = "~> 5.10"
    }
  }

  backend "gcs" {
    bucket = "ogrodje-metaos-tfstate"
    prefix = "keycloak/prod"
  }
}

locals {
  keycloak_url = "https://metaos-auth.ogrodje.si"
}

# Signs in to realm master as an admin: KEYCLOAK_ADMIN_USERNAME and KEYCLOAK_ADMIN_PASSWORD (justfile)
provider "keycloak" {
  url       = local.keycloak_url
  realm     = "master"
  client_id = "admin-cli"
  username  = var.keycloak_admin_username
  password  = var.keycloak_admin_password
}

module "realm" {
  source = "../realm"

  metaos_mcp_url = "https://metaos.ogrodje.si/mcp"
  metaos_api_url = "https://metaos.ogrodje.si/graphql"
  metaos_ui_url  = "https://metaos.ogrodje.si"

  # Google's "Web application" OAuth client "Keycloak" (project ogrodje-metaos), and the Discord application's
  google = {
    client_id = "152555588938-nsui74mmtp8bn9d7d35l01c2krdio4ji.apps.googleusercontent.com"
  }
  discord = {
    client_id = "1561678587650768906"
  }
  google_client_secret  = var.google_client_secret
  discord_client_secret = var.discord_client_secret

  # Through delta.srv4all.net on 587 with StartTLS, as Hetzner blocks 25 and 465
  smtp = {
    host                  = "delta.srv4all.net"
    port                  = "587"
    starttls              = true
    from                  = "iva@ogrodje.si"
    from_display_name     = "Iva"
    reply_to              = "iva@ogrodje.si"
    reply_to_display_name = "Iva"
    username              = "iva@ogrodje.si"
  }
  smtp_password = var.smtp_password
}
