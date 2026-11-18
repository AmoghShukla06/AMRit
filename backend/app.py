"""AMRit backend: vision-LLM proxy (holds the OpenRouter key, caches, rate-limits) + anonymous event store.

    uvicorn app:app --host 0.0.0.0 --port 8000        (from backend/, with .env configured)

Privacy: no accounts; images are processed in memory and never written anywhere; IPs are only used as a salted hash
that rotates daily (rate limiting / alias voting) and are never stored raw. Events contain no personal data.
"""
import base64, datetime as dt, hashlib, json, os, re, secrets, sqlite3, threading, time, uuid
from contextlib import contextmanager
from typing import Literal, Optional

import httpx
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field, field_validator
from rapidfuzz import fuzz, process

# ------------------------------------------------------------------------------------------------ config


def _load_env(path=os.path.join(os.path.dirname(__file__), ".env")):
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())


_load_env()
OPENROUTER_KEY = os.environ.get("OPENROUTER_API_KEY", "")
# Benchmarked on the glare/blur test photos (eval/bench_models.py): gemma-4-26b 8/8 brands, 1.2 s, $0.00006/call;
# gemini-2.5-flash-lite 8/8 as automatic backup when the first is rate-limited upstream.
# Two independent readers (different model families) read every hard strip; the phone cross-checks them.
# eval/bench_composition.py on a real 10-ingredient strip: gemma-4-31b 10/10 twice, gemini-2.5-flash-lite 10/10 twice.
MODELS = [m.strip() for m in os.environ.get("OPENROUTER_MODELS", "google/gemma-4-31b-it,google/gemini-2.5-flash-lite").split(",") if m.strip()]
SPARE_MODEL = os.environ.get("OPENROUTER_SPARE_MODEL", "google/gemma-4-26b-a4b-it")  # if one of the two fails
DB_PATH = os.environ.get("AMRIT_DB", os.path.join(os.path.dirname(__file__), "amrit_backend.db"))
PER_IP_PER_DAY = int(os.environ.get("RESOLVE_PER_IP_PER_DAY", "80"))
DAILY_BUDGET_USD = float(os.environ.get("DAILY_BUDGET_USD", "2.0"))
CACHE_MIN_SCORE = float(os.environ.get("CACHE_MIN_SCORE", "92"))
ALIAS_MIN_REPORTERS = int(os.environ.get("ALIAS_MIN_REPORTERS", "2"))
SUPABASE_URL = os.environ.get("SUPABASE_URL", "").rstrip("/")
SUPABASE_KEY = os.environ.get("SUPABASE_SERVICE_KEY", "")
MAX_IMAGE_BYTES = 600_000

app = FastAPI(title="AMRit backend", docs_url="/docs")

# ------------------------------------------------------------------------------------------------ storage

DATABASE_URL = os.environ.get("DATABASE_URL", "")  # Postgres on free hosts whose disk is wiped on restart (Render)
_lock = threading.Lock()


class _Pg:
    """Just enough of sqlite3.Connection on top of psycopg: '?' placeholders, execute().fetchone()/fetchall(),
    commit/rollback, and a reconnect when a free-tier database drops idle connections."""

    def __init__(self, url: str):
        self.url, self._c = url, None

    def _conn(self):
        import psycopg  # only needed when DATABASE_URL is set
        if self._c is None or self._c.closed:
            self._c = psycopg.connect(self.url, connect_timeout=10)
        return self._c

    def execute(self, sql: str, params=()):
        try:
            return self._conn().execute(sql.replace("?", "%s"), params)
        except Exception as e:  # noqa: BLE001
            import psycopg
            if isinstance(e, psycopg.OperationalError) and self._c is not None and self._c.closed:
                self._c = None  # connection was dropped: one retry on a fresh one
                return self._conn().execute(sql.replace("?", "%s"), params)
            raise

    def commit(self):
        if self._c is not None and not self._c.closed:
            self._c.commit()

    def rollback(self):
        if self._c is not None and not self._c.closed:
            self._c.rollback()


