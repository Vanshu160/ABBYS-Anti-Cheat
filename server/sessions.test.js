"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { SessionStore, PLAYER_SESSION_MS, UNCLAIMED_TTL_MS, READ_GRACE_MS } = require("./sessions");

const MIN = 60 * 1000;
function setup() {
  const clock = { t: 1_000_000 };
  const store = new SessionStore(() => clock.t);
  const made = store.create("1.2.3.4", "Mod");
  return { clock, store, ...made };
}
const rep = (n = 1) => Array.from({ length: n }, (_, i) => ({ severity: "warn", title: "t" + i, detail: "d" }));

test("session lasts exactly 15 minutes from first verification", () => {
  const { clock, store, pin } = setup();
  clock.t += 5 * MIN; // the player takes a while to type the PIN
  const s = store.start(pin);
  assert.equal(s.expiresInSeconds, 900);
  assert.equal(s.expiresAt, clock.t + PLAYER_SESSION_MS);
  clock.t += 15 * MIN - 1;
  assert.equal(store.submit(s.playerToken, rep()).success, true);
  clock.t += 1; // exactly 15:00
  assert.equal(store.submit(s.playerToken, rep()).error, "expired");
});

test("unlimited scans in the window and the timer is never extended", () => {
  const { clock, store, pin } = setup();
  const s = store.start(pin);
  for (let i = 0; i < 25; i++) {
    clock.t += 30 * 1000;
    const r = store.submit(s.playerToken, rep(3));
    assert.equal(r.success, true);
    assert.equal(r.expiresAt, s.expiresAt);
  }
  assert.equal(store.submit(s.playerToken, rep()).success, true); // 12.5 min in
  clock.t += 3 * MIN;
  assert.equal(store.submit(s.playerToken, rep()).error, "expired");
});

test("a PIN can be claimed once and a second device gets nothing", () => {
  const { store, pin } = setup();
  assert.ok(store.start(pin).playerToken);
  assert.equal(store.start(pin).error, "invalid");
});

test("bad tokens, bad pins and bad reports are rejected", () => {
  const { store, pin } = setup();
  const s = store.start(pin);
  assert.equal(store.submit("nope", rep()).error, "invalid");
  assert.equal(store.submit(undefined, rep()).error, "invalid");
  assert.equal(store.start("12345").error, "invalid");
  assert.equal(store.start("abcdef").error, "invalid");
  assert.equal(store.submit(s.playerToken, "x").error, "bad_report");
  assert.equal(store.submit(s.playerToken, rep(201)).error, "bad_report");
});

test("unclaimed PIN expires after 30 minutes", () => {
  const { clock, store, pin } = setup();
  clock.t += UNCLAIMED_TTL_MS;
  assert.equal(store.start(pin).error, "invalid");
});

test("admin read needs the admin token, sees latest report and scan count, with a grace after expiry", () => {
  const { clock, store, pin, token } = setup();
  const s = store.start(pin);
  store.submit(s.playerToken, rep(1));
  store.submit(s.playerToken, rep(2));
  assert.equal(store.read(pin, "wrong"), null);
  assert.equal(store.read(pin, s.playerToken), null); // the player token is not an admin token
  let r = store.read(pin, token);
  assert.equal(r.scans, 2);
  assert.equal(r.report.length, 2);
  assert.equal(r.report[0].severity, "WARN");
  assert.equal(r.expired, false);
  assert.equal(r.secondsLeft, 900);
  clock.t += 16 * MIN;
  r = store.read(pin, token);
  assert.equal(r.expired, true);
  assert.equal(r.report.length, 2);
  clock.t += READ_GRACE_MS;
  assert.equal(store.read(pin, token), null);
});

test("sweep removes old sessions and their tokens", () => {
  const { clock, store, pin } = setup();
  const s = store.start(pin);
  clock.t += 15 * MIN + READ_GRACE_MS;
  store.sweep();
  assert.equal(store.sessions.size, 0);
  assert.equal(store.byPlayerToken.size, 0);
  assert.equal(store.submit(s.playerToken, rep()).error, "invalid");
});

test("per-IP limit on open PINs", () => {
  const store = new SessionStore(() => 5);
  for (let i = 0; i < 5; i++) assert.ok(store.create("9.9.9.9", "a").pin);
  assert.equal(store.create("9.9.9.9", "a").error, "too_many");
  assert.ok(store.create("8.8.8.8", "a").pin);
});
