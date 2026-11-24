#!/usr/bin/env bash
# A refresh token for MetaOS's Google bot account (GOOGLE_BOT_EMAIL):
#
#   google/refresh_token.sh [drive]  Drive, where the bot has edit access to the "Ogrodje" folder
#   google/refresh_token.sh gmail    the bot's Gmail
#   google/refresh_token.sh youtube  Ogrodje's YouTube channel, which the bot is an owner or manager of
#
# A token each, because Google revokes tokens with Gmail access when the account's password changes, and that shouldn't
# take the others down too; and because YouTube's is for a channel: Google asks which one as the bot signs in ("Choose
# your account or a brand account"), where Ogrodje's is the one to pick, not the bot's own. For that the channel has to
# be a Brand Account with the bot among its owners or managers (YouTube Studio's own "Permissions" don't reach the
# API). Then it prints the channel the token is for: its ID goes into METAOS_YOUTUBE_CHANNEL_ID, so MetaOS refuses a
# token for any other. The project needs the YouTube Data API v3 on (infra/gcp). MetaOS turns them into access tokens itself, so this is one-time - re-run it only when Google
# revokes a token (access removed at https://myaccount.google.com/connections; MetaOS uses both hourly, so they don't go
# unused for the six months after which Google drops them). Each run gets a new token and leaves the older ones working,
# but Google keeps only 100 per account and client and then drops the oldest.
#
# Sign in as the bot, not as yourself. Google then redirects to a port nothing listens on, so the browser shows an
# error page: copy the URL from its address bar back into the terminal.
#
# From the environment (.envrc):
#   GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET  the "Desktop app" OAuth client of the Google Cloud project
#   GOOGLE_BOT_EMAIL                        optional, preselects the account on Google's sign-in page
set -euo pipefail

: "${GOOGLE_CLIENT_ID:?}" "${GOOGLE_CLIENT_SECRET:?}"

case ${1:-drive} in
  # Docs API accepts the Drive scope too
  drive) SCOPE=https://www.googleapis.com/auth/drive VARIABLE=GOOGLE_DRIVE_REFRESH_TOKEN ;;
  # Reading, drafts, sending and labels; not deleting for good
  gmail) SCOPE=https://www.googleapis.com/auth/gmail.modify VARIABLE=GOOGLE_GMAIL_REFRESH_TOKEN ;;
  # The channel's videos: listing them, private ones too, uploading, and changing their details and thumbnails
  youtube) SCOPE=https://www.googleapis.com/auth/youtube VARIABLE=GOOGLE_YOUTUBE_REFRESH_TOKEN ;;
  *) echo "Usage: $0 [drive|gmail|youtube]" >&2; exit 2 ;;
esac
REDIRECT=http://127.0.0.1:8765

