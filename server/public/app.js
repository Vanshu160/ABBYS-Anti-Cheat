"use strict";
const $ = (id) => document.getElementById(id);
const nameEl = $("adminName"), statusEl = $("statusText");
const pinEl = $("pinDisplay"), pinWrap = $("pinWrap"), countdownEl = $("countdown");
const reportBox = $("reportContainer"), reportList = $("reportList"), summaryEl = $("summary");
const SEVERITIES = ["HIGH", "WARN", "INFO", "CLEAN"];
const TTL_MS = 30 * 60 * 1000;
let sessionToken = null, pollTimer = null, tickTimer = null, currentPin = null, report = [], filter = "ALL";

// Only the non-secret name is remembered, and only for this tab. The key is never stored.
nameEl.value = sessionStorage.getItem("abbys_admin") || "";

const headers = () => ({ "Content-Type": "application/json", "x-session-token": sessionToken || "" });
const sevOf = (i) => (SEVERITIES.includes(i.severity) ? i.severity : "INFO");
const stopTimers = () => { clearInterval(pollTimer); clearInterval(tickTimer); };

function render() {
  summaryEl.replaceChildren();
  for (const s of SEVERITIES) {
    const n = report.filter((i) => sevOf(i) === s).length;
    const el = document.createElement("div");
    el.className = "count " + s;
    const b = document.createElement("b");
    b.textContent = n;
    el.append(b, s);
    summaryEl.appendChild(el);
  }
  reportList.replaceChildren();
  const items = report
    .filter((i) => filter === "ALL" || sevOf(i) === filter)
    .sort((a, b) => SEVERITIES.indexOf(sevOf(a)) - SEVERITIES.indexOf(sevOf(b)));
  if (!items.length) {
    const p = document.createElement("p");
    p.className = "note";
    p.textContent = "No findings in this category.";
    reportList.appendChild(p);
  }
  for (const item of items) {
    const sev = sevOf(item);
    const box = document.createElement("div");
    box.className = "finding " + sev;
    const title = document.createElement("div");
    title.className = "sev";
    title.textContent = "[" + sev + "] " + item.title; // textContent: report data is never parsed as HTML
    const detail = document.createElement("div");
    detail.className = "detail";
    detail.textContent = item.detail;
    box.append(title, detail);
    reportList.appendChild(box);
  }
}

$("filters").addEventListener("click", (e) => {
  const f = e.target.dataset && e.target.dataset.f;
  if (!f) return;
  filter = f;
  document.querySelectorAll(".chip").forEach((c) => c.classList.toggle("active", c.dataset.f === f));
  render();
});

async function copy(text, btn) {
  const old = btn.textContent;
  try { await navigator.clipboard.writeText(text); btn.textContent = "Copied"; }
  catch (e) { btn.textContent = "Copy failed"; }
  setTimeout(() => { btn.textContent = old; }, 1500);
}
$("copyPin").addEventListener("click", (e) => currentPin && copy(currentPin, e.target));
$("copyReport").addEventListener("click", (e) => {
  const lines = report.map((i) => "[" + sevOf(i) + "] " + i.title + " - " + i.detail);
  copy("ABBYS scan, PIN " + currentPin + "\n" + lines.join("\n"), e.target);
});

function startCountdown(createdAt) {
  clearInterval(tickTimer);
  const tick = () => {
    const left = Math.max(0, createdAt + TTL_MS - Date.now());
    countdownEl.textContent = left ? "Expires in " + Math.floor(left / 60000) + ":" + String(Math.floor(left / 1000) % 60).padStart(2, "0") : "Expired";
    if (!left) clearInterval(tickTimer);
  };
  tick();
  tickTimer = setInterval(tick, 1000);
}

$("genBtn").addEventListener("click", async () => {
  const btn = $("genBtn");
  stopTimers();
  const admin = nameEl.value.trim() || "Admin";
  sessionStorage.setItem("abbys_admin", admin);
  statusEl.textContent = "Generating PIN... (the first request can take up to a minute if the server was asleep)";
  reportBox.classList.add("hidden");
  pinWrap.classList.add("hidden");
  btn.disabled = true;
  try {
    const res = await fetch("/api/create-pin", { method: "POST", headers: headers(), body: JSON.stringify({ admin }) });
    if (res.status === 429) { statusEl.textContent = "Too many requests. Wait a minute and retry."; return; }
    if (!res.ok) { statusEl.textContent = "Server error (" + res.status + ")."; return; }
    const { pin, token } = await res.json();
    sessionToken = token; // held in memory only; needed to read this PIN's report
    currentPin = pin;
    pinEl.textContent = pin;
    pinWrap.classList.remove("hidden");
    startCountdown(Date.now());
    statusEl.textContent = "Waiting for the player to enter the PIN in the app...";
    pollTimer = setInterval(async () => {
      try {
        const r = await fetch("/api/session/" + pin, { headers: headers() });
        if (r.status === 404) { stopTimers(); statusEl.textContent = "PIN expired."; return; }
        if (!r.ok) return;
        const s = await r.json();
        if (s.status === "COMPLETED" && Array.isArray(s.report)) {
          stopTimers();
          countdownEl.textContent = "Used";
          statusEl.textContent = "Scan completed.";
          $("reportPin").textContent = "PIN " + pin;
          report = s.report;
          filter = "ALL";
          document.querySelectorAll(".chip").forEach((c) => c.classList.toggle("active", c.dataset.f === "ALL"));
          render();
          reportBox.classList.remove("hidden");
        }
      } catch (e) { /* transient network error: keep polling */ }
    }, 2500);
  } catch (e) {
    statusEl.textContent = "Network error: " + e.message;
  } finally {
    btn.disabled = false;
  }
});
