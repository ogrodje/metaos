#!/usr/bin/env bash
# MetaOS's own files - pictures, videos, anything - in Google Cloud Storage, in project GOOGLE_CLOUD_PROJECT
# (ogrodje-metaos). Needs gcloud (in the devenv shell), signed in as an owner of the project: `gcloud auth login`.
#
#   google/storage.sh setup               the buckets and their service accounts; idempotent, re-run after editing
#   google/storage.sh key dev >> .envrc   a key for dev's service account (GOOGLE_STORAGE_KEY, METAOS_STORAGE_BUCKET,
#                                         METAOS_MEDIA_BUCKET)
#   google/storage.sh key prod >> .envrc  one for production's (PROD_GOOGLE_STORAGE_KEY), which k8s/set_secrets.sh
#                                         puts into metaos-secret
#
# Two buckets per environment, in Frankfurt, and a service account that can use only those two - their files, not
# their settings:
#   ogrodje-metaos-dev          development and tests. Tests write under test/, which is emptied after a day; no soft
#   ogrodje-metaos-public-dev   delete.
#   ogrodje-metaos-prod         production. Deleted and replaced files stay recoverable for 30 days (soft delete);
#   media.ogrodje.si            Autoclass moves files nobody reads to cheaper storage classes, without retrieval fees.
# The first of each is private (public access prevention): MetaOS hands out signed URLs instead (Storage.kt), and the
# bucket's CORS lets the front-end upload and download with them straight from the browser.
#
# The other is for everyone, e.g. episodes' thumbnails and people's avatars: anyone can read a file whose path they
# know, but not list the bucket. Production's is named after the domain it's served at: Cloudflare proxies
# media.ogrodje.si to c.storage.googleapis.com, which picks the bucket by the Host header. Creating it needs the Google
# account running this to be a verified owner of ogrodje.si (https://search.google.com/search-console). Dev's is
# served by Google itself, at https://storage.googleapis.com/ogrodje-metaos-public-dev/.
#
# A key works until it's deleted. `key` makes a new one each time and leaves the older ones working, up to 10 per
# service account - delete the ones no longer used:
#   gcloud iam service-accounts keys list --iam-account=metaos-storage-dev@ogrodje-metaos.iam.gserviceaccount.com
#   gcloud iam service-accounts keys delete KEY_ID --iam-account=...
set -euo pipefail

: "${GOOGLE_CLOUD_PROJECT:?}"
export CLOUDSDK_CORE_PROJECT=$GOOGLE_CLOUD_PROJECT
# API calls count against this project too, whatever quota project gcloud's own config has
export CLOUDSDK_BILLING_QUOTA_PROJECT=$GOOGLE_CLOUD_PROJECT

# Bucket names are global, so they carry the organisation's name
bucket() { echo "ogrodje-metaos-$1"; }
media_bucket() { if [[ $1 == prod ]]; then echo media.ogrodje.si; else echo "ogrodje-metaos-public-$1"; fi; }
account() { echo "metaos-storage-$1@$GOOGLE_CLOUD_PROJECT.iam.gserviceaccount.com"; }

usage() { echo "Usage: $0 setup | key dev|prod" >&2; exit 2; }

# What sets the environments' buckets apart
settings() {
  case $1 in
    dev) echo --clear-soft-delete --no-enable-autoclass ;;
    prod) echo --soft-delete-duration=30d --enable-autoclass ;;
  esac
}

# Unfinished multipart uploads are billed until they're aborted; uploads from the browser MetaOS didn't take
# (Artwork.kt), and tests' leftovers, go after a day
lifecycle() {
  jq -n --arg env "$1" '{rule: ([
    {action: {type: "AbortIncompleteMultipartUpload"}, condition: {age: 7}},
    {action: {type: "Delete"}, condition: {age: 1, matchesPrefix: ["uploads/"]}}
  ] + if $env == "dev" then [{action: {type: "Delete"}, condition: {age: 1, matchesPrefix: ["test/"]}}] else [] end)}'
}

# A new service account takes a few seconds to show up
grant() {
  local bucket=$1 member=$2 role=$3
  for attempt in 1 2 3 4 5 6; do
    gcloud storage buckets add-iam-policy-binding "$bucket" --member="$member" --role="$role" >/dev/null && return
    [[ $attempt == 6 ]] && exit 1
    sleep 10
  done
}

