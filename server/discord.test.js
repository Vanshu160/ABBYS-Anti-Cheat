"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { notify, buildPayload, reportText, normalizeUrl, describe } = require("./discord");
const { SessionStore } = require("./sessions");

const URL_OK = "https://discord.com/api/webhooks/123456789/abc-DEF_ghi";

function submitted(verdict) {
  const store = new SessionStore(() => 1000);
  const { pin } = store.create("1.1.1.1", "Mod");
  const s = store.start(pin);
  return store.submit(s.playerToken, [
    { severity: "HIGH", title: "Archive hit @everyone", detail: "d1" },
    { severity: "INFO", title: "t2", detail: "d2" },
  ], verdict);
}
const cheat = {
  level: "CHEAT_DETECTED", summary: "Evidence of wurst was found.",
  detections: [{ client: "wurst", file: "mods/a.jar", evidence: "wurst@path", confidence: "HIGH" }],
  reasons: [], limits: ["Other apps' private folders cannot be read."],
};

test("cheat report: red embed with client, file, evidence, confidence, and no mentions", () => {
  const p = buildPayload(submitted(cheat));
  assert.deepEqual(p.allowed_mentions, { parse: [] });
  const e = p.embeds[0];
  assert.match(e.title, /CHEAT DETECTED - PIN \d{6}/);
  assert.equal(e.color, 0xef4444);
  const det = e.fields.find((f) => f.name === "Client: wurst");
  assert.match(det.value, /mods\/a\.jar/);
  assert.match(det.value, /Confidence: HIGH/);
});

test("suspicious is amber and none is teal and not worded as clean", () => {
  assert.equal(buildPayload(submitted({ level: "SUSPICIOUS_ACTIVITY", summary: "s" })).embeds[0].color, 0xf59e0b);
  const none = buildPayload(submitted({ level: "NO_CHEAT_DETECTED", summary: "not a guarantee" })).embeds[0];
  assert.equal(none.color, 0x14b8a6);
  assert.match(none.title, /NO CHEAT DETECTED/);
});

test("older app without a verdict still posts, marked as having none", () => {
  const e = buildPayload(submitted(undefined)).embeds[0];
  assert.match(e.title, /no verdict/);
});

test("embed stays within Discord limits even with huge input", () => {
  const big = { ...cheat, summary: "x".repeat(5000), reasons: ["y".repeat(500)], limits: ["z".repeat(500)],
    detections: Array.from({ length: 10 }, () => ({ client: "c".repeat(300), file: "f".repeat(300), evidence: "e".repeat(300), confidence: "HIGH" })) };
  const e = buildPayload(submitted(big)).embeds[0];
  assert.ok(e.description.length <= 1000);
  assert.ok(e.fields.length <= 25);
  assert.ok(e.fields.every((f) => f.name.length <= 256 && f.value.length <= 1024));
});

test("full report text includes verdict and every finding", () => {
  const t = reportText(submitted(cheat));
  assert.match(t, /Verdict: CHEAT DETECTED/);
  assert.match(t, /\[HIGH\] Archive hit/);
  assert.match(t, /\[INFO\] t2/);
});

test("notify posts multipart to the webhook and reports success", async () => {
  let seen;
  const ok = await notify(URL_OK, submitted(cheat), async (url, opts) => { seen = { url, opts }; return { ok: true, status: 204 }; });
  assert.equal(ok, true);
  assert.equal(seen.url, URL_OK);
  assert.equal(seen.opts.method, "POST");
  assert.ok(seen.opts.body instanceof FormData);
  assert.ok(seen.opts.body.get("payload_json").includes("CHEAT DETECTED"));
  assert.match(seen.opts.body.get("files[0]").name, /^abyss-report-\d{6}-scan1\.txt$/);
});

test("notify does nothing without a valid discord webhook URL and never throws", async () => {
  const r = submitted(cheat);
  const boom = async () => { throw new Error("must not be called"); };
  assert.equal(await notify(undefined, r, boom), false);
  assert.equal(await notify("", r, boom), false);
  assert.equal(await notify("http://discord.com/api/webhooks/1/a", r, boom), false);
  assert.equal(await notify("https://evil.example/api/webhooks/1/a", r, boom), false);
  assert.equal(await notify("https://discord.com.evil.example/api/webhooks/1/a", r, boom), false);
  assert.equal(await notify(URL_OK, r, async () => { throw new Error("network down"); }), false);
  assert.equal(await notify(URL_OK, r, async () => ({ ok: false, status: 429 })), false);
});

test("a pasted URL with trailing newline, spaces or quotes still works", async () => {
  assert.equal(normalizeUrl(" " + URL_OK + "\n"), URL_OK);
  assert.equal(normalizeUrl('"' + URL_OK + '"'), URL_OK);
  for (const raw of [URL_OK + "\n", " " + URL_OK + " ", '"' + URL_OK + '"']) {
    let called = null;
    const ok = await notify(raw, submitted(cheat), async (url) => { called = url; return { ok: true, status: 204 }; });
    assert.equal(ok, true);
    assert.equal(called, URL_OK);
  }
});

test("describe reports setup state and last result without leaking the URL", async () => {
  assert.deepEqual([describe(undefined).configured, describe(undefined).valid], [false, false]);
  assert.deepEqual([describe("nonsense").configured, describe("nonsense").valid], [true, false]);
  assert.deepEqual([describe(URL_OK + "\n").configured, describe(URL_OK + "\n").valid], [true, true]);
  await notify(URL_OK, submitted(cheat), async () => ({ ok: false, status: 404 }));
  const d = describe(URL_OK);
  assert.match(d.lastResult, /HTTP 404/);
  assert.ok(!JSON.stringify(d).includes("webhooks"));
});
