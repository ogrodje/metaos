# Infrastructure, in Terraform

What MetaOS runs on and with is code here, planned and applied with Terraform (devenv's; CI's is the same version).
Nothing in it is set up by hand or by a script: a change to it is a change here, planned, reviewed and applied.

| Stack                         | What                                                                                     | State                  | Applied                         |
|-------------------------------|------------------------------------------------------------------------------------------|------------------------|---------------------------------|
| [`keycloak/`](keycloak)       | Realm `metaos`: scopes, clients, sign-in providers and their approval gate, sessions, email | `prod`: Cloud Storage; `dev`: each developer's own | `prod`: CI, on merge; `dev`: `just keycloak <command> dev` |
| [`gcp/`](gcp)                 | Project `ogrodje-metaos`: both environments' Cloud Storage, the states' bucket, CI's way in | Cloud Storage          | `just infra gcp <command>`, by its owners only |
| [`newrelic/`](newrelic)       | New Relic: the browser apps, uptime checks, alerts and where they go, the dashboard       | Cloud Storage          | CI, on merge |
| [`cloudflare/`](cloudflare)   | Zone `ogrodje.si`, as far as MetaOS goes: its three names, the zone's cache and configuration rules, Smart Tiered Caching | Cloud Storage          | CI, on merge |
| [`tailscale/`](tailscale)     | The tailnet's policy file, all of it. **Private:** never in git, never in CI (below)     | Cloud Storage          | `just infra tailscale <command>`, on a machine that has the file |

## Public and private

This repository is public, so what's here is what anyone may read. Every stack but one is: names, rules, settings and
IDs that are public anyway, or say nothing an attacker could use; secrets come from the environment and are only in the
private states. The tailnet's policy file isn't: it says how the whole tailnet is put together, other projects' parts
too. So [`tailscale/`](tailscale/README.md) commits only how it's managed. The policy, `tailscale/policy.hujson`, is
kept out of git (`.gitignore`), on the machines that apply it, in Tailscale and in its state; `just tailscale-policy`
gets it from Tailscale where it's missing, and a plan without it stops and says so. CI doesn't apply it.

A new stack, or a new part of one, that would put something like that in the repository goes the same way.

## Development and production

How a stack has its environments depends on what they are:

- **Separate systems: a directory each, sharing a module.** Development's Keycloak is every developer's own, in
  compose.yml; production's is in the cluster. They differ in where they are, how Terraform signs in and where the
  state is kept, so `keycloak/dev` and `keycloak/prod` each say only that, and the realm itself is `keycloak/realm`,
  the same for both. Not workspaces: those share one backend and one way of signing in, so development's state couldn't
  stay on a developer's machine while production's is shared, and one `workspace select` forgotten applies one
  environment's values to the other.
- **One account with both: a directory, the environments in it.** Both environments' buckets are in project
  `ogrodje-metaos` (`gcp/storage`, twice: `storage_dev` and `storage_prod`), and both browser apps in one New Relic
  account. They're made with the same credentials and change together, so one plan shows a change to both.
- **Production only:** Cloudflare and Tailscale have no development of their own; development runs on localhost.

A new environment, e.g. staging, is a directory beside `keycloak/dev` and `keycloak/prod`, and one more of each
module where the environments share an account.

## State

Every stack's state is in the bucket `ogrodje-metaos-tfstate` (`gcp/main.tf`), under the stack's name, with
Terraform's lock beside it: versioned, the 20 latest versions of each kept, and older ones 90 days. States have secrets
in them, so only the project's owners and CI read it. Development's Keycloak is the exception: its state is in
`keycloak/dev/terraform.tfstate`, on the developer's machine, as the Keycloak it describes is.

## Signing in

