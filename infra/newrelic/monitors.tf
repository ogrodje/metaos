# Uptime checks, every 5 minutes from Frankfurt, London and Milan, which the free plan doesn't count: the pages, MetaOS
# and Keycloak answering. "MetaOS is down" (alerts.tf) opens when one fails.

resource "newrelic_synthetics_monitor" "pages" {
  locations_public = ["EU_CENTRAL_1", "EU_SOUTH_1", "EU_WEST_2"]
  name             = "MetaOS pages"
  period           = "EVERY_5_MINUTES"
  status           = "ENABLED"
  type             = "SIMPLE"
  uri              = "https://metaos.ogrodje.si/healthz"
  verify_ssl       = false
}

resource "newrelic_synthetics_monitor" "api" {
  locations_public = ["EU_CENTRAL_1", "EU_SOUTH_1", "EU_WEST_2"]
  name             = "MetaOS API"
  period           = "EVERY_5_MINUTES"
  status           = "ENABLED"
  type             = "SIMPLE"
  uri              = "https://metaos.ogrodje.si/readyz"
  verify_ssl       = false
}

resource "newrelic_synthetics_monitor" "sign_in" {
  locations_public = ["EU_CENTRAL_1", "EU_SOUTH_1", "EU_WEST_2"]
  name             = "MetaOS sign-in"
  period           = "EVERY_5_MINUTES"
  status           = "ENABLED"
  type             = "SIMPLE"
  uri              = "https://metaos-auth.ogrodje.si/realms/metaos/.well-known/openid-configuration"
  verify_ssl       = false
}
