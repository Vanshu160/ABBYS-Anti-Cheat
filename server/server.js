"use strict";
const express = require("express");
const crypto = require("crypto");
const path = require("path");

const SESSION_TTL_MS = 30 * 60 * 1000;
const MAX_SESSIONS = 1000;
const MAX_SESSIONS_PER_IP = 5;
const MAX_FINDINGS = 200;
const SEVERITIES = new Set(["HIGH", "WARN", "INFO", "CLEAN"]);

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

/** pin -> { pin, token, ip, admin, status: PENDING|COMPLETED, report, createdAt } */
const sessions = new Map();

setInterval(() => {
  const now = Date.now();
  for (const [pin, s] of sessions) {
    if (now - s.createdAt > SESSION_TTL_MS) sessions.delete(pin);
  }
  for (const [k, v] of hits) {
    if (now > v.reset) hits.delete(k);
  }
}, 60 * 1000).unref();

// --- helpers ---------------------------------------------------------------------------------

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

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

function liveSession(pin) {
  if (typeof pin !== "string" || !/^\d{6}$/.test(pin)) return null;
  const s = sessions.get(pin);
  if (!s) return null;
  if (Date.now() - s.createdAt > SESSION_TTL_MS) {
    sessions.delete(pin);
    return null;
  }
  return s;
}

function clean(value, max) {
  // plain text only, no control characters; the dashboard also renders via textContent
  return String(value == null ? "" : value).replace(/[\u0000-\u001f\u007f]/g, " ").slice(0, max);
}

function sanitizeReport(report) {
  if (!Array.isArray(report) || report.length > MAX_FINDINGS) return null;
  return report.map((item) => {
    const sev = item && typeof item.severity === "string" ? item.severity.toUpperCase() : "INFO";
    return {
      severity: SEVERITIES.has(sev) ? sev : "INFO",
      title: clean(item && item.title, 160),
      detail: clean(item && item.detail, 1400),
    };
  });
}

// --- API -------------------------------------------------------------------------------------

// 1. Anyone can create a PIN. The reply carries a secret token that only the creator's browser
//    holds; it is required to read the report, so other people cannot read it by guessing PINs.
app.post("/api/create-pin", rateLimit("create", 10, 60 * 1000), (req, res) => {
  if (sessions.size >= MAX_SESSIONS) return res.status(503).json({ error: "too many open sessions" });
  let open = 0;
  for (const v of sessions.values()) if (v.ip === req.ip && v.status === "PENDING") open++;
  if (open >= MAX_SESSIONS_PER_IP) return res.status(429).json({ error: "too many open PINs; use or wait for the earlier ones to expire" });
  let pin;
  do {
    pin = String(crypto.randomInt(100000, 1000000));
  } while (sessions.has(pin));
  const token = crypto.randomBytes(24).toString("hex");
  const admin = clean(req.body && req.body.admin, 60) || "Admin";
  sessions.set(pin, { pin, token, ip: req.ip, admin, status: "PENDING", report: null, createdAt: Date.now() });
  res.json({ pin, token, expiresInSeconds: SESSION_TTL_MS / 1000 });
});

// 2. Player app checks the PIN (rate limited: a 6-digit PIN must not be guessable)
app.get("/api/verify-pin/:pin", rateLimit("verify", 10, 60 * 1000), (req, res) => {
  const s = liveSession(req.params.pin);
  if (!s || s.status !== "PENDING") return res.status(404).json({ valid: false });
  res.json({ valid: true });
});

// 3. Player app uploads its report, once per PIN
app.post("/api/submit-report/:pin", rateLimit("submit", 10, 60 * 1000), (req, res) => {
  const s = liveSession(req.params.pin);
  if (!s || s.status !== "PENDING") return res.status(404).json({ error: "Invalid or already used PIN" });
  const report = sanitizeReport(req.body && req.body.report);
  if (!report) return res.status(400).json({ error: "report must be an array of at most " + MAX_FINDINGS + " findings" });
  s.status = "COMPLETED";
  s.report = report;
  res.json({ success: true });
});

// 4. The creator's dashboard polls for the result, proving itself with the session token
app.get("/api/session/:pin", rateLimit("session", 120, 60 * 1000), (req, res) => {
  const s = liveSession(req.params.pin);
  if (!s || !safeEqual(req.get("x-session-token") || "", s.token)) return res.status(404).json({ error: "Not found or expired" });
  res.json({ pin: s.pin, admin: s.admin, status: s.status, report: s.report, createdAt: s.createdAt });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log("ABBYS Anti-Cheat running on port " + PORT));
