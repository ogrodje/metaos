#!/usr/bin/env bash
# Canvas' own settings that hold credentials (../README.md, "Elysium in GitHub and Linear"), which Canvas keeps,
# encrypted, on its volume rather than in Kubernetes: so neither Terraform nor set_secrets.sh, but its API. Idempotent:
# run it again after a token changes, and monthly for Linear's, which lasts 30 days. New conversations get the change;
# running ones keep what they started with.
#
#   Secrets (Settings > Secrets), which Canvas exports to every agent as environment variables:
#     GITHUB_TOKEN                          elysium-og's token, which git and gh push, open and merge PRs with
#     GIT_AUTHOR_NAME, GIT_COMMITTER_NAME   Elysium
#     GIT_AUTHOR_EMAIL, GIT_COMMITTER_EMAIL elysium-og's noreply address, so GitHub attributes the commits to it
#   MCP servers (Customize > MCP):
#     github  GitHub's hosted server, as elysium-og
#     linear  Linear's hosted server, as the app Elysium, with a new client-credentials token each run
#
# From the environment: secretspec.toml's ELYSIUM_GITHUB_TOKEN and ELYSIUM_LINEAR_CLIENT_SECRET, which the devenv shell
# has (or `secretspec run -P default -- infra/elysium/factory/canvas_settings.sh`). Into Canvas through its API, called
# inside its pod with its own session key, so neither leaves the cluster but for kubectl's own connection.
set -euo pipefail

: "${ELYSIUM_GITHUB_TOKEN:?ELYSIUM_GITHUB_TOKEN: secretspec set ELYSIUM_GITHUB_TOKEN}"
: "${ELYSIUM_LINEAR_CLIENT_SECRET:?ELYSIUM_LINEAR_CLIENT_SECRET: secretspec set ELYSIUM_LINEAR_CLIENT_SECRET}"

export KUBECONFIG="${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}"
LINEAR_CLIENT_ID=1fad2d2ffaf2bf8a19da97b44790cdff # OAuth app "Elysium" in Linear's workspace ogrodje
GITHUB_LOGIN=elysium-og
GITHUB_ID=361901225

# canvas METHOD PATH: the JSON on stdin to Canvas' API, as seen from inside its pod; prints the HTTP status
canvas() {
  kubectl exec -i -n factory canvas-0 -- sh -c '
    curl -sS -o /dev/null -w "%{http_code}" -X "$1" "http://127.0.0.1:8000/api$2" \
      -H "Content-Type: application/json" \
      -H "X-Session-API-Key: $(cat /home/openhands/.openhands/agent-canvas/api-key.txt)" \
      --data-binary @-' sh "$1" "$2"
}

secret() { # NAME VALUE DESCRIPTION
  local status
  status=$(jq -n --arg n "$1" --arg v "$2" --arg d "$3" '{name: $n, value: $v, description: $d}' | canvas PUT /settings/secrets)
  [[ $status == 2* ]] || { echo "secret $1: HTTP $status" >&2; exit 1; }
  echo "secret $1"
}

mcp() { # NAME URL TOKEN: updated if Canvas has it, added if not
  local body status
  body=$(jq -n --arg u "$2" --arg t "$3" \
    '{url: $u, transport: "http", auth: {strategy: "bearer", value: $t}, enabled: true}')
  status=$(canvas PATCH "/settings/mcp/$1" <<<"$body")
  [[ $status == 404 ]] && status=$(canvas POST "/settings/mcp/$1" <<<"$body")
  [[ $status == 2* ]] || { echo "MCP server $1: HTTP $status" >&2; exit 1; }
  echo "MCP server $1"
}

noreply="$GITHUB_ID+$GITHUB_LOGIN@users.noreply.github.com"
secret GITHUB_TOKEN "$ELYSIUM_GITHUB_TOKEN" \
  "GitHub token of $GITHUB_LOGIN (Elysium): push, pull requests, issues and workflows on all of ogrodje"
secret GIT_AUTHOR_NAME Elysium "Commits are Elysium's"
secret GIT_COMMITTER_NAME Elysium "Commits are Elysium's"
secret GIT_AUTHOR_EMAIL "$noreply" "$GITHUB_LOGIN's noreply address, so GitHub attributes commits to it"
secret GIT_COMMITTER_EMAIL "$noreply" "$GITHUB_LOGIN's noreply address, so GitHub attributes commits to it"

mcp github https://api.githubcopilot.com/mcp/ "$ELYSIUM_GITHUB_TOKEN"

# Linear's client-credentials token: the app as actor, 30 days, no refresh
linear=$(curl -sS https://api.linear.app/oauth/token \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "scope=read,write" \
  --data-urlencode client_id="$LINEAR_CLIENT_ID" \
  --data-urlencode client_secret="$ELYSIUM_LINEAR_CLIENT_SECRET" | jq -r '.access_token // empty')
[ -n "$linear" ] || { echo "Linear gave no token: is ELYSIUM_LINEAR_CLIENT_SECRET current?" >&2; exit 1; }
mcp linear https://mcp.linear.app/mcp "$linear"
echo "Linear's token lasts until $(date -v+30d +%Y-%m-%d 2>/dev/null || date -d +30days +%Y-%m-%d): run this again before then"
