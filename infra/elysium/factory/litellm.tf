# LiteLLM, as a proxy only: the one place the providers' keys are (secret llm-providers, set_secrets.sh), so an agent,
# which can read everything in its own pod, has LiteLLM's key at most, never Anthropic's or OpenAI's. Every Claude and
# OpenAI model by its own name, `anthropic/<model>` and `openai/<model>`; Agent Canvas asks for them as
# `litellm_proxy/anthropic/<model>`.
#
# No database yet, so no virtual keys or budgets of LiteLLM's own: the spend limits are the providers', on the keys'
# workspace (Anthropic) and project (OpenAI). With Postgres on the cluster (elysium/platform), LiteLLM gets a database,
# a key per user of it and a budget on each.

locals {
  litellm_labels = { "app.kubernetes.io/name" = "litellm" }
}

# Agent Canvas' key to it. LiteLLM takes only keys that start with sk-
resource "random_password" "litellm_master_key" {
  length  = 40
  special = false
}

resource "kubernetes_secret_v1" "litellm" {
  metadata {
    name      = "litellm"
    namespace = local.namespace
  }

  data = {
    LITELLM_MASTER_KEY = "sk-${random_password.litellm_master_key.result}"
  }
}

resource "kubernetes_config_map_v1" "litellm" {
  metadata {
    name      = "litellm"
    namespace = local.namespace
  }

  data = {
    "config.yaml" = yamlencode({
      model_list = [
        {
          model_name     = "anthropic/*"
          litellm_params = { model = "anthropic/*", api_key = "os.environ/ANTHROPIC_API_KEY" }
        },
        {
          model_name     = "openai/*"
          litellm_params = { model = "openai/*", api_key = "os.environ/OPENAI_API_KEY" }
        },
      ]
      litellm_settings = {
        # A parameter a model doesn't take is left out, rather than failing the request
        drop_params = true
      }
      general_settings = {
        master_key = "os.environ/LITELLM_MASTER_KEY"
      }
    })
  }
}

resource "kubernetes_deployment_v1" "litellm" {
  metadata {
    name      = "litellm"
    namespace = local.namespace
    labels    = local.litellm_labels
  }

  # set_secrets.sh restarts it with `kubectl rollout restart`, which stamps the time on it
  lifecycle {
    ignore_changes = [spec[0].template[0].metadata[0].annotations["kubectl.kubernetes.io/restartedAt"]]
  }

  spec {
    replicas = 1

    selector {
      match_labels = local.litellm_labels
    }

    template {
      metadata {
        labels = local.litellm_labels
        # A change to the configuration rolls the pod
        annotations = {
          "ogrodje.si/config" = sha256(kubernetes_config_map_v1.litellm.data["config.yaml"])
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

        container {
          name  = "litellm"
          image = "ghcr.io/berriai/litellm:v1.108.19" # LTS
          args  = ["--config", "/etc/litellm/config.yaml", "--port", "4000"]

          port {
            name           = "http"
            container_port = 4000
          }

          env_from {
            secret_ref {
              name = kubernetes_secret_v1.litellm.metadata[0].name
            }
          }

          # set_secrets.sh's; LiteLLM starts without it, and answers every request with an error until it's there
          env_from {
            secret_ref {
              name     = "llm-providers"
              optional = true
            }
          }

          volume_mount {
            name       = "config"
            mount_path = "/etc/litellm"
            read_only  = true
          }

          resources {
            requests = { cpu = "250m", memory = "512Mi" }
            limits   = { memory = "1Gi" }
          }

          security_context {
            allow_privilege_escalation = false
          }

          readiness_probe {
            http_get {
              path = "/health/readiness"
              port = "http"
            }
            period_seconds = 10
          }

          liveness_probe {
            http_get {
              path = "/health/liveliness"
              port = "http"
            }
            initial_delay_seconds = 30
            period_seconds        = 30
          }
        }

        volume {
          name = "config"
          config_map {
            name = kubernetes_config_map_v1.litellm.metadata[0].name
          }
        }
      }
    }
  }
}

resource "kubernetes_service_v1" "litellm" {
  metadata {
    name      = "litellm"
    namespace = local.namespace
  }

  spec {
    selector = local.litellm_labels

    port {
      name        = "http"
      port        = 4000
      target_port = "http"
    }
  }
}
