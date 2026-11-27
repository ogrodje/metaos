# GitHub Actions' way in (.github/workflows/terraform.yml), with no key to keep: a workflow of ogrodje/metaos exchanges
# its GitHub token for the service account's, which may only read and write the states. What each stack changes is
# done with that stack's own credentials (GitHub's environment production), and this stack, which can give anyone
# anything in the project, is applied by hand only.

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"

  depends_on = [google_project_service.used]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub Actions"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
  }
  # Workflows of this repository only, whoever else uses GitHub's tokens
  attribute_condition = "assertion.repository == 'ogrodje/metaos'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "terraform_ci" {
  account_id   = "metaos-terraform-ci"
  display_name = "MetaOS Terraform (CI)"
  description  = "GitHub Actions' Terraform: the states in gs://${google_storage_bucket.tfstate.name} (infra/gcp/ci.tf)"
}

resource "google_service_account_iam_member" "terraform_ci_github" {
  service_account_id = google_service_account.terraform_ci.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/ogrodje/metaos"
}

# Reading a state, and writing it and its lock: every state but Elysium's (infra/elysium), whose stacks are applied by
# hand and whose states hold what opens the cluster to an admin, e.g. LiteLLM's key and the Postgres' credentials. The
# condition sees an object as projects/_/buckets/<bucket>/objects/<name>; listing the bucket is the bucket's, so it
# still lists them
resource "google_storage_bucket_iam_member" "terraform_ci_state" {
  bucket = google_storage_bucket.tfstate.name
  role   = "roles/storage.objectUser"
  member = google_service_account.terraform_ci.member

  condition {
    title       = "not-elysium"
    description = "Not Elysium's states, which only owners apply (infra/elysium)"
    expression  = "!resource.name.startsWith(\"projects/_/buckets/${google_storage_bucket.tfstate.name}/objects/elysium/\")"
  }
}

# Elysium's factory (.github/workflows/elysium-factory.yml), its own account: only a job in GitHub's environment
# elysium, which a person approves before it starts (infra/elysium/README.md, CI), gets it, by the subject GitHub gives
# such a job, so a pull request's own workflow can't; and it reads and writes Elysium's states only
resource "google_service_account" "elysium_ci" {
  account_id   = "elysium-terraform-ci"
  display_name = "Elysium Terraform (CI)"
  description  = "GitHub Actions' Terraform for infra/elysium/factory, from environment elysium only (infra/gcp/ci.tf)"
}

resource "google_service_account_iam_member" "elysium_ci_github" {
  service_account_id = google_service_account.elysium_ci.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principal://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/subject/repo:ogrodje/metaos:environment:elysium"
}

resource "google_storage_bucket_iam_member" "elysium_ci_state" {
  bucket = google_storage_bucket.tfstate.name
  role   = "roles/storage.objectUser"
  member = google_service_account.elysium_ci.member
  condition {
    title       = "elysium"
    description = "Elysium's states only (infra/elysium), and listing the bucket, which is the bucket's"
    expression  = "resource.name.startsWith(\"projects/_/buckets/${google_storage_bucket.tfstate.name}/objects/elysium/\") || resource.name == \"projects/_/buckets/${google_storage_bucket.tfstate.name}\""
  }
}

output "ci_workload_identity_provider" {
  description = "For google-github-actions/auth's workload_identity_provider"
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "ci_service_account" {
  description = "For google-github-actions/auth's service_account"
  value       = google_service_account.terraform_ci.email
}

output "elysium_ci_service_account" {
  description = "For elysium-factory.yml's google-github-actions/auth"
  value       = google_service_account.elysium_ci.email
}
