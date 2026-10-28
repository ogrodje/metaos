# Deploying MetaOS

The app, its front-end, its Keycloak and its Temporal in namespace `metaos-prod` on the ogrodje-one cluster, the way goo, boo-run and bongbong
deploy there: images in the tailnet registry, `kustomize` picks the tags, Postgres is the shared CNPG `main-cluster`,
ingress-nginx routes and Cloudflare terminates TLS.

```
k8s/base/
  namespace.yaml             metaos-prod
  deployment-metaos.yaml     ConfigMap, Deployment, Service, Ingress (MetaOS's own paths), ServiceMonitor
  deployment-web.yaml        the front-end (metaos-web): Deployment, Service, Ingress (everything else)
  deployment-keycloak.yaml   Deployment, Service, Ingress, ServiceMonitor (its metrics, sign-ins among them)
  deployment-patreon-gateway.yaml  patreon-gateway: its volume, Deployment, Service (no Ingress: MetaOS's only)
  deployment-temporal.yaml   Temporal: its server (schema set up before it starts), Service, ServiceMonitor,
                             NetworkPolicy; the Job that makes its namespace; its UI, on the tailnet only
  kustomization.yaml         the resources, the public URLs and the image tags
k8s/set_secrets.sh           Postgres roles + databases, secrets, registry pull secret
k8s/prometheus/              what the cluster's Prometheus passes on to New Relic (newrelic.sh applies it)
k8s/ci/deployer.yaml         what CI may do in the cluster
docker/Dockerfile            the app: boot jar on distroless java25, non-root
docker/web.Dockerfile        the front-end: Nuxt's build (fe/.output) on distroless Node 26, non-root
docker/keycloak.Dockerfile   Keycloak, optimized build, realm "metaos" baked in
docker/patreon-gateway.Dockerfile  patreon-gateway's boot jar on distroless java25, as the app's; its Chromium is Microsoft's image, beside it
```

| Host                     | What                                                                       |
|--------------------------|----------------------------------------------------------------------------|
| `metaos.ogrodje.si`      | the front-end's pages; the app's `/graphql`, `/events`, `/mcp`, ...        |
| `metaos-auth.ogrodje.si` | Keycloak; realm `metaos` issues the tokens, `/admin` is the admin console |
| `metaos-temporal.folk-decibel.ts.net` | Temporal's UI, on the tailnet only (nothing signs in to it): workflows and their histories |

Actuator (`/actuator/health/*`, `/actuator/prometheus`) is on port 9090, which no Ingress routes - Prometheus reaches
it, the internet doesn't. The app's probes use `/livez` and `/readyz` on port 8080 instead, so they check the port that
serves traffic; those two are public, but only answer UP or DOWN.

## Day to day

