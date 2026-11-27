#!/usr/bin/env bash
# Previews: a worktree's MetaOS and front-end, built here and run in Elysium (infra/elysium/factory/previews.tf), at
# https://preview-N.ogrodje.dev behind Cloudflare Access, for the team to try a change out before it's merged
# (CLAUDE.md's Previews). `just preview <command>`, or preview/preview.sh <command> where there's no just:
#
#   up [--fresh] [--slot N]   build this worktree and run it in its slot (the one it has, else a free one); again to
#                             update it, keeping its data, or --fresh to start its data over from seed.sql
#   down [N]                  done: stop this worktree's preview (or slot N's), and clear its build and data
#   ls                        the slots, and whose they are
#   logs [api|web|postgres|seed] [N] [-f]
#                             a preview's log, the last 200 lines, or followed
#
# Elysium's previews' manager (elysium/previews/manager.ts) runs the build in a slot of its own, with a Postgres of its
# own seeded from preview/seed.sql, and clears it once it's done with: `down`, its branch's pull request closed, or a
# day nobody updated it. In a Canvas, PREVIEWS_URL and PREVIEWS_TOKEN say where it is (its secrets); elsewhere, with
# Elysium's kubeconfig (`just elysium-kubeconfig`), this reaches it through a port-forward.
#
# Previews run with none of the integrations' secrets (Drive, Dropbox, Koofr, Patreon, OpenRouter), Temporal or
# Unleash: they act on nothing real. Cloud Storage is the exception, development's buckets only (ogrodje-metaos-dev and
# -public-dev, whose CORS has the slots' addresses, infra/gcp), so a preview publishes pictures and takes uploads.
# Runs on Linux and macOS (bash 3.2).
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
OWNER="$(hostname -s 2>/dev/null || hostname):$ROOT"
FORWARD=""
BUILD=""

say() { printf '%s\n' "$*" >&2; }
cleanup() {
  [ -z "$FORWARD" ] || kill "$FORWARD" 2>/dev/null || true
  [ -z "$BUILD" ] || rm -rf "$BUILD"
}
die() { say "preview: $*"; exit 1; }

# PREVIEWS_URL and PREVIEWS_TOKEN: a Canvas' own, else a port-forward to the manager with Elysium's kubeconfig
connect() {
  if [ -n "${PREVIEWS_URL:-}" ]; then
    : "${PREVIEWS_TOKEN:?PREVIEWS_TOKEN: a secret of the Canvas, with PREVIEWS_URL}"
    return
  fi
  local kubeconfig="${ELYSIUM_KUBECONFIG:-$HOME/.kube/elysium}" port i
  [ -f "$kubeconfig" ] || die "no PREVIEWS_URL, and no Elysium kubeconfig at $kubeconfig: just elysium-kubeconfig"
  case "$(kubectl --kubeconfig "$kubeconfig" config current-context)" in
    vke-*) ;;
    *) die "$kubeconfig isn't Elysium's: just elysium-kubeconfig" ;;
  esac
  PREVIEWS_TOKEN=$(kubectl --kubeconfig "$kubeconfig" -n previews get secret previews-manager \
    -o jsonpath='{.data.PREVIEWS_TOKEN}' | base64 -d)
  port=$((20000 + RANDOM % 20000))
  kubectl --kubeconfig "$kubeconfig" -n previews port-forward svc/manager "$port:8080" >/dev/null 2>&1 &
  FORWARD=$!
  trap cleanup EXIT
  PREVIEWS_URL="http://127.0.0.1:$port"
  for i in $(seq 1 30); do
    curl -fsS -o /dev/null "$PREVIEWS_URL/healthz" 2>/dev/null && return 0
    sleep 1
  done
  die "the previews' manager isn't answering (kubectl -n previews get pods)"
}

# api METHOD PATH [curl's args]: the manager's answer, failing with its error on a status that isn't 2xx
api() {
  local method=$1 path=$2 out status
  shift 2
  out=$(curl -sS -X "$method" -H "Authorization: Bearer $PREVIEWS_TOKEN" -w '\n%{http_code}' "$@" "$PREVIEWS_URL$path")
  status=${out##*$'\n'}
  out=${out%$'\n'*}
  case "$status" in
    2*) printf '%s' "$out" ;;
    *) die "$(printf '%s' "$out" | jq -r '.error // .' 2>/dev/null || printf '%s' "$out") (HTTP $status)" ;;
  esac
}

uri() { jq -rn --arg v "$1" '$v | @uri'; }

slots() { api GET /slots; }

