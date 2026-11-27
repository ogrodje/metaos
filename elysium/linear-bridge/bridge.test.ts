// `node --test elysium/linear-bridge/`: the bridge against fakes of Linear, GitHub and Canvas
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { describe, it } from "node:test";
import {
  actionOf,
  agentText,
  Bridge,
  closedBranch,
  finalActivity,
  githubPrompt,
  linearEventKey,
  MetaOS,
  promptFor,
  pullRequestIn,
  requestOf,
  server,
  verifyGitHub,
  verifyWebhook,
} from "./bridge.ts";
import type { ActivityContent, AgentSessionEvent, CanvasEvent, Config, GitHubRequest, PullRequest } from "./bridge.ts";

const config: Config = {
  port: 0,
  linearWebhookSecret: "lin_wh_test",
  linearClientId: "client",
  linearClientSecret: "secret",
  githubWebhookSecret: "gh_wh_test",
  githubToken: "gh_token",
  githubLogin: "elysium-og",
  canvases: [
    { name: "team", url: "http://canvas", key: "k0", publicUrl: "https://agent.example/canvas", emails: [], github: [] },
    {
      name: "oto",
      url: "http://canvas-oto",
      key: "k1",
      publicUrl: "https://agent-oto.example/canvas",
      emails: ["oto@example.com"],
      github: ["otobrglez"],
    },
    {
      name: "ana",
      url: "http://canvas-ana",
      key: "k2",
      publicUrl: "https://agent-ana.example/canvas",
      emails: ["ana@example.com"],
      github: ["ana"],
    },
  ],
  maxBudgetPerRun: 5,
  pollMs: 5,
};

const SESSION = "5b2d7f1e-1111-4222-8333-944455556666";

// Linear's webhookId is the webhook's: the same in every delivery
function sessionEvent(overrides: Partial<AgentSessionEvent> = {}): AgentSessionEvent {
  return {
    type: "AgentSessionEvent",
    action: "created",
    webhookId: "the-webhook",
    webhookTimestamp: Date.now(),
    promptContext: '<issue identifier="OG-86"><title>Verify the preview</title></issue>',
    agentSession: {
      id: SESSION,
      creator: { id: "u1", name: "Oto", email: "Oto@Example.com" },
      issue: { id: "issue-1", identifier: "OG-86", title: "Verify the preview", url: "https://linear.app/ogrodje/issue/OG-86" },
    },
    ...overrides,
  };
}

function sign(body: string, secret = config.linearWebhookSecret): string {
  return createHmac("sha256", secret).update(body).digest("hex");
}

describe("verifyWebhook", () => {
  const body = JSON.stringify({ webhookTimestamp: 1_000_000 });
  it("accepts Linear's signature within a minute", () => {
    assert.equal(verifyWebhook(Buffer.from(body), sign(body), config.linearWebhookSecret, 1_030_000), true);
  });
  it("refuses another secret's, a missing one and a tampered body", () => {
    assert.equal(verifyWebhook(Buffer.from(body), sign(body, "other"), config.linearWebhookSecret, 1_000_000), false);
    assert.equal(verifyWebhook(Buffer.from(body), undefined, config.linearWebhookSecret, 1_000_000), false);
    assert.equal(verifyWebhook(Buffer.from(`${body} `), sign(body), config.linearWebhookSecret, 1_000_000), false);
  });
  it("refuses a delivery older than a minute, a replay", () => {
    assert.equal(verifyWebhook(Buffer.from(body), sign(body), config.linearWebhookSecret, 1_061_000), false);
  });
});

