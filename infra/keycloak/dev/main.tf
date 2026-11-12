# Development: compose.yml's Keycloak. Every developer has their own, so its state stays on their machine
# (terraform.tfstate here, not committed), and goes with the Keycloak: `just keycloak adopt dev` takes in one Terraform
# hasn't seen, e.g. a new one, or one whose state was deleted.
terraform {
  required_version = ">= 1.9"

  required_providers {
    keycloak = {
      source  = "keycloak/keycloak"
      version = "~> 5.10"
    }
  }
}

locals {
  keycloak_url = "http://localhost:8180"
}

# compose.yml's admin, whatever .envrc says for production's
provider "keycloak" {
  url       = local.keycloak_url
  realm     = "master"
  client_id = "admin-cli"
  username  = "admin"
  password  = "admin"
}

module "realm" {
  source = "../realm"

  metaos_mcp_url = "http://localhost:8080/mcp"
  metaos_api_url = "http://localhost:8080/graphql"
  metaos_ui_url  = "http://localhost:5173"
  # A second front-end, e.g. a worktree's, on the next ports Nuxt's dev server takes (as the dev bucket's CORS, ../../gcp),
  # and this developer's own others, e.g. by a tailnet name
  metaos_ui_other_urls = concat(["http://localhost:5174", "http://localhost:5175"], var.metaos_ui_other_urls)

  # The same OAuth client as production's, which has localhost's redirect URI too. Its secret only to create the
  # provider or change it: `TF_VAR_google_client_secret=$GOOGLE_IDP_CLIENT_SECRET just keycloak apply dev`.
  google = {
    client_id = "152555588938-nsui74mmtp8bn9d7d35l01c2krdio4ji.apps.googleusercontent.com"
  }
  google_client_secret = var.google_client_secret

  dev_user = true

  # compose.yml's Mailpit, which keeps what Keycloak sends, e.g. the links to verify an email, at http://localhost:8025.
  # With it, Keycloak has users verify their email, as in production. Any password does
  smtp = {
    host              = "mailpit"
    port              = "1025"
    starttls          = false
    from              = "metaos@metaos.local"
    from_display_name = "MetaOS (dev)"
    username          = "keycloak"
  }
}

variable "google_client_secret" {
  description = "Google's client secret, to create the provider or change the secret"
  type        = string
  sensitive   = true
  default     = null
}

variable "metaos_ui_other_urls" {
  description = "Where else this developer's front-end is, e.g. https://nuc8.folk-decibel.ts.net:5173 (TF_VAR_metaos_ui_other_urls)"
  type        = list(string)
  default     = []
}

# For agents/ in development: `terraform -chdir=infra/keycloak/dev output -raw agents_client_secret`
output "agents_client_secret" {
  value     = module.realm.agents_client_secret
  sensitive = true
}
