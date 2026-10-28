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
- **Every change to the MCP tools changes their version, and old tools keep working.** Assistants keep the tools they
  were given, whatever version MetaOS says it has, until they're refreshed: Claude's apps until the user refreshes the
  connector, ChatGPT's workspaces until an admin does. So the tools have a version (`spring.ai.mcp.server.version` in
  [`mcp.properties`](src/main/resources/mcp.properties), semver), which `McpToolsTest` works out from their snapshot as
  `tools/list` has them ([`mcp-tools.json`](src/test/resources/mcp-tools.json)): a tool, parameter or value that's gone,
  a new parameter that's required or another type is major; a new tool, optional parameter or value minor; a title,
  description or hint a patch. After a change to the tools, `just mcp-tools` writes the snapshot and the version, also
  the Usage page's (`TOOLS_VERSION`), to commit with it; CI fails without them, and Claude Code is reminded after each
  edit of a file with tools (a hook in [`.claude/settings.json`](.claude/settings.json)). It refuses a major change unless it's
  `just mcp-tools --breaking`: rather, a new parameter is optional, and for 14 days a renamed tool or parameter keeps
  its old name too (a tool whose description starts "Deprecated: use `<new>`."), and a removed tool answers what to use
  instead; then they go, `--breaking`. A call that doesn't fit its tool says the assistant may have older tools, and
  where the Usage page says how to refresh them (`StaleToolInputs` in `McpVersion.kt`, `/usage#refresh`).
