// The Canvases' settings (settings.ts), against a fake Canvas and Linear: `node --test elysium/canvas-settings/`.
import assert from "node:assert/strict";
import { createServer } from "node:http";
import type { IncomingMessage, Server } from "node:http";
import type { AddressInfo } from "node:net";
import { describe, it } from "node:test";
import { run } from "./settings.ts";
import type { Config } from "./settings.ts";

type Call = { method: string; path: string; key?: string; body: any };

/** A Canvas that knows no MCP servers yet and has no active agent profile, unless told otherwise */
async function fakeCanvas(state: { mcp: Set<string>; active?: string; failSecrets?: boolean }) {
  const calls: Call[] = [];
  const server = createServer(async (request: IncomingMessage, response) => {
    let text = "";
    for await (const chunk of request) text += chunk;
    const call = { method: request.method!, path: request.url!, key: request.headers["x-session-api-key"] as string, body: text ? JSON.parse(text) : null };
    calls.push(call);
    const reply = (status: number, body: unknown = {}) => response.writeHead(status, { "Content-Type": "application/json" }).end(JSON.stringify(body));
    const mcp = call.path.match(/^\/api\/settings\/mcp\/(\w+)$/);
    if (call.method === "GET" && call.path === "/api/settings") return reply(200, { active_agent_profile_id: state.active ?? null });
    if (call.method === "GET" && call.path === "/api/agent-profiles") return reply(200, { profiles: [] });
    if (call.method === "POST" && call.path === "/api/agent-profiles/default") return reply(200, { id: "ap-1" });
    if (call.method === "PUT" && call.path === "/api/settings/secrets") return reply(state.failSecrets ? 500 : 200);
    if (mcp && call.method === "PATCH") return state.mcp.has(mcp[1]) ? reply(200) : reply(404, { detail: "no such server" });
    if (mcp && call.method === "POST") {
      state.mcp.add(mcp[1]);
      return reply(201);
    }
    return reply(200);
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  return { calls, server, url: `http://127.0.0.1:${(server.address() as AddressInfo).port}` };
}

async function fakeLinear() {
  const asked: URLSearchParams[] = [];
  const server = createServer(async (request, response) => {
    let text = "";
    for await (const chunk of request) text += chunk;
    const form = new URLSearchParams(text);
    asked.push(form);
    if (form.get("client_secret") !== "lin-secret") return response.writeHead(401).end(JSON.stringify({ error: "invalid_client" }));
    response.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ access_token: "lin-token", expires_in: 2592000 }));
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  return { asked, server, url: `http://127.0.0.1:${(server.address() as AddressInfo).port}/oauth/token` };
}

function configFor(canvases: { name: string; url: string }[], linearUrl: string, linearSecret = "lin-secret"): Config {
  return {
    canvases: canvases.map((c) => ({ ...c, key: `key-${c.name}` })),
    litellm: { url: "http://litellm:4000", key: "llm-key", model: "litellm_proxy/anthropic/claude-sonnet-5-5", profile: "claude-sonnet-5-5" },
    github: { token: "gh-token", login: "elysium-og", id: "361901225", mcpUrl: "https://api.githubcopilot.com/mcp/" },
    linear: { clientId: "lin-id", clientSecret: linearSecret, tokenUrl: linearUrl, mcpUrl: "https://mcp.linear.app/mcp" },
    previews: { url: "http://manager.previews:8080", token: "pv-token" },
    metaosMcpUrl: "http://linear-bridge:8080/mcp/metaos",
  };
}

const close = (...servers: Server[]) => servers.forEach((s) => s.close());

describe("a Canvas' settings", () => {
  it("a new Canvas: the LLM and an agent profile, Elysium's secrets, and the three MCP servers added", async () => {
    const linear = await fakeLinear();
    const canvas = await fakeCanvas({ mcp: new Set() });
    try {
      assert.equal(await run(configFor([{ name: "team", url: canvas.url }], linear.url)), true);
      assert.equal(linear.asked[0].get("scope"), "read,write,app:assignable,app:mentionable");
      assert.ok(canvas.calls.every((c) => c.key === "key-team"));

      const paths = canvas.calls.map((c) => `${c.method} ${c.path}`);
      assert.deepEqual(paths.slice(0, 6), [
        "POST /api/profiles/claude-sonnet-5-5",
        "POST /api/profiles/claude-sonnet-5-5/activate",
        "GET /api/settings",
        "GET /api/agent-profiles",
        "POST /api/agent-profiles/default",
        "POST /api/agent-profiles/ap-1/activate",
      ]);
      assert.equal(canvas.calls[0].body.llm.api_key, "llm-key");

      const secrets = canvas.calls.filter((c) => c.path === "/api/settings/secrets").map((c) => [c.body.name, c.body.value]);
      assert.deepEqual(Object.fromEntries(secrets), {
        GITHUB_TOKEN: "gh-token",
        GIT_AUTHOR_NAME: "Elysium",
        GIT_COMMITTER_NAME: "Elysium",
        GIT_AUTHOR_EMAIL: "361901225+elysium-og@users.noreply.github.com",
        GIT_COMMITTER_EMAIL: "361901225+elysium-og@users.noreply.github.com",
        PREVIEWS_URL: "http://manager.previews:8080",
        PREVIEWS_TOKEN: "pv-token",
      });

      const added = canvas.calls.filter((c) => c.method === "POST" && c.path.startsWith("/api/settings/mcp/"));
      assert.deepEqual(added.map((c) => [c.path.split("/").pop(), c.body.auth]), [
        ["github", { strategy: "bearer", value: "gh-token" }],
        ["linear", { strategy: "bearer", value: "lin-token" }],
        ["metaos", { strategy: "none" }],
      ]);
    } finally {
      close(linear.server, canvas.server);
    }
  });

  it("again: the profile it has stays active, and the MCP servers it has are updated, not added twice", async () => {
    const linear = await fakeLinear();
    const canvas = await fakeCanvas({ mcp: new Set(["github", "linear", "metaos"]), active: "ap-9" });
    try {
      assert.equal(await run(configFor([{ name: "team", url: canvas.url }], linear.url)), true);
      const paths = canvas.calls.map((c) => `${c.method} ${c.path}`);
      assert.ok(!paths.some((p) => p.includes("agent-profiles")));
      assert.ok(!paths.some((p) => p.startsWith("POST /api/settings/mcp/")));
      assert.equal(paths.filter((p) => p.startsWith("PATCH /api/settings/mcp/")).length, 3);
    } finally {
      close(linear.server, canvas.server);
    }
  });

  it("one Canvas failing fails the run, but the others are still done", async () => {
    const linear = await fakeLinear();
    const broken = await fakeCanvas({ mcp: new Set(), failSecrets: true });
    const fine = await fakeCanvas({ mcp: new Set() });
    try {
      const config = configFor([{ name: "oto", url: broken.url }, { name: "team", url: fine.url }], linear.url);
      assert.equal(await run(config), false);
      assert.ok(fine.calls.some((c) => c.path === "/api/settings/mcp/metaos"));
    } finally {
      close(linear.server, broken.server, fine.server);
    }
  });

  it("a Linear secret that's no longer current stops it before any Canvas is touched", async () => {
    const linear = await fakeLinear();
    const canvas = await fakeCanvas({ mcp: new Set() });
    try {
      await assert.rejects(run(configFor([{ name: "team", url: canvas.url }], linear.url, "old")), /LINEAR_CLIENT_SECRET/);
      assert.equal(canvas.calls.length, 0);
    } finally {
      close(linear.server, canvas.server);
    }
  });
});
