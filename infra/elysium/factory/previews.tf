# Previews (../README.md, "Previews"): MetaOS as an agent built it, at https://preview-N.ogrodje.dev behind Cloudflare
# Access, for the team to try out before it's merged. What preview/preview.sh did on nuc8's tailnet, here:
#
#   manager          elysium/previews/manager.ts: takes an agent's build (preview/preview.sh up), puts it into its slot
#                    and starts it; stops and clears a slot once it's done with (down, its pull request closed, a day
#                    untouched). The only thing here that may change anything in Kubernetes, and only scale the slots
#   preview-N        a slot: its pod runs the build (the boot jar and the front-end's .output, on the images production
#                    runs them on) with a Postgres of its own, seeded from preview/seed.sql, on the volume
#   keycloak         the previews' Keycloak (infra/keycloak/preview: realm metaos, dev / dev), which every slot signs in
#                    through at its own address, as cloudflared passes /auth/realms/metaos and /auth/resources on to it
#
# Namespace previews, on the factory node, whose agents' builds come first: a lower priority, so a preview is what's
# evicted if the node runs short, and a quota. Restricted: what runs here is what an agent wrote, so no root, no service
# account token but the manager's, no secrets but each slot's own database's and the dev buckets' key, and
# NetworkPolicy lets in only cloudflared and the Canvases.

locals {
  previews_namespace = kubernetes_namespace_v1.previews.metadata[0].name
  preview_slots      = [for n in range(1, var.preview_slots + 1) : tostring(n)]
  preview_hostnames  = { for n in local.preview_slots : n => "preview-${n}.ogrodje.dev" }

  preview_labels          = { "app.kubernetes.io/name" = "preview" }
  previews_keycloak_label = { "app.kubernetes.io/name" = "keycloak" }
  previews_manager_labels = { "app.kubernetes.io/name" = "previews-manager" }

  keycloak_image = "quay.io/keycloak/keycloak:26.8.1"
  postgres_image = "postgres:18-alpine"

  # Every container here: restricted's
  restricted = {
    allow_privilege_escalation = false
    drop                       = ["ALL"]
  }
}

resource "kubernetes_namespace_v1" "previews" {
  metadata {
    name = "previews"
    labels = {
      "pod-security.kubernetes.io/enforce" = "restricted"
      "pod-security.kubernetes.io/warn"    = "restricted"
    }
  }
}

resource "kubernetes_priority_class_v1" "previews" {
  metadata {
    name = "previews"
  }
  value             = -100
  preemption_policy = "Never"
  description       = "Elysium's previews: below the factory's agents, so they're what goes if the node runs short"
}

resource "kubernetes_resource_quota_v1" "previews" {
  metadata {
    name      = "previews"
    namespace = local.previews_namespace
  }
  spec {
    hard = {
      "requests.memory" = "3Gi"
      # A slot's pod is 2.25 GiB at most, Keycloak's 1.25 and the manager's 0.25
      "limits.memory" = "${2 + ceil(var.preview_slots * 2.5)}Gi"
      "pods"          = "10"
    }
  }
}

# --- The volume: each slot's build and database (slot-N/build, slot-N/data), and the manager's leases ---------------

resource "kubernetes_persistent_volume_claim_v1" "previews" {
  metadata {
    name      = "previews"
    namespace = local.previews_namespace
  }
  spec {
    access_modes       = ["ReadWriteOnce"]
    storage_class_name = var.storage_class
    resources {
      requests = { storage = "10Gi" }
    }
  }
  wait_until_bound = false
}

# --- The manager -----------------------------------------------------------------------------------------------------

resource "random_password" "previews_token" {
  length  = 40
  special = false
}

resource "kubernetes_secret_v1" "previews_manager" {
  metadata {
    name      = "previews-manager"
    namespace = local.previews_namespace
  }
  data = {
    PREVIEWS_TOKEN = random_password.previews_token.result
  }
}

resource "kubernetes_config_map_v1" "previews_manager" {
  metadata {
    name      = "previews-manager"
    namespace = local.previews_namespace
  }
  data = {
    "manager.ts" = file("${path.module}/../../../elysium/previews/manager.ts")
  }
}

