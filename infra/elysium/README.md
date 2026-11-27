# Elysium, the Vultr cluster

Elysium is the Kubernetes cluster (Vultr's VKE, Amsterdam) that MetaOS moves to from ogrodje-one, and where the
software factory runs: OpenHands' agents working on Ogrodje's code, with Claude and OpenAI models through their APIs.
It's €120 a month, split 40/60 between the platform and the factory.

| Stack                   | What                                                                              | State             | Applied |
|-------------------------|-----------------------------------------------------------------------------------|-------------------|---------|
| [`cluster/`](cluster)   | VPC, the VKE cluster and its node pools (platform: `vhp-4c-8gb-amd`, factory: `vc2-6c-16gb`, tainted), the registry `ams.vultrcr.com/ogrodje` | `elysium/cluster` | `just infra elysium/cluster <command>`, by hand only |
| [`factory/`](factory)   | Namespace `factory`: Agent Canvas, LiteLLM, the NetworkPolicies between them      | `elysium/factory` | `just infra elysium/factory <command>`, by hand |
| `platform/` (not yet)   | What MetaOS and the other apps share: ingress-nginx, Cloudflare's tunnel, CloudNativePG and `main-cluster`, the Tailscale operator, Prometheus, Sysbox | `elysium/platform` | by hand |

None of these are CI's. Their states hold what opens the cluster to an admin, so CI's service account can't read
anything under `elysium/` in the states' bucket (`../gcp/ci.tf`). They sign in with `VULTR_API_KEY` (secretspec.toml),
and `factory` reaches the cluster with the admin kubeconfig Vultr gives for it.

## How the stacks find each other

By names, not by reading each other's state: `factory` finds the cluster by its label, `elysium`. The names other
stacks may rely on:

| Name                                        | What |
|---------------------------------------------|------|
| `elysium`                                   | The VKE cluster's label |
| `ogrodje.si/pool`: `platform`, `factory`    | The nodes' label; `factory` is also a `NoSchedule` taint with that key |
| `ams.vultrcr.com/ogrodje`                   | The registry |
| `litellm.factory.svc.cluster.local:4000`    | LiteLLM, for what's in namespace `factory` only (NetworkPolicy `litellm`) |
| `agent.ogrodje.dev`, `agent-<name>.ogrodje.dev` | The team's Canvas and each person's, behind Access (Cloudflare account of ogrodje.dev, tunnel `elysium-factory`) |
| `hooks.ogrodje.dev/linear/webhook`, `/github/webhook` | The bridge, for the Linear app Elysium's webhook and ogrodje's GitHub org webhook |

## The first time

1. CI's way into the states stops at `elysium/`: `just infra gcp apply`, before any of Elysium's states exist.
2. A Vultr API key (Account → API), its access control allowing your address, in 1Password:
   `secretspec set VULTR_API_KEY`.
3. `just infra elysium/cluster apply`. The cluster takes over 20 minutes, longer than the provider waits for it (20
   minutes, which can't be changed): the apply then fails with "context deadline exceeded" and marks the cluster
   tainted. Don't apply again while it is, as that replaces the cluster: wait for Vultr's console to show it running,
   then `terraform untaint vultr_kubernetes.elysium` in `cluster/` and apply again, which makes the factory's pool.
   Then `just elysium-kubeconfig`.
4. A key of an Anthropic workspace of the factory's own, with a spend limit, and optionally an OpenAI project's, with
   a budget: `secretspec set PROD_ANTHROPIC_API_KEY` (and `PROD_OPENAI_API_KEY`). Ours: workspace "Elysium factory".
5. In the Cloudflare account that has ogrodje.dev, with Zero Trust set up (team `ogrodje-dev`): an account token,
   `ELYSIUM_CLOUDFLARE_API_TOKEN`, with Cloudflare One Connector: cloudflared Edit, Access: Apps Edit, Access:
   Policies Edit, Access: Organizations, Identity Providers, and Groups Read, and, on ogrodje.dev, DNS Edit and Zone
   Read. And who may use the factory: `secretspec set FACTORY_USERS`
   (`{"<name>": {"email": "<email>", "github": "<login>"}, ...}`). The org webhook (`factory/github.tf`) is applied
   as you, with gh's token, which needs `admin:org_hook` (`gh auth refresh -h github.com -s admin:org_hook`).
6. Elysium in GitHub and in Linear (below): `ELYSIUM_GITHUB_TOKEN`, `ELYSIUM_LINEAR_CLIENT_SECRET`, and the Linear
   app's webhook (`https://hooks.ogrodje.dev/linear/webhook`, agent session events) with its signing secret,
   `ELYSIUM_LINEAR_WEBHOOK_SECRET`.
7. `just infra elysium/factory apply`, `infra/elysium/factory/set_secrets.sh`, then `just canvas-settings`, which gives
   every Canvas its LLM (Claude Sonnet 5.5, through LiteLLM, streamed), its agent profile (up to 4 tool calls of a step
   at once, e.g. terminal commands, each in a tmux pane of its own), GitHub and Linear. Settings changed in Canvas'
   own UI since, it puts back.

## Images: Vultr's registry

Elysium's images are in **`ams.vultrcr.com/ogrodje`**, Vultr's registry beside the cluster (`cluster/registry.tf`,
private, the free plan's 10 GB; 20 GB is $5 a month). **CI pushes them**, as it does to the tailnet's registry:

- **MetaOS'** (`metaos`, `metaos-web`, `keycloak`, `patreon-gateway`, `scrapers`): `deploy.yml` pushes each to both
  registries with the same tags. Production still pulls from the tailnet's until MetaOS moves here.
- **Elysium's Canvas** (`elysium-canvas`, `elysium/canvas/Dockerfile`: Canvas with MetaOS' development tools):
  `elysium.yml`, when its Dockerfile changes on master or an `elysium/` branch, tagged `<Canvas' version>-<the
  Dockerfile's hash>`; `just canvas-image` says the tag, which goes into the factory's `canvas_image`.

CI pushes with a login that doesn't run out, `VULTR_REGISTRY_USERNAME`/`VULTR_REGISTRY_PASSWORD` (1Password,
secretspec's scope `registry`); the factory's pods pull with a read-only one, the secret `vultr-registry`, which
`factory/set_secrets.sh` makes once. For pushing from a laptop, `just registry-login` logs Docker in for an hour.
Each login is a new one Vultr makes from `VULTR_API_KEY`; they last as long as the registry.

## CI: the factory from GitHub Actions, once someone approves

`infra/elysium/factory` is applied by **`.github/workflows/elysium-factory.yml`**: a pull request that changes it (or
the bridge, the previews' manager or the settings' job, which it runs from ConfigMaps) plans it, its merge into master
applies it and gives the Canvases their settings again. `elysium/cluster` stays by hand.

Every job of it runs in GitHub's environment **`elysium`**, whose required reviewers approve it first, the plans too:
a plan runs the change's own Terraform with the factory's credentials, and `elysium-og`, which opens pull requests and
may merge them, mustn't have that run unseen. So: read the change, then approve (Actions, the run, Review deployments).
Only such a job gets the state, as `elysium-terraform-ci` (`infra/gcp/ci.tf`, by the environment's subject, so not a
pull request's own workflow), and secretspec's scope `elysium` from 1Password. The run's summary lists what changes, by
address, never the plan: the repository is public. The environment isn't Terraform's, or the pipeline could take its own
approval away; once, by hand:

```sh
gh api -X PUT repos/ogrodje/metaos/environments/elysium --input - <<<'{"reviewers":[{"type":"User","id":<your id>}],"prevent_self_review":false,"can_admins_bypass":false}'
just infra gcp apply                          # elysium-terraform-ci
secretspec set ELYSIUM_TERRAFORM_GITHUB_TOKEN # an owner's fine-grained token, ogrodje, Webhooks read/write only
```

CI reaches the cluster as the service account `elysium-terraform` (`just elysium-ci-credentials`, `ELYSIUM_KUBE_*`),
not with Vultr's kubeconfig, and Cloudflare with a token of its own (`ELYSIUM_CI_CLOUDFLARE_API_TOKEN`, the same
permissions as `ELYSIUM_CLOUDFLARE_API_TOKEN`): Vultr's API key and the Cloudflare token people use only work from their
owners' addresses, and a runner's address is anyone's. `ELYSIUM_TERRAFORM_GITHUB_TOKEN` is for `github.tf`'s org
webhook only. By hand, `just infra elysium/factory` still
works as before, with gh's token.

The Canvases' settings (`canvas_settings.tf`, `elysium/canvas-settings`) are a **CronJob, weekly**, from what the
cluster has already, so Linear's 30-day token never runs out; `just canvas-settings` runs it at once.

## The factory

Agent Canvas is OpenHands' single-tenant app (MIT): its UI, agent-server and automations in one pod, every agent of it
in that pod and on its one volume, with no users of its own. So there's one for the team, and, when it's worth the
memory, one of each person's own (`personal_canvases`, off for now: `factory/variables.tf`).

### Team and personal Canvases

**For now there's only the team's**, at https://agent.ogrodje.dev, for everyone in `FACTORY_USERS`, Linear's and
GitHub's requests too: one Canvas leaves the factory node to the agents' builds (up to 12 GiB of memory). Its storage:

- **its volume**, Vultr NVMe block storage (`team_volume_size`, 50 GiB): settings, conversations, the agents'
  checkouts (`~/.openhands/agent-canvas/workspaces`). It lasts, and grows in place with a bigger size (never shrinks):
  Terraform has it itself, not the StatefulSet's template, for that;
- **the node's own disk** for the build tools' caches (`~/.gradle`, `~/.m2`, `~/.npm`, up to 100 GiB): free and fast,
  emptied when the pod restarts, after which the next build fills them again.

With `personal_canvases` on (`-var personal_canvases=true`, or its default changed), each person also gets their own,
as follows, and the team's becomes nobody's for Linear and GitHub, whose requests then run in the asker's.

| | The team's, **https://agent.ogrodje.dev** | A person's, **https://agent-&lt;name&gt;.ogrodje.dev** |
|---|---|---|
| Who gets in | everyone in `FACTORY_USERS` | only them |
| Conversations | everyone's to see and continue | their own |
| MCP servers | service identities only: GitHub and Linear as Elysium | the same, and their own accounts, which they connect themselves: e.g. MetaOS' (`https://ogrodje.si/mcp`), signed in as them |
| Automations | the team's | their own |
| Linear | — | the sessions they start (below) |

A Canvas' settings, MCP servers, secrets, conversations and automations are its own, on its own volume
(`factory/canvas/`): a browser adds each Canvas its person may open as a backend, and switches between them. Files
aren't shared between Canvases: the shared workspace is GitHub, where they all clone, branch and open pull requests.

Who gets in is decided twice: **Cloudflare Access** lets in only the Canvas' people (`FACTORY_USERS`, in 1Password,
not this public repository), signing in with a code Cloudflare emails them or their Cloudflare account, for 24 hours
at a time; and only cloudflared, which checks Access' token too, and the Linear bridge reach a Canvas (NetworkPolicy
`canvas`). So Canvas puts its session key, which Terraform makes (`canvas-<name>-session`), into its own page:
nobody types it.

