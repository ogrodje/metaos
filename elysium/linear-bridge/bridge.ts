// Elysium's bridge from Linear and GitHub to Agent Canvas (infra/elysium/README.md, "The bridge"), what Cyrus is on
// nuc8. Two ways in:
//
// - Linear: someone delegates an issue to Elysium or mentions it, and Linear sends an agent session event. The bridge
//   starts a conversation for it in that person's own Canvas, and reports back to the session: a thought at once, each
//   of the agent's steps as an action, its last message as the response. A reply in the session goes to the same
//   conversation; Stop interrupts it.
// - GitHub: someone mentions @elysium-og on a pull request (a comment, a review, a review comment) or requests changes
//   on one Elysium opened. The bridge reacts with 👀, continues the conversation the pull request came from if it's in
//   that person's Canvas, or starts one there, and posts the agent's last message on the pull request.
//
// And a third thing: MetaOS' MCP server for the Canvases' agents, at /mcp/metaos inside the cluster only, which the
// bridge passes on to with a token of Elysium's own service account (Keycloak's client elysium), new before the last
// runs out: so no agent holds a MetaOS credential, and none runs out under a long conversation.
//
// And a fourth: a pull request of MetaOS' merged or closed, from the same webhook, is its branch's preview done with,
// which the previews' manager (elysium/previews) is told, so it's stopped and cleared.
//
// Only for the people in FACTORY_USERS, each in their own Canvas, with its agent profile (its LLM, MCP servers and
// secrets, so their accounts): someone else's request runs nowhere, and never in the team's. The bridge keeps no state
// of its own: a conversation's ID is its Linear session's, and its tags say which pull request it's on, so a restart
// picks up the running ones again from Canvas (resumeRunning). One file, Node's own modules only, run as TypeScript by
// Node itself, so it needs no build: Terraform puts it into a ConfigMap (infra/elysium/factory/linear-bridge.tf).

import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import type { IncomingMessage, Server, ServerResponse } from "node:http";

// --- Configuration ---------------------------------------------------------------------------------------------------

/** One Canvas: the team's, or a person's (infra/elysium/factory: one per FACTORY_USERS entry, and the team's). */
export interface CanvasTarget {
  name: string;
  /** Its API inside the cluster, e.g. http://canvas-oto.factory.svc.cluster.local:8000 */
  url: string;
  key: string;
  /** Canvas as its people open it, for links to its conversations, e.g. https://agent-oto.ogrodje.dev/canvas */
  publicUrl: string;
  /** Whose Canvas it is, by Linear email and GitHub login: their requests run here. The team's has none */
  emails: string[];
  github: string[];
}

export interface Config {
  port: number;
  linearWebhookSecret: string;
  linearClientId: string;
  linearClientSecret: string;
  /** GitHub's side, off without them: the org webhook's secret, and Elysium's token (elysium-og's) to answer with */
  githubWebhookSecret?: string;
  githubToken?: string;
  /** Elysium on GitHub, whom a mention asks */
  githubLogin: string;
  canvases: CanvasTarget[];
  /** MetaOS' MCP server and Elysium's service account in its Keycloak; the /mcp/metaos route is off without them */
  metaos?: MetaOSConfig;
  /** The previews' manager (elysium/previews), told when a pull request of MetaOS' closes, so its preview is cleared */
  previews?: { url: string; token: string };
  /** Hard cost ceiling of one conversation, in USD */
  maxBudgetPerRun: number;
  pollMs: number;
}

export interface MetaOSConfig {
  /** e.g. https://ogrodje.si/mcp */
  mcpUrl: string;
  /** Keycloak's token endpoint, e.g. https://metaos-auth.ogrodje.si/realms/metaos/protocol/openid-connect/token */
  tokenUrl: string;
  clientId: string;
  clientSecret: string;
}

export function configFromEnv(env: NodeJS.ProcessEnv): Config {
  const need = (name: string): string => {
    const value = env[name];
    if (!value) throw new Error(`${name} is not set`);
    return value;
  };
  return {
    port: Number(env.PORT ?? 8080),
    linearWebhookSecret: need("LINEAR_WEBHOOK_SECRET"),
    linearClientId: need("LINEAR_CLIENT_ID"),
    linearClientSecret: need("LINEAR_CLIENT_SECRET"),
    githubWebhookSecret: env.GITHUB_WEBHOOK_SECRET || undefined,
    githubToken: env.GITHUB_TOKEN || undefined,
    githubLogin: env.GITHUB_LOGIN ?? "elysium-og",
    // [{name, url, key, publicUrl, emails, github}], from Terraform (infra/elysium/factory/linear-bridge.tf)
    canvases: (JSON.parse(need("CANVASES")) as CanvasTarget[]).map((c) => ({
      ...c,
      url: c.url.replace(/\/$/, ""),
      publicUrl: c.publicUrl.replace(/\/$/, ""),
      emails: c.emails.map((e) => e.toLowerCase()),
      github: (c.github ?? []).map((l) => l.toLowerCase()),
    })),
    metaos:
      env.METAOS_MCP_URL && env.METAOS_TOKEN_URL && env.METAOS_CLIENT_ID && env.METAOS_CLIENT_SECRET
        ? {
            mcpUrl: env.METAOS_MCP_URL,
            tokenUrl: env.METAOS_TOKEN_URL,
            clientId: env.METAOS_CLIENT_ID,
            clientSecret: env.METAOS_CLIENT_SECRET,
          }
        : undefined,
    previews: env.PREVIEWS_URL && env.PREVIEWS_TOKEN ? { url: env.PREVIEWS_URL, token: env.PREVIEWS_TOKEN } : undefined,
    maxBudgetPerRun: Number(env.MAX_BUDGET_PER_RUN ?? 10),
    pollMs: Number(env.POLL_MS ?? 3000),
  };
}

function log(message: string, fields: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ time: new Date().toISOString(), message, ...fields }));
}

