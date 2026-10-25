# patreon-gateway

Ogrodje's Patreon behind a small HTTP API, for MetaOS (`Patreon.kt`, `PatreonMCP.kt`), which is the only caller. A
service of its own, so Chromium, the bot's Patreon session and Patreon's changing web app stay out of MetaOS: one pod,
one browser session, one job at a time.

Kotlin and Spring Boot like MetaOS, with Playwright for Java; a Gradle build of its own, apart from MetaOS's
(`./gradlew -p patreon-gateway ...`).

## How it reaches Patreon

- **Reading** goes through Patreon's API (v2), as the campaign's creator, with the creator token of the Patreon app
  "MetaOS" (its client ID and secret, an access and a refresh token, from Patreon's developer portal): the campaign,
  its tiers, its published posts and its members (`PatreonApi.kt`). Patreon lists posts oldest first a page at a time,
  so the gateway reads them all, keeps them for a minute and gives them newest first; the members, with their names and
  emails, for five minutes.
- **The creator token** is refreshed once when it's not known when it runs out (a token from the portal), to learn it,
  then a week before it does, when Patreon refuses it, and on `POST /token/refresh`. A refresh gives a new access and
  refresh token and ends the old ones, so the new ones are kept in `state/tokens.json`, and only one gateway may use a
  creator token: another with the same one would be left with a dead one. A different refresh token in the environment
  (a new one from the portal) takes over from the kept ones. Patreon's refreshed tokens last for years (2037).
- **Writing** goes through Patreon's web app, signed in as the bot, `iva.ogrodje@gmail.com`, a member of the campaign's
  team (`PatreonWeb.kt`). Patreon's API can't make posts or upload media. The gateway doesn't click through the editor,
  though: Cloudflare checks headless browsers on the editor's page. It calls Patreon's own web API, as the editor does,
  from a page of the campaign that Cloudflare lets through (`/c/ogrodje`), with the page's CSRF signature
  (`csrf_signature` in its HTML):
  - `POST /api/session/acting-as` with the creator's user ID: a team member acts as the creator, as the web app's
    Switch does. While it does, `/api/current_user` answers with the creator, and the person is the page's `actor`.
  - `POST /api/posts/new`, then `PATCH /api/posts/{id}` with the title, the text as HTML and as the editor's own
    document (`content_json_string`, ProseMirror's JSON), the post type and its access rules (the campaign's `public`
    rule, its `patrons` rule, or a `tier` rule per tier) and collections. `meta.auto_save` for drafts.
  - Publishing is the same `PATCH` with `tags: {publish: true}`, or `scheduled_for` (UTC) for later, and
    `meta.send_notifications` to email the patrons; a post whose video is still processing gets
    `auto_publish_when_media_ready` instead, as the editor's Publish does then. The gateway refuses a post that's
    published or scheduled already.
  - `DELETE /api/posts/{id}`.
  - Media: `POST /api/media` with the file's name and size, the post as its owner and `audio` or `video` as its
    relationship, which answers where to put the file: S3 with a signed form for audio, Mux with a `PUT` for video. The
    gateway sends it from the JVM (`Media.kt`), not through the browser, as an episode is gigabytes; then
    `GET /api/media/{id}` until it's `ready`, less and less often. Audio makes the post a `podcast` post, in the
    campaign's show (its podcast feed), as the editor does; video a `video_external_file` one.

  These were found by recording what the editor sends (headless Chrome is stopped by Cloudflare on its page; Chrome
  you can see isn't) and reading its scripts. They aren't a public API: Patreon may change them. When it does, a job
  fails with Patreon's answer, and keeps a screenshot of the page (`GET /jobs/{id}/screenshot`).

## Rate limits

Cloudflare answers 429 when Patreon is asked too often, after some 15–20 page loads and calls within a few minutes.
A job Patreon turns away waits and tries again, 5, 10 and 20 minutes later, before it fails (`Jobs.kt`); the job says
so (`error`, `retryAt`). Each job loads one page and makes a few calls.

## API

Every call but `/health` and the actuator's needs `Authorization: Bearer $PATREON_GATEWAY_TOKEN`.

| Call                          | What                                                                                       |
|-------------------------------|--------------------------------------------------------------------------------------------|
| `GET /health[?check=true]`    | Patreon's API and the browser's session, as last checked (hourly), or now; 503 when not ok |
| `GET /campaign`, `GET /tiers` | the campaign; its tiers, cheapest first                                                    |
| `GET /members?query&status&tier&limit&offset` | members, patrons first; `status` active_patron, declined_patron, former_patron or free; words in name or email |
| `GET /token`, `POST /token/refresh` | when the creator token runs out and was refreshed (never the tokens); refresh it now |
| `GET /posts?query&limit`      | published posts, newest first, words in their titles                                      |
| `GET /posts/{id}`             | a published post (Patreon's API)                                                           |
| `GET /posts/{id}/web`         | a post, drafts too (Patreon's web app)                                                     |
| `POST /posts`                 | a draft: `{title, text, access: {kind: PUBLIC, PATRONS or TIERS, tiers}}`                  |
| `PATCH /posts/{id}`           | changes a post; what's left out stays                                                      |
| `POST /posts/{id}/media`      | `{url, kind: AUDIO or VIDEO, fileName}`: downloads the file and puts it on the post        |
| `POST /posts/{id}/publish`    | `{at, notify}`: now, or later; patrons are emailed only with `notify`                      |
| `DELETE /posts/{id}`          | deletes a post                                                                             |
| `GET /jobs`, `GET /jobs/{id}` | the jobs, latest first; one                                                                |
| `PUT /session`                | the bot's browser session, from `login.sh`                                                 |

Changes answer 202 with a job, to follow at `/jobs/{id}`: QUEUED, RUNNING, SUCCEEDED with what it made, or FAILED
and why. An `Idempotency-Key` header gives back the job it was first sent with. Jobs are kept in memory: one lost to a
restart is one its caller sees not finish.

`text` is plain text: paragraphs between blank lines, and addresses made links. A file to upload comes from an https
URL the gateway downloads, e.g. a Dropbox temporary link (MetaOS makes them) or a signed Cloud Storage URL.

## Running it

```bash
# PATREON_CLIENT_ID, PATREON_CLIENT_SECRET, PATREON_ACCESS_TOKEN, PATREON_REFRESH_TOKEN, PATREON_GATEWAY_TOKEN set
./gradlew -p patreon-gateway bootRun     # http://localhost:8090
./gradlew -p patreon-gateway test
```

`PATREON_HEADED=true` runs the browser where you can see it. State (tokens, the session, what failed jobs saw) is in
`state/`, which git ignores: it's secret. MetaOS finds a gateway at `PATREON_GATEWAY_URL` (`http://localhost:8090` by
default) with `PATREON_GATEWAY_TOKEN`; it's off without the token.

## Signing the bot in

The browser's session is the bot's Patreon sign-in, made once by hand (a password and an emailed code, a captcha):

```bash
patreon-gateway/login.sh          # to production's gateway (Tailscale, KUBECONFIG)
patreon-gateway/login.sh local    # to one running here
```

It opens Chrome on Patreon's login: sign in as `iva.ogrodje@gmail.com`, and Reject non-essential cookies. It keeps
only a session of that account, and hands it to the gateway (`PUT /session`), which checks it before taking it. The
health check says when the session runs out; run it again then.

## Deployment

`k8s/base/deployment-patreon-gateway.yaml`: one pod (`Recreate`), its state on a 1Gi volume (`microk8s-hostpath`,
which deleting the claim deletes), no Ingress: only MetaOS calls it, at `http://patreon-gateway`. The image is
Playwright's, with Chromium, and Temurin's Java (`docker/patreon-gateway.Dockerfile`); Playwright's version there and
in `build.gradle.kts` has to be the same. CI tests it, builds and deploys it with MetaOS.

The first time, `k8s/set_secrets.sh` with `PATREON_CLIENT_ID`, `PATREON_CLIENT_SECRET`, `PATREON_ACCESS_TOKEN`,
`PATREON_REFRESH_TOKEN` and `PROD_PATREON_GATEWAY_TOKEN` makes `patreon-gateway-secret` (MetaOS reads the gateway token
from it too), then `login.sh`. The tokens there only seed the gateway: it keeps refreshed ones on its volume. They have
to be the latest: a gateway that ran here refreshed them, so take them from its `state/tokens.json`
(`jq -r .accessToken`, `.refreshToken`), and stop that one; or make new ones in the portal.
