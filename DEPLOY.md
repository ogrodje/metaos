# Deployment

MetaOS runs on the ogrodje-one Kubernetes cluster in namespace `metaos-prod`. It follows the same pattern as goo,
boo-run and bongbong: images in the Tailscale registry, `kustomize` picks the tags, Postgres is the shared CNPG
cluster, ingress-nginx routes and Cloudflare terminates TLS.

[`k8s/README.md`](k8s/README.md) is the operational reference (commands, checks). This document covers the setup as a
whole, the decisions behind it and what is still left to do.

Status (2026-10-19): everything is written and verified locally. Nothing is deployed or committed yet.

## What it sets up

| Piece                       | Where                                         | Notes                                                                                   |
|-----------------------------|-----------------------------------------------|-----------------------------------------------------------------------------------------|
| App                         | `https://metaos.ogrodje.si`                   | `/mcp` is the MCP endpoint; `/graphql`, `/events` and the thumbnails are the front-end's |
| Front-end                   | `https://metaos.ogrodje.si`, all other paths  | `metaos-web`: Nuxt on Node, renders the pages anyone may see; Cloudflare caches them    |
| Keycloak                    | `https://metaos-auth.ogrodje.si`              | Own instance, realm `metaos` baked into the image and imported on first start           |
| Postgres                    | `main-cluster` in namespace `pg`              | Databases `metaos` and `metaos_keycloak`, each owned by a role of the same name         |
| Images                      | `registry.folk-decibel.ts.net/metaos/...`     | `metaos`, `metaos-web` and `keycloak`, tagged with `git describe`                       |
| Actuator and metrics        | Port 9090                                     | Never routed by the Ingress; Prometheus scrapes it through a ServiceMonitor             |
| Probes                      | `/livez`, `/readyz` on port 8080              | Public through the Ingress, but they only answer UP or DOWN                             |

### Files

```
docker/Dockerfile            the app: boot jar on distroless Java 25, Spring Boot layers, runs as non-root
docker/keycloak.Dockerfile   Keycloak 26.8.1, optimized build, keycloak/metaos-realm.json baked in
docker/web.Dockerfile        the front-end: Nuxt's build (fe/.output) on distroless Node 26, runs as non-root
.dockerignore                allowlist: only the boot jar, the realm JSON and fe/.output enter the build context
k8s/base/
  namespace.yaml             metaos-prod
  deployment-metaos.yaml     ConfigMap, Deployment, Service, Ingress (MetaOS's own paths), ServiceMonitor
  deployment-web.yaml        the front-end: Deployment, Service, Ingress (everything else)
  deployment-keycloak.yaml   Deployment, Service, Ingress
  kustomization.yaml         the resources, the public URLs and the image tags
k8s/set_secrets.sh           database roles and databases, secrets, registry pull secret
k8s/ci/deployer.yaml         CI's permissions, scoped to metaos-prod
k8s/README.md                operational reference
.github/workflows/deploy.yml test, build, push, deploy and smoke-test on push to master
justfile                     the same steps by hand: just push, just deploy, just logs, just manifests
```

### Details

- **Public URLs in one place.** `kustomization.yaml` generates a `public-urls` ConfigMap (Keycloak hostname, issuer,
  MCP URL) that both Deployments read. Its name carries a content hash, so changing a URL rolls both.
- **Proxy headers.** The app trusts ingress-nginx's `X-Forwarded-*` headers, so its OAuth metadata advertises
  `https://metaos.ogrodje.si/mcp` rather than the pod's own address.
- **Streaming.** The app's Ingress turns off nginx buffering and raises the timeouts to an hour, because Streamable
  HTTP responses can be long-lived SSE streams. `/events`, the front-end's stream of changes, is one too; it also says
  `X-Accel-Buffering: no`, and sends a heartbeat every 25 s, as Cloudflare hangs up after 100 s of silence.
- **Probes.** Both Deployments have a startup probe, so Flyway migrations and Keycloak's first-start bootstrap don't
  get the pod killed. Keycloak 26.8 answers `started` and `live` while still bootstrapping and only `ready` once
  it's done; readiness keeps traffic away until then. The app's probes use `/livez` and `/readyz` on port 8080 rather
  than actuator on 9090: that's a second Tomcat, which would still answer UP if 8080 hung. Startup probes wait 10 s,
  by when the ports are usually open, so a rollout doesn't log connection-refused warnings.
