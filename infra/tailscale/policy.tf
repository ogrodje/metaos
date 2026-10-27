# Who and what may reach what on the tailnet: tags and their owners, grants, SSH. It's the whole tailnet's, so other
# projects' rules (boo-run, bongbong, ...) are here too, beside MetaOS's (CI's tag:ci reaching the cluster's operator,
# patreon-gateway's way out).
locals {
  policy_file = "${path.module}/policy.hujson"
  policy      = fileexists(local.policy_file) ? file(local.policy_file) : null
}

resource "tailscale_acl" "policy" {
  # Never applied without the file: the precondition stops the plan first, and says why
  acl = coalesce(local.policy, "{}")

  lifecycle {
    precondition {
      condition     = local.policy != null
      error_message = "infra/tailscale/policy.hujson isn't here: it's private, so not in git. `just tailscale-policy` gets the tailnet's from Tailscale."
    }
  }
}