if DATABASE_URL:
    _conn = _Pg(DATABASE_URL)
    with open(os.path.join(os.path.dirname(__file__), "schema_pg.sql"), encoding="utf-8") as f:
        for stmt in f.read().split(";"):
            if stmt.strip():
                _conn.execute(stmt)
    _conn.commit()
else:
    _conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    _conn.executescript(open(os.path.join(os.path.dirname(__file__), "schema.sql"), encoding="utf-8").read())
    if "prescribed" not in [r[1] for r in _conn.execute("PRAGMA table_info(scan_events)")]:
        _conn.execute("ALTER TABLE scan_events ADD COLUMN prescribed INTEGER")


@contextmanager
def db():
    with _lock:
        try:
            yield _conn
            _conn.commit()
        except Exception:
            _conn.rollback()
            raise


def today() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")


_salt = {"day": None, "salt": b""}


CLIENT_IP_HEADER = os.environ.get("CLIENT_IP_HEADER", "").lower()  # e.g. fly-client-ip (set by Fly's proxy)
TRUSTED_PROXY_HOPS = int(os.environ.get("TRUSTED_PROXY_HOPS", "0"))  # e.g. 1 behind Render / Cloud Run
EXPORT_TOKEN = os.environ.get("EXPORT_TOKEN", "")


def client_ip(req: Request) -> str:
    """The caller's IP for rate limiting. Only headers written by our own proxy are trusted: the left end of
    X-Forwarded-For is whatever the client sent, so with N trusted proxies we take the N-th entry from the right."""
    if CLIENT_IP_HEADER and req.headers.get(CLIENT_IP_HEADER):
        return req.headers[CLIENT_IP_HEADER].strip()
    if TRUSTED_PROXY_HOPS > 0:
        hops = [h.strip() for h in req.headers.get("x-forwarded-for", "").split(",") if h.strip()]
        if len(hops) >= TRUSTED_PROXY_HOPS:
            return hops[-TRUSTED_PROXY_HOPS]
    return (req.client.host if req.client else "") or "?"


def reporter_hash(req: Request) -> str:
    """Salted hash of the client IP; the salt lives only in memory and changes daily, so hashes can't be linked
    across days or reversed from the DB."""
    d = today()
    if _salt["day"] != d:
        _salt.update(day=d, salt=secrets.token_bytes(16))
    ip = client_ip(req)
    return hashlib.sha256(_salt["salt"] + ip.encode()).hexdigest()[:16]


# ------------------------------------------------------------------------------------------------ models


class LlmIngredient(BaseModel):
    name: str = Field(..., max_length=80)
    strength: Optional[str] = Field(None, max_length=40)


class LlmReading(BaseModel):
    brand: Optional[str] = Field(None, max_length=80)
    strength: Optional[str] = Field(None, max_length=40)
    manufacturer: Optional[str] = Field(None, max_length=120)
    dosage_form: Optional[str] = Field(None, max_length=40)
    ingredients: list[LlmIngredient] = Field(default_factory=list, max_length=15)
    composition_text: Optional[str] = Field(None, max_length=600)
    expiry: Optional[str] = Field(None, max_length=20)
    legible: bool = True

    @field_validator("brand", "strength", "manufacturer", "dosage_form", "composition_text", "expiry")
    @classmethod
    def clean(cls, v):
        if v is None:
            return None
        v = re.sub(r"[\x00-\x1f]+", " ", v).strip()
        return v or None