resource "kubernetes_service_account_v1" "previews_manager" {
  metadata {
    name      = "previews-manager"
    namespace = local.previews_namespace
  }
}

# Scale the slots and read their pods and logs: nothing else, and only here
resource "kubernetes_role_v1" "previews_manager" {
  metadata {
    name      = "previews-manager"
    namespace = local.previews_namespace
  }
  rule {
    api_groups     = ["apps"]
    resources      = ["deployments"]
    resource_names = [for n in local.preview_slots : "preview-${n}"]
    verbs          = ["get", "patch"]
  }
  rule {
    api_groups = [""]
    resources  = ["pods", "pods/log"]
    verbs      = ["get", "list"]
  }
}

resource "kubernetes_role_binding_v1" "previews_manager" {
  metadata {
    name      = "previews-manager"
    namespace = local.previews_namespace
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role_v1.previews_manager.metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.previews_manager.metadata[0].name
    namespace = local.previews_namespace
  }
}

resource "kubernetes_deployment_v1" "previews_manager" {
  metadata {
    name      = "previews-manager"
    namespace = local.previews_namespace
    labels    = local.previews_manager_labels
  }

  spec {
    replicas = 1
    strategy {
      type = "Recreate"
    }
    selector {
      match_labels = local.previews_manager_labels
    }

    template {
      metadata {
        labels = local.previews_manager_labels
        annotations = {
          "ogrodje.si/source" = sha256(kubernetes_config_map_v1.previews_manager.data["manager.ts"])
        }
      }

      spec {
        service_account_name = kubernetes_service_account_v1.previews_manager.metadata[0].name
        priority_class_name  = kubernetes_priority_class_v1.previews.metadata[0].name
        node_selector        = { "ogrodje.si/pool" = local.pool }

        toleration {
          key      = "ogrodje.si/pool"
          operator = "Equal"
          value    = local.pool
          effect   = "NoSchedule"
        }

        # Postgres' user in the slots (alpine's 70), so what it makes there is the manager's to delete
        security_context {
          run_as_non_root        = true
          run_as_user            = 70
          run_as_group           = 70
          fs_group               = 70
          fs_group_change_policy = "OnRootMismatch"
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        container {
          name    = "manager"
          image   = "node:26.13.0-slim"
          command = ["node", "/app/manager.ts"]

          env {
            name  = "NAMESPACE"
            value = local.previews_namespace
          }
          env {
            name = "SLOTS"
            value = jsonencode([for n in local.preview_slots : {
              n        = tonumber(n)
              url      = "https://${local.preview_hostnames[n]}"
              internal = "http://preview-${n}.${local.previews_namespace}.svc.cluster.local:3000"
            }])
          }
          env {
            name  = "IDLE_HOURS"
            value = "24"
          }
          # Kubernetes' API, whose certificate is the cluster's own
          env {
            name  = "NODE_EXTRA_CA_CERTS"
            value = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
          }
          env_from {
            secret_ref {
              name = kubernetes_secret_v1.previews_manager.metadata[0].name
            }
          }

          port {
            name           = "http"
            container_port = 8080
          }

          resources {
            requests = { cpu = "20m", memory = "64Mi" }
            limits   = { memory = "256Mi" }
          }

          security_context {
            allow_privilege_escalation = local.restricted.allow_privilege_escalation
            run_as_non_root            = true
            read_only_root_filesystem  = true
            capabilities {
              drop = local.restricted.drop
            }
          }

          volume_mount {
            name       = "app"
            mount_path = "/app"
            read_only  = true
          }
          volume_mount {
            name       = "previews"
            mount_path = "/previews"
          }

          readiness_probe {
            http_get {
              path = "/healthz"
              port = "http"
            }
            period_seconds = 10
          }
        }

        volume {
          name = "app"
          config_map {
            name = kubernetes_config_map_v1.previews_manager.metadata[0].name
          }
        }
        volume {
          name = "previews"
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim_v1.previews.metadata[0].name
          }
        }
      }
    }
  }
  wait_for_rollout = false
}

