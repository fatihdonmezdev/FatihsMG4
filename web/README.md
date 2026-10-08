# FatihsMG4 web

Next.js app on Vercel: the ingest API the car writes to, and a phone-shaped dashboard for
reading the history back. Replaces the standalone Express service that used to live in
`server/`.

**The car never connects to MongoDB and never holds a MongoDB URI.** The URI is a Vercel
environment variable and is only ever read by a server-side route.

## Two credentials, on purpose

| | Who holds it | What it opens |
|---|---|---|
| `INGEST_TOKEN` | the car, in the APK | the `PUT` endpoints — write only |
| `DASHBOARD_PASSWORD` | you, in a browser | the dashboard and the `GET` history |

The APK is published, so its token must be assumed extractable: anyone can `unzip` an APK
and read its strings. That is survivable here because the write token cannot read anything
back. The password is never compiled into anything.

## Environment variables

Set all five in Vercel → Settings → Environment Variables, for Production and Preview.

| Name | Notes |
|---|---|
| `MONGODB_URI` | Atlas connection string |
| `MONGODB_DATABASE` | defaults to `fatihsmg4` |
| `INGEST_TOKEN` | `openssl rand -hex 32` |
| `DASHBOARD_PASSWORD` | at least 8 characters, or login is refused outright |
| `SESSION_SECRET` | `openssl rand -hex 32`; rotating it signs every browser out |

Atlas network access must allow Vercel's egress, which is not a fixed address on the Hobby
plan — either allow `0.0.0.0/0` and rely on the database user's credentials, or use Atlas
private networking on a paid plan.

## Endpoints

Write, `Authorization: Bearer <INGEST_TOKEN>`:

- `PUT /v1/consumption/daily`
- `PUT /v1/charging-sessions/:sessionId`

Read, session cookie or `Authorization: Bearer <DASHBOARD_PASSWORD>`:

- `GET /v1/history/:installationId/consumption`
- `GET /v1/history/:installationId/charging`

`GET /v1/health` is unauthenticated and returns `{"status":"ok"}`.

The car builds its URLs as `CONSUMPTION_API_URL + /v1/...`, so the Android build variable
is the bare origin — `https://your-app.vercel.app`, no trailing path.

## Indexes

There is no boot step on Vercel, so `ensureIndexes()` runs on the first write of each warm
lambda and is skipped afterwards. The unique indexes on `(installationId, date)` and
`(installationId, sessionId)` are what make the upserts idempotent when the car retries a
record it could not confirm.

## Local development

```sh
cp .env.example .env.local   # Next loads .env.local automatically
npm install
npm run dev
npm test                     # pure validation and session-signing tests, no database
```
