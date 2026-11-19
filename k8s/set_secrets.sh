#!/usr/bin/env bash
# What k8s/base needs but doesn't hold (k8s/README.md, "One-time setup"): the Postgres roles and databases on the
# shared CNPG cluster, the secrets the Deployments read and the registry pull secret. Idempotent - re-running it with
# new passwords rotates them (then `kubectl -n metaos-prod rollout restart deployment` to pick them up).
#
# From the environment: secretspec.toml's secrets, from 1Password, which the devenv shell has (or run it as
# `secretspec run -P default -- k8s/set_secrets.sh`). A new or rotated one: `secretspec set NAME`, then run this again.
#   PROD_METAOS_DB_PASSWORD       the app's Postgres password
#   PROD_KEYCLOAK_DB_PASSWORD     Keycloak's Postgres password
#   PROD_KEYCLOAK_ADMIN_PASSWORD  Keycloak's bootstrap admin ("admin")
#   PROD_TEMPORAL_DB_PASSWORD     Temporal's Postgres password (deployment-temporal.yaml)
#   PROD_UNLEASH_DB_PASSWORD      Unleash's Postgres password (deployment-unleash.yaml)
#   PROD_UNLEASH_ADMIN_PASSWORD   its first user's ("admin"), on its first start only
#   PROD_UNLEASH_SESSION_SECRET   what it signs its sessions' cookies with
#   PROD_UNLEASH_BACKEND_TOKEN, PROD_UNLEASH_FRONTEND_TOKEN
#                                 the secret parts of the tokens the app and the front-end ask it with, for environment
#                                 production; it makes them on its first start only, so after that they're its UI's
#                                 (CLAUDE.md, "Feature flags")
#   DOCKER_REGISTRY_USERNAME, DOCKER_REGISTRY_PASSWORD
#   GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_DRIVE_REFRESH_TOKEN  the Google bot account's, as locally
#   GOOGLE_GMAIL_REFRESH_TOKEN    optional, Gmail stays off without it (google/refresh_token.sh)
#   KOOFR_APP_PASSWORD            optional, the bot's Koofr app password; Koofr stays off without it
#   PROD_GOOGLE_STORAGE_KEY       optional, the key of production's storage service account (just storage-key
#                                 prod); Cloud Storage stays off without it
#   DROPBOX_APP_KEY, DROPBOX_APP_SECRET, DROPBOX_REFRESH_TOKEN
#                                 optional, MetaOS's Dropbox app and the bot's refresh token (dropbox/refresh_token.sh);
#                                 Dropbox stays off without them
#   PATREON_CLIENT_ID, PATREON_CLIENT_SECRET, PATREON_ACCESS_TOKEN, PATREON_REFRESH_TOKEN, PROD_PATREON_GATEWAY_TOKEN
#                                 optional, patreon-gateway's: the Patreon app's client and creator tokens (the
#                                 gateway refreshes them and keeps the new ones on its volume, so these only seed it;
#                                 they have to be the latest: a local gateway's state/tokens.json, if one ran),
#                                 and the token MetaOS calls it with (`openssl rand -hex 24`); Patreon stays off without
#                                 the last (patreon-gateway/README.md)
#   PROD_PATREON_TAILSCALE_AUTHKEY
#                                 the Tailscale auth key its browser's exit node (low) is reached with: reusable,
#                                 ephemeral, pre-approved, tagged tag:patreon-gateway
#   PROD_NEW_RELIC_LICENSE_KEY    optional, an ingest license key of New Relic's account 8666724 (EU), which MetaOS's
#                                 and patreon-gateway's Java agents report with; they don't start without it
#   SONIOX_API_KEY                optional, Soniox's (project Ogrodje One, US region), as locally, unless
#                                 PROD_SONIOX_API_KEY has production's own; Soniox stays off without it (Soniox.kt)
# The DB passwords go into SQL literals, so keep them alphanumeric: `openssl rand -hex 24`.
set -euo pipefail

NS=metaos-prod
REGISTRY=registry.folk-decibel.ts.net

: "${PROD_METAOS_DB_PASSWORD:?}" "${PROD_KEYCLOAK_DB_PASSWORD:?}" "${PROD_TEMPORAL_DB_PASSWORD:?}" "${PROD_UNLEASH_DB_PASSWORD:?}"
: "${PROD_UNLEASH_ADMIN_PASSWORD:?}" "${PROD_UNLEASH_SESSION_SECRET:?}"
: "${PROD_UNLEASH_BACKEND_TOKEN:?}" "${PROD_UNLEASH_FRONTEND_TOKEN:?}"
: "${PROD_KEYCLOAK_ADMIN_PASSWORD:?}"
: "${DOCKER_REGISTRY_USERNAME:?}" "${DOCKER_REGISTRY_PASSWORD:?}"
: "${GOOGLE_CLIENT_ID:?}" "${GOOGLE_CLIENT_SECRET:?}" "${GOOGLE_DRIVE_REFRESH_TOKEN:?}"

# No cd: KUBECONFIG may be relative to where this runs, as .envrc's is
kubectl apply -f "$(dirname "$0")/base/namespace.yaml"

