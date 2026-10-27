# Tailscale, in Terraform, privately

The tailnet's policy file, `policy.hujson`: who and what may reach what. Unlike the other stacks, it's private. This
repository is public, and the policy says how the whole tailnet is put together, other projects' parts too, so:

- `policy.hujson` is never committed (`.gitignore`). The files here say how it's managed; the policy itself is only on
  the machines that apply it, in Tailscale, and in the state (Cloud Storage, private, versioned: its history).
- It's applied from such a machine, with `just`, never by CI (`.github/workflows/terraform.yml` hasn't got it).
- Without the file, a plan stops and says how to get it; it never plans the policy away.

```bash
just tailscale-policy          # policy.hujson from Tailscale, on a machine that hasn't got it (or to start over)
just infra tailscale plan      # what would change, after editing policy.hujson
just infra tailscale apply
```

Edit `policy.hujson` here, not in Tailscale's console: the next apply undoes a change made there. If one was made
anyway, `just tailscale-policy --force` takes Tailscale's over the file here.

It signs in as the OAuth client "MetaOS Terraform" (`TAILSCALE_OAUTH_CLIENT_ID`, `TAILSCALE_OAUTH_CLIENT_SECRET`
in `.envrc`): Policy File write, and reading devices and their routes.
