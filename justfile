# MetaOS images and their deploy (k8s/README.md). The registry and the cluster are on the tailnet, so these need
# Tailscale up. Images are tagged with `git describe`, as .github/workflows/deploy.yml does.

registry := "registry.folk-decibel.ts.net"
app_image := registry / "metaos/metaos"
web_image := registry / "metaos/metaos-web"
keycloak_image := registry / "metaos/keycloak"
gateway_image := registry / "metaos/patreon-gateway"
scrapers_image := registry / "metaos/scrapers"
ns := "metaos-prod"

# The boot jar docker/Dockerfile copies.
jar:
    ./gradlew bootJar

# The front-end's build, fe/.output, which docker/web.Dockerfile copies.
web:
    ./gradlew feBuild

# patreon-gateway's boot jar, which docker/patreon-gateway.Dockerfile copies; a build of its own (patreon-gateway/).
gateway:
    ./gradlew -p patreon-gateway bootJar

# The scrapers' boot jar, which docker/scrapers.Dockerfile copies; a build of its own (scrapers/).
scrapers:
    ./gradlew -p scrapers bootJar

# linux/amd64 is what the cluster runs.
# Build the images locally.
images: jar web gateway scrapers
    #!/usr/bin/env bash
    set -euo pipefail
    TAG=$(git describe --tags --always --dirty)
    docker buildx build --platform linux/amd64 -f docker/Dockerfile \
      -t "{{app_image}}:latest" -t "{{app_image}}:$TAG" --load .
    docker buildx build --platform linux/amd64 -f docker/web.Dockerfile \
      -t "{{web_image}}:latest" -t "{{web_image}}:$TAG" --load .
    docker buildx build --platform linux/amd64 -f docker/keycloak.Dockerfile \
      -t "{{keycloak_image}}:latest" -t "{{keycloak_image}}:$TAG" --load .
    docker buildx build --platform linux/amd64 -f docker/patreon-gateway.Dockerfile \
      -t "{{gateway_image}}:latest" -t "{{gateway_image}}:$TAG" --load .
    docker buildx build --platform linux/amd64 -f docker/scrapers.Dockerfile \
      -t "{{scrapers_image}}:latest" -t "{{scrapers_image}}:$TAG" --load .

# Build and push the images, and point the kustomization at the new tag.
push: jar web gateway scrapers
    #!/usr/bin/env bash
    set -euo pipefail
    TAG=$(git describe --tags --always --dirty)
    echo "Building and pushing $TAG"
    # As CI pushes them (.github/workflows/deploy.yml): on the bases as they are now, and with the files at
    # SOURCE_DATE_EPOCH's time, so the layers that didn't change are those the registry has, which aren't pushed again
    export SOURCE_DATE_EPOCH=0
    push=(--platform linux/amd64 --pull --provenance=false --output "type=registry,rewrite-timestamp=true")
    docker buildx build "${push[@]}" -f docker/Dockerfile \
      -t "{{app_image}}:latest" -t "{{app_image}}:$TAG" .
    docker buildx build "${push[@]}" -f docker/web.Dockerfile \
      -t "{{web_image}}:latest" -t "{{web_image}}:$TAG" .
    docker buildx build "${push[@]}" -f docker/keycloak.Dockerfile \
      -t "{{keycloak_image}}:latest" -t "{{keycloak_image}}:$TAG" .
    docker buildx build "${push[@]}" -f docker/patreon-gateway.Dockerfile \
      -t "{{gateway_image}}:latest" -t "{{gateway_image}}:$TAG" .
    docker buildx build "${push[@]}" -f docker/scrapers.Dockerfile \
      -t "{{scrapers_image}}:latest" -t "{{scrapers_image}}:$TAG" .
    cd k8s/base && kustomize edit set image "{{app_image}}:$TAG" "{{web_image}}:$TAG" "{{keycloak_image}}:$TAG" "{{gateway_image}}:$TAG" "{{scrapers_image}}:$TAG"

# Apply the manifests and wait for the rollout.
deploy:
    kubectl apply -k k8s/base/
    kubectl rollout status deployment/keycloak -n {{ns}} --timeout=5m
    kubectl rollout status deployment/metaos -n {{ns}} --timeout=5m
    kubectl rollout status deployment/metaos-web -n {{ns}} --timeout=5m
    kubectl rollout status deployment/patreon-gateway -n {{ns}} --timeout=5m
    kubectl rollout status deployment/scrapers -n {{ns}} --timeout=5m
    kubectl rollout status deployment/unleash -n {{ns}} --timeout=5m

# What the cluster would apply, without applying it.
manifests:
    kubectl kustomize k8s/base/

