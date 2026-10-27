# tflint for every stack in infra/ (`just tflint`, and CI on pull requests): Terraform's own recommended rules, and
# Google's provider's, for gcp. The other providers have no ruleset.
config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "google" {
  enabled = true
  version = "0.41.0"
  source  = "github.com/terraform-linters/tflint-ruleset-google"
}
