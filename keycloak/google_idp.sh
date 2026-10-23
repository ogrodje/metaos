#!/usr/bin/env bash
# "Sign in with Google" for realm metaos, set up through Keycloak's admin API (DEPLOY.md, "Google login") with
# Keycloak's built-in Google provider.
#
# Logins wait for approval (approval_gate.sh), but unlike Discord's, emails are trusted: Google only hands out
# addresses it has verified, so approving someone = granting the role, and their login's account is linked to their
# Person by email right away (Accounts.kt). Trusting them doesn't link a Google login to an existing user with the same
# email - Keycloak still asks to verify that account first.
#
# Idempotent - re-running it updates the identity provider, e.g. with a new client secret.
#
# From the environment:
#   KEYCLOAK_URL, KEYCLOAK_ADMIN_USERNAME, KEYCLOAK_ADMIN_PASSWORD  see approval_gate.sh
#   GOOGLE_IDP_CLIENT_ID, GOOGLE_IDP_CLIENT_SECRET  the "Web application" OAuth client "Keycloak" of Google Cloud
#                                                   project ogrodje-metaos; its redirect URIs are
#                                                   $KEYCLOAK_URL/realms/metaos/broker/google/endpoint
set -euo pipefail

FLOW=google-post-login

: "${GOOGLE_IDP_CLIENT_ID:?}" "${GOOGLE_IDP_CLIENT_SECRET:?}"
source "$(dirname "$0")/approval_gate.sh"

approval_gate "$FLOW" Google

idp=$(jq -n --arg id "$GOOGLE_IDP_CLIENT_ID" --arg secret "$GOOGLE_IDP_CLIENT_SECRET" --arg flow "$FLOW" '{
  alias: "google", displayName: "Google", providerId: "google", enabled: true,
  trustEmail: true, postBrokerLoginFlowAlias: $flow,
  config: {clientId: $id, clientSecret: $secret, defaultScope: "openid profile email", syncMode: "IMPORT"}}')
upsert_idp google "$idp"
echo "Google's redirect URI: $KC/realms/$REALM/broker/google/endpoint"
