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

variable "personal_canvases" {
  description = "A Canvas of each person's own in FACTORY_USERS, beside the team's (canvas.tf). Off: everyone works in the team's, Linear's and GitHub's requests too"
  type        = bool
  default     = false
}

variable "team_volume_size" {
  description = "The team Canvas' volume: settings, conversations, the agents' checkouts. It grows in place, never shrinks"
  type        = string
  default     = "50Gi"
}

variable "canvas_image" {
  description = "The Canvases' image: Elysium's (elysium/canvas/Dockerfile), which CI pushes to the Vultr registry; `just canvas-image` says its tag"
  type        = string
  default     = "ghcr.io/openhands/agent-canvas:1.46.0"
}

variable "preview_slots" {
  description = "How many previews can run at once (previews.tf): preview-1.ogrodje.dev and on, each up to 2.25 GiB"
  type        = number
  default     = 2
}