setup_bucket() {
  local env=$1 origins
  local bucket=gs://$(bucket "$env") account=$(account "$env")
  case $env in
    # Vite's dev server, the app serving the built front-end, and a second front-end, e.g. a worktree's
    dev) origins='["http://localhost:5173", "http://localhost:8080", "http://localhost:5175"]' ;;
    prod) origins='["https://metaos.ogrodje.si"]' ;;
  esac

  local files
  files=$(mktemp -d)
  # Resumable uploads from the browser: it starts one with a signed POST, reads the session from Location and PUTs
  # the file there, in chunks if it's big. responseHeader is both the headers it may send and those it may read.
  cat >"$files/cors.json" <<JSON
[{
  "origin": $origins,
  "method": ["GET", "HEAD", "PUT", "POST"],
  "responseHeader": ["Content-Type", "Content-Range", "Range", "ETag", "Location", "x-goog-resumable",
                     "x-goog-if-generation-match"],
  "maxAgeSeconds": 3600
}]
JSON
  lifecycle "$env" >"$files/lifecycle.json"

  # The location can only be set here, on creation
  if ! gcloud storage buckets describe "$bucket" >/dev/null 2>&1; then
    gcloud storage buckets create "$bucket" --location=europe-west3 --default-storage-class=STANDARD \
      --uniform-bucket-level-access --public-access-prevention
  fi
  # shellcheck disable=SC2046
  gcloud storage buckets update "$bucket" \
    --uniform-bucket-level-access --public-access-prevention \
    --cors-file="$files/cors.json" --lifecycle-file="$files/lifecycle.json" \
    --update-labels=app=metaos,env="$env" $(settings "$env")
  rm -r "$files"

  if ! gcloud iam service-accounts describe "$account" >/dev/null 2>&1; then
    gcloud iam service-accounts create "metaos-storage-$env" --display-name="MetaOS storage ($env)" \
      --description="MetaOS's files in gs://$(bucket "$env") and gs://$(media_bucket "$env") (google/storage.sh)"
  fi
  # Create, read, replace and delete files in this bucket and the public one only
  grant "$bucket" "serviceAccount:$account" roles/storage.objectUser
  echo "$bucket: ready for $account" >&2
}

# No CORS: <img> doesn't need it, and Cloudflare would hand one origin's answer to everyone
setup_media_bucket() {
  local env=$1
  local bucket=gs://$(media_bucket "$env") account=$(account "$env")

  local files
  files=$(mktemp -d)
  lifecycle "$env" >"$files/lifecycle.json"

  if ! gcloud storage buckets describe "$bucket" >/dev/null 2>&1; then
    gcloud storage buckets create "$bucket" --location=europe-west3 --default-storage-class=STANDARD \
      --uniform-bucket-level-access || {
      [[ $bucket == *.* ]] && echo "$bucket is named after a domain: verify it in Search Console as $(gcloud config get account 2>/dev/null)" >&2
      exit 1
    }
  fi
  # shellcheck disable=SC2046
  gcloud storage buckets update "$bucket" \
    --uniform-bucket-level-access --no-public-access-prevention --lifecycle-file="$files/lifecycle.json" \
    --update-labels=app=metaos,env="$env",access=public $(settings "$env")
  rm -r "$files"

  # A file for anyone who has its path, and no listing: the legacy reader has storage.objects.get only
  grant "$bucket" allUsers roles/storage.legacyObjectReader
  grant "$bucket" "serviceAccount:$account" roles/storage.objectUser
  echo "$bucket: public, and ready for $account" >&2
}

case ${1:-} in
  setup)
    gcloud services enable storage.googleapis.com iam.googleapis.com
    setup_bucket dev
    setup_media_bucket dev
    setup_bucket prod
    setup_media_bucket prod
    ;;

  # The export lines go to stdout and everything else to stderr, so they can be appended to .envrc as they are
  key)
    case ${2:-} in
      dev) VARIABLE=GOOGLE_STORAGE_KEY ;;
      prod) VARIABLE=PROD_GOOGLE_STORAGE_KEY ;;
      *) usage ;;
    esac
    KEY=$(mktemp)
    trap 'rm -f "$KEY"' EXIT
    gcloud iam service-accounts keys create "$KEY" --iam-account="$(account "$2")"
    echo
    echo "# Google Cloud Storage: key $(jq -r .private_key_id "$KEY") of $(account "$2") (google/storage.sh key $2)"
    [[ $2 == dev ]] && echo "export METAOS_STORAGE_BUCKET=$(bucket dev)" && echo "export METAOS_MEDIA_BUCKET=$(media_bucket dev)"
    # One line, which single quotes keep as it is: the private key's newlines are \n in JSON
    echo "export $VARIABLE='$(jq -c . "$KEY")'"
    ;;

  *) usage ;;
esac
