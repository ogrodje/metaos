#!/usr/bin/env bash
# Previews: a worktree's MetaOS and front-end, built and run on this machine, at an address on the tailnet only, for the
# human and the agents to try a change out before it's merged (CLAUDE.md's Previews). `just preview <command>`:
#
#   up [--fresh] [--slot N]   build this worktree and run it in its slot (the one it has, else a free one); again to
#                             update it, keeping its data, or --fresh to start its data over from seed.sql
#   down [N]                  stop this worktree's preview (or slot N's), take its address down and drop its data
#   ls                        the slots, and whose they are
#   logs [api|web] [N]        follow a preview's log
#   reap [HOURS]              take down previews nobody has updated for HOURS (24), which every `up` does first
#
# A machine has two slots, so their addresses never change and Keycloak knows them (infra/keycloak/preview). Slot N:
#
#   https://<machine>.<tailnet>.ts.net:944N   tailscale serve, tailnet only: / to its front-end, /auth/realms and
#                                             /auth/resources to the previews' Keycloak, never its admin console
#   127.0.0.1:519N                            its front-end, the built Nuxt server (fe/.output), which passes the API's
#                                             paths on to its MetaOS
#   127.0.0.1:809N                            its MetaOS, the boot jar, in database metaos_preview_N of compose's Postgres
#   127.0.0.1:8189                            compose.yml's keycloak-preview, all slots'
#
# Previews run with none of the integrations' secrets (Drive, Dropbox, Koofr, Patreon, OpenRouter), Temporal or
# Unleash, which this script doesn't pass on: they act on nothing real. Cloud Storage is the exception, development's
# buckets only (ogrodje-metaos-dev and -public-dev, which are for development and tests, and whose CORS has the slots'
# addresses, infra/gcp): with the shell's key for them, a preview publishes pictures and takes uploads; never with
# another bucket's. Never `tailscale serve reset`, which
# would also take down what else this machine serves (nuc8's Funnel on 443): each slot takes down only its own port.
# Runs on Linux and macOS (bash 3.2).
set -euo pipefail

SLOTS="1 2"
KEYCLOAK_PORT=8189
# The only buckets a preview may use (infra/gcp: development's)
DEV_BUCKET=ogrodje-metaos-dev
DEV_MEDIA_BUCKET=ogrodje-metaos-public-dev
STATE="${METAOS_PREVIEW_STATE:-$HOME/.local/state/metaos-previews}"
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

public_port() { echo $((9440 + $1)); }
web_port() { echo $((5190 + $1)); }
api_port() { echo $((8090 + $1)); }
database() { echo "metaos_preview_$1"; }
slot_dir() { echo "$STATE/slot-$1"; }

say() { printf '%s\n' "$*" >&2; }
die() { say "preview: $*"; exit 1; }

host() {
  tailscale status --json | jq -r '.Self.DNSName | rtrimstr(".")'
}

# psql_ DATABASE [ARGS] - psql in compose's Postgres, reading stdin
psql_() {
  local db=$1
  shift
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$db" "$@"
}

# lease N VAR - a value of slot N's lease, which `up` writes
lease() {
  local file
  file="$(slot_dir "$1")/lease"
  [ -f "$file" ] || return 0
  sed -n "s/^$2=//p" "$file" | head -1
}

# when SECONDS - a time, as GNU's date says it, else BSD's (macOS)
when() {
  [ -n "$1" ] || { echo '?'; return; }
  date -d "@$1" '+%Y-%m-%d %H:%M' 2>/dev/null || date -r "$1" '+%Y-%m-%d %H:%M'
}

alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

stop_pid() {
  local pid=$1 i
  alive "$pid" || return 0
  kill "$pid" 2>/dev/null || true
  for i in $(seq 1 30); do alive "$pid" || return 0; sleep 1; done
  kill -9 "$pid" 2>/dev/null || true
}

# wait_for URL SECONDS PID - until URL answers 2xx, or PID dies
wait_for() {
  local url=$1 seconds=$2 pid=$3 i
  for i in $(seq 1 "$seconds"); do
    curl -fsS -o /dev/null --max-time 2 "$url" 2>/dev/null && return 0
    alive "$pid" || return 1
    sleep 1
  done
  return 1
}

# The slot this worktree has, if any
own_slot() {
  local n
  for n in $SLOTS; do
    [ "$(lease "$n" WORKTREE)" = "$ROOT" ] && { echo "$n"; return; }
  done
  return 0
}

# claim N - takes slot N for this worktree, if it's free; mkdir is atomic, so two agents can't both take it
claim() {
  mkdir -p "$STATE"
  mkdir "$(slot_dir "$1")" 2>/dev/null
}

stop_processes() {
  stop_pid "$(lease "$1" WEB_PID)"
  stop_pid "$(lease "$1" API_PID)"
}

