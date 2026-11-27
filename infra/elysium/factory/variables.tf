variable "factory_users" {
  description = "Who has a Canvas, and gets into the team's: {\"<name>\": {\"email\": \"<email>\", \"github\": \"<login>\"}, ...}. FACTORY_USERS, from 1Password (secretspec.toml), never in this public repository"
  type        = string
  sensitive   = true

  validation {
    condition = alltrue([
      for name, user in jsondecode(var.factory_users) :
      can(regex("^[a-z][a-z0-9]{0,19}$", name)) && can(regex("@", user.email)) && can(regex("^[A-Za-z0-9-]+$", user.github))
    ]) && length(jsondecode(var.factory_users)) > 0
    error_message = "FACTORY_USERS: a JSON object of names (lowercase letters and digits, from a letter) to {email, github}, at least one."
  }
}

variable "storage_class" {
  # VKE's classes: vultr-block-storage(-retain) and vultr-vfs-storage(-retain) are NVMe, the -hdd ones HDD
  description = "StorageClass of Agent Canvas' volume: Vultr's NVMe block storage"
  type        = string
  default     = "vultr-block-storage"
}
