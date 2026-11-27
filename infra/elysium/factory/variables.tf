variable "access_emails" {
  description = "Who Cloudflare Access lets into agent.ogrodje.dev, comma-separated: FACTORY_ACCESS_EMAILS, from 1Password (secretspec.toml), never in this public repository"
  type        = string
  sensitive   = true

  validation {
    condition     = length([for e in split(",", var.access_emails) : e if trimspace(e) != ""]) > 0
    error_message = "At least one email: secretspec set FACTORY_ACCESS_EMAILS."
  }
}

variable "storage_class" {
  # VKE's classes: vultr-block-storage(-retain) and vultr-vfs-storage(-retain) are NVMe, the -hdd ones HDD
  description = "StorageClass of Agent Canvas' volume: Vultr's NVMe block storage"
  type        = string
  default     = "vultr-block-storage"
}