resource "kubernetes_service_v1" "previews_manager" {
  metadata {
    name      = "manager"
    namespace = local.previews_namespace
  }
  spec {
    selector = local.previews_manager_labels
    port {
      name        = "http"
      port        = 8080
      target_port = "http"
    }
  }
}

# --- The previews' Keycloak ------------------------------------------------------------------------------------------

resource "random_password" "previews_keycloak_admin" {
  length  = 32
  special = false
}

# Its admin, for `just keycloak apply preview` through a port-forward: its console is never routed
resource "kubernetes_secret_v1" "previews_keycloak" {
  metadata {
    name      = "keycloak"
    namespace = local.previews_namespace
  }
  data = {
    KC_BOOTSTRAP_ADMIN_USERNAME = "admin"
    KC_BOOTSTRAP_ADMIN_PASSWORD = random_password.previews_keycloak_admin.result
  }
}

# Its database, a file (start-dev's dev-file): the realm and the sessions, all of it made up
resource "kubernetes_persistent_volume_claim_v1" "previews_keycloak" {
  metadata {
    name      = "keycloak"
    namespace = local.previews_namespace
  }
  spec {
    access_modes       = ["ReadWriteOnce"]
    storage_class_name = var.storage_class
    resources {
      requests = { storage = "1Gi" }
    }
  }
  wait_until_bound = false
}

resource "kubernetes_deployment_v1" "previews_keycloak" {
  metadata {
    name      = "keycloak"
    namespace = local.previews_namespace
    labels    = local.previews_keycloak_label
  }

  spec {
    replicas = 1
    strategy {
      type = "Recreate"
    }
    selector {
      match_labels = local.previews_keycloak_label
    }

    template {
      metadata {
        labels = local.previews_keycloak_label
      }

      spec {
        automount_service_account_token = false
        priority_class_name             = kubernetes_priority_class_v1.previews.metadata[0].name
        node_selector                   = { "ogrodje.si/pool" = local.pool }

        toleration {
          key      = "ogrodje.si/pool"
          operator = "Equal"
          value    = local.pool
          effect   = "NoSchedule"
        }

        security_context {
          run_as_non_root        = true
          run_as_user            = 1000
          run_as_group           = 0
          fs_group               = 1000
          fs_group_change_policy = "OnRootMismatch"
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        # As compose.yml's keycloak-preview was: its hostname from each request (cloudflared passes the preview's, and
        # Cloudflare's X-Forwarded-Proto), so each slot's tokens have the slot's issuer
        container {
          name  = "keycloak"
          image = local.keycloak_image
          args  = ["start-dev"]

          env {
            name  = "KC_DB"
            value = "dev-file"
          }
          env {
            name  = "KC_HTTP_RELATIVE_PATH"
            value = "/auth"
          }
          env {
            name  = "KC_PROXY_HEADERS"
            value = "xforwarded"
          }
          env {
            name  = "KC_HEALTH_ENABLED"
            value = "true"
          }
          env_from {
            secret_ref {
              name = kubernetes_secret_v1.previews_keycloak.metadata[0].name
            }
          }

          port {
            name           = "http"
            container_port = 8080
          }
          port {
            name           = "management"
            container_port = 9000
          }

          resources {
            requests = { cpu = "100m", memory = "512Mi" }
            limits   = { memory = "1280Mi" }
          }

          security_context {
            allow_privilege_escalation = local.restricted.allow_privilege_escalation
            run_as_non_root            = true
            capabilities {
              drop = local.restricted.drop
            }
          }

          volume_mount {
            name       = "data"
            mount_path = "/opt/keycloak/data/h2"
          }

          startup_probe {
            http_get {
              path = "/auth/health/ready"
              port = "management"
            }
            period_seconds    = 5
            failure_threshold = 60
          }
          readiness_probe {
            http_get {
              path = "/auth/health/ready"
              port = "management"
            }
            period_seconds = 10
          }
        }

        volume {
          name = "data"
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim_v1.previews_keycloak.metadata[0].name
          }
        }
      }
    }
  }
  wait_for_rollout = false
}

