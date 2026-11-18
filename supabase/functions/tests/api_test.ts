// deno test supabase/functions/tests/  (no network, no database: in-memory store + fake OpenRouter)
import { assert, assertAlmostEquals, assertEquals } from "jsr:@std/assert@1.0.8";
import { configFromEnv, Deps, handle } from "../api/handler.ts";
import { consistent, ratio, tokenSetRatio } from "../api/logic.ts";
import { MemStore } from "../api/store.ts";

const JPEG = btoa("\xff\xd8" + "0".repeat(100));

type Reply = (model: string) => Response | Promise<Response>;

function deps(opts: { key?: string; token?: string; reply?: Reply } = {}) {
  const env: Record<string, string> = { TRUSTED_PROXY_HOPS: "1", EXPORT_TOKEN: opts.token ?? "test-token" };
  if (opts.key) env.OPENROUTER_API_KEY = opts.key;
  const calls: string[] = [];
  const store = new MemStore();
  const d: Deps = {
    store,
    config: configFromEnv((k) => env[k]),
    fetch: (async (_url: string, init: RequestInit) => {
      const model = JSON.parse(init.body as string).model;
      calls.push(model);
      return opts.reply ? opts.reply(model) : new Response("{}", { status: 500 });
    }) as typeof fetch,
    log: () => {},
  };
  return { d, store, calls };
}

