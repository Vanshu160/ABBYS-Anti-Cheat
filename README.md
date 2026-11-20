# ABBYS Anti-Cheat

Admin generates a 6-digit PIN on a dashboard, the player enters it in the Android app, the app
scans the device and sends the result back to the admin.

## Deploy on Render

1. Render dashboard -> New -> Blueprint -> select this repo. `render.yaml` creates a free web service named `abbys-anticheat`.
2. Open the service URL. No admin key is needed: anyone can generate a PIN, and only the browser tab that created it can read its report.
3. Free tier sleeps after inactivity (first request may take ~30-50s) and sessions are in memory, so a restart clears pending PINs.

## Server (`server/`)

```sh
cd server
npm install
npm start
```

- Serve it over HTTPS (reverse proxy such as Caddy or nginx). Set `TRUST_PROXY=1` when behind one,
  so rate limits use the real client address.
- PINs are random and rate limited. An unused PIN is valid for 30 minutes. When the player enters it, a **15-minute session** starts (fixed, never extended); during it the player can upload any number of reports (the latest is shown, with a scan counter). The admin can still read the last report for 15 minutes after the session ends.
- Anyone can create a PIN (limited to 5 open PINs and 10 requests per minute per address). Creating one returns a secret session token that stays in that browser tab and is required to read the report, so guessing PINs does not expose reports. Closing the tab loses access to that PIN's report.
- Data is held in memory only and is deleted after the PIN expires.

## Android app (`app/`)

`Config.SERVER_URL` in `ReportClient.kt` points at `https://abbys-anticheat.onrender.com`; change it if your Render service has a different name. The app asks for
consent text up front, starts a 15-minute session with the PIN (the server returns a session token that is kept in Keystore-encrypted storage; the PIN itself is not stored), then scans and uploads reports with that token. Release signing and installing: see `docs/RELEASE.md`.

Severity policy: HIGH is reserved for strong native signals (root files, hooks, debugger). App-name
matches are WARN and say "name match only". Common innocent things (sideloaded apps, accessibility
services, ADB) are INFO. Results are indicators for an admin to review, not proof.