resource "kubernetes_service_v1" "previews_keycloak" {
  metadata {
    name      = "keycloak"
    namespace = local.previews_namespace
  }
  spec {
    selector = local.previews_keycloak_label
    port {
      name        = "http"
      port        = 8080
      target_port = "http"
    }
  }
}

# --- The slots -------------------------------------------------------------------------------------------------------

resource "random_password" "preview_db" {
  for_each = toset(local.preview_slots)
  length   = 32
  special  = false
}

resource "kubernetes_secret_v1" "preview_db" {
  for_each = toset(local.preview_slots)
  metadata {
    name      = "preview-${each.key}-db"
    namespace = local.previews_namespace
  }
  data = {
    POSTGRES_PASSWORD = random_password.preview_db[each.key].result
  }
}

# A slot: its Deployment stays at no pods until the manager scales it for a build, whose ID it puts on the pod
resource "kubernetes_deployment_v1" "preview" {
  for_each = toset(local.preview_slots)

  metadata {
    name      = "preview-${each.key}"
    namespace = local.previews_namespace
    labels    = merge(local.preview_labels, { "ogrodje.si/slot" = each.key })
  }

  spec {
    replicas = 0
    strategy {
      type = "Recreate"
    }
    selector {
      match_labels = merge(local.preview_labels, { "ogrodje.si/slot" = each.key })
    }

    template {
      metadata {
        labels = merge(local.preview_labels, { "ogrodje.si/slot" = each.key })
      }

      spec {
        automount_service_account_token  = false
        enable_service_links             = false
        priority_class_name              = kubernetes_priority_class_v1.previews.metadata[0].name
        node_selector                    = { "ogrodje.si/pool" = local.pool }
        termination_grace_period_seconds = 20

        toleration {
          key      = "ogrodje.si/pool"
          operator = "Equal"
          value    = local.pool
          effect   = "NoSchedule"
        }

        # Postgres' user (alpine's 70) for all of them, so its data on the volume is the manager's to clear
        security_context {
          run_as_non_root        = true
          run_as_user            = 70
          run_as_group           = 70
          fs_group               = 70
          fs_group_change_policy = "OnRootMismatch"
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        # The build, out of what the manager put on the volume
        init_container {
          name    = "unpack"
          image   = "busybox:1.37.0"
          command = ["sh", "-c", "tar -xzf /build/build.tar.gz -C /app"]
          resources {
            requests = { cpu = "50m", memory = "32Mi" }
            limits   = { memory = "128Mi" }
          }
          security_context {
            allow_privilege_escalation = local.restricted.allow_privilege_escalation
            run_as_non_root            = true
            read_only_root_filesystem  = true
            capabilities {
              drop = local.restricted.drop
            }
          }
          volume_mount {
            name       = "previews"
            mount_path = "/build"
            sub_path   = "slot-${each.key}/build"
            read_only  = true
          }
          volume_mount {
            name       = "app"
            mount_path = "/app"
          }
        }

        # Its own Postgres, a sidecar that's up before MetaOS starts, on localhost only
        init_container {
          name           = "postgres"
          image          = local.postgres_image
          restart_policy = "Always"
          args           = ["-c", "listen_addresses=127.0.0.1", "-c", "fsync=off"]
          env {
            name  = "POSTGRES_USER"
            value = "metaos"
          }
          env {
            name  = "POSTGRES_DB"
            value = "metaos"
          }
          env {
            name  = "PGDATA"
            value = "/data/pgdata"
          }
          env_from {
            secret_ref {
              name = kubernetes_secret_v1.preview_db[each.key].metadata[0].name
            }
          }
          resources {
            requests = { cpu = "50m", memory = "64Mi" }
            limits   = { memory = "256Mi" }
          }
          security_context {
            allow_privilege_escalation = local.restricted.allow_privilege_escalation
            run_as_non_root            = true
            capabilities {
              drop = local.restricted.drop
            }
          }
          volume_mount {
            name       = "previews"
            mount_path = "/data"
            sub_path   = "slot-${each.key}/data"
          }
          startup_probe {
            exec {
              command = ["pg_isready", "-h", "127.0.0.1", "-U", "metaos", "-d", "metaos"]
            }
            period_seconds    = 2
            failure_threshold = 60
          }
        }

        # MetaOS, as preview.sh ran it: none of the integrations' secrets, Temporal or Unleash, so it changes nothing
        # real; the dev buckets, which are for this, with their key
        container {
          name    = "api"
          image   = "gcr.io/distroless/java25-debian13:nonroot"
          command = ["/usr/bin/java", "-XX:MaxRAMPercentage=70", "-jar", "/app/metaos.jar"]

          dynamic "env" {
            for_each = {
              HOME                = "/tmp"
              LANG                = "C.UTF-8"
              POSTGRES_HOST       = "127.0.0.1"
              POSTGRES_PORT       = "5432"
              POSTGRES_DB         = "metaos"
              POSTGRES_USER       = "metaos"
              SERVER_PORT         = "8080"
              KEYCLOAK_ISSUER_URI = "https://${local.preview_hostnames[each.key]}/auth/realms/metaos"
              # Keycloak's keys from inside the cluster; the issuer it checks is the preview's own address
              SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI = "http://keycloak.${local.previews_namespace}.svc.cluster.local:8080/auth/realms/metaos/protocol/openid-connect/certs"
            }
            content {
              name  = env.key
              value = env.value
            }
          }
          env_from {
            secret_ref {
              name = kubernetes_secret_v1.preview_db[each.key].metadata[0].name
            }
          }
          # set_secrets.sh's: GOOGLE_STORAGE_KEY and the dev buckets' names
          env_from {
            secret_ref {
              name     = "previews-storage"
              optional = true
            }
          }

          port {
            name           = "api"
            container_port = 8080
          }

          resources {
            requests = { cpu = "250m", memory = "512Mi" }
            limits   = { memory = "1536Mi" }
          }

          security_context {
            allow_privilege_escalation = local.restricted.allow_privilege_escalation
            run_as_non_root            = true
            capabilities {
              drop = local.restricted.drop
            }
          }

          volume_mount {
            name       = "app"
            mount_path = "/app"
            read_only  = true
          }

          startup_probe {
            http_get {
              path = "/readyz"
              port = "api"
            }
            period_seconds    = 3
            failure_threshold = 100
          }
          readiness_probe {
            http_get {
              path = "/readyz"
              port = "api"
            }
            period_seconds = 10
          }
        }

        # The front-end, which passes the API's paths on to its MetaOS
        container {
          name    = "web"
          image   = "gcr.io/distroless/nodejs26-debian13:nonroot"
          command = ["/nodejs/bin/node", "/app/web/server/index.mjs"]

          dynamic "env" {
            for_each = {
              HOST                 = "0.0.0.0"
              PORT                 = "3000"
              NUXT_METAOS_URL      = "http://127.0.0.1:8080"
              NUXT_PUBLIC_ISSUER   = "https://${local.preview_hostnames[each.key]}/auth/realms/metaos"
              NUXT_PUBLIC_SITE_URL = "https://${local.preview_hostnames[each.key]}"
              NUXT_PUBLIC_MCP_URL  = "https://${local.preview_hostnames[each.key]}/mcp"
            }
            content {
              name  = env.key
              value = env.value
            }
          }

          port {
            name           = "web"
            container_port = 3000
          }

          resources {
            requests = { cpu = "50m", memory = "96Mi" }
            limits   = { memory = "384Mi" }
          }

          security_context {
            allow_privilege_escalation = local.restricted.allow_privilege_escalation
            run_as_non_root            = true
            capabilities {
              drop = local.restricted.drop
            }
          }

          volume_mount {
            name       = "app"
            mount_path = "/app"
            read_only  = true
          }

          readiness_probe {
            http_get {
              path = "/healthz"
              port = "web"
            }
            period_seconds = 5
          }
        }

        # preview/seed.sql, once MetaOS has made its schema, into a database that's new (the manager's marker)
        container {
          name  = "seed"
          image = local.postgres_image
          command = ["sh", "-c", <<-EOT
            if [ -f /data/seed ]; then
              until wget -q -O /dev/null http://127.0.0.1:8080/readyz; do sleep 2; done
              psql -v ON_ERROR_STOP=1 -q -h 127.0.0.1 -U metaos -d metaos -f /app/seed.sql && rm /data/seed &&
                echo "seeded from preview/seed.sql"
            fi
            exec sleep 2147483647
          EOT
          ]
          env {
            name = "PGPASSWORD"
            value_from {
              secret_key_ref {
                name = kubernetes_secret_v1.preview_db[each.key].metadata[0].name
                key  = "POSTGRES_PASSWORD"
              }
            }
          }
          resources {
            requests = { cpu = "10m", memory = "16Mi" }
            limits   = { memory = "128Mi" }
          }
          security_context {
            allow_privilege_escalation = local.restricted.allow_privilege_escalation
            run_as_non_root            = true
            read_only_root_filesystem  = true
            capabilities {
              drop = local.restricted.drop
            }
          }
          volume_mount {
            name       = "previews"
            mount_path = "/data"
            sub_path   = "slot-${each.key}/data"
          }
          volume_mount {
            name       = "app"
            mount_path = "/app"
            read_only  = true
          }
        }

        volume {
          name = "previews"
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim_v1.previews.metadata[0].name
          }
        }
        volume {
          name = "app"
          empty_dir {
            size_limit = "2Gi"
          }
        }
      }
    }
  }

  # The manager scales it, and annotates each build's pod
  lifecycle {
    ignore_changes = [spec[0].replicas, spec[0].template[0].metadata[0].annotations]
  }
  wait_for_rollout = false
}

