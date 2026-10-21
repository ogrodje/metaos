# Deploying MetaOS

The app and its Keycloak in namespace `metaos-prod` on the ogrodje-one cluster, the way goo, boo-run and bongbong
deploy there: images in the tailnet registry, `kustomize` picks the tags, Postgres is the shared CNPG `main-cluster`,
ingress-nginx routes and Cloudflare terminates TLS.

```
k8s/base/
  namespace.yaml             metaos-prod
  deployment-metaos.yaml     ConfigMap, Deployment, Service, Ingress, ServiceMonitor
  deployment-keycloak.yaml   Deployment, Service, Ingress
  kustomization.yaml         the resources, the public URLs and the image tags
k8s/set_secrets.sh           Postgres roles + databases, secrets, registry pull secret
k8s/ci/deployer.yaml         what CI may do in the cluster
docker/Dockerfile            the app: boot jar on distroless java25, non-root
docker/keycloak.Dockerfile   Keycloak, optimized build, realm "metaos" baked in
```

| Host                     | What                                                                       |
|--------------------------|----------------------------------------------------------------------------|
| `metaos.ogrodje.si`      | the app; `/mcp` is the MCP endpoint                                        |
| `metaos-auth.ogrodje.si` | Keycloak; realm `metaos` issues the tokens, `/admin` is the admin console |

Actuator (`/actuator/health/*`, `/actuator/prometheus`) is on port 9090, which no Ingress routes - Prometheus reaches
it, the internet doesn't. The app's probes use `/livez` and `/readyz` on port 8080 instead, so they check the port that
serves traffic; those two are public, but only answer UP or DOWN.

## Day to day

Pushing to `master` runs `.github/workflows/deploy.yml`: tests, builds and pushes both images, applies `k8s/base` and
smoke-tests the public URLs. By hand (needs Tailscale up and `KUBECONFIG` pointing at `ogrodje-one-config`):

```bash
just push        # boot jar, both images (linux/amd64), push, point kustomization.yaml at the tag
just deploy      # kubectl apply -k, wait for both rollouts
just manifests   # what would be applied
just logs        # follow the app
```

## One-time setup

1. **DNS.** `metaos.ogrodje.si` and `metaos-auth.ogrodje.si` as proxied CNAMEs to `ogrodje-one.boo.run`, like
   `goo.ogrodje.si`. Proxied, because Cloudflare holds the certificate - the cluster has no cert-manager.

2. **Database, secrets, pull secret.** Put the passwords into `.envrc` (`openssl rand -hex 24` for each) and run the
   script; it's idempotent, and re-running it with new passwords rotates them:

   ```bash
   export PROD_METAOS_DB_PASSWORD=...
   export PROD_KEYCLOAK_DB_PASSWORD=...
   export PROD_KEYCLOAK_ADMIN_PASSWORD=...
   export PROD_KEYCLOAK_DEV_PASSWORD=...
   export DOCKER_REGISTRY_USERNAME=... DOCKER_REGISTRY_PASSWORD=...
   ./k8s/set_secrets.sh
   ```

   It creates roles `metaos` and `metaos_keycloak`, each owning a database of the same name on `main-cluster`.

3. **First deploy:** `just push deploy`. Keycloak imports realm `metaos` from the image on its first start, with
   `METAOS_MCP_URL` as the token audience and `PROD_KEYCLOAK_DEV_PASSWORD` as the `dev` user's password.

4. **Keycloak, once it's up** (`https://metaos-auth.ogrodje.si/admin`, `admin` / `PROD_KEYCLOAK_ADMIN_PASSWORD`):
    - The bootstrap admin is temporary - create a permanent admin in the master realm and delete it.
    - Delete the `dev` user from realm `metaos`, or keep it as a smoke-test account.
    - Add real users with **verified** emails; that's how a login is linked to a person (`Security.kt`).

5. **CI** (once the repo is on GitHub):
    - Repo secrets `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`, `DOCKER_REGISTRY_USERNAME`, `DOCKER_REGISTRY_PASSWORD`,
      `KUBECONFIG` - the same ones boo-run and bongbong have. `KUBECONFIG` is base64 of the credential-less Tailscale
      operator kubeconfig (`tailscale configure kubeconfig tailscale-operator`), not `ogrodje-one-config`.
    - `kubectl apply -f k8s/ci/deployer.yaml` - a Role in `metaos-prod` for group `metaos-deployers`.
    - In the tailnet policy, add `metaos-deployers` to the groups `tag:ci` impersonates on `tag:k8s-operator`
      (next to `bongbong-deployers`).

## Checking it

```bash
curl -s https://metaos.ogrodje.si/.well-known/oauth-protected-resource/mcp   # resource + authorization server
curl -s https://metaos-auth.ogrodje.si/realms/metaos/.well-known/openid-configuration
kubectl -n metaos-prod get pods,svc,ingress
kubectl -n metaos-prod port-forward deploy/metaos 9090 &
curl -s localhost:9090/actuator/health
```

Then point an MCP client at `https://metaos.ogrodje.si/mcp`, e.g.
`claude mcp add --transport http metaos https://metaos.ogrodje.si/mcp`.

## Notes

- **The realm is imported once.** Later edits to `keycloak/metaos-realm.json` don't reach production; make them in the
  admin console too. Same for `METAOS_MCP_URL`: changing it in `kustomization.yaml` rolls the app, but Keycloak's
  `mcp:tools` audience mapper keeps the old value until it's edited there.
- **Dynamic Client Registration allows redirect URIs on a few hosts only** (the realm's Trusted Hosts policy):
  `localhost` for Claude Code and MCP Inspector, `claude.ai` and `claude.com` for Claude's connectors, `chatgpt.com`
  for ChatGPT's. `keycloak/trusted_hosts.sh` adds them to a realm imported before; another hosted client needs its
  host added there.
- Every deploy rolls Keycloak too, since both images get the same tag. It's a rolling update, so the old pod serves
  until the new one is ready, and for 10 s after it's told to stop, until ingress-nginx has dropped it.
- The registry isn't cleaned up: tags are commit hashes, which don't sort by age, so boo-run's keep-the-newest-three
  job can't be reused as is.
