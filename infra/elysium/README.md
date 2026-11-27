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
   every Canvas its LLM (Claude Sonnet 5.5, through LiteLLM), GitHub and Linear.

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
- **In Canvas**, both as MCP servers (GitHub's and Linear's hosted ones, `github` and `linear`) and the secrets above,
  set by `just canvas-settings` (`factory/canvas_settings.sh`) through Canvas' API: **monthly**, for Linear's token.

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
