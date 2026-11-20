"use strict";
const express = require("express");
const path = require("path");

const { SessionStore, MAX_FINDINGS } = require("./sessions");

const app = express();
app.disable("x-powered-by");
app.set("trust proxy", Number(process.env.TRUST_PROXY || 0));
app.use(express.json({ limit: "1mb" }));

// The dashboard is static files only and runs under a strict CSP, so even if a malicious report
// slipped through sanitising it still could not execute script in the admin's browser.
app.use((req, res, next) => {
  res.set("Content-Security-Policy",
    "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
  res.set("X-Content-Type-Options", "nosniff");
  res.set("Referrer-Policy", "no-referrer");
  res.set("Cache-Control", "no-store");
  next();
});
app.use(express.static(path.join(__dirname, "public")));

const store = new SessionStore();

setInterval(() => {
  store.sweep();
  const now = Date.now();
  for (const [k, v] of hits) {
    if (now > v.reset) hits.delete(k);
  }
}, 60 * 1000).unref();

// --- helpers ---------------------------------------------------------------------------------

const hits = new Map();
function rateLimit(name, max, windowMs) {
  return (req, res, next) => {
    const key = name + ":" + req.ip;
    const now = Date.now();
    let h = hits.get(key);
    if (!h || now > h.reset) {
      h = { count: 0, reset: now + windowMs };
      hits.set(key, h);
    }
    if (++h.count > max) {
      res.set("Retry-After", String(Math.ceil((h.reset - now) / 1000)));
      return res.status(429).json({ error: "too many requests" });
    }
    next();
  };
}

// --- API -------------------------------------------------------------------------------------

// 1. Anyone can create a PIN. The reply carries a secret token that only the creator's browser
//    holds; it is required to read the report, so other people cannot read it by guessing PINs.
app.post("/api/create-pin", rateLimit("create", 10, 60 * 1000), (req, res) => {
  const r = store.create(req.ip, req.body && req.body.admin);
  if (r.error === "busy") return res.status(503).json({ error: "too many open sessions" });
  if (r.error === "too_many") return res.status(429).json({ error: "too many open PINs; use or wait for the earlier ones to expire" });
  res.json(r);
});

// 2. The player app enters the PIN (rate limited: a 6-digit PIN must not be guessable). This starts
//    a 15-minute session and returns a player token; the PIN is not needed again after this.
app.post("/api/start-session/:pin", rateLimit("start", 10, 60 * 1000), (req, res) => {
  const r = store.start(req.params.pin);
  if (r.error) return res.status(404).json({ valid: false });
  res.json({ valid: true, playerToken: r.playerToken, expiresInSeconds: r.expiresInSeconds });
});

// 3. The player app uploads a report (as many as it likes until the session expires). The latest one
//    is what the dashboard shows. The token travels in a header, never in the URL.
app.post("/api/submit-report", rateLimit("submit", 20, 60 * 1000), (req, res) => {
  const r = store.submit(req.get("x-player-token") || "", req.body && req.body.report, req.body && req.body.verdict);
  if (r.error === "expired") return res.status(401).json({ error: "session expired" });
  if (r.error === "invalid") return res.status(401).json({ error: "invalid session" });
  if (r.error) return res.status(400).json({ error: "report must be an array of at most " + MAX_FINDINGS + " findings" });
  res.json({ success: true });
});

// 4. The creator's dashboard polls for the result, proving itself with the admin token
app.get("/api/session/:pin", rateLimit("session", 120, 60 * 1000), (req, res) => {
  const r = store.read(req.params.pin, req.get("x-session-token") || "");
  if (!r) return res.status(404).json({ error: "Not found or expired" });
  res.json(r);
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log("ABBYS Anti-Cheat running on port " + PORT));