Adding someone: their entry in `FACTORY_USERS`, then `just infra elysium/factory apply` and `just canvas-settings`;
their MCP accounts they connect in their Canvas. Taking someone out: the same without them, which deletes their
Canvas but not its volume (`home-canvas-<name>-0`, kept until deleted by hand). Each Canvas takes about 1.6 GiB idle
of the factory node's 15, and up to 6 (the team's 8) while building.

`just canvas [name]` port-forwards a Canvas, e.g. when Access is down.

### Previews

MetaOS as an agent built it, at **`https://preview-N.ogrodje.dev`**, behind Access for the team (`factory/previews.tf`,
CLAUDE.md's Previews for how agents use them). What nuc8's tailnet previews were, here:

- **`preview/preview.sh up`** builds the boot jar and `fe/.output` where the agent works and uploads them, with
  `preview/seed.sql`, to the **previews' manager** (`elysium/previews/manager.ts`), which puts them into the agent's
  slot, or a free one, and starts its pod: the jar and the front-end on production's images, with a Postgres of its
  own, seeded once MetaOS has made its schema. In a Canvas, its secrets `PREVIEWS_URL` and `PREVIEWS_TOKEN` (set by
  `canvas-settings`) say where the manager is; on a laptop, Elysium's kubeconfig reaches it through a port-forward.
- **Done is cleared**: `preview down`, the branch's pull request merged or closed (the GitHub webhook's
  `pull_request`, which the bridge passes on), or a day nobody updated it. The pod stops; its build and database go.