function hmacMatches(rawBody: Buffer, given: string, secret: string, prefix = ""): boolean {
  const expected = Buffer.from(prefix + createHmac("sha256", secret).update(rawBody).digest("hex"));
  const actual = Buffer.from(given);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

// --- Linear's webhook ------------------------------------------------------------------------------------------------

/** The parts of Linear's AgentSessionEventWebhookPayload the bridge reads (@linear/sdk, webhooks). */
export interface AgentSessionEvent {
  type: string;
  action: string;
  webhookId: string;
  webhookTimestamp: number;
  promptContext?: string | null;
  agentSession: {
    id: string;
    url?: string | null;
    /** Who delegated the issue or wrote the comment that mentions Elysium */
    creator?: { id: string; name: string; email: string } | null;
    issue?: { id: string; identifier: string; title: string; url: string } | null;
  };
  agentActivity?: { id?: string; content?: { type?: string; body?: string } | null; signal?: string | null } | null;
}

/**
 * What a Linear event is, to do it once though Linear retries it: its session, what happened, and for a reply its
 * activity. Not its `webhookId`, which is the webhook's, the same in every delivery.
 */
export function linearEventKey(event: AgentSessionEvent): string {
  return `linear:${event.action}:${event.agentSession.id}:${event.agentActivity?.id ?? ""}`;
}

/** Linear signs the raw body with HMAC-SHA256, as hex, in `linear-signature`; the timestamp must be within a minute. */
export function verifyWebhook(rawBody: Buffer, signature: string | undefined, secret: string, now = Date.now()): boolean {
  if (!signature || !hmacMatches(rawBody, signature, secret)) return false;
  let timestamp: unknown;
  try {
    timestamp = JSON.parse(rawBody.toString("utf8")).webhookTimestamp;
  } catch {
    return false;
  }
  return typeof timestamp === "number" && Number.isFinite(timestamp) && Math.abs(now - timestamp) <= 60_000;
}

// --- Linear's API, as the app Elysium --------------------------------------------------------------------------------

/** Elysium's install in Linear: the same in every token (canvas_settings.sh's, the justfile's) */
export const LINEAR_SCOPES = "read,write,app:assignable,app:mentionable";

/** A Linear agent activity's content: https://linear.app/developers/agent-interaction */
export type ActivityContent =
  | { type: "thought"; body: string }
  | { type: "action"; action: string; parameter: string; result?: string }
  | { type: "response"; body: string }
  | { type: "error"; body: string }
  | { type: "elicitation"; body: string };

export class Linear {
  #config: Config;
  #token: string | undefined;
  #expiresAt = 0;

  constructor(config: Config) {
    this.#config = config;
  }

  /**
   * A client-credentials token (the app as actor, 30 days), renewed a day before it ends. Its scopes are Elysium's
   * install: with app:assignable and app:mentionable, Linear makes it an Agent, which can be delegated issues and
   * mentioned. Every token is asked for with the same ones (LINEAR_SCOPES), as Linear doesn't change an install's scopes.
   */
  async #accessToken(): Promise<string> {
    if (this.#token && Date.now() < this.#expiresAt - 86_400_000) return this.#token;
    const response = await fetch("https://api.linear.app/oauth/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        scope: LINEAR_SCOPES,
        client_id: this.#config.linearClientId,
        client_secret: this.#config.linearClientSecret,
      }),
    });
    const body = (await response.json()) as { access_token?: string; expires_in?: number; error?: string };
    if (!response.ok || !body.access_token) throw new Error(`Linear token: ${response.status} ${body.error ?? ""}`);
    this.#token = body.access_token;
    this.#expiresAt = Date.now() + (body.expires_in ?? 86_400) * 1000;
    return this.#token;
  }

  async graphql<T>(query: string, variables: Record<string, unknown>, retried = false): Promise<T> {
    const response = await fetch("https://api.linear.app/graphql", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${await this.#accessToken()}` },
      body: JSON.stringify({ query, variables }),
    });
    if (response.status === 401 && !retried) {
      this.#token = undefined;
      return this.graphql(query, variables, true);
    }
    const body = (await response.json()) as { data?: T; errors?: { message: string }[] };
    if (!response.ok || body.errors?.length) {
      throw new Error(`Linear: ${response.status} ${(body.errors ?? []).map((e) => e.message).join("; ")}`);
    }
    return body.data as T;
  }

  async activity(sessionId: string, content: ActivityContent, ephemeral = false): Promise<void> {
    await this.graphql(
      `mutation($input: AgentActivityCreateInput!) { agentActivityCreate(input: $input) { success } }`,
      { input: { agentSessionId: sessionId, content, ...(ephemeral ? { ephemeral } : {}) } },
    );
  }

  /** The link on the session to its conversation in Canvas. */
  async link(sessionId: string, url: string): Promise<void> {
    await this.graphql(
      `mutation($id: String!, $input: AgentSessionUpdateInput!) { agentSessionUpdate(id: $id, input: $input) { success } }`,
      { id: sessionId, input: { externalUrls: [{ label: "Open in Elysium", url }] } },
    );
  }

  /** As Linear asks of agents: an issue not yet started, completed or canceled moves to the team's first started state. */
  async markStarted(issueId: string): Promise<void> {
    const data = await this.graphql<{
      issue: { state: { type: string }; team: { states: { nodes: { id: string; position: number }[] } } };
    }>(
      `query($id: String!) { issue(id: $id) { state { type } team { states(filter: { type: { eq: "started" } }) {
         nodes { id position } } } } }`,
      { id: issueId },
    );
    if (["started", "completed", "canceled"].includes(data.issue.state.type)) return;
    const first = [...data.issue.team.states.nodes].sort((a, b) => a.position - b.position)[0];
    if (!first) return;
    await this.graphql(
      `mutation($id: String!, $stateId: String!) { issueUpdate(id: $id, input: { stateId: $stateId }) { success } }`,
      { id: issueId, stateId: first.id },
    );
  }
}

// --- GitHub's webhook ------------------------------------------------------------------------------------------------

