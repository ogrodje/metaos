# MetaOS's names, proxied: Cloudflare holds their certificates (the cluster has none) and caches what may be cached

# The pages, and MetaOS's paths beside them (the Ingress tells them apart), on the node
resource "cloudflare_dns_record" "metaos" {
  zone_id = local.zone_id
  name    = "metaos.ogrodje.si"
  type    = "CNAME"
  content = "ogrodje-one.ogrodje.si"
  proxied = true
  ttl     = 1
}

# Keycloak, on the node
resource "cloudflare_dns_record" "metaos_auth" {
  zone_id = local.zone_id
  name    = "metaos-auth.ogrodje.si"
  type    = "CNAME"
  content = "ogrodje-one.ogrodje.si"
  proxied = true
  ttl     = 1
}

# The public bucket of the same name (../gcp), which Google picks by the Host header
resource "cloudflare_dns_record" "media" {
  zone_id = local.zone_id
  name    = "media.ogrodje.si"
  type    = "CNAME"
  content = "c.storage.googleapis.com"
  proxied = true
  ttl     = 1
}
