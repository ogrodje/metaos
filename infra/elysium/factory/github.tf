# GitHub's side of the bridge (linear-bridge.tf, ../README.md "The bridge"): an ogrodje organization webhook for what
# happens on pull requests (comments, reviews, review comments), to hooks.ogrodje.dev/github/webhook (access.tf),
# signed with a secret only it and the bridge have. The bridge acts only on a mention of @elysium-og, or changes
# requested on a pull request Elysium opened, by someone in FACTORY_USERS: the repositories are public.
#
# Applied with ELYSIUM_TERRAFORM_GITHUB_TOKEN, by CI and the justfile alike: an owner's fine-grained token for ogrodje
# with organization webhooks only. Only that, never gh's: GitHub shows a webhook only to the kind of token that made it
# (an OAuth app's, gh's, sees only its own), so another would find none and make a second one.

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
  # pull_request for its closing: its branch's preview is done with (previews.tf)
  events = ["issue_comment", "pull_request", "pull_request_review", "pull_request_review_comment"]
}
