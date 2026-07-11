---
title: Edutrack Backend
emoji: 📚
colorFrom: blue
colorTo: green
sdk: docker
pinned: false
---

# edutrack-backend

## Deploying (SnapDeploy, Railway, Render, or any Docker/Nixpacks host)

Set these environment variables before deploying. Each one accepts either
name shown (some PaaS scanners auto-prefix nested YAML keys with the app
name — both are wired up so it works either way):

| Variable (either name works) | Required? | Notes |
|---|---|---|
| `DATABASE_URL` | **Yes** | Postgres connection string, e.g. `jdbc:postgresql://host:5432/dbname` |
| `DB_USERNAME` | Only if not embedded in `DATABASE_URL` | |
| `DB_PASSWORD` | Only if not embedded in `DATABASE_URL` | |
| `EDUTRACK_SCHOOL_EMAIL_FROM` or `EMAIL_FROM` | **Yes** | Must be a sender address verified on your Resend account |
| `RESEND_API_KEY` or `EDUTRACK_RESEND_API_KEY` | **Yes** | API key from your Resend dashboard |
| `EDUTRACK_JWT_SECRET` or `JWT_SECRET` | **Yes (prod)** | 64+ random characters. Has an insecure dev fallback — never rely on it in production |
| `EDUTRACK_JWT_EXPIRATION_MS` or `JWT_EXPIRATION_MS` | No | Token lifetime in ms. Defaults to `86400000` (24h) |
| `EDUTRACK_QR_BASE_URL` or `QR_BASE_URL` | No | Defaults to `https://app.edutrack.school` — set to your real frontend URL |
| `EDUTRACK_SCHOOL_NAME` or `SCHOOL_NAME` | No | Defaults to `Mahinda College` |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated list of allowed frontend origins |
| `PORT` | No | Most PaaS platforms inject this automatically; defaults to `8080` |

The app will **fail to start** if `EMAIL_FROM`/`EDUTRACK_SCHOOL_EMAIL_FROM` or
`RESEND_API_KEY` are missing — there's no default for these on purpose,
since a silently-wrong sender address causes every email to be rejected.

Check your deploy platform's logs on failure — a message like
`Could not resolve placeholder 'X'` means that env var isn't set.