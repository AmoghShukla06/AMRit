// Pure logic shared by the handler and the tests: request validation, the model prompt/schema, the transcription
// consistency check, the OCR-token cache signature and fuzzy match. No I/O here.

export const MAX_IMAGE_BYTES = 600_000;

export class BadRequest extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

// ------------------------------------------------------------------------------------------------ validation helpers

type Json = Record<string, unknown>;

function obj(v: unknown, what: string): Json {
  if (typeof v !== "object" || v === null || Array.isArray(v)) throw new BadRequest(422, `${what} must be an object`);
  return v as Json;
}

/** Optional string field: control characters become spaces, blank becomes null, longer than [max] is an error. */
function optStr(v: unknown, max: number, what: string): string | null {
  if (v === undefined || v === null) return null;
  if (typeof v !== "string") throw new BadRequest(422, `${what} must be a string`);
  const s = v.replace(/[\x00-\x1f]+/g, " ").trim();
  if (s.length > max) throw new BadRequest(422, `${what} too long`);
  return s || null;
}

function reqStr(v: unknown, min: number, max: number, what: string): string {
  if (typeof v !== "string" || v.length < min || v.length > max) throw new BadRequest(422, `bad ${what}`);
  return v;
}

function bool(v: unknown, dflt: boolean, what: string): boolean {
  if (v === undefined || v === null) return dflt;
  if (typeof v !== "boolean") throw new BadRequest(422, `${what} must be a boolean`);
  return v;
}

// ------------------------------------------------------------------------------------------------ readings

export interface LlmIngredient { name: string; strength: string | null }

export interface LlmReading {
  brand: string | null;
  strength: string | null;
  manufacturer: string | null;
  dosage_form: string | null;
  ingredients: LlmIngredient[];
  composition_text: string | null;
  expiry: string | null;
  legible: boolean;
}

/** Validates a model's (or the cache's) transcription; anything malformed or oversized is rejected, not trimmed. */
export function parseReading(v: unknown): LlmReading {
  const o = obj(v, "reading");
  const ings = o.ingredients ?? [];
  if (!Array.isArray(ings) || ings.length > 15) throw new BadRequest(422, "bad ingredients");
  return {
    brand: optStr(o.brand, 80, "brand"),
    strength: optStr(o.strength, 40, "strength"),
    manufacturer: optStr(o.manufacturer, 120, "manufacturer"),
    dosage_form: optStr(o.dosage_form, 40, "dosage_form"),
    ingredients: ings.map((i, k) => {
      const io = obj(i, `ingredient ${k}`);
      const name = optStr(io.name, 80, "ingredient name");
      if (!name) throw new BadRequest(422, "ingredient without a name");
      return { name, strength: optStr(io.strength, 40, "ingredient strength") };
    }),
    composition_text: optStr(o.composition_text, 600, "composition_text"),
    expiry: optStr(o.expiry, 20, "expiry"),
    legible: bool(o.legible, true, "legible"),
  };
}

/**
 * A transcription that lists the same ingredient twice ("Pyridoxine 3 mg" and "Pyridoxine 150 mg") has misread
 * something; the phone would refuse it, so it doesn't count as a usable reading.
 */
export function consistent(r: LlmReading): boolean {
  // "Pyridoxine I.P." = "Pyridoxine IP" (punctuation, then the pharmacopoeia word, dropped); digits are kept so
  // "Vitamin B6" and "Vitamin B12" stay different
  const names = r.ingredients.map((i) =>
    i.name.toLowerCase().replace(/[^a-z0-9 ]/g, "").replace(/\b(ip|bp|usp)\b/g, "").replace(/ /g, "")
  );
  return names.length === new Set(names).size;
}

export const PROMPT = `You transcribe the text printed on a photo of an Indian medicine strip or box. You do not give medical
information and you do not guess.
Return JSON with:
- brand: the product/brand name exactly as printed (the most prominent name), without words like Tablets/Capsules. null if not readable.
- strength: the strength printed next to the brand (e.g. "650", "500 mg", "625"), null if none.
- manufacturer: the "Mfd./Mkd. by" company name, null if not readable.
- dosage_form: the form printed on the pack (tablet, capsule, syrup, injection, cream...), null if not printed.
- ingredients: every active ingredient listed in the composition, each as {name, strength} exactly as printed
  (e.g. {"name": "Paracetamol IP", "strength": "650 mg"}). Do not include excipients or colours. [] if not visible.
- composition_text: null (the ingredients list replaces it), unless no ingredient list is readable at all.
- expiry: the expiry date as printed after EXP (e.g. "08/2027" or "AUG 2027"), null if not visible. Not the MFG date.
- legible: false if you cannot read the brand or composition with confidence.
Only transcribe characters you can actually see. If a word is cut off or covered by glare, use null for that field.`;

