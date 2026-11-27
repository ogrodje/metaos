#!/usr/bin/env bash
# The factory's secrets that aren't Terraform's (../../README.md, "What isn't Terraform's": it would keep a copy in its
# state): the LLM providers' keys, which only LiteLLM gets (litellm.tf), and the Linear app's, which only the Linear
# bridge gets (linear-bridge.tf). Idempotent: run it again after one changes, and what reads it restarts. After
# `just infra elysium/factory apply`, which makes the namespace.
#
# From the environment: secretspec.toml's secrets, from 1Password, which the devenv shell has (or
# `secretspec run -P default -- infra/elysium/factory/set_secrets.sh`). Into the cluster `just elysium-kubeconfig`
# wrote the kubeconfig of (ELYSIUM_KUBECONFIG, by default ~/.kube/elysium).
#   PROD_ANTHROPIC_API_KEY         a key of the Anthropic workspace the factory spends in, with its spend limit
#   PROD_OPENAI_API_KEY            optional, a key of the OpenAI project the factory spends in, with its budget
#   ELYSIUM_LINEAR_CLIENT_SECRET   the Linear app Elysium's client secret: the bridge's tokens
#   ELYSIUM_LINEAR_WEBHOOK_SECRET  its webhook's signing secret: what the bridge checks Linear's deliveries with
#   ELYSIUM_GITHUB_TOKEN           elysium-og's token: the bridge's 👀 and answers on pull requests
#   ELYSIUM_METAOS_CLIENT_SECRET   optional, Keycloak's client elysium's: MetaOS' MCP server for the Canvases, as it
set -euo pipefail

: "${PROD_ANTHROPIC_API_KEY:?PROD_ANTHROPIC_API_KEY: secretspec set PROD_ANTHROPIC_API_KEY}"
: "${ELYSIUM_LINEAR_CLIENT_SECRET:?ELYSIUM_LINEAR_CLIENT_SECRET: secretspec set ELYSIUM_LINEAR_CLIENT_SECRET}"
: "${ELYSIUM_LINEAR_WEBHOOK_SECRET:?ELYSIUM_LINEAR_WEBHOOK_SECRET: secretspec set ELYSIUM_LINEAR_WEBHOOK_SECRET}"
: "${ELYSIUM_GITHUB_TOKEN:?ELYSIUM_GITHUB_TOKEN: secretspec set ELYSIUM_GITHUB_TOKEN}"

export KUBECONFIG="${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}"
NS=factory

kubectl create secret generic llm-providers -n "$NS" \
  --from-literal=ANTHROPIC_API_KEY="$PROD_ANTHROPIC_API_KEY" \
  --from-literal=OPENAI_API_KEY="${PROD_OPENAI_API_KEY:-}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic linear-bridge-linear -n "$NS" \
  --from-literal=LINEAR_CLIENT_SECRET="$ELYSIUM_LINEAR_CLIENT_SECRET" \
  --from-literal=LINEAR_WEBHOOK_SECRET="$ELYSIUM_LINEAR_WEBHOOK_SECRET" \
  --from-literal=GITHUB_TOKEN="$ELYSIUM_GITHUB_TOKEN" \
  --from-literal=METAOS_CLIENT_SECRET="${ELYSIUM_METAOS_CLIENT_SECRET:-}" \
  --dry-run=client -o yaml | kubectl apply -f -

for deployment in litellm linear-bridge; do
  kubectl rollout restart "deployment/$deployment" -n "$NS"
done
for deployment in litellm linear-bridge; do
  kubectl rollout status "deployment/$deployment" -n "$NS" --timeout=5m
done