# The app's logs, followed.
logs *ARGS:
    kubectl logs -n {{ns}} deployment/metaos --follow --tail=100 {{ARGS}}

# The front-end's logs, followed.
web-logs *ARGS:
    kubectl logs -n {{ns}} deployment/metaos-web --follow --tail=100 {{ARGS}}

# patreon-gateway's logs, followed.
gateway-logs *ARGS:
    kubectl logs -n {{ns}} deployment/patreon-gateway --follow --tail=100 {{ARGS}}

# The scrapers' logs, followed.
scrapers-logs *ARGS:
    kubectl logs -n {{ns}} deployment/scrapers -c scrapers --follow --tail=100 {{ARGS}}

# After a change to the MCP tools: their snapshot (src/test/resources/mcp-tools.json) and version, by what the change is
# for assistants that still have the old ones (McpToolsTest, CLAUDE.md). --breaking for one that breaks them.
mcp-tools *BREAKING:
    #!/usr/bin/env bash
    set -euo pipefail
    mode=update
    if [ "{{BREAKING}}" = --breaking ]; then mode=breaking; elif [ -n "{{BREAKING}}" ]; then
      echo "Only --breaking, for a change that breaks assistants with the old tools" >&2
      exit 1
    fi
    ./gradlew test --tests si.ogrodje.metaos.McpToolsTest -PmcpTools=$mode

# architecture.png from architecture.svg, at twice the size so it stays sharp, by headless Chrome, which also loads the
# diagram's font. CHROME points at another Chrome than macOS's.
architecture:
    #!/usr/bin/env bash
    set -euo pipefail
    chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
    # The window is the drawing's size, from its <svg> element
    size=$(sed -nE 's/.*<svg [^>]*width="([0-9]+)" height="([0-9]+)".*/\1,\2/p' architecture.svg | head -1)
    profile=$(mktemp -d)
    trap 'rm -rf "$profile"' EXIT
    "$chrome" --headless --disable-gpu --hide-scrollbars --user-data-dir="$profile" \
      --force-device-scale-factor=2 --window-size="$size" --virtual-time-budget=5000 \
      --screenshot="$PWD/architecture.png" "file://$PWD/architecture.svg"

# A preview of this worktree on the tailnet, for trying a change out (CLAUDE.md's Previews, preview/preview.sh): `just
# preview up`, then the address it prints; `just preview down` once it's done with; `just preview ls`
preview *ARGS:
    preview/preview.sh {{ARGS}}

# Keycloak's realm metaos through Terraform (infra/keycloak/README.md): `just keycloak plan prod`, `just keycloak apply
# dev`, `just keycloak adopt dev` for a realm its state doesn't have yet, e.g. a new compose Keycloak's, and `just
# keycloak smtp-password prod` to send the SMTP password when a plain apply wouldn't (smtp-password.sh). Signs in to
# production as KEYCLOAK_ADMIN_USERNAME with KEYCLOAK_ADMIN_PASSWORD (.envrc), and to compose.yml's as admin / admin.
keycloak command env *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{env}}" in
      dev) export KEYCLOAK_ADMIN_USERNAME=admin KEYCLOAK_ADMIN_PASSWORD=admin ;;
      prod) export TF_VAR_keycloak_admin_username="${KEYCLOAK_ADMIN_USERNAME:?}" \
              TF_VAR_keycloak_admin_password="${KEYCLOAK_ADMIN_PASSWORD:?}" \
              GOOGLE_OAUTH_ACCESS_TOKEN="${GOOGLE_OAUTH_ACCESS_TOKEN:-$(gcloud auth print-access-token)}" ;;
      # The previews' Keycloak in Elysium (infra/elysium/factory/previews.tf), through a port-forward, as its console is
      # never routed; its admin's password from the cluster, its state in Cloud Storage
      preview)
        kube=(kubectl --kubeconfig "${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}")
        [[ $("${kube[@]}" config current-context) == vke-* ]] || { echo "Not Elysium's kubeconfig: just elysium-kubeconfig" >&2; exit 1; }
        password=$("${kube[@]}" -n previews get secret keycloak -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)
        export KEYCLOAK_ADMIN_USERNAME=admin KEYCLOAK_ADMIN_PASSWORD="$password" TF_VAR_keycloak_admin_password="$password" \
          GOOGLE_OAUTH_ACCESS_TOKEN="${GOOGLE_OAUTH_ACCESS_TOKEN:-$(gcloud auth print-access-token)}"
        "${kube[@]}" -n previews rollout status deployment/keycloak --timeout=5m >/dev/null
        "${kube[@]}" -n previews port-forward svc/keycloak 8189:8080 >/dev/null &
        forward=$!
        trap 'kill "$forward" 2>/dev/null' EXIT
        for i in $(seq 1 30); do curl -fsS -o /dev/null http://localhost:8189/auth/realms/master && break; sleep 1; done ;;
      *) echo "dev, prod or preview" >&2; exit 2 ;;
    esac
    # How Keycloak sends email, in development and production: secretspec.toml's KEYCLOAK_SMTP_* (OG-61). Previews send none
    if [ "{{env}}" != preview ]; then
      export TF_VAR_smtp_host="${KEYCLOAK_SMTP_HOST:?KEYCLOAK_SMTP_HOST, from secretspec.toml}" \
        TF_VAR_smtp_port="${KEYCLOAK_SMTP_PORT:?}" TF_VAR_smtp_email="${KEYCLOAK_SMTP_EMAIL:?}" \
        TF_VAR_smtp_password="${KEYCLOAK_SMTP_PASSWORD:?}"
    fi
    cd infra/keycloak/{{env}}
    terraform init -input=false >/dev/null
    # Not exec: the previews' port-forward stops when they're done
    if [ "{{command}}" = adopt ]; then ../import.sh {{env}}; exit; fi
    if [ "{{command}}" = smtp-password ]; then ../smtp-password.sh {{env}}; exit; fi
    terraform "{{command}}" {{ARGS}}