resource "kubernetes_service_v1" "preview" {
  for_each = toset(local.preview_slots)
  metadata {
    name      = "preview-${each.key}"
    namespace = local.previews_namespace
  }
  spec {
    selector = merge(local.preview_labels, { "ogrodje.si/slot" = each.key })
    port {
      name        = "web"
      port        = 3000
      target_port = "web"
    }
  }
}

# --- Who reaches what ------------------------------------------------------------------------------------------------

# Kubernetes' API, for the manager: its address, as Calico sees it after the service's
data "kubernetes_endpoints_v1" "api" {
  metadata {
    name      = "kubernetes"
    namespace = "default"
  }
}

locals {
  factory_pods = {
    cloudflared = local.cloudflared_labels
    canvas      = local.canvas_labels
    bridge      = local.linear_bridge_labels
  }
}

resource "kubernetes_network_policy_v1" "previews_default_deny" {
  metadata {
    name      = "default-deny"
    namespace = local.previews_namespace
  }
  spec {
    pod_selector {}
    policy_types = ["Ingress", "Egress"]
  }
}

resource "kubernetes_network_policy_v1" "previews_dns" {
  metadata {
    name      = "dns"
    namespace = local.previews_namespace
  }
  spec {
    pod_selector {}
    policy_types = ["Egress"]
    egress {
      to {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = "kube-system" }
        }
        pod_selector {
          match_labels = { "k8s-app" = "kube-dns" }
        }
      }
      ports {
        protocol = "UDP"
        port     = "53"
      }
      ports {
        protocol = "TCP"
        port     = "53"
      }
    }
  }
}