const call = (d: Deps, method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
  handle(new Request(`https://x.supabase.co/functions/v1/api${path}`, {
    method, headers: { "content-type": "application/json", ...headers }, body: body === undefined ? undefined : JSON.stringify(body),
  }), d);

const completion = (reading: unknown, cost: number) =>
  new Response(JSON.stringify({ choices: [{ message: { content: "```json\n" + JSON.stringify(reading) + "\n```" } }], usage: { cost } }));

const reading = (ings: [string, string | null][], brand = "Becosules Z") => ({
  brand, strength: null, manufacturer: "Pfizer", dosage_form: "capsule", composition_text: null, expiry: "08/2027", legible: true,
  ingredients: ings.map(([name, strength]) => ({ name, strength })),
});

// ------------------------------------------------------------------------------------------------ ported from test_backend.py

Deno.test("events are validated and idempotent; time is cut to the hour", async () => {
  const { d } = deps();
  const e = { event_id: crypto.randomUUID(), ingredients: ["Azithromycin"], aware_group: "Watch", is_antibiotic: true, banned: false,
    pincode: "201309", scanned_at: "2026-11-19T10:37:12Z" };
  assertEquals((await call(d, "POST", "/v1/events", [e, e])).status, 200);
  const rows = await (await call(d, "GET", "/v1/events/export", undefined, { authorization: "Bearer test-token" })).json();
  assertEquals(rows.length, 1);
  assertEquals(rows[0].scanned_at, "2026-11-19T10:00:00Z");
  assertEquals((await call(d, "POST", "/v1/events", [{ ...e, event_id: crypto.randomUUID(), pincode: "12" }])).status, 422);
  assertEquals((await call(d, "POST", "/v1/events", [{ ...e, event_id: crypto.randomUUID(), aware_group: "Bogus" }])).status, 422);
  assertEquals((await call(d, "POST", "/v1/events", [{ ...e, event_id: "not-a-uuid" }])).status, 422);
});

Deno.test("resolve without a key says so; non-JPEG is rejected", async () => {
  const { d } = deps();
  const r = await (await call(d, "POST", "/v1/resolve", { image_jpeg_b64: JPEG, ocr_tokens: ["x"] })).json();
  assertEquals(r.source, "error");
  assert(r.error.includes("not configured"));
  assertEquals((await call(d, "POST", "/v1/resolve", { image_jpeg_b64: btoa("GIF89a") })).status, 400);
});

Deno.test("shared cache hit needs no model call", async () => {
  const { d, store, calls } = deps({ key: "k" });
  await store.recordCall("2026-01-01", 0, "m", { sig: "alembic azithral azithromycin tablets", readings: JSON.stringify({ brand: "Azithral 500", legible: true }) });
  const r = await (await call(d, "POST", "/v1/resolve",
    { image_jpeg_b64: JPEG, ocr_tokens: ["Azithral", "500", "Azithromycin", "Tablets", "Alembic"] })).json();
  assertEquals(r.source, "server_cache");
  assertEquals(r.reading.brand, "Azithral 500");
  assertEquals(r.cost_usd, 0);
  assertEquals(calls.length, 0);
});

Deno.test("alias promoted only after two distinct reporters", async () => {
  const { d } = deps();
  const a = { phrase: "zif1 cv", base_key: "zifi cv", db_version: "v1" };
  assertEquals((await (await call(d, "POST", "/v1/aliases", a, { "x-forwarded-for": "1.1.1.1" })).json()).promoted, false);
  assertEquals((await (await call(d, "POST", "/v1/aliases", a, { "x-forwarded-for": "1.1.1.1" })).json()).promoted, false);
  assertEquals((await (await call(d, "POST", "/v1/aliases", a, { "x-forwarded-for": "2.2.2.2" })).json()).promoted, true);
  assertEquals((await (await call(d, "GET", "/v1/aliases?since=0&db_version=v1")).json()).aliases, [{ phrase: "zif1 cv", base_key: "zifi cv" }]);
});

Deno.test("learned product promoted only when two reporters agree", async () => {
  const { d } = deps();
  const r = { brand_key: "evion 200", display_name: "Evion 200", form: "capsule", items: "2817:200mg", db_version: "v1" };
  assertEquals((await (await call(d, "POST", "/v1/learned", r, { "x-forwarded-for": "3.3.3.3" })).json()).promoted, false);
  assertEquals((await (await call(d, "POST", "/v1/learned", { ...r, items: "2817:400mg" }, { "x-forwarded-for": "4.4.4.4" })).json()).promoted, false);
  assertEquals((await (await call(d, "POST", "/v1/learned", r, { "x-forwarded-for": "5.5.5.5" })).json()).promoted, true);
  assertEquals((await (await call(d, "GET", "/v1/learned?since=0&db_version=v1")).json()).products,
    [{ brand_key: "evion 200", display_name: "Evion 200", form: "capsule", items: "2817:200mg" }]);
  assertEquals((await call(d, "POST", "/v1/learned", { ...r, items: "DROP TABLE" })).status, 422);
});

Deno.test("export needs the token, and is off when no token is configured", async () => {
  const { d } = deps();
  assertEquals((await call(d, "GET", "/v1/events/export")).status, 401);
  assertEquals((await call(d, "GET", "/v1/events/export", undefined, { authorization: "Bearer wrong" })).status, 401);
  assertEquals((await call(d, "GET", "/v1/events/export", undefined, { authorization: "Bearer test-token" })).status, 200);
  const open = deps({ token: "" }).d;
  assertEquals((await call(open, "GET", "/v1/events/export", undefined, { authorization: "Bearer " })).status, 401);
});

Deno.test("a client-supplied X-Forwarded-For entry can't change the counted IP", async () => {
  const { d } = deps();
  const ip = async (xff: string) => (await (await call(d, "GET", "/v1/ip", undefined, { "x-forwarded-for": xff })).json()).counted_as;
  assertEquals(await ip("6.6.6.6, 9.9.9.9"), "9.9.9.9");
  assertEquals(await ip("7.7.7.7, 9.9.9.9"), "9.9.9.9");
});

// ------------------------------------------------------------------------------------------------ the reader path

const good = reading([["Thiamine Mononitrate IP", "10 mg"], ["Pyridoxine Hydrochloride IP", "3 mg"], ["Zinc Sulphate", "15 mg"]]);
const doubled = reading([["Pyridoxine IP", "3 mg"], ["Pyridoxine I.P.", "150 mg"]]); // same ingredient twice: a misread
const TOKENS = ["BECOSULES", "Capsules", "Thiamine", "Pyridoxine", "Zinc", "Pfizer"];

Deno.test("two readers in parallel: costs add up, the consistent reading leads, result is cached", async () => {
  const { d, store, calls } = deps({ key: "k", reply: (m) => completion(m.includes("gemma") ? doubled : good, 0.0001) });
  const r = await (await call(d, "POST", "/v1/resolve", { image_jpeg_b64: JPEG, ocr_tokens: TOKENS })).json();
  assertEquals(r.source, "llm");
  assertEquals(r.readings.length, 2);
  assertEquals(r.reading.ingredients.length, 3); // the reader that listed pyridoxine twice is not the lead
  assertAlmostEquals(r.cost_usd, 0.0002, 1e-12);
  assertEquals(calls.sort(), ["google/gemini-2.5-flash-lite", "google/gemma-4-31b-it"]);
  assertEquals(store.cache.length, 1);
  // the same strip again is served from the shared cache
  const again = await (await call(d, "POST", "/v1/resolve", { image_jpeg_b64: JPEG, ocr_tokens: TOKENS })).json();
  assertEquals(again.source, "server_cache");
  assertEquals(calls.length, 2);
});

Deno.test("a failing reader is replaced by the spare", async () => {
  const { d, calls } = deps({ key: "k", reply: (m) => m.includes("flash-lite") ? new Response("busy", { status: 429 }) : completion(good, 0.0001) });
  const r = await (await call(d, "POST", "/v1/resolve", { image_jpeg_b64: JPEG, ocr_tokens: TOKENS })).json();
  assertEquals(r.source, "llm");
  assertEquals(r.readings.length, 2);
  assert(calls.includes("google/gemma-4-26b-a4b-it"));
});

Deno.test("per-phone daily limit and daily budget stop model calls", async () => {
  const { d, store } = deps({ key: "k", reply: () => completion(good, 0.0001) });
  d.config.perIpPerDay = 1;
  const body = (t: string) => ({ image_jpeg_b64: JPEG, ocr_tokens: [t, "aaa", "bbb", "ccc"] });
  assertEquals((await (await call(d, "POST", "/v1/resolve", body("one"), { "x-forwarded-for": "8.8.8.8" })).json()).source, "llm");
  const limited = await (await call(d, "POST", "/v1/resolve", body("two"), { "x-forwarded-for": "8.8.8.8" })).json();
  assertEquals(limited.error, "daily limit reached");
  d.config.perIpPerDay = 80;
  store.spend.set(new Date().toISOString().slice(0, 10), 5);
  const broke = await (await call(d, "POST", "/v1/resolve", body("three"), { "x-forwarded-for": "9.9.9.9" })).json();
  assertEquals(broke.error, "daily budget reached");
});

Deno.test("all readers failing is reported, not guessed", async () => {
  const { d } = deps({ key: "k", reply: () => new Response("down", { status: 503 }) });
  const r = await (await call(d, "POST", "/v1/resolve", { image_jpeg_b64: JPEG, ocr_tokens: TOKENS })).json();
  assertEquals(r.source, "error");
  assert(r.error.startsWith("vision model failed"));
});

// ------------------------------------------------------------------------------------------------ fuzzy match parity

Deno.test("token_set_ratio matches rapidfuzz", () => {
  // reference values computed with rapidfuzz 3.11 (fuzz.token_set_ratio / fuzz.ratio)
  assertEquals(tokenSetRatio("amlopres cipla tablets telmisartan", "amlopres cipla tablets hydrochlorothiazide telmisartan"), 100);
  assertAlmostEquals(tokenSetRatio("dolo micro labs paracetamol tablets", "crocin gsk paracetamol tablets advance"), 71.2329, 1e-3);
  assertAlmostEquals(tokenSetRatio("becosules capsules pfizer riboflavin thiamine", "becosules capsule pfizer riboflavin thiamine niacinamide"), 88.8889, 1e-3);
  assertEquals(tokenSetRatio("abc", "xyz"), 0);
  assertAlmostEquals(ratio("amlopres cipla tablets telmisartan", "amlopres cipla tablets hydrochlorothiazide telmisartan"), 77.2727, 1e-3);
});

Deno.test("misread check: same ingredient twice is caught, B6 and B12 are different", () => {
  const r = (names: string[]) => ({ ...reading([]), ingredients: names.map((name) => ({ name, strength: null })) });
  assertEquals(consistent(r(["Pyridoxine IP", "Pyridoxine I.P."])), false);
  assertEquals(consistent(r(["Folic Acid B.P.", "Folic acid"])), false);
  assertEquals(consistent(r(["Vitamin B6", "Vitamin B12", "Vitamin B1"])), true);
  assertEquals(consistent(r(["Pyridoxine Hydrochloride IP", "Thiamine IP"])), true);
});

Deno.test("unknown routes are 404, bad JSON is 400", async () => {
  const { d } = deps();
  assertEquals((await call(d, "GET", "/nope")).status, 404);
  const bad = await handle(new Request("https://x/functions/v1/api/v1/events", { method: "POST", body: "{not json" }), d);
  assertEquals(bad.status, 400);
  const h = await (await call(d, "GET", "/health")).json();
  assertEquals(h.ok, true);
});

// ------------------------------------------------------------------------------------------------ CDSCO alert sync

import { batchNorm, parseRecord } from "../api/cdsco.ts";

const CDSCO_ROW = { str_product_name: "Telmisartan 40 mg and Amlodipine 5mg tablets IP (TELMA AM)", str_batch_no: "18230791",
  str_brand_name: "NA", str_manufactured_by: "Under Investigation", str_nsq_result: "Assay", str_reported_by_lab_or_state: "DTL, Delhi",
  dt_reporting_month_year: "AUG-2026" };

function cdscoDeps(admin = "adm") {
  const store = new MemStore();
  const urls: string[] = [];
  const d: Deps = {
    store, log: () => {},
    config: configFromEnv((k) => ({ ADMIN_TOKEN: admin } as Record<string, string>)[k]),
    fetch: (async (url: string) => {
      urls.push(url);
      const u = new URL(url);
      const body = u.pathname.endsWith("reportingYears") ? ["2025", "2026"]
        : u.pathname.endsWith("publicReportingMonths") ? (u.searchParams.get("year") === "2026" ? ["Jul", "Aug"] : ["Dec"])
        : u.pathname.includes("filtered") ? { aaData: u.searchParams.get("tab") === "spurious" ? [CDSCO_ROW, { ...CDSCO_ROW, str_batch_no: "NA" }] : [] }
        : "<html></html>";
      return new Response(typeof body === "string" ? body : JSON.stringify(body));
    }) as typeof fetch,
  };
  return { d, store, urls };
}

Deno.test("batch numbers fold the same way as the app and the DB build", () => {
  assertEquals(batchNorm("I823O791"), "18230791");
  assertEquals(batchNorm(" pr s-520 "), "PRS520");
  assertEquals(parseRecord({ ...CDSCO_ROW, str_batch_no: "NA" }, "NSQ"), null);
  assertEquals(parseRecord(CDSCO_ROW, "SPURIOUS")?.brand, null); // "NA" brand is no brand
});

Deno.test("alert refresh needs the admin token, fetches recent months, and never duplicates", async () => {
  const { d, store, urls } = cdscoDeps();
  const path = "/v1/admin/refresh-alerts?months=3";
  assertEquals((await call(d, "POST", path)).status, 401);
  assertEquals((await call(d, "POST", path, undefined, { authorization: "Bearer nope" })).status, 401);
  // (runs ~25 s: the polite one-second pause between CDSCO requests is real here too)
  const r1 = await (await handle(new Request(`https://x/functions/v1/api${path}`, { method: "POST", headers: { authorization: "Bearer adm" } }),
    { ...d, fetch: d.fetch })).json();
  assertEquals(r1.added, 1);
  assert(urls.some((u) => u.includes("month=Aug-2026")) && urls.some((u) => u.includes("month=Dec-2025")));
  const again = await (await call(d, "POST", path, undefined, { authorization: "Bearer adm" })).json();
  assertEquals(again.added, 0);
  const got = await (await call(d, "GET", "/v1/batch-alerts?since=0")).json();
  assertEquals(got.alerts.length, 1);
  assertEquals(got.alerts[0].batch_norm, "18230791");
  assertEquals((await (await call(d, "GET", `/v1/batch-alerts?since=${got.last_id}`)).json()).alerts.length, 0);
  assertEquals(store.alerts[0].kind, "SPURIOUS");
});

Deno.test("alert refresh is off when no admin token is configured", async () => {
  const { d } = cdscoDeps("");
  assertEquals((await call(d, "POST", "/v1/admin/refresh-alerts", undefined, { authorization: "Bearer " })).status, 401);
});

// ------------------------------------------------------------------------------------------------ prescription answer

Deno.test("the prescription answer updates the same anonymous event and is never erased by a re-send", async () => {
  const { d } = deps();
  const e = { event_id: crypto.randomUUID(), ingredients: ["Azithromycin"], aware_group: "Watch", is_antibiotic: true, banned: false,
    pincode: "201309", scanned_at: "2026-11-19T10:37:12Z" };
  const auth = { authorization: "Bearer test-token" };
  assertEquals((await call(d, "POST", "/v1/events", [e])).status, 200);
  assertEquals((await call(d, "POST", "/v1/events", [{ ...e, prescribed: false }])).status, 200);
  assertEquals((await call(d, "POST", "/v1/events", [e])).status, 200); // a retry of the original upload
  const rows = await (await call(d, "GET", "/v1/events/export", undefined, auth)).json();
  assertEquals(rows.length, 1);
  assertEquals(rows[0].prescribed, false);
  assertEquals((await (await call(d, "GET", "/v1/stats")).json()).antibiotic_without_prescription, 1);
  assertEquals((await call(d, "POST", "/v1/events", [{ ...e, prescribed: "no" }])).status, 422);
});

Deno.test("public dashboard: aggregates only, open to any page, small areas hidden", async () => {
  const { d } = deps();
  const mk = (pin: string) => ({ event_id: crypto.randomUUID(), ingredients: ["Azithromycin"], aware_group: "Watch", is_antibiotic: true,
    banned: false, pincode: pin, scanned_at: new Date().toISOString() });
  await call(d, "POST", "/v1/events", [...Array(5)].map(() => mk("201309")).concat([mk("560001")]));
  const r = await call(d, "GET", "/v1/dashboard");
  assertEquals(r.status, 200);
  assertEquals(r.headers.get("access-control-allow-origin"), "*");
  const body = await r.json();
  assertEquals(body.total_scans, 6);
  assertEquals(body.regions.length, 1);
  assertEquals(JSON.stringify(body).includes("event_id"), false);
});
