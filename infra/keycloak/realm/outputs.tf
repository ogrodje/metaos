# What ../import.sh needs to know to adopt an existing realm: what this manages by name, as Keycloak names it

output "registration_policies" {
  description = "The client registration policies, by key, each with its provider and sub type"
  value       = { for key, policy in local.registration_policies : key => { provider_id = policy.provider_id, sub_type = policy.sub_type } }
}

output "identity_providers" {
  description = "The sign-in providers there are, by alias"
  value       = keys(local.gated)
}

output "dev_user" {
  description = "Whether the realm has the dev user"
  value       = var.dev_user
}

# The agents' client secret, which Keycloak made (agents.tf): METAOS_AGENTS_CLIENT_SECRET, for agents/
output "agents_client_secret" {
  description = "metaos-agents' client secret"
  value       = keycloak_openid_client.metaos_agents.client_secret
  sensitive   = true
}
