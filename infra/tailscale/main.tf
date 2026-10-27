# The tailnet's policy file (policy.tf), which is private: this repository is public, so the file is never committed
# and the stack is applied from a machine that has it, never by CI (README.md). Signs in with an OAuth client,
# TAILSCALE_OAUTH_CLIENT_ID and TAILSCALE_OAUTH_CLIENT_SECRET (.envrc).
terraform {
  required_version = ">= 1.9"

  required_providers {
    tailscale = {
      source  = "tailscale/tailscale"
      version = "~> 0.29"
    }
  }

  # Private as well: the state has the policy in it
  backend "gcs" {
    bucket = "ogrodje-metaos-tfstate"
    prefix = "tailscale"
  }
}

provider "tailscale" {}