class ResolveRequest(BaseModel):
    image_jpeg_b64: str = Field(..., max_length=MAX_IMAGE_BYTES * 4 // 3 + 16)
    ocr_tokens: list[str] = Field(default_factory=list, max_length=80)
    db_version: str = Field("dev", max_length=40)


class ResolveResponse(BaseModel):
    reading: Optional[LlmReading] = None
    # every independent reading (normally 2); the phone accepts what they agree on
    readings: list[LlmReading] = Field(default_factory=list)
    source: Literal["server_cache", "llm", "error"]
    cost_usd: float = 0.0
    error: Optional[str] = None


class ScanEvent(BaseModel):
    event_id: str
    ingredients: list[str] = Field(..., max_length=25)
    aware_group: Optional[Literal["Access", "Watch", "Reserve"]] = None
    is_antibiotic: bool = False
    banned: bool = False
    pincode: Optional[str] = None
    scanned_at: str
    # answer to "was this antibiotic sold with a prescription?"; None = not asked / skipped
    prescribed: Optional[bool] = None

    @field_validator("event_id")
    @classmethod
    def v_id(cls, v):
        return str(uuid.UUID(v))

    @field_validator("ingredients")
    @classmethod
    def v_ing(cls, v):
        if any(len(x) > 80 for x in v):
            raise ValueError("ingredient name too long")
        return sorted(set(x.strip() for x in v if x.strip()))

    @field_validator("pincode")
    @classmethod
    def v_pin(cls, v):
        if v is None or v == "":
            return None
        if not re.fullmatch(r"[1-9][0-9]{5}", v):
            raise ValueError("bad pincode")
        return v

    @field_validator("scanned_at")
    @classmethod
    def v_ts(cls, v):
        t = dt.datetime.fromisoformat(v.replace("Z", "+00:00")).astimezone(dt.timezone.utc)
        return t.replace(minute=0, second=0, microsecond=0).strftime("%Y-%m-%dT%H:00:00Z")  # enforce hour granularity


class AliasReport(BaseModel):
    phrase: str = Field(..., min_length=3, max_length=60)
    base_key: str = Field(..., min_length=2, max_length=60)
    db_version: str = Field("dev", max_length=40)


# ------------------------------------------------------------------------------------------------ LLM

PROMPT = """You transcribe the text printed on a photo of an Indian medicine strip or box. You do not give medical
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
Only transcribe characters you can actually see. If a word is cut off or covered by glare, use null for that field."""

SCHEMA = {
    "type": "object",
    "properties": {
        "brand": {"type": ["string", "null"]},
        "strength": {"type": ["string", "null"]},
        "manufacturer": {"type": ["string", "null"]},
        "dosage_form": {"type": ["string", "null"]},
        "ingredients": {"type": "array", "items": {"type": "object", "properties": {
            "name": {"type": "string"}, "strength": {"type": ["string", "null"]}},
            "required": ["name", "strength"], "additionalProperties": False}},
        "composition_text": {"type": ["string", "null"]},
        "expiry": {"type": ["string", "null"]},
        "legible": {"type": "boolean"},
    },
    "required": ["brand", "strength", "manufacturer", "dosage_form", "ingredients", "composition_text", "expiry", "legible"],
    "additionalProperties": False,
}


def consistent(r: LlmReading) -> bool:
    """A transcription that lists the same ingredient twice (e.g. 'Pyridoxine 3 mg' and 'Pyridoxine 150 mg') has
    misread something; the phone would refuse it, so ask the next model instead."""
    # "Pyridoxine I.P." = "Pyridoxine IP" (punctuation, then the pharmacopoeia word, dropped); digits are kept so
    # "Vitamin B6" and "Vitamin B12" stay different
    names = [re.sub(r"\b(ip|bp|usp)\b", "", re.sub(r"[^a-z0-9 ]", "", i.name.lower())).replace(" ", "") for i in r.ingredients]
    return len(names) == len(set(names))


def call_llm(image_b64: str) -> tuple[list[LlmReading], float]:
    """Ask the reader models in parallel; a model that fails is replaced by the spare. Returns readings + total cost."""
    from concurrent.futures import ThreadPoolExecutor
    out, total = [], 0.0

    def one(model):
        try:
            return call_model(model, image_b64)
        except Exception as e:  # noqa: BLE001  (rate limit upstream, truncated JSON, ...)
            print(f"resolve: {model} failed: {type(e).__name__}", flush=True)
            return None

    with ThreadPoolExecutor(max_workers=len(MODELS)) as ex:
        results = list(ex.map(one, MODELS))
    if any(r is None for r in results) and SPARE_MODEL:
        results = [r for r in results if r is not None] + [one(SPARE_MODEL)]
    for r in results:
        if r is not None:
            out.append(r[0]); total += r[1]
    if not out:
        raise RuntimeError("all models failed")
    return out, total


def call_model(model: str, image_b64: str) -> tuple[LlmReading, float]:
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": PROMPT},
            {"role": "user", "content": [
                {"type": "text", "text": "Transcribe this medicine strip."},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
            ]},
        ],
        "response_format": {"type": "json_schema", "json_schema": {"name": "strip", "strict": True, "schema": SCHEMA}},
        "temperature": 0,
        "max_tokens": 1200,  # 10-ingredient multivitamin labels need ~600; truncated JSON = failed fallback
        "usage": {"include": True},
    }
    r = httpx.post("https://openrouter.ai/api/v1/chat/completions", json=body, timeout=25,
                   headers={"Authorization": f"Bearer {OPENROUTER_KEY}", "X-Title": "AMRit"})
    r.raise_for_status()
    d = r.json()
    content = d["choices"][0]["message"]["content"]
    if isinstance(content, list):
        content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
    m = re.search(r"\{.*\}", content, re.S)  # tolerate code fences
    reading = LlmReading.model_validate(json.loads(m.group(0) if m else content))
    cost = float((d.get("usage") or {}).get("cost") or 0.0)
    return reading, cost


