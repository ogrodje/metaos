# The factory on the internet, through a Cloudflare Tunnel (cloudflared.tf), all of it in the Cloudflare account that
# has ogrodje.dev (Oto's own), not ogrodje.si's, and with that account's token, ELYSIUM_CLOUDFLARE_API_TOKEN
# (secretspec.toml), which the justfile hands the provider:
#
#   agent.ogrodje.dev, agent-<name>.ogrodje.dev  the Canvases (canvas.tf), each behind Cloudflare Access for its people
#   preview-N.ogrodje.dev                        the previews' slots (previews.tf), behind Access for the team
#   hooks.ogrodje.dev/linear/webhook, /github/webhook
#                                                the bridge (linear-bridge.tf), for Linear's and GitHub's signed
#                                                deliveries: no Access, as they can't sign in; the signature is the check
#
# cloudflared checks each Canvas' Access token as well, so a request that got past Access some other way is still
# turned away.

locals {
  cloudflare_account_id = "388e06cab3ce097b777fb26dae49b09f" # the one that has ogrodje.dev
  ogrodje_dev_zone_id   = "5f7626e0f5ca7c4dd6e836dd859782aa" # ogrodje.dev
  access_team           = "ogrodje-dev"                      # Zero Trust's team name: ogrodje-dev.cloudflareaccess.com
  agent_hostname        = "agent.ogrodje.dev"
  hooks_hostname        = "hooks.ogrodje.dev"
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
    ingress = concat(
      # Each Canvas, only with Access' token for its own application
      [for name in local.canvas_names : {
        hostname = module.canvas[name].hostname
        service  = module.canvas[name].service_url
        origin_request = {
          access = {
            required  = true
            team_name = local.access_team
            aud_tag   = [module.canvas[name].access_aud]
          }
        }
      }],
      # The previews' slots (previews.tf)
      local.preview_ingress,
      [
        {
          hostname = local.hooks_hostname
          path     = "^/(linear|github)/webhook$"
          service  = "http://${kubernetes_service_v1.linear_bridge.metadata[0].name}.${local.namespace}.svc.cluster.local:8080"
        },
        # Anything else that reaches the tunnel
        { service = "http_status:404" },
      ],
    )
  }
}

resource "cloudflare_dns_record" "canvas" {
  for_each = toset(local.canvas_names)

  zone_id = local.ogrodje_dev_zone_id
  name    = module.canvas[each.key].hostname
  type    = "CNAME"
  content = "${cloudflare_zero_trust_tunnel_cloudflared.factory.id}.cfargotunnel.com"
  proxied = true
  ttl     = 1
  comment = "Elysium factory: the ${each.key} Canvas, behind Cloudflare Access (metaos: infra/elysium/factory)"
}

resource "cloudflare_dns_record" "hooks" {
  zone_id = local.ogrodje_dev_zone_id
  name    = local.hooks_hostname
  type    = "CNAME"
  content = "${cloudflare_zero_trust_tunnel_cloudflared.factory.id}.cfargotunnel.com"
  proxied = true
  ttl     = 1
  comment = "Elysium factory: webhooks, e.g. Linear's to its bridge (metaos: infra/elysium/factory)"
}