export const SCHEMA = {
  type: "object",
  properties: {
    brand: { type: ["string", "null"] },
    strength: { type: ["string", "null"] },
    manufacturer: { type: ["string", "null"] },
    dosage_form: { type: ["string", "null"] },
    ingredients: {
      type: "array",
      items: {
        type: "object",
        properties: { name: { type: "string" }, strength: { type: ["string", "null"] } },
        required: ["name", "strength"],
        additionalProperties: false,
      },
    },
    composition_text: { type: ["string", "null"] },
    expiry: { type: ["string", "null"] },
    legible: { type: "boolean" },
  },
  required: ["brand", "strength", "manufacturer", "dosage_form", "ingredients", "composition_text", "expiry", "legible"],
  additionalProperties: false,
};

/** Pulls the JSON object out of a chat completion (tolerates code fences and content-part arrays). */
export function readingFromCompletion(d: Json): { reading: LlmReading; cost: number } {
  const choices = d.choices as Json[] | undefined;
  let content = (choices?.[0]?.message as Json | undefined)?.content as unknown;
  if (Array.isArray(content)) content = content.map((p) => (typeof p === "object" && p ? (p as Json).text ?? "" : "")).join("");
  if (typeof content !== "string") throw new Error("no content");
  const m = content.match(/\{[\s\S]*\}/);
  const reading = parseReading(JSON.parse(m ? m[0] : content));
  const cost = Number((d.usage as Json | undefined)?.cost ?? 0) || 0;
  return { reading, cost };
}

// ------------------------------------------------------------------------------------------------ requests

export interface ResolveRequest { image_jpeg_b64: string; ocr_tokens: string[]; db_version: string }

export function parseResolve(v: unknown): ResolveRequest {
  const o = obj(v, "body");
  const img = o.image_jpeg_b64;
  if (typeof img !== "string" || img.length > Math.floor(MAX_IMAGE_BYTES * 4 / 3) + 16) throw new BadRequest(422, "bad image_jpeg_b64");
  const toks = o.ocr_tokens ?? [];
  if (!Array.isArray(toks) || toks.length > 80 || toks.some((t) => typeof t !== "string" || t.length > 80)) {
    throw new BadRequest(422, "bad ocr_tokens");
  }
  return { image_jpeg_b64: img, ocr_tokens: toks as string[], db_version: reqStr(o.db_version ?? "dev", 0, 40, "db_version") };
}

/** Decodes and checks the image (a JPEG under 600 KB). The bytes are only used for this check and never stored. */
export function checkJpeg(b64: string): void {
  let bytes: Uint8Array;
  try {
    const bin = atob(b64);
    bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  } catch {
    throw new BadRequest(400, "bad image");
  }
  if (bytes.length > MAX_IMAGE_BYTES || bytes[0] !== 0xff || bytes[1] !== 0xd8) throw new BadRequest(400, "image must be a JPEG under 600 KB");
}

export interface ScanEvent {
  event_id: string;
  ingredients: string[];
  aware_group: "Access" | "Watch" | "Reserve" | null;
  is_antibiotic: boolean;
  banned: boolean;
  pincode: string | null;
  scanned_at: string;
  /** answer to "was this antibiotic sold with a prescription?"; null = not asked / skipped */
  prescribed: boolean | null;
}

const UUID = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;

