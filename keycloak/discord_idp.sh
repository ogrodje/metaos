#!/usr/bin/env bash
# "Sign in with Discord" for realm metaos, set up through Keycloak's admin API (DEPLOY.md, "Discord login").
# Keycloak has no Discord provider, so this uses its generic OAuth v2 one against Discord's /users/@me.
#
# Logins wait for approval (approval_gate.sh). Emails aren't trusted either: Discord also returns unverified ones, and a
# verified email is what links a login's account to a Person by itself (Accounts.kt). Approving someone = granting the
# role + turning on "Email verified" once you know the address is theirs, or linking their account by hand.
#
# Idempotent - re-running it updates the identity provider, e.g. with a new client secret.
#
# From the environment:
#   KEYCLOAK_URL, KEYCLOAK_ADMIN_USERNAME, KEYCLOAK_ADMIN_PASSWORD  see approval_gate.sh
#   DISCORD_CLIENT_ID, DISCORD_CLIENT_SECRET    from the Discord application's OAuth2 page; its redirect is
#                                               $KEYCLOAK_URL/realms/metaos/broker/discord/endpoint
set -euo pipefail

FLOW=discord-post-login

: "${DISCORD_CLIENT_ID:?}" "${DISCORD_CLIENT_SECRET:?}"
source "$(dirname "$0")/approval_gate.sh"

approval_gate "$FLOW" Discord

# The identity provider. Discord's /users/@me has id, username, global_name and email (with scope "email").
idp=$(jq -n --arg id "$DISCORD_CLIENT_ID" --arg secret "$DISCORD_CLIENT_SECRET" --arg flow "$FLOW" '{
  alias: "discord", displayName: "Discord", providerId: "oauth2", enabled: true,
  trustEmail: false, postBrokerLoginFlowAlias: $flow,
  config: {
    authorizationUrl: "https://discord.com/oauth2/authorize",
    tokenUrl: "https://discord.com/api/oauth2/token",
    userInfoUrl: "https://discord.com/api/users/@me",
    clientId: $id, clientSecret: $secret, clientAuthMethod: "client_secret_post",
    defaultScope: "identify email",
    userIDClaim: "id", userNameClaim: "username", fullNameClaim: "global_name", emailClaim: "email",
    syncMode: "IMPORT"
  }}')
upsert_idp discord "$idp"
echo "Discord's redirect URI: $KC/realms/$REALM/broker/discord/endpoint"