/** GitHub signs the raw body with HMAC-SHA256 too, as `sha256=<hex>` in `x-hub-signature-256`. */
export function verifyGitHub(rawBody: Buffer, signature: string | undefined, secret: string): boolean {
  return !!signature && hmacMatches(rawBody, signature, secret, "sha256=");
}

/** Someone asking Elysium for something on a pull request. */
export interface GitHubRequest {
  delivery: string;
  /** owner/name */
  repo: string;
  number: number;
  /** Who asked, by GitHub login */
  author: string;
  kind: "comment" | "review" | "review comment";
  body: string;
  url: string;
  /** What to react 👀 to (a review has no reactions) */
  reaction?: { on: "issue" | "pull"; id: number };
  /** A review comment's place */
  path?: string;
  line?: number | null;
  diffHunk?: string;
}

// The parts of GitHub's issue_comment, pull_request_review and pull_request_review_comment payloads the bridge reads
interface GitHubUser {
  login: string;
  type?: string;
}
interface GitHubPayload {
  action?: string;
  sender?: GitHubUser;
  repository?: { full_name: string };
  issue?: { number: number; pull_request?: unknown };
  pull_request?: { number: number; user?: GitHubUser };
  comment?: {
    id: number;
    body?: string | null;
    html_url: string;
    path?: string;
    line?: number | null;
    original_line?: number | null;
    diff_hunk?: string;
  };
  review?: { body?: string | null; state?: string; html_url: string };
}

/**
 * Whether a GitHub event asks Elysium for something: a comment, review or review comment that mentions it (by its
 * login, as a word), or changes requested on a pull request it opened. Never its own, nor a bot's.
 */
export function requestOf(event: string, delivery: string, payload: GitHubPayload, elysium: string): GitHubRequest | null {
  const sender = payload.sender;
  const repo = payload.repository?.full_name;
  if (!sender || !repo || sender.type === "Bot" || sender.login.toLowerCase() === elysium.toLowerCase()) return null;
  const login = elysium.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const mentions = (body: string | null | undefined) => new RegExp(`(^|[^\\w-])@${login}(?![\\w-])`, "i").test(body ?? "");
  const asked = { delivery, repo, author: sender.login };

  if (event === "issue_comment") {
    const { issue, comment } = payload;
    if (payload.action !== "created" || !issue?.pull_request || !comment || !mentions(comment.body)) return null;
    return {
      ...asked,
      number: issue.number,
      kind: "comment",
      body: comment.body ?? "",
      url: comment.html_url,
      reaction: { on: "issue", id: comment.id },
    };
  }
  if (event === "pull_request_review") {
    const { review, pull_request: pr } = payload;
    if (payload.action !== "submitted" || !review || !pr) return null;
    const onElysiums = pr.user?.login.toLowerCase() === elysium.toLowerCase();
    if (!mentions(review.body) && !(onElysiums && review.state?.toLowerCase() === "changes_requested")) return null;
    return { ...asked, number: pr.number, kind: "review", body: review.body ?? "", url: review.html_url };
  }
  if (event === "pull_request_review_comment") {
    const { comment, pull_request: pr } = payload;
    if (payload.action !== "created" || !comment || !pr || !mentions(comment.body)) return null;
    return {
      ...asked,
      number: pr.number,
      kind: "review comment",
      body: comment.body ?? "",
      url: comment.html_url,
      reaction: { on: "pull", id: comment.id },
      path: comment.path,
      line: comment.line ?? comment.original_line ?? null,
      diffHunk: comment.diff_hunk,
    };
  }
  return null;
}

// --- GitHub's API, as elysium-og -------------------------------------------------------------------------------------

export interface PullRequest {
  number: number;
  title: string;
  html_url: string;
  user: { login: string };
  head: { ref: string; repo: { full_name: string } | null };
  base: { repo: { full_name: string } };
}

export class GitHub {
  #token: string;

  constructor(token: string) {
    this.#token = token;
  }

  async #request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const response = await fetch(`https://api.github.com${path}`, {
      method,
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${this.#token}`,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "elysium-bridge",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`GitHub ${method} ${path}: ${response.status} ${await response.text()}`);
    const text = await response.text();
    return (text ? JSON.parse(text) : null) as T;
  }

  pullRequest(repo: string, number: number): Promise<PullRequest> {
    return this.#request("GET", `/repos/${repo}/pulls/${number}`);
  }

  async react(repo: string, on: "issue" | "pull", commentId: number, content = "eyes"): Promise<void> {
    await this.#request("POST", `/repos/${repo}/${on === "issue" ? "issues" : "pulls"}/comments/${commentId}/reactions`, {
      content,
    });
  }

  async comment(repo: string, number: number, body: string): Promise<void> {
    await this.#request("POST", `/repos/${repo}/issues/${number}/comments`, { body });
  }
}

// --- Previews --------------------------------------------------------------------------------------------------------

/**
 * The branch whose pull request was merged or closed, if it's MetaOS' own (not a fork's, whose branch names aren't
 * ours): its preview is done with (elysium/previews).
 */
export function closedBranch(event: string, payload: Record<string, any>): string | null {
  if (event !== "pull_request" || payload.action !== "closed") return null;
  const pr = payload.pull_request;
  if (payload.repository?.full_name !== "ogrodje/metaos" || pr?.head?.repo?.full_name !== "ogrodje/metaos") return null;
  return pr.head.ref ?? null;
}