# ------------------------------------------------------------------------------------------------ cache


def token_sig(tokens: list[str]) -> str:
    toks = sorted({t.lower() for t in tokens if len(t) >= 3 and not t.isdigit()})
    return " ".join(toks[:60])


def cache_lookup(sig: str):
    if len(sig.split()) < 4:
        return None
    with db() as c:
        rows = c.execute("SELECT id, ocr_sig, reading FROM llm_cache ORDER BY id DESC LIMIT 5000").fetchall()
    if not rows:
        return None
    hit = process.extractOne(sig, {r[0]: r[1] for r in rows}, scorer=fuzz.token_set_ratio)
    if hit and hit[1] >= CACHE_MIN_SCORE:
        with db() as c:
            c.execute("UPDATE llm_cache SET hits = hits + 1 WHERE id=?", (hit[2],))
        return next(r[2] for r in rows if r[0] == hit[2])
    return None


# ------------------------------------------------------------------------------------------------ routes


@app.get("/health")
def health():
    return {"ok": True, "llm_configured": bool(OPENROUTER_KEY), "models": MODELS, "store": "postgres" if DATABASE_URL else "sqlite"}


@app.get("/v1/ip")
def ip(request: Request):
    """Setup check for rate limiting behind a host's proxy: the IP this server would count the caller as, and which
    proxy headers arrived. Only ever shows callers their own address."""
    names = ["true-client-ip", "cf-connecting-ip", "fly-client-ip", "x-real-ip", "x-forwarded-for"]
    return {"counted_as": client_ip(request), "headers": {n: request.headers.get(n) for n in names if request.headers.get(n)}}


