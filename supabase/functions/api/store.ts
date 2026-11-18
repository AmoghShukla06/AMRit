// Storage behind one interface: Postgres in production (Supabase), an in-memory fake in tests.
import postgres from "npm:postgres@3.4.5";

export interface EventRow {
  event_id: string; ingredients: string[]; aware_group: string | null; is_antibiotic: boolean; banned: boolean;
  pincode: string | null; scanned_at: string; received_at: string; prescribed: boolean | null;
}

export interface Store {
  /** Per-day random salt for hashing caller IPs; older days' salts are deleted, so old hashes can't be linked. */
  daySalt(day: string): Promise<string>;
  cacheRows(limit: number): Promise<{ id: number; sig: string }[]>;
  cacheReading(id: number): Promise<string | null>;
  usage(day: string, who: string): Promise<{ calls: number; usd: number }>;
  incUsage(day: string, who: string): Promise<void>;
  recordCall(day: string, cost: number, model: string, cache: { sig: string; readings: string } | null): Promise<void>;
  insertEvents(rows: EventRow[]): Promise<void>;
  exportEvents(since: string, limit: number): Promise<EventRow[]>;
  reportAlias(phrase: string, baseKey: string, dbv: string, who: string, day: string, min: number): Promise<number>;
  aliases(since: number, dbv: string): Promise<{ phrase: string; base_key: string }[]>;
  reportLearned(r: { key: string; items: string; display_name: string; form: string; dbv: string }, who: string, day: string, min: number): Promise<number>;
  learned(since: number, dbv: string): Promise<{ brand_key: string; display_name: string; form: string; items: string }[]>;
  stats(): Promise<Record<string, unknown>>;
  /** Events scanned in the last [days] days, for the public aggregate (never returned raw). */
  recentEvents(days: number): Promise<EventRow[]>;
  /** CDSCO NSQ/spurious batch records added after [sinceId], oldest first. */
  batchAlerts(sinceId: number, limit: number): Promise<AlertRow[]>;
  /** Inserts records not already present (same kind, batch, product, month); returns how many were new. */
  addBatchAlerts(rows: Omit<AlertRow, "id">[]): Promise<number>;
  /** Daily database-side schedule that calls [url] with the admin token (Postgres only). */
  scheduleAlertRefresh?(url: string, token: string): Promise<Record<string, unknown>>;
}

export interface AlertRow {
  id: number; batch_norm: string; batch: string; product: string; brand: string | null; manufacturer: string | null;
  result: string | null; kind: "NSQ" | "SPURIOUS"; reported_by: string | null; month: string;
}

// Tables live in their own schema: Supabase exposes `public` through its REST API (reachable with the public anon
// key), while `amrit` is reachable only by this function's direct database connection.
const SCHEMA_SQL = `
create schema if not exists amrit;
create table if not exists amrit.scan_events(
  event_id text primary key, ingredients jsonb not null, aware_group text, is_antibiotic boolean not null,
  banned boolean not null, pincode text, scanned_at text not null, received_at text not null);
alter table amrit.scan_events add column if not exists prescribed boolean;
create index if not exists scan_events_received on amrit.scan_events(received_at);
create index if not exists scan_events_pin_time on amrit.scan_events(pincode, scanned_at);
create table if not exists amrit.llm_cache(id bigserial primary key, ocr_sig text not null, reading text not null,
  created_at bigint, hits integer default 0);
create table if not exists amrit.llm_calls(ts bigint, cost_usd double precision, model text);
create table if not exists amrit.usage(day text, who text, calls integer, primary key(day, who));
create table if not exists amrit.spend(day text primary key, usd double precision);
create table if not exists amrit.salt(day text primary key, salt text not null);
create table if not exists amrit.alias_reports(phrase text, base_key text, db_version text, who text, day text,
  primary key(phrase, base_key, db_version, who, day));
create table if not exists amrit.aliases(phrase text, base_key text, db_version text, promoted_at bigint,
  primary key(phrase, db_version));
create table if not exists amrit.learned_reports(brand_key text, items text, display_name text, form text, db_version text,
  who text, day text, primary key(brand_key, items, db_version, who, day));
create table if not exists amrit.learned_products(brand_key text, display_name text, form text, items text,
  db_version text, promoted_at bigint, primary key(brand_key, db_version));
create table if not exists amrit.batch_alert(id bigserial primary key, batch_norm text not null, batch text not null,
  product text not null, brand text, manufacturer text, result text, kind text not null, reported_by text, month text not null,
  added_at bigint not null, unique(kind, batch_norm, product, month));
`;