- **Graceful shutdown.** Both Deployments have a 10 s `preStop` sleep (distroless has no `sleep` binary, so this uses
  the Kubernetes sleep action) to let ingress-nginx drop the old pod before it shuts down. The app then ends its
  `/events` streams, which graceful shutdown would otherwise wait out, and browsers reconnect to the new pod.
- **Resources.** Memory requests and limits, no CPU limit, as in bongbong: a CPU quota would only throttle JVM
  startup on a shared node.
- **`set_secrets.sh` is idempotent.** It finds the CNPG primary by label, creates roles and databases only if
  missing, and always resets the passwords, so re-running it with new values rotates them. Secrets are applied
  rather than deleted and recreated, so there is no window without them.
- **The `dev` user.** The realm JSON has a `dev` user whose password comes from `KEYCLOAK_DEV_PASSWORD`. In production
  that is a random secret, so the account is unusable until someone deliberately uses or deletes it.

## Changes to the app

- `build.gradle.kts`: added `micrometer-registry-prometheus`. The boot jar is now always `metaos.jar`, so the
  Dockerfile doesn't depend on the version.
- `Security.kt`: `/actuator/prometheus` is allowed without a token, next to `/actuator/health/**`. In Kubernetes
  actuator is on port 9090 only, so this doesn't expose anything publicly.
- `application.properties` (main and test, because the test copy replaces the main one): health probe groups on, and
  `health,prometheus` exposed.
- `SecurityTest.kt`: a test that `/actuator/prometheus` is public and serves metrics.
- `devenv.nix`: added `just`.

## How it was verified

- The full test suite passes, including the new Prometheus test.
- `kustomize build k8s/base` renders; `k8s/ci/deployer.yaml` passes a client dry-run; the workflow YAML parses; the
  justfile lists its recipes; `set_secrets.sh` passes `bash -n`.
- Both images build for `linux/amd64` and natively.
- **App image**, run against the local compose Postgres with the production settings:
  - liveness, readiness and `/actuator/prometheus` answer on 9090;
  - actuator returns 401 on the public port 8080;
  - with proxy headers, the protected resource metadata is `https://metaos.ogrodje.si/mcp` with authorization server
    `https://metaos-auth.ogrodje.si/realms/metaos`, and anonymous `/mcp` gets a 401 pointing at it.
- **Keycloak image**, run against a throwaway Postgres:
  - realm `metaos` is imported;
  - the issuer comes from `KC_HOSTNAME`;
  - the `mcp:tools` audience is the production MCP URL;
  - the `dev` user accepts the secret password and rejects `dev`.
- **The SQL from `set_secrets.sh`** ran twice cleanly against the throwaway Postgres. Both roles can log in, and the
  `metaos` role can create the `citext` extension that `V1__init.sql` needs.

### Not yet verified

- Nothing has been applied to the cluster. One read-only look showed `metaos-prod` exists but is empty (no `regcred`
  yet) and that `main-cluster-1` is the CNPG primary. The ingress controller's configuration wasn't checked.
- The workflow hasn't run: there is no git remote yet.

## Decisions to confirm

- **Hostnames.** `metaos.ogrodje.si` and `metaos-auth.ogrodje.si` are placeholders. They're set in
  `k8s/base/kustomization.yaml`, the two Ingresses and the smoke test in `.github/workflows/deploy.yml`.
- **Own Keycloak.** MetaOS gets its own Keycloak, as in `compose.yml`. The alternative is a `metaos` realm on goo's
  existing Keycloak at `keycloak.ogrodje.si`.
- **Login from claude.ai and ChatGPT.** The realm lets clients register themselves with redirect URIs on `localhost`,
  `claude.ai`, `claude.com` and `chatgpt.com` (its Trusted Hosts policy; `keycloak/trusted_hosts.sh` for production's
  realm, imported before). Anyone can register such a client, but its tokens only work for an approved user who
  signed in and agreed to the consent screen.

## Differences from the older projects

- **No registry cleanup job.** Tags are commit hashes, which don't sort by age, so boo-run's keep-the-newest-three
  job could delete the image that's currently deployed.
