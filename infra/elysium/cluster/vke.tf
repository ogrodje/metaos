# The cluster: Vultr runs its control plane (free, not HA), and its nodes are two pools of one node each, platform and
# factory, which split the budget 40/60 (../README.md).
#
# A pool's plan can't be changed in place: the provider ignores a new one. A bigger node is a new pool, the workloads
# moved to it, then the old pool removed. The cluster's own pool, platform, can't be removed at all, so a bigger
# platform node means a new cluster.

resource "vultr_vpc" "elysium" {
  region      = local.region
  description = "elysium"
  # Outside 10.0.0.0/8, where the cluster's pod and service ranges are (cluster_subnet, service_subnet)
  v4_subnet      = "192.168.42.0"
  v4_subnet_mask = 24
}

resource "vultr_kubernetes" "elysium" {
  region  = local.region
  label   = "elysium"
  version = "v1.37.2+1"
  vpc_id  = vultr_vpc.elysium.id
  # A firewall group of the cluster's own, which Vultr keeps in step with its nodes: only the NodePorts and the
  # control plane's traffic reach them. Nothing is published through a NodePort: the way in is Cloudflare's tunnel
  # and Tailscale, both of which dial out
  enable_firewall = true

  # platform: what every app on the cluster shares, and MetaOS. 4 vCPU, 8 GB, $48 a month
  node_pools {
    label         = "elysium-platform"
    plan          = "vhp-4c-8gb-amd"
    node_quantity = 1

    labels {
      key   = "ogrodje.si/pool"
      value = "platform"
    }
  }
}

# factory: OpenHands' agents and LiteLLM (../factory), and nothing else: tainted, so only what tolerates it runs here.
# 6 vCPU, 16 GB, $80 a month
resource "vultr_kubernetes_node_pools" "factory" {
  cluster_id    = vultr_kubernetes.elysium.id
  label         = "elysium-factory"
  tag           = "factory"
  plan          = "vc2-6c-16gb"
  node_quantity = 1

  labels {
    key   = "ogrodje.si/pool"
    value = "factory"
  }

  taints {
    key    = "ogrodje.si/pool"
    value  = "factory"
    effect = "NoSchedule"
  }
}