serve_off() {
  local port path
  port=$(public_port "$1")
  for path in /auth/realms /auth/resources /; do
    tailscale serve --https="$port" --set-path="$path" off >/dev/null 2>&1 || true
  done
}

down_slot() {
  local n=$1
  [ -d "$(slot_dir "$n")" ] || { say "Slot $n is free."; return 0; }
  say "Taking slot $n down ($(lease "$n" BRANCH))"
  serve_off "$n"
  stop_processes "$n"
  echo "DROP DATABASE IF EXISTS $(database "$n") WITH (FORCE);" | psql_ "$POSTGRES_DB" || say "  couldn't drop $(database "$n")"
  rm -rf "$(slot_dir "$n")"
}

cmd_ls() {
  local n url
  for n in $SLOTS; do
    if [ ! -d "$(slot_dir "$n")" ]; then
      printf 'slot %s  free\n' "$n"
      continue
    fi
    url=$(lease "$n" URL)
    local state=up
    alive "$(lease "$n" API_PID)" && alive "$(lease "$n" WEB_PID)" || state=stopped
    printf 'slot %s  %-7s %s  %s  (%s, updated %s)\n' "$n" "$state" "${url:-?}" "$(lease "$n" BRANCH)" \
      "$(lease "$n" WORKTREE)" "$(when "$(lease "$n" STARTED)")"
  done
}

cmd_reap() {
  local hours=${1:-24} n started now
  now=$(date +%s)
  for n in $SLOTS; do
    [ -d "$(slot_dir "$n")" ] || continue
    started=$(lease "$n" STARTED)
    # A slot claimed without a lease is an `up` that died before it got far: its directory's age says how old
    [ -n "$started" ] || started=$(stat -c %Y "$(slot_dir "$n")" 2>/dev/null || stat -f %m "$(slot_dir "$n")")
    if [ $((now - started)) -gt $((hours * 3600)) ]; then
      say "Slot $n wasn't updated for over ${hours}h:"
      down_slot "$n"
    fi
  done
}

cmd_down() {
  local n=${1:-$(own_slot)}
  [ -n "$n" ] || die "this worktree ($ROOT) has no slot; \`just preview down N\` takes slot N down"
  down_slot "$n"
}

cmd_logs() {
  local which=${1:-api} n=${2:-$(own_slot)}
  [ -n "$n" ] || die "this worktree has no slot; \`just preview logs api|web N\`"
  exec tail -n 200 -F "$(slot_dir "$n")/$which.log"
}

ensure_keycloak() {
  docker compose --profile preview up -d --wait keycloak-preview >/dev/null 2>&1 ||
    die "the previews' Keycloak didn't start: docker compose --profile preview up keycloak-preview"
  curl -fsS -o /dev/null "http://127.0.0.1:$KEYCLOAK_PORT/auth/realms/metaos" ||
    die "the previews' Keycloak has no realm metaos yet: just keycloak adopt preview && just keycloak apply preview"
}