- **Sign-in** is `dev` / `dev`, through the previews' own Keycloak (stock Keycloak, its data a file; realm `metaos`
  from `infra/keycloak/preview`, `just keycloak adopt|apply preview` through a port-forward). cloudflared passes each
  slot's `/auth/realms/metaos` and `/auth/resources` to it, never its console, so its tokens carry the slot's issuer.
- **Kept apart**: namespace `previews`, restricted, below the agents in priority and in a quota; the slots reach
  Keycloak's keys and Google's Cloud Storage (the dev buckets, `previews-storage` from `set_secrets.sh`), nothing else;
  only cloudflared and the Canvases reach them. The manager may only scale the slots' Deployments and read their pods.
- **Two slots** (`preview_slots`), each up to 2.25 GiB; a third is that and its address in `infra/keycloak/preview` and
  the dev buckets' CORS (`infra/gcp`).

### Elysium in GitHub and Linear

The agents are **Elysium** wherever they act, not whoever started them:

- **GitHub: the account `elysium-og`** (Elysium), a member of `ogrodje` in team `elysium`, which has Write on every
  repository: push, open and merge pull requests, edit workflows. Not Admin. Its fine-grained token
  (`ELYSIUM_GITHUB_TOKEN`: all of ogrodje, contents, pull requests, issues, workflows and actions read/write, a year) is
  Canvas' `GITHUB_TOKEN`, and commits carry `GIT_AUTHOR_*`/`GIT_COMMITTER_*` of Elysium with elysium-og's noreply
  address. A new repository needs adding to the team. Not a GitHub App: Canvas gives a conversation its token for its
  whole run, and an App's last an hour.
