// Every Canvas' settings that Elysium gives it (infra/elysium/README.md, "Elysium in GitHub and Linear"), which Canvas
// keeps, encrypted, on its volume rather than in Kubernetes: so neither Terraform nor set_secrets.sh, but its API.
//
//   LLM (Customize > LLM):          claude-sonnet-5-5, Claude Sonnet 5.5 through LiteLLM, with its key; the agent
//                                   profile using it
//   Secrets (Settings > Secrets),   GITHUB_TOKEN, elysium-og's, which git and gh push, open and merge PRs with;
//   exported to every agent:        PREVIEWS_URL/PREVIEWS_TOKEN: the previews' manager, for MetaOS' preview/preview.sh
//   MCP servers (Customize > MCP):  github, GitHub's hosted server as elysium-og; linear, Linear's as the app Elysium,
//                                   with a new client-credentials token each run; metaos, MetaOS' (Ogrodje's) as
//                                   Keycloak's service account elysium, through the bridge, which holds its token
//
// The same in each Canvas, the team's and everyone's own; what a person connects themselves it leaves alone. A CronJob
// in the cluster (infra/elysium/factory/canvas_settings.tf), weekly, as Linear's token lasts 30 days, from what the
// cluster has already (the Canvases' session keys, LiteLLM's key, the bridge's GitHub and Linear secrets), so nothing
// leaves it; `just canvas-settings` runs it now. Idempotent. New conversations get the change; running ones keep what
// they started with. One file, Node's own modules only, run as TypeScript by Node itself, from a ConfigMap.

// --- Configuration ---------------------------------------------------------------------------------------------------

export interface Target {
  name: string;
  /** Its API inside the cluster, e.g. http://canvas.factory.svc.cluster.local:8000 */
  url: string;
  key: string;
}

export interface Config {
  canvases: Target[];
  litellm: { url: string; key: string; model: string; profile: string };
  github: { token: string; login: string; mcpUrl: string };
  linear: { clientId: string; clientSecret: string; tokenUrl: string; mcpUrl: string };
  previews?: { url: string; token: string };
  /** The bridge's route to MetaOS' MCP server, which signs in as Elysium */
  metaosMcpUrl?: string;
}

/** Elysium's install in Linear: the scopes of its client-credentials token (elysium/linear-bridge: LINEAR_SCOPES) */
const LINEAR_SCOPES = "read,write,app:assignable,app:mentionable";

export function configFromEnv(env: NodeJS.ProcessEnv): Config {
  const required = (name: string) => {
    const value = env[name];
    if (!value) throw new Error(`${name} is not set`);
    return value;
  };
  return {
    // The bridge's, which names each Canvas with its session key
    canvases: (JSON.parse(required("CANVASES")) as Target[]).map(({ name, url, key }) => ({ name, url, key })),
    litellm: {
      url: env.LITELLM_URL ?? "http://litellm.factory.svc.cluster.local:4000",
      key: required("LITELLM_MASTER_KEY"),
      model: env.MODEL ?? "litellm_proxy/anthropic/claude-sonnet-5-5",
      profile: env.LLM_PROFILE ?? "claude-sonnet-5-5",
    },
    github: {
      token: required("GITHUB_TOKEN"),
      login: env.GITHUB_LOGIN ?? "elysium-og",
      mcpUrl: env.GITHUB_MCP_URL ?? "https://api.githubcopilot.com/mcp/",
    },
    linear: {
      clientId: required("LINEAR_CLIENT_ID"),
      clientSecret: required("LINEAR_CLIENT_SECRET"),
      tokenUrl: env.LINEAR_TOKEN_URL ?? "https://api.linear.app/oauth/token",
      mcpUrl: env.LINEAR_MCP_URL ?? "https://mcp.linear.app/mcp",
    },
    previews: env.PREVIEWS_URL && env.PREVIEWS_TOKEN ? { url: env.PREVIEWS_URL, token: env.PREVIEWS_TOKEN } : undefined,
    metaosMcpUrl: env.METAOS_MCP_URL || undefined,
  };
}

function log(message: string, fields: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ time: new Date().toISOString(), message, ...fields }));
}

// --- Linear ----------------------------------------------------------------------------------------------------------

/** A client-credentials token of the app Elysium: the app as actor, 30 days, no refresh; one for every Canvas */
export async function linearToken(config: Config["linear"]): Promise<string> {
  const response = await fetch(config.tokenUrl, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "client_credentials",
      scope: LINEAR_SCOPES,
      client_id: config.clientId,
      client_secret: config.clientSecret,
    }),
  });
  const body = (await response.json().catch(() => ({}))) as { access_token?: string; error?: string };
  if (!response.ok || !body.access_token) {
    throw new Error(`Linear gave no token: ${response.status} ${body.error ?? ""} (is LINEAR_CLIENT_SECRET current?)`);
  }
  return body.access_token;
}

// --- A Canvas --------------------------------------------------------------------------------------------------------

export class Canvas {
  target: Target;
  constructor(target: Target) {
    this.target = target;
  }

