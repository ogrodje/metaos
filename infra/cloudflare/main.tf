# Cloudflare zone ogrodje.si, as far as MetaOS goes (CLAUDE.md, "Google Cloud Storage" and "Deployment"): the names
# metaos.ogrodje.si, metaos-auth.ogrodje.si and media.ogrodje.si, and the rules that cache and serve them. The zone
# has other sites' names and rules, which stay out. Signs in with CLOUDFLARE_API_TOKEN (.envrc).
terraform {
  required_version = ">= 1.9"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.28"
    }
  }

  backend "gcs" {
    bucket = "ogrodje-metaos-tfstate"
    prefix = "cloudflare"
  }
}

provider "cloudflare" {}

locals {
  zone_id = "524def828f49bea187dc3eae3d02fff8" # ogrodje.si
}
