#!/usr/bin/env bash
# Sends the realm's SMTP password, TF_VAR_smtp_password, to Keycloak, when a plain apply wouldn't (OG-62):
#
#   just keycloak smtp-password dev|prod
#
# Keycloak's admin API answers every secret as `**********`, so a realm the state took from Keycloak (`adopt`) has that
# in its state as the SMTP password, and the provider (keycloak/keycloak 5.x) ignores any change to a password whose
# old value is `**********` (its DiffSuppressFunc): every plan says "No changes", every apply sends `**********`, which
# Keycloak takes as "keep the one you have", and the realm keeps whatever password it had before, however wrong. So
# this puts another value in the state first, which the provider then sees changing to the real one, and applies the
# realm alone, asking first as `terraform apply` does. After it, the state has the real password, and plans compare it.
#
# Runs in the environment's directory, initialised, with TF_VAR_smtp_password and Keycloak's admin from the
# environment (justfile). The state has secrets in it, so its copies are in a directory only you can read, removed
# afterwards.
set -euo pipefail

ENV=${1:?dev or prod}
cd "$(dirname "$0")/$ENV"
: "${TF_VAR_smtp_password:?TF_VAR_smtp_password, KEYCLOAK_SMTP_PASSWORD in secretspec.toml}"

REALM=module.realm.keycloak_realm.metaos
MASK='**********'
PASSWORD='.resources[] | select(.module == "module.realm" and .type == "keycloak_realm") | .instances[0].attributes.smtp_server[0].auth[0].password'

dir=$(mktemp -d)
chmod 700 "$dir"
trap 'rm -rf "$dir"' EXIT

terraform state pull >"$dir/state.json"
case "$(jq -r "$PASSWORD // \"\"" "$dir/state.json")" in
  "") echo "The state has no SMTP password for the realm: a plain apply sets it" >&2; exit 1 ;;
  "$MASK")
    jq "($PASSWORD) |= \"not $MASK\" | .serial += 1" "$dir/state.json" >"$dir/new.json"
    terraform state push "$dir/new.json"
    echo "The state's SMTP password was Keycloak's $MASK: now the plan sends the real one" ;;
  *) echo "The state has an SMTP password: the plan sends it again if it differs" ;;
esac

terraform apply -input=true -target="$REALM"
