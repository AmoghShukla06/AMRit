// AMRit backend as a Supabase Edge Function: vision-model proxy (holds the OpenRouter key, caches, rate-limits)
// plus the anonymous event store. Same API as backend/app.py (the FastAPI version used for local eval).
//
// Privacy: no accounts; the image is checked and forwarded to the readers in memory, never stored or logged; caller
// IPs are only kept as a salted hash whose salt changes daily; events carry no personal data.
import {
  aggregate, BadRequest, bestCacheMatch, checkJpeg, consistent, LlmReading, normKey, parseAlias, parseEvent, parseLearned,
  parseReading, parseResolve, PROMPT, readingFromCompletion, safeEqual, SCHEMA, today, tokenSig,
} from "./logic.ts";
import type { Store } from "./store.ts";
import { fetchRecent } from "./cdsco.ts";

export interface Config {
  openrouterKey: string;
  models: string[];
  spareModel: string;
  perIpPerDay: number;
  dailyBudgetUsd: number;
  cacheMinScore: number;
  minReporters: number;
  exportToken: string;
  /** protects maintenance endpoints (alert refresh); unset = those endpoints are off */
  adminToken: string;
  /** public project URL (Supabase sets SUPABASE_URL), used to schedule calls to this function */
  publicUrl: string;
  /** header our hosting proxy sets to the caller's address (never one the caller can set) */
  clientIpHeader: string;
  trustedProxyHops: number;
}

export function configFromEnv(env: (k: string) => string | undefined): Config {
  const list = (s: string) => s.split(",").map((x) => x.trim()).filter((x) => x);
  return {
    openrouterKey: env("OPENROUTER_API_KEY") ?? "",
    models: list(env("OPENROUTER_MODELS") ?? "google/gemma-4-31b-it,google/gemini-2.5-flash-lite"),
    spareModel: env("OPENROUTER_SPARE_MODEL") ?? "google/gemma-4-26b-a4b-it",
    perIpPerDay: Number(env("RESOLVE_PER_IP_PER_DAY") ?? 80),
    dailyBudgetUsd: Number(env("DAILY_BUDGET_USD") ?? 2.0),
    cacheMinScore: Number(env("CACHE_MIN_SCORE") ?? 92),
    minReporters: Number(env("ALIAS_MIN_REPORTERS") ?? 2),
    exportToken: env("EXPORT_TOKEN") ?? "",
    adminToken: env("ADMIN_TOKEN") ?? "",
    publicUrl: (env("SUPABASE_URL") ?? "").replace(/\/$/, ""),
    clientIpHeader: (env("CLIENT_IP_HEADER") ?? "").toLowerCase(),
    trustedProxyHops: Number(env("TRUSTED_PROXY_HOPS") ?? 0),
  };
}

export interface Deps {
  store: Store;
  config: Config;
  fetch: typeof fetch;
  log: (msg: string) => void;
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function clientIp(req: Request, c: Config): string {
  if (c.clientIpHeader) {
    const v = req.headers.get(c.clientIpHeader);
    if (v) return v.split(",")[0].trim();
  }
  if (c.trustedProxyHops > 0) {
    // the left end of X-Forwarded-For is whatever the client sent; with N trusted proxies take the N-th from the right
    const hops = (req.headers.get("x-forwarded-for") ?? "").split(",").map((h) => h.trim()).filter((h) => h);
    if (hops.length >= c.trustedProxyHops) return hops[hops.length - c.trustedProxyHops];
  }
  return "?";
}

async function reporterHash(req: Request, d: Deps): Promise<string> {
  const salt = await d.store.daySalt(today());
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(salt + clientIp(req, d.config)));
  return Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}

async function callModel(model: string, imageB64: string, d: Deps): Promise<{ reading: LlmReading; cost: number }> {
  const r = await d.fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    signal: AbortSignal.timeout(25_000),
    headers: { "Authorization": `Bearer ${d.config.openrouterKey}`, "Content-Type": "application/json", "X-Title": "AMRit" },
    body: JSON.stringify({
      model,
      messages: [
        { role: "system", content: PROMPT },
        { role: "user", content: [
          { type: "text", text: "Transcribe this medicine strip." },
          { type: "image_url", image_url: { url: `data:image/jpeg;base64,${imageB64}` } },
        ] },
      ],
      response_format: { type: "json_schema", json_schema: { name: "strip", strict: true, schema: SCHEMA } },
      temperature: 0,
      max_tokens: 1200, // 10-ingredient multivitamin labels need ~600; truncated JSON = failed fallback
      usage: { include: true },
    }),
  });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return readingFromCompletion(await r.json());
}

