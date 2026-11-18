"use strict";
const keyEl = document.getElementById("adminKey");
const nameEl = document.getElementById("adminName");
const statusEl = document.getElementById("statusText");
const pinEl = document.getElementById("pinDisplay");
const reportBox = document.getElementById("reportContainer");
const reportList = document.getElementById("reportList");
const SEVERITIES = ["HIGH", "WARN", "INFO", "CLEAN"];
let timer = null;

// Only the non-secret name is remembered, and only for this tab. The key is never stored.
nameEl.value = sessionStorage.getItem("abbys_admin") || "";

function headers() {
  return { "Content-Type": "application/json", "x-admin-key": keyEl.value };
}

function render(report) {
  reportList.replaceChildren();
  for (const item of report) {
    const sev = SEVERITIES.includes(item.severity) ? item.severity : "INFO";
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

document.getElementById("genBtn").addEventListener("click", async () => {
  if (timer) clearInterval(timer);
  const admin = nameEl.value.trim() || "Admin";
  sessionStorage.setItem("abbys_admin", admin);
  statusEl.textContent = "Generating PIN...";
  reportBox.classList.add("hidden");
  try {
    const res = await fetch("/api/create-pin", { method: "POST", headers: headers(), body: JSON.stringify({ admin }) });
    if (res.status === 401) { statusEl.textContent = "Wrong admin key."; return; }
    if (!res.ok) { statusEl.textContent = "Server error (" + res.status + ")."; return; }
    const { pin } = await res.json();
    pinEl.classList.remove("hidden");
    pinEl.textContent = pin;
    statusEl.textContent = "Waiting for player to enter the PIN in the app (valid 30 minutes)...";
    timer = setInterval(async () => {
      try {
        const r = await fetch("/api/session/" + pin, { headers: headers() });
        if (r.status === 404) { clearInterval(timer); statusEl.textContent = "PIN expired."; return; }
        if (!r.ok) return;
        const s = await r.json();
        if (s.status === "COMPLETED" && Array.isArray(s.report)) {
          clearInterval(timer);
          statusEl.textContent = "Scan completed.";
          document.getElementById("reportPin").textContent = "PIN " + pin;
          render(s.report);
          reportBox.classList.remove("hidden");
        }
      } catch (e) { /* transient network error: keep polling */ }
    }, 2500);
  } catch (e) {
    statusEl.textContent = "Network error: " + e.message;
  }
});
