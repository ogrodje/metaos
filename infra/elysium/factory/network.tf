# What the factory's pods may reach: nothing, but what each needs. The agents run whatever they're asked to, so they
# reach the internet (GitHub, the package registries) and LiteLLM, never the rest of the cluster, the VPC or Vultr's
# metadata service, where a node's secrets are.

locals {
  # The cluster (pods, services, nodes, the VPC), the tailnet's addresses and the metadata service
  private_ranges = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16"]
}

resource "kubernetes_network_policy_v1" "default_deny" {
  metadata {
    name      = "default-deny"
    namespace = local.namespace
  }

  spec {
    pod_selector {}
    policy_types = ["Ingress", "Egress"]
  }
}

# The cluster's DNS, for every pod here
resource "kubernetes_network_policy_v1" "dns" {
  metadata {
    name      = "dns"
    namespace = local.namespace
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

resource "kubernetes_network_policy_v1" "canvas" {
  metadata {
    name      = "canvas"
    namespace = local.namespace
  }

  spec {
    pod_selector {
      match_labels = local.canvas_labels
    }
    policy_types = ["Ingress", "Egress"]

    # cloudflared (access.tf) and the Linear bridge only, and `just canvas`, whose port-forward arrives on the pod's own
    # localhost: so the session key in Canvas' page reaches no one else
    ingress {
      from {
        pod_selector {
          match_labels = local.cloudflared_labels
        }
      }
      from {
        pod_selector {
          match_labels = local.linear_bridge_labels
        }
      }
      ports {
        protocol = "TCP"
        port     = "8000"
      }
    }

    egress {
      to {
        pod_selector {
          match_labels = local.litellm_labels
        }
      }
      ports {
        protocol = "TCP"
        port     = "4000"
      }
    }

    # The internet, on any port: git over SSH, registries and whatever else a build reaches for
    egress {
      to {
        ip_block {
          cidr   = "0.0.0.0/0"
          except = local.private_ranges
        }
      }
    }
  }
}

resource "kubernetes_network_policy_v1" "litellm" {
  metadata {
    name      = "litellm"
    namespace = local.namespace
  }

  spec {
    pod_selector {
      match_labels = local.litellm_labels
    }
    policy_types = ["Ingress", "Egress"]

    ingress {
      from {
        pod_selector {
          match_labels = local.canvas_labels
        }
      }
      ports {
        protocol = "TCP"
        port     = "4000"
      }
    }

    # The providers' APIs, over HTTPS
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

# cloudflared: Cloudflare's edge, on 7844 (QUIC, or HTTP/2 over TCP) and 443 (its API), and Canvas
resource "kubernetes_network_policy_v1" "cloudflared" {
  metadata {
    name      = "cloudflared"
    namespace = local.namespace
  }

  spec {
    pod_selector {
      match_labels = local.cloudflared_labels
    }
    policy_types = ["Ingress", "Egress"]

    egress {
      to {
        ip_block {
          cidr   = "0.0.0.0/0"
          except = local.private_ranges
        }
      }
      ports {
        protocol = "UDP"
        port     = "7844"
      }
      ports {
        protocol = "TCP"
        port     = "7844"
      }
      ports {
        protocol = "TCP"
        port     = "443"
      }
    }

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
        pod_selector {
          match_labels = local.linear_bridge_labels
        }
      }
      ports {
        protocol = "TCP"
        port     = "8080"
      }
    }
  }
}

# The Linear bridge: Linear's deliveries from cloudflared; the Canvases' API, and Linear's over HTTPS
resource "kubernetes_network_policy_v1" "linear_bridge" {
  metadata {
    name      = "linear-bridge"
    namespace = local.namespace
  }

  spec {
    pod_selector {
      match_labels = local.linear_bridge_labels
    }
    policy_types = ["Ingress", "Egress"]

    ingress {
      from {
        pod_selector {
          match_labels = local.cloudflared_labels
        }
      }
      ports {
        protocol = "TCP"
        port     = "8080"
      }
    }

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