cmd_up() {
  local fresh=false want=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --fresh) fresh=true ;;
      --slot) want=$2; shift ;;
      *) die "up: unknown option $1" ;;
    esac
    shift
  done
  : "${POSTGRES_USER:?POSTGRES_USER, from the devenv shell}" "${POSTGRES_PASSWORD:?}"

  cmd_reap 24

  local n
  n=$(own_slot)
  if [ -n "$want" ] && [ "$want" != "$n" ]; then
    [ -z "$n" ] || die "this worktree has slot $n already; \`just preview down\` first"
    claim "$want" || die "slot $want is taken: $(cmd_ls | grep "^slot $want")"
    n=$want
  elif [ -z "$n" ]; then
    for want in $SLOTS; do claim "$want" && { n=$want; break; }; done
    [ -n "$n" ] || { cmd_ls >&2; die "both slots are taken; \`just preview down N\` frees one once it's done with"; }
  fi

  local dir url web api db
  dir=$(slot_dir "$n")
  # Whose it is, from the start, so a failed build still leaves the slot this worktree's to try again
  [ -f "$dir/lease" ] || printf 'WORKTREE=%s\nBRANCH=%s\nSTARTED=%s\n' "$ROOT" \
    "$(git rev-parse --abbrev-ref HEAD)" "$(date +%s)" >"$dir/lease"
  url="https://$(host):$(public_port "$n")" web=$(web_port "$n") api=$(api_port "$n") db=$(database "$n")
  say "Slot $n: $url, for $(git rev-parse --abbrev-ref HEAD) ($ROOT)"

  ensure_keycloak

  # Built before the old preview stops, so a failed build leaves it running
  say "Building MetaOS (bootJar) and the front-end (nuxt build)"
  ./gradlew -q bootJar >"$dir/build.log" 2>&1 || { tail -30 "$dir/build.log" >&2; die "bootJar failed ($dir/build.log)"; }
  ( cd fe && { [ -d node_modules ] || yarn install --immutable; } && yarn nuxt build ) >>"$dir/build.log" 2>&1 ||
    { tail -30 "$dir/build.log" >&2; die "nuxt build failed ($dir/build.log)"; }

  stop_processes "$n"
  for port in "$web" "$api"; do
    listening "$port" && die "port $port is in use by something else than this slot's preview"
  done
  cp build/libs/metaos.jar "$dir/metaos.jar"
  rm -rf "$dir/web" && cp -R fe/.output "$dir/web"

  local created=false
  if $fresh; then echo "DROP DATABASE IF EXISTS $db WITH (FORCE);" | psql_ "$POSTGRES_DB"; fi
  if [ -z "$(echo "SELECT 1 FROM pg_database WHERE datname = '$db'" | psql_ "$POSTGRES_DB" -At)" ]; then
    echo "CREATE DATABASE $db;" | psql_ "$POSTGRES_DB"
    created=true
  fi

  # Only what a preview needs: not the shell's integrations' secrets, Temporal's address or Unleash's token; but
  # development's buckets, when the shell has their key, and only theirs
  local issuer="$url/auth/realms/metaos" storage=()
  if [ -n "${GOOGLE_STORAGE_KEY:-}" ] && [ "${METAOS_STORAGE_BUCKET:-}" = "$DEV_BUCKET" ] &&
     [ "${METAOS_MEDIA_BUCKET:-}" = "$DEV_MEDIA_BUCKET" ]; then
    storage=(GOOGLE_STORAGE_KEY="$GOOGLE_STORAGE_KEY" METAOS_STORAGE_BUCKET="$DEV_BUCKET" METAOS_MEDIA_BUCKET="$DEV_MEDIA_BUCKET")
    say "Cloud Storage: $DEV_BUCKET and $DEV_MEDIA_BUCKET"
  else
    say "Cloud Storage: off (the shell has no key for $DEV_BUCKET and $DEV_MEDIA_BUCKET)"
  fi
  nohup env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-C.UTF-8}" \
    POSTGRES_HOST=localhost POSTGRES_PORT=5437 POSTGRES_DB="$db" \
    POSTGRES_USER="$POSTGRES_USER" POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    SERVER_PORT="$api" KEYCLOAK_ISSUER_URI="$issuer" \
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI="http://127.0.0.1:$KEYCLOAK_PORT/auth/realms/metaos/protocol/openid-connect/certs" \
    ${storage[@]+"${storage[@]}"} \
    java -Xmx1g -jar "$dir/metaos.jar" >"$dir/api.log" 2>&1 &
  local api_pid=$!
  nohup env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-C.UTF-8}" \
    HOST=127.0.0.1 PORT="$web" \
    NUXT_METAOS_URL="http://127.0.0.1:$api" NUXT_PUBLIC_ISSUER="$issuer" \
    NUXT_PUBLIC_SITE_URL="$url" NUXT_PUBLIC_MCP_URL="$url/mcp" \
    node "$dir/web/server/index.mjs" >"$dir/web.log" 2>&1 &
  local web_pid=$!

  cat >"$dir/lease" <<EOF
WORKTREE=$ROOT
BRANCH=$(git rev-parse --abbrev-ref HEAD)
COMMIT=$(git rev-parse --short HEAD)
STARTED=$(date +%s)
URL=$url
API_PID=$api_pid
WEB_PID=$web_pid
EOF

  say "Starting (logs: just preview logs api|web)"
  wait_for "http://127.0.0.1:$api/readyz" 180 "$api_pid" ||
    { tail -40 "$dir/api.log" >&2; die "MetaOS didn't start ($dir/api.log)"; }
  wait_for "http://127.0.0.1:$web/healthz" 60 "$web_pid" ||
    { tail -40 "$dir/web.log" >&2; die "the front-end didn't start ($dir/web.log)"; }

  if $created; then
    say "Seeding $db (preview/seed.sql)"
    psql_ "$db" <preview/seed.sql
  fi

  local port
  port=$(public_port "$n")
  tailscale serve --bg --yes --https="$port" "http://127.0.0.1:$web" >/dev/null
  tailscale serve --bg --yes --https="$port" --set-path=/auth/realms \
    "http://127.0.0.1:$KEYCLOAK_PORT/auth/realms" >/dev/null
  tailscale serve --bg --yes --https="$port" --set-path=/auth/resources \
    "http://127.0.0.1:$KEYCLOAK_PORT/auth/resources" >/dev/null

  cat <<EOF

Preview up in slot $n: $url
  Sign in as dev / dev (staff). On the tailnet only.
  Logs: just preview logs api|web   Update: just preview up   Done: just preview down
EOF
}

command=${1:-ls}
shift || true
case "$command" in
  up) cmd_up "$@" ;;
  down) cmd_down "$@" ;;
  ls) cmd_ls ;;
  logs) cmd_logs "$@" ;;
  reap) cmd_reap "$@" ;;
  *) die "up, down, ls, logs or reap (preview/preview.sh says what each does)" ;;
esac
