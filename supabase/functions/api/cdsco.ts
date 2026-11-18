// Recent CDSCO drug alerts (NSQ / spurious batches) from the public searchable alert page that CDSCO moved its monthly
// alerts to in Aug 2025 (https://cdscoonline.gov.in/CDSCO/viewPublicNSQDrug). Same endpoints the page itself calls,
// same normalisation and filters as data-build/build_db.py, so phones and the shipped DB agree.
import type { AlertRow } from "./store.ts";

const BASE = "https://cdscoonline.gov.in/CDSCO";
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** Upper-case letters+digits with O->0 and I/L->1 (same folding as build_db.py and core/BatchAlerts.kt). */
export function batchNorm(b: string): string {
  return (b ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
}

const NO_BRAND = new Set(["", "NA", "N/A", "-", "NIL"]);

/** One record from the CDSCO JSON, or null when it has no usable batch or product (same rules as the DB build). */
export function parseRecord(r: Record<string, unknown>, kind: "NSQ" | "SPURIOUS"): Omit<AlertRow, "id"> | null {
  const s = (k: string) => (typeof r[k] === "string" ? (r[k] as string).trim() : "");
  const batch = s("str_batch_no");
  const product = s("str_product_name") || s("product_name_from_dtl");
  const bn = batchNorm(batch);
  if (bn.length < 3 || !product || ["NA", "N0TMENT10NED", "N1L"].includes(bn)) return null;
  const brand = s("str_brand_name");
  return {
    batch_norm: bn, batch, product: product.slice(0, 200), brand: NO_BRAND.has(brand.toUpperCase()) ? null : brand,
    manufacturer: s("str_manufactured_by").slice(0, 200) || null,
    result: (s("str_nsq_result") || s("str_nsq_remarks")).slice(0, 300) || null, kind,
    reported_by: s("str_reported_by_lab_or_state").slice(0, 120) || null, month: s("dt_reporting_month_year").toUpperCase(),
  };
}

/** Alerts for the latest [months] reporting months of both lists. One request at a time, a second apart. */
export async function fetchRecent(fetchFn: typeof fetch, months = 3, pause = 1000,
                                  only?: "nsq" | "spurious"): Promise<Omit<AlertRow, "id">[]> {
  const wait = () => new Promise((r) => setTimeout(r, pause));
  let cookie = "";
  const get = async (path: string, q: Record<string, string> = {}) => {
    await wait();
    const r = await fetchFn(`${BASE}/${path}?${new URLSearchParams(q)}`, {
      headers: { "User-Agent": "Mozilla/5.0 (AMRit alert sync)", "Referer": `${BASE}/viewPublicNSQDrug`, ...(cookie ? { cookie } : {}) },
      signal: AbortSignal.timeout(60_000),
    });
    const set = r.headers.get("set-cookie");
    if (set) cookie = set.split(";")[0];
    if (!r.ok) throw new Error(`CDSCO ${path}: HTTP ${r.status}`);
    return new TextDecoder("utf-8", { fatal: false }).decode(await r.arrayBuffer());
  };
  await get("viewPublicNSQDrug");
  const out: Omit<AlertRow, "id">[] = [];
  const lists = [["nsq", "filteredNsqDrugTable", "NSQ"], ["spurious", "filteredSpuriousDrugTable", "SPURIOUS"]] as const;
  for (const [tab, endpoint, kind] of lists.filter(([t]) => !only || t === only)) {
    const years = (JSON.parse(await get("reportingYears", { tab })) as string[]).map(Number).sort((a, b) => b - a);
    const recent: string[] = [];
    for (const y of years.slice(0, 2)) {
      const ms = (JSON.parse(await get("publicReportingMonths", { year: String(y), tab })) as string[])
        .sort((a, b) => MONTHS.indexOf(b.slice(0, 3)) - MONTHS.indexOf(a.slice(0, 3)));
      for (const m of ms) if (recent.length < months) recent.push(`${m}-${y}`);
    }
    for (const month of recent) {
      const data = JSON.parse(await get(endpoint, { month, source: "All", tab }));
      for (const r of (data?.aaData ?? []) as Record<string, unknown>[]) {
        const row = parseRecord(r, kind);
        if (row) out.push(row);
      }
    }
  }
  return out;
}
