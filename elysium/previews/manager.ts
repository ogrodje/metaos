// Elysium's previews (infra/elysium/README.md, "Previews"): MetaOS as an agent built it, running at
// https://preview-N.ogrodje.dev for the team to try out before it's merged, behind Cloudflare Access. This is what hands
// out the slots: an agent's preview/preview.sh, in its Canvas, uploads its build (the boot jar, the front-end's
// .output and seed.sql, as a .tar.gz) and the manager puts it into a free slot, or the one that agent already has, and
// starts the slot's pod, which runs it with a Postgres of its own (infra/elysium/factory/previews.tf).
//
// A slot is whose it is until it's done with, which is any of: `preview down`; its branch's pull request closed (the
// bridge says so, POST /closed); nobody updating it for IDLE_HOURS (24). Done is stopped and cleared: its pod scaled to
// nothing, its build and its database deleted. An update keeps its data; --fresh, and a slot someone new takes, start
// it over from seed.sql.
//
// What it may do in Kubernetes is only that: scale the slots' Deployments, which Terraform made, and read their pods
// and logs (a Role in namespace previews); it starts nothing else. The leases are a JSON file on its volume, beside the
// slots' builds and databases. Only the Canvases and the bridge reach it (NetworkPolicy), with PREVIEWS_TOKEN. One file,
// Node's own modules only, run as TypeScript by Node itself, from a ConfigMap, as the bridge is.

import { randomBytes, timingSafeEqual } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import type { IncomingMessage, Server, ServerResponse } from "node:http";
import { join } from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";

// --- Configuration ---------------------------------------------------------------------------------------------------

export interface Slot {
  n: number;
  /** Where people open it, e.g. https://preview-1.ogrodje.dev */
  url: string;
  /** Its front-end inside the cluster, for the agent's own checks, e.g. http://preview-1.previews.svc.cluster.local:3000 */
  internal: string;
}

export interface Config {
  port: number;
  token: string;
  /** The volume: slot-N/build/build.tar.gz, slot-N/data (its Postgres' and the seed marker), leases.json */
  dir: string;
  slots: Slot[];
  idleHours: number;
  /** The largest build, in bytes */
  maxUpload: number;
}

export function configFromEnv(env: NodeJS.ProcessEnv): Config {
  const required = (name: string) => {
    const value = env[name];
    if (!value) throw new Error(`${name} is not set`);
    return value;
  };
  return {
    port: Number(env.PORT ?? 8080),
    token: required("PREVIEWS_TOKEN"),
    dir: env.PREVIEWS_DIR ?? "/previews",
    slots: JSON.parse(required("SLOTS")),
    idleHours: Number(env.IDLE_HOURS ?? 24),
    maxUpload: Number(env.MAX_UPLOAD_MB ?? 1024) * 1024 * 1024,
  };
}

function log(message: string, fields: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ time: new Date().toISOString(), message, ...fields }));
}

// --- Kubernetes ------------------------------------------------------------------------------------------------------

export type SlotState = "free" | "stopped" | "starting" | "up" | "failed";

export interface Kube {
  /** Slot n's Deployment (preview-n) to this many pods; with a build, a new pod for it */
  scale(n: number, replicas: number, build?: string): Promise<void>;
  /** Until slot n has no pod left */
  stopped(n: number): Promise<void>;
  /** How slot n's pod for this build is doing */
  state(n: number, build: string): Promise<{ state: SlotState; detail?: string }>;
  /** Its container's log, as Kubernetes streams it */
  logs(n: number, container: string, follow: boolean, tail: number): Promise<Response>;
}

/** Kubernetes' API as the pod's service account, whose Role (previews.tf) allows only this */
export class KubeApi implements Kube {
  #namespace: string;
  #base = "https://kubernetes.default.svc";
  #tokenFile = "/var/run/secrets/kubernetes.io/serviceaccount/token";

  constructor(namespace: string) {
    this.#namespace = namespace;
  }

