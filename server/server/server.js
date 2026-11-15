const express = require("express");
const app = express();
app.use(express.json({ limit: "2mb" }));

// Stores active PINs and scan reports in memory
const sessions = new Map();

// 1. Admin generates a 6-digit PIN
app.post("/api/create-pin", (req, res) => {
  const admin = (req.body && req.body.admin) || "Admin";
  const pin = Math.floor(100000 + Math.random() * 900000).toString();
  sessions.set(pin, {
    pin,
    admin,
    status: "PENDING",
    report: null,
    createdAt: Date.now()
  });
  res.json({ pin });
});

// 2. Android APK verifies if PIN exists
app.get("/api/verify-pin/:pin", (req, res) => {
  const session = sessions.get(req.params.pin);
  if (!session || session.status !== "PENDING") {
    return res.status(404).json({ valid: false });
  }
  res.json({ valid: true });
});

// 3. Android APK uploads the C++ & Kotlin scan report
app.post("/api/submit-report/:pin", (req, res) => {
  const session = sessions.get(req.params.pin);
  if (!session) {
    return res.status(404).json({ error: "Invalid PIN" });
  }
  session.status = "COMPLETED";
  session.report = req.body.report || [];
  res.json({ success: true });
});

// 4. Web Dashboard checks if report has arrived
app.get("/api/session/:pin", (req, res) => {
  const session = sessions.get(req.params.pin);
  if (!session) return res.status(404).json({ error: "Not found" });
  res.json(session);
});

// 5. Serves the Ocean-Style Web Dashboard
app.get("/", (req, res) => {
  res.send(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>ABBYS Anti-Cheat | SS Dashboard</title>
  <style>
    * { box-sizing: border-box; }
    body { background: #090d16; color: #e6edf3; font-family: system-ui, sans-serif; padding: 20px; max-width: 720px; margin: auto; }
    header { display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #1f2937; padding-bottom: 16px; margin-bottom: 24px; }
    h2 { margin: 0; color: #38bdf8; letter-spacing: 1px; }
    .badge { background: #1e293b; color: #94a3b8; padding: 4px 10px; border-radius: 99px; font-size: 12px; }
    .card { background: #111827; border: 1px solid #1f2937; border-radius: 12px; padding: 20px; margin-bottom: 20px; }
    label { display: block; font-size: 13px; color: #9ca3af; margin-bottom: 6px; }
    input { width: 100%; padding: 12px; border-radius: 8px; border: 1px solid #374151; background: #090d16; color: #fff; font-size: 15px; margin-bottom: 14px; }
    button { width: 100%; padding: 14px; background: #0284c7; color: white; border: none; border-radius: 8px; font-weight: 600; font-size: 15px; cursor: pointer; }
    .pin-box { font-size: 36px; font-weight: 800; letter-spacing: 8px; color: #38bdf8; text-align: center; padding: 18px; background: #090d16; border: 1px dashed #0284c7; border-radius: 10px; margin-top: 16px; }
    .status { text-align: center; font-size: 14px; color: #f59e0b; margin-top: 12px; }
    .finding { background: #090d16; border-left: 4px solid #38bdf8; padding: 12px; border-radius: 6px; margin-bottom: 10px; }
    .finding.HIGH { border-left-color: #ef4444; }
    .finding.WARN { border-left-color: #f59e0b; }
    .finding.CLEAN { border-left-color: #10b981; }
    .sev { font-weight: 700; font-size: 12px; }
    .detail { font-size: 13px; color: #9ca3af; margin-top: 4px; word-break: break-all; }
  </style>
</head>
<body>
  <header>
    <h2>🛡️ ABBYS ANTI-CHEAT</h2>
    <span class="badge">Self-Hosted SS Panel</span>
  </header>
  <div class="card">
    <label for="adminName">Admin Name:</label>
    <input type="text" id="adminName" placeholder="e.g. Vanshu" />
    <button id="genBtn" type="button">Generate 6-Digit SS PIN</button>
    <div id="pinDisplay" class="pin-box" style="display:none;"></div>
    <p id="statusText" class="status">Ready to generate PIN.</p>
  </div>
  <div id="reportContainer" class="card" style="display:none;">
    <h3 style="margin-top:0;">📋 Scan Results (<span id="reportPin"></span>)</h3>
    <div id="reportList"></div>
  </div>
  <script>
    document.getElementById("genBtn").addEventListener("click", async () => {
      const admin = document.getElementById("adminName").value.trim() || "Admin";
      const statusEl = document.getElementById("statusText");
      const pinEl = document.getElementById("pinDisplay");
      statusEl.innerText = "Generating PIN...";

      const res = await fetch("/api/create-pin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ admin })
      });
      const data = await res.json();
      pinEl.style.display = "block";
      pinEl.innerText = data.pin;
      statusEl.innerText = "Waiting for player to enter PIN in APK...";
      document.getElementById("reportContainer").style.display = "none";

      const timer = setInterval(async () => {
        const check = await fetch("/api/session/" + data.pin);
        if (!check.ok) return;
        const session = await check.json();
        if (session.status === "COMPLETED" && session.report) {
          clearInterval(timer);
          statusEl.innerText = "✅ Scan Completed!";
          document.getElementById("reportPin").innerText = "PIN: " + data.pin;
          document.getElementById("reportContainer").style.display = "block";
          document.getElementById("reportList").innerHTML = session.report.map(item => \`
            <div class="finding \${item.severity}">
              <div class="sev">[\${item.severity}] \${item.title}</div>
              <div class="detail">\${item.detail}</div>
            </div>
          \`).join("");
        }
      }, 2500);
    });
  </script>
</body>
</html>`);
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log("ABBYS Anti-Cheat running on port " + PORT));
        
