# Deploying the AMRit backend

The hosted backend is a **Supabase Edge Function** (`supabase/functions/api/`, TypeScript) with its tables in
Supabase's Postgres. It serves the same API as `backend/app.py`. The Python FastAPI version is kept for local
development and the on-device evaluation, which also serves the test photos.

What lives on the server:
- the OpenRouter key (a Supabase secret, never in the app)
- anonymous scan events
- the shared reading cache
- learned brands
- per-phone and daily-spend limits

Photos are never stored.

## Setup (free plan, no card)

1. Create a project at https://supabase.com/dashboard. Choose the region **South Asia (Mumbai)** and any database
   password. The password isn't needed here.
2. Sign the CLI in once: `supabase login`.
3. From the repo root:
   ```
   supabase secrets set --project-ref <ref> OPENROUTER_API_KEY=<key> EXPORT_TOKEN=<random string>
   supabase functions deploy api --project-ref <ref> --no-verify-jwt --use-api
   ```
   - `--no-verify-jwt`: the app calls the function without any key; abuse is limited by the per-IP and daily-budget caps.
   - `--use-api`: bundling happens on Supabase's side, so Docker isn't needed.
   - Tables are created on the first request, in a separate `amrit` schema. Supabase's public REST API only exposes
     the `public` schema, so the data is reachable only through the function.
4. Check it: `curl https://<ref>.supabase.co/functions/v1/api/health` should return `"llm_configured": true`.

## CDSCO drug alerts (daily)

The function keeps CDSCO's Not-of-Standard-Quality and spurious batch alerts in `amrit.batch_alert`. Phones download
the whole list from `GET /v1/batch-alerts?since=<id>` and match batches on the phone, so no batch number is ever sent.

```
supabase secrets set --project-ref <ref> ADMIN_TOKEN=<random string>
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" https://<ref>.supabase.co/functions/v1/api/v1/admin/schedule-alerts
```

The second command schedules two daily database jobs (pg_cron + pg_net). They call
`POST /v1/admin/refresh-alerts?tab=nsq|spurious&months=2`, one list per call to stay inside the 150 s request limit.
The app also ships a snapshot of all alerts since 2023 (`data-build/fetch_cdsco_alerts.py`).

## Point the app at it

Add this to `local.properties` and rebuild:

```
amrit.backendUrl=https://<ref>.supabase.co/functions/v1/api
```

Installed phones can change it under Settings › Advanced › Backend URL.

## Free-plan limits

| Limit | Effect |
|---|---|
| A project is paused after a week without activity | Restore it from the dashboard. The data is kept. |
| Edge Function CPU time per request | The cache lookup prefilters candidates by word overlap before fuzzy matching, to stay well inside it. |

## Limits that protect the key

These are optional secrets.

| Setting | Default | What it does |
|---|---|---|
| `RESOLVE_PER_IP_PER_DAY` | 80 | Vision-fallback calls allowed per phone IP per day |
| `DAILY_BUDGET_USD` | 2.0 | Hard stop on model spend per day |
| `CLIENT_IP_HEADER` / `TRUSTED_PROXY_HOPS` | see below | Which proxy header carries the caller's IP |

`GET /v1/ip` shows the address a caller is counted as. Use it to confirm that the IP header can't be spoofed:
send your own `X-Forwarded-For` header and check whether it changes the result.

## Data export for the dashboard

```
curl -H "Authorization: Bearer $EXPORT_TOKEN" "https://<ref>.supabase.co/functions/v1/api/v1/events/export?since=2026-11-18T00:00:00Z"
```

The export is off unless `EXPORT_TOKEN` is set. Each event contains only: ingredients, AWaRe group, antibiotic flag,
banned flag, pincode, and the hour of the scan.

## Tests

```
deno test --allow-env supabase/functions/tests/      # Edge Function: in-memory store, fake model API
cd backend && python test_backend.py                  # FastAPI version
```

## Paid alternative: Fly.io

`backend/fly.toml` runs the FastAPI version in Mumbai with a persistent volume. It needs a card on file.
