#!/usr/bin/env bash
# The cluster's Prometheus passing some of MetaOS's metrics on to New Relic (newrelic-values.yaml): the license key as
# a secret beside Prometheus, then the values laid over kube-prometheus-stack's own, at the chart version the release
# already has, so nothing else in it changes. Idempotent. Arguments go to `helm upgrade`, e.g. --dry-run=server first.
#
# From the environment (.envrc):
#   PROD_NEW_RELIC_LICENSE_KEY  an ingest license key of New Relic's account 8666724 (EU), as for MetaOS's agents
# Needs a kubeconfig that may change namespace observability, e.g. on the node (`ssh ogrodje-one-x`).
set -euo pipefail

: "${PROD_NEW_RELIC_LICENSE_KEY:?}"
NS=observability
RELEASE=prom
DIR=$(dirname "$0")

# The values replace the release's list of remote writes: refuse when it has one of its own
others=$(helm get values "$RELEASE" -n "$NS" -o json |
  jq '[.prometheus.prometheusSpec.remoteWrite // [] | .[] | select(.url | contains("newrelic.com") | not)] | length')
if [[ $others != 0 ]]; then
  echo "Prometheus already writes to $others other place(s), which these values would drop: add them first" >&2
  exit 1
fi

kubectl create secret generic newrelic-remote-write -n "$NS" \
  --from-literal=license_key="$PROD_NEW_RELIC_LICENSE_KEY" \
  --dry-run=client -o yaml | kubectl apply -f -

# kube-prometheus-stack-<version>, as installed
chart=$(helm list -n "$NS" --filter "^$RELEASE\$" -o json | jq -r '.[0].chart')
version=${chart#kube-prometheus-stack-}
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update prometheus-community >/dev/null

helm upgrade "$RELEASE" prometheus-community/kube-prometheus-stack -n "$NS" --version "$version" \
  --reuse-values -f "$DIR/newrelic-values.yaml" "$@"
