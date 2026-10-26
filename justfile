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

# docs/episode-workflow.png from the chart in docs/episode-workflow.md (its mermaid block), at twice the size, by
# Mermaid's CLI in headless Chrome, on a page wide enough that the landscape chart isn't shrunk to fit and which loads
# Inter, the font of architecture.png. CHROME points at another Chrome than macOS's.
episode-workflow:
    #!/usr/bin/env bash
    set -euo pipefail
    chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    awk '/^```mermaid$/ { on = 1; next } on && /^```$/ { exit } on' docs/episode-workflow.md > "$work/chart.mmd"
    test -s "$work/chart.mmd" || { echo "No mermaid block in docs/episode-workflow.md" >&2; exit 1; }
    # Puppeteer uses that Chrome rather than downloading its own
    printf '{"executablePath": "%s"}\n' "$chrome" > "$work/puppeteer.json"
    echo "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&display=swap');" > "$work/page.css"
    PUPPETEER_SKIP_DOWNLOAD=1 npx -y @mermaid-js/mermaid-cli@11.17.0 --quiet -p "$work/puppeteer.json" \
      -C "$work/page.css" -i "$work/chart.mmd" -o "$PWD/docs/episode-workflow.png" -w 2000 -s 2 -b white
