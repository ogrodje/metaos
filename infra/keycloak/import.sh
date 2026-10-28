#!/usr/bin/env bash
# Adopts realm metaos into an environment's state (dev/ or prod/): it looks up what Terraform manages by name in
# Keycloak's admin API, and imports each by the ID Keycloak gave it. `terraform plan` afterwards says how the realm
# differs from the configuration.
#
#   just keycloak adopt dev|prod
#
# It changes nothing in Keycloak, but for a realm that isn't there yet, which it creates first (asking, as `terraform
# apply` does): Keycloak gives every new realm its default client registration policies, which Terraform has to adopt
# rather than create again, as two Trusted Hosts policies would refuse every registration the default one doesn't allow.
#
# Skips what's in the state already, so it can be run again. Runs in the environment's directory, initialised, with
# KEYCLOAK_ADMIN_USERNAME and KEYCLOAK_ADMIN_PASSWORD, an admin of realm master, from the environment (justfile).
set -euo pipefail

ENV=${1:?dev or prod}
cd "$(dirname "$0")/$ENV"

REALM=metaos
MODULE=module.realm
# value EXPRESSION - an expression of the configuration's, as JSON
value() { terraform console <<<"jsonencode($1)" | jq -r fromjson; }

KC=$(value local.keycloak_url)
: "${KEYCLOAK_ADMIN_USERNAME:?}" "${KEYCLOAK_ADMIN_PASSWORD:?}"

signin=$(curl -sS --fail-with-body "$KC/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d grant_type=password \
  --data-urlencode "username=$KEYCLOAK_ADMIN_USERNAME" --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD") ||
  { echo "Keycloak refused the sign-in to realm master: $signin" >&2; exit 1; }
TOKEN=$(jq -r .access_token <<<"$signin")

api() { curl -sS --fail-with-body -H "Authorization: Bearer $TOKEN" "$KC/admin/realms/$REALM$1"; }

if ! api "" >/dev/null 2>&1; then
  echo "Realm $REALM isn't at $KC yet: creating it first, so Keycloak makes its defaults"
  terraform apply -input=false -target="$MODULE.keycloak_realm.metaos"
fi

in_state=$(terraform state list 2>/dev/null || true)

# adopt ADDRESS ID - imports the module's ADDRESS unless it's in the state already, or ID is empty (not in Keycloak:
# apply creates it)
adopt() {
  if grep -qxF "$MODULE.$1" <<<"$in_state"; then
    echo "  $1: in the state already"
  elif [ -z "$2" ]; then
    echo "  $1: not in Keycloak, terraform apply creates it"
  else
    terraform import -input=false -no-color "$MODULE.$1" "$2" >/dev/null
    echo "  $1: imported"
  fi
}

echo "Adopting realm $REALM at $KC into $ENV's state"
adopt keycloak_realm.metaos "$REALM"

role=$(api /roles | jq -r '.[] | select(.name == "metaos-user") | .id')
adopt keycloak_role.metaos_user "${role:+$REALM/$role}"

scopes=$(api /client-scopes)
for scope in mcp:tools metaos:read metaos:write; do
  id=$(jq -r --arg n "$scope" '.[] | select(.name == $n) | .id' <<<"$scopes")
  [ -n "$id" ] || { adopt "keycloak_openid_client_scope.metaos[\"$scope\"]" ""; continue; }
  adopt "keycloak_openid_client_scope.metaos[\"$scope\"]" "$REALM/$id"
  mapper=$(jq -r --arg n "$scope" '.[] | select(.name == $n) | .protocolMappers[]? | select(.protocolMapper == "oidc-audience-mapper") | .id' <<<"$scopes" | head -1)
  adopt "keycloak_openid_audience_protocol_mapper.metaos[\"$scope\"]" "${mapper:+$REALM/client-scope/$id/$mapper}"
  mapped=$(api "/client-scopes/$id/scope-mappings/realm" | jq -r --arg r "${role:-none}" 'any(.[]; .id == $r)')
  adopt "keycloak_generic_role_mapper.metaos_scope_needs_approval[\"$scope\"]" \
    "$([ "$mapped" = true ] && echo "$REALM/client-scope/$id/scope-mappings/realm/$role")"
done
adopt keycloak_realm_optional_client_scopes.metaos "$REALM"

