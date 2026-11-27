# What's handy to know, none of it secret. The kubeconfig isn't an output: `just elysium-kubeconfig` asks Vultr for
# it, and the other stacks find the cluster by its label (../README.md)

output "cluster_id" {
  description = "The VKE cluster's ID"
  value       = vultr_kubernetes.elysium.id
}

output "endpoint" {
  description = "The control plane's address"
  value       = vultr_kubernetes.elysium.endpoint
}

output "registry" {
  description = "Where images are pushed and pulled"
  value       = "${local.region}.vultrcr.com/${vultr_container_registry.elysium.name}"
}
