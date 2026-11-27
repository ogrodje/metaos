output "litellm_master_key" {
  description = "Agent Canvas' key to LiteLLM, for its LLM settings (../README.md)"
  value       = kubernetes_secret_v1.litellm.data["LITELLM_MASTER_KEY"]
  sensitive   = true
}
