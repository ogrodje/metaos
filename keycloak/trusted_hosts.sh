#!/usr/bin/env bash
# Which hosts MCP clients may register themselves (DCR) for, set through Keycloak's admin API - for realms imported
# before metaos-realm.json had them, as Keycloak imports the file only once.
#
# The realm's Trusted Hosts policy refuses a registration whose redirect URIs are on any other host. localhost and
# 127.0.0.1 are for clients on your computer (Claude Code, MCP Inspector), claude.ai and claude.com for Claude's
# connectors, chatgpt.com for ChatGPT's (https://chatgpt.com/connector_platform_oauth_redirect). Whoever registers still
# has to sign in as an approved user to get a token MetaOS takes.
#
# Adds the missing ones and keeps any already there. Idempotent.
#
# From the environment:
#   KEYCLOAK_URL, KEYCLOAK_ADMIN_USERNAME, KEYCLOAK_ADMIN_PASSWORD  see approval_gate.sh
set -euo pipefail

HOSTS=(localhost 127.0.0.1 claude.ai claude.com chatgpt.com)

source "$(dirname "$0")/approval_gate.sh"

policy=$(api GET "/components?type=org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy" |
  jq -c '[.[] | select(.providerId == "trusted-hosts" and .subType == "anonymous")] | first // empty')
[ -n "$policy" ] || { echo "No Trusted Hosts policy for anonymous registrations in realm $REALM" >&2; exit 1; }
id=$(jq -r .id <<<"$policy")

api PUT "/components/$id" "$(jq -c --args \
  '.config["trusted-hosts"] = ((.config["trusted-hosts"] // []) + $ARGS.positional | unique)' "${HOSTS[@]}" <<<"$policy")"
api GET "/components/$id" | jq -r '"Trusted hosts: \(.config["trusted-hosts"] | join(", "))"'
