"use strict";
const fs = require("fs");
const path = require("path");

const COLORS = { CHEAT_DETECTED: 0xef4444, SUSPICIOUS_ACTIVITY: 0xf59e0b, NO_CHEAT_DETECTED: 0x14b8a6 };
const WEBHOOK_RE = /^https:\/\/(?:ptb\.|canary\.)?(?:discord|discordapp)\.com\/api\/webhooks\/\d+\/[\w-]+$/;

const cut = (s, n) => {
  s = String(s == null ? "" : s);
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
};

/** Plain-text copy of the whole report, attached to the Discord message as a file. */
function reportText(r) {
  const head = [
    "ABYSS AntiCheat scan report",
    "PIN " + r.pin + " | admin: " + r.admin + " | scan " + r.scans,
    "Verdict: " + (r.verdict ? r.verdict.headline : "none (older app)"),
    "",
  ];
  const lines = r.report.map((i) => "[" + i.severity + "] " + i.title + "\n    " + i.detail);
  return head.concat(lines).join("\n");
}

/** Builds the Discord message. No @mentions are ever allowed to fire, whatever a player's text contains. */
function buildPayload(r) {
  const v = r.verdict;
  const level = v ? v.level : "NO_CHEAT_DETECTED";
  const counts = { HIGH: 0, WARN: 0, INFO: 0, CLEAN: 0 };
  for (const i of r.report) counts[i.severity] = (counts[i.severity] || 0) + 1;
  const embed = {
    title: cut((v ? v.headline : "REPORT RECEIVED (no verdict)") + " - PIN " + r.pin, 250),
    description: cut(v ? v.summary : "An older app version sent no verdict.", 1000),
    color: v ? COLORS[level] : 0x64748b,
    fields: [
      { name: "Scan", value: "#" + r.scans + " | admin: " + cut(r.admin, 60), inline: true },
      { name: "Findings", value: "HIGH " + counts.HIGH + " | WARN " + counts.WARN + " | INFO " + counts.INFO + " | CLEAN " + counts.CLEAN, inline: true },
    ],
    footer: { text: "Computed by the app; treat as a lead, not proof." },
    timestamp: new Date().toISOString(),
  };
  if (v) {
    for (const d of v.detections.slice(0, 5)) {
      embed.fields.push({
        name: cut("Client: " + d.client, 250),
        value: cut("File: " + d.file + "\nEvidence: " + d.evidence + "\nConfidence: " + d.confidence, 1000),
      });
    }
    if (v.reasons.length) embed.fields.push({ name: "Why", value: cut(v.reasons.map((x) => "• " + x).join("\n"), 1000) });
    if (v.limits.length) embed.fields.push({ name: "Limits", value: cut(v.limits.map((x) => "• " + x).join("\n"), 1000) });
  }
  return { username: "ABYSS AntiCheat", allowed_mentions: { parse: [] }, embeds: [embed] };
}

/** A pasted value often carries a trailing space/newline or quotes; strip them before validating. */
function normalizeUrl(raw) {
  return String(raw == null ? "" : raw).trim().replace(/^["'`]+|["'`]+$/g, "").trim();
}

const SECRET_DIRS = ["/etc/secrets", __dirname];

/** Reads small files (Render "Secret Files") and returns the first content that is an exact discord webhook URL. */
function urlFromFiles(dirs = SECRET_DIRS) {
  for (const dir of dirs) {
    let names = [];
    try { names = fs.readdirSync(dir); } catch (e) { continue; }
    for (const n of names) {
      if (!/discord|webhook|hook/i.test(n)) continue;
      try {
        const p = path.join(dir, n);
        const st = fs.statSync(p);
        if (!st.isFile() || st.size > 1024) continue;
        const c = normalizeUrl(fs.readFileSync(p, "utf8"));
        if (WEBHOOK_RE.test(c)) return c;
      } catch (e) { /* unreadable file: ignore */ }
    }
  }
  return "";
}

/**
 * Finds the webhook URL in the environment. DISCORD_WEBHOOK_URL wins; otherwise any variable whose value is an exact
 * discord.com webhook URL is used, so a slightly different key name in the dashboard still works.
 */
function resolveUrl(env = process.env, dirs = SECRET_DIRS) {
  const direct = normalizeUrl(env.DISCORD_WEBHOOK_URL);
  if (WEBHOOK_RE.test(direct)) return direct;
  for (const v of Object.values(env)) {
    const c = normalizeUrl(v);
    if (WEBHOOK_RE.test(c)) return c;
  }
  return urlFromFiles(dirs) || direct; // a secret file is the fallback; otherwise notify() records why
}

/** What the last attempt did (never contains the URL). Shown by /api/health so a broken setup can be diagnosed. */
const status = { lastAt: null, lastResult: "no report posted yet" };
function record(result) {
  status.lastAt = new Date().toISOString();
  status.lastResult = result;
}

/** configured: variable present; valid: it looks like a discord.com webhook after cleanup. */
function describe(env = process.env, dirs = SECRET_DIRS) {
  const url = resolveUrl(env, dirs);
  return {
    configured: url.length > 0,
    valid: WEBHOOK_RE.test(url),
    // Names only (never values) of variables that look related, to spot a misspelled key.
    relatedVariableNames: Object.keys(env).filter((k) => /discord|webhook/i.test(k)),
    // Does the rest of the Render configuration reach the server? (booleans only)
    otherVariablesPresent: { TRUST_PROXY: "TRUST_PROXY" in env, ADMIN_KEY: "ADMIN_KEY" in env },
    secretFileNames: (() => { try { return fs.readdirSync("/etc/secrets").slice(0, 10); } catch (e) { return []; } })(),
    lastAt: status.lastAt,
    lastResult: status.lastResult,
  };
}

/**
 * Posts one report to Discord. Fire-and-forget: it never throws and never affects the upload.
 * Only an exact discord.com webhook URL is accepted, so the variable cannot point the server at anything else.
 */
async function notify(rawUrl, r, fetchImpl = globalThis.fetch) {
  const url = normalizeUrl(rawUrl);
  if (!url) { record("skipped: DISCORD_WEBHOOK_URL is not set"); return false; }
  if (!WEBHOOK_RE.test(url)) { record("skipped: DISCORD_WEBHOOK_URL is not a valid discord.com webhook address"); return false; }
  if (!r || !Array.isArray(r.report) || typeof fetchImpl !== "function") { record("skipped: nothing to post"); return false; }
  try {
    const form = new FormData();
    form.append("payload_json", JSON.stringify(buildPayload(r)));
    form.append("files[0]", new Blob([reportText(r)], { type: "text/plain" }), "abyss-report-" + r.pin + "-scan" + r.scans + ".txt");
    const res = await fetchImpl(url, { method: "POST", body: form, signal: AbortSignal.timeout(8000) });
    if (!res.ok) console.error("discord webhook answered", res.status);
    record(res.ok ? "posted (HTTP " + res.status + ")" : "Discord refused it: HTTP " + res.status + (res.status === 404 ? " (webhook deleted or wrong)" : ""));
    return res.ok;
  } catch (e) {
    console.error("discord webhook failed:", e && e.message);
    record("failed: " + (e && e.message ? String(e.message).slice(0, 100) : "network error"));
    return false;
  }
}

module.exports = { urlFromFiles, resolveUrl, normalizeUrl, describe, notify, buildPayload, reportText, WEBHOOK_RE };
