# MetaOS images and their deploy (k8s/README.md). The registry and the cluster are on the tailnet, so these need
# Tailscale up. Images are tagged with `git describe`, as .github/workflows/deploy.yml does.

registry := "registry.folk-decibel.ts.net"
app_image := registry / "metaos/metaos"
web_image := registry / "metaos/metaos-web"
keycloak_image := registry / "metaos/keycloak"
gateway_image := registry / "metaos/patreon-gateway"
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

# linux/amd64 is what the cluster runs.
# Build the images locally.
images: jar web gateway
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

# Build and push the images, and point the kustomization at the new tag.
push: jar web gateway
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
    cd k8s/base && kustomize edit set image "{{app_image}}:$TAG" "{{web_image}}:$TAG" "{{keycloak_image}}:$TAG" "{{gateway_image}}:$TAG"

# Apply the manifests and wait for the rollout.
deploy:
    kubectl apply -k k8s/base/
    kubectl rollout status deployment/keycloak -n {{ns}} --timeout=5m
    kubectl rollout status deployment/metaos -n {{ns}} --timeout=5m
    kubectl rollout status deployment/metaos-web -n {{ns}} --timeout=5m
    kubectl rollout status deployment/patreon-gateway -n {{ns}} --timeout=5m

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

# Keycloak's realm metaos through Terraform (infra/keycloak/README.md): `just keycloak plan prod`, `just keycloak apply
# dev`, and `just keycloak adopt dev` for a realm its state doesn't have yet, e.g. a new compose Keycloak's. Signs in to
# production as KEYCLOAK_ADMIN_USERNAME with KEYCLOAK_ADMIN_PASSWORD (.envrc), and to compose.yml's as admin / admin.
keycloak command env *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{env}}" in
      dev) export KEYCLOAK_ADMIN_USERNAME=admin KEYCLOAK_ADMIN_PASSWORD=admin ;;
      prod) export TF_VAR_keycloak_admin_username="${KEYCLOAK_ADMIN_USERNAME:?}" \
              TF_VAR_keycloak_admin_password="${KEYCLOAK_ADMIN_PASSWORD:?}" \
              GOOGLE_OAUTH_ACCESS_TOKEN="${GOOGLE_OAUTH_ACCESS_TOKEN:-$(gcloud auth print-access-token)}" ;;
      *) echo "dev or prod" >&2; exit 2 ;;
    esac
    cd infra/keycloak/{{env}}
    terraform init -input=false >/dev/null
    if [ "{{command}}" = adopt ]; then exec ../import.sh {{env}}; fi
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
      *) echo "gcp, newrelic, cloudflare or tailscale (Keycloak's is just keycloak)" >&2; exit 2 ;;
    esac
    export GOOGLE_OAUTH_ACCESS_TOKEN="${GOOGLE_OAUTH_ACCESS_TOKEN:-$(gcloud auth print-access-token)}"
    cd infra/{{stack}}
    terraform init -input=false >/dev/null
    terraform "{{command}}" {{ARGS}}

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