# A slot: the people, through cloudflared, and the Canvases' agents, to check theirs; it reaches Keycloak's keys and
# Google's Cloud Storage, nothing else in the cluster
resource "kubernetes_network_policy_v1" "previews_slots" {
  metadata {
    name      = "slots"
    namespace = local.previews_namespace
  }
  spec {
    pod_selector {
      match_labels = local.preview_labels
    }
    policy_types = ["Ingress", "Egress"]

    ingress {
      dynamic "from" {
        for_each = { for k, v in local.factory_pods : k => v if k != "bridge" }
        content {
          namespace_selector {
            match_labels = { "kubernetes.io/metadata.name" = local.namespace }
          }
          pod_selector {
            match_labels = from.value
          }
        }
      }
      ports {
        protocol = "TCP"
        port     = "3000"
      }
    }

    egress {
      to {
        pod_selector {
          match_labels = local.previews_keycloak_label
        }
      }
      ports {
        protocol = "TCP"
        port     = "8080"
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

resource "kubernetes_network_policy_v1" "previews_keycloak" {
  metadata {
    name      = "keycloak"
    namespace = local.previews_namespace
  }
  spec {
    pod_selector {
      match_labels = local.previews_keycloak_label
    }
    policy_types = ["Ingress"]

    ingress {
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = local.namespace }
        }
        pod_selector {
          match_labels = local.cloudflared_labels
        }
      }
      from {
        pod_selector {
          match_labels = local.preview_labels
        }
      }
      ports {
        protocol = "TCP"
        port     = "8080"
      }
    }
  }
}

# The manager: the Canvases' agents (preview/preview.sh) and the bridge (pull requests closed); it reaches Kubernetes'
# API, for its slots
resource "kubernetes_network_policy_v1" "previews_manager" {
  metadata {
    name      = "manager"
    namespace = local.previews_namespace
  }
  spec {
    pod_selector {
      match_labels = local.previews_manager_labels
    }
    policy_types = ["Ingress", "Egress"]

    ingress {
      dynamic "from" {
        for_each = { for k, v in local.factory_pods : k => v if k != "cloudflared" }
        content {
          namespace_selector {
            match_labels = { "kubernetes.io/metadata.name" = local.namespace }
          }
          pod_selector {
            match_labels = from.value
          }
        }
      }
      ports {
        protocol = "TCP"
        port     = "8080"
      }
    }

    dynamic "egress" {
      for_each = data.kubernetes_endpoints_v1.api.subset
      content {
        dynamic "to" {
          for_each = egress.value.address
          content {
            ip_block {
              cidr = "${to.value.ip}/32"
            }
          }
        }
        dynamic "ports" {
          for_each = egress.value.port
          content {
            protocol = "TCP"
            port     = tostring(ports.value.port)
          }
        }
      }
    }
  }
}

# --- On the internet -------------------------------------------------------------------------------------------------

resource "cloudflare_zero_trust_access_policy" "previews" {
  account_id = local.cloudflare_account_id
  name       = "Elysium previews"
  decision   = "allow"
  include    = [for n in local.names : { email = { email = local.users[n].email } }]
}

resource "cloudflare_zero_trust_access_application" "previews" {
  account_id       = local.cloudflare_account_id
  name             = "Elysium previews"
  type             = "self_hosted"
  domain           = local.preview_hostnames[local.preview_slots[0]]
  destinations     = [for n in local.preview_slots : { type = "public", uri = local.preview_hostnames[n] }]
  session_duration = "24h"

  policies = [{
    id         = cloudflare_zero_trust_access_policy.previews.id
    precedence = 1
  }]
}

resource "cloudflare_dns_record" "preview" {
  for_each = toset(local.preview_slots)

  zone_id = local.ogrodje_dev_zone_id
  name    = local.preview_hostnames[each.key]
  type    = "CNAME"
  content = "${cloudflare_zero_trust_tunnel_cloudflared.factory.id}.cfargotunnel.com"
  proxied = true
  ttl     = 1
  comment = "Elysium: preview slot ${each.key}, behind Cloudflare Access (metaos: infra/elysium/factory)"
}

locals {
  # The tunnel's routes to the slots (access.tf): Keycloak's realm metaos and its resources to the previews' Keycloak,
  # never its console or realm master, and the rest to the slot's front-end; each only with Access' token
  preview_ingress = flatten([for n in local.preview_slots : [
    {
      hostname = local.preview_hostnames[n]
      path     = "^/auth/(realms/metaos|resources)/"
      service  = "http://keycloak.${local.previews_namespace}.svc.cluster.local:8080"
      origin_request = {
        access = {
          required  = true
          team_name = local.access_team
          aud_tag   = [cloudflare_zero_trust_access_application.previews.aud]
        }
      }
    },
    {
      hostname = local.preview_hostnames[n]
      service  = "http://preview-${n}.${local.previews_namespace}.svc.cluster.local:3000"
      origin_request = {
        access = {
          required  = true
          team_name = local.access_team
          aud_tag   = [cloudflare_zero_trust_access_application.previews.aud]
        }
      }
    },
  ]])
}
