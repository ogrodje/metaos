#!/usr/bin/env bash
# Signs the bot (iva.ogrodje@gmail.com) in to Patreon in a browser you can see, and hands the session to a gateway.
#
#   patreon-gateway/login.sh            to production's, through kubectl port-forward (needs Tailscale and KUBECONFIG)
#   patreon-gateway/login.sh local      to one running here, on :8090 (PATREON_GATEWAY_TOKEN from the environment)
#
# Production's token comes from the cluster (patreon-gateway-secret). The session is a secret: it's kept in
# patreon-gateway/state/ (git ignores it) and on the gateway's volume, nowhere else.
set -euo pipefail

cd "$(dirname "$0")"
SESSION=state/session.json
../gradlew -q login -Psession="$SESSION"

if [[ "${1:-prod}" == "local" ]]; then
  URL=http://localhost:8090
  : "${PATREON_GATEWAY_TOKEN:?the local gateway's token}"
  TOKEN=$PATREON_GATEWAY_TOKEN
else
  NS=metaos-prod
  TOKEN=$(kubectl -n "$NS" get secret patreon-gateway-secret -o jsonpath='{.data.gateway_token}' | base64 -d)
  kubectl -n "$NS" port-forward service/patreon-gateway 18090:80 >/dev/null &
  FORWARD=$!
  trap 'kill $FORWARD' EXIT
  sleep 3
  URL=http://localhost:18090
fi

curl -sf -X PUT "$URL/session" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$SESSION"
echo