/** Both readers in parallel; one that fails is replaced by the spare. */
async function callReaders(imageB64: string, d: Deps): Promise<{ readings: LlmReading[]; cost: number }> {
  const one = (m: string) => callModel(m, imageB64, d).catch((e) => { d.log(`resolve: ${m} failed: ${e?.name ?? "error"}`); return null; });
  let results = await Promise.all(d.config.models.map(one));
  if (results.some((r) => r === null) && d.config.spareModel) results = [...results.filter((r) => r !== null), await one(d.config.spareModel)];
  const ok = results.filter((r): r is { reading: LlmReading; cost: number } => r !== null);
  if (!ok.length) throw new Error("all models failed");
  return { readings: ok.map((r) => r.reading), cost: ok.reduce((s, r) => s + r.cost, 0) };
}

async function resolve(req: Request, d: Deps): Promise<Response> {
  const body = parseResolve(await req.json());
  checkJpeg(body.image_jpeg_b64);
  const sig = tokenSig(body.ocr_tokens);
  const hit = bestCacheMatch(sig, await d.store.cacheRows(1000), d.config.cacheMinScore);
  if (hit !== null) {
    const cached = await d.store.cacheReading(hit);
    if (cached) {
      const data = JSON.parse(cached);
      const readings = (Array.isArray(data) ? data : [data]).map(parseReading);
      return json({ reading: readings[0], readings, source: "server_cache", cost_usd: 0, error: null });
    }
  }
  const err = (error: string) => json({ reading: null, readings: [], source: "error", cost_usd: 0, error });
  if (!d.config.openrouterKey) return err("fallback not configured (no OPENROUTER_API_KEY on the server)");
  const who = await reporterHash(req, d), day = today();
  const u = await d.store.usage(day, who);
  if (u.calls >= d.config.perIpPerDay) return err("daily limit reached");
  if (u.usd >= d.config.dailyBudgetUsd) return err("daily budget reached");
  await d.store.incUsage(day, who);
  let out: { readings: LlmReading[]; cost: number };
  try {
    out = await callReaders(body.image_jpeg_b64, d);
  } catch (e) {
    return err(`vision model failed: ${(e as Error)?.name ?? "Error"}`);
  }
  const reading = out.readings.find((r) => r.legible && consistent(r)) ?? out.readings[0];
  // diagnostics: what the readers transcribed (product text only; no image, no user data)
  for (const r of out.readings) {
    d.log(`resolve: legible=${r.legible} brand=${JSON.stringify(r.brand)} form=${JSON.stringify(r.dosage_form)} ` +
      `ingredients=${JSON.stringify(r.ingredients.map((i) => [i.name, i.strength]))}`);
  }
  d.log(`resolve: ${out.readings.length} readings, cost=${out.cost}`);
  const cacheable = reading.legible && consistent(reading) && (reading.brand || reading.composition_text) && sig.split(" ").length >= 4;
  await d.store.recordCall(day, out.cost, d.config.models[0] ?? "", cacheable ? { sig, readings: JSON.stringify(out.readings) } : null);
  return json({ reading, readings: out.readings, source: "llm", cost_usd: out.cost, error: null });
}

async function events(req: Request, d: Deps): Promise<Response> {
  const body = await req.json();
  if (!Array.isArray(body)) throw new BadRequest(422, "expected a list of events");
  if (body.length > 500) throw new BadRequest(413, "too many events");
  const parsed = body.map(parseEvent);
  const now = new Date().toISOString().slice(0, 19) + "Z";
  await d.store.insertEvents(parsed.map((e) => ({ ...e, received_at: now })));
  return json({ accepted: parsed.length });
}

async function exportEvents(req: Request, url: URL, d: Deps): Promise<Response> {
  // fail closed: without a configured token the export is off, so a misconfigured deployment never leaks events
  if (!d.config.exportToken || !safeEqual(req.headers.get("authorization") ?? "", `Bearer ${d.config.exportToken}`)) {
    return json({ detail: "export token required" }, 401);
  }
  const since = url.searchParams.get("since") ?? "1970-01-01T00:00:00Z";
  const limit = Math.min(Number(url.searchParams.get("limit") ?? 10000) || 10000, 50000);
  return json(await d.store.exportEvents(since, limit));
}

async function reportAlias(req: Request, d: Deps): Promise<Response> {
  const a = parseAlias(await req.json());
  const n = await d.store.reportAlias(normKey(a.phrase), a.base_key, a.db_version, await reporterHash(req, d), today(), d.config.minReporters);
  return json({ reports: n, promoted: n >= d.config.minReporters });
}

async function reportLearned(req: Request, d: Deps): Promise<Response> {
  const r = parseLearned(await req.json());
  const n = await d.store.reportLearned({ key: normKey(r.brand_key), items: r.items, display_name: r.display_name, form: r.form, dbv: r.db_version },
    await reporterHash(req, d), today(), d.config.minReporters);
  return json({ reports: n, promoted: n >= d.config.minReporters });
}

