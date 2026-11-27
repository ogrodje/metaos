// The previews' manager (manager.ts), with Kubernetes as a fake: `node --test elysium/previews/`.
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable } from "node:stream";
import { afterEach, beforeEach, describe, it } from "node:test";
import { podState, PreviewError, Previews, server } from "./manager.ts";
import type { Config, Kube, Pod, SlotState } from "./manager.ts";

class FakeKube implements Kube {
  replicas: Record<number, number> = {};
  builds: Record<number, string | undefined> = {};
  states: Record<number, SlotState> = {};
  calls: string[] = [];

  async scale(n: number, replicas: number, build?: string) {
    this.calls.push(`scale ${n} ${replicas}${build ? " build" : ""}`);
    this.replicas[n] = replicas;
    if (build) this.builds[n] = build;
  }
  async stopped(n: number) {
    this.calls.push(`stopped ${n}`);
  }
  async state(n: number, build: string) {
    if (this.builds[n] !== build) return { state: "starting" as SlotState, detail: "no pod yet" };
    return { state: this.states[n] ?? ("starting" as SlotState) };
  }
  async logs(n: number, container: string) {
    return new Response(`slot ${n} ${container}\n`);
  }
}

const build = (text = "a build") => Readable.from([Buffer.from(text)]);

let dir: string;
let config: Config;
let kube: FakeKube;
let previews: Previews;

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), "previews-"));
  config = {
    port: 0,
    token: "t0ken",
    dir,
    slots: [1, 2].map((n) => ({ n, url: `https://preview-${n}.ogrodje.dev`, internal: `http://preview-${n}:3000` })),
    idleHours: 24,
    maxUpload: 1024,
  };
  kube = new FakeKube();
  previews = new Previews(config, kube);
  await previews.load();
});

afterEach(() => rm(dir, { recursive: true, force: true }));

describe("a slot", () => {
  it("someone new gets a free one, started fresh: its build in place, its database to be seeded, its pod", async () => {
    const { n, fresh } = previews.claim("nuc:/w/a", "elysium/og-1", "abc");
    assert.deepEqual({ n, fresh }, { n: 1, fresh: true });
    const view = await previews.up(n, build(), fresh, "elysium/og-1", "abc");
    assert.equal(view.url, "https://preview-1.ogrodje.dev");
    assert.equal(await readFile(join(dir, "slot-1/build/build.tar.gz"), "utf8"), "a build");
    assert.ok(existsSync(join(dir, "slot-1/data/seed")));
    assert.deepEqual(kube.calls, ["scale 1 0", "stopped 1", "scale 1 1 build"]);
    assert.equal(kube.replicas[1], 1);
    const leases = JSON.parse(await readFile(join(dir, "leases.json"), "utf8"));
    assert.equal(leases[1].owner, "nuc:/w/a");
  });

  it("its owner again: the same slot, the new build, its data kept", async () => {
    let c = previews.claim("w/a", "b", "1");
    await previews.up(c.n, build("one"), c.fresh, "b", "1");
    const first = kube.builds[1];
    await rm(join(dir, "slot-1/data/seed"));
    kube.calls = [];

    c = previews.claim("w/a", "b", "2");
    assert.deepEqual(c, { n: 1, fresh: false });
    await previews.up(c.n, build("two"), c.fresh, "b", "2");
    assert.equal(await readFile(join(dir, "slot-1/build/build.tar.gz"), "utf8"), "two");
    assert.ok(!existsSync(join(dir, "slot-1/data/seed")), "not seeded again");
    assert.deepEqual(kube.calls, ["scale 1 1 build"]);
    assert.notEqual(kube.builds[1], first);
  });

  it("--fresh starts its data over", async () => {
    const c = previews.claim("w/a", "b", "1");
    await previews.up(c.n, build(), c.fresh, "b", "1");
    await rm(join(dir, "slot-1/data/seed"));
    await previews.up(1, build(), true, "b", "1");
    assert.ok(existsSync(join(dir, "slot-1/data/seed")));
  });

  it("when every slot is taken, says whose they are; one asked for that's taken, whose it is", async () => {
    for (const owner of ["w/a", "w/b"]) {
      const c = previews.claim(owner, owner, "1");
      await previews.up(c.n, build(), c.fresh, owner, "1");
    }
    assert.throws(() => previews.claim("w/c", "c", "1"), (e: PreviewError) => e.status === 409 && /slot 1 w\/a/.test(e.message));
    assert.throws(() => previews.claim("w/c", "c", "1", 2), /slot 2 is slot 2 w\/b/);
    assert.throws(() => previews.claim("w/a", "a", "1", 2), /has slot 1 already/);
    assert.throws(() => previews.claim("w/a", "a", "1", 9), /no slot 9/);
  });

  it("a build too big is turned down, and a claim whose first build never arrived is no one's", async () => {
    const c = previews.claim("w/a", "b", "1");
    await assert.rejects(previews.up(c.n, build("x".repeat(2048)), c.fresh, "b", "1"), /over/);
    assert.ok(!existsSync(join(dir, "slot-1/build/build.tar.gz.part")));
    assert.equal(previews.ownSlot("w/a"), undefined);
    assert.deepEqual(previews.claim("w/b", "b", "1"), { n: 1, fresh: true });
  });

  it("down: stopped and cleared, and free", async () => {
    const c = previews.claim("w/a", "b", "1");
    await previews.up(c.n, build(), c.fresh, "b", "1");
    await previews.down(1, "asked");
    assert.equal(kube.replicas[1], 0);
    assert.ok(!existsSync(join(dir, "slot-1")));
    assert.equal((await previews.list())[0].state, "free");
  });

  it("is done when nobody updated it for a day, or its branch's pull request closed", async () => {
    for (const [owner, branch] of [["w/a", "elysium/og-1"], ["w/b", "elysium/og-2"]]) {
      const c = previews.claim(owner, branch, "1");
      await previews.up(c.n, build(), c.fresh, branch, "1");
    }
    assert.deepEqual(await previews.reap(Date.now() + 23 * 3_600_000), []);
    assert.deepEqual(await previews.closed("elysium/og-2"), [2]);
    assert.deepEqual(await previews.reap(Date.now() + 25 * 3_600_000), [1]);
    assert.deepEqual((await previews.list()).map((s) => s.state), ["free", "free"]);
  });

  it("its leases outlast a restart", async () => {
    const c = previews.claim("w/a", "b", "1");
    await previews.up(c.n, build(), c.fresh, "b", "1");
    const again = new Previews(config, kube);
    await again.load();
    assert.equal(again.ownSlot("w/a"), 1);
  });
});