- **Infrastructure is Terraform's.** Keycloak's realm, Google Cloud, New Relic, MetaOS's part of Cloudflare and the
  tailnet's policy file are code in [`infra/`](infra/README.md), planned and applied with Terraform, never changed in a
  console or by a script: a change made there, the next apply undoes. A change to them is a change to `infra/` (its
  README says how, per stack, and how development and production differ); a new one goes there too, `import`ed if
  it's there already. CI plans a change to `keycloak/prod`, `newrelic` or `cloudflare` on its pull request and applies
  it once merged (`terraform.yml`); `gcp` and `tailscale` are applied by hand. This repository is public: the tailnet's
  policy file is private, so never committed (`infra/tailscale/policy.hujson`, `.gitignore`) and never applied by CI,
  and anything else like it goes the same way.
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
yarn test       # the tests in src/**/__tests__, with vitest (also ./gradlew feTest), which CI runs
```

- The devenv shell runs `yarn install` in `fe/` when `yarn.lock` has changed, and `devenv up` runs `yarn dev`.
  [`fe/server/middleware/api.ts`](fe/server/middleware/api.ts) passes `/graphql`, `/events`, `/koofr` and `/dropbox`
  on to MetaOS (`NUXT_METAOS_URL`, by default `:8080`), so in development too the browser sees one origin; in the
  cluster the Ingress sends them to MetaOS before they get there.
- The front-end is a service of its own, `metaos-web` ([`docker/web.Dockerfile`](docker/web.Dockerfile),
  [`k8s/base/deployment-web.yaml`](k8s/base/deployment-web.yaml)): `./gradlew feBuild` (with `feInstall`, the
  devenv's Node and Yarn; CI runs `yarn build` itself) builds it into `fe/.output`, which Node runs. MetaOS itself serves no pages, only its API.
- Its runtime config (`nuxt.config.ts`) says where users sign in (`NUXT_PUBLIC_ISSUER`, the Keycloak issuer) and as
  which client, where MCP clients connect (`NUXT_PUBLIC_MCP_URL`), the site's public address (`NUXT_PUBLIC_SITE_URL`)
  and where its server finds MetaOS (`NUXT_METAOS_URL`), so one build works in every environment.
- The pages anyone may see (Home, Episodes, People, an episode's and a person's page, Usage) render on the server, as
  someone who isn't signed in sees them: the server asks the API without a token (`graphql.ts`), so every visitor gets
  the same page, which Nitro and Cloudflare keep for a minute and serve stale for ten while it's rendered again
  (`routeRules`); not in development, where Nitro's cache (`.nuxt/cache`) would outlive a restart of the dev server. A page's data comes in it (`useFirstLoad` in [`fe/src/ssr.ts`](fe/src/ssr.ts), over `useAsyncData`;
  not in a `_payload.json` of its own, which a cache could keep apart from its page), and the browser hydrates it
  rather than loading it again. Going to a page in the browser, it loads there, as the signed-in user.
- keycloak-js starts once the page is hydrated ([`fe/src/plugins/auth.client.ts`](fe/src/plugins/auth.client.ts)), and
  a page loads again once the user is known to be on the team (`auth.team`: the realm role `metaos-user` is in their
  token), when what's the team's comes on top. Until then a page has to render in the browser as it did on the server:
  what depends on who's signed in waits for `auth.ready`, `auth.team` or `auth.likelyMember`, which are false there;
  dates are in English and in Ogrodje's time zone (`DATE_LOCALE`, `TIME_ZONE`), whatever the browser's; and
  `navigator`, `location`, `document` and storage are read in `onMounted` or handlers only. Nothing may write
  module-level state on the server, which every request shares (`pageName` in `title.ts` is the browser's only).
- The team's pages, `/ideas`, `/sessions/**`, `/search` and `/transcriptions`, render in the browser only
  (`ssr: false`), with `no-store` and `noindex`. Signed in but not approved, users see what everyone does, with a note
  under the header.
- A page from a cache that's over 30 s old loads its data again once it's shown
  ([`fe/src/plugins/rendered.ts`](fe/src/plugins/rendered.ts)), as live updates do after a break, since they didn't
  hear what changed meanwhile.
- For search engines and link previews, most of which don't run JavaScript, each page has its title, description,
  picture and canonical address in the server's page (`usePageTitle`, and `usePageMeta` in
  [`fe/src/seo.ts`](fe/src/seo.ts)), and an episode's and a person's page their schema.org data (`PodcastEpisode`,
  `Person`). [`fe/server/routes`](fe/server/routes) has `/sitemap.xml`, with every page anyone may see, and
  `/robots.txt`, which keeps crawlers off the team's pages and the API; and `/healthz`, for the probes. A not-found
  page answers 404 (`NotFound.vue`).
- The API and MCP usage page (`/usage`) shows how to install the MCP server, and how each assistant gets its tools
  again once they've changed (`#refresh`, with their version), and lists its tools by hand, from
  [`fe/src/tools.ts`](fe/src/tools.ts), which has to follow every change to the tools (see above). It also shows how to
  use the GraphQL API with curl, with or without a token (a signed-in user copies theirs); its examples have to keep
  working as the schema changes.
- Data comes from the GraphQL API at `/graphql` ([`schema.graphqls`](src/main/resources/graphql/schema.graphqls),
  [`GraphQL.kt`](src/main/kotlin/si/ogrodje/metaos/GraphQL.kt)), through `fe/src/graphql.ts`. It's open to anyone, as
  some of it is public (`releasedEpisodes`, and `episode` and `person` for a released episode and the people on it); a
  bearer token says who is asking (`me`). What isn't public needs `metaos:read` and an approved user (realm role
  `metaos-user`, `Security.kt`): whole queries with `@PreAuthorize`, and fields that public episodes and sessions also
  have (history, comments, docs, addresses, ...) come back empty for anyone else. Changes (`Mutation`: comments of
  Discussions, linking Accounts, an episode's pictures, video and audio and links, creating and changing sessions,
  transcribing and cancelling, removing transcripts) need `metaos:write` and an approved user
  (`@approval.writes`).
- An episode's links (`Platform` in [`Episodes.kt`](src/main/kotlin/si/ogrodje/metaos/Episodes.kt)) are where it can
  be watched or listened to: YouTube, Spotify, Apple Podcasts, and four on Patreon, each set or cleared on its own
  (`set_episode_link`, `setEpisodeLink`): the full episode as video (`PATREON`, `patreonUrl`, the only one there was
  at first) or audio only (`PATREON_AUDIO`), and its bonus as video (`PATREON_BONUS`) or audio only
  (`PATREON_BONUS_AUDIO`). All four are optional and public; `EARLY_ACCESS` needs any one of them. The front-end shows only `patreonUrl` yet.
- The header ([`App.vue`](fe/src/App.vue)) is two rows: the logo, and at the right Search and you
  (`UserMenu.vue`: your initials, which open your name, your page, your sign-ins (see Accounts) and Sign out; or
  Sign in, which asks Discord or Google); under them the pages as tabs, in the order they're used (`pages`), which
  scroll sideways when they don't fit: Home, Episodes, Ideas (the team's), People and Transcriptions (the team's). A
  new section, e.g. Partners or Analytics, is another tab there. A tab is
  underlined on its own pages too, Episodes on an episode's. "API and MCP usage" isn't a tab: the footer links to it,
  as do the sign-in prompts. The user menu is a button that shows and hides its links, not an ARIA menu.
- Every page is named in the browser's tab and the server's page (`meta.title` in `fe/src/router/index.ts`, and
  `usePageTitle` from `fe/src/title.ts`, with `useHead`, for one about a thing, e.g. an episode's code and title) and
  has an `h1`, hidden where its tab names it. Going to another page moves focus to it and says its name in a live
  region (`App.vue`); a skip link leads past the header. Anything else is a not-found page (`NotFound.vue`, also an episode, session or person that isn't
  there), with where to go instead.
- A list loads behind rows like its own (`LoadingRows.vue`), so the page doesn't jump, and what didn't load says why
  with Try again (`LoadError.vue`); `graphql.ts` words errors so they say what to do, e.g. an account not approved
  yet. Dates are in English (`DATE_LOCALE`), whatever the browser's language.
- Home is what's being made for the team, and the latest releases for everyone (as a grid of `EpisodeCard`s, two rows
  of four, with a line under them for the team to sign in). Signed in, two columns: first what needs attention
  ([`fe/src/attention.ts`](fe/src/attention.ts): recording dates that passed, recordings in the next two weeks with no
  confirmed guest or doc, and episodes recorded 60 days ago that aren't out), then the upcoming recordings and what's
  in production, a card a session with how far away it is (`SessionCard.vue`); at the side how many episodes are at
  each stage (`StageBar.vue`, a line each, which leads to Ideas or to Episodes with only that stage, `?status=`), the
  latest releases and what the team did lately (`RecentActivity.vue`), from `recentChanges`: the audit trail's latest
  changes, as `History.kt` tells them, each with what it's a change to, one person's close together as one entry
  (`recentActivity` in `activity.ts`), e.g. Created 28 episodes.
- The list pages (Episodes, Ideas, People) have a `PageHeader` and a `FilterBar` (a box that finds as you type, with
  `fold`, and `ChipGroup`s, or a `SelectFilter` where there'd be too many chips), and keep their filters and view in
  the address (`useQueryState`, `fe/src/query.ts`), so a link keeps them and the server renders them. While filtered,
  the header says how many are shown, e.g. 12 of 74, which screen readers hear; when nothing's left, `NoMatch.vue`
  says what was looked for, with Clear filters (`useClearQuery`). What they filter and group by is in
  [`fe/src/lists.ts`](fe/src/lists.ts), with tests, and happens in the browser on what's loaded.
- Pictures follow one set of rules ([`_pictures.scss`](fe/src/assets/_pictures.scss)): people round (`PersonAvatar`),
  things (episodes' pictures, artwork, photos) rounded, 6 px up to 56 px across and 8 px above, with a hairline edge
  inside; sizes from one scale (24, 32, 40, 56, 72, 96 px, and the grids' widths); a picture that isn't there is the
  same grey, with a card's code on it; dashed only where the team uploads one (Media, `ArtworkUpload`).
- Every page is in one frame, 1200 px wide (`--content-width`), the header's and the footer's too, so they don't jump
  between pages; running text keeps a measure of its own (`MarkdownText.vue`'s 680 px). The width goes to side panels,
  grids and tables.
- Episode titles lead to their pages, `/episodes/<code or ID>`, and for the team session labels to `/sessions/<ID>`:
  a workspace (`.workspace` in `page.scss`), with the facts in a panel at the right (`FactPanel.vue`, `FactGroup.vue`,
  `Fact.vue`: dates, session, people with their pictures (`PersonLine.vue`), where to listen, files, and what needs
  attention by Home's rules, `episodeConcerns`), first in the page, so above the rest on phones. Once the page's title
  has scrolled away, the panel, which stays in view, says what it's about: its cover, code and title (`PanelTitle.vue`,
  `useScrolledPast`), also a way back to the top; a session's says its date and name. The main column has
  the episode's media (`MediaStrip.vue`: its titles with the thumbnails they're A/B tested with and the cover, its
  video and audio, its artwork marked with what each file is used as, its photos), the summaries folded when long (`FoldedText.vue`), and
  for the team its activity (`ActivityFeed.vue`, see Discussions): comments and history in one feed, from the audit
  trail as [`History.kt`](src/main/kotlin/si/ogrodje/metaos/History.kt) tells it. A released episode's page is
  anyone's, without what's the team's (its activity, alternative titles, files, ...), its thumbnail large; any other
  episode is the team's, and not found for anyone else, as one that isn't there.
- A recording session has a name (e.g. AWS Community Day Adria 2026), apart from its location (the venue) and address;
  [`V29__session_names.sql`](src/main/resources/db/migration/V29__session_names.sql) named each one after its
  location to begin with. Without a name it goes by its location, else by its date, wherever it's labelled
  (`sessionName` and `sessionTitle` in `fe/src/episodes.ts`, the names `History.kt` gives sessions). Its page's
  heading is its name, with its date and, where the name doesn't say it, its location under it; the team renames it
  there (`RenameSession.vue`), through the `updateSession` mutation (`SessionsGraphQL.kt`, with `metaos:write`, as
  `update_session` does over MCP; both through `Sessions` in `Sessions.kt`). `createSession` creates one, as
  `create_session` does; no page uses it yet.
- The Episodes page lists what's in the works (the team's) by stage, in the order episodes go through them (`byStage`
  in `lists.ts`), with those on hold or cancelled folded at the end, then the released episodes by season, as a list
  (`EpisodeRow`, with its cover, square, or else its thumbnail: `listPicture`) or a grid of `EpisodeCard`s
  (`?view=grid`), by show and season; seasons older than the two latest show their first row until opened. `?status=`
  shows one stage, as Home's stages do, as a chip that turns it off (`FilterChip.vue`).
- Episode ideas (`IDEA`) have a page of their own, `/ideas`, from the `ideas` query, which needs `metaos:read`, as
  does its link in the header; the `episodes` query, and so the Episodes page, leaves them out. It's a table
  (`IdeaRow.vue`), grouped by how far along each is (`readiness`: in a session, with a guest, with candidates, no
  one yet), by who recommended it, or not at all, and filtered by show and recommender (by their whole names, in a
  select, as two may share a first name). It loads for an approved user only; one who isn't yet gets a note.
- The People page, `/people`, lists everyone by name (Slovenian order, past a "dr."), from the `people` query: who
  they are, and links to their website and social profiles (`PersonCard.vue`). The team first, as cards with their
  pictures large, their bios, and how many episodes they're on as what (`teamSummary`, e.g. 37 episodes as co-host,
  host and consultant; `kindOf` in `lists.ts`: anyone credited as host, co-host, producer, consultant or designer),
  then the guests in three columns under their letters, each with their latest episode's title and how many they're
  on, with an A–Z index (on phones a row of letters that stays at the top), then, folded, the candidates. Anyone not
  signed in gets only those on the released episodes, with only those episodes; candidates are the team's.
- People's names (on the People page and an episode's page, and who recommended an idea) lead to their pages,
  `/people/<ID>`, from the `person` query: a workspace like an episode's, with how many episodes they're on as what
  in its panel, each also a filter (`?role=HOST`), and where they're online; what's coming up (the team's), their
  released episodes by season, as rows or cards, and the episodes they recommended. Anyone not signed in
  gets the page of someone on a released episode, as the People page lists them, with only those episodes.
- Search is the team's: signed in, the header has a Search button (`SearchPalette.vue`; also ⌘K, Ctrl+K and `/`),
  which opens a few of each kind over the page, and its See all the search page, `/search?q=`, with all of them by
  kind; the address keeps what's searched. Both ask the `search` query, as does the `search` MCP tool:
  [`Search.kt`](src/main/kotlin/si/ogrodje/metaos/Search.kt) finds episodes, ideas too, people and recording sessions.
  An episode code, however it's typed (`s5e8`, `S5 E8`, `5x08`), or an ID is that episode alone, which ↵ opens; a
  season (`S04`) lists it. Otherwise every word has to start a word, or be in one, in an episode's titles, people,
  show, summaries (not the AI one) or session, a person's name or bio, or a session's name, place, address or date; case
  and diacritics don't matter (`fold`, also in `fe/src/search.ts`, which marks the words found). Each word counts the
  weight of the field it's best in (`SearchField`), and a row says why it was found when it doesn't show it.
- Users sign in with keycloak-js (`fe/src/stores/auth.ts`), straight to Discord or Google (`idpHint`). Once the page
  is hydrated it checks for a Keycloak session in a hidden iframe of `fe/public/silent-check-sso.html`, which is why
  MetaOS's pages may be framed by MetaOS itself (`X-Frame-Options: SAMEORIGIN`, in `routeRules`). Whether the user was
  on the team last time is kept in `localStorage`, as a hint only, read once the page is hydrated: while Keycloak is
  asked, the header keeps the team's tabs and pages their places, and the check waits longer before taking them for a
  guest. After 3 s a note under the header says it's checking (`checkingSlowly`), and when it gives up, that it
  couldn't confirm the sign-in, with Try again (`unconfirmed`), so the guest's page doesn't look like a sign-out.

### Authentication (Keycloak)

The MCP endpoint (`/mcp`) is an OAuth2 resource server. [Keycloak] from `compose.yml` is the identity provider /
authorization server, with realm `metaos`, which Terraform makes and keeps as it says
([`infra/keycloak`](infra/keycloak/README.md)), in development as in production. Keycloak persists into the
`keycloak` database of the `postgres` service (same credentials as the app, from `.envrc`); the one-shot
`keycloak-db-init` service creates that database when it is missing.

```bash
docker compose up -d keycloak   # http://localhost:8180 (admin console: admin / admin), with the master realm only
just keycloak adopt dev         # realm metaos, the first time (infra/keycloak/README.md)
just keycloak apply dev         # and after each change to infra/keycloak/realm; the first time with
                                # TF_VAR_google_client_secret=$GOOGLE_IDP_CLIENT_SECRET, for Sign in with Google
```

- MCP clients (Claude Code, MCP Inspector, ...) discover Keycloak via `/.well-known/oauth-protected-resource/mcp`,
  register themselves with Dynamic Client Registration and log the user in. The realm's Trusted Hosts policy takes
  redirect URIs on `localhost`, `claude.ai`, `claude.com` and `chatgpt.com` only (`trusted_hosts`).
- Tokens need the `mcp:tools` scope; it also puts the MCP URL into `aud`, which the server checks. They also need the
  realm role `metaos-user`, an approved user's (`APPROVED_ROLE` in `Security.kt`, also on `/graphql`'s team data and
  changes and the thumbnails): Keycloak can issue the scope to more than approved users, e.g. a client registered
  through DCR for its own service account, which no login and so no approval gate stands in front of. Test JWTs carry
  it with `claim("realm_access", APPROVED)` (`TestTokens.kt`).
- The front-end signs users in as `metaos-ui`, a public client with PKCE. Its tokens carry `metaos:read` and
  `metaos:write` (`API_READ_SCOPE`, `API_WRITE_SCOPE`: reading the team's data and changing it, e.g. comments), which
  put the `/graphql` URL into `aud` (and don't get them into `/mcp`). They're the client's own scopes, not realm
  defaults, as MCP clients registering themselves could ask for any realm default.
- Dev login: `dev` / `dev`.
- A sign-in lasts 7 days without use and 30 days at most (`sso_session_idle_timeout`, `sso_session_max_lifespan`),
  and refresh tokens as long. Access tokens
  last 5 minutes; keycloak-js keeps them in memory and refreshes them before each request (`fe/src/stores/auth.ts`),
  and only signs the page out when Keycloak refuses the refresh token, not when it can't be reached.
- Sign-ins through Discord and Google are remembered, as with Remember me on Keycloak's login form, which they never
  see: 30 days without use and 90 at most (`remember_me` and its two timeouts in `realm.tf`).
  Otherwise Keycloak's sign-in cookie, `KEYCLOAK_IDENTITY`, went when the browser quit, and with it the front-end's
  only way back to the session, so users signed in again. MetaOS's own Keycloak provider does it
  ([`keycloak/remember-me`](keycloak/remember-me), Java, which `docker/keycloak.Dockerfile` compiles against the
  Keycloak it goes in; compose runs its `dev` stage), the last step of each identity provider's post-login flow
  (`identity_providers.tf`). A Keycloak without it, e.g. compose's before `docker compose up -d --build keycloak`,
  refuses the step.
- `METAOS_MCP_URL` (default `http://localhost:8080/mcp`) and `METAOS_API_URL` (default
  `http://localhost:8080/graphql`) must be the public URLs of `/mcp` and `/graphql`; they are used by both Keycloak
  (token audience) and the app (audience check); Keycloak has them from `infra/keycloak/<env>/main.tf`, which has to
  change with them. `METAOS_UI_URL` (default `http://localhost:5173`) is where the front-end is, for `metaos-ui`'s
  redirect URIs. `KEYCLOAK_ISSUER_URI` points the app at another realm.
- A change to the realm is a change to `infra/keycloak/realm`, planned and applied, never one made in the admin
  console, which the next apply would undo; only users and who has `metaos-user` are the console's. To start over in
  development (wipes all Keycloak data, including registered MCP clients):

  ```bash
  docker compose stop keycloak
  docker compose exec postgres sh -c 'dropdb -U "$POSTGRES_USER" keycloak'
  rm infra/keycloak/dev/terraform.tfstate*
  docker compose up -d keycloak && just keycloak adopt dev
  TF_VAR_google_client_secret=$GOOGLE_IDP_CLIENT_SECRET just keycloak apply dev
  ```

### Accounts

MetaOS knows who's signed in by their account: one per Keycloak user it has seen, by the token's `sub`, linked to the
person it is ([`Accounts.kt`](src/main/kotlin/si/ogrodje/metaos/Accounts.kt),
[`V25__accounts.sql`](src/main/resources/db/migration/V25__accounts.sql)). Keycloak links a user's sign-ins (Google,
Discord, ...) to one user, so they're one account; a person may have several, e.g. the `dev` user and a Google one.

- `AccountsFilter` writes an approved user's account down as their token says it (username, name, email), once the token
  says something new or a few minutes on, so most requests write nothing; each pod keeps what it last wrote.
- An account is linked by its verified email: when it signs in, or when a person gets that email (`create_person`,
  `set_person_email`), unless someone unlinked it. Or by hand, only by `otobrglez@gmail.com` (`ACCOUNT_LINKER`,
  hardcoded; `CurrentUser.canLinkAccounts`): `link_account` and `unlink_account` (MCP), `linkAccount` and
  `unlinkAccount` (GraphQL); one linked to someone else has to be unlinked first. Links stay when emails change, and
  each link and unlink is in the person's history.
- `Accounts.signedInPerson()` is who "you" are (`whoami`, `me`, `my_episodes`, who recommended a new episode): your
  account's person, or, while it isn't linked and wasn't unlinked, the person with your verified email.
- Comments and the history name people through their accounts (`Accounts.people`, by `sub`) when they're shown, so
  linking an account names what it did before too.
- On the front-end: a person's page lists their sign-ins (`PersonSignIns.vue`) and the People page the accounts not
  linked to anyone (`UnlinkedAccounts.vue`, with `PersonPicker.vue`); only `canLinkAccounts` gets Link, Unlink and
  This is me. Your menu lists the realm's identity providers from Keycloak's Account API
  (`<issuer>/account/linked-accounts`, which `metaos-ui`'s tokens may call: they carry the `account` audience and
  `manage-account-links`), and links another one to your Keycloak user with keycloak-js
  `login({ action: 'idp_link:<alias>' })`, Keycloak's application-initiated action (the legacy `/broker/.../link` is off
  since 26.7.2); it falls back to the Account Console's Linked accounts.

### Audit trail

Postgres triggers ([`V6__audit_log.sql`](src/main/resources/db/migration/V6__audit_log.sql)) write every insert, update
and delete on the domain tables to `audit_log`, with the old and new row as JSON. `AuditingTransactionManager` tags each
read-write transaction with the signed-in user (`sub`, `preferred_username`), their OAuth client (`azp`) and the MCP tool
that started it; writes made any other way (psql, migrations) are logged without them. `AuditLog` reads it, for the
`get_history` and `list_recent_changes` MCP tools, for the history on the front-end's episode and session pages
(`History.kt`, which says each change in words, and what it's a change to, `ChangeKind`, so the front-end can group
changes without reading their words), for the latest changes on the home page (`recentChanges`, which leaves out
the sign-ins `AccountsFilter` keeps up to date), and for the MCP tool each comment was written with (Discussions).

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
- Each tab's stream holds one of the browser's six connections to a host over HTTP/1.1, which `yarn dev` serves: with
  six or so MetaOS tabs open in development, the next tab's requests, its sign-in check too, wait for one to close.
  Production's HTTP/2 doesn't run out.
- A new table with an audit trigger is in it by itself. A new page that shows MetaOS's data should use
  `useLiveRefresh`, and, when anyone may see it, load what it shows first with `useFirstLoad` (Front-end).

### Discussions

Episodes (ideas too) and recording sessions have a discussion each, as on GitHub: comments in Markdown, oldest first,
by whoever was signed in, on MetaOS's pages or over MCP. They're the team's: writing one takes an approved user, and
`Episode.comments` and `Session.comments` are empty for anyone else, on released episodes too.

- A comment ([`V24__comments.sql`](src/main/resources/db/migration/V24__comments.sql)) is on one episode or one
  session, by ID, so it stays when they're deleted and comes back with them. It keeps its author's Keycloak user ID
  (`sub`), and goes by the name of the person their account is (see Accounts) when it's shown, else by their username
  then. At most 20,000 characters (`MAX_COMMENT_LENGTH`, also in `fe/src/comments.ts`).
- [`Comments.kt`](src/main/kotlin/si/ogrodje/metaos/Comments.kt) has the rules, for MCP and GraphQL alike: an approved
  user writes as themselves, on an episode or session that isn't deleted, and only the author edits (`edited_at`) or
  deletes. Deleting is soft (`deleted_at`): the comment leaves the discussion, and the history keeps that it was there.
- MCP: `list_episode_comments`, `list_session_comments`, `comment_on_episode`, `comment_on_session`, `edit_comment`
  and `delete_comment` (`CommentsMCP`). GraphQL: `commentOnEpisode`, `commentOnSession`, `editComment` and
  `deleteComment` (`CommentsGraphQL`), its first mutations, which need an approved user's token with `metaos:write`
  (`@approval.writes`).
- `comments` has its own audit trigger, so every comment, edit and delete is in the audit trail, and so in the live
  updates with no code of their own. `AuditLog.history` takes in an episode's or session's comments, which `History.kt`
  tells as "Commented" (with the start of its text), "Edited a comment" and "Deleted a comment". A comment's `tool`,
  shown as e.g. "MCP · comment_on_episode", is the MCP tool its insert in `audit_log` was made by
  (`AuditLog.creatingTools`); none on MetaOS's pages.
- On the episode and session pages, the discussion and the history are one Activity feed (`ActivityFeed.vue`), by
  day, oldest first, filtered to everything, the comments or the changes: the comments (`CommentCard.vue`), with Edit
  and Delete on your own, the changes, and a box for a new comment (`CommentEditor.vue`), with Write and Preview tabs
  like GitHub's. [`fe/src/activity.ts`](fe/src/activity.ts) merges them: one person's changes within 15 minutes of
  each other are one entry, each field from what it was to what it became (a status with each step), and a folder
  linked, unlinked and linked again linked once; writing and editing a comment that's shown are left out, as its card
  says so, deleting one isn't. The preview renders with `MarkdownText.vue`, as posted comments do; ⌘↵ sends. A
  comment's "commented <date>" links to it, its page's address with `#comment-<id>`: opened, the feed shows
  everything, scrolls to it once its comments are there, and outlines it.

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
Terraform has them ([`infra/gcp`](infra/gcp/main.tf), its module `storage` once for each environment); `just
storage-key dev|prod` makes the service accounts' keys, which Terraform doesn't, so none is in its state. gcloud comes
from the devenv shell.

| Bucket                      | Service account       | For                                                                   |
|-----------------------------|-----------------------|-----------------------------------------------------------------------|
| `ogrodje-metaos-dev`        | `metaos-storage-dev`  | development and tests; `test/` is emptied after a day; no soft delete |
| `ogrodje-metaos-public-dev` | `metaos-storage-dev`  | the same, public                                                      |
| `ogrodje-metaos-prod`       | `metaos-storage-prod` | production; deleted and replaced files are kept 30 days; Autoclass    |
| `media.ogrodje.si`          | `metaos-storage-prod` | the same, public, at `https://media.ogrodje.si` through Cloudflare    |

- All are in Frankfurt (`europe-west3`). The private ones have public access prevention: MetaOS hands out signed URLs
  instead, which it signs with the key itself: `uploadUrl` starts a resumable upload, so big files, e.g. an episode's
  artwork (Pictures) and its video and audio, go from the browser straight to the bucket, without MetaOS in between,
  whole or in chunks; `copy` moves one inside the bucket the same way. Their CORS allows that from the front-end's
  origins, `Content-Range` and `Range` included. Uploads MetaOS didn't take (`uploads/`) are deleted after a day, a
  lifecycle rule (`infra/gcp/storage`).
- The public ones are for what everyone may see, e.g. episodes' thumbnails and people's avatars: anyone can read a file
  whose path they know (`allUsers` has `legacyObjectReader`, which is `storage.objects.get` only), but nobody can list
  them. Dev's is served by Google, at `https://storage.googleapis.com/ogrodje-metaos-public-dev/<path>`. Production's
  is named after its domain: `media.ogrodje.si` is a proxied CNAME to `c.storage.googleapis.com` in Cloudflare, which
  picks the bucket by the Host header. Two rules of the zone are for it: Configuration Rule `media: SSL Full`
  (Google's certificate is for `*.storage.googleapis.com`, which Full (strict) would refuse) and Cache Rule
  `media: cache`, which caches its files and tells browsers as their `Cache-Control` says, and nothing without one
  (errors). Smart Tiered Caching is on for the zone, so Cloudflare's data centres ask one near the bucket instead of
  each asking Google. The name, the rules and the caching are Terraform's (`infra/cloudflare`).
  Creating a bucket named after a domain needs whoever applies `infra/gcp` to be a verified owner of `ogrodje.si` in
  Google Search Console. A file deleted from it stays in Cloudflare's cache until it's purged there.
- `GOOGLE_STORAGE_KEY` (the service account's JSON key, on one line) and `METAOS_STORAGE_BUCKET` (`.envrc`) turn it on;
  it's off without the key. `METAOS_MEDIA_BUCKET` turns on the public one (`Media`), served at `METAOS_MEDIA_URL`, by
  default `https://storage.googleapis.com/<bucket>`. In production the key is in `metaos-secret`
  (`PROD_GOOGLE_STORAGE_KEY`, `k8s/set_secrets.sh`) and the rest in `metaos-config`.
- Keys don't expire. The `storage` health check uses the key every hour, on both buckets, and reports a deleted one
  there, in `metaos_storage_connected` and in the log.
- `GoogleCloudStorageTest` runs against the dev buckets when the key is in the environment, under `test/`.

### Pictures

Pictures everyone may see are copied to the public bucket ([`Images.kt`](src/main/kotlin/si/ogrodje/metaos/Images.kt)):
an episode's thumbnail, thumbnails B and C, which go with its alternative titles as they're A/B tested together, and its
web picture, the thumbnail without its words, for ogrodje.si, all 1920 × 1080; and its cover, 3000 × 3000, for podcast
apps; a person's avatar. `ImagesMCP` sets them from Dropbox or Koofr (a place and a path), or from Hygraph (an https URL
on `*.graphassets.com`, the only hosts MetaOS fetches pictures from).

- An episode's pictures are PNG or JPEG of exactly their size (`EpisodeImage`), as the Artwork folder in Dropbox has
  them, wherever they come from: anything else is refused, by its header before it's decoded, and also when MetaOS has
  the file already, e.g. as an avatar. Avatars may be any size, also WebP or GIF. Pictures set before the sizes were
  enforced stay as they are.

- A picture is at `images/<sha256>/`: `original.<ext>` as it came (PNG, JPEG, WebP or GIF, up to 50 MB and 40
  megapixels), and copies 320, 640, 1280 and 1920 px wide where it's wider, `<width>.jpg`, or `.png` when it's
  see-through somewhere (Thumbnailator; TwelveMonkeys reads CMYK JPEGs and WebP). Its path is its content's hash, so
  its files never change: they're served with `Cache-Control: public, max-age=31536000, immutable`. The same file is
  stored once (`images`, by `sha256`), however often it's used; setting a picture again after it changed where it came
  from makes a new one. One at a time, the download too, as a 3000 × 3000 cover takes some 400 MB to decode and
  resize; at a 1Gi limit, 17 at once got the pod OOM-killed, hence its 2.2Gi.
- `episodes.thumbnail_image_id`, `thumbnail_b_image_id`, `thumbnail_c_image_id`, `web_image_id`, `cover_image_id` and
  `people.avatar_image_id` point at them; nothing deletes an image or its files. `History.kt` names them by the file
  they were made from, e.g. "Thumbnail: ogrodje-vresGams-thumb1.png"; Hygraph's URLs have no file name, so the tools
  take one (`name`).
- GraphQL has them as `Image` (`thumbnail`, `thumbnailB`, `thumbnailC`, `web` and `cover` on `Episode`, `avatar` on
  `Person` and `EpisodePerson`), with the original's `sha256`, batched for lists; thumbnails B and C only for the team, as titles B and C are.
- The team uploads an episode's pictures from its page ([`Artwork.kt`](src/main/kotlin/si/ogrodje/metaos/Artwork.kt)):
  `artworkUpload` gives the browser a signed URL in the private bucket (`uploads/<id>`), where it sends the file itself,
  and `setEpisodeImage` publishes it as from Dropbox (`ImageSource.UPLOAD`, kept by its file's name), refusing another
  size; it replaces a picture the episode has only with `replace`, and the upload goes either way. Then it's saved in
  the episode's first Dropbox folder too, unless the same content (Dropbox's content hash) is right in it or in a folder
  right in it, under another name ("(1)") when its own is taken; a read-only place or a missing folder only says so
  (`dropbox.error`). MCP's `set_episode_image` doesn't take uploads.
- On the front-end, Upload artwork in the episode's media (`MediaStrip.vue`, which shows the team its five places)
  opens `ArtworkUpload.vue`, as a missing picture's place does, at its row: drop the episode's folder or pictures, or
  choose them, or one on its row. [`fe/src/artwork.ts`](fe/src/artwork.ts) reads each file's size from its header and its SHA-256, and places
  it by size and name (`-thumb1`/`-thumb-1`/`-thumbnail` A, `-thumb2` B, `-thumb3` C, `-web`, `-podcast`/`-cover`; the
  only square one is the cover), refusing what fits nowhere; the same file as the current one isn't sent. Upload asks
  once about every picture that would replace one, then sends them one at a time (`fe/src/uploads.ts`), each with its
  progress; one refused or failed holds nothing back, and Try again sends only that one. The front-end shows an episode's on
  its page (`MediaStrip.vue`) and avatars on the People, person and episode pages (`PersonAvatar.vue`, initials
  without one), with `srcset` from the copies (`fe/src/images.ts`), never the original, which may be 16 MB.
- `ImagesTest` and `ArtworkTest` run against buckets in memory (`FakeStorage`), with fake Dropbox and Koofr.

### Video and audio

An episode's video and audio are the team's, in MetaOS's private bucket, not streamed: there to be handed on, e.g. to
patreon-gateway, transcription or a backup ([`MediaFiles.kt`](src/main/kotlin/si/ogrodje/metaos/MediaFiles.kt),
[`V30__episode_media.sql`](src/main/resources/db/migration/V30__episode_media.sql)). Each file is one of four kinds,
as Patreon has them (`MediaKind`): the full video and the full audio, one each, and bonuses as video or audio, any
number, each with a title.

- The team uploads them from the episode's page as it does artwork: `mediaUpload` signs a resumable upload in the private
  bucket (`uploads/<id>`; its `size` a `Float`, as GraphQL's `Int` stops at 2 GB), the browser sends the file there
  itself in 16 MB chunks, going on from where Cloud Storage has it after the connection broke
  ([`fe/src/resumable.ts`](fe/src/resumable.ts)), then `addEpisodeMedia` makes it the episode's. Video is MP4 or MOV,
  audio MP3, M4A, WAV or FLAC (`MEDIA_TYPES`), up to 50 GB. A full one replaces the episode's only with `replace`, and
  the same file (Cloud Storage's MD5) isn't added twice; the upload goes either way.
- Once it's there, ffprobe reads what's in it from a signed URL, over HTTP range requests, megabytes rather than the
  file ([`MediaProbe.kt`](src/main/kotlin/si/ogrodje/metaos/MediaProbe.kt)): a video has to have video, an audio audio
  and no video, as a cover in an MP3 isn't video. Its whole answer is kept (`probe`, JSON), and what's shown in columns
  (`MediaInfo`: duration, container, codecs, size, frame rate, bit depth, colour, sample rate, channels, tracks, when
  and with what it was made). Then Cloud Storage copies it to `episode-media/<upload>/<name>` without it passing
  through MetaOS, downloaded as a file by its name (`Content-Disposition`). ffprobe reads only the formats MetaOS takes
  and only over https (or from disk, in tests), so a playlist uploaded as an MP4 doesn't make it fetch what it names.
  It's a static build in MetaOS's image (`docker/Dockerfile`) and in devenv; `FFPROBE` points at another, e.g. for an
  IDE that doesn't run devenv.
- Removing one (`removeEpisodeMedia`, `remove_episode_media`) deletes its row and, once that's in, its file;
  production's bucket keeps a deleted file 30 days, where only an administrator gets it back. The audit trail keeps the
  row, and `History.kt` tells it, e.g. "Uploaded the full video: S05E08.mp4", "Made bonus.mov the full video".
- GraphQL has them as `Episode.media` (none for anyone not on the team) and `mediaFile`, with `downloadUrl`, a link
  for an hour, signed as it's asked for. MCP: `list_episode_media` (with those links, e.g. for
  `set_patreon_post_media`), `update_episode_media` (title, or what it is, within video or audio) and
  `remove_episode_media`; MCP can't send gigabytes, so it doesn't upload.
- On the front-end, the episode's media has Video and audio (`MediaList.vue`): the full video and audio, a missing one
  opening the upload for it, then the bonuses, each with what ffprobe read (`infoLine` in
  [`fe/src/media.ts`](fe/src/media.ts)), Download and Remove (asked on its row). Upload video and audio
  (`MediaUpload.vue`, styled with `ArtworkUpload.vue` by `_upload-dialog.scss`) reads each file in the browser with
  mediainfo.js (MediaInfo in WebAssembly, loaded once a file is read), places it by what's in it and its name (the
  longest video the full video, the longest audio the full audio, `-bonus` a bonus), refuses what MetaOS doesn't take,
  warns about what looks wrong (a full video under 1080p, audio not at 44.1 or 48 kHz, a full audio not as long as the
  video), asks once before replacing, and sends one at a time; leaving the page while it's sending asks first. The
  episode's panel says when the full audio and video differ in length by more than 5 s (`mediaConcerns`).
- `MediaFilesTest` runs against a bucket in memory with ffprobe's answers as its files (`FakeProbe`); `MediaProbeTest`
  runs ffprobe itself where FFmpeg is installed, also on a file in the dev bucket with its key;
  `GoogleCloudStorageTest` sends a file in chunks and copies it in the dev bucket.

### Workflows (Temporal)

What takes long, waits on others or has to happen in order, MetaOS does as durable workflows on [Temporal]
([`Workflows.kt`](src/main/kotlin/si/ogrodje/metaos/Workflows.kt)), through its Spring Boot integration
(`io.temporal:temporal-spring-boot-starter`, `temporal-kotlin`): Temporal keeps each workflow's every step and runs it
to the end through restarts and deploys, retrying what failed.

- MetaOS is the worker, on task queue `metaos` (`WORKFLOWS_QUEUE`): its workflows are classes in this package
  (`@WorkflowImpl`, which Temporal makes, not Spring), its activities its own beans (`@ActivityImpl`), so an activity
  uses what MetaOS has (its bucket, its repositories) and a workflow's code stays deterministic. Workflows' arguments
  are Kotlin data classes, through Jackson 2 with its Kotlin module (`temporalDataConverter`; temporal-kotlin's
  `KotlinObjectMapperFactory` calls a constructor Jackson 2.21 no longer has).
- `TEMPORAL_ADDRESS` (`spring.temporal.connection.target`) turns it on; it's off without, as Temporal's own
  auto-configuration is, and so is what needs it (`@ConditionalOnTemporal`). In development it's compose.yml's
  `temporal`, Temporal's development server with its UI at http://localhost:8233: `TEMPORAL_ADDRESS=localhost:7233`.
  The `temporal` health check asks its frontend every hour; it's not in the app's readiness, so MetaOS answers while
  Temporal is down.
- In production, Temporal is a server of its own in `metaos-prod`
  ([`deployment-temporal.yaml`](k8s/base/deployment-temporal.yaml), `temporalio/server`, 1.32.1), all its services in
  one pod, configured by environment variables alone (its embedded config template), in databases of the shared CNPG
  cluster: `metaos_temporal` and `metaos_temporal_visibility`, owned by role `metaos_temporal`, as Keycloak's are
  (`k8s/set_secrets.sh`). Its `schema` init container (`temporalio/admin-tools`, the same version) sets their schemas up
  the first time and updates them after an upgrade, before every start; the Job `temporal-namespace` makes namespace
  `default` (two weeks' retention). MetaOS's two pods are its workers (`TEMPORAL_ADDRESS=temporal:7233`). Nothing signs
  in to Temporal, so a NetworkPolicy lets only `metaos-prod`'s pods reach its frontend, and Prometheus its metrics
  (ServiceMonitor); its UI (`temporalio/ui`) is on the tailnet only, https://metaos-temporal.folk-decibel.ts.net.
  Upgrades go one minor version at a time, server and admin-tools together (k8s/README.md).
- What an activity changes is in the audit log as whoever asked for the workflow, through client `metaos-workflows`
  (`actingFor`, `AuditingTransactionManager`), as nobody's signed in in a worker's thread.
- What only so many may do at once, whichever pod asks, takes turns
  ([`Slots.kt`](src/main/kotlin/si/ogrodje/metaos/Slots.kt)): a workflow for each thing, `slots-<resource>`
  (`SlotsWorkflow`), hands its turns out in the order they're asked for, up to its limit (`metaos.slots.<resource>`),
  and the next when one is given back. A workflow waits for its turn on Temporal, holding nothing in MetaOS (`withSlot`,
  in a `SlotHolder`), and gives it back when it's done, fails or is cancelled; one that ends without giving it back
  (terminated) has it taken back at the next check, every ten minutes (`metaos.slots.check-every`). The slots end when
  no one holds or waits for a turn, and the next ask starts them again. Each provider's transcriptions take turns, 5 at
  once (`google`, `soniox`; Soniox lets the organization run 10), and agents, 2 (`agents`).
- A workflow that waits on something outside, e.g. a provider's transcription, waits on Temporal's timer between quick
  checks, rather than in an activity that would hold one of the worker's 200 slots (and a thread) while it sleeps.
- Tests run workflows in Temporal's test server, in memory (`spring.temporal.test-server.enabled`, `TranscriptionTest`).

### Agents

Agents are workflows too ([`Agents.kt`](src/main/kotlin/si/ogrodje/metaos/Agents.kt)): `AgentWorkflow` runs an
`AgentTask` (what it is, what to do, which of MetaOS's MCP tools it may use) with Spring AI's `ChatClient`, through
Temporal's Spring AI plugin (`io.temporal:spring-ai`, Public Preview), which makes each model call and each MCP tool call
an activity: an agent a restart interrupts goes on from its last step. Other workflows start one as a child. Two work
at once (`metaos.slots.agents`, `METAOS_AGENT_SLOTS`; see Workflows), as each reads a whole transcript through a model,
which costs; the rest wait their turn.

- The model is any of OpenRouter's, through Spring AI's OpenAI client (`spring.ai.openai.base-url`, OpenRouter speaks
  OpenAI's API), `anthropic/claude-sonnet-5.5` by default (`METAOS_AGENTS_MODEL`) or the task's own. `OPENROUTER_API_KEY`
  turns agents on; workflows skip what agents do without it. Spring AI doesn't retry (`max-retries=0`), Temporal does.
- Their tools are MetaOS's own MCP tools, at its own `/mcp`, through Spring AI's MCP client (`spring.ai.mcp.client`,
  connected when first used, and not offered as MetaOS's tools in turn), as Keycloak's `metaos-agents`: a confidential
  client whose service account is approved (`infra/keycloak/realm/agents.tf`), with a token by client credentials
  (Spring Security's OAuth2 client, `METAOS_AGENTS_CLIENT_SECRET`). So the tools check, audit and attribute what agents
  do as for anyone: service-account-metaos-agents, MCP · its tool. A task names the tools it may use; it gets no others.
- Temporal's plugin 0.1.1 finds MCP clients only as beans of their own, while Spring AI keeps them in a list, so MetaOS's
  is a bean of its own too (`agentsMcpClient`); its main branch finds both.

### Transcripts

An episode's video and audio are transcribed by a speech-to-text provider, Google's Speech-to-Text or Soniox
([`SpeechToText.kt`](src/main/kotlin/si/ogrodje/metaos/SpeechToText.kt),
[`Soniox.kt`](src/main/kotlin/si/ogrodje/metaos/Soniox.kt)), in a workflow, `TranscriptionWorkflow`
([`Transcription.kt`](src/main/kotlin/si/ogrodje/metaos/Transcription.kt)): its ID is the file's and the provider's
(`transcribe-<media ID>-<provider>`), so a file has one transcription at a time by each. The workflow does the work;
[`Transcriptions.kt`](src/main/kotlin/si/ogrodje/metaos/Transcriptions.kt) has the rules, for MCP (`TranscriptsMCP`),
GraphQL (`TranscriptsGraphQL`) and the Transcriptions page alike, as Sessions and Comments do theirs.

- A transcription ([`V33__transcriptions.sql`](src/main/resources/db/migration/V33__transcriptions.sql)) is a row from
  when it's asked for till it ends, which the workflow keeps up to date as it goes: `QUEUED` (waiting for its turn at
  the provider, its place in line from the provider's slots), `STARTING`, `RECOGNIZING` (with how far the provider got,
  by tens, where it says), then `DONE` (with the transcript it made), `FAILED` (with why) or `CANCELLED`. Its audit
  trigger puts it in the live updates, so pages see it change; an episode's history leaves it out, as its transcripts
  say what was transcribed. One is on by a provider for a file at a time (a unique index, as its workflow's ID).
  Temporal runs it; one on whose workflow ended without saying so (terminated) is marked failed when it's next listed,
  telling a run that started for it from one that hasn't yet (`ended`).
- Cancelled, the workflow stops, asks the provider to stop too (Google cancels its operation, Soniox deletes the
  transcription; it may not have time to), gives its turn back and marks the row; the file keeps the transcript it had.

- A provider is a `SpeechToText` (its name, `google` or `soniox`, and model): it starts on a file in the bucket, with
  what the episode tells about it (`SpeechHints`: its title, show and people, to spell names), says when it's done,
  leaving what it heard in the bucket as it answered, and reads that as its own pieces, each with its words and when
  they were said (`heard`). `SpeechProviders` has them by name; `transcribe_episode` says which (`provider`), else
  `metaos.speech.provider` (`METAOS_SPEECH_PROVIDER`, `google`); each transcription keeps who heard it and with which
  model. Another provider is another `SpeechToText`, whose `heard` puts its answer in the same terms.
- Google's Speech-to-Text: Slovenian lowercase and unpunctuated, no speakers, a minute a piece, in the EU, $0.96 an
  hour of audio, or $0.18 in a batch. Soniox: punctuated, its speakers told apart (`Speaker 1`, ...), in Ogrodje One's
  region, the United States, about $0.10 an hour (its context a little more).
- A provider transcribes 5 files at once (`metaos.slots.<provider>`, `METAOS_GOOGLE_SLOTS`, `METAOS_SONIOX_SLOTS`), from
  its start till it's done; the rest wait their turn, in order, `QUEUED` with their place in line
  (`get_episode_transcript`). Saving and the chapters don't hold a turn. A provider that's too busy to start (429) or
  down is asked again after 30 s, then ever less often, up to every 10 minutes, for 6 hours; one that refuses isn't.
  Then the workflow asks it how it's going after 30 s (`metaos.transcription.poll-every`), then ever less often, up to
  every 5 minutes, waiting on Temporal's timer in between (`recognize`), for 26 hours at most.
- Google: Speech-to-Text's batch recognition reads the file from MetaOS's private bucket itself (its service agent may,
  in the project's buckets) and writes what it heard next to it, under `transcripts/<media ID>/<run>/`, as JSON (not as
  subtitles, which take word timestamps): minutes for an episode, or up to a day in a batch (`DYNAMIC_BATCHING`) for a
  fifth of the price, $0.016 or $0.003 a minute. MetaOS starts it as the bucket's service account
  (`roles/speech.client`, `infra/gcp/storage`).
- An episode is spoken in its show's language (`Show.language`, [`V31__show_languages.sql`](src/main/resources/db/migration/V31__show_languages.sql),
  `create_show` and `update_show`): Slovenian (`sl-SI`, also without a show), but Borderless's English (`en-GB`, as
  the team transcribed it before).
  That's what it's transcribed as, unless `transcribe_episode` is told another (`language`), and what the agent writes
  in.
- The model is `long`, as the team used it in Speech-to-Text's console (project sandbox) before MetaOS, but in
  location `eu` (`eu-speech.googleapis.com`, which keeps the audio in the EU), in the episode's language alone (an
  alternative `en-GB` heard Slovenian as English gibberish), on the first channel (the episodes' are mixes of the same,
  which separately were billed twice), with what the model can do for the language turned on, as Google's Locations
  API says (`features`): word timestamps, and punctuation, which `long` has for English but not for Slovenian, so
  Slovenian transcripts are lowercase and unpunctuated. Chirp 3, which punctuates Slovenian, heard a 13-minute episode
  as one stretch and looped ("v spet v spet ..."). No Google model tells speakers apart in Slovenian. Its pieces are
  its results, each from its first word (`googleHeard`). `METAOS_SPEECH_LOCATION` and `_MODEL` change them.
- Soniox ([`Soniox.kt`](src/main/kotlin/si/ogrodje/metaos/Soniox.kt)): its async transcription (`stt-async-v5`,
  `METAOS_SONIOX_MODEL`), on with `SONIOX_API_KEY` (`.envrc`; in production `metaos-secret`, `k8s/set_secrets.sh`). It
  fetches the file itself, from a link to the bucket signed for a day, as a transcription may wait in its queue; it's
  told the episode's language as a hint (`sl`, as English is spoken in Slovenian episodes too), to tell speakers apart,
  and the episode's title, show and people as its context, which it spells names by. Once it's done, MetaOS keeps its
  answer in the bucket (`soniox.json`: the transcription and its tokens) and deletes it at Soniox, which keeps
  transcriptions until they're deleted. Its tokens are parts of words, which MetaOS puts together, and a speaker's turn
  is a piece (`sonioxHeard`). A project, and so its key, is in a region, where the audio and transcripts are processed
  and kept: Ogrodje One's is the United States (`api.soniox.com`); the EU's is `api.eu.soniox.com` (`SONIOX_API_URL`),
  with a key of a project there. A request it turns down (`SonioxRefused`: invalid, a key it doesn't take, no balance)
  isn't tried again; one it's too busy for is. `SonioxTest` runs it against a stand-in API.
- A transcript ([`Transcripts.kt`](src/main/kotlin/si/ogrodje/metaos/Transcripts.kt),
  [`V32__transcripts.sql`](src/main/resources/db/migration/V32__transcripts.sql)) is what was said in MetaOS's own
  format, whichever provider heard it (`TranscriptContent`): stretches, each with its speaker, where the provider tells
  them, its text and its words, each with when it was said (and how sure the provider was, where it says). MetaOS cuts
  what a provider heard into stretches of 20 s at most, at a pause of a second, or at a sentence's end once it's 8 s
  long (`inStretches`), so chapters are to the quarter-minute whatever pieces the provider heard (Google's `long`: a
  minute). It's in the database, as JSONB, in `transcript_contents`, a table without an audit trigger (an hour's is
  ~400 KB, ~150 KB stored, which the audit log would keep again); and in the bucket, the same, as `transcript.json`
  (`path`), next to what the provider answered (`raw_path`), from which it can be made again. The `transcripts` row
  says what it's of, who heard it and with which model, how many stretches and words, its speakers (their labels, till
  they're named), who asked, and two languages: `spoken_language`, what's said in the file, and `language`, what the
  text is in. A file has one transcript of what's said in it (the two the same, `findSpoken`) by each provider, to
  compare them, which transcribing it again by that provider replaces, only when asked (`replace`), and one in each
  other language, for translations later. `History.kt` tells it: "Transcribed S05E08.mp3".
- The workflow ends with the transcript saved; what's done with one (e.g. an episode's `machine_summary`) is for agents,
  the Editor to come, through these tools.
- MCP: `transcribe_episode` (its full audio, else its full video, or a file by ID; `provider`, `language`, `batch`,
  `replace`; it answers at once, with the transcription's ID), `get_transcription` (one by its ID, as it is now, to
  watch it), `list_transcriptions` (the latest first; by status, `on` for those on, provider and episode),
  `cancel_transcription`, `get_episode_transcript` (`[h:mm:ss] text`, or `[h:mm:ss] Speaker 1: text`, a line a stretch,
  from the database; `media` and `provider` narrow it; and its files' transcriptions that are on, or that failed or were
  cancelled last) and `remove_transcript` (the bucket keeps its files).
- GraphQL: `transcriptions` (by statuses and provider), `transcription` (one by its ID), `transcript` (with `lines`,
  what was said), `speechProviders` and `Episode.transcripts`, for the team (empty or refused for anyone else);
  `transcribeEpisode`, `cancelTranscription` and `removeTranscript`, with `metaos:write`.
- The Transcriptions page (`/transcriptions`, the team's, a tab; `TranscriptionsView.vue`,
  [`fe/src/transcriptions.ts`](fe/src/transcriptions.ts)) transcribes a file (`TranscribeForm.vue`: an episode by its
  code, then its file, the provider and the language, Slovenian or English (its show's to begin with: Borderless's is
  English, `Episode.language`); Replace only for a file the provider has transcribed, Batch only with Google) and lists
  what's on, with its place in line or how far it is and Cancel, then what ended, the latest first, with how long it
  took and why it failed, each transcript's lines to Read (`TranscriptLines.vue`) and Remove, asked on its row
  (`TranscriptionRow.vue`). It filters by status and provider, and finds by episode or file; live updates keep it as it
  is, as rows change.
- `TranscriptionTest` runs the workflow in Temporal's test server against a bucket and providers in memory
  (`FakeSpeech`: `fake`, `other`, and `single`, with one turn), over MCP and GraphQL.

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
- An episode's page shows the pictures in its folders as its Artwork, a tab of its media beside its photos and like
  them, eight at a time: each whole, as covers, thumbnails and stories have different shapes, by file name, as that
  says what it's for, and with what it's used as, e.g. Thumbnail A, by the file its picture was made from. As with Koofr's photos,
  they're those right in a folder, then those in the folders right in it ([`DropboxPictures.kt`](src/main/kotlin/si/ogrodje/metaos/DropboxPictures.kt)).
  Dropbox makes the thumbnails; MetaOS passes them on at `/dropbox/thumbnail`, behind `metaos:read` and only for those
  pictures, which it checks against the linked folders' IDs through `DropboxFolderIndex`.
- The `dropbox` health check uses it every hour and reports a rejected token there, in `metaos_dropbox_connected` and in
  the log. `DropboxTest` runs against the real account (read-only) when the refresh token is in the environment.

### Patreon

MetaOS reaches Ogrodje's Patreon through [patreon-gateway](patreon-gateway/README.md), a service of its own in
[`patreon-gateway/`](patreon-gateway) (Kotlin, Spring Boot and Playwright; a Gradle build of its own,
`./gradlew -p patreon-gateway test`), which MetaOS alone calls, with its token. It reads through Patreon's API, as the
campaign's creator, and makes, changes, publishes and deletes posts and uploads an episode's audio or video in Patreon's
web app, signed in as the bot, `iva.ogrodje@gmail.com`, acting as the creator, by calling Patreon's own web API from a
page of the campaign, as its editor does (not a public API: the README says how it was found). Changes are jobs, one at
a time; one Patreon turns away (a rate limit) tries again by itself.

- [`Patreon.kt`](src/main/kotlin/si/ogrodje/metaos/Patreon.kt) is MetaOS's client: `PATREON_GATEWAY_URL` and
  `PATREON_GATEWAY_TOKEN` turn it on (`metaos.patreon.*`); it's off without the token. `PatreonMCP` has the tools:
  tiers, members (`list_patreon_members`: status, tiers, pledges and charges, with names and emails), posts and drafts
  (`list_patreon_drafts`, through Patreon's web app, as its API lists only what's published), new drafts
  (`create_patreon_draft`), their audio or video from Dropbox, as a temporary link the gateway downloads, or any https
  URL (`set_patreon_post_media`), download links for a post's audio or video (`get_patreon_post_media`: the MP3 as
  uploaded, or the best MP4 Patreon's video host, Mux, keeps, which needs Patreon as its referrer; signed for a day or
  more), publishing, which can set the episode's Patreon link (`publish_patreon_post`, the only one patrons see, so the
  only open-world one), deleting, and the jobs. Quick changes are waited for; an upload comes back as a job
  (`get_patreon_job`).
- The bot signs in once by hand (`patreon-gateway/login.sh`), and the gateway keeps the session and the refreshed
  creator tokens on its volume. A refresh ends the old tokens, so only one gateway may use a creator token. The `patreon` health check asks the gateway every hour, which says whether Patreon's
  API answers and the browser is still signed in, in `metaos_patreon_connected` and in the log.
- `PatreonTest` runs the client against a stand-in gateway; the gateway's own tests need no browser.

### New Relic

MetaOS is watched with New Relic, account 8666724 in its EU region, on its free plan: 100 GB a month, after which it
takes nothing until the next, and one full user ([`NewRelic.kt`](src/main/kotlin/si/ogrodje/metaos/NewRelic.kt),
[`newrelic/newrelic.yml`](newrelic/newrelic.yml)).

- Its Java agent runs with MetaOS and patreon-gateway (`-javaagent` in their images, from `build/newrelic`, which
  `./gradlew bootJar` and `-p patreon-gateway bootJar` fill): requests, the database, calls out, errors, the JVM, and
  the app's logs, each with its request. It doesn't start without `NEW_RELIC_LICENSE_KEY` (`metaos-secret`, from
  `PROD_NEW_RELIC_LICENSE_KEY`, `k8s/set_secrets.sh`), so the tests never have it. They report as `MetaOS` and
  `patreon-gateway`, `environment:production` (`NEW_RELIC_LABELS`). The agent is 41 MB, a layer of its own, pushed
  again only when its version changes; 8.25 is the first for Java 25.
- GraphQL and MCP answer 200 with their errors in the body, so `NewRelic.kt` reports them, the caller's mistakes as
  expected, which New Relic shows but doesn't count as errors (`callersMistake`: `IllegalArgumentException`, which
  `require` throws, a GraphQL argument that doesn't convert, and being denied); the errors alert leaves them out too.
  The agent reports GraphQL's itself, the caller's mistakes too, and a request keeps the last: each field's exception
  (wrapped, `CompletionException`), the null a non-null field's error leaves, and invalid queries. So
  `GraphQLErrorReports` (the first exception resolver, and an `Instrumentation`) notes each field's exception, and
  once the request is done reports the first that isn't the caller's mistake, else the first that is, else has the
  agent ignore its own. An advisor on `@McpTool` methods reports what a tool throws, before Spring AI makes it the
  tool's result. Without the agent, its API does nothing. The agent leaves out 4xx responses (`ignore_status_codes`),
  e.g. the 401 every MCP client's sign-in starts with.
- The front-end's browser agent ([`fe/src/newrelic.ts`](fe/src/newrelic.ts), `plugins/newrelic.client.ts`) reports
  the pages' errors, Vue's too (which Vue only logs), their load times and their requests to MetaOS, as the start of
  their traces there. It keeps nothing in the browser (`cookies_enabled: false`), so there's nothing to ask visitors,
  and it's off without `NUXT_PUBLIC_NEW_RELIC_*`: the browser app "MetaOS web" (`deployment-web.yaml`), whose
  settings every page sends anyway.
- In development: `./gradlew newRelic`, `-Xshare:off -javaagent:build/newrelic/newrelic.jar` in IntelliJ's run
  configuration (VM options; `-Xshare:off`, as in the images, as class data sharing warns about the agent otherwise),
  and in `.envrc` the license key, `NEW_RELIC_APP_NAME="MetaOS (dev)"`,
  `NEW_RELIC_LABELS=environment:development`, `NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED=false` and the browser
  app "MetaOS web (dev)" (`NUXT_PUBLIC_NEW_RELIC_APPLICATION_ID` and `_AGENT_ID` 538920244, the rest as production's).
- Ping monitors, which the free plan doesn't count, check every 5 minutes from Frankfurt, London and Milan that the
  pages (`/healthz`), MetaOS (`/readyz`) and Keycloak (its realm's OpenID configuration) answer. The alert policy
  "MetaOS" has conditions on them, on the back end's errors and on the browser's, and on the cluster's metrics
  (below), mailed to Oto. They, the browser apps and the dashboard "MetaOS on ogrodje-one" are Terraform's
  ([`infra/newrelic`](infra/newrelic)); the agents make their APM apps themselves. Keycloak has no agent: it's
  Keycloak's own image, watched by those checks and its metrics.
- The cluster's Prometheus passes some of its metrics on to New Relic by remote write
  ([`k8s/prometheus/newrelic-values.yaml`](k8s/prometheus/newrelic-values.yaml), applied by `newrelic.sh` to
  kube-prometheus-stack, which the cluster's apps share): from namespace `metaos-prod` only, MetaOS's own
  (`metaos_*_connected`, `metaos_changes_*`), patreon-gateway's (`patreon_gateway_*`), Keycloak's sign-ins
  (`keycloak_user_events_total`, by event and error, which its image builds in: `KC_EVENT_METRICS_USER_ENABLED`, a
  build option) and its password checks, `up`, and the pods' (cAdvisor's memory and CPU, kube-state-metrics' limits,
  restarts, last termination reasons such as OOMKilled, readiness, replicas available); and the node's, from
  node-exporter and kube-state-metrics: memory, the root filesystem, load, and memory pressure (major page faults,
  stalls). That's MetaOS's Kubernetes monitoring, without New Relic's own Kubernetes integration (`nri-bundle`), whose
  agents the node's memory hasn't room for; New Relic's Kubernetes pages stay empty. A new metric for New Relic goes in
  the allowlist. Keycloak's metrics come from its management port (9000) through a ServiceMonitor, as MetaOS's do
  from 9090.

### Deployment

Production runs on the ogrodje-one Kubernetes cluster, namespace `metaos-prod`: the app at `https://metaos.ogrodje.si`
and its Keycloak at `https://metaos-auth.ogrodje.si`. Pushes to `master` deploy through
[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml); [`k8s/README.md`](k8s/README.md) has the manifests,
the one-time setup and the `just` recipes for deploying by hand. [`DEPLOY.md`](DEPLOY.md) explains the setup, the
decisions behind it and what is left to do.

- Pull requests run the same tests, [`.github/workflows/test.yml`](.github/workflows/test.yml) (the check `test`),
  which master's ruleset requires; repository admins bypass it, so they can still push to `master`. With auto-merge on
  (`gh pr merge --auto --squash`, or Enable auto-merge on GitHub), a pull request is merged once they pass, and so
  deployed.

- The pages are the front-end's own Deployment, `metaos-web`, also two pods: the Ingress sends MetaOS's paths
  (`/graphql`, `/events`, `/mcp`, `/.well-known`, `/koofr`, `/dropbox`, the probes) to MetaOS and everything else to
  it; its server asks MetaOS's GraphQL inside the cluster (`http://metaos`). Cloudflare caches the pages anyone may
  see as their `Cache-Control` says, which a Cache Rule has to allow, as it doesn't cache HTML by default (`metaos:
  pages`, `infra/cloudflare/caching.tf`, which keeps MetaOS's own paths out); the team's pages say `no-store`.
- MetaOS runs as two pods, so one answers while the other restarts or rolls out. Nothing may live in one pod's memory
  that another needs: MCP is stateless (`spring.ai.mcp.server.protocol=STATELESS`, no `Mcp-Session-Id`), as no tool
  needs a session and the bearer token says who's calling; every pod listens for changes (Live updates) and checks its
  connections; caches like `DropboxFolderIndex` are each pod's own. Flyway takes a lock, so both may start at once.
- patreon-gateway is one pod (`deployment-patreon-gateway.yaml`), with its state on a volume, and no Ingress: MetaOS
  calls it at `http://patreon-gateway`, with the token from `patreon-gateway-secret` (`k8s/set_secrets.sh`). CI tests,
  builds and rolls it out with the rest. Its Chromium is a container of its own in the pod, Microsoft's Playwright
  image, which the node pulls from Microsoft, so the browsers never go through our registry over the tailnet; its
  version has to be the gateway's Playwright's (`BrowserVersionTest`). Cloudflare turns away Patreon's pages from the
  cluster's address, so the browser goes out through low (oto.srv4all.net, a VM whose address it lets through), the
  tailnet's exit node, by the pod's `tailscale` container (an ephemeral machine, `PROD_PATREON_TAILSCALE_AUTHKEY`);
  while low is down, jobs wait and try again.
- CI builds each image (`metaos`, `metaos-web`, `patreon-gateway`, `keycloak`) in a job of its own, at once, each
  building what goes in it first (and testing it, but for the app's, whose tests, the longest, are a job of their own
  beside them), and the deploy waits for all five. They get devenv.nix's JDK, Node and Yarn, the same versions, from
  GitHub's setup actions rather than Nix ([`.github/actions/toolchain`](.github/actions/toolchain/action.yml), which
  has to follow devenv.nix), as entering devenv's shell took a minute a job, and production's ffprobe for
  `MediaProbeTest`. The app's tests run in three JVMs (`-PtestForks=3`), with their Postgres pulled while Gradle
  starts. The test JVMs share one Postgres (Testcontainers' reuse, `TESTCONTAINERS_REUSE_ENABLE`, also in devenv,
  where it stays up between runs), on which the migrations run once, into a template named after them; each Spring
  context gets a copy of it, a database of its own, dropped with it (`TestcontainersConfig`). Tests that change no
  beans or properties share one context. A rollout starts both new pods of the front-end at once and the app's one at
  a time, while the old ones keep serving: both of the app's at once were a minute or two faster, until a deploy that
  changed every image ran the node, shared and at 85% of its memory, out of it: it thrashed, as its kubelet evicted
  pods only below 100 MB free. Now it evicts them below 1 GiB (`--eviction-hard` in ogrodje-one's
  `/var/snap/microk8s/current/args/kubelet`), so a pod goes before the node freezes. The images are reproducible: their
  files have `SOURCE_DATE_EPOCH`'s time (`rewrite-timestamp`), not the build's, and the boot jars are too (no file
  timestamps, `AbstractArchiveTask`), so a layer that didn't change, e.g. the dependencies, is the blob the registry
  has, which isn't pushed again over the tailnet (a slow link, ~0.5 MB/s). An image that didn't change is the same
  image, and the deploy points the Deployments at the images by digest, so it rolls only what changed: Keycloak (whose
  `kc.sh build` layer comes from GitHub's cache) and patreon-gateway (whose restart cuts its job short) mostly stay
  up. Anything that puts the build's time into a jar or an image, e.g. Spring Boot's `buildInfo()`, undoes that.
  `nuxt build` does (a random build ID, build times, files' mtimes, its styles in the order they were done), so the
  front-end's image is tagged `src-<key>` too, the key git's hashes of what it's made from (`sources` in the
  workflow's matrix) and its base image's digest: a run that finds one of the same key tags it again instead of
  building, and `metaos-web` isn't rolled.
- patreon-gateway's image is on distroless Java, as the app's, with whose layers it shares: Playwright's driver runs
  its Node.js itself, no shell needed, and the boot jar has only Linux's (`linuxDriverBundle`; the bundle has five
  platforms', 200 MB).

[Ogrodje]: https://ogrodje.si
[Nix]: https://nixos.org/
[devenv]: https://devenv.sh/
[Keycloak]: https://www.keycloak.org/
[Nuxt]: https://nuxt.com/



[Temporal]: https://temporal.io/