describe("from Canvas' events to what's reported", () => {
  const action: CanvasEvent = {
    id: "a1",
    kind: "ActionEvent",
    timestamp: "t1",
    source: "agent",
    tool_name: "terminal",
    summary: 'terminal: {"command": "git status"}',
  };
  it("an agent's step is an action, without the tool's name twice", () => {
    assert.deepEqual(actionOf(action), { type: "action", action: "terminal", parameter: '{"command": "git status"}' });
  });
  it("other events aren't steps", () => {
    assert.equal(actionOf({ ...action, kind: "ObservationEvent" }), null);
    assert.equal(actionOf({ ...action, source: "user" }), null);
  });
  it("the agent's words, but not the user's", () => {
    const message: CanvasEvent = {
      id: "m1",
      kind: "MessageEvent",
      timestamp: "t2",
      source: "agent",
      llm_message: { role: "assistant", content: [{ type: "text", text: "Done: PR #12" }] },
    };
    assert.equal(agentText(message), "Done: PR #12");
    assert.equal(agentText({ ...message, source: "user", llm_message: { role: "user", content: [] } }), null);
  });
  it("the last status says how it ends", () => {
    assert.deepEqual(finalActivity("finished", "All done", false, null), { type: "response", body: "All done" });
    assert.deepEqual(finalActivity("finished", null, false, null), { type: "response", body: "Done." });
    assert.deepEqual(finalActivity("paused", "half", true, null), { type: "response", body: "Stopped, as asked." });
    assert.equal(finalActivity("error", null, false, "LLM down").type, "error");
    assert.equal(finalActivity("stuck", "x", false, null).type, "error");
  });
  it("a pull request named in the agent's last words, if it's ogrodje's", () => {
    assert.equal(pullRequestIn("Opened https://github.com/ogrodje/metaos/pull/123 for it"), "ogrodje/metaos#123");
    assert.equal(pullRequestIn("See https://github.com/someone/else/pull/1"), null);
    assert.equal(pullRequestIn(null), null);
  });
  it("the prompt carries Linear's context and the issue", () => {
    const prompt = promptFor(sessionEvent());
    assert.match(prompt, /<issue identifier="OG-86">/);
    assert.match(prompt, /OG-86 \(https:\/\/linear\.app\/ogrodje\/issue\/OG-86\)/);
    assert.match(prompt, /elysium\/og-86-/);
    assert.doesNotMatch(prompt, /\n\n\n/);
  });
});

// --- Fakes ------------------------------------------------------------------------------------------------------------

class FakeLinear {
  activities: { session: string; content: ActivityContent; ephemeral: boolean }[] = [];
  links: string[] = [];
  started: string[] = [];
  async activity(session: string, content: ActivityContent, ephemeral = false) {
    this.activities.push({ session, content, ephemeral });
  }
  async link(_session: string, url: string) {
    this.links.push(url);
  }
  async markStarted(issueId: string) {
    this.started.push(issueId);
  }
}

const PR: PullRequest = {
  number: 7,
  title: "Add GitHub to people",
  html_url: "https://github.com/ogrodje/metaos/pull/7",
  user: { login: "elysium-og" },
  head: { ref: "elysium/og-85-github", repo: { full_name: "ogrodje/metaos" } },
  base: { repo: { full_name: "ogrodje/metaos" } },
};

class FakeGitHub {
  reactions: { repo: string; on: string; id: number }[] = [];
  comments: { repo: string; number: number; body: string }[] = [];
  async pullRequest() {
    return PR;
  }
  async react(repo: string, on: string, id: number) {
    this.reactions.push({ repo, on, id });
  }
  async comment(repo: string, number: number, body: string) {
    this.comments.push({ repo, number, body });
  }
}