| What                                        | Locally (`just`)                                     | CI (environment `production`; only an apply is a deployment) |
|---------------------------------------------|------------------------------------------------------|-------------------------------|
| The states, and `gcp`                       | You, in gcloud (`gcloud auth login`): `just` hands Terraform gcloud's token | `metaos-terraform-ci`, with no key (Workload Identity Federation, `gcp/ci.tf`) |
| Keycloak                                    | `KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD`; development's is `admin` / `admin` | The same secrets |
| New Relic                                   | `NEW_RELIC_USER_KEY`, a User API key                 | `NEW_RELIC_API_KEY`           |
| Cloudflare                                  | `CLOUDFLARE_API_TOKEN`: zone `ogrodje.si`, Zone Read, DNS Edit, Zone Settings Edit, Cache Rules Edit, Config Rules Edit | The same secret |
| Tailscale                                   | `TAILSCALE_OAUTH_CLIENT_ID`, `TAILSCALE_OAUTH_CLIENT_SECRET`: the OAuth client "MetaOS Terraform" (Trust credentials) with Policy File write, Devices Core read, Devices Routes read | Not in CI |

## CI

CI runs `keycloak/prod`, `newrelic` and `cloudflare` ([`terraform.yml`](../.github/workflows/terraform.yml)), as
the deploy rolls out the app: a pull request that changes one plans it, and its merge into `master` applies it. Each
plan is in its run's summary, which is what the apply then does: a plan saved, not made again. Actions → Terraform →
Run workflow plans one by hand, and applies it with Apply ticked, e.g. after a change made outside Terraform. A
change to the workflow plans, and applies, all three.

tflint checks every stack, CI's and the others, on every change to `infra/` (`.tflint.hcl`: Terraform's
recommended rules, and Google's for `gcp`); `just tflint` does the same locally, with devenv's tflint.

Three aren't CI's: `gcp` can give anyone anything in the project, so only its owners apply it, themselves;
`tailscale`'s policy file is private, so not in the repository CI checks out; and `keycloak/dev` is each developer's
own. An infra change merged with an app change is applied beside the app's deploy, not before it: one the app needs
first goes in a pull request of its own.

## The first time

The states' bucket is made by a stack, so the first applies go in this order:

1. `gcp`, with its state on disk to begin with (`gcp/backend_override.tf`, not committed): `just infra gcp apply`
   takes in the buckets and service accounts `google/storage.sh` made (`gcp/imports.tf`), changing only their
   labels' and descriptions' wording, and makes the states' bucket and CI's way in. Then the state moves into the
   bucket: delete `backend_override.tf`, and in `infra/gcp`,
   `GOOGLE_OAUTH_ACCESS_TOKEN=$(gcloud auth print-access-token) terraform init -migrate-state`. Delete `imports.tf`.
2. `newrelic`: `just infra newrelic apply` takes in what was made by hand (`newrelic/imports.tf`) and changes nothing.
   Delete `imports.tf`.
3. Keycloak: `just keycloak adopt prod`, then `just keycloak plan prod` (keycloak/README.md says what the first plan
   has) and `just keycloak apply prod`.
4. `cloudflare`: `just infra cloudflare apply` takes in MetaOS's names and the zone's rules (`cloudflare/imports.tf`) and
   changes nothing. Delete `imports.tf`.
5. `tailscale`: `just infra tailscale apply` takes in the policy file as `tailscale/policy.hujson` has it
   (`just tailscale-policy`), changing nothing. Delete `imports.tf`.
6. GitHub's environment `production`: `NEW_RELIC_API_KEY` and `CLOUDFLARE_API_TOKEN`, for CI.

## What isn't Terraform's

- **Sign-ins and keys:** the bot's refresh tokens (`google/refresh_token.sh`, `dropbox/refresh_token.sh`), its Patreon
  session (`patreon-gateway/login.sh`) and Koofr app password, the storage service accounts' keys (`just
  storage-key`), and the Kubernetes secrets made from them all (`k8s/set_secrets.sh`). Terraform would keep a copy of
  each in its state.
- **What has no API:** the Google OAuth clients (MetaOS's, and Keycloak's for Sign in with Google), the Discord and
  Dropbox apps, Search Console.
- **The cluster's shared pieces:** the Postgres cluster `main-cluster` (MetaOS's roles and databases:
  `k8s/set_secrets.sh`) and kube-prometheus-stack (`k8s/prometheus/newrelic.sh`), which other apps use too.
- **The servers:** ogrodje-one (its kubelet) and low.
- **MetaOS's own Deployments:** `k8s/base`, rolled out by `deploy.yml` with each change.
