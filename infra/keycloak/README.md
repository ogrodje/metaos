# Keycloak, in Terraform

Realm `metaos` as code, in every environment: everything about it but its users, planned, reviewed and applied the
same way. Nothing else sets it up: Keycloak starts with the master realm only, and Terraform makes the rest.

| Directory | What                                                                                                         |
|-----------|--------------------------------------------------------------------------------------------------------------|
| `realm/`  | The realm, as a module                                                                                       |
| `dev/`    | compose.yml's Keycloak, `http://localhost:8180`. Each developer has their own, so the state is on their machine |
| `prod/`   | `https://metaos-auth.ogrodje.si`, with its state in Cloud Storage (`../gcp`)                                  |

```bash
just keycloak plan prod     # what would change
just keycloak apply prod    # change it; CI does on a merge into master (../README.md)
just keycloak adopt dev     # a realm the state doesn't have yet, e.g. a new compose Keycloak's (below)
```

`just keycloak` signs in to production as `KEYCLOAK_ADMIN_USERNAME` with `KEYCLOAK_ADMIN_PASSWORD` (`.envrc`), and to
compose.yml's Keycloak as `admin` / `admin`.

## What it manages

| File                          | What                                                                                       |
|-------------------------------|--------------------------------------------------------------------------------------------|
| `realm/realm.tf`              | The realm: sign-in lengths, Remember me, token lifespan, brute-force protection, security headers, email (SMTP) and verifying addresses with it; the role `metaos-user`; the `dev` user in development |
| `realm/scopes.tf`             | `mcp:tools`, `metaos:read` and `metaos:write`, the audience each puts in tokens, and that each needs `metaos-user` |
| `realm/clients.tf`            | `metaos-ui`, the front-end's client, and its scopes                                        |
| `realm/registration.tf`       | What MCP clients registering themselves (DCR) may ask for, and from which hosts            |
| `realm/identity_providers.tf` | Sign in with Google and Discord, each with its approval gate (the post-login flow that waits for `metaos-user`), which then remembers the sign-in (MetaOS's `metaos-remember-me`, which Keycloak has from `docker/keycloak.Dockerfile`'s image) |

Not managed, and left as they are: users and who has `metaos-user` (granted by hand in the admin console, where users
are renamed too, as `edit_username_allowed` lets them be), the clients MCP clients registered, Keycloak's built-in
clients and scopes, its keys, and realm master.

## Secrets

Client secrets and the SMTP password aren't in the configuration; they come from the environment, when there's one to
set: `TF_VAR_google_client_secret`, `TF_VAR_discord_client_secret`, `TF_VAR_smtp_password`. One that isn't set is sent
as `**********`, which Keycloak takes as "keep the one you have", so a plan or apply without them changes no secret. Set
one to change it; a new realm or sign-in provider needs its real one. The plan after one was set says it changes back to
`**********`: applying that keeps the secret, and the plans after it say "No changes".

The state has the realm in it, with any secret that was set: production's is in the bucket `ogrodje-metaos-tfstate`,
which only the project's owners and CI can read, and development's in `dev/terraform.tfstate`, not committed.

## A realm the state doesn't have

`just keycloak adopt <env>` runs [`import.sh`](import.sh), which takes what's in Keycloak into the state, by name, and
changes nothing there. A Keycloak without realm `metaos`, as compose.yml's starts, gets it made first, as `terraform
apply` asks: Keycloak gives every new realm its default client registration policies, which have to be adopted rather
than made again, as two Trusted Hosts policies would refuse what the default one doesn't allow. So, for a new
development Keycloak, or one whose state was deleted:

```bash
docker compose up -d keycloak
just keycloak adopt dev
TF_VAR_google_client_secret=$GOOGLE_IDP_CLIENT_SECRET just keycloak apply dev   # the secret only the first time
```

The first plan after adopting a realm Terraform didn't make has a few changes that change nothing: fields import
doesn't read back (Discord's claims, the security headers), written again as they are, and Keycloak's defaults said out
loud (e.g. `use_refresh_tokens`). After one apply, plan says "No changes". Anything else it plans is a real difference
between the realm and this: look at it before applying.
