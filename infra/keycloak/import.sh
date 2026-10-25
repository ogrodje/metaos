#!/usr/bin/env bash
# Adopts an existing realm metaos into Terraform's state, once per environment: it looks up what Terraform manages by
# name in Keycloak's admin API, and imports each by the ID Keycloak gave it. It changes nothing in Keycloak; `tofu plan`
# afterwards says how the realm differs from the configuration.
#
#   infra/keycloak/import.sh dev|prod
#
# Also for a new realm, after `tofu apply -target=keycloak_realm.metaos`: Keycloak gives every new realm its default
# client registration policies, which Terraform has to adopt rather than create again, as two Trusted Hosts policies
# would refuse every registration the default one doesn't allow.
#
# Skips what's in the state already, so it can be run again. From the environment: KEYCLOAK_ADMIN_PASSWORD (and
# KEYCLOAK_ADMIN_USERNAME, admin by default); for prod also TF_VAR_google_client_secret and TF_VAR_discord_client_secret,
# which aren't sent to Keycloak on import, only checked to be there.
set -euo pipefail

ENV=${1:?dev or prod}
cd "$(dirname "$0")"

KC=$(sed -n 's/^keycloak_url *= *"\(.*\)"/\1/p' "$ENV.tfvars")
REALM=metaos
: "${KEYCLOAK_ADMIN_PASSWORD:?}"
export TF_VAR_keycloak_admin_password=$KEYCLOAK_ADMIN_PASSWORD
export TF_VAR_keycloak_admin_username=${KEYCLOAK_ADMIN_USERNAME:-admin}

signin=$(curl -sS --fail-with-body "$KC/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d grant_type=password \
  --data-urlencode "username=$TF_VAR_keycloak_admin_username" --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD") ||
  { echo "Keycloak refused the sign-in to realm master: $signin" >&2; exit 1; }
TOKEN=$(jq -r .access_token <<<"$signin")

api() { curl -sS --fail-with-body -H "Authorization: Bearer $TOKEN" "$KC/admin/realms/$REALM$1"; }

tofu workspace select -or-create "$ENV" >/dev/null
in_state=$(tofu state list 2>/dev/null || true)

# adopt ADDRESS ID - imports ADDRESS unless it's in the state already, or ID is empty (not in Keycloak: plan creates it)
adopt() {
  if grep -qxF "$1" <<<"$in_state"; then
    echo "  $1: in the state already"
  elif [ -z "$2" ]; then
    echo "  $1: not in Keycloak, tofu apply creates it"
  else
    tofu import -var-file="$ENV.tfvars" -input=false -no-color "$1" "$2" >/dev/null
    echo "  $1: imported"
  fi
}

echo "Adopting realm $REALM at $KC into workspace $ENV"
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

policies=$(api "/components?type=org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy")
for key in $(tofu console -var-file="$ENV.tfvars" <<<'jsonencode(keys(local.registration_policies))' | jq -r 'fromjson | .[]'); do
  spec=$(tofu console -var-file="$ENV.tfvars" <<<"jsonencode(local.registration_policies[\"$key\"])" | jq -r fromjson)
  id=$(jq -r --argjson s "$spec" '.[] | select(.providerId == $s.provider_id and .subType == $s.sub_type) | .id' <<<"$policies" | head -1)
  adopt "keycloak_realm_client_registration_policy.metaos[\"$key\"]" "${id:+$REALM/$id}"
done

flows=$(api /authentication/flows)
for idp in google discord; do
  declared=$(tofu console -var-file="$ENV.tfvars" <<<"var.$idp != null")
  [ "$declared" = true ] || continue

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
  for step in without_role:conditional-user-role deny:deny-access-authenticator; do
    name=${step%%:*} provider=${step#*:}
    id=$(execution "$provider" | jq -r .id)
    config=$(execution "$provider" | jq -r '.authenticationConfig // empty')
    adopt "keycloak_authentication_execution.$name[\"$idp\"]" "${id:+$REALM/$idp-require-role/$id}"
    adopt "keycloak_authentication_execution_config.$name[\"$idp\"]" "${config:+$REALM/$id/$config}"
  done
done

dev=$(api "/users?username=dev&exact=true" | jq -r '.[0].id // empty')
if [ "$(tofu console -var-file="$ENV.tfvars" <<<'var.dev_user')" = true ]; then
  adopt "keycloak_user.dev[0]" "${dev:+$REALM/$dev}"
  adopt "keycloak_user_roles.dev[0]" "${dev:+$REALM/$dev}"
elif [ -n "$dev" ]; then
  echo "  (the dev user exists here but isn't managed: disable it in the admin console if nobody uses it)"
fi

echo "Done. Now: tofu plan -var-file=$ENV.tfvars"