/** One anonymous scan event. Times are cut to the hour; pincodes must be real 6-digit Indian pincodes. */
export function parseEvent(v: unknown): ScanEvent {
  const o = obj(v, "event");
  const id = reqStr(o.event_id, 32, 36, "event_id");
  if (!UUID.test(id)) throw new BadRequest(422, "bad event_id");
  const hex = id.replace(/-/g, "").toLowerCase();
  const ings = o.ingredients;
  if (!Array.isArray(ings) || ings.length > 25 || ings.some((x) => typeof x !== "string" || x.length > 80)) {
    throw new BadRequest(422, "bad ingredients");
  }
  const ag = o.aware_group ?? null;
  if (ag !== null && ag !== "Access" && ag !== "Watch" && ag !== "Reserve") throw new BadRequest(422, "bad aware_group");
  let pin = o.pincode ?? null;
  if (pin === "") pin = null;
  if (pin !== null && (typeof pin !== "string" || !/^[1-9][0-9]{5}$/.test(pin))) throw new BadRequest(422, "bad pincode");
  const t = new Date(reqStr(o.scanned_at, 10, 40, "scanned_at"));
  if (isNaN(t.getTime())) throw new BadRequest(422, "bad scanned_at");
  t.setUTCMinutes(0, 0, 0);
  return {
    event_id: `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`,
    ingredients: [...new Set((ings as string[]).map((x) => x.trim()).filter((x) => x))].sort(),
    aware_group: ag as ScanEvent["aware_group"],
    is_antibiotic: bool(o.is_antibiotic, false, "is_antibiotic"),
    banned: bool(o.banned, false, "banned"),
    pincode: pin as string | null,
    scanned_at: t.toISOString().slice(0, 13) + ":00:00Z",
    prescribed: o.prescribed === undefined || o.prescribed === null ? null : bool(o.prescribed, false, "prescribed"),
  };
}

export interface AliasReport { phrase: string; base_key: string; db_version: string }

export function parseAlias(v: unknown): AliasReport {
  const o = obj(v, "body");
  return {
    phrase: reqStr(o.phrase, 3, 60, "phrase"),
    base_key: reqStr(o.base_key, 2, 60, "base_key"),
    db_version: reqStr(o.db_version ?? "dev", 0, 40, "db_version"),
  };
}

export interface LearnedReport { brand_key: string; display_name: string; form: string; items: string; db_version: string }

export function parseLearned(v: unknown): LearnedReport {
  const o = obj(v, "body");
  const items = reqStr(o.items, 3, 300, "items");
  if (!/^\d+:([0-9.]+[A-Za-z/0-9]*)?(;\d+:([0-9.]+[A-Za-z/0-9]*)?)*$/.test(items)) throw new BadRequest(422, "bad items");
  return {
    brand_key: reqStr(o.brand_key, 3, 60, "brand_key"),
    display_name: reqStr(o.display_name, 2, 80, "display_name"),
    form: reqStr(o.form ?? "tablet", 0, 30, "form"),
    items,
    db_version: reqStr(o.db_version ?? "dev", 0, 40, "db_version"),
  };
}

export const normKey = (s: string) => s.toLowerCase().replace(/[^a-z0-9 ]/g, "").trim();

// ------------------------------------------------------------------------------------------------ cache matching

/** Signature of a strip from its OCR tokens: distinct words of 3+ letters, sorted. */
export function tokenSig(tokens: string[]): string {
  const toks = [...new Set(tokens.filter((t) => t.length >= 3 && !/^\d+$/.test(t)).map((t) => t.toLowerCase()))].sort();
  return toks.slice(0, 60).join(" ");
}