  async call(method: string, path: string, body?: unknown): Promise<{ status: number; body: any }> {
    const response = await fetch(`${this.target.url}/api${path}`, {
      method,
      headers: { "Content-Type": "application/json", "X-Session-API-Key": this.target.key },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let parsed: unknown = text;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      // not JSON: as it is
    }
    return { status: response.status, body: parsed };
  }

  /** call(), failing on a status that isn't 2xx */
  async ok(method: string, path: string, what: string, body?: unknown): Promise<any> {
    const result = await this.call(method, path, body);
    if (result.status < 200 || result.status >= 300) {
      throw new Error(`${this.target.name}: ${what}: HTTP ${result.status} ${JSON.stringify(result.body)}`);
    }
    return result.body;
  }
}

/** Secrets that earlier runs set, and this one removes */
export const RETIRED_SECRETS = ["GIT_AUTHOR_NAME", "GIT_COMMITTER_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_EMAIL"];

/** Everything above into one Canvas; what it set, for the log */
export async function settle(canvas: Canvas, config: Config, linear: string): Promise<string[]> {
  const done: string[] = [];
  const { litellm, github } = config;

  // The LLM: the profile, active, and an agent profile using it (a new Canvas' server makes "default" itself)
  await canvas.ok("POST", `/profiles/${litellm.profile}`, "LLM profile", {
    llm: { model: litellm.model, base_url: litellm.url, api_key: litellm.key, usage_id: "default" },
  });
  await canvas.ok("POST", `/profiles/${litellm.profile}/activate`, "LLM profile activation");
  done.push(`LLM ${litellm.profile}`);

  const settings = await canvas.ok("GET", "/settings", "settings");
  if (!settings?.active_agent_profile_id) {
    const profiles = await canvas.ok("GET", "/agent-profiles", "agent profiles");
    let id = (profiles?.profiles ?? []).find((p: any) => p.name === "default")?.id ??
      (profiles?.profiles ?? []).find((p: any) => p.profile?.name === "default")?.profile?.id;
    if (!id) {
      const created = await canvas.ok("POST", "/agent-profiles/default", "agent profile", {
        schema_version: 4,
        agent_kind: "openhands",
        name: "default",
        llm_profile_ref: litellm.profile,
      });
      id = created?.id ?? created?.profile?.id;
    }
    await canvas.ok("POST", `/agent-profiles/${id}/activate`, "agent profile activation");
    done.push("agent profile");
  }

  // Exported to every agent. Canvas hides a secret's value wherever it appears in what an agent says, so only what's
  // secret: the git identity was one too, and every "Elysium" in a reply read <secret-hidden>. It's the pod's
  // /etc/gitconfig now (infra/elysium/factory/canvas/main.tf), and the old secrets are removed
  const secrets: [string, string, string][] = [
    ["GITHUB_TOKEN", github.token, `GitHub token of ${github.login} (Elysium): push, pull requests, issues and workflows on all of ogrodje`],
  ];
  if (config.previews) {
    secrets.push(
      ["PREVIEWS_URL", config.previews.url, "Elysium's previews' manager, for MetaOS' preview/preview.sh (up, ls, logs, down)"],
      ["PREVIEWS_TOKEN", config.previews.token, "The previews' manager's token, with PREVIEWS_URL"],
    );
  }
  for (const [name, value, description] of secrets) {
    await canvas.ok("PUT", "/settings/secrets", `secret ${name}`, { name, value, description });
  }
  done.push(`secrets ${secrets.map(([n]) => n).join(", ")}`);
  for (const name of RETIRED_SECRETS) {
    const removed = await canvas.call("DELETE", `/settings/secrets/${name}`);
    if (removed.status !== 404 && (removed.status < 200 || removed.status >= 300)) {
      throw new Error(`${canvas.target.name}: removing secret ${name}: HTTP ${removed.status} ${JSON.stringify(removed.body)}`);
    }
  }

  // MCP servers: updated if the Canvas has them, added if not
  const servers: [string, string, string | null][] = [
    ["github", github.mcpUrl, github.token],
    ["linear", config.linear.mcpUrl, linear],
  ];
  if (config.metaosMcpUrl) servers.push(["metaos", config.metaosMcpUrl, null]);
  for (const [name, url, token] of servers) {
    const body = {
      url,
      transport: "http",
      enabled: true,
      auth: token ? { strategy: "bearer", value: token } : { strategy: "none" },
    };
    const patched = await canvas.call("PATCH", `/settings/mcp/${name}`, body);
    if (patched.status === 404) await canvas.ok("POST", `/settings/mcp/${name}`, `MCP server ${name}`, body);
    else if (patched.status < 200 || patched.status >= 300) {
      throw new Error(`${canvas.target.name}: MCP server ${name}: HTTP ${patched.status} ${JSON.stringify(patched.body)}`);
    }
  }
  done.push(`MCP ${servers.map(([n]) => n).join(", ")}`);
  return done;
}

/** Every Canvas, each on its own: one that fails doesn't stop the others, but fails the run */
export async function run(config: Config): Promise<boolean> {
  const linear = await linearToken(config.linear);
  let ok = true;
  for (const target of config.canvases) {
    try {
      const done = await settle(new Canvas(target), config, linear);
      log("settled", { canvas: target.name, done });
    } catch (error) {
      ok = false;
      log("failed", { canvas: target.name, error: String(error) });
    }
  }
  log("Linear's token lasts 30 days", { until: new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10) });
  return ok;
}

if (import.meta.main) {
  const ok = await run(configFromEnv(process.env)).catch((e) => {
    log("failed", { error: String(e) });
    return false;
  });
  process.exit(ok ? 0 : 1);
}