# A stack in infra/ with no environments of its own (infra/README.md): `just infra newrelic plan`, `just infra gcp
# apply`. Its state is in Cloud Storage, which Terraform reaches as you, signed in to gcloud; each stack's provider
# signs in with what .envrc has for it.
infra stack command *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{stack}}" in
      gcp) ;;
      newrelic) export NEW_RELIC_API_KEY="${NEW_RELIC_USER_KEY:?NEW_RELIC_USER_KEY, a User API key}" ;;
      cloudflare) : "${CLOUDFLARE_API_TOKEN:?}" ;;
      tailscale) : "${TAILSCALE_OAUTH_CLIENT_ID:?}" "${TAILSCALE_OAUTH_CLIENT_SECRET:?}" ;;
      elysium/cluster) : "${VULTR_API_KEY:?VULTR_API_KEY: secretspec set VULTR_API_KEY}" ;;
      elysium/factory)
        : "${VULTR_API_KEY:?VULTR_API_KEY: secretspec set VULTR_API_KEY}"
        # ogrodje.dev's Cloudflare account, not ogrodje.si's, whose token CLOUDFLARE_API_TOKEN otherwise is
        export CLOUDFLARE_API_TOKEN="${ELYSIUM_CLOUDFLARE_API_TOKEN:?ELYSIUM_CLOUDFLARE_API_TOKEN: secretspec set ELYSIUM_CLOUDFLARE_API_TOKEN}"
        export TF_VAR_factory_users="${FACTORY_USERS:?FACTORY_USERS: secretspec set FACTORY_USERS}"
        # The ogrodje organization's webhook (github.tf), as you: gh's token, with admin:org_hook
        # The org webhook's own token, as CI's: GitHub shows a webhook only to the kind of token that made it, so gh's
        # and this one would each make their own (infra/elysium/factory/github.tf)
        export GITHUB_TOKEN="${ELYSIUM_TERRAFORM_GITHUB_TOKEN:?ELYSIUM_TERRAFORM_GITHUB_TOKEN, the org webhook token (secretspec)}" ;;
      *) echo "gcp, newrelic, cloudflare, tailscale, elysium/cluster or elysium/factory (Keycloak's is just keycloak)" >&2; exit 2 ;;
    esac
    export GOOGLE_OAUTH_ACCESS_TOKEN="${GOOGLE_OAUTH_ACCESS_TOKEN:-$(gcloud auth print-access-token)}"
    cd infra/{{stack}}
    terraform init -input=false >/dev/null
    terraform "{{command}}" {{ARGS}}

# Elysium's admin kubeconfig (infra/elysium), from Vultr, into ~/.kube/elysium (or ELYSIUM_KUBECONFIG): outside the
# repository, and readable by you only. Then `KUBECONFIG=~/.kube/elysium kubectl ...`, or k9s with --kubeconfig
elysium-kubeconfig:
    #!/usr/bin/env bash
    set -euo pipefail
    api=https://api.vultr.com/v2/kubernetes/clusters
    auth="Authorization: Bearer ${VULTR_API_KEY:?VULTR_API_KEY: secretspec set VULTR_API_KEY}"
    id=$(curl -sSf -H "$auth" "$api?per_page=100" | jq -r '.vke_clusters[] | select(.label == "elysium") | .id')
    [ -n "$id" ] || { echo "No VKE cluster labelled elysium: just infra elysium/cluster apply" >&2; exit 1; }
    file="${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}"
    mkdir -p "$(dirname "$file")"
    umask 077
    curl -sSf -H "$auth" "$api/$id/config" | jq -r .kube_config | base64 -d > "$file"
    echo "$file: cluster $id"

