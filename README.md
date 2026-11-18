# ABBYS Anti-Cheat

Admin generates a 6-digit PIN on a dashboard, the player enters it in the Android app, the app
scans the device and sends the result back to the admin.

## Server (`server/`)

```sh
cd server
npm install
ADMIN_KEY="$(openssl rand -hex 24)" npm start     # keep this key secret; admins type it into the dashboard
```

- Serve it over HTTPS (reverse proxy such as Caddy or nginx). Set `TRUST_PROXY=1` when behind one,
  so rate limits use the real client address.
- PINs are random, valid for 30 minutes, usable once, and rate limited.
- The dashboard needs the admin key; players never get it.
- Data is held in memory only and is deleted after the PIN expires.

## Android app (`app/`)

Set `Config.SERVER_URL` in `ReportClient.kt` to your server's HTTPS address. The app asks for
consent text up front, verifies the PIN with the server, scans, then uploads the report.

Severity policy: HIGH is reserved for strong native signals (root files, hooks, debugger). App-name
matches are WARN and say "name match only". Common innocent things (sideloaded apps, accessibility
services, ADB) are INFO. Results are indicators for an admin to review, not proof.

## `docs/index.html`

A separate Supabase-based dashboard. If you use it, enable Row Level Security on `scan_sessions` so
the publishable key cannot read other sessions' reports; otherwise use the Express server instead.
