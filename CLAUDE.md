# MetaOS

MetaOS is a autonomous, agentic framework for managing and operating media organisations.

MetaOS was designed for scaling, optimising and helping grow the [Ogrodje] organisation.

## Development && Infrastructure notes

- This project uses Nix and devenv for dependency isolation and reproducability.
- The project is written in Kotlin and uses Spring Boot at its core.
- Services and infrastructure is deployed via Kubernetes
- **MCP tools and the Usage page stay in sync.** Whenever an MCP tool (`@McpTool`) is added, renamed, removed or
  changes what it does, update the front-end's Usage page in the same change: the tool's entry in
  [`fe/src/tools.ts`](fe/src/tools.ts) (name, group and one-line description). `UsageToolsTest` fails on a missing
  or extra name, but can't tell when a description no longer fits.
- **Every MCP tool has a title and all four hints.** Its `McpTool.McpAnnotations` gives its title (also the tool's own)
  and says whether it only reads, destroys what's there, can be repeated and reaches the open world. Spring AI's
  defaults are the cautious ones, and are sent when left out. `ToolHintsTest` says what each means here and checks every
  tool's by its name, so a new tool that doesn't fit its rules changes them too.
- **The architecture diagram stays in sync.** [`architecture.png`](architecture.png), at the root and in README.md's
  Architecture Overview, is drawn in [`architecture.svg`](architecture.svg). Whenever the architecture or an
  integration changes, update `architecture.svg` in the same change and render the PNG again with `just architecture`
  (headless Chrome), then look at it. That's when MetaOS starts or stops talking to a service (Google, Koofr, Dropbox,
  ...) or uses one for something else (e.g. Gmail or Cloud Storage, drawn dashed as unused, getting a feature), when an
  endpoint or a kind of client comes or goes, when sign-in or tokens work differently, or when what's deployed and
  where changes (a new deployment, database or monitoring). Also update the README's paragraph under the diagram when
  it no longer fits.

### Front-end

The web front-end lives in [`fe/`](fe): Vue 3 with TypeScript, rendered by [Nuxt] (Vite builds it, Nitro serves it),
with Pinia for state and Vue Router. Nuxt keeps the app's own layout (`srcDir: 'src'` in
[`fe/nuxt.config.ts`](fe/nuxt.config.ts)): `App.vue`, its views and components, its routes in `fe/src/router/index.ts`
(through `router.options.ts`, not a `pages/` directory), and explicit imports, without Nuxt's auto-imports:
composables come from `#imports`, `NuxtPage` from `#components`. Node and Yarn (Berry) come from devenv
(`languages.javascript` in `devenv.nix`). Yarn installs into `node_modules` (`nodeLinker: node-modules` in
`fe/.yarnrc.yml`), as Vite doesn't support Plug'n'Play: under it pinia couldn't find its `@vue/devtools-api` peer
dependency.

```bash
cd fe
yarn            # install, and nuxt prepare (its types, in .nuxt)
yarn dev        # Nuxt's dev server, rendering on the server too, with hot reload, http://localhost:5173
yarn build      # type-check (nuxt typecheck) and build into fe/.output
yarn preview    # run the build, http://localhost:3000
yarn test       # the tests in src/**/__tests__, with vitest (./gradlew feTest, which CI runs)
```

- The devenv shell runs `yarn install` in `fe/` when `yarn.lock` has changed, and `devenv up` runs `yarn dev`.
  [`fe/server/middleware/api.ts`](fe/server/middleware/api.ts) passes `/graphql`, `/events`, `/koofr` and `/dropbox`
  on to MetaOS (`NUXT_METAOS_URL`, by default `:8080`), so in development too the browser sees one origin; in the
  cluster the Ingress sends them to MetaOS before they get there.