export async function previewsDone(previews: { url: string; token: string }, branch: string): Promise<void> {
  const response = await fetch(`${previews.url}/closed`, {
    method: "POST",
    headers: { Authorization: `Bearer ${previews.token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ branch }),
  });
  const body = (await response.json().catch(() => ({}))) as { down?: number[]; error?: string };
  if (!response.ok) throw new Error(`previews: ${response.status} ${body.error ?? ""}`);
  if (body.down?.length) log("previews done with", { branch, slots: body.down });
}

// --- MetaOS' MCP server, as Elysium's service account ----------------------------------------------------------------

/** What of an MCP request and response is passed on (Streamable HTTP): not the agent's own Authorization, if any */
const MCP_REQUEST_HEADERS = ["content-type", "accept", "mcp-session-id", "mcp-protocol-version", "last-event-id"];
const MCP_RESPONSE_HEADERS = ["content-type", "mcp-session-id", "cache-control"];

export class MetaOS {
  #config: MetaOSConfig;
  #token: string | undefined;
  #expiresAt = 0;

  constructor(config: MetaOSConfig) {
    this.#config = config;
  }

  /** A client-credentials token of Keycloak's client elysium, new a minute before the last one runs out. */
  async #accessToken(): Promise<string> {
    if (this.#token && Date.now() < this.#expiresAt - 60_000) return this.#token;
    const response = await fetch(this.#config.tokenUrl, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_id: this.#config.clientId,
        client_secret: this.#config.clientSecret,
      }),
    });
    const body = (await response.json()) as { access_token?: string; expires_in?: number; error?: string };
    if (!response.ok || !body.access_token) throw new Error(`MetaOS token: ${response.status} ${body.error ?? ""}`);
    this.#token = body.access_token;
    this.#expiresAt = Date.now() + (body.expires_in ?? 300) * 1000;
    return this.#token;
  }

  /** The agent's MCP request, on to MetaOS with the token; its answer back as it comes, a stream of events too. */
  async proxy(request: IncomingMessage, response: ServerResponse, body: Buffer, retried = false): Promise<void> {
    const headers: Record<string, string> = { Authorization: `Bearer ${await this.#accessToken()}` };
    for (const name of MCP_REQUEST_HEADERS) {
      const value = header(request, name);
      if (value) headers[name] = value;
    }
    const method = request.method ?? "GET";
    const upstream = await fetch(this.#config.mcpUrl, {
      method,
      headers,
      body: ["GET", "HEAD", "DELETE"].includes(method) ? undefined : body,
    });
    // A token MetaOS turned down (e.g. Keycloak's keys rotated): once more with a new one
    if (upstream.status === 401 && !retried) {
      await upstream.body?.cancel();
      this.#token = undefined;
      return this.proxy(request, response, body, true);
    }
    const out: Record<string, string> = {};
    for (const name of MCP_RESPONSE_HEADERS) {
      const value = upstream.headers.get(name);
      if (value) out[name] = value;
    }
    response.writeHead(upstream.status, out);
    if (upstream.body) for await (const chunk of upstream.body) response.write(chunk);
    response.end();
  }
}

// --- Canvas' API -----------------------------------------------------------------------------------------------------

export interface CanvasEvent {
  id: string;
  kind: string;
  timestamp: string;
  source?: string;
  tool_name?: string;
  summary?: string | null;
  key?: string;
  value?: unknown;
  llm_message?: { role: string; content: { type: string; text?: string }[] };
  error?: string;
  detail?: string;
}

export interface Conversation {
  id: string;
  execution_status: string;
  tags?: Record<string, string>;
}

export class Canvas {
  readonly target: CanvasTarget;
  #maxBudgetPerRun: number;

  constructor(target: CanvasTarget, maxBudgetPerRun: number) {
    this.target = target;
    this.#maxBudgetPerRun = maxBudgetPerRun;
  }

  async #request<T>(method: string, path: string, body?: unknown): Promise<{ status: number; body: T }> {
    const response = await fetch(`${this.target.url}/api${path}`, {
      method,
      headers: { "Content-Type": "application/json", "X-Session-API-Key": this.target.key },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    return { status: response.status, body: (text ? JSON.parse(text) : null) as T };
  }

  async #ok<T>(method: string, path: string, body?: unknown): Promise<T> {
    const result = await this.#request<T>(method, path, body);
    if (result.status >= 300) {
      throw new Error(`Canvas ${this.target.name} ${method} ${path}: ${result.status} ${JSON.stringify(result.body)}`);
    }
    return result.body;
  }

  /** The conversation, or null if Canvas has none with this ID. */
  async conversation(id: string): Promise<Conversation | null> {
    const result = await this.#request<Conversation>("GET", `/conversations/${id}`);
    if (result.status === 404) return null;
    if (result.status >= 300) throw new Error(`Canvas GET conversation: ${result.status}`);
    return result.body;
  }

  /**
   * A new conversation with Canvas' active agent profile, so with its LLM and MCP servers, under `id`, and its secrets
   * (Settings > Secrets), as Canvas' UI gives them: each by reference, which the agent-server looks up itself when the
   * agent needs it (a LookupSecret), so their values never pass through here.
   */
  async start(id: string, prompt: string, title: string, tags: Record<string, string>): Promise<void> {
    const settings = await this.#ok<{ active_agent_profile_id?: string | null }>("GET", "/settings");
    if (!settings.active_agent_profile_id) throw new Error("Canvas has no active agent profile");
    const saved = await this.#ok<{ secrets: { name: string; description?: string | null }[] }>("GET", "/settings/secrets");
    const secrets = Object.fromEntries(
      saved.secrets.map((s) => [
        s.name,
        {
          kind: "LookupSecret",
          url: `/api/settings/secrets/${encodeURIComponent(s.name)}`,
          headers: { "X-Session-API-Key": this.target.key },
          ...(s.description ? { description: s.description } : {}),
        },
      ]),
    );
    await this.#ok("POST", "/conversations", {
      conversation_id: id,
      agent_profile_id: settings.active_agent_profile_id,
      secrets,
      // Where Canvas' UI puts a conversation's working directory
      workspace: { working_dir: `/home/openhands/.openhands/agent-canvas/workspaces/${id.replaceAll("-", "")}` },
      initial_message: { role: "user", content: [{ type: "text", text: prompt }], run: true },
      max_budget_per_run: this.#maxBudgetPerRun,
      tags: { clientsource: "agentcanvas", ...tags },
      autotitle: false,
    });
    await this.#ok("PATCH", `/conversations/${id}`, { title });
  }

  async message(id: string, text: string): Promise<void> {
    await this.#ok("POST", `/conversations/${id}/events`, { role: "user", content: [{ type: "text", text }], run: true });
  }

  async interrupt(id: string): Promise<void> {
    await this.#ok("POST", `/conversations/${id}/interrupt`);
  }

  /** Adds tags to the conversation's own. */
  async tag(id: string, tags: Record<string, string>): Promise<void> {
    const current = (await this.conversation(id))?.tags ?? {};
    await this.#ok("PATCH", `/conversations/${id}`, { tags: { ...current, ...tags } });
  }

  /** The newest conversation tagged `key` = `value`, e.g. the one a pull request came from. */
  async tagged(key: string, value: string): Promise<Conversation | null> {
    const body = await this.#ok<{ items: Conversation[] }>("GET", "/conversations/search?limit=100");
    return body.items.find((c) => c.tags?.[key] === value) ?? null;
  }

  /** The conversation's events from `since` on (Canvas' own timestamp), oldest first. */
  async events(id: string, since: string | undefined): Promise<CanvasEvent[]> {
    const all: CanvasEvent[] = [];
    let page: string | undefined;
    do {
      const query = new URLSearchParams({ limit: "100" });
      if (since) query.set("timestamp__gte", since);
      if (page) query.set("page_id", page);
      const body = await this.#ok<{ items: CanvasEvent[]; next_page_id?: string | null }>(
        "GET",
        `/conversations/${id}/events/search?${query}`,
      );
      all.push(...body.items);
      page = body.next_page_id ?? undefined;
    } while (page);
    return all;
  }

  /**
   * Conversations the bridge started that are still running, e.g. after it restarted. By each one's own status, as
   * the search's status filter doesn't narrow it down: a finished one would have its answer posted again.
   */
  async runningFromBridge(): Promise<Conversation[]> {
    const body = await this.#ok<{ items: Conversation[] }>("GET", "/conversations/search?limit=100");
    return body.items.filter((c) => c.execution_status === "running" && (c.tags?.linearsession || c.tags?.githubpr));
  }
}

