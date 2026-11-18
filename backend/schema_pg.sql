-- Postgres version of schema.sql (used when DATABASE_URL is set). Same tables and columns; only the auto-increment
-- id differs. Statements are split on ';', so no semicolons inside comments or strings.
CREATE TABLE IF NOT EXISTS scan_events(
  event_id TEXT PRIMARY KEY,
  ingredients TEXT NOT NULL,
  aware_group TEXT,
  is_antibiotic INTEGER NOT NULL,
  banned INTEGER NOT NULL,
  pincode TEXT,
  scanned_at TEXT NOT NULL,
  received_at TEXT NOT NULL,
  prescribed INTEGER
);
CREATE INDEX IF NOT EXISTS scan_events_pin_time ON scan_events(pincode, scanned_at);
CREATE INDEX IF NOT EXISTS scan_events_received ON scan_events(received_at);
CREATE TABLE IF NOT EXISTS llm_cache(id BIGSERIAL PRIMARY KEY, ocr_sig TEXT NOT NULL, reading TEXT NOT NULL, created_at BIGINT, hits INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS llm_calls(ts BIGINT, cost_usd DOUBLE PRECISION, model TEXT);
CREATE TABLE IF NOT EXISTS usage(day TEXT, who TEXT, calls INTEGER, PRIMARY KEY(day, who));
CREATE TABLE IF NOT EXISTS spend(day TEXT PRIMARY KEY, usd DOUBLE PRECISION);
CREATE TABLE IF NOT EXISTS alias_reports(phrase TEXT, base_key TEXT, db_version TEXT, who TEXT, day TEXT,
  PRIMARY KEY(phrase, base_key, db_version, who, day));
CREATE TABLE IF NOT EXISTS aliases(phrase TEXT, base_key TEXT, db_version TEXT, promoted_at BIGINT, PRIMARY KEY(phrase, db_version));
CREATE TABLE IF NOT EXISTS learned_reports(brand_key TEXT, items TEXT, display_name TEXT, form TEXT, db_version TEXT, who TEXT, day TEXT,
  PRIMARY KEY(brand_key, items, db_version, who, day));
CREATE TABLE IF NOT EXISTS learned_products(brand_key TEXT, display_name TEXT, form TEXT, items TEXT, db_version TEXT, promoted_at BIGINT,
  PRIMARY KEY(brand_key, db_version))