- The front-end is a service of its own, `metaos-web` ([`docker/web.Dockerfile`](docker/web.Dockerfile),
  [`k8s/base/deployment-web.yaml`](k8s/base/deployment-web.yaml)): `./gradlew feBuild` (with `feInstall`, the
  devenv's Node and Yarn) builds it into `fe/.output`, which Node runs. MetaOS itself serves no pages, only its API.
- Its runtime config (`nuxt.config.ts`) says where users sign in (`NUXT_PUBLIC_ISSUER`, the Keycloak issuer) and as
  which client, where MCP clients connect (`NUXT_PUBLIC_MCP_URL`), the site's public address (`NUXT_PUBLIC_SITE_URL`)
  and where its server finds MetaOS (`NUXT_METAOS_URL`), so one build works in every environment.
- The pages anyone may see (Home, Episodes, People, an episode's and a person's page, Usage) render on the server, as
  someone who isn't signed in sees them: the server asks the API without a token (`graphql.ts`), so every visitor gets
  the same page, which Nitro and Cloudflare keep for a minute and serve stale for ten while it's rendered again
  (`routeRules`). A page's data comes in it (`useFirstLoad` in [`fe/src/ssr.ts`](fe/src/ssr.ts), over `useAsyncData`;
  not in a `_payload.json` of its own, which a cache could keep apart from its page), and the browser hydrates it
  rather than loading it again. Going to a page in the browser, it loads there, as the signed-in user.
- keycloak-js starts once the page is hydrated ([`fe/src/plugins/auth.client.ts`](fe/src/plugins/auth.client.ts)), and
  a page loads again once the user is known to be on the team (`auth.team`: the realm role `metaos-user` is in their
  token), when what's the team's comes on top. Until then a page has to render in the browser as it did on the server:
  what depends on who's signed in waits for `auth.ready`, `auth.team` or `auth.likelyMember`, which are false there;
  dates are in English and in Ogrodje's time zone (`DATE_LOCALE`, `TIME_ZONE`), whatever the browser's; and
  `navigator`, `location`, `document` and storage are read in `onMounted` or handlers only. Nothing may write
  module-level state on the server, which every request shares (`pageName` in `title.ts` is the browser's only).
- The team's pages, `/ideas`, `/sessions/**` and `/search`, render in the browser only (`ssr: false`), with
  `no-store` and `noindex`. Signed in but not approved, users see what everyone does, with a note under the header.
- A page from a cache that's over 30 s old loads its data again once it's shown
  ([`fe/src/plugins/rendered.ts`](fe/src/plugins/rendered.ts)), as live updates do after a break, since they didn't
  hear what changed meanwhile.
- For search engines and link previews, most of which don't run JavaScript, each page has its title, description,
  picture and canonical address in the server's page (`usePageTitle`, and `usePageMeta` in
  [`fe/src/seo.ts`](fe/src/seo.ts)), and an episode's and a person's page their schema.org data (`PodcastEpisode`,
  `Person`). [`fe/server/routes`](fe/server/routes) has `/sitemap.xml`, with every page anyone may see, and
  `/robots.txt`, which keeps crawlers off the team's pages and the API; and `/healthz`, for the probes. A not-found
  page answers 404 (`NotFound.vue`).
- The Usage page (`/usage`) shows how to install the MCP server and lists its tools by hand, from
  [`fe/src/tools.ts`](fe/src/tools.ts), which has to follow every change to the tools (see above).
- Data comes from the GraphQL API at `/graphql` ([`schema.graphqls`](src/main/resources/graphql/schema.graphqls),
  [`GraphQL.kt`](src/main/kotlin/si/ogrodje/metaos/GraphQL.kt)), through `fe/src/graphql.ts`. It's open to anyone, as
  some of it is public (`releasedEpisodes`, and `episode` and `person` for a released episode and the people on it);
  a bearer token says who is asking (`me`). What isn't public needs
  `metaos:read` and an approved user (realm role `metaos-user`, `Security.kt`): whole queries with `@PreAuthorize`, and fields that public episodes and sessions also have (history,
  docs, addresses, ...) come back empty for anyone else.
- The header ([`App.vue`](fe/src/App.vue)) is two rows: the logo, and at the right Search and you
  (`UserMenu.vue`: your initials, which open your name, your page and Sign out; or Sign in, which asks Discord or
  Google); under them the pages as tabs, in the order they're used (`pages`), which scroll sideways when they don't
  fit. A new section, e.g. Partners or Analytics, is another tab there. A tab is underlined on its own pages too,
  Episodes on an episode's. The user menu is a button that shows and hides its links, not an ARIA menu.