Pushing to `master` runs `.github/workflows/deploy.yml`: tests, builds and pushes the images, each in a job of its own,
at once (the app's tests a job of their own too), applies `k8s/base` and smoke-tests the public URLs. One run at a time: a push while one runs waits for it, and
only the newest of those waiting runs. Started by hand on another branch (Actions → Build and Deploy → Run workflow), it
builds and pushes that branch's images, tagged but not as `latest`, and deploys nothing.

The images are reproducible (`SOURCE_DATE_EPOCH=0`, `rewrite-timestamp`), so a push sends only the layers that changed,
usually the application's, under a megabyte, and the deploy sets them by digest (`metaos@sha256:...`, not the tag): a
Deployment whose image didn't change isn't rolled. `nuxt build` isn't reproducible, so the front-end's image is also
tagged `src-<key>`, by git's hashes of what it's made from and its base image's digest; a run that finds that tag
tags the image again rather than build it. They have no provenance attestation, so each is one plain manifest
rather than an index, which `registry garbage-collect --delete-untagged` would sweep the layers of; images pushed before
are still indexes. A cleanup of the registry keeps what the cluster runs by its digests.

By hand (needs Tailscale up and `KUBECONFIG` pointing at `ogrodje-one-config`):

```bash
just push        # boot jar, front-end, the images (linux/amd64), push, point kustomization.yaml at the tag
just deploy      # kubectl apply -k, wait for the rollouts
just manifests   # what would be applied
just logs        # follow the app
just web-logs    # follow the front-end
just gateway-logs  # follow patreon-gateway
```

## One-time setup

1. **DNS.** `metaos.ogrodje.si` and `metaos-auth.ogrodje.si` as proxied CNAMEs to `ogrodje-one.ogrodje.si`
   ([infra/cloudflare](../infra/cloudflare/dns.tf)). Proxied, because Cloudflare holds the certificate - the cluster
   has no cert-manager.

2. **Database, secrets, pull secret.** Put the passwords into `.envrc` (`openssl rand -hex 24` for each) and run the
   script; it's idempotent, and re-running it with new passwords rotates them:

   ```bash
   export PROD_METAOS_DB_PASSWORD=...
   export PROD_KEYCLOAK_DB_PASSWORD=...
   export PROD_KEYCLOAK_ADMIN_PASSWORD=...
   export PROD_TEMPORAL_DB_PASSWORD=...
   export DOCKER_REGISTRY_USERNAME=... DOCKER_REGISTRY_PASSWORD=...
   ./k8s/set_secrets.sh
   ```

   It creates roles `metaos` and `metaos_keycloak`, each owning a database of the same name on `main-cluster`, and
   `metaos_temporal`, owning `metaos_temporal` and `metaos_temporal_visibility`, whose schemas Temporal sets up itself (its pod's `schema`
   init container, before every start: made the first time, updated after an upgrade). With
   the Patreon app's credentials and `PROD_PATREON_GATEWAY_TOKEN` it also makes `patreon-gateway-secret`; then sign
   the bot in to Patreon with `patreon-gateway/login.sh` ([its README](../patreon-gateway/README.md)).

3. **Cloudflare caches the pages** anyone may see, as their `Cache-Control` says (`s-maxage=60,
   stale-while-revalidate=600`), but only with a Cache Rule, as it doesn't cache HTML by default: for host
   `metaos.ogrodje.si`, *Eligible for cache*, Edge TTL *Use cache-control header if present, bypass cache if not*,
   Browser TTL *Respect origin*. The team's pages say `no-store`, and the API's answers aren't cacheable, so they pass
   through. It's `metaos: pages`, Terraform's ([infra/cloudflare](../infra/cloudflare/caching.tf)), which also
   leaves MetaOS's own paths (`/graphql`, `/events`, `/mcp`, ...) uncached, whatever they say.

4. **First deploy:** `just push deploy`. Keycloak starts with the master realm only.

5. **Keycloak, once it's up** (`https://metaos-auth.ogrodje.si/admin`, `admin` / `PROD_KEYCLOAK_ADMIN_PASSWORD`):
    - The bootstrap admin is temporary - create a permanent admin in the master realm and delete it. That admin is
      `KEYCLOAK_ADMIN_USERNAME` and `KEYCLOAK_ADMIN_PASSWORD`, in `.envrc` and GitHub's environment `production`.
    - Realm `metaos`: `just keycloak adopt prod`, then `just keycloak apply prod`, with the sign-in providers' secrets
      ([infra/keycloak](../infra/keycloak/README.md)).
    - Add real users with **verified** emails; that's how a login is linked to a person (`Security.kt`).

6. **CI** (once the repo is on GitHub):
    - Repo secrets `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`, `DOCKER_REGISTRY_USERNAME`, `DOCKER_REGISTRY_PASSWORD`,
      `KUBECONFIG` - the same ones boo-run and bongbong have. `KUBECONFIG` is base64 of the credential-less Tailscale
      operator kubeconfig (`tailscale configure kubeconfig tailscale-operator`), not `ogrodje-one-config`.
    - `kubectl apply -f k8s/ci/deployer.yaml` - a Role in `metaos-prod` for group `metaos-deployers`.
    - In the tailnet policy, add `metaos-deployers` to the groups `tag:ci` impersonates on `tag:k8s-operator`
      (next to `bongbong-deployers`): `infra/tailscale/policy.hujson`, which is private, applied with
      `just infra tailscale apply` ([infra/tailscale](../infra/tailscale/README.md)).

7. **New Relic** (CLAUDE.md, "New Relic"): `PROD_NEW_RELIC_LICENSE_KEY` in `.envrc`, then `./k8s/set_secrets.sh` for
   the agents, and, where kubectl may change namespace `observability` (`ssh ogrodje-one-x`), `./k8s/prometheus/newrelic.sh
   --dry-run=server` and again without it: the cluster's Prometheus (kube-prometheus-stack, release `prom`, the
   cluster's apps share it) then passes MetaOS's own metrics, Keycloak's sign-ins, `metaos-prod`'s pods' and the
   node's on to New Relic, and nothing else (`newrelic-values.yaml` says which). Run it again after changing them.

8. **Temporal** (CLAUDE.md, "Workflows"): `PROD_TEMPORAL_DB_PASSWORD` in `.envrc`, then `./k8s/set_secrets.sh` for its
   role, databases and `temporal-secret`, before the deploy that brings `deployment-temporal.yaml`. The deploy starts
   its server, the Job `temporal-namespace` makes namespace `default` (two weeks' retention) once the server answers,
   and MetaOS's pods connect as its workers (`TEMPORAL_ADDRESS`). What MetaOS's workflows use needs its keys too:
   `SONIOX_API_KEY` (Soniox), and the Speech-to-Text API on in project `ogrodje-metaos` (infra/gcp).

## Checking it

```bash
curl -s https://metaos.ogrodje.si/.well-known/oauth-protected-resource/mcp   # resource + authorization server
curl -s https://metaos-auth.ogrodje.si/realms/metaos/.well-known/openid-configuration
curl -sI https://metaos.ogrodje.si/episodes | grep -i 'cf-cache-status\|cache-control'   # HIT on the second try
curl -s https://metaos.ogrodje.si/episodes | grep -c 'href="/episodes/S'               # rendered on the server
kubectl -n metaos-prod get pods,svc,ingress
kubectl -n metaos-prod port-forward deploy/metaos 9090 &
curl -s localhost:9090/actuator/health
kubectl -n metaos-prod logs deploy/temporal -c schema               # its schema, set up or as it was
kubectl -n metaos-prod get job temporal-namespace                    # namespace default made
kubectl -n metaos-prod run temporal-cli --rm -it --restart=Never --image=temporalio/admin-tools:1.32.1 \
  --env TEMPORAL_ADDRESS=temporal:7233 -- temporal task-queue describe --task-queue metaos   # MetaOS's pods poll it
```

Then point an MCP client at `https://metaos.ogrodje.si/mcp`, e.g.
`claude mcp add --transport http metaos https://metaos.ogrodje.si/mcp`.

## Notes

- **Realm `metaos` is Terraform's** ([infra/keycloak](../infra/keycloak/README.md)): a change to it is a change there,
  planned and applied, never made in the admin console. That includes `METAOS_MCP_URL` and `METAOS_API_URL`: changing
  them in `kustomization.yaml` rolls the app, but the scopes' audience mappers keep the old URLs until
  `infra/keycloak/prod` has the new ones and is applied.
- **Dynamic Client Registration allows redirect URIs on a few hosts only** (the realm's Trusted Hosts policy):
  `localhost` for Claude Code and MCP Inspector, `claude.ai` and `claude.com` for Claude's connectors, `chatgpt.com`
  for ChatGPT's. Another hosted client needs its host in `trusted_hosts` (infra/keycloak/realm/variables.tf).
- Every deploy rolls Keycloak and the front-end too, since the images get the same tag. It's a rolling update, so the old pod serves
  until the new one is ready, and for 10 s after it's told to stop, until ingress-nginx has dropped it.
- **Temporal's Job** (`temporal-namespace`) is deleted an hour after it's done, and the next deploy makes it again,
  which changes nothing then. A deploy within that hour that changes the Job fails on it, as a Job can't be changed:
  delete it first (`kubectl -n metaos-prod delete job temporal-namespace`).
- **Upgrading Temporal**: the server and admin-tools at the same version, one minor version at a time (1.32 to 1.34
  goes through 1.33), after reading its release notes; the `schema` init container updates its databases first.
- The registry isn't cleaned up: tags are commit hashes, which don't sort by age, so boo-run's keep-the-newest-three
  job can't be reused as is.
