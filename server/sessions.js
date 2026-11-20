"use strict";
const crypto = require("crypto");

const UNCLAIMED_TTL_MS = 30 * 60 * 1000; // a PIN nobody has entered yet
const PLAYER_SESSION_MS = 15 * 60 * 1000; // exactly this long after the player first enters the PIN
const READ_GRACE_MS = 15 * 60 * 1000; // the admin can still read the last report this long after expiry
const MAX_SESSIONS = 1000;
const MAX_SESSIONS_PER_IP = 5;
const MAX_FINDINGS = 200;
const SEVERITIES = new Set(["HIGH", "WARN", "INFO", "CLEAN"]);

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

const LEVELS = new Set(["CHEAT_DETECTED", "SUSPICIOUS_ACTIVITY", "NO_CHEAT_DETECTED"]);
const HEADLINES = { CHEAT_DETECTED: "CHEAT DETECTED", SUSPICIOUS_ACTIVITY: "SUSPICIOUS ACTIVITY", NO_CHEAT_DETECTED: "NO CHEAT DETECTED" };

/** The verdict computed by the app. The headline is derived from the level so the two can never disagree. */
function sanitizeVerdict(v) {
  if (!v || typeof v !== "object" || !LEVELS.has(v.level)) return null;
  const list = (a, n, f) => (Array.isArray(a) ? a.slice(0, n).map(f) : []);
  return {
    level: v.level,
    headline: HEADLINES[v.level],
    summary: clean(v.summary, 400),
    detections: list(v.detections, 10, (d) => ({
      client: clean(d && d.client, 80),
      file: clean(d && d.file, 160),
      evidence: clean(d && d.evidence, 200),
      confidence: clean(d && d.confidence, 20),
    })),
    reasons: list(v.reasons, 5, (r) => clean(r, 200)),
    limits: list(v.limits, 5, (r) => clean(r, 200)),
  };
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/**
 * A session is created by the admin (PIN + admin token). The player's first successful
 * start() claims the PIN: it fixes expiresAt = claimedAt + 15 minutes and returns a player token.
 * The timer is never extended. Until expiry the player may submit any number of reports with the
 * token (the PIN itself is not needed again). The latest report replaces the previous one.
 */
class SessionStore {
  constructor(now = Date.now) {
    this.now = now;
    this.sessions = new Map(); // pin -> session
    this.byPlayerToken = new Map(); // playerToken -> pin
  }

  /** End of the period in which the player may act. */
  _end(s) {
    return s.claimedAt ? s.expiresAt : s.createdAt + UNCLAIMED_TTL_MS;
  }

  _alive(s) {
    return this.now() < this._end(s);
  }

  sweep() {
    const t = this.now();
    for (const [pin, s] of this.sessions) {
      if (t >= this._end(s) + READ_GRACE_MS) {
        this.sessions.delete(pin);
        if (s.playerToken) this.byPlayerToken.delete(s.playerToken);
      }
    }
  }

  create(ip, admin) {
    this.sweep();
    if (this.sessions.size >= MAX_SESSIONS) return { error: "busy" };
    let open = 0;
    for (const v of this.sessions.values()) if (v.ip === ip && !v.claimedAt && this._alive(v)) open++;
    if (open >= MAX_SESSIONS_PER_IP) return { error: "too_many" };
    let pin;
    do {
      pin = String(crypto.randomInt(100000, 1000000));
    } while (this.sessions.has(pin));
    const token = crypto.randomBytes(24).toString("hex");
    this.sessions.set(pin, {
      pin, token, ip, admin: clean(admin, 60) || "Admin",
      createdAt: this.now(), claimedAt: 0, expiresAt: 0, playerToken: null,
      status: "PENDING", report: null, verdict: null, scans: 0, reportAt: 0,
    });
    return { pin, token, expiresInSeconds: UNCLAIMED_TTL_MS / 1000 };
  }

  /** The player enters the PIN. Succeeds once per PIN. */
  start(pin) {
    if (typeof pin !== "string" || !/^\d{6}$/.test(pin)) return { error: "invalid" };
    const s = this.sessions.get(pin);
    if (!s || s.claimedAt || !this._alive(s)) return { error: "invalid" };
    s.claimedAt = this.now();
    s.expiresAt = s.claimedAt + PLAYER_SESSION_MS;
    s.playerToken = crypto.randomBytes(24).toString("hex");
    this.byPlayerToken.set(s.playerToken, pin);
    return { playerToken: s.playerToken, expiresInSeconds: PLAYER_SESSION_MS / 1000, expiresAt: s.expiresAt };
  }

  /** The player uploads a report. Does not change expiresAt. */
  submit(playerToken, rawReport, rawVerdict) {
    const pin = typeof playerToken === "string" ? this.byPlayerToken.get(playerToken) : null;
    const s = pin && this.sessions.get(pin);
    if (!s || !safeEqual(playerToken, s.playerToken)) return { error: "invalid" };
    if (!this._alive(s)) return { error: "expired" };
    const report = sanitizeReport(rawReport);
    if (!report) return { error: "bad_report" };
    s.report = report;
    s.verdict = sanitizeVerdict(rawVerdict); // null when an older app sent none
    s.status = "COMPLETED";
    s.scans++;
    s.reportAt = this.now();
    return { success: true, expiresAt: s.expiresAt };
  }

  /** The admin's dashboard reads a session with the admin token. */
  read(pin, adminToken) {
    if (typeof pin !== "string" || !/^\d{6}$/.test(pin)) return null;
    const s = this.sessions.get(pin);
    if (!s || this.now() >= this._end(s) + READ_GRACE_MS) return null;
    if (!safeEqual(adminToken || "", s.token)) return null;
    return {
      pin: s.pin, admin: s.admin, status: s.status, report: s.report, verdict: s.verdict, createdAt: s.createdAt,
      claimed: !!s.claimedAt, scans: s.scans, reportAt: s.reportAt,
      expiresAt: s.claimedAt ? s.expiresAt : s.createdAt + UNCLAIMED_TTL_MS,
      expired: !this._alive(s),
      secondsLeft: Math.max(0, Math.ceil((this._end(s) - this.now()) / 1000)),
    };
  }
}

module.exports = { SessionStore, sanitizeReport, sanitizeVerdict, UNCLAIMED_TTL_MS, PLAYER_SESSION_MS, READ_GRACE_MS, MAX_FINDINGS };