- Every page is named in the browser's tab and the server's page (`meta.title` in `fe/src/router/index.ts`, and
  `usePageTitle` from `fe/src/title.ts`, with `useHead`, for one about a thing, e.g. an episode's code and title) and
  has an `h1`, hidden where its tab
  names it. Going to another page moves focus to it and says its name in a live region (`App.vue`); a skip link leads
  past the header. Anything else is a not-found page (`NotFound.vue`, also an episode, session or person that isn't
  there), with where to go instead.
- A list loads behind rows like its own (`LoadingRows.vue`), so the page doesn't jump, and what didn't load says why
  with Try again (`LoadError.vue`); `graphql.ts` words errors so they say what to do, e.g. an account not approved
  yet. Dates are in English (`DATE_LOCALE`), whatever the browser's language.
- Home is what's being made for the team, and the latest releases for everyone: signed in, first what needs
  attention ([`fe/src/attention.ts`](fe/src/attention.ts): recording dates that passed, recordings in the next two
  weeks with no confirmed guest or doc, and episodes recorded 60 days ago that aren't out), then the upcoming
  recordings and what's in production, by session with how far away it is, then the releases.
- Episode titles lead to their pages, `/episodes/<code or ID>`, and for the team session labels to `/sessions/<ID>`:
  everything about them, and their history on a date rail (`HistoryTimeline.vue`), from the audit trail as
  [`History.kt`](src/main/kotlin/si/ogrodje/metaos/History.kt) tells it. A released episode's page is anyone's, without
  what's the team's (its history, alternative titles, files, ...); any other episode is the team's, and not found for
  anyone else, as one that isn't there.
- Episode ideas (`IDEA`) have a page of their own, `/ideas`, from the `ideas` query, which needs `metaos:read`, as
  does its link in the header; the `episodes` query, and so the Episodes page, leaves them out.
- The People page, `/people`, lists everyone by name (Slovenian order, past a "dr."), from the `people` query: who
  they are, their roles and latest episodes, and links to their website and social profiles. Anyone not signed in
  gets only those on the released episodes, with only those episodes; candidates are the team's.
- People's names (on the People page and an episode's page, and who recommended an idea) lead to their pages,
  `/people/<ID>`, from the `person` query: who they are, where they're online, their episodes by what they are on
  them, hosts first, each list as the session page has it, and the episodes they recommended. Anyone not signed in
  gets the page of someone on a released episode, as the People page lists them, with only those episodes.
- Search is the team's: signed in, the header has a Search button (`SearchPalette.vue`; also ⌘K, Ctrl+K and `/`),
  which opens a few of each kind over the page, and its See all the search page, `/search?q=`, with all of them by
  kind; the address keeps what's searched. Both ask the `search` query, as does the `search` MCP tool:
  [`Search.kt`](src/main/kotlin/si/ogrodje/metaos/Search.kt) finds episodes, ideas too, people and recording sessions.
  An episode code, however it's typed (`s5e8`, `S5 E8`, `5x08`), or an ID is that episode alone, which ↵ opens; a
  season (`S04`) lists it. Otherwise every word has to start a word, or be in one, in an episode's titles, people,
  show, summaries (not the AI one) or session, a person's name or bio, or a session's place, address or date; case
  and diacritics don't matter (`fold`, also in `fe/src/search.ts`, which marks the words found). Each word counts the
  weight of the field it's best in (`SearchField`), and a row says why it was found when it doesn't show it.
- Users sign in with keycloak-js (`fe/src/stores/auth.ts`), straight to Discord or Google (`idpHint`). Once the page
  is hydrated it checks for a Keycloak session in a hidden iframe of `fe/public/silent-check-sso.html`, which is why
  MetaOS's pages may be framed by MetaOS itself (`X-Frame-Options: SAMEORIGIN`, in `routeRules`). Whether the user was
  on the team last time is kept in `localStorage`, as a hint only, read once the page is hydrated: while Keycloak is
  asked, the header keeps the team's tabs and pages their places, and the check waits longer before taking them for a
  guest.

### Authentication (Keycloak)

The MCP endpoint (`/mcp`) is an OAuth2 resource server. [Keycloak] from `compose.yml` is the identity provider /
authorization server, with realm `metaos` imported from [`keycloak/metaos-realm.json`](keycloak/metaos-realm.json).
Keycloak persists into the `keycloak` database of the `postgres` service (same credentials as the app, from `.envrc`);
the one-shot `keycloak-db-init` service creates that database when it is missing.

```bash
docker compose up -d keycloak   # http://localhost:8180 (admin console: admin / admin)
```

- MCP clients (Claude Code, MCP Inspector, ...) discover Keycloak via `/.well-known/oauth-protected-resource/mcp`,
  register themselves with Dynamic Client Registration and log the user in. The realm's Trusted Hosts policy takes
  redirect URIs on `localhost`, `claude.ai`, `claude.com` and `chatgpt.com` only; for realms imported before,
  [`keycloak/trusted_hosts.sh`](keycloak/trusted_hosts.sh).
- Tokens need the `mcp:tools` scope; it also puts the MCP URL into `aud`, which the server checks. They also need the
  realm role `metaos-user`, an approved user's (`APPROVED_ROLE` in `Security.kt`, also on `/graphql`'s team data and
  the thumbnails): Keycloak can issue the scope to more than approved users, e.g. a client registered through DCR for
  its own service account, which no login and so no approval gate stands in front of. Test JWTs carry it with
  `claim("realm_access", APPROVED)` (`TestTokens.kt`).
