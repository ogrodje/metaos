# The bridge (elysium/linear-bridge, ../README.md "The bridge"): what Cyrus is on nuc8, for the Canvases. Linear's agent
# session events (an issue delegated to Elysium, a mention of it) reach it at hooks.ogrodje.dev/linear/webhook, and
# GitHub's on ogrodje's pull requests (a mention of @elysium-og, changes requested on Elysium's) at
# hooks.ogrodje.dev/github/webhook (access.tf, github.tf); it runs each in the personal Canvas of whoever asked, and
# reports back to where they asked. One TypeScript file, Node's own modules only, which Node runs as
# it is: so a ConfigMap of it and Node's own image, nothing to build.
#
# Its secrets: the Canvases' session keys and whose each is (Terraform's, below), and Linear's app's client secret and
# webhook signing secret (set_secrets.sh, from 1Password).

locals {
  linear_bridge_labels = { "app.kubernetes.io/name" = "linear-bridge" }
  linear_bridge_source = file("${path.module}/../../../elysium/linear-bridge/bridge.ts")

  # Whose Canvas is whose: a request runs in its asker's, by Linear email or GitHub login. With personal Canvases, the
  # team's is nobody's, so none runs there; without, it's everyone's
  linear_bridge_canvases = jsonencode([
    for name in local.canvas_names : {
      name      = name
      url       = module.canvas[name].service_url
      key       = module.canvas[name].session_key
      publicUrl = module.canvas[name].public_url
      emails    = name != "team" ? [lower(local.users[name].email)] : var.personal_canvases ? [] : [for n in local.names : lower(local.users[n].email)]
      github    = name != "team" ? [lower(local.users[name].github)] : var.personal_canvases ? [] : [for n in local.names : lower(local.users[n].github)]
    }
  ])
}

resource "kubernetes_config_map_v1" "linear_bridge" {
  metadata {
    name      = "linear-bridge"
    namespace = local.namespace
  }

  data = {
    "bridge.ts" = local.linear_bridge_source
  }
}

resource "kubernetes_secret_v1" "linear_bridge" {
  metadata {
    name      = "linear-bridge"
    namespace = local.namespace
  }

  data = {
    CANVASES              = local.linear_bridge_canvases
    LINEAR_CLIENT_ID      = "1fad2d2ffaf2bf8a19da97b44790cdff" # OAuth app "Elysium" in Linear's workspace ogrodje
    GITHUB_WEBHOOK_SECRET = random_password.github_webhook.result
  }
}

resource "kubernetes_deployment_v1" "linear_bridge" {
  metadata {
    name      = "linear-bridge"
    namespace = local.namespace
    labels    = local.linear_bridge_labels
  }

  spec {
    replicas = 1

    selector {
      match_labels = local.linear_bridge_labels
    }

    template {
      metadata {
        labels = local.linear_bridge_labels
        # A new version of the code, or of the Canvases, rolls the pod
        annotations = {
          "ogrodje.si/source"   = sha256(local.linear_bridge_source)
          "ogrodje.si/canvases" = nonsensitive(sha256(local.linear_bridge_canvases))
          "ogrodje.si/github"   = nonsensitive(sha256(random_password.github_webhook.result))
        }
      }

      spec {
        automount_service_account_token = false
        node_selector                   = { "ogrodje.si/pool" = local.pool }

        toleration {
          key      = "ogrodje.si/pool"
          operator = "Equal"
          value    = local.pool
          effect   = "NoSchedule"
        }

        # The image's user, node
        security_context {
          run_as_user     = 1000
          run_as_group    = 1000
          run_as_non_root = true
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        container {
          name    = "bridge"
          image   = "node:26.13.0-slim"
          command = ["node", "/app/bridge.ts"]

          port {
            name           = "http"
            container_port = 8080
          }

          env {
            name  = "PORT"
            value = "8080"
          }
          # A conversation's hard cost ceiling, in USD (the month's is Anthropic's, on the workspace)
          env {
            name  = "MAX_BUDGET_PER_RUN"
            value = "10"
          }

          env_from {
            secret_ref {
              name = kubernetes_secret_v1.linear_bridge.metadata[0].name
            }
          }

          # set_secrets.sh's: LINEAR_CLIENT_SECRET, LINEAR_WEBHOOK_SECRET, and GITHUB_TOKEN, Elysium's (elysium-og's), to
          # answer on GitHub with. The bridge doesn't start without the first two
          env_from {
            secret_ref {
              name     = "linear-bridge-linear"
              optional = true
            }
          }

          volume_mount {
            name       = "app"
            mount_path = "/app"
            read_only  = true
          }

          resources {
            requests = { cpu = "50m", memory = "96Mi" }
            limits   = { memory = "256Mi" }
          }

          security_context {
            allow_privilege_escalation = false
            read_only_root_filesystem  = true
            capabilities {
              drop = ["ALL"]
            }
          }

          readiness_probe {
            http_get {
              path = "/healthz"
              port = "http"
            }
            period_seconds = 10
          }

          liveness_probe {
            http_get {
              path = "/healthz"
              port = "http"
            }
            initial_delay_seconds = 10
            period_seconds        = 30
          }
        }

        volume {
          name = "app"
          config_map {
            name = kubernetes_config_map_v1.linear_bridge.metadata[0].name
          }
        }
      }
    }
  }

  # It waits for set_secrets.sh's secret, the first time
  wait_for_rollout = false
}

resource "kubernetes_service_v1" "linear_bridge" {
  metadata {
    name      = "linear-bridge"
    namespace = local.namespace
  }

  spec {
    selector = local.linear_bridge_labels

    port {
      name        = "http"
      port        = 8080
      target_port = "http"
    }
  }
}
