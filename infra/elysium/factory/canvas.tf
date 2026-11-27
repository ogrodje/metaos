# OpenHands' Agent Canvas (github.com/OpenHands/OpenHands, MIT): its UI, agent-server and automations in one pod, one
# volume. As its chart (helm/agent-canvas there) has it, which isn't published, so it's here instead, with what the
# chart has no place for: the factory node, and later Sysbox' runtime class, for Docker in the agents' builds.
#
# One tenant, no sign-in: whoever reaches port 8000 drives the agents, so nothing publishes it (`just canvas`), and
# every agent shares the pod and its volume. Its LLM is set in its UI, once (../README.md): the agent-server has no
# environment variables for it, and keeps it encrypted on the volume.

locals {
  canvas_labels = { "app.kubernetes.io/name" = "canvas" }
}

resource "kubernetes_service_account_v1" "canvas" {
  metadata {
    name      = "canvas"
    namespace = local.namespace
  }

  # No access to the cluster: the agents run kubectl with nothing
  automount_service_account_token = false
}

resource "kubernetes_stateful_set_v1" "canvas" {
  metadata {
    name      = "canvas"
    namespace = local.namespace
    labels    = local.canvas_labels
  }

  spec {
    service_name = kubernetes_service_v1.canvas.metadata[0].name
    replicas     = 1

    selector {
      match_labels = local.canvas_labels
    }

    template {
      metadata {
        labels = local.canvas_labels
      }

      spec {
        service_account_name            = kubernetes_service_account_v1.canvas.metadata[0].name
        automount_service_account_token = false
        node_selector                   = { "ogrodje.si/pool" = local.pool }

        toleration {
          key      = "ogrodje.si/pool"
          operator = "Equal"
          value    = local.pool
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

          # The agents' builds are what take the memory: MetaOS' Gradle and Kotlin daemons want a few GB of their own
          resources {
            requests = { cpu = "1", memory = "4Gi" }
            limits   = { memory = "12Gi" }
          }

          dynamic "volume_mount" {
            for_each = local.canvas_mounts
            content {
              name       = "home"
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
      }
    }

    # What survives a restart: settings, secrets, conversations and the automations' SQLite (.openhands), the agents'
    # checkouts (workspace), and Gradle's caches, so a build doesn't download the world each time. Not ~/.cache, which
    # the image ships things in
    volume_claim_template {
      metadata {
        name = "home"
      }

      spec {
        access_modes       = ["ReadWriteOnce"]
        storage_class_name = var.storage_class

        resources {
          requests = { storage = "20Gi" }
        }
      }
    }

    persistent_volume_claim_retention_policy {
      when_deleted = "Retain"
      when_scaled  = "Retain"
    }
  }
}

locals {
  # Under /home/openhands, each on its own directory of the one volume
  canvas_mounts = {
    ".openhands" = "openhands"
    "workspace"  = "workspace"
    ".gradle"    = "gradle"
  }
}

resource "kubernetes_service_v1" "canvas" {
  metadata {
    name      = "canvas"
    namespace = local.namespace
  }

  spec {
    selector = local.canvas_labels

    port {
      name        = "http"
      port        = 8000
      target_port = "http"
    }
  }
}