- The front-end signs users in as `metaos-ui`, a public client with PKCE. Its tokens carry `metaos:read` and
  `metaos:write`, which put the `/graphql` URL into `aud` (and don't get them into `/mcp`). They're the client's own
  scopes, not realm defaults, as MCP clients registering themselves could ask for any realm default.
  [`keycloak/frontend_client.sh`](keycloak/frontend_client.sh) sets them up in a realm imported without them, e.g.
  production's (`METAOS_UI_URL` and `METAOS_API_URL` default to production's).
- Dev login: `dev` / `dev`.
- A sign-in lasts 7 days without use and 30 days at most (`ssoSessionIdleTimeout`, `ssoSessionMaxLifespan`; for
  realms imported before, [`keycloak/sessions.sh`](keycloak/sessions.sh)), and refresh tokens as long. Access tokens
  last 5 minutes; keycloak-js keeps them in memory and refreshes them before each request (`fe/src/stores/auth.ts`),
  and only signs the page out when Keycloak refuses the refresh token, not when it can't be reached.
- `METAOS_MCP_URL` (default `http://localhost:8080/mcp`) and `METAOS_API_URL` (default
  `http://localhost:8080/graphql`) must be the public URLs of `/mcp` and `/graphql`; they are used by both Keycloak
  (token audience) and the app (audience check). `METAOS_UI_URL` (default `http://localhost:5173`) is where the
  front-end is, for `metaos-ui`'s redirect URIs. `KEYCLOAK_ISSUER_URI` points the app at another realm.
- The realm is only imported when it doesn't exist yet. To re-import after editing the JSON (wipes all Keycloak data,
  including registered MCP clients):

  ```bash
  docker compose stop keycloak
  docker compose exec postgres sh -c 'dropdb -U "$POSTGRES_USER" keycloak'
  docker compose up -d keycloak
  ```

### Audit trail

Postgres triggers ([`V6__audit_log.sql`](src/main/resources/db/migration/V6__audit_log.sql)) write every insert, update
and delete on the domain tables to `audit_log`, with the old and new row as JSON. `AuditingTransactionManager` tags each
read-write transaction with the signed-in user (`sub`, `preferred_username`), their OAuth client (`azp`) and the MCP tool
that started it; writes made any other way (psql, migrations) are logged without them. `AuditLog` reads it, for the
`get_history` and `list_recent_changes` MCP tools and for the history on the front-end's episode and session pages
(`History.kt`, which says each change in words).

A new table needs its own `<table>_audit` trigger, `AuditLog.history` needs to know about new link columns, and
`History.kt` how to say what a change to it means.

### Live updates