- **Linear: the app "Elysium"** (Settings > API, private to workspace ogrodje, client credentials on), installed as an
  Agent: its tokens, client credentials, are asked for with `app:assignable` and `app:mentionable` as well as `read`
  and `write`, and those scopes are its install, so it's its own user, which can be @mentioned and delegated issues, with
  no seat. Its token (client credentials, 30 days) is minted from `ELYSIUM_LINEAR_CLIENT_SECRET`.
- **MetaOS: Keycloak's client `elysium`** (`infra/keycloak/realm/elysium.tf`), a service account that's staff, as
  MetaOS' own agents' `metaos-agents` is: its tokens get `mcp:tools`, and what it changes is in MetaOS' history as
  `elysium` (its tokens' `preferred_username`, which Keycloak would make `service-account-elysium`). Canvas signs in to an MCP server only with a person's OAuth, in their browser, so the bridge
  does it instead: Canvas' `metaos` server is the bridge's `/mcp/metaos`, inside the cluster only (NetworkPolicy lets
  only the Canvases reach it, the tunnel doesn't route it), which passes each request on to `https://ogrodje.si/mcp`
  with a client-credentials token it renews before it runs out. No agent holds a MetaOS credential, and none runs out
  under a long conversation. Its secret is `ELYSIUM_METAOS_CLIENT_SECRET`; without it the route is off.
- **In Canvas**, all three as MCP servers (GitHub's and Linear's hosted ones, `github` and `linear`, and `metaos`) and
  the secrets above, set through Canvas' API by the CronJob `canvas-settings` (`factory/canvas_settings.tf`,
  `elysium/canvas-settings`): **weekly**, as Linear's token lasts 30 days, and at once with `just canvas-settings`.

To turn MetaOS on, once Keycloak has the client (CI applies `keycloak/prod` on merge to master, or
`just keycloak apply prod`):

```sh
just keycloak output prod -raw elysium_client_secret | secretspec set ELYSIUM_METAOS_CLIENT_SECRET --from-file -
infra/elysium/factory/set_secrets.sh   # the bridge gets it, and restarts
just canvas-settings                   # Canvas gets the metaos MCP server
```

### The bridge: Elysium in Linear and on pull requests

Delegating a Linear issue to Elysium, or mentioning it in a comment, starts an agent session; Linear sends it to the
**Linear bridge** (`elysium/linear-bridge`, `factory/linear-bridge.tf`), what Cyrus is on nuc8, at
`hooks.ogrodje.dev/linear/webhook`, without Access (Linear can't sign in), checking Linear's signature instead.

- The session runs in the **personal Canvas of whoever started it**, by their Linear email in `FACTORY_USERS`, so with
  their MCP accounts. Someone without a Canvas is told so in the session, and nothing runs; never in someone else's,
  nor in the team's.
- The conversation has the session's ID, the issue in its title, a $10 cost ceiling, the Canvas' secrets (by
  reference, as Canvas' UI gives them: `GITHUB_TOKEN`, Elysium's git identity), and Linear's context and how
  Elysium works (a branch, a pull request titled with the issue's identifier, merge only when asked) as its first
  message. The issue moves to its team's first started state.
- In Linear: a first thought at once, a link to the conversation (which only its Canvas' person can open), each step
  as an action, and the agent's last message as Elysium's answer. A reply in the session goes to the same
  conversation; Stop interrupts it.
- **On GitHub**, Elysium is `@elysium-og` (`@elysium` is someone else's). A pull request comment, review or review
  comment that mentions it, or changes requested on a pull request it opened, reaches the bridge through the `ogrodje`
  org webhook (`factory/github.tf`, at `hooks.ogrodje.dev/github/webhook`, signed). From someone in `FACTORY_USERS`
  (their `github` login) only: the repositories are public, so anyone else's is ignored, without a word. The bridge
  reacts 👀, goes on in the conversation the pull request came from if it's in their Canvas (a conversation is tagged
  with the pull request its answer names), or starts one there with the pull request and what was asked, and posts
  the agent's last message on the pull request, for them, with a link to the conversation.
- One file, Node's own modules only, which Node runs as TypeScript as it is: a ConfigMap of it on Node's own image, so
  nothing to build. `node --test elysium/linear-bridge/` tests it. It keeps no state: after a restart it reports the
  outcome of the conversations still running.

LiteLLM is the only thing that holds the providers' keys; Canvas has LiteLLM's key. Without a database it has no
budgets of its own yet, so the spend limits are the providers', on the workspace and the project the keys are of.

What the factory's pods may reach (`factory/network.tf`): Canvas the internet and LiteLLM, LiteLLM the internet on
443, both the cluster's DNS; neither the rest of the cluster, the VPC, the tailnet's addresses nor Vultr's metadata
service.

Not yet: Docker in the agents' builds (MetaOS' tests run Postgres with Testcontainers). That's Sysbox, whose
installer replaces the factory node's container runtime, and which isn't known to work on VKE's nodes yet.