describe("how a pod is doing", () => {
  const pod = (status: Pod["status"]): Pod => ({ metadata: { name: "p", creationTimestamp: "" }, status });

  it("up once Kubernetes says it's ready", () => {
    assert.deepEqual(podState(pod({ conditions: [{ type: "Ready", status: "True" }] })), { state: "up" });
  });

  it("starting while a container isn't ready, naming it", () => {
    const s = podState(pod({ containerStatuses: [{ name: "api", ready: false, state: { waiting: { reason: "ContainerCreating" } } }] }));
    assert.deepEqual(s, { state: "starting", detail: "waiting on api" });
  });

  it("failed once a container can't start, or keeps exiting", () => {
    assert.deepEqual(podState(pod({ containerStatuses: [{ name: "api", state: { waiting: { reason: "CrashLoopBackOff" } } }] })), {
      state: "failed",
      detail: "api: CrashLoopBackOff",
    });
    const exiting = pod({
      initContainerStatuses: [{ name: "unpack", restartCount: 2, state: { terminated: { exitCode: 1, reason: "Error" } } }],
    });
    assert.deepEqual(podState(exiting), { state: "failed", detail: "unpack: exited 1 (Error)" });
  });
});

describe("its HTTP API", () => {
  it("wants the token; takes a build, lists, gives logs, takes a closed pull request and a down", async () => {
    const http = server(config, previews);
    await new Promise<void>((r) => http.listen(0, r));
    const url = `http://127.0.0.1:${(http.address() as AddressInfo).port}`;
    const auth = { Authorization: "Bearer t0ken" };
    try {
      assert.equal((await fetch(`${url}/healthz`)).status, 200);
      assert.equal((await fetch(`${url}/slots`)).status, 401);
      assert.equal((await fetch(`${url}/slots`, { headers: { Authorization: "Bearer nope!" } })).status, 401);

      const put = await fetch(`${url}/slots?owner=${encodeURIComponent("nuc:/w/a")}&branch=elysium%2Fog-1&commit=abc`, {
        method: "PUT",
        headers: { ...auth, "Content-Type": "application/gzip" },
        body: "a build",
      });
      assert.equal(put.status, 202);
      assert.equal((await put.json()).slot, 1);

      kube.states[1] = "up";
      const list = await (await fetch(`${url}/slots`, { headers: auth })).json();
      assert.deepEqual(list.map((s: { state: string }) => s.state), ["up", "free"]);
      assert.equal(list[0].branch, "elysium/og-1");

      assert.equal(await (await fetch(`${url}/slots/1/logs?container=web`, { headers: auth })).text(), "slot 1 web\n");
      assert.equal((await fetch(`${url}/slots/1/logs?container=sh`, { headers: auth })).status, 400);

      const tooBig = await fetch(`${url}/slots?owner=w%2Fb&branch=x&commit=1`, { method: "PUT", headers: auth, body: "x".repeat(4096) });
      assert.equal(tooBig.status, 413);

      const closed = await fetch(`${url}/closed`, { method: "POST", headers: auth, body: JSON.stringify({ branch: "elysium/og-1" }) });
      assert.deepEqual(await closed.json(), { down: [1] });

      const again = await fetch(`${url}/slots?owner=w%2Fa&branch=b&commit=1`, { method: "PUT", headers: auth, body: "b" });
      assert.equal((await again.json()).slot, 1);
      assert.equal((await fetch(`${url}/slots?owner=w%2Fa`, { method: "DELETE", headers: auth })).status, 200);
      assert.equal((await fetch(`${url}/slots?owner=w%2Fa`, { method: "DELETE", headers: auth })).status, 404);
    } finally {
      http.close();
    }
  });
});