- **Deploy annotations use own keys** (`ogrodje.si/git-sha`, `ogrodje.si/deployed-by`, `ogrodje.si/deployed-at`).
  boo-run overwrites `deployment.kubernetes.io/revision`, which belongs to the Deployment controller.
- **CI permissions are namespaced.** bongbong's deployer role is cluster-wide; this one is a Role in `metaos-prod`
  plus get/patch on that one Namespace object. CI has no access to secrets: `set_secrets.sh` is run by hand.

## Known trade-offs

- **The realm is imported once.** Later edits to `keycloak/metaos-realm.json` don't reach production; make them in the
  admin console too. The same goes for `METAOS_MCP_URL`: changing it rolls the app, but Keycloak's `mcp:tools`
  audience mapper keeps the old value until it's edited there.
- **Every deploy rolls Keycloak**, since both images get the same tag. It's a rolling update, so the old pod serves
  until the new one is ready.
- **Realms imported from the older realm file lack built-in client scopes.** An import that lists client scopes leaves
  Keycloak's own clients (account console, admin console, ...) without `roles`, `profile`, `email`, ..., so their
  tokens carry no roles and the account console fails with "Something went wrong" (a 403 from the account API).
  `metaos-realm.json` now lists only `mcp:tools` and sets the realm attribute `CreateDefaultClientScopes`, which makes
  the import create Keycloak's standard scopes and assign them as in a fresh realm. A realm imported from the file
  before that change - production, and local databases from before - needs
  [`keycloak/fix_builtin_clients.sh`](keycloak/fix_builtin_clients.sh) once; production has had it.
- **Sessions last 7 days idle, 30 days at most.** Keycloak's defaults, 30 minutes and 10 hours, signed people out of
  the front-end and MCP clients several times a day, as refresh tokens last only as long as the session.
  `metaos-realm.json` has the longer ones; a realm imported before needs
  [`keycloak/sessions.sh`](keycloak/sessions.sh) once; production has had it (2026-10-20). Keycloak's sign-in cookie still ends with the browser, as it
  only outlives it with "Remember me", which logins through Discord and Google skip.

## Discord login

[`keycloak/discord_idp.sh`](keycloak/discord_idp.sh) adds "Sign in with Discord" to realm `metaos` through the admin
API. It's not in the realm JSON, because the realm is imported only once and the client secret doesn't belong in git.

- **Generic OAuth v2 provider.** Keycloak has no Discord provider; the generic one reads `id`, `username`,
  `global_name` and `email` from `https://discord.com/api/users/@me`. The community keycloak-discord extension could
  restrict logins to a Discord server, but it targets Keycloak 26.0 and server membership isn't a fine enough gate.
- **Approval gate.** Every approved user may use every MCP tool, so the post-login flow `discord-post-login` denies
  Discord logins until the user has the realm role `metaos-user`. A first login creates the user and is denied; an admin
  approves them by granting the role. The flow doesn't cover password users that an admin creates, but MetaOS itself
  does: it takes no token without the role (see "Only approved users" below).
- **Emails aren't trusted.** Discord also returns unverified emails, and a verified email links a login's account to a
  Person (`Accounts.kt`). When approving someone, also turn on *Email verified* once you know the address is theirs, or
  link their account to their person by hand (`link_account`, or the People page).
- Verified on a throwaway Keycloak 26.8.1, with a second realm standing in for Discord: denied without the role,
  allowed with it, denied again once it's removed; password logins unaffected; the Discord button redirects to
  `discord.com/oauth2/authorize` with scope `identify email`.

## Google login

[`keycloak/google_idp.sh`](keycloak/google_idp.sh) adds "Sign in with Google" the same way, with Keycloak's built-in
Google provider. The role, the post-login flows and the admin API helpers are shared with the Discord script, in
[`keycloak/approval_gate.sh`](keycloak/approval_gate.sh).

- **OAuth client.** "Web application" client "Keycloak" in Google Cloud project `ogrodje-metaos`, with redirect URIs
  `https://metaos-auth.ogrodje.si/realms/metaos/broker/google/endpoint` and
  `http://localhost:8180/realms/metaos/broker/google/endpoint`. Its ID and secret are `GOOGLE_IDP_CLIENT_ID` and
  `GOOGLE_IDP_CLIENT_SECRET` in `.envrc`. The project's "Desktop app" client "MetaOS" is a different one: it is for
  the Drive bot (`google/refresh_token.sh`).
