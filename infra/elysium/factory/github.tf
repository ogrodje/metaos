# GitHub's side of the bridge (linear-bridge.tf, ../README.md "The bridge"): an ogrodje organization webhook for what
# happens on pull requests (comments, reviews, review comments), to hooks.ogrodje.dev/github/webhook (access.tf),
# signed with a secret only it and the bridge have. The bridge acts only on a mention of @elysium-og, or changes
# requested on a pull request Elysium opened, by someone in FACTORY_USERS: the repositories are public.
#
# Applied as you, an owner of ogrodje: the justfile hands the provider gh's token, which needs admin:org_hook
# (`gh auth refresh -h github.com -s admin:org_hook`).

resource "random_password" "github_webhook" {
  length  = 40
  special = false
}

resource "github_organization_webhook" "bridge" {
  configuration {
    url          = "https://${local.hooks_hostname}/github/webhook"
    content_type = "json"
    secret       = random_password.github_webhook.result
    insecure_ssl = false
  }

  active = true
  events = ["issue_comment", "pull_request_review", "pull_request_review_comment"]
}
