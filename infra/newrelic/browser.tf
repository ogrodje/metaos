# The front-end's browser agent (fe/src/newrelic.ts), production's and development's. The agent's own settings, in
# NUXT_PUBLIC_NEW_RELIC_* (k8s/base/deployment-web.yaml, .envrc), come from these: an app's ID and its license key.

resource "newrelic_browser_application" "web" {
  cookies_enabled             = true
  distributed_tracing_enabled = true
  loader_type                 = "SPA"
  name                        = "MetaOS web"
}

resource "newrelic_browser_application" "web_dev" {
  cookies_enabled             = true
  distributed_tracing_enabled = true
  loader_type                 = "SPA"
  name                        = "MetaOS web (dev)"
}
