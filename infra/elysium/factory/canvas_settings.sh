#!/usr/bin/env bash
# Every Canvas' settings that Elysium gives it (../README.md, "Team and personal Canvases"), which Canvas keeps,
# encrypted, on its volume rather than in Kubernetes: so neither Terraform nor set_secrets.sh, but its API. The same in
# each, the team's and everyone's own; what a person connects themselves it leaves alone. Idempotent: after a new Canvas, after a token changes, and monthly, for Linear's, which lasts 30 days. New
# conversations get the change; running ones keep what they started with.
#
#   LLM (Customize > LLM):          claude-sonnet-5-5, Claude Sonnet 5.5 through LiteLLM, with its key; the agent
#                                   profile using it
#   Secrets (Settings > Secrets),   GITHUB_TOKEN, elysium-og's, which git and gh push, open and merge PRs with;
#   exported to every agent:        GIT_AUTHOR_*/GIT_COMMITTER_*: Elysium, with elysium-og's noreply address;
#                                   PREVIEWS_URL/PREVIEWS_TOKEN: the previews' manager, for MetaOS' preview/preview.sh
#   MCP servers (Customize > MCP):  github, GitHub's hosted server as elysium-og; linear, Linear's as the app Elysium,
#                                   with a new client-credentials token each run; metaos, MetaOS' (Ogrodje's) as
#                                   Keycloak's service account elysium, through the bridge, which holds its token
#
# From the environment: secretspec.toml's ELYSIUM_GITHUB_TOKEN and ELYSIUM_LINEAR_CLIENT_SECRET, which the devenv shell
# has (or `secretspec run -P default -- infra/elysium/factory/canvas_settings.sh`). Into each Canvas through its API,
# called inside its pod with its own session key, so nothing leaves the cluster but for kubectl's own connection.
set -euo pipefail

: "${ELYSIUM_GITHUB_TOKEN:?ELYSIUM_GITHUB_TOKEN: secretspec set ELYSIUM_GITHUB_TOKEN}"
: "${ELYSIUM_LINEAR_CLIENT_SECRET:?ELYSIUM_LINEAR_CLIENT_SECRET: secretspec set ELYSIUM_LINEAR_CLIENT_SECRET}"

export KUBECONFIG="${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}"
NS=factory
LINEAR_CLIENT_ID=1fad2d2ffaf2bf8a19da97b44790cdff # OAuth app "Elysium" in Linear's workspace ogrodje
GITHUB_LOGIN=elysium-og
GITHUB_ID=361901225
LLM_PROFILE=claude-sonnet-5-5

# api POD METHOD PATH: the JSON on stdin to the Canvas' API, as seen from inside its pod; prints the body, then the
# HTTP status on a line of its own
api() {
  kubectl exec -i -n "$NS" "$1" -- sh -c '
    key="${OH_SESSION_API_KEYS_0:-$(cat /home/openhands/.openhands/agent-canvas/api-key.txt)}"
    curl -sS -w "\n%{http_code}" -X "$1" "http://127.0.0.1:8000/api$2" \
      -H "Content-Type: application/json" -H "X-Session-API-Key: $key" --data-binary @-' sh "$2" "$3"
}