export class PgStore implements Store {
  private sql: ReturnType<typeof postgres>;
  private ready: Promise<void> | null = null;

  constructor(url: string) {
    // prepare:false works through Supabase's transaction pooler as well as a direct connection
    this.sql = postgres(url, { prepare: false, max: 3, idle_timeout: 20, connect_timeout: 10 });
  }

  private init(): Promise<void> {
    return (this.ready ??= this.sql.unsafe(SCHEMA_SQL).then(() => undefined).catch((e) => { this.ready = null; throw e; }));
  }

  private async q<T = Record<string, unknown>>(strings: TemplateStringsArray, ...v: unknown[]): Promise<T[]> {
    await this.init();
    // deno-lint-ignore no-explicit-any
    return (await this.sql(strings, ...(v as any[]))) as unknown as T[];
  }

  async daySalt(day: string): Promise<string> {
    const fresh = Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, "0")).join("");
    await this.q`insert into amrit.salt(day, salt) values(${day}, ${fresh}) on conflict do nothing`;
    await this.q`delete from amrit.salt where day < ${day}`;
    const r = await this.q<{ salt: string }>`select salt from amrit.salt where day = ${day}`;
    return r[0].salt;
  }

  async cacheRows(limit: number) {
    const r = await this.q<{ id: string; sig: string }>`select id, ocr_sig as sig from amrit.llm_cache order by id desc limit ${limit}`;
    return r.map((x) => ({ id: Number(x.id), sig: x.sig }));
  }

  async cacheReading(id: number) {
    const r = await this.q<{ reading: string }>`update amrit.llm_cache set hits = hits + 1 where id = ${id} returning reading`;
    return r[0]?.reading ?? null;
  }

  async usage(day: string, who: string) {
    const c = await this.q<{ calls: number }>`select calls from amrit.usage where day = ${day} and who = ${who}`;
    const s = await this.q<{ usd: number }>`select usd from amrit.spend where day = ${day}`;
    return { calls: c[0]?.calls ?? 0, usd: s[0]?.usd ?? 0 };
  }

  async incUsage(day: string, who: string) {
    await this.q`insert into amrit.usage(day, who, calls) values(${day}, ${who}, 1)
      on conflict(day, who) do update set calls = amrit.usage.calls + 1`;
  }

  async recordCall(day: string, cost: number, model: string, cache: { sig: string; readings: string } | null) {
    await this.init();
    await this.sql.begin(async (tx) => {
      await tx`insert into amrit.spend(day, usd) values(${day}, ${cost}) on conflict(day) do update set usd = amrit.spend.usd + excluded.usd`;
      await tx`insert into amrit.llm_calls(ts, cost_usd, model) values(${Math.floor(Date.now() / 1000)}, ${cost}, ${model})`;
      if (cache) {
        await tx`insert into amrit.llm_cache(ocr_sig, reading, created_at, hits)
          values(${cache.sig}, ${cache.readings}, ${Math.floor(Date.now() / 1000)}, 0)`;
      }
    });
  }

  async insertEvents(rows: EventRow[]) {
    if (!rows.length) return;
    await this.init();
    await this.sql.begin(async (tx) => {
      for (const e of rows) {
        // a re-send of the same event may carry the prescription answer; a later re-send without it never erases it
        await tx`insert into amrit.scan_events(event_id, ingredients, aware_group, is_antibiotic, banned, pincode, scanned_at, received_at, prescribed)
          values(${e.event_id}, ${tx.json(e.ingredients)}, ${e.aware_group}, ${e.is_antibiotic}, ${e.banned}, ${e.pincode},
                 ${e.scanned_at}, ${e.received_at}, ${e.prescribed})
          on conflict (event_id) do update set prescribed = coalesce(excluded.prescribed, amrit.scan_events.prescribed)`;
      }
    });
  }

  async exportEvents(since: string, limit: number) {
    return await this.q<EventRow>`select event_id, ingredients, aware_group, is_antibiotic, banned, pincode, scanned_at, received_at, prescribed
      from amrit.scan_events where received_at > ${since} order by received_at limit ${limit}`;
  }

  async reportAlias(phrase: string, baseKey: string, dbv: string, who: string, day: string, min: number) {
    await this.q`insert into amrit.alias_reports(phrase, base_key, db_version, who, day)
      values(${phrase}, ${baseKey}, ${dbv}, ${who}, ${day}) on conflict do nothing`;
    const r = await this.q<{ n: string }>`select count(distinct who || day) as n from amrit.alias_reports
      where phrase = ${phrase} and base_key = ${baseKey} and db_version = ${dbv}`;
    const n = Number(r[0].n);
    if (n >= min) {
      await this.q`insert into amrit.aliases(phrase, base_key, db_version, promoted_at)
        values(${phrase}, ${baseKey}, ${dbv}, ${Date.now()}) on conflict do nothing`;
    }
    return n;
  }

  async aliases(since: number, dbv: string) {
    return await this.q<{ phrase: string; base_key: string }>`select phrase, base_key from amrit.aliases
      where promoted_at > ${since} and db_version = ${dbv}`;
  }

  async reportLearned(r: { key: string; items: string; display_name: string; form: string; dbv: string }, who: string, day: string, min: number) {
    await this.q`insert into amrit.learned_reports(brand_key, items, display_name, form, db_version, who, day)
      values(${r.key}, ${r.items}, ${r.display_name}, ${r.form}, ${r.dbv}, ${who}, ${day}) on conflict do nothing`;
    const c = await this.q<{ n: string }>`select count(distinct who || day) as n from amrit.learned_reports
      where brand_key = ${r.key} and items = ${r.items} and db_version = ${r.dbv}`;
    const n = Number(c[0].n);
    if (n >= min) {
      await this.q`insert into amrit.learned_products(brand_key, display_name, form, items, db_version, promoted_at)
        values(${r.key}, ${r.display_name}, ${r.form}, ${r.items}, ${r.dbv}, ${Date.now()})
        on conflict(brand_key, db_version) do update set display_name = excluded.display_name, form = excluded.form,
          items = excluded.items, promoted_at = excluded.promoted_at`;
    }
    return n;
  }

  async learned(since: number, dbv: string) {
    return await this.q<{ brand_key: string; display_name: string; form: string; items: string }>`
      select brand_key, display_name, form, items from amrit.learned_products where promoted_at > ${since} and db_version = ${dbv}`;
  }

  async recentEvents(days: number) {
    const since = new Date(Date.now() - days * 86_400_000).toISOString();
    return await this.q<EventRow>`select event_id, ingredients, aware_group, is_antibiotic, banned, pincode, scanned_at, received_at, prescribed
      from amrit.scan_events where scanned_at >= ${since}`;
  }

  async batchAlerts(sinceId: number, limit: number) {
    const r = await this.q<AlertRow & { id: string }>`select id, batch_norm, batch, product, brand, manufacturer, result, kind,
      reported_by, month from amrit.batch_alert where id > ${sinceId} order by id limit ${limit}`;
    return r.map((x) => ({ ...x, id: Number(x.id) }));
  }

  async addBatchAlerts(rows: Omit<AlertRow, "id">[]) {
    if (!rows.length) return 0;
    await this.init();
    let n = 0;
    await this.sql.begin(async (tx) => {
      for (const a of rows) {
        const r = await tx`insert into amrit.batch_alert(batch_norm, batch, product, brand, manufacturer, result, kind,
            reported_by, month, added_at)
          values(${a.batch_norm}, ${a.batch}, ${a.product}, ${a.brand}, ${a.manufacturer}, ${a.result}, ${a.kind},
            ${a.reported_by}, ${a.month}, ${Date.now()})
          on conflict do nothing returning id`;
        n += r.length;
      }
    });
    return n;
  }

  async scheduleAlertRefresh(url: string, token: string) {
    await this.init();
    await this.sql.unsafe("create extension if not exists pg_net");
    await this.sql.unsafe("create extension if not exists pg_cron");
    const jobs: Record<string, number> = {};
    // 03:30 and 03:45 UTC (09:00 / 09:15 IST), one list per call
    for (const [tab, minute] of [["nsq", 30], ["spurious", 45]] as const) {
      const command = `select net.http_post(url := '${url}?tab=${tab}&months=2', ` +
        `headers := jsonb_build_object('Authorization', 'Bearer ${token.replace(/'/g, "''")}'), timeout_milliseconds := 150000)`;
      const [r] = await this.sql`select cron.schedule(${`amrit-alerts-${tab}`}, ${`${minute} 3 * * *`}, ${command}) as id`;
      jobs[tab] = Number(r.id);
    }
    return { scheduled: jobs };
  }

  async stats() {
    const [ev] = await this.q<Record<string, string | null>>`select count(*) as n,
      count(*) filter (where is_antibiotic) as abx, count(*) filter (where aware_group = 'Access') as access,
      count(*) filter (where banned) as banned, count(*) filter (where is_antibiotic and prescribed = false) as no_rx from amrit.scan_events`;
    const [calls] = await this.q<Record<string, string | number>>`select count(*) as n, coalesce(sum(cost_usd), 0) as usd from amrit.llm_calls`;
    const [hits] = await this.q<Record<string, string>>`select coalesce(sum(hits), 0) as hits, count(*) as n from amrit.llm_cache`;
    const [al] = await this.q<Record<string, string>>`select count(*) as n from amrit.aliases`;
    const abx = Number(ev.abx);
    return {
      events: Number(ev.n), antibiotic_events: abx, access_share: abx ? Number(ev.access) / abx : null,
      banned_events: Number(ev.banned), antibiotic_without_prescription: Number(ev.no_rx), llm_calls: Number(calls.n), llm_cost_usd: Math.round(Number(calls.usd) * 1e6) / 1e6,
      server_cache_hits: Number(hits.hits), cache_entries: Number(hits.n), learned_aliases: Number(al.n),
    };
  }
}

