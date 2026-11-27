# The Canvases (canvas/, ../README.md "Team and personal Canvases"): the team's at agent.ogrodje.dev, which everyone in
# FACTORY_USERS opens, and one of each person's own at agent-<name>.ogrodje.dev, which only they do. A Canvas' settings,
# MCP servers, secrets, conversations and automations are its own: in a person's, their own accounts (e.g. MetaOS'
# MCP server signed in as them); in the team's, service identities only. Each browser adds the ones it may open as
# backends, and switches between them.
#
# Who's who is FACTORY_USERS, from 1Password, never in this public repository: {"<name>": {"email": "<email>", "github":
# "<login>"}, ...}: their Linear and Access email and their GitHub login, whose requests the bridge runs in their Canvas.
# A name is a Canvas' and its address' (lowercase letters and digits). Adding someone: their entry, then
# `just infra elysium/factory apply` and `just canvas-settings`.

locals {
  # Every Canvas' pods, whoever's (canvas/ adds app.kubernetes.io/instance), for the NetworkPolicies
  canvas_labels = { "app.kubernetes.io/name" = "canvas" }

  users = jsondecode(var.factory_users)
  # The names aren't secret, the addresses are: so for_each over the one, and the other only where it's needed
  names = nonsensitive(sort(keys(local.users)))
  # The team's first
  canvas_names = concat(["team"], local.names)

  canvases = merge(
    {
      team = {
        k8s_name       = "canvas"
        hostname       = local.agent_hostname
        emails         = [for n in local.names : local.users[n].email]
        volume_size    = "20Gi"
        memory_request = "1536Mi"
        memory_limit   = "8Gi"
      }
    },
    {
      for n in local.names : n => {
        k8s_name       = "canvas-${n}"
        hostname       = "agent-${n}.ogrodje.dev"
        emails         = [local.users[n].email]
        volume_size    = "15Gi"
        memory_request = "1Gi"
        memory_limit   = "6Gi"
      }
    },
  )
}

resource "kubernetes_service_account_v1" "canvas" {
  metadata {
    name      = "canvas"
    namespace = local.namespace
  }

  # No access to the cluster: the agents run kubectl with nothing
  automount_service_account_token = false
}

module "canvas" {
  source   = "./canvas"
  for_each = toset(local.canvas_names)

  name                  = each.key
  k8s_name              = local.canvases[each.key].k8s_name
  hostname              = local.canvases[each.key].hostname
  emails                = local.canvases[each.key].emails
  namespace             = local.namespace
  pool                  = local.pool
  service_account       = kubernetes_service_account_v1.canvas.metadata[0].name
  storage_class         = var.storage_class
  volume_size           = local.canvases[each.key].volume_size
  memory_request        = local.canvases[each.key].memory_request
  memory_limit          = local.canvases[each.key].memory_limit
  cloudflare_account_id = local.cloudflare_account_id
}

# The one Canvas there was is the team's now, with its volume
moved {
  from = kubernetes_stateful_set_v1.canvas
  to   = module.canvas["team"].kubernetes_stateful_set_v1.canvas
}

moved {
  from = kubernetes_service_v1.canvas
  to   = module.canvas["team"].kubernetes_service_v1.canvas
}

moved {
  from = cloudflare_zero_trust_access_policy.factory_team
  to   = module.canvas["team"].cloudflare_zero_trust_access_policy.canvas
}

moved {
  from = cloudflare_zero_trust_access_application.agent
  to   = module.canvas["team"].cloudflare_zero_trust_access_application.canvas
}

moved {
  from = cloudflare_dns_record.agent
  to   = cloudflare_dns_record.canvas["team"]
}