The front-end's pages show changes as they happen, without being reloaded. Each change the audit trail records also
goes out on Postgres's channel `metaos_changes`, as its table's name
([`V22__change_notifications.sql`](src/main/resources/db/migration/V22__change_notifications.sql)): when the transaction
commits, once a table, to every connection listening, so to every pod. [`Changes.kt`](src/main/kotlin/si/ogrodje/metaos/Changes.kt)
listens on a connection of its own, outside the pool, and passes it on to the browsers connected to its pod, as
Server-Sent Events at `/events`.

- `/events` is public and says no more than a table's name (`change`), or `resync` once it listens again after its
  connection to Postgres broke, as it may have missed some. Pages load their data again through `/graphql`, with their
  own token.
- It sends a comment every 25 s (`metaos.changes.heartbeat`), as Cloudflare hangs up after 100 s of silence, and ends
  its streams when the app stops, which graceful shutdown would otherwise wait out; the browsers connect to another pod.
  `metaos_changes_listening` and `metaos_changes_streams` say how it's doing.
- [`fe/src/changes.ts`](fe/src/changes.ts) keeps one `EventSource` for the app, and connects again when that gives up,
  e.g. on a 502 while MetaOS restarts. A page passes `useLiveRefresh` a quiet load: one that keeps what's shown until
  the new data is there (`keepUnchanged`, which keeps what didn't change as it was, so components keep their state),
  and leaves it be when it fails. It loads once for a burst of changes, and after the stream connected again; a hidden
  page loads when it's shown again, if anything changed or it was hidden for a minute (Koofr, Dropbox and Drive don't
  tell MetaOS what changed).
- A new table with an audit trigger is in it by itself. A new page that shows MetaOS's data should use
  `useLiveRefresh`, and, when anyone may see it, load what it shows first with `useFirstLoad` (Front-end).

### Google (Drive and Gmail)

MetaOS uses Google as a bot account, `iva.ogrodje@gmail.com`, through the "Desktop app" OAuth client "MetaOS" of
Google Cloud project `ogrodje-metaos` and Google's Java client libraries ([`Google.kt`](src/main/kotlin/si/ogrodje/metaos/Google.kt)).
Drive and Gmail have a refresh token each, from [`google/refresh_token.sh`](google/refresh_token.sh) `[drive|gmail]`:
Google revokes tokens with Gmail access when the account's password changes, and that shouldn't take Drive down too.

- **Drive** ([`Drive.kt`](src/main/kotlin/si/ogrodje/metaos/Drive.kt)): Ogrodje's folder is shared with the bot.
  `DocumentsMCP` has the tools and only touches what's inside the folder; docs are linked to episodes and recording
  sessions by Drive file ID (`episode_documents`, `session_documents`).
- **Gmail** ([`Gmail.kt`](src/main/kotlin/si/ogrodje/metaos/Gmail.kt)): the bot's mailbox - search, threads, drafts,
  sending. Not exposed as MCP tools.
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_DRIVE_REFRESH_TOKEN`, `METAOS_DRIVE_ROOT_FOLDER_ID` and
  `GOOGLE_GMAIL_REFRESH_TOKEN` (`.envrc`) turn them on; each is off without its refresh token. In production they come
  from `metaos-secret` (`k8s/set_secrets.sh`) and `metaos-config`.
- The refresh tokens don't expire with time. The `drive` and `gmail` health checks use them every hour, as Google
  drops tokens that go unused for six months, and report a revoked one there, in `metaos_<drive|gmail>_connected` and
  in the log.
- `GoogleDriveTest` and `GmailTest` run against the real account when its credentials are in the environment; Gmail's
  only makes and deletes a draft.

### Google Cloud Storage

MetaOS's own files (pictures, videos, anything) are in Google Cloud Storage, in project `ogrodje-metaos`, through
[`Storage.kt`](src/main/kotlin/si/ogrodje/metaos/Storage.kt). Unlike Drive and Gmail, this isn't the bot account: each
environment has two buckets, a private one and a public one, and its own service account, which can use only those two.
[`google/storage.sh`](google/storage.sh) `setup` creates them (it's idempotent) and `key dev|prod` makes the keys;
gcloud comes from the devenv shell.

| Bucket                      | Service account       | For                                                                   |
|-----------------------------|-----------------------|-----------------------------------------------------------------------|
| `ogrodje-metaos-dev`        | `metaos-storage-dev`  | development and tests; `test/` is emptied after a day; no soft delete |
| `ogrodje-metaos-public-dev` | `metaos-storage-dev`  | the same, public                                                      |
| `ogrodje-metaos-prod`       | `metaos-storage-prod` | production; deleted and replaced files are kept 30 days; Autoclass    |
| `media.ogrodje.si`          | `metaos-storage-prod` | the same, public, at `https://media.ogrodje.si` through Cloudflare    |