@app.post("/v1/resolve", response_model=ResolveResponse)
def resolve(req: ResolveRequest, request: Request):
    try:
        img = base64.b64decode(req.image_jpeg_b64, validate=True)
    except Exception:
        raise HTTPException(400, "bad image")
    if len(img) > MAX_IMAGE_BYTES or not img.startswith(b"\xff\xd8"):
        raise HTTPException(400, "image must be a JPEG under 600 KB")
    sig = token_sig(req.ocr_tokens)
    cached = cache_lookup(sig)
    if cached:
        data = json.loads(cached)
        readings = [LlmReading.model_validate(x) for x in (data if isinstance(data, list) else [data])]
        return ResolveResponse(reading=readings[0], readings=readings, source="server_cache")
    if not OPENROUTER_KEY:
        return ResolveResponse(source="error", error="fallback not configured (no OPENROUTER_API_KEY on the server)")
    who, day = reporter_hash(request), today()
    with db() as c:
        calls = c.execute("SELECT calls FROM usage WHERE day=? AND who=?", (day, who)).fetchone()
        spent = c.execute("SELECT usd FROM spend WHERE day=?", (day,)).fetchone()
        if calls and calls[0] >= PER_IP_PER_DAY:
            return ResolveResponse(source="error", error="daily limit reached")
        if spent and spent[0] >= DAILY_BUDGET_USD:
            return ResolveResponse(source="error", error="daily budget reached")
        c.execute("INSERT INTO usage(day, who, calls) VALUES(?,?,1) ON CONFLICT(day, who) DO UPDATE SET calls=usage.calls+1", (day, who))
    try:
        readings, cost = call_llm(req.image_jpeg_b64)
    except Exception as e:  # noqa: BLE001
        return ResolveResponse(source="error", error=f"vision model failed: {type(e).__name__}")
    finally:
        del img  # the image is never persisted
    reading = next((r for r in readings if r.legible and consistent(r)), readings[0])
    # diagnostics: what the model transcribed (product text only; no image, no user data)
    for r in readings:
        print(f"resolve: legible={r.legible} brand={r.brand!r} form={r.dosage_form!r} "
              f"ingredients={[(i.name, i.strength) for i in r.ingredients]}", flush=True)
    print(f"resolve: {len(readings)} readings, cost={cost}", flush=True)
    with db() as c:
        c.execute("INSERT INTO spend(day, usd) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET usd=spend.usd+excluded.usd", (day, cost))
        c.execute("INSERT INTO llm_calls(ts, cost_usd, model) VALUES(?,?,?)", (int(time.time()), cost, MODELS[0]))
        if reading.legible and consistent(reading) and (reading.brand or reading.composition_text) and len(sig.split()) >= 4:
            c.execute("INSERT INTO llm_cache(ocr_sig, reading, created_at, hits) VALUES(?,?,?,0)",
                      (sig, json.dumps([r.model_dump() for r in readings]), int(time.time())))
    return ResolveResponse(reading=reading, readings=readings, source="llm", cost_usd=cost)


@app.post("/v1/events")
def events(evts: list[ScanEvent]):
    if len(evts) > 500:
        raise HTTPException(413, "too many events")
    now = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with db() as c:
        for e in evts:
            # a re-send of the same event may carry the prescription answer; a later re-send without it never erases it
            c.execute("INSERT INTO scan_events(event_id, ingredients, aware_group, is_antibiotic, banned, pincode,"
                      " scanned_at, received_at, prescribed) VALUES(?,?,?,?,?,?,?,?,?)"
                      " ON CONFLICT(event_id) DO UPDATE SET prescribed=COALESCE(excluded.prescribed, scan_events.prescribed)",
                      (e.event_id, json.dumps(e.ingredients), e.aware_group, int(e.is_antibiotic), int(e.banned), e.pincode,
                       e.scanned_at, now, None if e.prescribed is None else int(e.prescribed)))
    if SUPABASE_URL and SUPABASE_KEY:  # optional mirror into the dashboard's Supabase table (Realtime)
        try:
            httpx.post(f"{SUPABASE_URL}/rest/v1/scan_events", json=[e.model_dump() for e in evts], timeout=10,
                       headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}",
                                "Prefer": "resolution=ignore-duplicates"})
        except Exception:  # noqa: BLE001
            pass  # local store is the source of truth; the mirror can be backfilled from /v1/events/export
    return {"accepted": len(evts)}


