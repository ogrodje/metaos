# The zone's Single Redirects, all of them, as a phase's rules are one ruleset for the whole zone: another site's go
# here too. The token needs Single Redirect: Edit (../README.md)
resource "cloudflare_ruleset" "redirects" {
  zone_id = local.zone_id
  kind    = "zone"
  name    = "default"
  phase   = "http_request_dynamic_redirect"

  rules = [
    {
      # www.ogrodje.si to ogrodje.si, the same path and query, for good (OG-59), as Netlify did
      ref         = "www-to-apex"
      description = "www: to ogrodje.si"
      expression  = "(http.host eq \"www.ogrodje.si\")"
      action      = "redirect"
      action_parameters = {
        from_value = {
          status_code           = 301
          target_url            = { expression = "concat(\"https://ogrodje.si\", http.request.uri.path)" }
          preserve_query_string = true
        }
      }
      enabled = true
    },
    {
      # metaos.ogrodje.si, MetaOS's address until OG-59: anything on it goes to ogrodje.si's home page, for good, its
      # path and query not kept, /mcp and /graphql too, as nothing's kept for the old address (OG-60)
      ref         = "metaos-to-apex"
      description = "metaos: to ogrodje.si"
      expression  = "(http.host eq \"metaos.ogrodje.si\")"
      action      = "redirect"
      action_parameters = {
        from_value = {
          status_code           = 301
          target_url            = { value = "https://ogrodje.si/" }
          preserve_query_string = false
        }
      }
      enabled = true
    },
  ]
}
