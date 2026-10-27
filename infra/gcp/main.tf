# Google Cloud project ogrodje-metaos: Cloud Storage for both environments (storage/), the bucket every stack's state
# is in, and GitHub Actions' way in to it (ci.tf). Applied by hand by an owner of the project (README.md), with gcloud's
# application default credentials: `gcloud auth application-default login`.
terraform {
  required_version = ">= 1.9"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.8"
    }
  }

  # In the bucket below, which this stack made with its state on disk, then moved it into (README.md)
  backend "gcs" {
    bucket = "ogrodje-metaos-tfstate"
    prefix = "gcp"
  }
}

locals {
  project = "ogrodje-metaos"
}

# API calls count against this project, whatever quota project the credentials have
provider "google" {
  project               = local.project
  region                = "europe-west3"
  billing_project       = local.project
  user_project_override = true
}

resource "google_project_service" "used" {
  for_each = toset([
    # Terraform's own way to the project, which it needs to manage the others
    "cloudresourcemanager.googleapis.com",
    "storage.googleapis.com",
    "iam.googleapis.com",
    # Workload Identity Federation (ci.tf): GitHub's tokens exchanged for the service account's
    "sts.googleapis.com",
    "iamcredentials.googleapis.com",
    # Speech-to-Text, which transcribes episodes' audio from the private buckets (Transcription.kt)
    "speech.googleapis.com",
  ])

  service            = each.value
  disable_on_destroy = false
}

module "storage_dev" {
  source = "./storage"

  env          = "dev"
  media_bucket = "ogrodje-metaos-public-dev"
  # Nuxt's dev server, the app serving the built front-end, and a second front-end, e.g. a worktree's
  origins    = ["http://localhost:5173", "http://localhost:8080", "http://localhost:5175"]
  production = false
}

module "storage_prod" {
  source = "./storage"

  env          = "prod"
  media_bucket = "media.ogrodje.si"
  origins      = ["https://metaos.ogrodje.si"]
  production   = true
}

# Every stack's state, each under its own prefix. Versioned, as a state is the only record of what Terraform manages;
# a state has secrets in it, so nobody but the project's owners and CI reads it.
resource "google_storage_bucket" "tfstate" {
  name                        = "ogrodje-metaos-tfstate"
  location                    = "EUROPE-WEST3"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  labels                      = { app = "metaos", purpose = "terraform" }

  versioning {
    enabled = true
  }

  # The 20 latest versions of each state, and the older ones for 90 days
  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      num_newer_versions         = 20
      days_since_noncurrent_time = 90
      with_state                 = "ARCHIVED"
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}