@app.get("/v1/events/export")
def export(request: Request, since: str = "1970-01-01T00:00:00Z", limit: int = 10000):
    """For the dashboard: anonymous events received after `since` (ISO UTC). Needs `Authorization: Bearer
    <EXPORT_TOKEN>` when EXPORT_TOKEN is set (always set it on a public host)."""
    if EXPORT_TOKEN and not secrets.compare_digest(request.headers.get("authorization", ""), f"Bearer {EXPORT_TOKEN}"):
        raise HTTPException(401, "export token required")
    with db() as c:
        rows = c.execute("SELECT event_id, ingredients, aware_group, is_antibiotic, banned, pincode, scanned_at, received_at, prescribed "
                         "FROM scan_events WHERE received_at > ? ORDER BY received_at LIMIT ?", (since, min(limit, 50000))).fetchall()
    keys = ["event_id", "ingredients", "aware_group", "is_antibiotic", "banned", "pincode", "scanned_at", "received_at", "prescribed"]
    out = [dict(zip(keys, r)) for r in rows]
    for o in out:
        o["ingredients"] = json.loads(o["ingredients"]); o["is_antibiotic"] = bool(o["is_antibiotic"]); o["banned"] = bool(o["banned"])
        o["prescribed"] = None if o["prescribed"] is None else bool(o["prescribed"])
    return out


