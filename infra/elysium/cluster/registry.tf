# The cluster's images: ams.vultrcr.com/<name>, private. Free up to 10 GB (plan start_up); the registry on the tailnet
# (registry.folk-decibel.ts.net) stays MetaOS's until it moves here, as Vultr's nodes can't reach the tailnet.
resource "vultr_container_registry" "elysium" {
  name   = "ogrodje"
  region = local.region
  plan   = "start_up"
  public = false
}
