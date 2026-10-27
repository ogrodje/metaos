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

# Reading a state, and writing it and its lock
resource "google_storage_bucket_iam_member" "terraform_ci_state" {
  bucket = google_storage_bucket.tfstate.name
  role   = "roles/storage.objectUser"
  member = google_service_account.terraform_ci.member
}

output "ci_workload_identity_provider" {
  description = "For google-github-actions/auth's workload_identity_provider"
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "ci_service_account" {
  description = "For google-github-actions/auth's service_account"
  value       = google_service_account.terraform_ci.email
}
