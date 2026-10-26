# Sourced by the identity provider scripts (discord_idp.sh, google_idp.sh), frontend_client.sh and sessions.sh: an admin
# token for realm metaos, `api` for its admin API, `approval_gate` for the post-login flow that keeps a provider's logins
# out until they're approved, `remember_logins` for its step that keeps them signed in, and `upsert_idp`.
#
# Anyone with a Discord or Google account can sign in, and every user of the realm may use every MCP tool - so each
# provider's post-login flow denies its logins until an admin grants the user the realm role "metaos-user".
#
# From the environment:
#   KEYCLOAK_URL                                      defaults to https://metaos-auth.ogrodje.si
#   KEYCLOAK_ADMIN_USERNAME, KEYCLOAK_ADMIN_PASSWORD  an admin of the master realm

KC=${KEYCLOAK_URL:-https://metaos-auth.ogrodje.si}
REALM=metaos
ROLE=metaos-user

: "${KEYCLOAK_ADMIN_USERNAME:?}" "${KEYCLOAK_ADMIN_PASSWORD:?}"

# Keycloak's own words when it refuses, which would otherwise go into jq or a variable and be lost
signin=$(curl -sS --fail-with-body "$KC/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d grant_type=password \
  --data-urlencode "username=$KEYCLOAK_ADMIN_USERNAME" --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD") ||
  { echo "Keycloak refused $KEYCLOAK_ADMIN_USERNAME's sign-in to realm master: $signin" >&2; exit 1; }
TOKEN=$(jq -r .access_token <<<"$signin")

# api METHOD PATH [JSON] - PATH is relative to the realm's admin API
api() {
  local response
  response=$(curl -sS --fail-with-body -X "$1" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    "$KC/admin/realms/$REALM$2" ${3:+-d "$3"}) || { echo "Keycloak refused $1 $2: $response" >&2; return 1; }
  echo "$response"
}

# approval_gate FLOW PROVIDER - the role and the post-login flow FLOW (e.g. discord-post-login), unless they exist
approval_gate() {
  local flow=$1 provider=$2 prefix=${1%-post-login}

  # The role that lets a provider's user in
  if ! api GET /roles | jq -e --arg r "$ROLE" 'any(.[]; .name == $r)' >/dev/null; then
    api POST /roles "$(jq -n --arg r "$ROLE" '{name: $r, description: "May sign in to MetaOS with Discord or Google"}')"
    echo "Created role $ROLE"
  fi

  # Post-login flow: a conditional subflow that denies access when the user lacks the role
  if ! api GET /authentication/flows | jq -e --arg f "$flow" 'any(.[]; .alias == $f)' >/dev/null; then
    api POST /authentication/flows "$(jq -n --arg f "$flow" --arg p "$provider" --arg r "$ROLE" \
      '{alias: $f, providerId: "basic-flow", topLevel: true, builtIn: false,
        description: "Denies \($p) logins without the \($r) role"}')"
    api POST "/authentication/flows/$flow/executions/flow" "$(jq -n --arg s "$prefix-require-role" \
      '{alias: $s, type: "basic-flow", provider: "registration-page-form"}')"
    api POST "/authentication/flows/$prefix-require-role/executions/execution" '{"provider": "conditional-user-role"}'
    api POST "/authentication/flows/$prefix-require-role/executions/execution" '{"provider": "deny-access-authenticator"}'
    # Without a step that succeeds, a flow whose condition didn't match fails as "Invalid username or password"
    api POST "/authentication/flows/$flow/executions/execution" '{"provider": "allow-access-authenticator"}'

    # Executions are created DISABLED; the subflow is CONDITIONAL, everything else REQUIRED
    api GET "/authentication/flows/$flow/executions" | jq -c '.[]' | while read -r execution; do
      requirement=$(jq -r 'if .authenticationFlow then "CONDITIONAL" else "REQUIRED" end' <<<"$execution")
      api PUT "/authentication/flows/$flow/executions" "$(jq -c --arg q "$requirement" '.requirement = $q' <<<"$execution")"
    done

    # Config aliases are per realm, hence the prefix
    local executions condition deny
    executions=$(api GET "/authentication/flows/$flow/executions")
    condition=$(jq -r '.[] | select(.providerId == "conditional-user-role") | .id' <<<"$executions")
    deny=$(jq -r '.[] | select(.providerId == "deny-access-authenticator") | .id' <<<"$executions")
    api POST "/authentication/executions/$condition/config" "$(jq -n --arg a "$prefix-without-$ROLE" --arg r "$ROLE" \
      '{alias: $a, config: {condUserRole: $r, negate: "true"}}')"
    api POST "/authentication/executions/$deny/config" "$(jq -n --arg a "$prefix-awaiting-approval" \
      '{alias: $a, config: {denyErrorMessage: "Your account is waiting for approval by a MetaOS admin."}}')"
    echo "Created flow $flow"
  fi

  remember_logins "$flow"
}

# remember_logins FLOW - the post-login flow FLOW remembers its sign-ins, as Remember me on Keycloak's login form does,
# unless it does already: its last step is MetaOS's provider (keycloak/remember-me), which Keycloak has to have
remember_logins() {
  local flow=$1 step=metaos-remember-me execution
  if api GET "/authentication/flows/$flow/executions" | jq -e --arg s "$step" 'any(.[]; .providerId == $s)' >/dev/null; then
    return
  fi
  if ! api GET /authentication/authenticator-providers | jq -e --arg s "$step" 'any(.[]; .id == $s)' >/dev/null; then
    echo "Keycloak at $KC has no $step yet: deploy docker/keycloak.Dockerfile's image first" >&2
    return 1
  fi

  # Created DISABLED, and last, after Allow access
  api POST "/authentication/flows/$flow/executions/execution" "$(jq -n --arg s "$step" '{provider: $s}')"
  execution=$(api GET "/authentication/flows/$flow/executions" | jq -c --arg s "$step" '.[] | select(.providerId == $s)')
  api PUT "/authentication/flows/$flow/executions" "$(jq -c '.requirement = "REQUIRED"' <<<"$execution")"
  echo "$flow remembers sign-ins"
}

# upsert_idp ALIAS JSON - creates or updates the identity provider ALIAS
upsert_idp() {
  if api GET /identity-provider/instances | jq -e --arg a "$1" 'any(.[]; .alias == $a)' >/dev/null; then
    api PUT "/identity-provider/instances/$1" "$2"
    echo "Updated identity provider $1"
  else
    api POST /identity-provider/instances "$2"
    echo "Created identity provider $1"
  fi
}