- All are in Frankfurt (`europe-west3`). The private ones have public access prevention: MetaOS hands out signed URLs
  instead, which it signs with the key itself: `uploadUrl` starts a resumable upload, so big videos go from the browser
  straight to the bucket, without MetaOS in between. Their CORS allows that from the front-end's origins.
- The public ones are for what everyone may see, e.g. episodes' thumbnails and people's avatars: anyone can read a file
  whose path they know (`allUsers` has `legacyObjectReader`, which is `storage.objects.get` only), but nobody can list
  them. Dev's is served by Google, at `https://storage.googleapis.com/ogrodje-metaos-public-dev/<path>`. Production's
  is named after its domain: `media.ogrodje.si` is a proxied CNAME to `c.storage.googleapis.com` in Cloudflare, which
  picks the bucket by the Host header. Two rules of the zone are for it: Configuration Rule `media: SSL Full` (Google's
  certificate is for `*.storage.googleapis.com`, which Full (strict) would refuse) and Cache Rule `media: cache`, which
  caches its files and tells browsers as their `Cache-Control` says, and nothing without one (errors). Smart Tiered
  Caching is on for the zone, so Cloudflare's data centres ask one near the bucket instead of each asking Google.
  Creating a bucket named after a domain needs whoever runs `setup` to be a verified owner of `ogrodje.si` in Google
  Search Console. A file deleted from it stays in Cloudflare's cache until it's purged there.
- `GOOGLE_STORAGE_KEY` (the service account's JSON key, on one line) and `METAOS_STORAGE_BUCKET` (`.envrc`) turn it on;
  it's off without the key. `METAOS_MEDIA_BUCKET` turns on the public one (`Media`), served at `METAOS_MEDIA_URL`, by
  default `https://storage.googleapis.com/<bucket>`. In production the key is in `metaos-secret`
  (`PROD_GOOGLE_STORAGE_KEY`, `k8s/set_secrets.sh`) and the rest in `metaos-config`.
- Keys don't expire. The `storage` health check uses the key every hour, on both buckets, and reports a deleted one
  there, in `metaos_storage_connected` and in the log.
- `GoogleCloudStorageTest` runs against the dev buckets when the key is in the environment, under `test/`.

### Pictures

Pictures everyone may see are copied to the public bucket ([`Images.kt`](src/main/kotlin/si/ogrodje/metaos/Images.kt)):
an episode's thumbnail (16:9), thumbnails B and C, which go with its alternative titles as they're A/B tested together,
and its cover (square, for podcast apps); a person's avatar. `ImagesMCP` sets them from Dropbox or Koofr (a place and a
path), or from Hygraph (an https URL on `*.graphassets.com`, the only hosts MetaOS fetches pictures from).

- A picture is at `images/<sha256>/`: `original.<ext>` as it came (PNG, JPEG, WebP or GIF, up to 50 MB and 40
  megapixels), and copies 320, 640, 1280 and 1920 px wide where it's wider, `<width>.jpg`, or `.png` when it's
  see-through somewhere (Thumbnailator; TwelveMonkeys reads CMYK JPEGs and WebP). Its path is its content's hash, so
  its files never change: they're served with `Cache-Control: public, max-age=31536000, immutable`. The same file is
  stored once (`images`, by `sha256`), however often it's used; setting a picture again after it changed where it came
  from makes a new one. One at a time, the download too, as a 3000 × 3000 cover takes some 400 MB to decode and
  resize; at a 1Gi limit, 17 at once got the pod OOM-killed, hence its 2.2Gi.
