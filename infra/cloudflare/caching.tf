# How Cloudflare serves MetaOS's names. Each phase's rules are one ruleset for the whole zone, so these are every Cache
# Rule and Configuration Rule ogrodje.si has: another site's go here too.

locals {
  # MetaOS's own paths, which the Ingress sends to it rather than to the pages (k8s/base): never cached, whatever
  # they'd say, so that an answer for one signed-in user can't reach another
  metaos_paths = ["/graphql", "/events", "/mcp", "/.well-known", "/koofr", "/dropbox"]
}

resource "cloudflare_ruleset" "cache" {
  zone_id = local.zone_id
  kind    = "zone"
  name    = "default"
  phase   = "http_request_cache_settings"

  rules = [
    {
      # media's files as their Cache-Control says (pictures: a year, immutable), and nothing without one, e.g. errors
      ref         = "c5123650b90043d792f4b4f970202f38"
      description = "media: cache"
      expression  = "(http.host eq \"media.ogrodje.si\")"
      action      = "set_cache_settings"
      action_parameters = {
        cache       = true
        edge_ttl    = { mode = "bypass_by_default" }
        browser_ttl = { mode = "respect_origin" }
      }
      enabled = true
    },
    {
      # The pages, which Cloudflare doesn't cache without a rule, as they're HTML: as their Cache-Control says, and
      # nothing without one. The pages anyone may see say s-maxage=60, stale-while-revalidate=600, so each is
      # rendered once a minute, not once a visit (routeRules, fe/nuxt.config.ts); the team's pages say no-store.
      ref         = "metaos-pages"
      description = "metaos: pages"
      expression  = "(http.host eq \"metaos.ogrodje.si\" and not (${join(" or ", [for path in local.metaos_paths : "starts_with(http.request.uri.path, \"${path}\")"])}))"
      action      = "set_cache_settings"
      action_parameters = {
        cache       = true
        edge_ttl    = { mode = "bypass_by_default" }
        browser_ttl = { mode = "respect_origin" }
      }
      enabled = true
    },
  ]
}

resource "cloudflare_ruleset" "config" {
  zone_id = local.zone_id
  kind    = "zone"
  name    = "default"
  phase   = "http_config_settings"

  rules = [
    {
      # Google's certificate is for *.storage.googleapis.com, which Full (strict) would refuse
      ref               = "5c2e8a7660fd459c84129472defe8616"
      description       = "media: SSL Full"
      expression        = "(http.host eq \"media.ogrodje.si\")"
      action            = "set_config"
      action_parameters = { ssl = "full" }
      enabled           = true
    },
  ]
}

# Smart Tiered Caching: Cloudflare's data centres ask one near the origin, e.g. the bucket, instead of each asking it
resource "cloudflare_tiered_cache" "smart" {
  zone_id = local.zone_id
  value   = "on"
}
