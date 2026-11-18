-- Read-only reference DB shipped in the APK (assets/amrit_ref.db). Built by data-build/build_db.py.
CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
-- aware_oral / aware_parenteral: WHO AWaRe 2025 group by route (they differ for e.g. fosfomycin). NULL = not an antibiotic.
CREATE TABLE ingredient(id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL,
  aware_oral TEXT, aware_parenteral TEXT, aware_class TEXT, atc TEXT, is_antibiotic INTEGER NOT NULL);
CREATE TABLE ingredient_alias(key TEXT PRIMARY KEY, ingredient_id INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE manufacturer(id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT);
-- ingr_key = sorted ingredient ids joined by '+', used for set matching (banned FDCs, WHO combos, Jan Aushadhi)
CREATE TABLE composition(id INTEGER PRIMARY KEY, n INTEGER NOT NULL, maybe_incomplete INTEGER NOT NULL, ingr_key TEXT NOT NULL);
CREATE TABLE composition_item(composition_id INTEGER NOT NULL, ingredient_id INTEGER NOT NULL, strength REAL, unit TEXT,
  PRIMARY KEY(composition_id, ingredient_id)) WITHOUT ROWID;
-- base_key: normalised brand name without strength/form ('augmentin'); the matcher's lookup key.
CREATE TABLE product(id INTEGER PRIMARY KEY, name TEXT NOT NULL, base_key TEXT NOT NULL,
  manufacturer_id INTEGER, composition_id INTEGER NOT NULL, form TEXT, pack_qty REAL, mrp REAL, discontinued INTEGER);
CREATE INDEX product_base ON product(base_key);
CREATE TABLE aware_combo(ingr_key TEXT PRIMARY KEY, title TEXT, aware TEXT);
CREATE TABLE who_not_recommended(ingr_key TEXT PRIMARY KEY, title TEXT);
-- form_req: the ban covers only this dosage form (e.g. 'tablet_dispersible', 'injection'); NULL = any form.
-- strengths: '|'-separated variants of 'ingredient_id:valueunit,...'; NULL = any strength.
CREATE TABLE banned_fdc(id INTEGER PRIMARY KEY, ingr_key TEXT NOT NULL, title TEXT, notification TEXT, date TEXT, source TEXT,
  form_req TEXT, strengths TEXT);
CREATE INDEX banned_key ON banned_fdc(ingr_key);
CREATE TABLE interaction(a INTEGER NOT NULL, b INTEGER NOT NULL, level INTEGER NOT NULL, PRIMARY KEY(a,b)) WITHOUT ROWID;
CREATE TABLE jan_aushadhi(drug_code INTEGER PRIMARY KEY, name TEXT, unit_size TEXT, unit_qty REAL, mrp REAL, form TEXT,
  ingr_key TEXT NOT NULL, strengths TEXT);
CREATE INDEX ja_key ON jan_aushadhi(ingr_key);
-- "What it's for": short use phrases from the 11.8k-product dataset, aggregated by ingredient set (never generated live).
CREATE TABLE use_phrase(id INTEGER PRIMARY KEY, en TEXT NOT NULL);
-- translations (data-build/i18n/uses_<lang>.tsv, machine-translated at build time, reviewed status in meta)
CREATE TABLE use_phrase_i18n(phrase_id INTEGER NOT NULL, lang TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY(phrase_id, lang)) WITHOUT ROWID;
CREATE TABLE composition_use(ingr_key TEXT PRIMARY KEY, phrase_ids TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE ingredient_use(ingredient_id INTEGER PRIMARY KEY, phrase_ids TEXT NOT NULL);
-- CDSCO monthly drug alerts (data-build/fetch_cdsco_alerts.py): batches declared Not of Standard Quality (NSQ) or
-- spurious. batch_norm = upper-case letters+digits with O->0 and I/L->1 (same folding in core/BatchAlerts.kt).
-- A scan is flagged only when the batch matches AND the product matches (brand or all ingredients), never batch alone.
CREATE TABLE batch_alert(id INTEGER PRIMARY KEY, batch_norm TEXT NOT NULL, batch TEXT NOT NULL, product TEXT NOT NULL,
  brand TEXT, manufacturer TEXT, result TEXT, kind TEXT NOT NULL, reported_by TEXT, month TEXT NOT NULL);
CREATE INDEX batch_alert_norm ON batch_alert(batch_norm);
