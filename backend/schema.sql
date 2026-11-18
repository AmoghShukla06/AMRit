-- AMRit backend store (SQLite by default). scan_events is also the table the dashboard reads; the same DDL works
-- on Supabase/Postgres (use JSONB for ingredients, BOOLEAN for flags, TIMESTAMPTZ for times).
CREATE TABLE IF NOT EXISTS scan_events(
  event_id TEXT PRIMARY KEY,          -- random per event, links to nothing
  ingredients TEXT NOT NULL,          -- JSON array of canonical ingredient names
  aware_group TEXT,                   -- Access | Watch | Reserve | NULL
  is_antibiotic INTEGER NOT NULL,
  banned INTEGER NOT NULL,
  pincode TEXT,                       -- 6 digits or NULL; dashboard hides areas with < 5 scans
  scanned_at TEXT NOT NULL,           -- UTC, hour granularity
  received_at TEXT NOT NULL,
  prescribed INTEGER                  -- 1/0 answer to "sold with a prescription?", NULL = not asked
);
CREATE INDEX IF NOT EXISTS scan_events_pin_time ON scan_events(pincode, scanned_at);
CREATE TABLE IF NOT EXISTS llm_cache(id INTEGER PRIMARY KEY, ocr_sig TEXT NOT NULL, reading TEXT NOT NULL, created_at INTEGER, hits INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS llm_calls(ts INTEGER, cost_usd REAL, model TEXT);
CREATE TABLE IF NOT EXISTS usage(day TEXT, who TEXT, calls INTEGER, PRIMARY KEY(day, who));
CREATE TABLE IF NOT EXISTS spend(day TEXT PRIMARY KEY, usd REAL);
CREATE TABLE IF NOT EXISTS alias_reports(phrase TEXT, base_key TEXT, db_version TEXT, who TEXT, day TEXT,
  PRIMARY KEY(phrase, base_key, db_version, who, day));
CREATE TABLE IF NOT EXISTS aliases(phrase TEXT, base_key TEXT, db_version TEXT, promoted_at INTEGER, PRIMARY KEY(phrase, db_version));
-- Brands learned from printed compositions (absent from the shipped DB); promoted after 2 independent agreeing reports.
CREATE TABLE IF NOT EXISTS learned_reports(brand_key TEXT, items TEXT, display_name TEXT, form TEXT, db_version TEXT, who TEXT, day TEXT,
  PRIMARY KEY(brand_key, items, db_version, who, day));
CREATE TABLE IF NOT EXISTS learned_products(brand_key TEXT, display_name TEXT, form TEXT, items TEXT, db_version TEXT, promoted_at INTEGER,
  PRIMARY KEY(brand_key, db_version));