# Roles and databases - names as in base/deployment-*.yaml. Each role owns its database(s), so the app's
# `CREATE EXTENSION citext` (V1__init.sql) and Temporal's `CREATE EXTENSION btree_gin` (its visibility schema) work:
# both are trusted extensions. Temporal has two, its own and its visibility's, whose schemas it sets up itself; Unleash
# one, which it migrates itself.
PRIMARY=$(kubectl get pod -n pg -l cnpg.io/cluster=main-cluster,cnpg.io/instanceRole=primary -o name)
kubectl exec -n pg -i "$PRIMARY" -- psql -U postgres -v ON_ERROR_STOP=1 -q postgres <<SQL
\set metaos_password '$PROD_METAOS_DB_PASSWORD'
\set keycloak_password '$PROD_KEYCLOAK_DB_PASSWORD'
\set temporal_password '$PROD_TEMPORAL_DB_PASSWORD'
\set unleash_password '$PROD_UNLEASH_DB_PASSWORD'
SELECT format('CREATE ROLE %I LOGIN', r) FROM unnest(ARRAY['metaos', 'metaos_keycloak', 'metaos_temporal', 'metaos_unleash']) r
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) \gexec
ALTER ROLE metaos PASSWORD :'metaos_password';
ALTER ROLE metaos_keycloak PASSWORD :'keycloak_password';
ALTER ROLE metaos_temporal PASSWORD :'temporal_password';
ALTER ROLE metaos_unleash PASSWORD :'unleash_password';
SELECT format('CREATE DATABASE %I OWNER %I', d, d) FROM unnest(ARRAY['metaos', 'metaos_keycloak', 'metaos_unleash']) d
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = d) \gexec
SELECT format('CREATE DATABASE %I OWNER metaos_temporal', d) FROM unnest(ARRAY['metaos_temporal', 'metaos_temporal_visibility']) d
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = d) \gexec
SQL

kubectl create secret generic metaos-secret -n "$NS" \
  --from-literal=postgres_password="$PROD_METAOS_DB_PASSWORD" \
  --from-literal=google_client_id="$GOOGLE_CLIENT_ID" \
  --from-literal=google_client_secret="$GOOGLE_CLIENT_SECRET" \
  --from-literal=google_drive_refresh_token="$GOOGLE_DRIVE_REFRESH_TOKEN" \
  --from-literal=google_gmail_refresh_token="${GOOGLE_GMAIL_REFRESH_TOKEN:-}" \
  --from-literal=koofr_app_password="${KOOFR_APP_PASSWORD:-}" \
  --from-literal=google_storage_key="${PROD_GOOGLE_STORAGE_KEY:-}" \
  --from-literal=dropbox_app_key="${DROPBOX_APP_KEY:-}" \
  --from-literal=dropbox_app_secret="${DROPBOX_APP_SECRET:-}" \
  --from-literal=dropbox_refresh_token="${DROPBOX_REFRESH_TOKEN:-}" \
  --from-literal=new_relic_license_key="${PROD_NEW_RELIC_LICENSE_KEY:-}" \
  --from-literal=soniox_api_key="${PROD_SONIOX_API_KEY:-${SONIOX_API_KEY:-}}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic temporal-secret -n "$NS" \
  --from-literal=postgres_password="$PROD_TEMPORAL_DB_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

# The tokens as Unleash has them, <project>:<environment>.<secret>; the app and the front-end read them from here too
kubectl create secret generic unleash-secret -n "$NS" \
  --from-literal=postgres_password="$PROD_UNLEASH_DB_PASSWORD" \
  --from-literal=admin_password="$PROD_UNLEASH_ADMIN_PASSWORD" \
  --from-literal=session_secret="$PROD_UNLEASH_SESSION_SECRET" \
  --from-literal=backend_token="default:production.$PROD_UNLEASH_BACKEND_TOKEN" \
  --from-literal=frontend_token="default:production.$PROD_UNLEASH_FRONTEND_TOKEN" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic keycloak-secret -n "$NS" \
  --from-literal=kc_db_password="$PROD_KEYCLOAK_DB_PASSWORD" \
  --from-literal=keycloak_admin_password="$PROD_KEYCLOAK_ADMIN_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

if [[ -n "${PROD_PATREON_GATEWAY_TOKEN:-}" ]]; then
  kubectl create secret generic patreon-gateway-secret -n "$NS" \
    --from-literal=patreon_client_id="${PATREON_CLIENT_ID:?}" \
    --from-literal=patreon_client_secret="${PATREON_CLIENT_SECRET:?}" \
    --from-literal=patreon_access_token="${PATREON_ACCESS_TOKEN:?}" \
    --from-literal=patreon_refresh_token="${PATREON_REFRESH_TOKEN:?}" \
    --from-literal=gateway_token="$PROD_PATREON_GATEWAY_TOKEN" \
    --from-literal=tailscale_auth_key="${PROD_PATREON_TAILSCALE_AUTHKEY:-}" \
    --dry-run=client -o yaml | kubectl apply -f -
fi

# On the namespace's default ServiceAccount, as in boo-prod, so no Deployment has to name it
kubectl create secret docker-registry regcred -n "$NS" \
  --docker-server="$REGISTRY" \
  --docker-username="$DOCKER_REGISTRY_USERNAME" \
  --docker-password="$DOCKER_REGISTRY_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl patch serviceaccount default -n "$NS" -p '{"imagePullSecrets":[{"name":"regcred"}]}'