# PKCE
VERIFIER=$(openssl rand -hex 48)
CHALLENGE=$(printf %s "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')

# access_type=offline + prompt=consent, or Google leaves the refresh token out
QUERY=$(jq -rn \
  --arg client_id "$GOOGLE_CLIENT_ID" --arg redirect_uri "$REDIRECT" --arg response_type code --arg scope "$SCOPE" \
  --arg access_type offline --arg prompt consent --arg login_hint "${GOOGLE_BOT_EMAIL:-}" \
  --arg code_challenge "$CHALLENGE" --arg code_challenge_method S256 \
  '$ARGS.named | to_entries | map("\(.key)=\(.value | @uri)") | join("&")')
AUTH_URL="https://accounts.google.com/o/oauth2/v2/auth?$QUERY"

echo "Sign in as ${GOOGLE_BOT_EMAIL:-the bot account} at:"
echo
echo "  $AUTH_URL"
echo
command -v open >/dev/null && open "$AUTH_URL"
read -rp "URL the browser ended up on: " RETURNED

# Still URL-encoded, which is what the form body below wants
CODE=$(sed -nE 's/.*[?&]code=([^&]+).*/\1/p' <<<"$RETURNED")
[[ -n $CODE ]] || { echo "No code= in that URL" >&2; exit 1; }

TOKENS=$(curl -sS https://oauth2.googleapis.com/token \
  -d "code=$CODE" -d grant_type=authorization_code \
  --data-urlencode "client_id=$GOOGLE_CLIENT_ID" --data-urlencode "client_secret=$GOOGLE_CLIENT_SECRET" \
  --data-urlencode "redirect_uri=$REDIRECT" --data-urlencode "code_verifier=$VERIFIER")
REFRESH_TOKEN=$(jq -er .refresh_token <<<"$TOKENS") || { echo "$TOKENS" >&2; exit 1; }
# Set only when the consent screen was given a time limit, after which the token stops working
if EXPIRES_IN=$(jq -er .refresh_token_expires_in <<<"$TOKENS"); then
  echo "This token stops working in $((EXPIRES_IN / 86400)) days - re-run and grant access without a time limit" >&2
  exit 1
fi
ACCESS_TOKEN=$(jq -r .access_token <<<"$TOKENS")

# Only what was asked for, so a Drive token doesn't get Gmail access, or the other way round
GRANTED=$(jq -r .scope <<<"$TOKENS")
[[ $GRANTED == "$SCOPE" ]] || { echo "Google granted \"$GRANTED\" instead of $SCOPE" >&2; exit 1; }

api() { curl -sS --fail-with-body -G -H "Authorization: Bearer $ACCESS_TOKEN" "$@"; }

# A YouTube token is for a channel, and says nothing of the account: the channel is what to check
if [[ $SCOPE == *youtube* ]]; then
  CHANNELS=$(api https://www.googleapis.com/youtube/v3/channels --data-urlencode part=snippet,statistics --data-urlencode mine=true)
  echo
  if [[ $(jq '.items | length' <<<"$CHANNELS") == 0 ]]; then
    echo "This token is for an account with no channel - re-run and pick Ogrodje's channel as you sign in" >&2
    exit 1
  fi
  echo "The token is for the channel:"
  jq -r '.items[] | "  \(.snippet.title)  \(.snippet.customUrl // "")  id=\(.id)  videos=\(.statistics.videoCount)"' <<<"$CHANNELS"
  echo
  echo "If that isn't Ogrodje's, remove the app at https://myaccount.google.com/connections and re-run."
  echo "In secretspec's vault (Ogrodje-Prod; then k8s/set_secrets.sh), or in .envrc to try it locally:"
  echo "  export $VARIABLE='$REFRESH_TOKEN'"
  echo "And the channel's ID, so MetaOS refuses a token for any other (k8s/base/deployment-metaos.yaml, or .envrc):"
  echo "  export METAOS_YOUTUBE_CHANNEL_ID='$(jq -r '.items[0].id' <<<"$CHANNELS")'"
  exit 0
fi

# Catches signing in with the wrong account
if [[ $SCOPE == *gmail* ]]; then
  ACCOUNT=$(api https://gmail.googleapis.com/gmail/v1/users/me/profile | jq -r .emailAddress)
else
  ACCOUNT=$(api https://www.googleapis.com/drive/v3/about --data-urlencode 'fields=user(emailAddress)' | jq -r .user.emailAddress)
fi
echo
echo "Signed in as $ACCOUNT"
if [[ -n ${GOOGLE_BOT_EMAIL:-} && $ACCOUNT != "$GOOGLE_BOT_EMAIL" ]]; then
  echo "That isn't $GOOGLE_BOT_EMAIL - remove the app at https://myaccount.google.com/connections and re-run" >&2
  exit 1
fi

# The folders shared with the bot - Ogrodje's ID goes into METAOS_DRIVE_ROOT_FOLDER_ID
if [[ $SCOPE != *gmail* ]]; then
  echo "Folders shared with it:"
  api https://www.googleapis.com/drive/v3/files \
    --data-urlencode "q=sharedWithMe = true and mimeType = 'application/vnd.google-apps.folder'" \
    --data-urlencode 'fields=files(id,name,owners(emailAddress))' |
    jq -r '.files[] | "  \(.name)  id=\(.id)  owner=\(.owners[0].emailAddress)"'
fi

echo
echo "Add to .envrc (and to production's secret):"
echo "  export $VARIABLE='$REFRESH_TOKEN'"