const since = (u: URL) => Number(u.searchParams.get("since") ?? 0) || 0;
const dbv = (u: URL) => u.searchParams.get("db_version") ?? "dev";

export async function handle(req: Request, d: Deps): Promise<Response> {
  const url = new URL(req.url);
  // Supabase serves this function under /functions/v1/api; accept the bare paths too (tests, other hosts)
  const path = url.pathname.replace(/^\/functions\/v1/, "").replace(/^\/api(?=\/|$)/, "") || "/";
  const route = `${req.method} ${path}`;
  try {
    switch (route) {
      case "GET /health":
        return json({ ok: true, llm_configured: !!d.config.openrouterKey, models: d.config.models, store: "supabase" });
      case "GET /v1/ip": {
        // setup check for rate limiting: what this caller counts as, and which proxy headers arrived (their own only)
        const names = ["x-forwarded-for", "x-real-ip", "cf-connecting-ip", "true-client-ip", "x-client-ip", "x-envoy-external-address"];
        const headers = Object.fromEntries(names.filter((n) => req.headers.get(n)).map((n) => [n, req.headers.get(n)]));
        return json({ counted_as: clientIp(req, d.config), headers });
      }
      case "POST /v1/resolve": return await resolve(req, d);
      case "POST /v1/events": return await events(req, d);
      case "GET /v1/events/export": return await exportEvents(req, url, d);
      case "POST /v1/aliases": return await reportAlias(req, d);
      case "GET /v1/aliases": return json({ aliases: await d.store.aliases(since(url), dbv(url)), now: Date.now() });
      case "POST /v1/learned": return await reportLearned(req, d);
      case "GET /v1/learned": return json({ products: await d.store.learned(since(url), dbv(url)), now: Date.now() });
      case "GET /v1/stats": return json(await d.store.stats());
      case "GET /v1/dashboard": {
        // public, aggregate-only (k-anonymous regions, no rows): any web page may load it
        const r = json(aggregate(await d.store.recentEvents(90), new Date()));
        r.headers.set("access-control-allow-origin", "*");
        r.headers.set("cache-control", "public, max-age=300");
        return r;
      }
      case "GET /v1/batch-alerts": {
        // the whole list since the phone's last sync: phones match batches locally, so no one's batches are sent here
        const alerts = await d.store.batchAlerts(since(url), 5000);
        return json({ alerts, last_id: alerts.length ? alerts[alerts.length - 1].id : since(url) });
      }
      case "POST /v1/admin/refresh-alerts": {
        if (!d.config.adminToken || !safeEqual(req.headers.get("authorization") ?? "", `Bearer ${d.config.adminToken}`)) {
          return json({ detail: "admin token required" }, 401);
        }
        // CDSCO's server is slow; one list and two months per call stays well inside the free 150 s request limit
        const tab = url.searchParams.get("tab");
        const rows = await fetchRecent(d.fetch, Number(url.searchParams.get("months") ?? 2) || 2, 1000,
          tab === "nsq" || tab === "spurious" ? tab : undefined);
        const added = await d.store.addBatchAlerts(rows);
        d.log(`refresh-alerts ${tab ?? "all"}: ${rows.length} records fetched, ${added} new`);
        return json({ fetched: rows.length, added });
      }
      case "POST /v1/admin/schedule-alerts": {
        // daily refresh inside the database (pg_cron + pg_net), so no laptop or external scheduler is needed
        if (!d.config.adminToken || !safeEqual(req.headers.get("authorization") ?? "", `Bearer ${d.config.adminToken}`)) {
          return json({ detail: "admin token required" }, 401);
        }
        if (!d.store.scheduleAlertRefresh) return json({ detail: "not supported by this store" }, 501);
        const self = `${d.config.publicUrl || url.origin}/functions/v1/api/v1/admin/refresh-alerts`;
        return json(await d.store.scheduleAlertRefresh(self, d.config.adminToken));
      }
      default: return json({ detail: "not found" }, 404);
    }
  } catch (e) {
    if (e instanceof BadRequest) return json({ detail: e.message }, e.status);
    if (e instanceof SyntaxError) return json({ detail: "invalid JSON" }, 400);
    d.log(`error on ${route}: ${(e as Error)?.name}: ${(e as Error)?.message}`);
    // admin endpoints check the token before doing anything that can throw, so only the operator sees the reason
    if (route.startsWith("POST /v1/admin/")) return json({ detail: `${(e as Error)?.name}: ${(e as Error)?.message}` }, 500);
    return json({ detail: "internal error" }, 500);
  }
}
