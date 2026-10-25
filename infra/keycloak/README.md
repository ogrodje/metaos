# Keycloak, in OpenTofu

Realm `metaos` as code: what [`keycloak/metaos-realm.json`](../../keycloak/metaos-realm.json) and the
[`keycloak/*.sh`](../../keycloak) scripts set up, in one place that `tofu plan` can compare with a running Keycloak.
Keycloak imports the realm file only once, so production has only had what the scripts did since; here a change is
planned, reviewed and applied the same way in every environment.

OpenTofu (`tofu`) comes from the devenv shell, with the [`keycloak/keycloak`](https://registry.terraform.io/providers/keycloak/keycloak)
provider. Each environment is a workspace with its values in `<env>.tfvars`: `dev` (compose.yml's Keycloak) and
`prod` (https://metaos-auth.ogrodje.si).

```bash
just keycloak plan prod     # what would change
just keycloak apply prod    # change it
```

`just keycloak` signs in to realm master as `KEYCLOAK_ADMIN_USERNAME` (`admin` by default) with
`KEYCLOAK_ADMIN_PASSWORD`, as the scripts do.

## What it manages

| File                    | What                                                                                                  |
|-------------------------|-------------------------------------------------------------------------------------------------------|
| `realm.tf`              | The realm: sign-in lengths, token lifespan, brute-force protection, security headers, email (SMTP); the role `metaos-user`; the `dev` user in development |
| `scopes.tf`             | `mcp:tools`, `metaos:read` and `metaos:write`, the audience each puts in tokens, and that each needs `metaos-user` |
| `clients.tf`            | `metaos-ui`, the front-end's client, and its scopes                                                   |
| `registration.tf`       | What MCP clients registering themselves (DCR) may ask for, and from which hosts                      |
| `identity_providers.tf` | Sign in with Google and Discord, each with its approval gate (the post-login flow that waits for `metaos-user`) |

Not managed, and left as they are: users and who has `metaos-user` (granted by hand), the clients MCP clients
registered, Keycloak's built-in clients and scopes, and realm master.

## Secrets

Client secrets and the SMTP password aren't in the `.tfvars`; they come from the environment, when there's one to set:
`TF_VAR_google_client_secret`, `TF_VAR_discord_client_secret`, `TF_VAR_smtp_password`. One that isn't set is sent as
`**********`, which Keycloak takes as "keep the one you have", so a plan or apply without them changes no secret. Set
one to change it. A new realm or sign-in provider needs its real secrets.

The state has the realm in it, with any secret that was set, so it isn't committed (`.gitignore`): for now it's on the
machine that applies, in `terraform.tfstate.d/<env>/`. Where it should live instead is open (OG-36).

## Adopting an existing realm

Once per environment, [`import.sh`](import.sh) takes what's already there into the state, by name, and changes
nothing in Keycloak:

```bash
cd infra/keycloak
KEYCLOAK_ADMIN_PASSWORD=... ./import.sh prod   # also fill in the client IDs in prod.tfvars first
just keycloak plan prod
```

The first plan after it has a few changes that change nothing: fields import doesn't read back (Discord's claims, the
security headers), written again as they are, and Keycloak's defaults said out loud (e.g. `use.refresh.tokens`). After
one apply, plan says "No changes". Anything else it plans is a real difference between the realm and this: look at it
before applying.

## A new realm

Keycloak gives every new realm its default client registration policies, which have to be adopted rather than made
again (two Trusted Hosts policies would refuse what the default one doesn't allow), so it takes three steps:

```bash
just keycloak apply <env> -target=keycloak_realm.metaos
KEYCLOAK_ADMIN_PASSWORD=... ./import.sh <env>
just keycloak apply <env>
```

## The realm file and the scripts

`keycloak/metaos-realm.json` still sets up compose.yml's Keycloak the first time, and has to say what this says: a
realm imported from it, adopted here and applied once plans no changes. The scripts in `keycloak/` did by hand what this does, and
can go once production is adopted.