own_slot() { slots | jq -r --arg o "$OWNER" '[.[] | select(.owner == $o) | .slot][0] // empty'; }

cmd_ls() {
  slots | jq -r '.[] | if .state == "free" then "slot \(.slot)  free        \(.url)"
    else "slot \(.slot)  \(.state)\(if .detail then " (" + .detail + ")" else "" end)  \(.url)  \(.branch) @ \(.commit)  (\(.owner), updated \(.updated / 1000 | strftime("%Y-%m-%d %H:%M")))" end'
}

cmd_down() {
  local n=${1:-}
  if [ -n "$n" ]; then
    api DELETE "/slots/$n" >/dev/null
  else
    n=$(own_slot)
    [ -n "$n" ] || die "this worktree ($OWNER) has no slot; \`preview down N\` takes slot N down"
    api DELETE "/slots?owner=$(uri "$OWNER")" >/dev/null
  fi
  say "Slot $n is free: its preview stopped, its build and data cleared."
}

cmd_logs() {
  local container=api n="" follow=0 arg
  for arg in "$@"; do
    case "$arg" in
      -f) follow=1 ;;
      api | web | postgres | seed | unpack) container=$arg ;;
      [0-9]*) n=$arg ;;
      *) die "logs [api|web|postgres|seed] [N] [-f]" ;;
    esac
  done
  [ -n "$n" ] || n=$(own_slot)
  [ -n "$n" ] || die "this worktree has no slot; \`preview logs api|web N\`"
  curl -sS -N -H "Authorization: Bearer $PREVIEWS_TOKEN" \
    "$PREVIEWS_URL/slots/$n/logs?container=$container&follow=$follow&tail=200"
}

cmd_up() {
  local fresh=0 want=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --fresh) fresh=1 ;;
      --slot) want=$2; shift ;;
      *) die "up: unknown option $1" ;;
    esac
    shift
  done
  local branch commit
  branch=$(git rev-parse --abbrev-ref HEAD)
  commit=$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- 2>/dev/null || echo "+")

  BUILD=$(mktemp -d)
  trap cleanup EXIT
  local dir=$BUILD

  say "Building MetaOS (bootJar) and the front-end (nuxt build), $branch @ $commit"
  ./gradlew -q bootJar >"$dir/build.log" 2>&1 || { tail -30 "$dir/build.log" >&2; die "bootJar failed"; }
  ( cd fe && { [ -d node_modules ] || yarn install --immutable; } && yarn nuxt build ) >>"$dir/build.log" 2>&1 ||
    { tail -30 "$dir/build.log" >&2; die "nuxt build failed"; }

  mkdir "$dir/build"
  cp build/libs/metaos.jar preview/seed.sql "$dir/build/"
  cp -R fe/.output "$dir/build/web"
  tar -czf "$dir/build.tar.gz" -C "$dir/build" .
  say "Uploading it ($(du -h "$dir/build.tar.gz" | cut -f1 | tr -d ' '))"

  local query answer n url internal
  query="owner=$(uri "$OWNER")&branch=$(uri "$branch")&commit=$(uri "$commit")&fresh=$fresh"
  [ -z "$want" ] || query="$query&slot=$want"
  answer=$(api PUT "/slots?$query" -H "Content-Type: application/gzip" -T "$dir/build.tar.gz")
  n=$(jq -r .slot <<<"$answer") url=$(jq -r .url <<<"$answer") internal=$(jq -r .internal <<<"$answer")
  say "Slot $n: $url, starting"

  local state detail last="" i
  for i in $(seq 1 120); do
    read -r state detail < <(slots | jq -r --argjson n "$n" '.[] | select(.slot == $n) | "\(.state) \(.detail // "")"') || true
    if [ "$state $detail" != "$last" ]; then say "  $state${detail:+: $detail}"; last="$state $detail"; fi
    case "$state" in
      up) break ;;
      failed)
        cmd_logs api "$n" | tail -40 >&2
        die "slot $n's preview failed: $detail (preview logs api|web|postgres $n)"
        ;;
    esac
    sleep 3
  done
  [ "$state" = up ] || die "slot $n's preview didn't come up in 6 minutes (preview logs api $n)"

  cat <<EOF

Preview up in slot $n: $url
  Sign in as dev / dev (staff). Behind Cloudflare Access, for the team.
  From a Canvas, without Access: $internal
  Logs: preview logs api|web   Update: preview up   Done: preview down
EOF
}

command=${1:-ls}
shift || true
case "$command" in
  up | down | ls | logs) connect; "cmd_$command" "$@" ;;
  *) die "up, down, ls or logs (preview/preview.sh says what each does)" ;;
esac