// --- From Canvas' events to what Linear and GitHub are told ----------------------------------------------------------

const TERMINAL = new Set(["finished", "idle", "paused", "error", "stuck"]);

function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

/** An agent's step as a Linear action: the tool, and what it was called with, which Canvas sums up as "tool: …". */
export function actionOf(event: CanvasEvent): ActivityContent | null {
  if (event.kind !== "ActionEvent" || event.source !== "agent" || !event.tool_name) return null;
  const summary = (event.summary ?? "").replace(new RegExp(`^${event.tool_name}:\\s*`), "");
  return { type: "action", action: event.tool_name, parameter: truncate(summary, 300) };
}

/** The agent's words in a message event, if it is one of the agent's. */
export function agentText(event: CanvasEvent): string | null {
  if (event.kind !== "MessageEvent" || event.source !== "agent" || event.llm_message?.role !== "assistant") return null;
  const text = event.llm_message.content
    .filter((c) => c.type === "text" && c.text)
    .map((c) => c.text)
    .join("\n")
    .trim();
  return text || null;
}

/** What the conversation's last status means for whoever asked, once it has stopped running. */
export function finalActivity(status: string, lastText: string | null, stopped: boolean, error: string | null): ActivityContent {
  if (stopped || status === "paused") return { type: "response", body: "Stopped, as asked." };
  if (status === "error") return { type: "error", body: `The conversation failed${error ? `: ${error}` : "."}` };
  if (status === "stuck") return { type: "error", body: "I got stuck and stopped. The conversation in Elysium has the details." };
  return { type: "response", body: lastText ?? "Done." };
}

/** The pull request an agent's message names, as `owner/name#number`, if it's one of ogrodje's. */
export function pullRequestIn(text: string | null): string | null {
  const match = text?.match(/https:\/\/github\.com\/(ogrodje\/[\w.-]+)\/pull\/(\d+)/i);
  return match ? `${match[1]}#${match[2]}` : null;
}

