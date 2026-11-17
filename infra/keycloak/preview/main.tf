# Previews: compose.yml's keycloak-preview, which the previews on one machine sign in through (CLAUDE.md's Previews,
# scripts/preview.sh). Each machine has its own, as development's, so its state stays on that machine (terraform.tfstate
# here, not committed): `just keycloak adopt preview` the first time, then `just keycloak apply preview`.
#
# A preview's slots are fixed, so their addresses are too: slot N is https://<this machine's tailnet name>:944N, which
# metaos-ui's redirect URIs name here once, rather than each preview adding its own.
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
  # Under /auth, as tailscale serve passes only that path on to it (compose.yml); import.sh signs in here too
  keycloak_url = "http://localhost:8189/auth"
  slot_urls    = [for port in var.slot_ports : "https://${var.host}:${port}"]
}

# compose.yml's admin
provider "keycloak" {
  url       = trimsuffix(local.keycloak_url, "/auth")
  base_path = "/auth"
  realm     = "master"
  client_id = "admin-cli"
  username  = "admin"
  password  = "admin"
}

module "realm" {
  source = "../realm"

  # The audiences, as development's: names the previews' MetaOS checks tokens for, not addresses anyone goes to
  metaos_mcp_url       = "http://localhost:8080/mcp"
  metaos_api_url       = "http://localhost:8080/graphql"
  metaos_ui_url        = local.slot_urls[0]
  metaos_ui_other_urls = slice(local.slot_urls, 1, length(local.slot_urls))

  # dev / dev, staff: previews are on the tailnet only. No Google or Discord, which don't know the previews' addresses,
  # and no email, so a preview sends none
  dev_user = true
}

variable "host" {
  description = "This machine's name on the tailnet, e.g. nuc8.folk-decibel.ts.net (TF_VAR_host; the justfile asks tailscale)"
  type        = string
}

variable "slot_ports" {
  description = "The slots' HTTPS ports on it, as scripts/preview.sh serves them"
  type        = list(number)
  default     = [9441, 9442]
}
