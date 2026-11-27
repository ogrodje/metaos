#!/usr/bin/env bash
# The factory's secrets that aren't Terraform's (../../README.md, "What isn't Terraform's": it would keep a copy in its
# state): the LLM providers' keys, which only LiteLLM gets (litellm.tf). Idempotent: run it again after a key changes,
# and LiteLLM restarts to read the new one. After `just infra elysium/factory apply`, which makes the namespace.
#
# From the environment: secretspec.toml's secrets, from 1Password, which the devenv shell has (or
# `secretspec run -P default -- infra/elysium/factory/set_secrets.sh`). Into the cluster `just elysium-kubeconfig`
# wrote the kubeconfig of (ELYSIUM_KUBECONFIG, by default ~/.kube/elysium).
#   PROD_ANTHROPIC_API_KEY  a key of the Anthropic workspace the factory spends in, with its spend limit
#   PROD_OPENAI_API_KEY     optional, a key of the OpenAI project the factory spends in, with its budget
set -euo pipefail

: "${PROD_ANTHROPIC_API_KEY:?PROD_ANTHROPIC_API_KEY: secretspec set PROD_ANTHROPIC_API_KEY}"

export KUBECONFIG="${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}"
NS=factory

kubectl create secret generic llm-providers -n "$NS" \
  --from-literal=ANTHROPIC_API_KEY="$PROD_ANTHROPIC_API_KEY" \
  --from-literal=OPENAI_API_KEY="${PROD_OPENAI_API_KEY:-}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl rollout restart deployment/litellm -n "$NS"
kubectl rollout status deployment/litellm -n "$NS" --timeout=5m