ui=$(api "/clients?clientId=metaos-ui" | jq -r '.[0].id // empty')
adopt keycloak_openid_client.metaos_ui "${ui:+$REALM/$ui}"
adopt keycloak_openid_client_default_scopes.metaos_ui "${ui:+$REALM/$ui}"
adopt keycloak_openid_client_optional_scopes.metaos_ui "${ui:+$REALM/$ui}"

agents=$(api "/clients?clientId=metaos-agents" | jq -r '.[0].id // empty')
adopt keycloak_openid_client.metaos_agents "${agents:+$REALM/$agents}"
adopt keycloak_openid_client_default_scopes.metaos_agents "${agents:+$REALM/$agents}"
adopt keycloak_openid_client_optional_scopes.metaos_agents "${agents:+$REALM/$agents}"
if [ -n "$agents" ] && [ -n "$role" ]; then
  account=$(api "/clients/$agents/service-account-user" | jq -r '.id')
  approved=$(api "/users/$account/role-mappings/realm" | jq -r --arg r "$role" 'any(.[]; .id == $r)')
fi
adopt keycloak_openid_client_service_account_realm_role.metaos_agents \
  "$([ "${approved:-}" = true ] && echo "$REALM/$account/$role")"

policies=$(api "/components?type=org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy")
declared=$(value "$MODULE.registration_policies")
for key in $(jq -r 'keys[]' <<<"$declared"); do
  id=$(jq -r --argjson s "$(jq -c --arg k "$key" '.[$k]' <<<"$declared")" \
    '.[] | select(.providerId == $s.provider_id and .subType == $s.sub_type) | .id' <<<"$policies" | head -1)
  adopt "keycloak_realm_client_registration_policy.metaos[\"$key\"]" "${id:+$REALM/$id}"
done

flows=$(api /authentication/flows)
for idp in $(value "$MODULE.identity_providers" | jq -r '.[]'); do
  address=$([ "$idp" = google ] && echo "keycloak_oidc_google_identity_provider.google[0]" || echo "keycloak_oidc_identity_provider.discord[0]")
  exists=$(api /identity-provider/instances | jq -r --arg a "$idp" 'any(.[]; .alias == $a)')
  adopt "$address" "$([ "$exists" = true ] && echo "$REALM/$idp")"

  flow=$(jq -r --arg a "$idp-post-login" '.[] | select(.alias == $a) | .id' <<<"$flows")
  adopt "keycloak_authentication_flow.post_login[\"$idp\"]" "${flow:+$REALM/$flow}"
  [ -n "$flow" ] || continue

  executions=$(api "/authentication/flows/$idp-post-login/executions")
  execution() { jq -c --arg p "$1" '.[] | select(.providerId == $p)' <<<"$executions"; }
  subflow=$(jq -r '.[] | select(.authenticationFlow == true and .level == 0) | .flowId' <<<"$executions")
  adopt "keycloak_authentication_subflow.require_role[\"$idp\"]" "${subflow:+$REALM/$idp-post-login/$subflow}"
  allow=$(execution allow-access-authenticator | jq -r .id)
  adopt "keycloak_authentication_execution.allow[\"$idp\"]" "${allow:+$REALM/$idp-post-login/$allow}"
  remember=$(execution metaos-remember-me | jq -r .id)
  adopt "keycloak_authentication_execution.remember[\"$idp\"]" "${remember:+$REALM/$idp-post-login/$remember}"
  for step in without_role:conditional-user-role deny:deny-access-authenticator; do
    name=${step%%:*} provider=${step#*:}
    id=$(execution "$provider" | jq -r .id)
    config=$(execution "$provider" | jq -r '.authenticationConfig // empty')
    adopt "keycloak_authentication_execution.$name[\"$idp\"]" "${id:+$REALM/$idp-require-role/$id}"
    adopt "keycloak_authentication_execution_config.$name[\"$idp\"]" "${config:+$REALM/$id/$config}"
  done
done

dev=$(api "/users?username=dev&exact=true" | jq -r '.[0].id // empty')
if [ "$(value "$MODULE.dev_user")" = true ]; then
  adopt "keycloak_user.dev[0]" "${dev:+$REALM/$dev}"
  adopt "keycloak_user_roles.dev[0]" "${dev:+$REALM/$dev}"
elif [ -n "$dev" ]; then
  echo "  (the dev user exists here but isn't managed: disable it in the admin console if nobody uses it)"
fi

echo "Done. Now: just keycloak plan $ENV"