- `episodes.thumbnail_image_id`, `thumbnail_b_image_id`, `thumbnail_c_image_id`, `cover_image_id` and
  `people.avatar_image_id` point at them; nothing deletes an image or its files. `History.kt` names them by the file
  they were made from, e.g. "Thumbnail: ogrodje-vresGams-thumb1.png"; Hygraph's URLs have no file name, so the tools
  take one (`name`).
- GraphQL has them as `Image` (`thumbnail`, `thumbnailB`, `thumbnailC` and `cover` on `Episode`, `avatar` on `Person`),
  batched for lists; thumbnails B and C only for the team, as titles B and C are. The front-end shows an episode's on
  its page (`EpisodePictures.vue`) and avatars on the People and person pages (`PersonAvatar.vue`, initials without
  one), with `srcset` from the copies (`fe/src/images.ts`), never the original, which may be 16 MB.
- `ImagesTest` runs against a bucket in memory (`FakeStorage`), with fake Dropbox and Koofr.

### Koofr

MetaOS also uses the bot account in Koofr ([`Koofr.kt`](src/main/kotlin/si/ogrodje/metaos/Koofr.kt)), through
Koofr's Java SDK (from JitPack, as it isn't on Maven Central). It works only in the folders other Koofr users share with
the bot; `KoofrMCP` lists, searches and reads them, uploads, creates folders and moves or renames, but never deletes or
replaces anything unasked.

- `KOOFR_EMAIL` and `KOOFR_APP_PASSWORD` (`.envrc`) turn it on; it's off without the app password, which is made at
  https://app.koofr.net/app/admin/preferences/password while signed in as the bot. In production the email is in
  `metaos-config` and the app password in `metaos-secret` (`k8s/set_secrets.sh`).
- `KoofrFoldersMCP` links folders to episodes and recording sessions (`episode_koofr_folders`,
  `session_koofr_folders`). Koofr has no file IDs, so a link is a place (mount ID) and a path: moves through
  `move_koofr_file` take the links along, while a folder moved in the Koofr app leaves its links listed as missing.
- On the front-end's episode and session pages, a linked folder links to Koofr's web app (`app.koofr.net/app/storage/
  <place>?path=...`; a shared place has the same ID for everyone it's shared with), and shows its photos, eight at a
  time with arrows to the rest (`PhotoStrip.vue`): those right in it, then those in the folders right in it
  ([`KoofrPhotos.kt`](src/main/kotlin/si/ogrodje/metaos/KoofrPhotos.kt)). Koofr makes the thumbnails; MetaOS passes
  them on at `/koofr/thumbnail`, behind `metaos:read` and only for those photos, so the page fetches them with its
  token, only for the photos it shows and the next ones. In lists, Koofr's logo links to an episode's or session's folders,
  as the doc icon does to its Google Docs; the API batches the folders, and asks Koofr only for place names and photos.
- The `koofr` health check uses it every hour and reports a rejected password there, in `metaos_koofr_connected` and in
  the log. `KoofrTest` runs against the real account (read-only) when the app password is in the environment.

### Dropbox

The bot account has Dropbox too ([`Dropbox.kt`](src/main/kotlin/si/ogrodje/metaos/Dropbox.kt)), for the folders shared
with it, like Artwork, which has the episodes' artwork. As with Koofr, `DropboxMCP` works only in those: it lists,
searches, reads text, shows pictures (`view_dropbox_image`, as an image MCP clients give their model), uploads, creates
folders and moves or renames, but never deletes or replaces anything unasked.

- It goes through MetaOS's own Dropbox app (scoped access, Full Dropbox), with a refresh token of the bot's from
  [`dropbox/refresh_token.sh`](dropbox/refresh_token.sh), which also says how to make the app. Dropbox's API never takes
  the account's password.
- `DROPBOX_APP_KEY`, `DROPBOX_APP_SECRET` and `DROPBOX_REFRESH_TOKEN` (`.envrc`) turn it on; it's off without the
  refresh token. In production they're in `metaos-secret` (`k8s/set_secrets.sh`).
- A place is a shared folder, by its ID, which is also its namespace's (`ns:<id>/path`): that finds it wherever the bot
  has it in its Dropbox. Search only sees the folders that are in the bot's Dropbox ("Add to Dropbox"), and finds them
  by where they are there.
- `DropboxFoldersMCP` links folders to episodes and recording sessions (`episode_dropbox_folders`,
  `session_dropbox_folders`), like Koofr's, but by Dropbox's ID for the folder: wherever it's moved or renamed in its
  place, also in the Dropbox app, it stays linked, and is listed where it is now (by ID, with the place as the path
  root). Only one deleted or moved out of its place is missing.
- Linking a folder also makes a Dropbox link to it that only those with access to it can open (`sharing.write`), kept
  with the link: Dropbox's web paths (`dropbox.com/home/...`) are in each viewer's own Dropbox, e.g. the owner has
  Artwork at `/Ogrodje/Artwork` and Iva at `/artwork`. It's a link for `no_one` (Dropbox's "only people invited"),
  which gives no access of its own; the bot's Basic account can make only those or public ones, and Dropbox refuses
  `members` for a new link. MetaOS never makes one for anyone else, and takes it back should Dropbox make it anyway;
  while Dropbox won't make one, the folder is linked without it (`urlError` says why), and linking it again makes it.
