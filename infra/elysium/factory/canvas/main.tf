# One Agent Canvas (github.com/OpenHands/OpenHands, MIT): its UI, agent-server and automations in one pod, one volume,
# at https://<hostname>, which Cloudflare Access opens to `emails` only (../README.md, "Team and personal Canvases").
# As Canvas' chart (helm/agent-canvas there) has it, which isn't published, with what the chart has no place for: the
# factory node, the session key from Kubernetes, and two kinds of disk: its volume (Vultr's NVMe block storage, which
# lasts), for its settings, conversations and the agents' checkouts, and the node's own disk, free and fast but gone
# when the pod restarts, for the build tools' caches.
#
# Canvas has no users of its own: whoever reaches port 8000 with its session key drives its agents. So only cloudflared,
# after Access, and the Linear bridge reach it (../network.tf), and the key, which Terraform makes, Canvas puts into its
# own page for whoever got that far: nobody has to type it.

terraform {
  required_version = ">= 1.9"

  required_providers {
    kubernetes = { source = "hashicorp/kubernetes", version = "~> 3.5" }
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.29" }
    random     = { source = "hashicorp/random", version = "~> 3.9" }
  }
}

variable "name" {
  description = "Whose Canvas: team, or a person's name (FACTORY_USERS)"
  type        = string
}

variable "k8s_name" {
  description = "Its StatefulSet's and Service's name"
  type        = string
}

variable "hostname" {
  description = "Where its people open it, under ogrodje.dev"
  type        = string
}

variable "emails" {
  description = "Whom Access lets in"
  type        = list(string)
  sensitive   = true
}

variable "namespace" {
  type = string
}

variable "pool" {
  description = "The node pool it runs on, which is tainted for it"
  type        = string
}

variable "service_account" {
  type = string
}

variable "storage_class" {
  type = string
}

variable "volume_size" {
  type = string
}

variable "cache_size" {
  description = "How much of the node's own disk the build tools' caches may take (Gradle's, Maven's, npm's): emptied when the pod restarts"
  type        = string
}

variable "memory_request" {
  type = string
}

variable "memory_limit" {
  type = string
}

variable "cloudflare_account_id" {
  type = string
}

locals {
  labels = { "app.kubernetes.io/name" = "canvas", "app.kubernetes.io/instance" = var.name }
  # Under /home/openhands, each on its own directory of the volume: settings, secrets, conversations, the agents'
  # checkouts (.openhands, whose agent-canvas/workspaces they're in) and the automations' SQLite, and workspace
  mounts = {
    ".openhands" = "openhands"
    "workspace"  = "workspace"
  }
  # And of the node's disk: the build tools' caches, so a build doesn't download the world each time; refilled after a
  # restart. Not ~/.cache, which the image ships things in
  caches = {
    ".gradle" = "gradle"
    ".m2"     = "m2"
    ".npm"    = "npm"
  }
}

resource "random_password" "session_key" {
  length  = 48
  special = false
}

resource "kubernetes_secret_v1" "session" {
  metadata {
    name      = "${var.k8s_name}-session"
    namespace = var.namespace
  }

  data = {
    OH_SESSION_API_KEYS_0 = random_password.session_key.result
  }
}

