# MetaOS's names, proxied: Cloudflare holds their certificates (the cluster has none) and caches what may be cached

# MetaOS's address until OG-59, which Cloudflare redirects to ogrodje.si (redirects.tf), so nothing of it reaches the
# node. Proxied, as the redirect is Cloudflare's
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

# ogrodje.si itself: MetaOS, on the node, since it took over from Netlify's old site (OG-59). An A record of the node's
# address (ogrodje-one.ogrodje.si's, which changes with it), not a CNAME as the others: Netlify's was an A record, and
# a record that changes its type is deleted and made again, which leaves the name without an answer that resolvers
# would remember. Proxied, so Cloudflare answers with its own address anyway. Rolling back: 75.2.60.5, Netlify's
resource "cloudflare_dns_record" "apex" {
  zone_id = local.zone_id
  name    = "ogrodje.si"
  type    = "A"
  content = "78.47.94.128"
  proxied = true
  ttl     = 1
}

# www, which Cloudflare redirects to ogrodje.si (redirects.tf), so nothing of it reaches the node. Proxied, as the
# redirect is Cloudflare's. Rolling back: CNAME ogrodje.netlify.app
resource "cloudflare_dns_record" "www" {
  zone_id = local.zone_id
  name    = "www.ogrodje.si"
  type    = "CNAME"
  content = "ogrodje-one.ogrodje.si"
  proxied = true
  ttl     = 1
}