- On the front-end, a linked folder shows like a Koofr one: Dropbox's logo in lists, and in the episode's and session's
  details, opening its link for members, or else its path in Dropbox's web app as the bot has it. Lists ask Dropbox
  where the folders are once per place and minute (`DropboxFolderIndex`), not per folder.
- An episode's page shows the pictures in its folders as its Artwork, above its photos and like them, eight at a
  time: each whole, as covers,
  thumbnails and stories have different shapes, and by file name, as that says what it's for. As with Koofr's photos,
  they're those right in a folder, then those in the folders right in it ([`DropboxPictures.kt`](src/main/kotlin/si/ogrodje/metaos/DropboxPictures.kt)).
  Dropbox makes the thumbnails; MetaOS passes them on at `/dropbox/thumbnail`, behind `metaos:read` and only for those
  pictures, which it checks against the linked folders' IDs through `DropboxFolderIndex`.
- The `dropbox` health check uses it every hour and reports a rejected token there, in `metaos_dropbox_connected` and in
  the log. `DropboxTest` runs against the real account (read-only) when the refresh token is in the environment.

### Deployment

Production runs on the ogrodje-one Kubernetes cluster, namespace `metaos-prod`: the app at `https://metaos.ogrodje.si`
and its Keycloak at `https://metaos-auth.ogrodje.si`. Pushes to `master` deploy through
[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml); [`k8s/README.md`](k8s/README.md) has the manifests,
the one-time setup and the `just` recipes for deploying by hand. [`DEPLOY.md`](DEPLOY.md) explains the setup, the
decisions behind it and what is left to do.

- The pages are the front-end's own Deployment, `metaos-web`, also two pods: the Ingress sends MetaOS's paths
  (`/graphql`, `/events`, `/mcp`, `/.well-known`, `/koofr`, `/dropbox`, the probes) to MetaOS and everything else to
  it; its server asks MetaOS's GraphQL inside the cluster (`http://metaos`). Cloudflare caches the pages anyone may
  see as their `Cache-Control` says, which a Cache Rule has to allow, as it doesn't cache HTML by default; the team's
  pages say `no-store`.
- MetaOS runs as two pods, so one answers while the other restarts or rolls out. Nothing may live in one pod's memory
  that another needs: MCP is stateless (`spring.ai.mcp.server.protocol=STATELESS`, no `Mcp-Session-Id`), as no tool
  needs a session and the bearer token says who's calling; every pod listens for changes (Live updates) and checks its
  connections; caches like `DropboxFolderIndex` are each pod's own. Flyway takes a lock, so both may start at once.

[Ogrodje]: https://ogrodje.si
[Nix]: https://nixos.org/
[devenv]: https://devenv.sh/
[Keycloak]: https://www.keycloak.org/
[Nuxt]: https://nuxt.com/