/** In-memory store with the same behaviour, for tests. */
export class MemStore implements Store {
  salts = new Map<string, string>();
  cache: { id: number; sig: string; reading: string; hits: number }[] = [];
  use = new Map<string, number>();
  spend = new Map<string, number>();
  calls: { cost: number; model: string }[] = [];
  events = new Map<string, EventRow>();
  aliasReports = new Set<string>();
  aliasRows = new Map<string, { phrase: string; base_key: string; dbv: string; at: number }>();
  learnedReports = new Set<string>();
  learnedRows = new Map<string, { brand_key: string; display_name: string; form: string; items: string; dbv: string; at: number }>();

  async daySalt(day: string) {
    if (!this.salts.has(day)) this.salts.set(day, crypto.randomUUID());
    for (const d of [...this.salts.keys()]) if (d < day) this.salts.delete(d);
    return this.salts.get(day)!;
  }
  async cacheRows(limit: number) { return [...this.cache].reverse().slice(0, limit).map((c) => ({ id: c.id, sig: c.sig })); }
  async cacheReading(id: number) { const c = this.cache.find((x) => x.id === id); if (!c) return null; c.hits++; return c.reading; }
  async usage(day: string, who: string) { return { calls: this.use.get(`${day}|${who}`) ?? 0, usd: this.spend.get(day) ?? 0 }; }
  async incUsage(day: string, who: string) { const k = `${day}|${who}`; this.use.set(k, (this.use.get(k) ?? 0) + 1); }
  async recordCall(day: string, cost: number, model: string, cache: { sig: string; readings: string } | null) {
    this.spend.set(day, (this.spend.get(day) ?? 0) + cost);
    this.calls.push({ cost, model });
    if (cache) this.cache.push({ id: this.cache.length + 1, sig: cache.sig, reading: cache.readings, hits: 0 });
  }
  async insertEvents(rows: EventRow[]) {
    for (const e of rows) {
      const old = this.events.get(e.event_id);
      if (!old) this.events.set(e.event_id, e);
      else if (e.prescribed !== null) old.prescribed = e.prescribed;
    }
  }
  async exportEvents(since: string, limit: number) {
    return [...this.events.values()].filter((e) => e.received_at > since).sort((a, b) => a.received_at.localeCompare(b.received_at)).slice(0, limit);
  }
  private countDistinct(set: Set<string>, prefix: string) {
    return new Set([...set].filter((k) => k.startsWith(prefix)).map((k) => k.slice(prefix.length))).size;
  }
  async reportAlias(phrase: string, baseKey: string, dbv: string, who: string, day: string, min: number) {
    const p = `${phrase}|${baseKey}|${dbv}|`;
    this.aliasReports.add(p + `${who}${day}`);
    const n = this.countDistinct(this.aliasReports, p);
    if (n >= min && !this.aliasRows.has(`${phrase}|${dbv}`)) this.aliasRows.set(`${phrase}|${dbv}`, { phrase, base_key: baseKey, dbv, at: Date.now() });
    return n;
  }
  async aliases(since: number, dbv: string) {
    return [...this.aliasRows.values()].filter((a) => a.at > since && a.dbv === dbv).map((a) => ({ phrase: a.phrase, base_key: a.base_key }));
  }
  async reportLearned(r: { key: string; items: string; display_name: string; form: string; dbv: string }, who: string, day: string, min: number) {
    const p = `${r.key}|${r.items}|${r.dbv}|`;
    this.learnedReports.add(p + `${who}${day}`);
    const n = this.countDistinct(this.learnedReports, p);
    if (n >= min) this.learnedRows.set(`${r.key}|${r.dbv}`, { brand_key: r.key, display_name: r.display_name, form: r.form, items: r.items, dbv: r.dbv, at: Date.now() });
    return n;
  }
  async learned(since: number, dbv: string) {
    return [...this.learnedRows.values()].filter((l) => l.at > since && l.dbv === dbv)
      .map(({ brand_key, display_name, form, items }) => ({ brand_key, display_name, form, items }));
  }
  async recentEvents(days: number) {
    const since = new Date(Date.now() - days * 86_400_000).toISOString();
    return [...this.events.values()].filter((e) => e.scanned_at >= since);
  }
  alerts: AlertRow[] = [];
  async batchAlerts(sinceId: number, limit: number) { return this.alerts.filter((a) => a.id > sinceId).slice(0, limit); }
  async addBatchAlerts(rows: Omit<AlertRow, "id">[]) {
    let n = 0;
    for (const r of rows) {
      const dup = this.alerts.some((a) => a.kind === r.kind && a.batch_norm === r.batch_norm && a.product === r.product && a.month === r.month);
      if (!dup) { this.alerts.push({ ...r, id: this.alerts.length + 1 }); n++; }
    }
    return n;
  }
  async stats() {
    const ev = [...this.events.values()];
    const abx = ev.filter((e) => e.is_antibiotic).length;
    return {
      events: ev.length, antibiotic_events: abx, access_share: abx ? ev.filter((e) => e.aware_group === "Access").length / abx : null,
      banned_events: ev.filter((e) => e.banned).length,
      antibiotic_without_prescription: ev.filter((e) => e.is_antibiotic && e.prescribed === false).length, llm_calls: this.calls.length,
      llm_cost_usd: this.calls.reduce((s, c) => s + c.cost, 0), server_cache_hits: this.cache.reduce((s, c) => s + c.hits, 0),
      cache_entries: this.cache.length, learned_aliases: this.aliasRows.size,
    };
  }
}