- **Approval gate.** Same as Discord's, with its own flow `google-post-login`: denied until the user has `metaos-user`.
- **Emails are trusted.** Google only hands out addresses it has verified, so approving a Google user is just granting
  the role. Keycloak 26 still asks to verify an existing account with the same email before linking it.
- **Publishing.** While the Google app is in *Testing*, only its test users can sign in with Google.
- Verified on a throwaway Keycloak 26.8.1: both scripts create the same flow structure and are idempotent, and the
  Google button redirects to `accounts.google.com/o/oauth2/v2/auth` with scope `openid profile email`.

## Front-end sign-in

## Only approved users

The approval gates only stand in front of logins, and Keycloak can issue a token without one: anonymous DCR lets a
client register for its own service account (`client_credentials`), whose token got `mcp:tools` with no user and no
approval. So both sides now require the realm role `metaos-user`:

- **Keycloak** puts `mcp:tools`, `metaos:read` and `metaos:write` into a token only when its user has the role, through
  the scopes' role scope mappings. They're in the realm JSON for new realms;
  [`keycloak/approved_scopes.sh`](keycloak/approved_scopes.sh) adds them to an existing one.
- **MetaOS** requires the role next to the scope on `/mcp`, `/graphql`'s team data and the thumbnails (`Security.kt`).

The mappings take effect at once, so the order matters. As the IdP scripts, with `KEYCLOAK_ADMIN_USERNAME` and
`KEYCLOAK_ADMIN_PASSWORD` from `.envrc`:

```bash
keycloak/approved_scopes.sh           # 1. report: enabled users without the role, service accounts with it
                                      # 2. grant metaos-user to those who should keep access
keycloak/approved_scopes.sh --apply   # 3. map the scopes; refuses while users without the role remain (--force: they go)
                                      # 4. deploy the MetaOS that checks the role
```

Taking the role away now ends access within an access token's lifespan, at the next refresh; end the user's sessions
in Keycloak to make it immediate.

Verified on a throwaway Keycloak 26.8.1 with production's realm JSON: before the script a DCR `client_credentials`
client got a token with `mcp:tools` and the MCP audience; after it, the same request gets neither, while an approved
user's token from an MCP-style client (public, PKCE, full scope disabled) still has both and the role. Re-running the
script changes nothing; it refuses `--apply` while unapproved users remain, and fails on a missing scope.

The front-end signs users in with keycloak-js as client `metaos-ui`, and its "Sign in with Discord/Google" buttons go
straight to the identity provider (`idpHint`), so the approval gates above apply to it as they do to MCP clients.
`metaos-ui` and its scopes `metaos:read` and `metaos:write`, which put `/graphql` into the tokens' audience, are in the
realm JSON for new realms; [`keycloak/frontend_client.sh`](keycloak/frontend_client.sh) adds them to an existing one:

```bash
keycloak/frontend_client.sh   # as the IdP scripts: KEYCLOAK_ADMIN_USERNAME and KEYCLOAK_ADMIN_PASSWORD from .envrc
```

Production has it (2026-10-19).

- **Public client with PKCE.** A browser can't keep a secret. Redirect URIs and web origins are `METAOS_UI_URL` only.
- **Not realm defaults.** The scopes are assigned to `metaos-ui` alone: clients that register themselves (DCR) may
  ask for any realm default scope. Its tokens don't carry `mcp:tools` either, so they don't get into `/mcp`.
- Verified on a throwaway Keycloak 26.8.1 (the realm import, and the script creating and updating) and in dev: a real
  `metaos-ui` token has `/graphql` in `aud`, a login without PKCE is refused, `check-sso` finds an existing Keycloak
  session, signing out comes back to the front-end, and the Google button goes straight to Google.

## Server-rendered front-end

Added on 2026-10-23, on branch `ssr`; not deployed. Until then the app served the front-end, a Vite build in its jar,
as one `index.html` for every page, which the browser filled in from the API. Search engines that run JavaScript
could read it eventually; link previews (Discord, Slack, X, ...) and most other crawlers saw an empty page called
"Ogrodje MetaOS". Now the front-end is Nuxt, a service of its own, which renders the pages anyone may see on the
server and hydrates them into the same single-page app (CLAUDE.md, Front-end).

