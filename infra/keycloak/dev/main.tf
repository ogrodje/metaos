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
  # A second front-end, e.g. a worktree's, on the next ports Nuxt's dev server takes (as the dev bucket's CORS, ../../gcp)
  metaos_ui_other_urls = ["http://localhost:5174", "http://localhost:5175"]

  # The same OAuth client as production's, which has localhost's redirect URI too. Its secret only to create the
  # provider or change it: `TF_VAR_google_client_secret=$GOOGLE_IDP_CLIENT_SECRET just keycloak apply dev`.
  google = {
    client_id = "152555588938-nsui74mmtp8bn9d7d35l01c2krdio4ji.apps.googleusercontent.com"
  }
  google_client_secret = var.google_client_secret

  dev_user = true
}

variable "google_client_secret" {
  description = "Google's client secret, to create the provider or change the secret"
  type        = string
  sensitive   = true
  default     = null
}

# For agents/ in development: `terraform -chdir=infra/keycloak/dev output -raw agents_client_secret`
output "agents_client_secret" {
  value     = module.realm.agents_client_secret
  sensitive = true
}
