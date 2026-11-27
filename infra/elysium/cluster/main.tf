# Elysium, the Vultr cluster MetaOS moves to and the software factory runs in (../README.md): its network, the VKE
# cluster with its two node pools, and the container registry. Applied by hand only, `just infra elysium/cluster
# <command>`: a change here can replace nodes, or the whole cluster. Signs in with VULTR_API_KEY (secretspec.toml).
terraform {
  required_version = ">= 1.9"

  required_providers {
    vultr = {
      source  = "vultr/vultr"
      version = "~> 2.33"
    }
  }

  # Under elysium/, which CI's service account can't read (../../gcp/ci.tf)
  backend "gcs" {
    bucket = "ogrodje-metaos-tfstate"
    prefix = "elysium/cluster"
  }
}

provider "vultr" {}

locals {
  # Amsterdam: VKE, the registry, both plans and NVMe block storage, which Postgres' volumes are on. Frankfurt, nearer,
  # has no NVMe block storage
  region = "ams"
}