class FakeCanvas {
  target: { name: string; emails: string[]; github: string[]; publicUrl: string };
  constructor(name: string, emails: string[], github: string[], publicUrl: string) {
    this.target = { name, emails, github, publicUrl };
  }
  conversations = new Map<string, { status: string; events: CanvasEvent[]; tags: Record<string, string> }>();
  starts: { id: string; prompt: string; title: string; tags: Record<string, string> }[] = [];
  messages: { id: string; text: string }[] = [];
  interrupts = 0;
  #clock = 0;
  #tick() {
    this.#clock += 1;
    return `2026-11-27T10:00:${String(this.#clock).padStart(2, "0")}`;
  }
  add(id: string, event: Omit<CanvasEvent, "timestamp">) {
    this.conversations.get(id)?.events.push({ ...event, timestamp: this.#tick() });
  }
  say(id: string, eventId: string, text: string) {
    this.add(id, {
      id: eventId,
      kind: "MessageEvent",
      source: "agent",
      llm_message: { role: "assistant", content: [{ type: "text", text }] },
    });
  }
  finish(id: string) {
    this.conversations.get(id)!.status = "finished";
  }
  async conversation(id: string) {
    const c = this.conversations.get(id);
    return c ? { id, execution_status: c.status, tags: c.tags } : null;
  }
  async start(id: string, prompt: string, title: string, tags: Record<string, string>) {
    this.starts.push({ id, prompt, title, tags });
    this.conversations.set(id, { status: "running", events: [], tags });
  }
  async message(id: string, text: string) {
    this.messages.push({ id, text });
    const c = this.conversations.get(id);
    if (c) c.status = "running";
  }
  async interrupt(id: string) {
    this.interrupts += 1;
    const c = this.conversations.get(id);
    if (c) c.status = "paused";
  }
  async tag(id: string, tags: Record<string, string>) {
    const c = this.conversations.get(id);
    if (c) c.tags = { ...c.tags, ...tags };
  }
  async tagged(key: string, value: string) {
    for (const [id, c] of this.conversations) if (c.tags[key] === value) return { id, execution_status: c.status, tags: c.tags };
    return null;
  }
  async events(id: string, since: string | undefined) {
    return (this.conversations.get(id)?.events ?? []).filter((e) => !since || e.timestamp >= since);
  }
  async runningFromBridge() {
    return [];
  }
}

const until = async (check: () => boolean, ms = 2000) => {
  const end = Date.now() + ms;
  while (!check()) {
    if (Date.now() > end) throw new Error("timed out");
    await new Promise((r) => setTimeout(r, 5));
  }
};

function bridge() {
  const linear = new FakeLinear();
  const github = new FakeGitHub();
  const canvases = config.canvases.map((c) => new FakeCanvas(c.name, c.emails, c.github, c.publicUrl));
  const canvas = canvases[1]; // oto's, whom the requests are from
  // @ts-expect-error: the fakes stand in for Linear, Canvas and GitHub
  return { linear, github, canvas, canvases, bridge: new Bridge(config, linear, canvases, github) };
}

describe("a Linear session", () => {
  it("delegated: a thought at once, a conversation under the session's ID, its steps, then its answer", async () => {
    const { linear, canvas, bridge: b } = bridge();
    await b.handle(sessionEvent());
    assert.equal(linear.activities[0].content.type, "thought");
    assert.equal(linear.activities[0].ephemeral, true);
    assert.equal(canvas.starts[0].id, SESSION);
    assert.equal(canvas.starts[0].title, "OG-86: Verify the preview");
    assert.deepEqual(canvas.starts[0].tags, { linearsession: SESSION, linearissue: "og-86" });
    assert.deepEqual(linear.links, [`https://agent-oto.example/canvas/conversations/${SESSION}`]);
    assert.deepEqual(linear.started, ["issue-1"]);

    canvas.add(SESSION, { id: "a1", kind: "ActionEvent", source: "agent", tool_name: "terminal", summary: "terminal: ls" });
    canvas.say(SESSION, "m1", "Opened https://github.com/ogrodje/metaos/pull/5");
    await until(() => linear.activities.some((a) => a.content.type === "action"));
    canvas.finish(SESSION);
    await until(() => linear.activities.at(-1)?.content.type === "response");
    assert.deepEqual(linear.activities.at(-1)?.content, {
      type: "response",
      body: "Opened https://github.com/ogrodje/metaos/pull/5",
    });
    assert.equal(linear.activities.filter((a) => a.content.type === "action").length, 1);
    // So feedback on the pull request finds the conversation
    await until(() => canvas.conversations.get(SESSION)!.tags.githubpr === "ogrodje/metaos#5");
  });

  it("the same event twice is done once", async () => {
    const { canvas, bridge: b } = bridge();
    await b.handle(sessionEvent());
    await b.handle(sessionEvent());
    assert.equal(canvas.starts.length, 1);
    canvas.finish(SESSION);
  });

  it("every session is done, though Linear's webhookId is the same in all of them", async () => {
    const { canvas, bridge: b } = bridge();
    const other = "7c3e0000-1111-4222-8333-944455550000";
    assert.notEqual(linearEventKey(sessionEvent()), linearEventKey(sessionEvent({ agentSession: { ...sessionEvent().agentSession, id: other } })));
    await b.handle(sessionEvent());
    await b.handle(sessionEvent({ agentSession: { ...sessionEvent().agentSession, id: other } }));
    assert.deepEqual(canvas.starts.map((s) => s.id), [SESSION, other]);
    canvas.finish(SESSION);
    canvas.finish(other);
  });

  it("prompted: the reply goes to the same conversation, and only what follows it is reported", async () => {
    const { linear, canvas, bridge: b } = bridge();
    canvas.conversations.set(SESSION, { status: "finished", events: [], tags: { linearsession: SESSION } });
    canvas.add(SESSION, { id: "old", kind: "ActionEvent", source: "agent", tool_name: "terminal", summary: "terminal: old" });
    await b.handle(
      sessionEvent({ action: "prompted", agentActivity: { id: "act-1", content: { type: "prompt", body: "Also add a test" } } }),
    );
    assert.deepEqual(canvas.messages.map((m) => m.text), ["Also add a test"]);
    assert.equal(canvas.starts.length, 0);
    canvas.add(SESSION, { id: "new", kind: "ActionEvent", source: "agent", tool_name: "file_editor", summary: "file_editor: x" });
    await until(() => linear.activities.some((a) => a.content.type === "action"));
    canvas.finish(SESSION);
    await until(() => linear.activities.at(-1)?.content.type === "response");
    const actions = linear.activities.filter((a) => a.content.type === "action").map((a) => a.content);
    assert.deepEqual(actions, [{ type: "action", action: "file_editor", parameter: "x" }]);
  });

  it("stop: the conversation is interrupted and the session told", async () => {
    const { linear, canvas, bridge: b } = bridge();
    await b.handle(sessionEvent());
    await b.handle(sessionEvent({ action: "prompted", agentActivity: { id: "act-2", signal: "stop", content: { type: "prompt" } } }));
    assert.equal(canvas.interrupts, 1);
    await until(() => linear.activities.at(-1)?.content.type === "response");
    assert.deepEqual(linear.activities.at(-1)?.content, { type: "response", body: "Stopped, as asked." });
  });

  it("a failure is reported to the session as an error", async () => {
    const { linear, canvas, bridge: b } = bridge();
    canvas.start = async () => {
      throw new Error("no active agent profile");
    };
    await b.handle(sessionEvent());
    assert.equal(linear.activities.at(-1)?.content.type, "error");
  });

  it("runs in its creator's own Canvas, found by email whatever its case", async () => {
    const { canvases, bridge: b } = bridge();
    const event = sessionEvent({
      agentSession: { ...sessionEvent().agentSession, creator: { id: "u2", name: "Ana", email: "ANA@example.com" } },
    });
    await b.handle(event);
    assert.deepEqual(canvases.map((c) => c.starts.length), [0, 0, 1]);
    canvases[2].finish(SESSION);
  });

  it("someone with no Canvas gets an answer, and nothing runs anywhere, not even in the team's", async () => {
    const { linear, canvases, bridge: b } = bridge();
    const stranger = { id: "u3", name: "Eve", email: "eve@example.com" };
    await b.handle(sessionEvent({ agentSession: { ...sessionEvent().agentSession, creator: stranger } }));
    assert.deepEqual(canvases.map((c) => c.starts.length), [0, 0, 0]);
    assert.equal(linear.activities.length, 1);
    assert.match((linear.activities[0].content as { body: string }).body, /Eve has no Canvas/);
  });
});

// --- GitHub ------------------------------------------------------------------------------------------------------------

const repository = { full_name: "ogrodje/metaos" };

function comment(body: string, login = "otobrglez") {
  return {
    action: "created",
    sender: { login },
    repository,
    issue: { number: 7, pull_request: {} },
    comment: { id: 99, body, html_url: "https://github.com/ogrodje/metaos/pull/7#issuecomment-99" },
  };
}

describe("what on GitHub asks Elysium", () => {
  it("a pull request comment that mentions @elysium-og, as a word", () => {
    const asked = requestOf("issue_comment", "d1", comment("@Elysium-og please rename this"), "elysium-og");
    assert.equal(asked?.kind, "comment");
    assert.equal(asked?.number, 7);
    assert.deepEqual(asked?.reaction, { on: "issue", id: 99 });
    assert.equal(requestOf("issue_comment", "d1", comment("ping @elysium-ogre"), "elysium-og"), null);
    assert.equal(requestOf("issue_comment", "d1", comment("no mention"), "elysium-og"), null);
  });
  it("not on an issue, nor Elysium's own words, nor a bot's", () => {
    const onIssue = { ...comment("@elysium-og"), issue: { number: 7 } };
    assert.equal(requestOf("issue_comment", "d1", onIssue, "elysium-og"), null);
    assert.equal(requestOf("issue_comment", "d1", comment("@elysium-og", "elysium-og"), "elysium-og"), null);
    const bot = { ...comment("@elysium-og"), sender: { login: "dependabot[bot]", type: "Bot" } };
    assert.equal(requestOf("issue_comment", "d1", bot, "elysium-og"), null);
  });
  it("changes requested on a pull request Elysium opened, or a review that mentions it", () => {
    const review = (state: string, body: string, author = "elysium-og") => ({
      action: "submitted",
      sender: { login: "otobrglez" },
      repository,
      pull_request: { number: 7, user: { login: author } },
      review: { state, body, html_url: "https://github.com/ogrodje/metaos/pull/7#pullrequestreview-1" },
    });
    assert.equal(requestOf("pull_request_review", "d2", review("changes_requested", "Rename it"), "elysium-og")?.kind, "review");
    assert.equal(requestOf("pull_request_review", "d2", review("approved", "LGTM"), "elysium-og"), null);
    assert.equal(requestOf("pull_request_review", "d2", review("changes_requested", "x", "someone"), "elysium-og"), null);
    assert.equal(requestOf("pull_request_review", "d2", review("commented", "@elysium-og look", "someone"), "elysium-og")?.kind, "review");
  });
  it("a review comment that mentions it, with its place", () => {
    const inline = {
      action: "created",
      sender: { login: "otobrglez" },
      repository,
      pull_request: { number: 7, user: { login: "someone" } },
      comment: { id: 5, body: "@elysium-og off by one", html_url: "u", path: "src/A.kt", line: 12, diff_hunk: "@@ -1 +1 @@" },
    };
    const asked = requestOf("pull_request_review_comment", "d3", inline, "elysium-og");
    assert.deepEqual([asked?.kind, asked?.path, asked?.line, asked?.reaction?.on], ["review comment", "src/A.kt", 12, "pull"]);
  });
  it("its prompt: the pull request, what was asked, and the branch to push to, or not a fork's", () => {
    const asked = requestOf("issue_comment", "d1", comment("@elysium-og rename x"), "elysium-og") as GitHubRequest;
    assert.match(githubPrompt(asked, PR), /Push to its branch, `elysium\/og-85-github`, on top of what's there/);
    assert.match(githubPrompt(asked, PR), /Never force-push/);
    const fork = { ...PR, head: { ref: "main", repo: { full_name: "someone/metaos" } } };
    assert.match(githubPrompt(asked, fork), /a fork's, which you can't push to/);
    assert.match(githubPrompt(asked, fork), /new branch in ogrodje\/metaos .* takes over #7 and credits its author/);
    assert.match(githubPrompt(asked, PR), /> @elysium-og rename x/);
  });
  it("GitHub's signature", () => {
    const body = '{"a":1}';
    const signature = `sha256=${sign(body, "gh_wh_test")}`;
    assert.equal(verifyGitHub(Buffer.from(body), signature, "gh_wh_test"), true);
    assert.equal(verifyGitHub(Buffer.from(body), sign(body, "gh_wh_test"), "gh_wh_test"), false);
    assert.equal(verifyGitHub(Buffer.from(body), undefined, "gh_wh_test"), false);
  });
});

describe("a request on a pull request", () => {
  const asked = (login = "otobrglez", delivery = "d1") =>
    requestOf("issue_comment", delivery, comment("@elysium-og rename x", login), "elysium-og") as GitHubRequest;

  it("from someone without a Canvas: nothing, not even a reaction (the repositories are public)", async () => {
    const { github, canvases, bridge: b } = bridge();
    await b.handleGitHub(asked("stranger"));
    assert.deepEqual([github.reactions.length, github.comments.length, ...canvases.map((c) => c.starts.length)], [0, 0, 0, 0, 0]);
  });

  it("a new conversation in the asker's Canvas, 👀 at once, and its answer on the pull request", async () => {
    const { github, canvas, bridge: b } = bridge();
    await b.handleGitHub(asked());
    assert.deepEqual(github.reactions, [{ repo: "ogrodje/metaos", on: "issue", id: 99 }]);
    assert.equal(canvas.starts.length, 1);
    assert.deepEqual(canvas.starts[0].tags, { githubpr: "ogrodje/metaos#7", githubauthor: "otobrglez" });
    const id = canvas.starts[0].id;
    canvas.say(id, "m1", "Renamed it in 3f2a1c.");
    canvas.finish(id);
    await until(() => github.comments.length === 1);
    assert.match(github.comments[0].body, /^Renamed it in 3f2a1c\./);
    assert.match(github.comments[0].body, /for @otobrglez/);
    assert.match(github.comments[0].body, new RegExp(`agent-oto\\.example/canvas/conversations/${id}`));
  });

  it("goes on in the conversation the pull request came from, if it's the asker's", async () => {
    const { github, canvas, bridge: b } = bridge();
    canvas.conversations.set(SESSION, { status: "finished", events: [], tags: { linearsession: SESSION, githubpr: "ogrodje/metaos#7" } });
    await b.handleGitHub(asked());
    assert.equal(canvas.starts.length, 0);
    assert.equal(canvas.messages[0].id, SESSION);
    assert.match(canvas.messages[0].text, /Feedback on your pull request ogrodje\/metaos#7/);
    canvas.say(SESSION, "m2", "Done, pushed.");
    canvas.finish(SESSION);
    await until(() => github.comments.length === 1);
    assert.match(github.comments[0].body, /^Done, pushed\./);
  });

  it("the same delivery twice is done once", async () => {
    const { canvas, bridge: b } = bridge();
    await b.handleGitHub(asked("otobrglez", "same"));
    await b.handleGitHub(asked("otobrglez", "same"));
    assert.equal(canvas.starts.length, 1);
    canvas.finish(canvas.starts[0].id);
  });
});

describe("the HTTP endpoints", () => {
  it("answer Linear's and GitHub's signed deliveries at once, refuse anything else", async () => {
    const handled: string[] = [];
    // @ts-expect-error: only handle() and handleGitHub() are used
    const http = server(config, {
      handle: async () => void handled.push("linear"),
      handleGitHub: async () => void handled.push("github"),
    });
    await new Promise<void>((r) => http.listen(0, r));
    const url = `http://127.0.0.1:${(http.address() as AddressInfo).port}`;
    try {
      const body = JSON.stringify(sessionEvent());
      assert.equal((await fetch(`${url}/linear/webhook`, { method: "POST", body, headers: { "linear-signature": sign(body) } })).status, 200);
      const forged = await fetch(`${url}/linear/webhook`, { method: "POST", body, headers: { "linear-signature": sign(body, "x") } });
      assert.equal(forged.status, 401);

      const gh = JSON.stringify(comment("@elysium-og hi"));
      const headers = { "x-hub-signature-256": `sha256=${sign(gh, "gh_wh_test")}`, "x-github-event": "issue_comment", "x-github-delivery": "d9" };
      assert.equal((await fetch(`${url}/github/webhook`, { method: "POST", body: gh, headers })).status, 202);
      const ghForged = { ...headers, "x-hub-signature-256": `sha256=${sign(gh, "x")}` };
      assert.equal((await fetch(`${url}/github/webhook`, { method: "POST", body: gh, headers: ghForged })).status, 401);

      assert.equal((await fetch(`${url}/healthz`)).status, 200);
      assert.equal((await fetch(`${url}/other`)).status, 404);
      await until(() => handled.length === 2);
      assert.deepEqual(handled.sort(), ["github", "linear"]);
    } finally {
      http.close();
    }
  });
});

describe("MetaOS' MCP server, as Elysium's service account", () => {
  /** Keycloak and MetaOS, as far as the bridge sees them: tokens by client credentials, and an MCP server that wants one */
  async function upstreams() {
    const seen = { tokens: 0, mcp: [] as { authorization?: string; session?: string; body: string }[] };
    let valid = "t1";
    const keycloak = createServer((request, response) => {
      let body = "";
      request.on("data", (c) => (body += c)).on("end", () => {
        const form = new URLSearchParams(body);
        if (form.get("grant_type") !== "client_credentials" || form.get("client_secret") !== "s3cret") {
          return response.writeHead(401).end(JSON.stringify({ error: "unauthorized_client" }));
        }
        seen.tokens++;
        response.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ access_token: valid, expires_in: 1800 }));
      });
    });
    const mcp = createServer((request, response) => {
      let body = "";
      request.on("data", (c) => (body += c)).on("end", () => {
        seen.mcp.push({ authorization: request.headers.authorization, session: request.headers["mcp-session-id"] as string, body });
        if (request.headers.authorization !== `Bearer ${valid}`) return response.writeHead(401).end();
        response.writeHead(200, { "Content-Type": "text/event-stream", "Mcp-Session-Id": "s1", "Set-Cookie": "x=y" });
        response.write("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n");
        response.end();
      });
    });
    await Promise.all([keycloak, mcp].map((s) => new Promise<void>((r) => s.listen(0, "127.0.0.1", r))));
    const at = (s: ReturnType<typeof createServer>) => `http://127.0.0.1:${(s.address() as AddressInfo).port}`;
    return {
      seen,
      rotate: (token: string) => void (valid = token),
      metaos: { mcpUrl: `${at(mcp)}/mcp`, tokenUrl: `${at(keycloak)}/token`, clientId: "elysium", clientSecret: "s3cret" },
      close: () => [keycloak, mcp].forEach((s) => s.close()),
    };
  }

  it("passes the agent's request on with a token of its own, not the agent's, and streams the answer back", async () => {
    const up = await upstreams();
    // @ts-expect-error: the bridge isn't used here
    const http = server({ ...config, metaos: up.metaos }, {});
    await new Promise<void>((r) => http.listen(0, r));
    const url = `http://127.0.0.1:${(http.address() as AddressInfo).port}/mcp/metaos`;
    try {
      const call = (headers: Record<string, string> = {}) =>
        fetch(url, {
          method: "POST",
          body: '{"jsonrpc":"2.0","id":1,"method":"tools/list"}',
          headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream", ...headers },
        });

      const first = await call({ Authorization: "Bearer the-agents-own" });
      assert.equal(first.status, 200);
      assert.equal(first.headers.get("mcp-session-id"), "s1");
      assert.equal(first.headers.get("set-cookie"), null);
      assert.match(await first.text(), /"result":\{\}/);
      assert.equal(up.seen.mcp[0].authorization, "Bearer t1");
      assert.equal(up.seen.mcp[0].body, '{"jsonrpc":"2.0","id":1,"method":"tools/list"}');

      // The same token while it lasts, the session passed on
      await (await call({ "Mcp-Session-Id": "s1" })).text();
      assert.equal(up.seen.tokens, 1);
      assert.equal(up.seen.mcp[1].session, "s1");

      // A token MetaOS turns down: a new one, once
      up.rotate("t2");
      assert.equal((await call()).status, 200);
      assert.equal(up.seen.tokens, 2);
      assert.equal(up.seen.mcp.at(-1)?.authorization, "Bearer t2");

      // Not through Cloudflare, and not without MetaOS configured
      assert.equal((await call({ "cf-connecting-ip": "203.0.113.9" })).status, 404);
    } finally {
      http.close();
      up.close();
    }
  });

  it("is off without MetaOS' configuration, and says so when Keycloak won't give a token", async () => {
    // @ts-expect-error: the bridge isn't used here
    const off = server(config, {});
    await new Promise<void>((r) => off.listen(0, r));
    const up = await upstreams();
    // @ts-expect-error: the bridge isn't used here
    const wrong = server(config, {}, new MetaOS({ ...up.metaos, clientSecret: "wrong" }));
    await new Promise<void>((r) => wrong.listen(0, r));
    try {
      assert.equal((await fetch(`http://127.0.0.1:${(off.address() as AddressInfo).port}/mcp/metaos`, { method: "POST", body: "{}" })).status, 404);
      const refused = await fetch(`http://127.0.0.1:${(wrong.address() as AddressInfo).port}/mcp/metaos`, { method: "POST", body: "{}" });
      assert.equal(refused.status, 502);
      assert.equal(up.seen.mcp.length, 0);
    } finally {
      off.close();
      wrong.close();
      up.close();
    }
  });
});

describe("previews", () => {
  const closed = (merged: boolean, head = "ogrodje/metaos", repo = "ogrodje/metaos") => ({
    action: "closed",
    repository: { full_name: repo },
    pull_request: { merged, head: { ref: "elysium/og-1", repo: { full_name: head } } },
  });

  it("a pull request of MetaOS' merged or closed is its branch's preview done with; not a fork's, nor another repository's", () => {
    assert.equal(closedBranch("pull_request", closed(true)), "elysium/og-1");
    assert.equal(closedBranch("pull_request", closed(false)), "elysium/og-1");
    assert.equal(closedBranch("pull_request", { ...closed(true), action: "opened" }), null);
    assert.equal(closedBranch("pull_request", closed(true, "someone/metaos")), null);
    assert.equal(closedBranch("pull_request", closed(true, "ogrodje/goo", "ogrodje/goo")), null);
    assert.equal(closedBranch("issue_comment", closed(true)), null);
  });

  it("the bridge tells the previews' manager, signed deliveries only", async () => {
    const told: { auth?: string; body: string }[] = [];
    const manager = createServer((request, response) => {
      let body = "";
      request.on("data", (c) => (body += c)).on("end", () => {
        told.push({ auth: request.headers.authorization, body });
        response.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ down: [1] }));
      });
    });
    await new Promise<void>((r) => manager.listen(0, "127.0.0.1", r));
    const previews = { url: `http://127.0.0.1:${(manager.address() as AddressInfo).port}`, token: "pt" };
    // @ts-expect-error: the bridge isn't used here
    const http = server({ ...config, previews }, {});
    await new Promise<void>((r) => http.listen(0, r));
    const url = `http://127.0.0.1:${(http.address() as AddressInfo).port}/github/webhook`;
    try {
      const body = JSON.stringify(closed(true));
      const headers = { "x-hub-signature-256": `sha256=${sign(body, "gh_wh_test")}`, "x-github-event": "pull_request", "x-github-delivery": "d1" };
      const answer = await fetch(url, { method: "POST", body, headers });
      assert.equal(answer.status, 202);
      await until(() => told.length === 1);
      assert.deepEqual(told[0], { auth: "Bearer pt", body: '{"branch":"elysium/og-1"}' });
      const forged = await fetch(url, { method: "POST", body, headers: { ...headers, "x-hub-signature-256": "sha256=00" } });
      assert.equal(forged.status, 401);
      assert.equal(told.length, 1);
    } finally {
      http.close();
      manager.close();
    }
  });
});