/** Indel similarity in 0..100 (same as rapidfuzz's ratio): 2*LCS / (len a + len b). */
export function ratio(a: string, b: string): number {
  if (!a.length && !b.length) return 100;
  if (!a.length || !b.length) return 0;
  let prev = new Uint16Array(b.length + 1), cur = new Uint16Array(b.length + 1);
  for (let i = 1; i <= a.length; i++) {
    const ca = a.charCodeAt(i - 1);
    for (let j = 1; j <= b.length; j++) {
      cur[j] = ca === b.charCodeAt(j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
    }
    [prev, cur] = [cur, prev];
  }
  return (200 * prev[b.length]) / (a.length + b.length);
}

/** rapidfuzz/fuzzywuzzy token_set_ratio: word sets compared through their shared part. */
export function tokenSetRatio(s1: string, s2: string): number {
  const a = new Set(s1.split(/\s+/).filter((x) => x)), b = new Set(s2.split(/\s+/).filter((x) => x));
  if (!a.size || !b.size) return 0;
  const inter = [...a].filter((x) => b.has(x)).sort();
  const da = [...a].filter((x) => !b.has(x)).sort(), db = [...b].filter((x) => !a.has(x)).sort();
  if (inter.length && (!da.length || !db.length)) return 100;
  const t0 = inter.join(" ");
  const t1 = [t0, da.join(" ")].filter((x) => x).join(" ");
  const t2 = [t0, db.join(" ")].filter((x) => x).join(" ");
  return Math.max(ratio(t0, t1), ratio(t0, t2), ratio(t1, t2));
}

/**
 * Best cached signature for [sig], or null. A cheap word-overlap prefilter keeps the exact (quadratic) comparison
 * to a handful of rows, so the lookup stays inside the free tier's CPU budget as the cache grows.
 */
export function bestCacheMatch(sig: string, rows: { id: number; sig: string }[], minScore: number): number | null {
  if (sig.split(" ").length < 4 || !rows.length) return null;
  const mine = new Set(sig.split(" "));
  const overlap = (s: string) => { const t = s.split(" "); let n = 0; for (const w of t) if (mine.has(w)) n++; return n / Math.max(1, Math.min(t.length, mine.size)); };
  const top = rows.map((r) => ({ r, o: overlap(r.sig) })).filter((x) => x.o >= 0.5).sort((x, y) => y.o - x.o).slice(0, 12);
  let best: { id: number; score: number } | null = null;
  for (const { r } of top) {
    const s = tokenSetRatio(sig, r.sig);
    if (!best || s > best.score) best = { id: r.id, score: s };
  }
  return best && best.score >= minScore ? best.id : null;
}

// ------------------------------------------------------------------------------------------------ misc

/** Constant-time comparison for the export token. */
export function safeEqual(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a), y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

export const today = () => new Date().toISOString().slice(0, 10);

// ------------------------------------------------------------------------------------------------ public dashboard

export interface DashEvent { ingredients: string[]; aware_group: string | null; is_antibiotic: boolean; banned: boolean;
  pincode: string | null; scanned_at: string; prescribed: boolean | null }

/**
 * Public, privacy-safe summary of the anonymous scans. Areas are 3-digit postal regions (never a full pincode) and are
 * shown only with at least [k] scans; the prescription share only once [k] answers exist. No raw rows leave the server.
 */
export function aggregate(rows: DashEvent[], now: Date, k = 5): Record<string, unknown> {
  const abx = rows.filter((r) => r.is_antibiotic);
  const group = (g: string) => abx.filter((r) => r.aware_group === g).length;
  const wr = (rs: DashEvent[]) => {
    const a = rs.filter((r) => r.is_antibiotic);
    return a.length ? a.filter((r) => r.aware_group === "Watch" || r.aware_group === "Reserve").length / a.length : null;
  };
  const byRegion = new Map<string, DashEvent[]>();
  for (const r of rows) if (r.pincode && /^[1-9]\d{5}$/.test(r.pincode)) {
    const reg = r.pincode.slice(0, 3);
    byRegion.set(reg, [...(byRegion.get(reg) ?? []), r]);
  }
  const shown = [...byRegion.entries()].filter(([, rs]) => rs.length >= k);
  const answered = abx.filter((r) => r.prescribed !== null);
  const noRx = answered.filter((r) => r.prescribed === false).length;
  const counts = new Map<string, number>();
  for (const r of abx) for (const i of r.ingredients) counts.set(i, (counts.get(i) ?? 0) + 1);
  const today = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const daily = [...Array(30)].map((_, i) => {
    const day = new Date(today.getTime() - (29 - i) * 86_400_000).toISOString().slice(0, 10);
    const rs = rows.filter((r) => r.scanned_at.slice(0, 10) === day);
    return { day, scans: rs.length, antibiotic: rs.filter((r) => r.is_antibiotic).length };
  });
  return {
    total_scans: rows.length,
    antibiotic_scans: abx.length,
    by_group: { Access: group("Access"), Watch: group("Watch"), Reserve: group("Reserve") },
    watch_reserve_share: wr(rows),
    banned_scans: rows.filter((r) => r.banned).length,
    rx_answered: answered.length,
    without_prescription_share: answered.length >= k ? noRx / answered.length : null,
    regions: shown.map(([region, rs]) => ({ region, scans: rs.length, antibiotic_scans: rs.filter((r) => r.is_antibiotic).length,
      watch_reserve_share: wr(rs) })).sort((a, b) => b.scans - a.scans),
    hidden_regions: byRegion.size - shown.length,
    top_antibiotics: [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10).map(([name, scans]) => ({ name, scans })),
    daily,
    k,
    updated_at: now.toISOString(),
  };
}
