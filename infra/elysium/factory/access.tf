# agent.ogrodje.dev: Agent Canvas for the team, through a Cloudflare Tunnel, with Cloudflare Access in front. All of it
# in the Cloudflare account that has ogrodje.dev (Oto's own), not ogrodje.si's, and with that
# account's token, ELYSIUM_CLOUDFLARE_API_TOKEN (secretspec.toml), which the justfile hands the provider.
#
# Who gets in: the emails in FACTORY_ACCESS_EMAILS (1Password, never in this public repository), each signing in with a
# one-time code Cloudflare emails them. Then Canvas asks for its own session key, the one `just canvas` copies: Access
# says who, the key what (README.md, "The factory"). cloudflared checks Access' token as well, so a request that got
# past Access some other way is still turned away.

locals {
  cloudflare_account_id = "388e06cab3ce097b777fb26dae49b09f" # the one that has ogrodje.dev
  ogrodje_dev_zone_id   = "5f7626e0f5ca7c4dd6e836dd859782aa" # ogrodje.dev
  access_team           = "ogrodje-dev"                      # Zero Trust's team name: ogrodje-dev.cloudflareaccess.com
  agent_hostname        = "agent.ogrodje.dev"

  access_emails = toset([for e in split(",", var.access_emails) : lower(trimspace(e)) if trimspace(e) != ""])
}

resource "cloudflare_zero_trust_tunnel_cloudflared" "factory" {
  account_id = local.cloudflare_account_id
  name       = "elysium-factory"
  # Its routes here (below), not in a file beside cloudflared
  config_src = "cloudflare"
}

# What cloudflared runs with (cloudflared.tf)
data "cloudflare_zero_trust_tunnel_cloudflared_token" "factory" {
  account_id = local.cloudflare_account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.factory.id
}

resource "cloudflare_zero_trust_tunnel_cloudflared_config" "factory" {
  account_id = local.cloudflare_account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.factory.id

  config = {
    ingress = [
      {
        hostname = local.agent_hostname
        service  = "http://${kubernetes_service_v1.canvas.metadata[0].name}.${local.namespace}.svc.cluster.local:8000"
        origin_request = {
          # Only requests that carry Access' token for this application
          access = {
            required  = true
            team_name = local.access_team
            aud_tag   = [cloudflare_zero_trust_access_application.agent.aud]
          }
        }
      },
      # Anything else that reaches the tunnel
      { service = "http_status:404" },
    ]
  }
}

resource "cloudflare_dns_record" "agent" {
  zone_id = local.ogrodje_dev_zone_id
  name    = local.agent_hostname
  type    = "CNAME"
  content = "${cloudflare_zero_trust_tunnel_cloudflared.factory.id}.cfargotunnel.com"
  proxied = true
  ttl     = 1
  comment = "Elysium's software factory, Agent Canvas, behind Cloudflare Access (metaos: infra/elysium/factory)"
}

resource "cloudflare_zero_trust_access_policy" "factory_team" {
  account_id = local.cloudflare_account_id
  name       = "Elysium factory team"
  decision   = "allow"
  include    = [for e in local.access_emails : { email = { email = e } }]
}

resource "cloudflare_zero_trust_access_application" "agent" {
  account_id       = local.cloudflare_account_id
  name             = "Elysium factory: Agent Canvas"
  type             = "self_hosted"
  domain           = local.agent_hostname
  destinations     = [{ type = "public", uri = local.agent_hostname }]
  session_duration = "24h"

  policies = [{
    id         = cloudflare_zero_trust_access_policy.factory_team.id
    precedence = 1
  }]
}
