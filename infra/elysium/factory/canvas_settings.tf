# Every Canvas' settings that Elysium gives it (elysium/canvas-settings/settings.ts): the LLM, Elysium's identity in
# GitHub and Linear, MetaOS' MCP server and the previews' manager, through Canvas' API. A CronJob, weekly, as Linear's
# token lasts 30 days; `just canvas-settings` runs it now (after a new Canvas, or a token changing in set_secrets.sh).
# From what the cluster has already: the Canvases and their session keys (the bridge's CANVASES), LiteLLM's key, the
# bridge's GitHub token and Linear client secret, the previews' token. Only the keys it needs, not the secrets whole.

locals {
  canvas_settings_labels = { "app.kubernetes.io/name" = "canvas-settings" }
}

resource "kubernetes_config_map_v1" "canvas_settings" {
  metadata {
    name      = "canvas-settings"
    namespace = local.namespace
  }
  data = {
    "settings.ts" = file("${path.module}/../../../elysium/canvas-settings/settings.ts")
  }
}

resource "kubernetes_cron_job_v1" "canvas_settings" {
  metadata {
    name      = "canvas-settings"
    namespace = local.namespace
    labels    = local.canvas_settings_labels
  }

  spec {
    # Mondays at 04:17 UTC
    schedule                      = "17 4 * * 1"
    concurrency_policy            = "Forbid"
    successful_jobs_history_limit = 1
    failed_jobs_history_limit     = 2

    job_template {
      metadata {
        labels = local.canvas_settings_labels
      }

      spec {
        backoff_limit           = 2
        active_deadline_seconds = 600

        template {
          metadata {
            labels = local.canvas_settings_labels
            annotations = {
              "ogrodje.si/source" = sha256(kubernetes_config_map_v1.canvas_settings.data["settings.ts"])
            }
          }

          spec {
            restart_policy                  = "Never"
            automount_service_account_token = false
            node_selector                   = { "ogrodje.si/pool" = local.pool }

            toleration {
              key      = "ogrodje.si/pool"
              operator = "Equal"
              value    = local.pool
              effect   = "NoSchedule"
            }

            security_context {
              run_as_non_root = true
              run_as_user     = 1000
              run_as_group    = 1000
              seccomp_profile {
                type = "RuntimeDefault"
              }
            }

            container {
              name    = "settings"
              image   = "node:26.13.0-slim"
              command = ["node", "/app/settings.ts"]

              dynamic "env" {
                for_each = {
                  PREVIEWS_URL   = "http://${kubernetes_service_v1.previews_manager.metadata[0].name}.${local.previews_namespace}.svc.cluster.local:8080"
                  METAOS_MCP_URL = "http://${kubernetes_service_v1.linear_bridge.metadata[0].name}.${local.namespace}.svc.cluster.local:8080/mcp/metaos"
                }
                content {
                  name  = env.key
                  value = env.value
                }
              }

              dynamic "env" {
                for_each = {
                  CANVASES             = kubernetes_secret_v1.linear_bridge.metadata[0].name
                  LINEAR_CLIENT_ID     = kubernetes_secret_v1.linear_bridge.metadata[0].name
                  PREVIEWS_TOKEN       = kubernetes_secret_v1.linear_bridge.metadata[0].name
                  LITELLM_MASTER_KEY   = "litellm"
                  GITHUB_TOKEN         = "linear-bridge-linear"
                  LINEAR_CLIENT_SECRET = "linear-bridge-linear"
                }
                content {
                  name = env.key
                  value_from {
                    secret_key_ref {
                      name = env.value
                      key  = env.key
                    }
                  }
                }
              }

              resources {
                requests = { cpu = "20m", memory = "64Mi" }
                limits   = { memory = "256Mi" }
              }

              security_context {
                allow_privilege_escalation = false
                run_as_non_root            = true
                read_only_root_filesystem  = true
                capabilities {
                  drop = ["ALL"]
                }
              }

              volume_mount {
                name       = "app"
                mount_path = "/app"
                read_only  = true
              }
            }

            volume {
              name = "app"
              config_map {
                name = kubernetes_config_map_v1.canvas_settings.metadata[0].name
              }
            }
          }
        }
      }
    }
  }
}

# The Canvases' API, and Linear's over HTTPS (its token); nothing else
resource "kubernetes_network_policy_v1" "canvas_settings" {
  metadata {
    name      = "canvas-settings"
    namespace = local.namespace
  }

  spec {
    pod_selector {
      match_labels = local.canvas_settings_labels
    }
    policy_types = ["Ingress", "Egress"]

    egress {
      to {
        pod_selector {
          match_labels = local.canvas_labels
        }
      }
      ports {
        protocol = "TCP"
        port     = "8000"
      }
    }

    egress {
      to {
        ip_block {
          cidr   = "0.0.0.0/0"
          except = local.private_ranges
        }
      }
      ports {
        protocol = "TCP"
        port     = "443"
      }
    }
  }
}
