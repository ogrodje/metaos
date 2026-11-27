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
| `agent.ogrodje.dev`, tunnel `elysium-factory` | Agent Canvas, in the Cloudflare account that has ogrodje.dev, behind Access |

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
   Read. And who may use the factory: `secretspec set FACTORY_ACCESS_EMAILS`.
   Then `just infra elysium/factory apply`, and `infra/elysium/factory/set_secrets.sh`.
6. `just canvas`, which puts Canvas' session key on the clipboard: paste it where http://localhost:8800/canvas/ asks.
   Then its LLM settings: model `litellm_proxy/anthropic/claude-sonnet-5-5` (or `claude-opus-5-5`, or
   `openai/<model>`), base URL `http://litellm.factory.svc.cluster.local:4000`, and API key LiteLLM's, which
   `just litellm-key` puts on the clipboard. Canvas keeps them, encrypted, on its volume.

## The factory

Agent Canvas is OpenHands' single-tenant app (MIT): its UI, agent-server and automations in one pod, with every agent
in that pod and on its one volume. Its service account has no access to the cluster.

It's at **https://agent.ogrodje.dev** for the team (`factory/access.tf`, `factory/cloudflared.tf`): a Cloudflare
Tunnel in the Cloudflare account that has ogrodje.dev (not ogrodje.si's), with Cloudflare Access in front. Two locks:

- **Access says who:** the emails in `FACTORY_ACCESS_EMAILS` (1Password, not this public repository), each signing in
  with a code Cloudflare emails them, for 24 hours at a time. cloudflared checks Access' token too, and is the only
  thing that reaches Canvas (NetworkPolicy `canvas`).
- **Canvas' session key says what:** `~/.openhands/agent-canvas/api-key.txt` on its volume, which drives every agent;
  `just canvas-key` copies it. Each browser asks for it once, with the backend's address (Host
  `https://agent.ogrodje.dev`, Type Local), as it keeps both per site; the LLM settings and the conversations are
  Canvas' own, so they're there already.

Everyone shares the one Canvas: its conversations, its settings, the agents' disk and LiteLLM's budget. Adding someone
is `secretspec set FACTORY_ACCESS_EMAILS` with their address added, then `just infra elysium/factory apply`; taking them
out, the same without it, and a new session key if they had it (delete `api-key.txt`, restart `canvas-0`).

`just canvas` still port-forwards it, as before.

LiteLLM is the only thing that holds the providers' keys; Canvas has LiteLLM's key. Without a database it has no
budgets of its own yet, so the spend limits are the providers', on the workspace and the project the keys are of.

What the factory's pods may reach (`factory/network.tf`): Canvas the internet and LiteLLM, LiteLLM the internet on
443, both the cluster's DNS; neither the rest of the cluster, the VPC, the tailnet's addresses nor Vultr's metadata
service.

Not yet: Docker in the agents' builds (MetaOS' tests run Postgres with Testcontainers). That's Sysbox, whose
installer replaces the factory node's container runtime, and which isn't known to work on VKE's nodes yet.
