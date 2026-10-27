#!/usr/bin/env bash
# How long users stay signed in to realm metaos, set through Keycloak's admin API - for realms imported before
# metaos-realm.json had it, as Keycloak imports the file only once.
#
# A sign-in is a Keycloak session, and refresh tokens last as long as it: it ends after IDLE without a refresh, and after
# MAX in any case. The front-end refreshes its token whenever it asks the API something, and MCP clients whenever they
# call a tool. Keycloak's defaults, 30 minutes and 10 hours, signed people out of both several times a day. Access
# tokens stay at 5 minutes, as they're what every request carries.
#
# Sign-ins through Discord and Google are remembered, as with Remember me on Keycloak's login form: each identity
# provider's post-login flow ends with MetaOS's step (remember_logins, approval_gate.sh), which Keycloak has to have
# (docker/keycloak.Dockerfile). Keycloak's sign-in cookie then lasts REMEMBERED_MAX, rather than until the browser quits,
# which signed people out of the front-end whenever it restarted; the session REMEMBERED_IDLE without use.
#
# Idempotent.
#
# From the environment:
#   KEYCLOAK_URL, KEYCLOAK_ADMIN_USERNAME, KEYCLOAK_ADMIN_PASSWORD  see approval_gate.sh
#   SSO_SESSION_IDLE  seconds, defaults to 7 days
#   SSO_SESSION_MAX   seconds, defaults to 30 days
#   REMEMBERED_IDLE   seconds, defaults to 30 days
#   REMEMBERED_MAX    seconds, defaults to 90 days
set -euo pipefail

IDLE=${SSO_SESSION_IDLE:-604800}
MAX=${SSO_SESSION_MAX:-2592000}
REMEMBERED_IDLE=${REMEMBERED_IDLE:-2592000}
REMEMBERED_MAX=${REMEMBERED_MAX:-7776000}

source "$(dirname "$0")/approval_gate.sh"

# The flows first, so a Keycloak without the step stops here, before anything changed
for flow in $(api GET /identity-provider/instances | jq -r '[.[].postBrokerLoginFlowAlias // empty] | unique[]'); do
  remember_logins "$flow"
done

# Only these: Keycloak leaves out of an update what it doesn't have
api PUT "" "$(jq -n --argjson idle "$IDLE" --argjson max "$MAX" \
  --argjson remembered_idle "$REMEMBERED_IDLE" --argjson remembered_max "$REMEMBERED_MAX" \
  '{ssoSessionIdleTimeout: $idle, ssoSessionMaxLifespan: $max, rememberMe: true,
    ssoSessionIdleTimeoutRememberMe: $remembered_idle, ssoSessionMaxLifespanRememberMe: $remembered_max}')"
api GET "" | jq -r '"Sessions end after \(.ssoSessionIdleTimeout / 86400) days idle, \(.ssoSessionMaxLifespan / 86400) days at most",
  "Remembered ones after \(.ssoSessionIdleTimeoutRememberMe / 86400) days idle, \(.ssoSessionMaxLifespanRememberMe / 86400) days at most"'
