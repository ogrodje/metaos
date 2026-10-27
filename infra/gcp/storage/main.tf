# One environment's Cloud Storage (Storage.kt): a private bucket for MetaOS's own files, a public one for what everyone
# may see, and a service account that can use the files in those two and nothing else, not even their settings.

variable "env" {
  description = "dev or prod"
  type        = string
}

variable "media_bucket" {
  description = "The public bucket's name. Production's is its domain, which Cloudflare serves from it by the Host header"
  type        = string
}

variable "origins" {
  description = "The front-end's origins, which upload and download with signed URLs straight from the browser"
  type        = list(string)
}

variable "production" {
  description = "Whether it's production's: deleted and replaced files stay 30 days, Autoclass, no tests' files"
  type        = bool
}

terraform {
  required_providers {
    google = {
      source = "hashicorp/google"
    }
  }
}

locals {
  labels = { app = "metaos", env = var.env }

  # Unfinished multipart uploads are billed until they're aborted; uploads from the browser MetaOS didn't take
  # (Artwork.kt), and tests' leftovers (under test/, in development only), go after a day
  expire = concat(
    [{ action = "AbortIncompleteMultipartUpload", age = 7, prefixes = [] }],
    [{ action = "Delete", age = 1, prefixes = ["uploads/"] }],
    var.production ? [] : [{ action = "Delete", age = 1, prefixes = ["test/"] }],
  )
}

# Private: MetaOS hands out signed URLs instead
resource "google_storage_bucket" "files" {
  name                        = "ogrodje-metaos-${var.env}"
  location                    = "EUROPE-WEST3"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  labels                      = local.labels

  soft_delete_policy {
    retention_duration_seconds = var.production ? 30 * 86400 : 0
  }

  dynamic "autoclass" {
    for_each = var.production ? [1] : []
    content {
      enabled                = true
      terminal_storage_class = "NEARLINE"
    }
  }

  # Resumable uploads from the browser: it starts one with a signed POST, reads the session from Location and PUTs the
  # file there, in chunks if it's big. response_header is both the headers it may send and those it may read.
  cors {
    origin          = var.origins
    method          = ["GET", "HEAD", "PUT", "POST"]
    response_header = ["Content-Type", "Content-Range", "Range", "ETag", "Location", "x-goog-resumable", "x-goog-if-generation-match"]
    max_age_seconds = 3600
  }

  dynamic "lifecycle_rule" {
    for_each = local.expire
    content {
      action {
        type = lifecycle_rule.value.action
      }
      condition {
        age            = lifecycle_rule.value.age
        matches_prefix = lifecycle_rule.value.prefixes
      }
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

# Public: a file for anyone who has its path, and no listing. No CORS: <img> doesn't need it, and Cloudflare would
# hand one origin's answer to everyone. A bucket named after a domain needs whoever creates it to be a verified owner
# of the domain in Search Console.
resource "google_storage_bucket" "media" {
  name                        = var.media_bucket
  location                    = "EUROPE-WEST3"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "inherited"
  labels                      = merge(local.labels, { access = "public" })

  soft_delete_policy {
    retention_duration_seconds = var.production ? 30 * 86400 : 0
  }

  dynamic "autoclass" {
    for_each = var.production ? [1] : []
    content {
      enabled                = true
      terminal_storage_class = "NEARLINE"
    }
  }

  dynamic "lifecycle_rule" {
    for_each = local.expire
    content {
      action {
        type = lifecycle_rule.value.action
      }
      condition {
        age            = lifecycle_rule.value.age
        matches_prefix = lifecycle_rule.value.prefixes
      }
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

# The legacy reader has storage.objects.get only: no listing
resource "google_storage_bucket_iam_member" "media_public" {
  bucket = google_storage_bucket.media.name
  role   = "roles/storage.legacyObjectReader"
  member = "allUsers"
}

# MetaOS, with its key (GOOGLE_STORAGE_KEY, PROD_GOOGLE_STORAGE_KEY), which isn't Terraform's: `just storage-key`
resource "google_service_account" "storage" {
  account_id   = "metaos-storage-${var.env}"
  display_name = "MetaOS storage (${var.env})"
  description  = "MetaOS's files in gs://${google_storage_bucket.files.name} and gs://${google_storage_bucket.media.name} (infra/gcp)"
}

# Create, read, replace and delete files
resource "google_storage_bucket_iam_member" "storage" {
  for_each = {
    files = google_storage_bucket.files.name
    media = google_storage_bucket.media.name
  }

  bucket = each.value
  role   = "roles/storage.objectUser"
  member = google_service_account.storage.member
}

output "service_account" {
  value = google_service_account.storage.email
}