resource "kubernetes_stateful_set_v1" "canvas" {
  metadata {
    name      = var.k8s_name
    namespace = var.namespace
    labels    = local.labels
  }

  spec {
    service_name = var.k8s_name
    replicas     = 1

    selector {
      match_labels = local.labels
    }

    template {
      metadata {
        labels = local.labels
        annotations = {
          "ogrodje.si/session-key" = sha256(random_password.session_key.result)
        }
      }

      spec {
        service_account_name            = var.service_account
        automount_service_account_token = false
        node_selector                   = { "ogrodje.si/pool" = var.pool }

        toleration {
          key      = "ogrodje.si/pool"
          operator = "Equal"
          value    = var.pool
          effect   = "NoSchedule"
        }

        # The image's user, openhands; fs_group gives it the volume
        security_context {
          run_as_user     = 10001
          run_as_group    = 10001
          run_as_non_root = true
          fs_group        = 10001
        }

        container {
          name  = "canvas"
          image = "ghcr.io/openhands/agent-canvas:1.46.0"

          port {
            name           = "http"
            container_port = 8000
          }

          env {
            name  = "PORT"
            value = "8000"
          }
          # Automations' packages on the volume, not in a cloud bucket
          env {
            name  = "FILE_STORE"
            value = "local"
          }
          # Its page carries the session key: only Access' people and the bridge reach it (../network.tf)
          env {
            name  = "AGENT_CANVAS_ALLOW_LAN_SESSION_KEY"
            value = "true"
          }

          env_from {
            secret_ref {
              name = kubernetes_secret_v1.session.metadata[0].name
            }
          }

          # The agents' builds are what take the memory: MetaOS' Gradle and Kotlin daemons want a few GB of their own
          resources {
            requests = { cpu = "250m", memory = var.memory_request }
            limits   = { memory = var.memory_limit }
          }

          dynamic "volume_mount" {
            for_each = local.mounts
            content {
              name       = "home"
              mount_path = "/home/openhands/${volume_mount.key}"
              sub_path   = volume_mount.value
            }
          }

          dynamic "volume_mount" {
            for_each = local.caches
            content {
              name       = "caches"
              mount_path = "/home/openhands/${volume_mount.key}"
              sub_path   = volume_mount.value
            }
          }

          startup_probe {
            http_get {
              path = "/alive"
              port = "http"
            }
            period_seconds    = 5
            timeout_seconds   = 5
            failure_threshold = 60
          }

          liveness_probe {
            http_get {
              path = "/alive"
              port = "http"
            }
            period_seconds    = 30
            timeout_seconds   = 5
            failure_threshold = 5
          }

          readiness_probe {
            http_get {
              path = "/alive"
              port = "http"
            }
            period_seconds  = 10
            timeout_seconds = 5
          }
        }

        volume {
          name = "home"
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim_v1.home.metadata[0].name
          }
        }

        volume {
          name = "caches"
          empty_dir {
            size_limit = var.cache_size
          }
        }
      }
    }

  }
}

# Its volume, Terraform's own rather than the StatefulSet's template's, so that a bigger size grows it in place
# (vultr-block-storage allows that; it never shrinks). Named as the template named it, which the team's was made by
resource "kubernetes_persistent_volume_claim_v1" "home" {
  metadata {
    name      = "home-${var.k8s_name}-0"
    namespace = var.namespace
    labels    = local.labels
  }

  spec {
    access_modes       = ["ReadWriteOnce"]
    storage_class_name = var.storage_class

    resources {
      requests = { storage = var.volume_size }
    }
  }
}

resource "kubernetes_service_v1" "canvas" {
  metadata {
    name      = var.k8s_name
    namespace = var.namespace
  }

  spec {
    selector = local.labels

    port {
      name        = "http"
      port        = 8000
      target_port = "http"
    }
  }
}

# --- Cloudflare: who Access lets in (its name, a CNAME to the tunnel, is ../access.tf's) ------------------------------------

resource "cloudflare_zero_trust_access_policy" "canvas" {
  account_id = var.cloudflare_account_id
  name       = var.name == "team" ? "Elysium factory team" : "Elysium: ${var.name}"
  decision   = "allow"
  include    = [for e in var.emails : { email = { email = e } }]
}

resource "cloudflare_zero_trust_access_application" "canvas" {
  account_id       = var.cloudflare_account_id
  name             = var.name == "team" ? "Elysium factory: Agent Canvas" : "Elysium: ${var.name}'s Canvas"
  type             = "self_hosted"
  domain           = var.hostname
  destinations     = [{ type = "public", uri = var.hostname }]
  session_duration = "24h"

  policies = [{
    id         = cloudflare_zero_trust_access_policy.canvas.id
    precedence = 1
  }]
}

output "service_url" {
  description = "Its API inside the cluster"
  value       = "http://${kubernetes_service_v1.canvas.metadata[0].name}.${var.namespace}.svc.cluster.local:8000"
}

output "public_url" {
  value = "https://${var.hostname}/canvas"
}

output "hostname" {
  value = var.hostname
}

output "access_aud" {
  description = "Access' audience tag for it, which cloudflared checks (../access.tf)"
  value       = cloudflare_zero_trust_access_application.canvas.aud
}

output "session_key" {
  value     = random_password.session_key.result
  sensitive = true
}