# The Vultr registry's push login (ams.vultrcr.com/ogrodje, infra/elysium/cluster/registry.tf), into Docker for an
# hour: for pushing an image built here. Its pods pull with a read-only one (factory/set_secrets.sh: vultr-registry)
registry-login:
    #!/usr/bin/env bash
    set -euo pipefail
    auth="Authorization: Bearer ${VULTR_API_KEY:?VULTR_API_KEY: secretspec set VULTR_API_KEY}"
    id=$(curl -sSf -H "$auth" https://api.vultr.com/v2/registries | jq -r '.registries[] | select(.name == "ogrodje") | .id')
    login=$(curl -sSf -X OPTIONS -H "$auth" \
      "https://api.vultr.com/v2/registry/$id/docker-credentials?expiry_seconds=3600&read_write=true" |
      jq -r '.auths["ams.vultrcr.com"].auth' | base64 -d)
    printf '%s' "${login#*:}" | docker login ams.vultrcr.com --username "${login%%:*}" --password-stdin

# Elysium's Canvas image, as CI names it (.github/workflows/elysium.yml): the tag for infra/elysium/factory's
# canvas_image once the workflow has pushed this Dockerfile
canvas-image:
    @echo "ams.vultrcr.com/ogrodje/elysium-canvas:$(sed -n 's/^ARG CANVAS=.*://p' elysium/canvas/Dockerfile)-$(git hash-object elysium/canvas/Dockerfile | cut -c1-8)"

# A Canvas (infra/elysium/README.md), the team's or a person's, on http://localhost:<port>/canvas/ for as long as this
# runs: a port-forward, past Cloudflare Access, e.g. when it's down. Its page carries its session key
canvas name="team" port="8800":
    #!/usr/bin/env bash
    set -euo pipefail
    service=canvas; [ "{{name}}" = team ] || service="canvas-{{name}}"
    echo "http://localhost:{{port}}/canvas/"
    KUBECONFIG="${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}" kubectl port-forward -n factory "svc/$service" {{port}}:8000

# Elysium in Linear: OAuth app "Elysium" in workspace ogrodje (Settings > API), private to it, with client credentials.
# Its client ID isn't secret; its client secret is ELYSIUM_LINEAR_CLIENT_SECRET (secretspec.toml)
elysium_linear_client_id := "1fad2d2ffaf2bf8a19da97b44790cdff"

# Client credentials: actor app, 30 days, no refresh. Its scopes are Elysium's install in Linear: with app:assignable
# and app:mentionable it's an Agent, which can be delegated issues and mentioned, taking no seat; the same in every
# token, as Linear doesn't change an install's scopes. A new one each run; the old ones work until they expire.
# Elysium's Linear token onto the clipboard (macOS), for Canvas' Linear MCP server (https://mcp.linear.app/mcp, Bearer)
elysium-linear-token:
    #!/usr/bin/env bash
    set -euo pipefail
    response=$(curl -sS https://api.linear.app/oauth/token \
      --data-urlencode grant_type=client_credentials \
      --data-urlencode "scope=read,write,app:assignable,app:mentionable" \
      --data-urlencode client_id={{elysium_linear_client_id}} \
      --data-urlencode "client_secret=${ELYSIUM_LINEAR_CLIENT_SECRET:?ELYSIUM_LINEAR_CLIENT_SECRET: secretspec set ELYSIUM_LINEAR_CLIENT_SECRET}")
    token=$(jq -r '.access_token // empty' <<<"$response")
    [ -n "$token" ] || { jq '{error, error_description}' <<<"$response" >&2; exit 1; }
    viewer=$(curl -sS https://api.linear.app/graphql -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      -d '{"query":"{ viewer { name } organization { name } }"}' | jq -r '"\(.data.viewer.name) in \(.data.organization.name)"')
    printf '%s' "$token" | pbcopy
    echo "Elysium's Linear token ($viewer, $(( $(jq -r .expires_in <<<"$response") / 86400 )) days) is on the clipboard"

# Every Canvas' settings (LLM, Elysium's identity, MCP servers, the previews' manager), now: the weekly CronJob
# canvas-settings (infra/elysium/factory/canvas_settings.tf) run once. After a new Canvas, or a token set_secrets.sh changed
canvas-settings:
    #!/usr/bin/env bash
    set -euo pipefail
    k=(kubectl --kubeconfig "${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}" -n factory)
    [[ $("${k[@]}" config current-context) == vke-* ]] || { echo "Not Elysium's kubeconfig: just elysium-kubeconfig" >&2; exit 1; }
    job="canvas-settings-$(date +%s)"
    "${k[@]}" create job --from=cronjob/canvas-settings "$job" >/dev/null
    trap '"${k[@]}" delete job "$job" --wait=false >/dev/null' EXIT
    for i in $(seq 1 100); do
      [ "$("${k[@]}" get job "$job" -o jsonpath='{.status.succeeded}')" = 1 ] && { "${k[@]}" logs "job/$job"; exit 0; }
      [ -n "$("${k[@]}" get job "$job" -o jsonpath='{.status.failed}')" ] && break
      sleep 3
    done
    "${k[@]}" logs "job/$job" >&2 || true
    echo "canvas-settings didn't finish: kubectl -n factory get jobs" >&2
    exit 1

# LiteLLM's key, for Canvas' LLM settings (infra/elysium/README.md), onto the clipboard (macOS)
litellm-key:
    KUBECONFIG="${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}" kubectl get secret -n factory litellm \
      -o jsonpath='{.data.LITELLM_MASTER_KEY}' | base64 -d | pbcopy
    @echo "LiteLLM's key is on the clipboard"

# tflint over every stack in infra/ (infra/.tflint.hcl), as CI does; the first run installs its plugins
tflint:
    #!/usr/bin/env bash
    set -euo pipefail
    cd infra
    GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token 2>/dev/null || true)}" tflint --init --config "$PWD/.tflint.hcl" >/dev/null
    tflint --recursive --config "$PWD/.tflint.hcl"

