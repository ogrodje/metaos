# The software factory (../README.md): OpenHands' Agent Canvas, whose agents work on Ogrodje's code, and LiteLLM, the
# only thing that holds the LLM providers' keys, in namespace factory on the factory node. Applied by hand,
# `just infra elysium/factory <command>`, after elysium/cluster; the providers' keys go in with set_secrets.sh, not
# Terraform (../../README.md, "What isn't Terraform's").
#
# Reached at https://agent.ogrodje.dev, behind Cloudflare Access (access.tf), or with `just canvas`, a port-forward.
terraform {
  required_version = ">= 1.9"

  required_providers {
    vultr = {
      source  = "vultr/vultr"
      version = "~> 2.33"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 3.5"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.29"
    }
  }

  # Under elysium/, which CI's service account can't read (../../gcp/ci.tf): it holds LiteLLM's master key
  backend "gcs" {
    bucket = "ogrodje-metaos-tfstate"
    prefix = "elysium/factory"
  }
}

provider "vultr" {}

# ogrodje.dev's account (access.tf), with ELYSIUM_CLOUDFLARE_API_TOKEN, which the justfile passes as
# CLOUDFLARE_API_TOKEN
provider "cloudflare" {}

# The cluster by its label, not from elysium/cluster's state, and its kubeconfig from Vultr, as an admin's: this stack
# changes nothing but what's in the cluster
data "vultr_kubernetes" "elysium" {
  filter {
    name   = "label"
    values = ["elysium"]
  }
}

data "vultr_kubernetes_kubeconfig" "elysium" {
  cluster_id = data.vultr_kubernetes.elysium.id
}

locals {
  kubeconfig = yamldecode(base64decode(data.vultr_kubernetes_kubeconfig.elysium.kube_config))
  cluster    = local.kubeconfig.clusters[0].cluster
  user       = local.kubeconfig.users[0].user
}

# VKE's kubeconfig signs in with a token, not a client certificate
provider "kubernetes" {
  host                   = local.cluster.server
  cluster_ca_certificate = base64decode(local.cluster["certificate-authority-data"])
  token                  = local.user.token
}

locals {
  namespace = kubernetes_namespace_v1.factory.metadata[0].name

  # Everything here runs on the factory node (../cluster/vke.tf), which is tainted for it
  pool = "factory"
}

resource "kubernetes_namespace_v1" "factory" {
  metadata {
    name = "factory"
    # Baseline, not restricted: the agents use sudo in their own container, which restricted's
    # allowPrivilegeEscalation: false stops; baseline still keeps out privileged pods and the host's namespaces
    labels = {
      "pod-security.kubernetes.io/enforce" = "baseline"
      "pod-security.kubernetes.io/warn"    = "restricted"
    }
  }
}
