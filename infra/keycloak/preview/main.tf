# Previews: the previews' Keycloak in Elysium (infra/elysium/factory/previews.tf), which every preview slot signs in
# through, at https://preview-N.ogrodje.dev/auth (CLAUDE.md's Previews). Reached through a port-forward, which the
# justfile starts, as its console is never routed: `just keycloak adopt preview` the first time, then
# `just keycloak apply preview`. Its state is in Cloud Storage, beside Elysium's, as there's one of it.
#
# A preview's slots are fixed, so their addresses are too, which metaos-ui's redirect URIs name here once, rather than
# each preview adding its own.
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
    prefix = "elysium/previews-keycloak"
  }
}

locals {
  # The port-forward the justfile starts, under /auth as the slots have it; import.sh signs in here too
  keycloak_url = "http://localhost:8189/auth"
  slot_urls    = [for n in range(1, var.slots + 1) : "https://preview-${n}.ogrodje.dev"]
}

# Its admin, whose password Terraform made (infra/elysium/factory: secret keycloak in namespace previews), which the
# justfile reads
provider "keycloak" {
  url       = trimsuffix(local.keycloak_url, "/auth")
  base_path = "/auth"
  realm     = "master"
  client_id = "admin-cli"
  username  = var.keycloak_admin_username
  password  = var.keycloak_admin_password
}

module "realm" {
  source = "../realm"

  # The audiences, as development's: names the previews' MetaOS checks tokens for, not addresses anyone goes to
  metaos_mcp_url       = "http://localhost:8080/mcp"
  metaos_api_url       = "http://localhost:8080/graphql"
  metaos_ui_url        = local.slot_urls[0]
  metaos_ui_other_urls = slice(local.slot_urls, 1, length(local.slot_urls))

  # dev / dev, staff: previews are behind Cloudflare Access, for the team only. No Google or Discord, which don't know
  # the previews' addresses, and no email, so a preview sends none
  dev_user = true
}

variable "slots" {
  description = "How many slots there are, as infra/elysium/factory's preview_slots"
  type        = number
  default     = 2
}

variable "keycloak_admin_username" {
  type    = string
  default = "admin"
}

variable "keycloak_admin_password" {
  description = "The previews' Keycloak's admin's (TF_VAR_keycloak_admin_password; the justfile reads it from the cluster)"
  type        = string
  sensitive   = true
}