# The tailnet's policy file, infra/tailscale/policy.hujson, from Tailscale, on a machine that hasn't got it: it's
# private, so never committed (infra/tailscale/README.md). --force replaces the one there with Tailscale's, e.g. after
# a change made in Tailscale's console.
tailscale-policy *FORCE:
    #!/usr/bin/env bash
    set -euo pipefail
    file=infra/tailscale/policy.hujson
    if [ -e "$file" ] && [ "{{FORCE}}" != --force ]; then
      echo "$file is there already: --force replaces it with Tailscale's" >&2
      exit 1
    fi
    token=$(curl -sSf https://api.tailscale.com/api/v2/oauth/token \
      -d "client_id=${TAILSCALE_OAUTH_CLIENT_ID:?}" -d "client_secret=${TAILSCALE_OAUTH_CLIENT_SECRET:?}" | jq -r .access_token)
    umask 077
    curl -sSf -H "Authorization: Bearer $token" -H "Accept: application/hujson" \
      https://api.tailscale.com/api/v2/tailnet/-/acl -o "$file"
    echo "$file: the tailnet's policy, as Tailscale has it"

# A key for an environment's storage service account (infra/gcp), as lines for .envrc: `just storage-key dev >>
# .envrc`; prod's is PROD_GOOGLE_STORAGE_KEY, which k8s/set_secrets.sh puts into metaos-secret. Terraform makes no keys,
# so none is in its state. Each run makes another, and the older ones keep working, up to 10 per service account:
# delete those no longer used with `gcloud iam service-accounts keys list|delete --iam-account=...`.
storage-key env:
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{env}}" in
      dev) variable=GOOGLE_STORAGE_KEY ;;
      prod) variable=PROD_GOOGLE_STORAGE_KEY ;;
      *) echo "dev or prod" >&2; exit 2 ;;
    esac
    account=metaos-storage-{{env}}@ogrodje-metaos.iam.gserviceaccount.com
    key=$(mktemp)
    trap 'rm -f "$key"' EXIT
    # Everything but the export lines goes to stderr, so they can be appended to .envrc as they are
    CLOUDSDK_CORE_PROJECT=ogrodje-metaos CLOUDSDK_BILLING_QUOTA_PROJECT=ogrodje-metaos \
      gcloud iam service-accounts keys create "$key" --iam-account="$account"
    echo
    echo "# Google Cloud Storage: key $(jq -r .private_key_id "$key") of $account (just storage-key {{env}})"
    if [ "{{env}}" = dev ]; then
      echo "export METAOS_STORAGE_BUCKET=ogrodje-metaos-dev"
      echo "export METAOS_MEDIA_BUCKET=ogrodje-metaos-public-dev"
    fi
    # One line, which single quotes keep as it is: the private key's newlines are \n in JSON
    echo "export $variable='$(jq -c . "$key")'"