/** The first message of a Linear session's conversation: Linear's own context for it, and how Elysium works. */
export function promptFor(event: AgentSessionEvent): string {
  const issue = event.agentSession.issue;
  const context = event.promptContext?.trim() || (issue ? `Linear issue ${issue.identifier}: ${issue.title}` : "");
  return [
    "You are Elysium, Ogrodje's software agent. This task comes from a Linear agent session; your steps and your last " +
      "message are posted to it for you, so don't comment on the issue or change its status yourself.",
    "",
    context,
    "",
    "How to work:",
    issue ? `- The issue is ${issue.identifier} (${issue.url}). The Linear tools have more of its context.` : "",
    "- The code is in GitHub's `ogrodje` organization: `gh repo clone ogrodje/<repo>`, in this conversation's working " +
      "directory. GITHUB_TOKEN and your git identity are set.",
    `- Work on a branch of your own${issue ? ` (e.g. \`elysium/${issue.identifier.toLowerCase()}-…\`)` : ""}, push it ` +
      `and open a pull request${issue ? ` whose title starts with ${issue.identifier}, so Linear links it` : ""}.`,
    "- Merge only when you are asked to.",
    "- End with a short message for Linear: what you did, the pull request's link, and what a person still has to decide.",
  ]
    .filter((line) => line !== "")
    .join("\n");
}

function quoted(text: string): string {
  return (text.trim() || "(no text)")
    .split("\n")
    .map((line) => `> ${line}`)
    .join("\n");
}

/** What was asked on GitHub, and where: for a new conversation and for one that goes on. */
function askedOnGitHub(request: GitHubRequest): string {
  const where =
    request.kind === "review comment" && request.path
      ? `\n\nOn \`${request.path}\`${request.line ? `, line ${request.line}` : ""}:\n\`\`\`diff\n${request.diffHunk ?? ""}\n\`\`\``
      : "";
  return `${request.author}'s ${request.kind} (${request.url}):\n\n${quoted(request.body)}${where}`;
}

/**
 * Where the work goes: the pull request's own branch, on top of what's there, as it may be someone else's; or, for a
 * fork's, which can't be pushed to, a pull request of Elysium's own that takes it over, if that's what was asked.
 */
function pushNote(pr: PullRequest): string {
  const fork = pr.head.repo?.full_name !== pr.base.repo.full_name;
  const history = "Never force-push, and never rebase or rewrite commits that aren't yours: the branch may be someone else's.";
  return fork
    ? "Its branch is a fork's, which you can't push to. If you were asked to take it over, push its commits and yours " +
        `to a new branch in ${pr.base.repo.full_name} and open a pull request of your own that says it takes over ` +
        `#${pr.number} and credits its author; otherwise answer with the change as a suggestion. ${history}`
    : `Push to its branch, \`${pr.head.ref}\`, on top of what's there. ${history}`;
}

/** The first message of a conversation started from a pull request. */
export function githubPrompt(request: GitHubRequest, pr: PullRequest): string {
  return [
    `You are Elysium, Ogrodje's software agent. ${request.author} asked you for this on GitHub, on pull request ` +
      `${request.repo}#${pr.number}, "${pr.title}" (${pr.html_url}).`,
    "",
    askedOnGitHub(request),
    "",
    "How to work:",
    `- \`gh pr checkout ${pr.number} --repo ${request.repo}\` in this conversation's working directory; ` +
      `\`gh pr view ${pr.number} --repo ${request.repo} --comments\` and the reviews' comments have the rest of the context.`,
    `- Do what was asked and commit. ${pushNote(pr)}`,
    "- Merge only when you are asked to.",
    "- Your last message is posted on the pull request for you, so don't comment there yourself: say briefly what you " +
      "changed, or why not.",
  ].join("\n");
}

/** A request on a pull request, to the conversation it came from. */
export function githubFollowUp(request: GitHubRequest, pr: PullRequest): string {
  return [
    `Feedback on your pull request ${request.repo}#${pr.number} (${pr.html_url}), on GitHub:`,
    "",
    askedOnGitHub(request),
    "",
    `Address it and commit. ${pushNote(pr)} Your last message is posted on the pull request for you: say briefly what ` +
      "you changed, or why not.",
  ].join("\n");
}

// --- The bridge ------------------------------------------------------------------------------------------------------

/** Whom a conversation's steps and outcome go to: a Linear session, a pull request, or both. */
export interface Reporter {
  action?(content: ActivityContent): Promise<void>;
  final(content: ActivityContent): Promise<void>;
}

interface Relay {
  stopped: boolean;
  reporters: Reporter[];
}

export class Bridge {
  #config: Config;
  #linear: Linear;
  #canvases: Canvas[];
  #github: GitHub | null;
  #relays = new Map<string, Relay>();
  #seen: string[] = [];

  constructor(
    config: Config,
    linear = new Linear(config),
    canvases = config.canvases.map((target) => new Canvas(target, config.maxBudgetPerRun)),
    github: GitHub | null = config.githubToken ? new GitHub(config.githubToken) : null,
  ) {
    this.#config = config;
    this.#linear = linear;
    this.#canvases = canvases;
    this.#github = github;
  }

  /** Linear and GitHub retry a delivery they didn't see answered in time: the same one is done once. */
  #firstDelivery(id: string): boolean {
    if (this.#seen.includes(id)) return false;
    this.#seen.push(id);
    if (this.#seen.length > 2000) this.#seen.shift();
    return true;
  }

  /** The personal Canvas of whoever started a Linear session, by their Linear email; null if they have none. */
  canvasFor(event: AgentSessionEvent): Canvas | null {
    const email = event.agentSession.creator?.email?.toLowerCase();
    return (email && this.#canvases.find((c) => c.target.emails.includes(email))) || null;
  }

  #canvasOfGitHub(login: string): Canvas | null {
    return this.#canvases.find((c) => c.target.github.includes(login.toLowerCase())) ?? null;
  }

  #linearReporter(session: string): Reporter {
    const linear = this.#linear;
    return {
      action: (content) => linear.activity(session, content),
      final: (content) => linear.activity(session, content),
    };
  }

  #githubReporter(canvas: Canvas, conversation: string, repo: string, number: number, author: string): Reporter {
    const github = this.#github;
    return {
      final: async (content) => {
        const body = "body" in content ? content.body : "Done.";
        const lead = content.type === "error" ? `Elysium couldn't finish this. ${body}` : body;
        const link = `${canvas.target.publicUrl}/conversations/${conversation}`;
        await github?.comment(repo, number, `${lead}\n\n<sub>Elysium, for @${author}: [the conversation](${link})</sub>`);
      },
    };
  }

  // --- Linear

  async handle(event: AgentSessionEvent): Promise<void> {
    if (event.type !== "AgentSessionEvent") return log("ignored", { from: "linear", type: event.type });
    if (!this.#firstDelivery(linearEventKey(event))) return log("repeated", { key: linearEventKey(event) });
    const session = event.agentSession.id;
    const creator = event.agentSession.creator;
    const canvas = this.canvasFor(event);
    log("session event", {
      session,
      action: event.action,
      issue: event.agentSession.issue?.identifier,
      canvas: canvas?.target.name ?? null,
    });
    try {
      if (!canvas) {
        // Nobody else's Canvas, with nobody else's accounts, does work someone without one asked for
        if (event.action === "created") {
          await this.#linear.activity(session, {
            type: "error",
            body:
              `${creator?.name ?? "Whoever started this"} has no Canvas in Elysium, so Elysium can't take this on. ` +
              "Ask for one (infra/elysium: FACTORY_USERS).",
          });
        }
        return;
      }
      if (event.action === "created") await this.#created(canvas, event);
      else if (event.action === "prompted") await this.#prompted(canvas, event);
    } catch (error) {
      log("session event failed", { session, error: String(error) });
      await this.#linear
        .activity(session, { type: "error", body: `Elysium couldn't take this on: ${String(error)}` })
        .catch((e) => log("error activity failed", { session, error: String(e) }));
    }
  }

  async #created(canvas: Canvas, event: AgentSessionEvent): Promise<void> {
    const session = event.agentSession.id;
    const issue = event.agentSession.issue;
    // Within Linear's 10 seconds
    await this.#linear.activity(session, { type: "thought", body: "On it: starting a conversation in Elysium." }, true);
    if (await canvas.conversation(session)) return; // Linear sent it twice; it's running already
    await canvas.start(session, promptFor(event), issue ? `${issue.identifier}: ${issue.title}` : "From Linear", {
      linearsession: session,
      ...(issue ? { linearissue: issue.identifier.toLowerCase() } : {}),
    });
    await this.#linear.link(session, `${canvas.target.publicUrl}/conversations/${session}`);
    if (issue) await this.#linear.markStarted(issue.id).catch((e) => log("issue state", { session, error: String(e) }));
    void this.relay(canvas, session, undefined, this.#linearReporter(session));
  }

  async #prompted(canvas: Canvas, event: AgentSessionEvent): Promise<void> {
    const session = event.agentSession.id;
    const activity = event.agentActivity;
    if (activity?.signal === "stop") {
      const relay = this.#relays.get(session);
      if (relay) relay.stopped = true;
      await canvas.interrupt(session).catch((e) => log("interrupt", { session, error: String(e) }));
      if (!relay) await this.#linear.activity(session, { type: "response", body: "Stopped, as asked." });
      return;
    }
    const text = activity?.content?.body?.trim();
    if (!text) return;
    if (!(await canvas.conversation(session))) {
      // A session this bridge never started (e.g. Canvas lost it): start one with the message as the task
      await this.#created(canvas, { ...event, action: "created", promptContext: `${event.promptContext ?? ""}\n\n${text}` });
      return;
    }
    await this.#linear.activity(session, { type: "thought", body: "Got it, passing it on." }, true);
    await this.#continue(canvas, session, text, this.#linearReporter(session));
  }

  // --- GitHub

  async handleGitHub(request: GitHubRequest): Promise<void> {
    if (!this.#firstDelivery(`github:${request.delivery}`)) return log("repeated", { delivery: request.delivery });
    const canvas = this.#canvasOfGitHub(request.author);
    log("pull request request", { repo: request.repo, number: request.number, kind: request.kind, canvas: canvas?.target.name ?? null });
    // Only for the factory's people: the repositories are public, and anyone may comment
    if (!canvas || !this.#github) return;
    const github = this.#github;
    const key = `${request.repo}#${request.number}`;
    try {
      if (request.reaction) {
        await github.react(request.repo, request.reaction.on, request.reaction.id).catch((e) => log("react", { error: String(e) }));
      }
      const pr = await github.pullRequest(request.repo, request.number);
      // The conversation the pull request came from, if it's this person's; otherwise a new one in their Canvas
      const from = await canvas.tagged("githubpr", key);
      if (from) {
        const reporter = this.#githubReporter(canvas, from.id, request.repo, request.number, request.author);
        await this.#continue(canvas, from.id, githubFollowUp(request, pr), reporter);
        return;
      }
      const id = randomUUID();
      await canvas.start(id, githubPrompt(request, pr), `${key}: ${pr.title}`, {
        githubpr: key,
        githubauthor: request.author.toLowerCase(),
      });
      void this.relay(canvas, id, undefined, this.#githubReporter(canvas, id, request.repo, request.number, request.author));
    } catch (error) {
      log("pull request request failed", { key, error: String(error) });
      await github
        .comment(request.repo, request.number, `Elysium couldn't take this on, @${request.author}: ${String(error)}`)
        .catch((e) => log("comment failed", { key, error: String(e) }));
    }
  }

  // --- Both

  /** A new message to a conversation; its outcome goes to `reporter` too, even if it's already reporting elsewhere. */
  async #continue(canvas: Canvas, id: string, text: string, reporter: Reporter): Promise<void> {
    const relaying = this.#relays.get(id);
    if (relaying) {
      relaying.reporters.push(reporter);
      await canvas.message(id, text);
      return;
    }
    const before = await this.#latest(canvas, id);
    await canvas.message(id, text);
    void this.relay(canvas, id, before, reporter);
  }

  /** Where the conversation is now, so that a relay after a new message reports only what follows it. */
  async #latest(canvas: Canvas, id: string): Promise<{ cursor: string; seen: string[] } | undefined> {
    const last = (await canvas.events(id, undefined)).at(-1);
    return last && { cursor: last.timestamp, seen: [last.id] };
  }

  /**
   * Reports the conversation's steps until it stops running, then its last message or error, to `reporter` and any
   * added meanwhile. From `start` on (the events up to it already reported or not to be), or, without it, from the
   * conversation's beginning. A pull request its last message names is tagged on it, so feedback there finds it.
   */
  async relay(canvas: Canvas, id: string, start: { cursor: string; seen: string[] } | undefined, reporter: Reporter): Promise<void> {
    const running = this.#relays.get(id);
    if (running) {
      running.reporters.push(reporter);
      return;
    }
    const relay: Relay = { stopped: false, reporters: [reporter] };
    this.#relays.set(id, relay);
    const seen = new Set<string>(start?.seen);
    let cursor = start?.cursor;
    let lastText: string | null = null;
    let error: string | null = null;
    let sawRunning = false;
    const began = Date.now();
    const report = async (content: ActivityContent) => {
      for (const r of relay.reporters) await r.final(content).catch((e) => log("report", { id, error: String(e) }));
    };
    try {
      for (;;) {
        // A run that never starts, or never ends, stops being reported on
        const waited = Date.now() - began;
        if ((!sawRunning && waited > 5 * 60_000) || waited > 12 * 3_600_000) {
          await report({ type: "error", body: "The conversation in Elysium didn't finish in time; it may still be there to look at." });
          return;
        }
        await new Promise((resolve) => setTimeout(resolve, this.#config.pollMs));
        for (const event of await canvas.events(id, cursor)) {
          if (seen.has(event.id)) continue;
          seen.add(event.id);
          cursor = event.timestamp;
          const action = actionOf(event);
          if (action) {
            for (const r of relay.reporters) await r.action?.(action).catch((e) => log("action", { id, error: String(e) }));
          }
          lastText = agentText(event) ?? lastText;
          if (event.kind.endsWith("ErrorEvent")) error = event.error ?? event.detail ?? error;
        }
        const conversation = await canvas.conversation(id);
        const status = conversation?.execution_status ?? "error";
        if (status === "running") sawRunning = true;
        // A new message can take a moment to start the run: idle before it has run once isn't the end
        if (TERMINAL.has(status) && (sawRunning || status !== "idle" || relay.stopped)) {
          this.#relays.delete(id); // a message from now on starts a relay of its own
          await report(finalActivity(status, lastText, relay.stopped, error));
          const pr = pullRequestIn(lastText);
          if (pr && conversation && conversation.tags?.githubpr !== pr) {
            await canvas.tag(id, { githubpr: pr }).catch((e) => log("tag", { id, error: String(e) }));
          }
          log("reported", { id, status, canvas: canvas.target.name, pr });
          return;
        }
      }
    } catch (e) {
      log("relay failed", { id, error: String(e) });
      await report({ type: "error", body: `Lost track of the conversation: ${String(e)}` });
    } finally {
      if (this.#relays.get(id) === relay) this.#relays.delete(id);
    }
  }

  /** After a restart: report the outcome of the conversations the bridge started that are still running. */
  async resumeRunning(): Promise<void> {
    for (const canvas of this.#canvases) {
      const running = await canvas.runningFromBridge().catch((e) => {
        log("resume failed", { canvas: canvas.target.name, error: String(e) });
        return [];
      });
      for (const conversation of running) {
        const tags = conversation.tags ?? {};
        const [repo, number] = (tags.githubpr ?? "").split("#");
        const reporter = tags.linearsession
          ? this.#linearReporter(tags.linearsession)
          : tags.githubauthor && repo && number
            ? this.#githubReporter(canvas, conversation.id, repo, Number(number), tags.githubauthor)
            : null;
        if (!reporter) continue;
        log("resuming", { id: conversation.id, canvas: canvas.target.name });
        // What happened while the bridge was down isn't reported step by step, only the outcome
        void this.relay(canvas, conversation.id, await this.#latest(canvas, conversation.id), reporter);
      }
    }
  }
}

// --- HTTP ------------------------------------------------------------------------------------------------------------

const MAX_BODY = 5 * 1024 * 1024;

async function readBody(request: IncomingMessage): Promise<Buffer | null> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    size += (chunk as Buffer).length;
    if (size > MAX_BODY) return null;
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks);
}

function header(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

export function server(
  config: Config,
  bridge: Bridge,
  metaos: MetaOS | null = config.metaos ? new MetaOS(config.metaos) : null,
): Server {
  return createServer(async (request: IncomingMessage, response: ServerResponse) => {
    const reply = (status: number, text: string) => response.writeHead(status, { "Content-Type": "text/plain" }).end(text);
    if (request.method === "GET" && request.url === "/healthz") return reply(200, "ok");
    // MetaOS' MCP server, for the Canvases only: the tunnel doesn't route it, NetworkPolicy lets only them reach it, and
    // anything that came through Cloudflare anyway is turned away
    if (metaos && (request.url === "/mcp/metaos" || request.url?.startsWith("/mcp/metaos?"))) {
      if (header(request, "cf-connecting-ip") || header(request, "cf-ray")) return reply(404, "not found");
      const body = await readBody(request);
      if (!body) return reply(413, "too large");
      try {
        await metaos.proxy(request, response, body);
      } catch (error) {
        log("metaos proxy failed", { error: String(error) });
        if (!response.headersSent) reply(502, "MetaOS isn't answering");
        else response.end();
      }
      return;
    }
    const github = request.url === "/github/webhook" && config.githubWebhookSecret;
    if (request.method !== "POST" || (request.url !== "/linear/webhook" && !github)) return reply(404, "not found");
    const body = await readBody(request);
    if (!body) return reply(413, "too large");
    const remote = header(request, "cf-connecting-ip") ?? request.socket.remoteAddress;

    if (github) {
      if (!verifyGitHub(body, header(request, "x-hub-signature-256"), config.githubWebhookSecret!)) {
        log("rejected webhook", { from: "github", remote });
        return reply(401, "invalid signature");
      }
      const event = header(request, "x-github-event") ?? "";
      const payload = JSON.parse(body.toString("utf8"));
      // A pull request merged or closed: its branch's preview is done with
      const closed = config.previews ? closedBranch(event, payload) : null;
      if (closed) {
        reply(202, "previews told");
        void previewsDone(config.previews!, closed).catch((e) => log("previews failed", { branch: closed, error: String(e) }));
        return;
      }
      const asked = requestOf(event, header(request, "x-github-delivery") ?? randomUUID(), payload, config.githubLogin);
      reply(202, asked ? "on it" : "nothing to do");
      if (asked) void bridge.handleGitHub(asked);
      return;
    }

    if (!verifyWebhook(body, header(request, "linear-signature"), config.linearWebhookSecret)) {
      log("rejected webhook", { from: "linear", remote });
      return reply(401, "invalid signature");
    }
    // Linear wants the answer within 5 seconds; the work follows it
    reply(200, "ok");
    void bridge.handle(JSON.parse(body.toString("utf8")) as AgentSessionEvent);
  });
}

if (import.meta.main) {
  const config = configFromEnv(process.env);
  const bridge = new Bridge(config);
  const http = server(config, bridge);
  http.listen(config.port, () =>
    log("listening", { port: config.port, github: !!config.githubWebhookSecret, metaos: !!config.metaos }),
  );
  bridge.resumeRunning().catch((e) => log("resume failed", { error: String(e) }));
  process.on("SIGTERM", () => http.close(() => process.exit(0)));
}