# call POD METHOD PATH WHAT: api(), failing on a status that isn't 2xx; prints the body
call() {
  local out status
  out=$(api "$1" "$2" "$3")
  status=${out##*$'\n'}
  [[ $status == 2* ]] || { echo "$1: $4: HTTP $status ${out%$'\n'*}" >&2; exit 1; }
  printf '%s' "${out%$'\n'*}"
}

secret() { # POD NAME VALUE DESCRIPTION
  jq -n --arg n "$2" --arg v "$3" --arg d "$4" '{name: $n, value: $v, description: $d}' |
    call "$1" PUT /settings/secrets "secret $2" >/dev/null
}

mcp() { # POD NAME URL [TOKEN]: updated if the Canvas has it, added if not; without a token, none
  local body out
  body=$(jq -n --arg u "$3" --arg t "${4:-}" '{url: $u, transport: "http", enabled: true,
    auth: (if $t == "" then {strategy: "none"} else {strategy: "bearer", value: $t} end)}')
  out=$(api "$1" PATCH "/settings/mcp/$2" <<<"$body")
  if [[ ${out##*$'\n'} == 404 ]]; then
    call "$1" POST "/settings/mcp/$2" "MCP server $2" <<<"$body" >/dev/null
  elif [[ ${out##*$'\n'} != 2* ]]; then
    echo "$1: MCP server $2: HTTP ${out##*$'\n'}" >&2
    exit 1
  fi
}

litellm_key=$(kubectl get secret -n "$NS" litellm -o jsonpath='{.data.LITELLM_MASTER_KEY}' | base64 -d)
previews_token=$(kubectl get secret -n previews previews-manager -o jsonpath='{.data.PREVIEWS_TOKEN}' | base64 -d)
noreply="$GITHUB_ID+$GITHUB_LOGIN@users.noreply.github.com"

# Linear's client-credentials token: the app as actor, 30 days, no refresh; one for every Canvas. Its scopes are
# Elysium's install, the same in every token (elysium/linear-bridge: LINEAR_SCOPES)
linear=$(curl -sS https://api.linear.app/oauth/token \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "scope=read,write,app:assignable,app:mentionable" \
  --data-urlencode client_id="$LINEAR_CLIENT_ID" \
  --data-urlencode client_secret="$ELYSIUM_LINEAR_CLIENT_SECRET" | jq -r '.access_token // empty')
[ -n "$linear" ] || { echo "Linear gave no token: is ELYSIUM_LINEAR_CLIENT_SECRET current?" >&2; exit 1; }

pods=$(kubectl get pods -n "$NS" -l app.kubernetes.io/name=canvas --field-selector=status.phase=Running \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
[ -n "$pods" ] || { echo "No Canvas is running" >&2; exit 1; }

for pod in $pods; do
  # The LLM: the profile, active, and an agent profile using it (a new Canvas' server makes "default" itself)
  jq -n --arg k "$litellm_key" '{llm: {
      model: "litellm_proxy/anthropic/claude-sonnet-5-5",
      base_url: "http://litellm.factory.svc.cluster.local:4000",
      api_key: $k, usage_id: "default"}}' | call "$pod" POST "/profiles/$LLM_PROFILE" "LLM profile" >/dev/null
  call "$pod" POST "/profiles/$LLM_PROFILE/activate" "LLM profile activation" </dev/null >/dev/null
  active=$(call "$pod" GET /settings "settings" </dev/null | jq -r '.active_agent_profile_id // empty')
  if [ -z "$active" ]; then
    id=$(call "$pod" GET /agent-profiles "agent profiles" </dev/null |
      jq -r '[.profiles[]? | select(.name == "default") | (.id // .profile.id)][0] // empty')
    if [ -z "$id" ]; then
      id=$(jq -n --arg l "$LLM_PROFILE" '{schema_version: 4, agent_kind: "openhands", name: "default", llm_profile_ref: $l}' |
        call "$pod" POST /agent-profiles/default "agent profile" | jq -r '.id // .profile.id // empty')
    fi
    call "$pod" POST "/agent-profiles/$id/activate" "agent profile activation" </dev/null >/dev/null
  fi

  secret "$pod" GITHUB_TOKEN "$ELYSIUM_GITHUB_TOKEN" \
    "GitHub token of $GITHUB_LOGIN (Elysium): push, pull requests, issues and workflows on all of ogrodje"
  secret "$pod" GIT_AUTHOR_NAME Elysium "Commits are Elysium's"
  secret "$pod" GIT_COMMITTER_NAME Elysium "Commits are Elysium's"
  secret "$pod" GIT_AUTHOR_EMAIL "$noreply" "$GITHUB_LOGIN's noreply address, so GitHub attributes commits to it"
  secret "$pod" GIT_COMMITTER_EMAIL "$noreply" "$GITHUB_LOGIN's noreply address, so GitHub attributes commits to it"
  secret "$pod" PREVIEWS_URL http://manager.previews.svc.cluster.local:8080 \
    "Elysium's previews' manager, for MetaOS' preview/preview.sh (up, ls, logs, down)"
  secret "$pod" PREVIEWS_TOKEN "$previews_token" "The previews' manager's token, with PREVIEWS_URL"

  mcp "$pod" github https://api.githubcopilot.com/mcp/ "$ELYSIUM_GITHUB_TOKEN"
  mcp "$pod" linear https://mcp.linear.app/mcp "$linear"
  mcp "$pod" metaos http://linear-bridge.factory.svc.cluster.local:8080/mcp/metaos
  echo "$pod: LLM, secrets, MCP servers github, linear and metaos"
done
echo "Linear's token lasts until $(date -v+30d +%Y-%m-%d 2>/dev/null || date -d +30days +%Y-%m-%d): run this again before then"
