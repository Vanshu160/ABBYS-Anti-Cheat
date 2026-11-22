"use strict";

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

/**
 * Posts one report to Discord. Fire-and-forget: it never throws and never affects the upload.
 * Only an exact discord.com webhook URL is accepted, so the variable cannot point the server at anything else.
 */
async function notify(url, r, fetchImpl = globalThis.fetch) {
  if (!url || !WEBHOOK_RE.test(url) || !r || !Array.isArray(r.report) || typeof fetchImpl !== "function") return false;
  try {
    const form = new FormData();
    form.append("payload_json", JSON.stringify(buildPayload(r)));
    form.append("files[0]", new Blob([reportText(r)], { type: "text/plain" }), "abyss-report-" + r.pin + "-scan" + r.scans + ".txt");
    const res = await fetchImpl(url, { method: "POST", body: form, signal: AbortSignal.timeout(8000) });
    if (!res.ok) console.error("discord webhook answered", res.status);
    return res.ok;
  } catch (e) {
    console.error("discord webhook failed:", e && e.message);
    return false;
  }
}

module.exports = { notify, buildPayload, reportText, WEBHOOK_RE };