@app.post("/v1/aliases")
def report_alias(a: AliasReport, request: Request):
    phrase = re.sub(r"[^a-z0-9 ]", "", a.phrase.lower()).strip()
    with db() as c:
        c.execute("INSERT INTO alias_reports(phrase, base_key, db_version, who, day) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                  (phrase, a.base_key, a.db_version, reporter_hash(request), today()))
        n = c.execute("SELECT COUNT(DISTINCT who || day) FROM alias_reports WHERE phrase=? AND base_key=? AND db_version=?",
                      (phrase, a.base_key, a.db_version)).fetchone()[0]
        if n >= ALIAS_MIN_REPORTERS:
            c.execute("INSERT INTO aliases(phrase, base_key, db_version, promoted_at) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                      (phrase, a.base_key, a.db_version, int(time.time() * 1000)))
    return {"reports": n, "promoted": n >= ALIAS_MIN_REPORTERS}


@app.get("/v1/aliases")
def aliases(since: int = 0, db_version: str = "dev"):
    with db() as c:
        rows = c.execute("SELECT phrase, base_key FROM aliases WHERE promoted_at > ? AND db_version=?", (since, db_version)).fetchall()
    return {"aliases": [{"phrase": p, "base_key": b} for p, b in rows], "now": int(time.time() * 1000)}


class LearnedReport(BaseModel):
    """A brand the phone resolved from the strip's printed composition (brand absent from the shipped DB).
    Ingredient ids are reference-DB ids; the phone only reports after every ingredient mapped exactly."""
    brand_key: str = Field(..., min_length=3, max_length=60)
    display_name: str = Field(..., min_length=2, max_length=80)
    form: str = Field("tablet", max_length=30)
    items: str = Field(..., min_length=3, max_length=300)  # "id:valueunit;id:valueunit"
    db_version: str = Field("dev", max_length=40)

    @field_validator("items")
    @classmethod
    def v_items(cls, v):
        if not re.fullmatch(r"\d+:([0-9.]+[A-Za-z/0-9]*)?(;\d+:([0-9.]+[A-Za-z/0-9]*)?)*", v):
            raise ValueError("bad items")
        return v


@app.post("/v1/learned")
def report_learned(r: LearnedReport, request: Request):
    """Two agreeing reports from different phones/days promote a learned brand; phones then resolve it offline."""
    key = re.sub(r"[^a-z0-9 ]", "", r.brand_key.lower()).strip()
    with db() as c:
        c.execute("INSERT INTO learned_reports(brand_key, items, display_name, form, db_version, who, day) "
                  "VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING", (key, r.items, r.display_name, r.form, r.db_version, reporter_hash(request), today()))
        n = c.execute("SELECT COUNT(DISTINCT who || day) FROM learned_reports WHERE brand_key=? AND items=? AND db_version=?",
                      (key, r.items, r.db_version)).fetchone()[0]
        if n >= ALIAS_MIN_REPORTERS:
            c.execute("INSERT INTO learned_products(brand_key, display_name, form, items, db_version, promoted_at) VALUES(?,?,?,?,?,?) "
                      "ON CONFLICT(brand_key, db_version) DO UPDATE SET display_name=excluded.display_name, form=excluded.form, "
                      "items=excluded.items, promoted_at=excluded.promoted_at", (key, r.display_name, r.form, r.items, r.db_version, int(time.time() * 1000)))
    return {"reports": n, "promoted": n >= ALIAS_MIN_REPORTERS}


@app.get("/v1/learned")
def learned(since: int = 0, db_version: str = "dev"):
    with db() as c:
        rows = c.execute("SELECT brand_key, display_name, form, items FROM learned_products WHERE promoted_at > ? AND db_version=?",
                         (since, db_version)).fetchall()
    return {"products": [{"brand_key": a, "display_name": b, "form": f, "items": i} for a, b, f, i in rows],
            "now": int(time.time() * 1000)}


@app.get("/v1/stats")
def stats():
    with db() as c:
        ev = c.execute("SELECT COUNT(*), SUM(is_antibiotic), SUM(CASE WHEN aware_group='Access' THEN 1 ELSE 0 END), SUM(banned), "
                       "SUM(CASE WHEN is_antibiotic=1 AND prescribed=0 THEN 1 ELSE 0 END) FROM scan_events").fetchone()
        calls = c.execute("SELECT COUNT(*), COALESCE(SUM(cost_usd),0) FROM llm_calls").fetchone()
        hits = c.execute("SELECT COALESCE(SUM(hits),0), COUNT(*) FROM llm_cache").fetchone()
        al = c.execute("SELECT COUNT(*) FROM aliases").fetchone()[0]
    abx = ev[1] or 0
    return {"events": ev[0], "antibiotic_events": abx, "access_share": (ev[2] or 0) / abx if abx else None,
            "banned_events": ev[3] or 0, "antibiotic_without_prescription": ev[4] or 0, "llm_calls": calls[0], "llm_cost_usd": round(calls[1], 6),
            "server_cache_hits": hits[0], "cache_entries": hits[1], "learned_aliases": al}


# ------------------------------------------------------------------------------------------------ dev-only: eval transfer
# Android 17 hides files that adb pushes into an app's storage, so the on-device eval pulls photos from here and
# posts its results back (over `adb reverse`). Off unless AMRIT_EVAL_DIR is set; never enable in a deployment.
EVAL_DIR = os.environ.get("AMRIT_EVAL_DIR")
EVAL_OUT = os.environ.get("AMRIT_EVAL_OUT")
_SAFE = re.compile(r"^[A-Za-z0-9_.-]+$")

if EVAL_DIR and EVAL_OUT:
    from fastapi.responses import FileResponse, PlainTextResponse

    @app.get("/eval/labels.csv")
    def eval_labels():
        return PlainTextResponse(open(os.path.join(EVAL_DIR, "labels.csv"), encoding="utf-8").read())

    @app.get("/eval/photo/{name}")
    def eval_photo(name: str):
        if not _SAFE.match(name):
            raise HTTPException(400, "bad name")
        return FileResponse(os.path.join(EVAL_DIR, name))

    @app.post("/eval/upload/{kind}/{name}")
    async def eval_upload(kind: str, name: str, request: Request):
        if kind not in ("root", "ocr", "llm", "capture") or not _SAFE.match(name):
            raise HTTPException(400, "bad path")
        d = EVAL_OUT if kind == "root" else os.path.join(EVAL_OUT, kind)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, name), "wb") as f:
            f.write(await request.body())
        return {"ok": True}
