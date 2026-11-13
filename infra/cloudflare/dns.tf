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

# ogrodje.si itself, and www: Netlify's old site, until MetaOS takes them over (OG-59). Proxied already, so Cloudflare
# holds their certificates, as for MetaOS's names
resource "cloudflare_dns_record" "apex" {
  zone_id = local.zone_id
  name    = "ogrodje.si"
  type    = "A"
  content = "75.2.60.5" # Netlify's load balancer
  proxied = true
  ttl     = 1
}

resource "cloudflare_dns_record" "www" {
  zone_id = local.zone_id
  name    = "www.ogrodje.si"
  type    = "CNAME"
  content = "ogrodje.netlify.app"
  proxied = true
  ttl     = 1
}