  async #call(path: string, init: RequestInit = {}): Promise<Response> {
    // Read each time: Kubernetes rotates the projected token
    const token = (await readFile(this.#tokenFile, "utf8")).trim();
    const headers = { Authorization: `Bearer ${token}`, ...(init.headers as Record<string, string>) };
    const response = await fetch(`${this.#base}${path}`, { ...init, headers });
    if (!response.ok) throw new Error(`Kubernetes ${init.method ?? "GET"} ${path}: ${response.status} ${await response.text()}`);
    return response;
  }

  #pods(n: number) {
    const selector = encodeURIComponent(`app.kubernetes.io/name=preview,ogrodje.si/slot=${n}`);
    return this.#call(`/api/v1/namespaces/${this.#namespace}/pods?labelSelector=${selector}`)
      .then((r) => r.json() as Promise<{ items: Pod[] }>)
      .then((list) => list.items);
  }

  async scale(n: number, replicas: number, build?: string): Promise<void> {
    const patch: Record<string, unknown> = { spec: { replicas } };
    if (build) (patch.spec as Record<string, unknown>).template = { metadata: { annotations: { "ogrodje.si/build": build } } };
    await this.#call(`/apis/apps/v1/namespaces/${this.#namespace}/deployments/preview-${n}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/merge-patch+json" },
      body: JSON.stringify(patch),
    });
  }

  async stopped(n: number): Promise<void> {
    for (let i = 0; i < 90; i++) {
      if ((await this.#pods(n)).length === 0) return;
      await new Promise((r) => setTimeout(r, 1000));
    }
    throw new Error(`slot ${n}'s pod didn't stop`);
  }

  async state(n: number, build: string): Promise<{ state: SlotState; detail?: string }> {
    const pods = (await this.#pods(n)).filter((p) => p.metadata.annotations?.["ogrodje.si/build"] === build);
    if (pods.length === 0) return { state: "starting", detail: "no pod yet" };
    return podState(pods[0]);
  }

  async logs(n: number, container: string, follow: boolean, tail: number): Promise<Response> {
    const pods = (await this.#pods(n)).sort((a, b) => b.metadata.creationTimestamp.localeCompare(a.metadata.creationTimestamp));
    if (pods.length === 0) throw new Error(`slot ${n} has no pod`);
    const query = new URLSearchParams({ container, follow: String(follow), tailLines: String(tail) });
    return this.#call(`/api/v1/namespaces/${this.#namespace}/pods/${pods[0].metadata.name}/log?${query}`);
  }
}

export interface Pod {
  metadata: { name: string; creationTimestamp: string; annotations?: Record<string, string> };
  status?: {
    phase?: string;
    conditions?: { type: string; status: string }[];
    initContainerStatuses?: ContainerStatus[];
    containerStatuses?: ContainerStatus[];
  };
}

interface ContainerStatus {
  name: string;
  ready?: boolean;
  restartCount?: number;
  state?: { waiting?: { reason?: string }; terminated?: { exitCode?: number; reason?: string } };
  lastState?: { terminated?: { exitCode?: number; reason?: string } };
}

const FAILING = ["CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "CreateContainerConfigError", "RunContainerError"];

/** Up once Kubernetes says it's ready; failed once a container can't start or keeps exiting */
export function podState(pod: Pod): { state: SlotState; detail?: string } {
  const statuses = [...(pod.status?.initContainerStatuses ?? []), ...(pod.status?.containerStatuses ?? [])];
  for (const s of statuses) {
    const waiting = s.state?.waiting?.reason;
    if (waiting && FAILING.includes(waiting)) return { state: "failed", detail: `${s.name}: ${waiting}` };
    const exited = s.state?.terminated ?? (s.restartCount ? s.lastState?.terminated : undefined);
    if (exited && exited.exitCode && exited.exitCode !== 0 && (s.restartCount ?? 0) >= 2) {
      return { state: "failed", detail: `${s.name}: exited ${exited.exitCode} (${exited.reason ?? "error"})` };
    }
  }
  if (pod.status?.conditions?.some((c) => c.type === "Ready" && c.status === "True")) return { state: "up" };
  const waitingOn = statuses.find((s) => !s.ready)?.name;
  return { state: "starting", detail: waitingOn ? `waiting on ${waitingOn}` : pod.status?.phase };
}

// --- The slots -------------------------------------------------------------------------------------------------------

export interface Lease {
  /** Whose: the worktree, as its preview.sh names it */
  owner: string;
  branch: string;
  commit: string;
  /** When it was last updated, in ms */
  updated: number;
  /** Its last build, which its pod is annotated with */
  build?: string;
}

export class Previews {
  #config: Config;
  #kube: Kube;
  #leases: Record<string, Lease> = {};
  /** A slot being changed, so two requests don't change it at once */
  #busy = new Set<number>();

  constructor(config: Config, kube: Kube) {
    this.#config = config;
    this.#kube = kube;
  }

  async load(): Promise<void> {
    try {
      this.#leases = JSON.parse(await readFile(join(this.#config.dir, "leases.json"), "utf8"));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }

  async #save(): Promise<void> {
    const file = join(this.#config.dir, "leases.json");
    await writeFile(`${file}.part`, JSON.stringify(this.#leases, null, 2));
    await rename(`${file}.part`, file);
  }

  #slot(n: number): Slot {
    const slot = this.#config.slots.find((s) => s.n === n);
    if (!slot) throw new PreviewError(404, `there's no slot ${n}`);
    return slot;
  }

  #dir(n: number, ...rest: string[]) {
    return join(this.#config.dir, `slot-${n}`, ...rest);
  }

  /** The slots, whose they are, and how their previews are doing */
  async list(): Promise<SlotView[]> {
    return Promise.all(
      this.#config.slots.map(async (slot) => {
        const lease = this.#leases[slot.n];
        if (!lease) return { slot: slot.n, url: slot.url, internal: slot.internal, state: "free" as SlotState };
        let state: { state: SlotState; detail?: string } = { state: "stopped" };
        if (lease.build) {
          state = await this.#kube.state(slot.n, lease.build).catch((e) => ({ state: "failed" as SlotState, detail: String(e) }));
        }
        return { slot: slot.n, url: slot.url, internal: slot.internal, ...state, ...lease };
      }),
    );
  }

  /** The slot this owner has, else the one asked for, else a free one: claimed at once, before the build arrives */
  claim(owner: string, branch: string, commit: string, want?: number): { n: number; fresh: boolean } {
    const own = this.#config.slots.find((s) => this.#leases[s.n]?.owner === owner)?.n;
    if (want !== undefined) this.#slot(want);
    if (own !== undefined && (want === undefined || want === own)) {
      return { n: own, fresh: false };
    }
    if (own !== undefined) throw new PreviewError(409, `this worktree has slot ${own} already; \`preview down\` first`);
    const n = want ?? this.#config.slots.find((s) => !this.#leases[s.n])?.n;
    if (n === undefined) throw new PreviewError(409, `every slot is taken: ${this.#whose()}`);
    if (this.#leases[n]) throw new PreviewError(409, `slot ${n} is ${this.#whose(n)}`);
    this.#leases[n] = { owner, branch, commit, updated: Date.now() };
    return { n, fresh: true };
  }

  #whose(only?: number): string {
    return Object.entries(this.#leases)
      .filter(([n]) => only === undefined || Number(n) === only)
      .map(([n, l]) => `slot ${n} ${l.branch} (${l.owner}, updated ${new Date(l.updated).toISOString()})`)
      .join("; ");
  }

  /**
   * A build into slot n, from the stream: the old preview runs until it's all there, so a failed upload leaves it as it
   * was. Fresh: its database is started over, and seeded once MetaOS has made its schema.
   */
  async up(n: number, build: Readable, fresh: boolean, branch: string, commit: string): Promise<SlotView> {
    if (this.#busy.has(n)) throw new PreviewError(409, `slot ${n} is being changed; try again in a moment`);
    this.#busy.add(n);
    try {
      await mkdir(this.#dir(n, "build"), { recursive: true });
      const part = this.#dir(n, "build", "build.tar.gz.part");
      await pipeline(build, limit(this.#config.maxUpload), createWriteStream(part));

      if (fresh) {
        await this.#kube.scale(n, 0);
        await this.#kube.stopped(n);
        await rm(this.#dir(n, "data"), { recursive: true, force: true });
        await mkdir(this.#dir(n, "data"), { recursive: true });
        // preview.sh's seed sidecar runs preview/seed.sql once MetaOS is ready, then takes this away
        await writeFile(this.#dir(n, "data", "seed"), "");
      }
      await rename(part, this.#dir(n, "build", "build.tar.gz"));

      const id = `${Date.now().toString(36)}-${randomBytes(3).toString("hex")}`;
      const lease = this.#leases[n];
      Object.assign(lease, { branch, commit, updated: Date.now(), build: id });
      await this.#save();
      await this.#kube.scale(n, 1, id);
      log("up", { slot: n, branch, commit, fresh, build: id });
      return { slot: n, url: this.#slot(n).url, internal: this.#slot(n).internal, state: "starting", ...lease };
    } catch (error) {
      await rm(this.#dir(n, "build", "build.tar.gz.part"), { force: true });
      // A claim whose first build never arrived is no one's
      if (!this.#leases[n]?.build) delete this.#leases[n];
      await this.#save();
      throw error;
    } finally {
      this.#busy.delete(n);
    }
  }

  /** Stopped and cleared: no pod, no build, no data, no one's */
  async down(n: number, why: string): Promise<void> {
    this.#slot(n);
    if (this.#busy.has(n)) throw new PreviewError(409, `slot ${n} is being changed; try again in a moment`);
    this.#busy.add(n);
    try {
      await this.#kube.scale(n, 0);
      await this.#kube.stopped(n);
      await rm(this.#dir(n), { recursive: true, force: true });
      const lease = this.#leases[n];
      delete this.#leases[n];
      await this.#save();
      log("down", { slot: n, why, branch: lease?.branch, owner: lease?.owner });
    } finally {
      this.#busy.delete(n);
    }
  }

  ownSlot(owner: string): number | undefined {
    return this.#config.slots.find((s) => this.#leases[s.n]?.owner === owner)?.n;
  }

  /** Those nobody updated for idleHours */
  async reap(now = Date.now()): Promise<number[]> {
    const idle = Object.entries(this.#leases)
      .filter(([, l]) => now - l.updated > this.#config.idleHours * 3_600_000)
      .map(([n]) => Number(n));
    for (const n of idle) await this.down(n, `not updated for ${this.#config.idleHours}h`);
    return idle;
  }

  /** Its branch's pull request was merged or closed: the work is done */
  async closed(branch: string): Promise<number[]> {
    const done = Object.entries(this.#leases)
      .filter(([, l]) => l.branch === branch)
      .map(([n]) => Number(n));
    for (const n of done) await this.down(n, `pull request of ${branch} closed`);
    return done;
  }

  logs(n: number, container: string, follow: boolean, tail: number): Promise<Response> {
    this.#slot(n);
    if (!this.#leases[n]) throw new PreviewError(404, `slot ${n} is free`);
    return this.#kube.logs(n, container, follow, tail);
  }
}

export type SlotView = { slot: number; url: string; internal: string; state: SlotState; detail?: string } & Partial<Lease>;

export class PreviewError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function limit(max: number): Transform {
  let size = 0;
  return new Transform({
    transform(chunk: Buffer, _encoding, done) {
      size += chunk.length;
      if (size > max) done(new PreviewError(413, `the build is over ${Math.round(max / 1048576)} MB`));
      else done(null, chunk);
    },
  });
}

// --- HTTP ------------------------------------------------------------------------------------------------------------
//
//   GET    /slots                                  the slots, whose and how they're doing
//   PUT    /slots?owner=&branch=&commit=[&fresh=1][&slot=N]
//                                                  a build (.tar.gz) into the owner's slot, or a free one; 202
//   DELETE /slots/N  or  /slots?owner=            done: stopped and cleared
//   GET    /slots/N/logs?container=api|web|postgres|seed[&follow=1][&tail=200]
//   POST   /closed {"branch"}                      from the bridge: its pull request is merged or closed
//
// Each with Authorization: Bearer PREVIEWS_TOKEN.

const CONTAINERS = ["api", "web", "postgres", "seed", "unpack"];

function authorized(request: IncomingMessage, token: string): boolean {
  const given = Buffer.from((request.headers.authorization ?? "").replace(/^Bearer /, ""));
  const expected = Buffer.from(token);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

async function json(request: IncomingMessage): Promise<Record<string, unknown>> {
  let body = "";
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 65536) throw new PreviewError(413, "too large");
  }
  try {
    return JSON.parse(body || "{}");
  } catch {
    throw new PreviewError(400, "not JSON");
  }
}

export function server(config: Config, previews: Previews): Server {
  return createServer(async (request: IncomingMessage, response: ServerResponse) => {
    const reply = (status: number, body: unknown) =>
      response.headersSent
        ? response.end()
        : response.writeHead(status, { "Content-Type": "application/json" }).end(JSON.stringify(body));
    const url = new URL(request.url ?? "/", "http://previews");
    if (request.method === "GET" && url.pathname === "/healthz") return reply(200, { ok: true });
    if (!authorized(request, config.token)) return reply(401, { error: "PREVIEWS_TOKEN, as Authorization: Bearer" });

    try {
      const slotPath = url.pathname.match(/^\/slots\/(\d+)(\/logs)?$/);
      if (request.method === "GET" && url.pathname === "/slots") return reply(200, await previews.list());

      if (request.method === "PUT" && url.pathname === "/slots") {
        const owner = url.searchParams.get("owner");
        const branch = url.searchParams.get("branch") ?? "?";
        const commit = url.searchParams.get("commit") ?? "?";
        if (!owner) throw new PreviewError(400, "owner: the worktree");
        const want = url.searchParams.get("slot");
        const claimed = previews.claim(owner, branch, commit, want ? Number(want) : undefined);
        const fresh = claimed.fresh || url.searchParams.get("fresh") === "1";
        return reply(202, await previews.up(claimed.n, request, fresh, branch, commit));
      }

      if (request.method === "DELETE" && (slotPath && !slotPath[2] || url.pathname === "/slots")) {
        const owner = url.searchParams.get("owner");
        const n = slotPath ? Number(slotPath[1]) : owner ? previews.ownSlot(owner) : undefined;
        if (n === undefined) throw new PreviewError(404, "this worktree has no slot");
        await previews.down(n, "asked");
        return reply(200, { slot: n, state: "free" });
      }

      if (request.method === "GET" && slotPath?.[2]) {
        const container = url.searchParams.get("container") ?? "api";
        if (!CONTAINERS.includes(container)) throw new PreviewError(400, `container: one of ${CONTAINERS.join(", ")}`);
        const follow = url.searchParams.get("follow") === "1";
        const tail = Number(url.searchParams.get("tail") ?? 200);
        const logs = await previews.logs(Number(slotPath[1]), container, follow, tail);
        response.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
        if (logs.body) await pipeline(Readable.fromWeb(logs.body as never), response);
        else response.end();
        return;
      }

      if (request.method === "POST" && url.pathname === "/closed") {
        const { branch } = await json(request);
        if (typeof branch !== "string" || !branch) throw new PreviewError(400, "branch");
        return reply(200, { down: await previews.closed(branch) });
      }

      return reply(404, { error: "not found" });
    } catch (error) {
      const status = error instanceof PreviewError ? error.status : 500;
      if (status === 500) log("failed", { method: request.method, path: url.pathname, error: String(error) });
      // An upload turned down part way: what's left of it isn't read
      if (request.method === "PUT") request.resume();
      return reply(status, { error: error instanceof Error ? error.message : String(error) });
    }
  });
}

if (import.meta.main) {
  const config = configFromEnv(process.env);
  const previews = new Previews(config, new KubeApi(process.env.NAMESPACE ?? "previews"));
  await previews.load();
  const http = server(config, previews);
  http.listen(config.port, () => log("listening", { port: config.port, slots: config.slots.length }));
  const reap = () => previews.reap().catch((e) => log("reap failed", { error: String(e) }));
  void reap();
  setInterval(reap, 10 * 60_000);
  process.on("SIGTERM", () => http.close(() => process.exit(0)));
}
