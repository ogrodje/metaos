# cloudflared: the tunnel's end in the cluster (access.tf). It dials out to Cloudflare, so nothing on the cluster is
# published, and it's the only thing that reaches Canvas (network.tf). Two, so a restart doesn't drop the tunnel; both on
# the factory node, as everything here.

locals {
  cloudflared_labels = { "app.kubernetes.io/name" = "cloudflared" }
}

resource "kubernetes_secret_v1" "cloudflared" {
  metadata {
    name      = "cloudflared"
    namespace = local.namespace
  }

  data = {
    TUNNEL_TOKEN = data.cloudflare_zero_trust_tunnel_cloudflared_token.factory.token
  }
}

resource "kubernetes_deployment_v1" "cloudflared" {
  metadata {
    name      = "cloudflared"
    namespace = local.namespace
    labels    = local.cloudflared_labels
  }

  spec {
    replicas = 2

    selector {
      match_labels = local.cloudflared_labels
    }

    template {
      metadata {
        labels = local.cloudflared_labels
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

        security_context {
          run_as_non_root = true
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        container {
          name  = "cloudflared"
          image = "cloudflare/cloudflared:2026.11.2"
          args  = ["tunnel", "--no-autoupdate", "--metrics", "0.0.0.0:2000", "run"]

          env_from {
            secret_ref {
              name = kubernetes_secret_v1.cloudflared.metadata[0].name
            }
          }

          port {
            name           = "metrics"
            container_port = 2000
          }

          resources {
            requests = { cpu = "50m", memory = "64Mi" }
            limits   = { memory = "256Mi" }
          }

          security_context {
            allow_privilege_escalation = false
            read_only_root_filesystem  = true
            capabilities {
              drop = ["ALL"]
            }
          }

          # Ready once it has a connection to Cloudflare
          readiness_probe {
            http_get {
              path = "/ready"
              port = "metrics"
            }
            period_seconds = 10
          }

          liveness_probe {
            http_get {
              path = "/ready"
              port = "metrics"
            }
            initial_delay_seconds = 30
            period_seconds        = 30
            failure_threshold     = 5
          }
        }
      }
    }
  }
}