- **Two services on one host.** `metaos-web` (`deployment-web.yaml`, two pods) serves everything on
  `metaos.ogrodje.si` but MetaOS's own paths, which `metaos-ingress` keeps for the app: `/graphql`, `/events`, `/mcp`,
  `/.well-known`, `/koofr`, `/dropbox`, `/livez` and `/readyz` (ingress-nginx takes the longest matching path). The
  browser still sees one origin, so there's no CORS, and Keycloak's silent check works as before. The app serves no
  pages and has no `/config.json` any more: the front-end's runtime config comes from `public-urls`.
- **Rendered for everyone alike.** The server asks the app's GraphQL (`http://metaos`, inside the cluster) without a
  token, so a page is the same for every visitor, and caching it can't leak what's the team's. The team's part loads
  in the browser, after sign-in, with the token, as before. The API's `episode` and `person` queries answer anyone for
  a released episode and the people on it.
- **Caching.** Pages anyone may see say `Cache-Control: s-maxage=60, stale-while-revalidate=600`; Nitro keeps them in
  each pod's memory, and Cloudflare too once a Cache Rule allows HTML (`k8s/README.md`, one-time setup). A page that's
  over 30 s old loads its data again once it's shown. The team's pages (`/ideas`, `/sessions`, `/search`) say
  `no-store` and `noindex`, and render in the browser only.
- **Rollout.** One deploy brings both: the app stops serving pages as the front-end starts. For the seconds between
  the Ingress changing and the first `metaos-web` pod being ready, pages answer 503; later deploys roll as usual.
- **Verified locally** (the app on :8081, the front-end's dev server and its build): the test suites pass, also the new
  tests that anyone gets a released episode's and person's page and nothing else (`GraphQLTest`), and that the app
  serves no pages (`SecurityTest`); every public page's HTML has its content, title, description, picture and
  canonical address, an episode's and a person's their schema.org data; an unreleased episode and unknown pages are
  404s that the team still sees once signed in; the browser hydrates them without mismatches; the team's pages, client
  navigation, sign-in and live updates work as before; the build caches pages and its assets are immutable.
- **Not yet verified:** the images, the Ingress split and the Cloudflare rule in production, and real crawlers and
  link previews (Google's Rich Results Test, a link posted in Discord).

## Next steps

1. [ ] **DNS.** Add `metaos.ogrodje.si` and `metaos-auth.ogrodje.si` as proxied Cloudflare CNAMEs to
   `ogrodje-one.boo.run`, like `goo.ogrodje.si`.
2. [ ] **Secrets.** Add `PROD_METAOS_DB_PASSWORD`, `PROD_KEYCLOAK_DB_PASSWORD`, `PROD_KEYCLOAK_ADMIN_PASSWORD`,
   `PROD_KEYCLOAK_DEV_PASSWORD` (`openssl rand -hex 24` each), `DOCKER_REGISTRY_USERNAME` and
   `DOCKER_REGISTRY_PASSWORD` to `.envrc`, then run `./k8s/set_secrets.sh`.
3. [ ] **First deploy.** `just push deploy`.
4. [ ] **Keycloak.** At `https://metaos-auth.ogrodje.si/admin`, sign in as `admin`, create a permanent admin in the
   master realm, then delete the temporary bootstrap admin and the `dev` user. Add real users with verified emails;
   that's how a login's account is linked to a person by itself (otherwise, `link_account`).
5. [ ] **CI**, once the repo is on GitHub:
    - add the repo secrets `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`, `DOCKER_REGISTRY_USERNAME`,
      `DOCKER_REGISTRY_PASSWORD` and `KUBECONFIG` (base64 of the credential-less Tailscale operator kubeconfig, not
      `ogrodje-one-config`);
    - run `kubectl apply -f k8s/ci/deployer.yaml`;
    - in the Tailscale access policy, add `metaos-deployers` to the groups `tag:ci` impersonates on
      `tag:k8s-operator`, next to `bongbong-deployers`.
6. [ ] **Check it.** Run the commands in [`k8s/README.md`](k8s/README.md#checking-it), then
   `claude mcp add --transport http metaos https://metaos.ogrodje.si/mcp`.
