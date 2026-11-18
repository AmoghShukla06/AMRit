#!/usr/bin/env python3
"""Builds the read-only reference database shipped inside the AMRit app.

    python -I data-build/build_db.py            (from the repo root)

Inputs (data/raw, see data-build/README.md for where each came from):
  medicines/indian_medicine_data.csv                 ~254k Indian brands, composition truncated to 2 ingredients
  medicines_full_composition/indian_medicines_11k.parquet   11.8k brands with full composition (overlay)
  ddinter/ddinter_downloads_code_*.csv              drug-drug interaction pairs + severity
  who_aware/WHO-AWaRe-2025-B09489-eng.xlsx          WHO AWaRe 2025 (+ 'not recommended' FDC sheet)
  cdsco/fdc_20{23,24,26}_*.pdf                      CDSCO prohibited FDC notifications
  jan_aushadhi/*.pdf                                 PMBJP product list (official, PIB) + MRP list
Outputs:
  app/src/main/assets/amrit_ref.db, data-build/out/report.md, data-build/out/key_vectors.tsv, data-build/out/manifest.json

Every number in the output comes from these files. Anything that can't be mapped confidently is written to the
report instead of being guessed.
"""
import argparse, collections, glob, hashlib, json, os, re, sqlite3, sys, time

import openpyxl
import pandas as pd
import pdfplumber
from rapidfuzz import fuzz, process

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from normalize import brand_keys, ingredient_key, parse_strength  # noqa: E402

AWARE_RANK = {"Access": 1, "Watch": 2, "Reserve": 3}
LEVEL = {"Minor": 1, "Moderate": 2, "Major": 3}  # DDInter 'Unknown' is dropped: no severity = nothing to show

# ---------------------------------------------------------------------------------------------- vocabulary


class Vocab:
    """Canonical ingredients. Every source name goes through ingredient_key() and the synonym table."""

    def __init__(self, synonyms: dict[str, str]):
        self.syn = synonyms  # key(alias) -> key(canonical)
        self.by_key: dict[str, dict] = {}
        self.alias: dict[str, str] = {}  # any alias key -> canonical key
        self.report: list[str] = []

    def resolve_key(self, name: str) -> str:
        # "Paracetamol/Acetaminophen", "Lidocaine/Lignocaine": a name followed by its synonym -> the first name
        if "/" in name and all(re.search(r"[A-Za-z]{3,}", x) and not re.search(r"\d", x) for x in name.split("/")):
            name = name.split("/")[0]
        k = ingredient_key(name)
        return self.syn.get(k, k)

    def add(self, name: str, source: str, display_priority: int) -> str:
        k = self.resolve_key(name)
        if not k:
            return ""
        e = self.by_key.setdefault(k, {"key": k, "names": collections.Counter(), "best": (99, ""), "sources": set()})
        e["names"][name.strip()] += 1
        e["sources"].add(source)
        if display_priority < e["best"][0]:
            e["best"] = (display_priority, name.strip())
        self.alias[ingredient_key(name)] = k
        self.alias[k] = k
        return k

    def lookup(self, name: str) -> str | None:
        k = self.resolve_key(name)
        return k if k in self.by_key else self.alias.get(ingredient_key(name))

    def _nospace(self):
        if getattr(self, "_ns_n", -1) != len(self.by_key):
            self._ns = {}
            for k in self.by_key:
                self._ns.setdefault(k.replace(" ", ""), k)
            for a, k in self.alias.items():
                self._ns.setdefault(a.replace(" ", ""), k)
            self._ns_n = len(self.by_key)
        return self._ns

    def find(self, text: str, fuzzy_min=92) -> tuple[str | None, str]:
        """Map messy free text (CDSCO / Jan Aushadhi) to a known ingredient. Returns (key, how)."""
        k = self.lookup(text)
        if k:
            return k, "exact"
        rk = self.resolve_key(text)
        ns = self._nospace()
        if rk and rk.replace(" ", "") in ns:  # PDF-split words: 'Cefix ime'
            return ns[rk.replace(" ", "")], "joined"
        words = text.split()
        for n in range(len(words) - 1, 0, -1):  # 'Metronidazole Infusion' -> 'Metronidazole'
            sub = " ".join(words[:n])
            sk = self.resolve_key(sub)
            if len(sk.replace(" ", "")) < 5:
                break
            hit = self.lookup(sub) or ns.get(sk.replace(" ", ""))
            if hit:
                return hit, f"prefix '{sub}'"
        fk, score = self.fuzzy(text, fuzzy_min)
        if fk:
            return fk, f"fuzzy {score:.0f}"
        return None, f"none (best fuzzy {score:.0f})"

    def fuzzy(self, name: str, min_score=90) -> tuple[str | None, float]:
        k = self.resolve_key(name)
        if k in self.by_key:
            return k, 100.0
        if not k:
            return None, 0
        cands = [c for c in self.by_key if c[:1] == k[:1]]
        hit = process.extractOne(k, cands, scorer=fuzz.ratio)
        if hit and hit[1] >= min_score:
            return hit[0], hit[1]
        return None, hit[1] if hit else 0


def display(e) -> str:
    """WHO spelling for antibiotics, otherwise the spelling Indian labels use most (Paracetamol, not Acetaminophen)."""
    if e["best"][0] == 0:
        n = e["best"][1]
    else:
        n = e["names"].most_common(1)[0][0]
    n = re.sub(r"\s*\(.*?\)\s*", " ", n).strip()
    return n[:1].upper() + n[1:] if n.islower() else n


def load_synonyms(path):
    syn = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#") or line.startswith("alias,"):
                continue
            alias, canon = line.split(",")[:2]
            syn[ingredient_key(alias)] = ingredient_key(canon)
    # resolve chains
    for a in list(syn):
        seen = {a}
        while syn[a] in syn and syn[a] not in seen and syn[syn[a]] != syn[a]:
            seen.add(syn[a])
            syn[a] = syn[syn[a]]
    return syn


# ---------------------------------------------------------------------------------------------- sources


def split_comp_item(item: str):
    """'Amoxycillin  (500mg) ' -> ('Amoxycillin', (500.0,'mg'))"""
    item = item.strip()
    if not item:
        return None
    m = re.match(r"^(.*?)\s*\(([^()]*)\)\s*$", item)
    if m:
        return m.group(1).strip(), parse_strength(m.group(2))
    return item, None


def load_medicines(raw):
    df = pd.read_csv(f"{raw}/medicines/indian_medicine_data.csv", dtype=str, keep_default_na=False)
    df.columns = ["id", "name", "price", "discontinued", "manufacturer", "type", "pack", "c1", "c2"]
    full = pd.read_parquet(f"{raw}/medicines_full_composition/indian_medicines_11k.parquet")
    full_by_name = {}
    for n, c in zip(full["name"], full["composition"]):
        full_by_name.setdefault(n.strip(), c)
    rows = []
    overlay = 0
    for r in df.itertuples(index=False):
        items = [split_comp_item(x) for x in (r.c1, r.c2)]
        items = [x for x in items if x]
        maybe_incomplete = len(items) == 2
        fc = full_by_name.get(r.name.strip())
        if fc:
            fitems = [split_comp_item(x) for x in fc.split("+")]
            fitems = [x for x in fitems if x]
            if fitems and len(fitems) >= len(items):
                if len(fitems) > len(items):
                    overlay += 1
                items, maybe_incomplete = fitems, False
        rows.append((r, items, maybe_incomplete))
    return rows, overlay


def load_who(raw):
    wb = openpyxl.load_workbook(f"{raw}/who_aware/WHO-AWaRe-2025-B09489-eng.xlsx", read_only=True, data_only=True)
    ws = wb["AWaRe classification 2025"]
    hdr = None
    out = []
    for r in ws.iter_rows(values_only=True):
        if r[0] == "Antibiotic":
            hdr = r
            continue
        if hdr and r[0] and r[3] in AWARE_RANK:
            out.append({"name": str(r[0]).strip(), "class": r[1], "atc": r[2], "aware": r[3]})
    nr = []
    ws = wb["Not recommended"]
    started = False
    for r in ws.iter_rows(values_only=True):
        if r[0] == "Antibiotic":
            started = True
            continue
        if started and r[0]:
            nr.append(str(r[0]).strip())
    return out, nr


def load_ddinter(raw):
    frames = [pd.read_csv(f) for f in sorted(glob.glob(f"{raw}/ddinter/ddinter_downloads_code_*.csv"))]
    d = pd.concat(frames).drop_duplicates()
    return d


def pdf_table_rows(path):
    rows = []
    with pdfplumber.open(path) as p:
        for pg in p.pages:
            for t in pg.extract_tables():
                rows.extend(t)
    return rows


NOISE = re.compile(
    r"\b(dispersible|tablets?|tabelts|capsules?|syrup of|syrup|suspension|injection|oral|solution|drops?|cream|gel|"
    r"ointment|lotion|soap noodles|soap|topical|spray|shampoo|powder|kit|dried|elemental|activated|infusion|intravenous|"
    r"inhalation|nebuli[sz]er|mouthwash|eye|ear|nasal|liquid|sachet|granules|prolonged[- ]release|delayed[- ]release|"
    r"sustained[- ]release|extended[- ]release|modified[- ]release|enteric[- ]coated|gastro[- ]resistant|film[- ]coated|"
    r"orally disintegrating|for reconstitution|with water|with wfi|wfi|vial|combipack of|combikit of|"
    r"as enteric coated tablet|for paediatric use|paediatric|in a flavou?red base|ip|bp|usp|nfi|i\.p\.|per|"
    r"million spores?|billion spores?|spores?|billion|million)\b",
    re.I,
)
STRENGTH_TXT = re.compile(r"\d+(?:[.,]\d+)*\s*(?:%\s*w/[wv]|%|mg|mcg|gm|g|iu|ml)?(?:\s*(?:/|per)\s*\d*\s*ml)?", re.I)


def clean_component(c: str) -> str:
    eq = re.search(r"\b(?:eq\.?|equivalent)\s*to\b", c, re.I)
    if eq:  # 'Amoxycillin Trihydrate eq. to Amoxycillin' -> 'Amoxycillin'
        c = c[eq.end():]
    c = re.sub(r"\(.*?\)", " ", c)
    c = re.sub(r"\bS\s*\(\s*\+\s*\)\s*", "S-", c)
    c = re.sub(r"\bflavou?red with.*$", " ", c, flags=re.I)
    c = STRENGTH_TXT.sub(" ", c)
    c = NOISE.sub(" ", c)
    c = re.sub(r"[^A-Za-z0-9\- ]", " ", c)
    return re.sub(r"\s+", " ", c).strip(" -")


def component_alternatives(raw: str) -> list[str]:
    """Main text plus single-name parentheticals: 'Hydroxyethyltheophylline (Etofylline)' -> both."""
    alts = [clean_component(raw)]
    for p in re.findall(r"\(([^()]*)\)", raw):
        if re.search(r"[A-Za-z]{4,}", p) and not re.search(r"\d|\+|\band\b", p):
            alts.append(clean_component(p))
    return [a for a in alts if re.search(r"[A-Za-z]{2,}", a)]


def form_requirement(title: str) -> str | None:
    t = title.lower()
    if "dispersible" in t:
        return "tablet_dispersible"
    for form, pat in [("injection", r"\binjection\b"), ("eye_ear_nose", r"\b(eye|ear|nasal)\b"),
                      ("oral_liquid", r"\b(syrup|suspension|oral liquid|drops)\b"),
                      ("topical", r"\b(cream|gel|ointment|lotion|soap|spray|topical)\b"),
                      ("tablet", r"\btablets?\b")]:
        if re.search(pat, t):
            return form
    return None


def load_cdsco(raw):
    """Returns list of dicts {title, components[[alt..]], strengths[[..]], form_req, notification, date, source}."""
    out = []
    for path in sorted(glob.glob(f"{raw}/cdsco/fdc_20*.pdf")):
        src = os.path.basename(path)
        rows = pdf_table_rows(path)
        if not rows:
            print(f"  ! {src}: no extractable table (scanned image?) - skipped", file=sys.stderr)
            continue
        for r in rows:
            if not r or not r[0] or not re.match(r"^\d+\.?$", str(r[0]).strip()):
                continue
            title = re.sub(r"\s+", " ", str(r[1] or "")).strip()
            notif = re.sub(r"\s+", " ", str(r[2] or "")).strip()
            m = re.search(r"S\.?O\.?\s*(\d+)\s*\(E\)", notif)
            d = re.search(r"(\d{2}\.\d{2}\.\d{4})", notif)
            # strength variants given as '(100mg + 40mg) , (125mg + 55mg)' are aligned with the component order
            variants = [g for g in re.findall(r"\(([^()]*)\)", title) if "+" in g and re.search(r"\d", g)]
            body = re.sub(r"\(([^()]*\+[^()]*)\)", " ", title)
            body = re.sub(r"\)\s*[,&]\s*\(", " ", body)
            body = re.sub(r"\bS\s*\(\s*\+\s*\)\s*", "S-", body)
            parts = [p for p in body.split("+") if re.search(r"[A-Za-z]{2,}", clean_component(p) or "")]
            comps = [component_alternatives(p) for p in parts]
            strengths = []
            if variants:
                for v in variants:
                    sts = [parse_strength(x) for x in v.split("+")]
                    if len(sts) == len(parts):
                        strengths.append(sts)
            else:
                sts = [parse_strength(p) for p in parts]
                if any(sts):
                    strengths.append(sts)
            out.append({"title": title, "components": comps, "strengths": strengths, "form_req": form_requirement(title),
                        "notification": f"S.O. {m.group(1)}(E)" if m else notif,
                        "date": d.group(1) if d else "", "source": src})
    return out


def parse_ja_name(name: str):
    """'Aceclofenac 100mg and Paracetamol 325mg Tablets' -> [('Aceclofenac',(100,'mg')),('Paracetamol',(325,'mg'))]"""
    n = re.sub(r"\s+", " ", name)
    inner = re.search(r"\(([^()]*\band\b[^()]*\d[^()]*)\)", n)
    if inner:  # 'Co-trimoxazole (Sulphamethoxazole 800mg and Trimethoprim 160mg) Tablets'
        n = inner.group(1)
    n = re.sub(r"\([^()]*\)", " ", n)
    parts = re.split(r"\s*(?:,|\band\b|\+|&)\s*", n, flags=re.I)
    out = []
    for p in parts:
        st = parse_strength(p)
        nm = clean_component(p)
        if nm and re.search(r"[A-Za-z]{3,}", nm):
            out.append((nm, st))
    return out


USE_SPLIT = re.compile(r"(?<=[a-z)])(?=[A-Z])")


def split_uses(u: str) -> list[str]:
    """'Pain reliefTreatment of Fever' -> ['Pain relief', 'Fever']"""
    out = []
    for p in USE_SPLIT.split(str(u or "").strip()):
        p = re.sub(r"\s+", " ", p).strip(" .,;")
        p = re.sub(r"^Treatment of ", "", p)
        # short condition names only; a few source rows hold whole template paragraphs ("$name is thought to ...")
        if 3 <= len(p) <= 70 and "$" not in p and ". " not in p and p not in out:
            out.append(p[0].upper() + p[1:])
    return out


def form_of(text: str) -> str:
    t = " " + text.lower() + " "
    if re.search(r"\b(dt|dispersible)\b", t) and "tablet" in t:
        return "tablet_dispersible"
    for form, pat in [
        ("injection", r"\b(injection|vial|ampoule|infusion|prefilled|cartridge)\b"),
        ("eye_ear_nose", r"\b(eye|ear|nasal|nose)\b"),
        ("inhaled", r"\b(inhaler|respules?|rotacaps?|transcaps|redicaps|nebuli[sz]er|inhalation)\b"),
        ("capsule", r"\bcapsules?\b"),
        ("tablet", r"\btablets?\b"),
        ("oral_liquid", r"\b(syrup|suspension|oral solution|drops|elixir|linctus|oral liquid)\b"),
        ("sachet", r"\b(sachets?|granules|powder for oral)\b"),
        ("topical", r"\b(cream|gel|ointment|lotion|soap|shampoo|spray|tube|dusting|paste|serum|face wash|mouthwash)\b"),
    ]:
        if re.search(pat, t):
            return form
    return "other"


SYSTEMIC = {"injection", "tablet", "capsule", "oral_liquid", "sachet"}


def pack_qty(label: str):
    m = re.search(r"(\d+(?:\.\d+)?)\s*(?:'s|s\b|tablets?|capsules?|ml|gm|g\b)", label or "", re.I)
    return float(m.group(1)) if m else None


# ---------------------------------------------------------------------------------------------- build


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="data/raw")
    ap.add_argument("--out", default="app/src/main/assets/amrit_ref.db")
    ap.add_argument("--reports", default="data-build/out")
    a = ap.parse_args()
    t0 = time.time()
    os.makedirs(a.reports, exist_ok=True)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    rep = ["# AMRit reference DB build report", ""]
    here = os.path.dirname(os.path.abspath(__file__))

    vocab = Vocab(load_synonyms(os.path.join(here, "synonyms.csv")))

    # 1. curated vocabularies first, so their spelling wins for display
    print("WHO AWaRe ...")
    who, who_nr = load_who(a.raw)
    who_single = {}
    who_combo = []
    for w in who:
        parts = re.split(r"/", w["name"].replace("-", " "))
        if len(parts) == 1:
            base, _, route = w["name"].partition("_")  # 'Fosfomycin_oral' (Watch) vs 'Fosfomycin_IV' (Reserve)
            k = vocab.add(base, "who", 0)
            e = who_single.setdefault(k, {"class": w["class"], "atc": w["atc"], "oral": None, "parenteral": None})
            if route.lower() != "iv":
                e["oral"] = w["aware"]
            if route.lower() != "oral":
                e["parenteral"] = w["aware"]
        else:
            who_combo.append((w, parts))
    for w, parts in who_combo:
        for p in parts:
            if not vocab.lookup(p):
                vocab.add(p, "who", 0)

    print("DDInter ...")
    dd = load_ddinter(a.raw)
    for n in set(dd.Drug_A) | set(dd.Drug_B):
        vocab.add(n, "ddinter", 2)

    # collision check: two different curated drug names that collapse to the same key would be a normaliser bug
    collisions = []
    for k, e in vocab.by_key.items():
        distinct = {n.lower() for n in e["names"]}
        if len(distinct) > 1 and e["sources"] <= {"who", "ddinter"}:
            collisions.append(f"{k}: {sorted(distinct)}")
    rep += ["## Key collisions among WHO/DDInter names (review)", ""] + [f"- {c}" for c in collisions[:200]] + [""]

    print("Medicines ...")
    meds, overlay = load_medicines(a.raw)
    for _, items, _ in meds:
        for nm, _st in items:
            vocab.add(nm, "medicines", 1)

    # 2. CDSCO banned FDCs: map components to vocab; fuzzy only above 90 (typos like 'Acelofenac'); else new ingredient
    print("CDSCO ...")
    fdc = load_cdsco(a.raw)
    fdc_map_log = []
    for f in fdc:
        keys = []
        for alts in f["components"]:
            k, how = None, ""
            for alt in alts:
                k, how = vocab.find(alt, 90)
                if k:
                    break
            if k and how != "exact":
                fdc_map_log.append(f"- '{alts[0]}' -> '{display(vocab.by_key[k])}' ({how})")
            if not k:
                k = vocab.add(alts[0], "cdsco", 3)
                fdc_map_log.append(f"- '{alts[0]}' -> NEW ingredient, not in any medicine list ({how})")
            keys.append(k)
        f["keys"] = keys  # aligned with f["strengths"] variants
    rep += [f"## CDSCO prohibited FDCs: {len(fdc)} entries", "", "Component mapping that was not exact (review):", ""]
    rep += fdc_map_log + [""]

    # 3. Jan Aushadhi
    print("Jan Aushadhi ...")
    ja_rows = []
    pib = {}
    with pdfplumber.open(f"{a.raw}/jan_aushadhi/pib_pmbjp_2110_generics_2025-12-31.pdf") as p:
        for pg in p.pages:  # text lines are more reliable than table extraction for this file
            for line in (pg.extract_text() or "").splitlines():
                m = re.match(r"^(\d+)\s+(\d+)\s+(\S.*)$", line)
                if m:
                    pib[int(m.group(2))] = m.group(3)
    mrp_rows = 0
    for r in pdf_table_rows(f"{a.raw}/jan_aushadhi/pmbi_product_portfolio_mrp_2025-04.pdf"):
        if not r or len(r) < 5 or not r[1] or not str(r[1]).strip().isdigit():
            continue
        code = int(str(r[1]).strip())
        try:
            mrp = float(str(r[4]).replace(",", "").strip())
        except ValueError:
            continue
        mrp_rows += 1
        if code not in pib or mrp <= 0:
            continue  # only products still on the official Dec-2025 list, with a real price
        name = re.sub(r"\s+", " ", str(r[2])).strip()
        unit = re.sub(r"\s+", " ", str(r[3])).strip()
        ja_rows.append((code, name, unit, mrp))
    ja = []
    ja_unmapped = 0
    for code, name, unit, mrp in ja_rows:
        comps = parse_ja_name(name)
        keys, strengths, ok = [], [], True
        for nm, st in comps:
            k, _how = vocab.find(nm, 92)
            if not k:
                ok = False
                break
            keys.append(k)
            strengths.append((k, st))
        if not ok or not keys:
            ja_unmapped += 1
            continue
        ja.append({"code": code, "name": name, "unit": unit, "mrp": mrp, "form": form_of(name + " " + unit),
                   "keys": sorted(set(keys)), "strengths": strengths, "qty": pack_qty(unit)})
    rep += [f"## Jan Aushadhi: {len(pib)} on official list, {mrp_rows} rows in MRP list, {len(ja)} mapped to "
            f"ingredients, {ja_unmapped} not mappable (skipped, mostly devices/nutraceuticals)", ""]

    # ------------------------------------------------------------------------------------------ write DB
    print("Writing DB ...")
    if os.path.exists(a.out):
        os.remove(a.out)
    db = sqlite3.connect(a.out)
    db.executescript(open(os.path.join(here, "schema.sql"), encoding="utf-8").read())

    ing_id = {}
    for i, (k, e) in enumerate(sorted(vocab.by_key.items()), start=1):
        ing_id[k] = i
        w = who_single.get(k)
        name = display(e)
        db.execute("INSERT INTO ingredient VALUES(?,?,?,?,?,?,?,?)",
                   (i, name, k, w["oral"] if w else None, w["parenteral"] if w else None,
                    w["class"] if w else None, w["atc"] if w else None, 1 if w else 0))
    for alias, k in vocab.alias.items():
        db.execute("INSERT OR IGNORE INTO ingredient_alias VALUES(?,?)", (alias, ing_id[k]))
    for alias, k in vocab.syn.items():
        if k in ing_id:
            db.execute("INSERT OR IGNORE INTO ingredient_alias VALUES(?,?)", (alias, ing_id[k]))

    def ikey(keys):
        return "+".join(str(x) for x in sorted({ing_id[k] for k in keys}))

    # products
    mf_id, comp_id = {}, {}
    n_prod = 0
    seen = set()
    for r, items, maybe_incomplete in meds:
        keys = [vocab.lookup(nm) for nm, _ in items]
        if not items or any(k is None for k in keys):
            continue
        name_key, base_key = brand_keys(r.name)
        if not base_key:
            continue
        mf = r.manufacturer.strip()
        if mf not in mf_id:
            mf_id[mf] = len(mf_id) + 1
            db.execute("INSERT INTO manufacturer VALUES(?,?,?)", (mf_id[mf], mf, " ".join(brand_keys(mf)[0].split()[:2])))
        sig = (tuple(sorted((ing_id[k], st or (-1.0, "")) for k, (_, st) in zip(keys, items))), maybe_incomplete)
        if sig not in comp_id:
            comp_id[sig] = len(comp_id) + 1
            cid = comp_id[sig]
            db.execute("INSERT INTO composition VALUES(?,?,?,?)", (cid, len(set(keys)), int(maybe_incomplete), ikey(keys)))
            for iid, st in sorted(set(sig[0]), key=lambda x: x[0]):
                db.execute("INSERT OR IGNORE INTO composition_item VALUES(?,?,?,?)",
                           (cid, iid, st[0] if st[0] >= 0 else None, st[1] or None))
        dedup = (name_key, mf_id[mf], comp_id[sig], r.pack)
        if dedup in seen:
            continue
        seen.add(dedup)
        try:
            price = float(r.price)
        except ValueError:
            price = None
        db.execute("INSERT INTO product VALUES(?,?,?,?,?,?,?,?,?)",
                   (n_prod + 1, r.name.strip(), base_key, mf_id[mf], comp_id[sig],
                    form_of(r.name + " " + r.pack), pack_qty(r.pack), price, int(r.discontinued == "TRUE")))
        n_prod += 1

    # AWaRe combos (e.g. amoxicillin/clavulanic acid = Access as a combination)
    for w, parts in who_combo:
        ks = [vocab.lookup(p) for p in parts]
        if all(ks):
            db.execute("INSERT OR IGNORE INTO aware_combo VALUES(?,?,?)", (ikey(ks), w["name"], w["aware"]))
    nr_ok = 0
    for t in who_nr:
        ks = [vocab.lookup(p) or vocab.fuzzy(p, 92)[0] for p in t.split("/")]
        if all(ks):
            db.execute("INSERT OR IGNORE INTO who_not_recommended VALUES(?,?)", (ikey(ks), t))
            nr_ok += 1
    rep += [f"## WHO: {len(who)} AWaRe entries ({len(who_combo)} combinations); "
            f"{nr_ok}/{len(who_nr)} 'not recommended' FDCs mapped", ""]

    for i, f in enumerate(fdc, start=1):
        variants = []
        for sts in f["strengths"]:
            variants.append(",".join(f"{ing_id[k]}:{st[0]:g}{st[1]}" for k, st in zip(f["keys"], sts) if st))
        db.execute("INSERT INTO banned_fdc VALUES(?,?,?,?,?,?,?,?)",
                   (i, ikey(f["keys"]), f["title"], f["notification"], f["date"], f["source"], f["form_req"],
                    "|".join(v for v in variants if v) or None))

    # interactions: only between ingredients that exist in Indian products or banned/JA lists
    used = {r[0] for r in db.execute("SELECT DISTINCT ingredient_id FROM composition_item")}
    n_int = 0
    best = {}
    for A, B, L in zip(dd.Drug_A, dd.Drug_B, dd.Level):
        if L not in LEVEL:
            continue
        ka, kb = vocab.lookup(A), vocab.lookup(B)
        if not ka or not kb or ka == kb:
            continue
        x, y = sorted((ing_id[ka], ing_id[kb]))
        if x in used and y in used:
            best[(x, y)] = max(best.get((x, y), 0), LEVEL[L])
    for (x, y), lv in best.items():
        db.execute("INSERT INTO interaction VALUES(?,?,?)", (x, y, lv))
        n_int += 1

    for j in ja:
        st = ";".join(f"{ing_id[k]}:{s[0]}{s[1]}" for k, s in j["strengths"] if s)
        db.execute("INSERT INTO jan_aushadhi VALUES(?,?,?,?,?,?,?,?)",
                   (j["code"], j["name"], j["unit"], j["qty"], j["mrp"], j["form"], ikey(j["keys"]), st))

    # "what it's for": use phrases per ingredient set, plus per single ingredient as a fallback
    full = pd.read_parquet(f"{a.raw}/medicines_full_composition/indian_medicines_11k.parquet")
    by_key, by_ing = collections.defaultdict(collections.Counter), collections.defaultdict(collections.Counter)
    for comp, uses in zip(full["composition"], full["uses"]):
        ids = []
        for part in str(comp).split("+"):
            it = split_comp_item(part)
            k = vocab.lookup(it[0]) if it else None
            if not k:
                ids = None
                break
            ids.append(ing_id[k])
        if not ids:
            continue
        phrases = split_uses(uses)
        key = "+".join(str(x) for x in sorted(set(ids)))
        for ph in phrases:
            by_key[key][ph] += 1
            if len(set(ids)) == 1:
                by_ing[ids[0]][ph] += 1
    phrase_id = {}

    def pid(ph):
        if ph not in phrase_id:
            phrase_id[ph] = len(phrase_id) + 1
            db.execute("INSERT INTO use_phrase VALUES(?,?)", (phrase_id[ph], ph))
        return phrase_id[ph]

    def top(counter):
        best = counter.most_common(1)[0][1]
        return [ph for ph, n in counter.most_common(3) if n >= max(1, best * 0.3)]

    for key, cnt in by_key.items():
        db.execute("INSERT INTO composition_use VALUES(?,?)", (key, ",".join(str(pid(p)) for p in top(cnt))))
    for iid, cnt in by_ing.items():
        db.execute("INSERT INTO ingredient_use VALUES(?,?)", (iid, ",".join(str(pid(p)) for p in top(cnt))))
    n_tr = 0
    for tsv in sorted(glob.glob(os.path.join(here, "i18n", "uses_*.tsv"))):
        lang = os.path.basename(tsv)[5:-4]
        for line in open(tsv, encoding="utf-8"):
            if "\t" not in line:
                continue
            en, tr = line.rstrip("\n").split("\t", 1)
            if en in phrase_id and tr.strip():
                db.execute("INSERT OR REPLACE INTO use_phrase_i18n VALUES(?,?,?)", (phrase_id[en], lang, tr.strip()))
                n_tr += 1
    covered = db.execute("SELECT COUNT(*) FROM product p JOIN composition c ON c.id=p.composition_id "
                         "WHERE c.ingr_key IN (SELECT ingr_key FROM composition_use)").fetchone()[0]
    rep += [f"## Uses: {len(phrase_id)} phrases, {len(by_key)} ingredient sets, {len(by_ing)} single ingredients; "
            f"{covered:,} products have an exact-set phrase; {n_tr} translations loaded", ""]
    with open(os.path.join(a.reports, "use_phrases_en.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(phrase_id)))

    # CDSCO drug alerts: NSQ / spurious batches (data/raw/cdsco_alerts, fetched by fetch_cdsco_alerts.py)
    def batch_norm(b: str) -> str:
        b = re.sub(r"[^A-Z0-9]", "", (b or "").upper())
        return b.translate(str.maketrans({"O": "0", "I": "1", "L": "1"}))

    n_alert, months = 0, set()
    for path in sorted(glob.glob(f"{a.raw}/cdsco_alerts/*.json")):
        kind = "SPURIOUS" if os.path.basename(path).startswith("spurious") else "NSQ"
        for r in json.loads(open(path, "rb").read().decode("utf-8", errors="replace")).get("aaData", []):
            batch = (r.get("str_batch_no") or "").strip()
            product = (r.get("str_product_name") or r.get("product_name_from_dtl") or "").strip()
            bn = batch_norm(batch)
            if len(bn) < 3 or not product or bn in {"NA", "N0TMENT10NED", "N1L"}:
                continue
            brand = (r.get("str_brand_name") or "").strip()
            brand = None if brand.upper() in {"", "NA", "N/A", "-", "NIL"} else brand
            month = (r.get("dt_reporting_month_year") or "").strip().upper()
            db.execute("INSERT INTO batch_alert(batch_norm, batch, product, brand, manufacturer, result, kind, reported_by, month) "
                       "VALUES(?,?,?,?,?,?,?,?,?)",
                       (bn, batch, product[:200], brand, (r.get("str_manufactured_by") or "")[:200],
                        (r.get("str_nsq_result") or r.get("str_nsq_remarks") or "")[:300], kind,
                        (r.get("str_reported_by_lab_or_state") or "")[:120], month))
            n_alert += 1
            months.add(month)
    rep += [f"## CDSCO drug alerts: {n_alert:,} NSQ/spurious batch records, {len(months)} reporting months", ""]
    db.execute("INSERT INTO meta VALUES(?,?)", ("batch_alerts", f"{n_alert}"))

    # ICMR AMRSN 2024 resistance facts (curated, verified against the report PDF by verify_amrsn.py)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from resistance import add_resistance  # noqa
    rep += [f"## ICMR AMRSN 2024: {add_resistance(db)} resistance rows", ""]

    # provenance
    files = sorted(glob.glob(f"{a.raw}/**/*.*", recursive=True))
    manifest = {"built_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "inputs": {}}
    for p in files:
        h = hashlib.sha256(open(p, "rb").read()).hexdigest()
        manifest["inputs"][os.path.relpath(p, a.raw).replace("\\", "/")] = h
    for k, v in [("built_at", manifest["built_at"]), ("schema", "1"),
                 ("jan_aushadhi_price_source", "PMBI Product Portfolio List (MRP), April 2025"),
                 ("aware_source", "WHO AWaRe classification 2025 (CC BY-NC-SA 3.0 IGO)"),
                 ("inputs_sha256", hashlib.sha256(json.dumps(manifest["inputs"], sort_keys=True).encode()).hexdigest())]:
        db.execute("INSERT INTO meta VALUES(?,?)", (k, v))
    db.commit()
    db.execute("VACUUM")
    db.close()

    # report + vectors
    n_ab = sum(1 for k in vocab.by_key if k in who_single)
    who_missing = [vocab.by_key[k]["best"][1] for k in who_single if ing_id[k] not in used]
    size = os.path.getsize(a.out)
    rep[1:1] = ["", f"- products: {n_prod:,} (overlay fixed {overlay:,} truncated compositions)",
                f"- compositions: {len(comp_id):,}", f"- ingredients: {len(ing_id):,} ({n_ab} WHO antibiotics)",
                f"- interactions kept: {n_int:,}", f"- banned FDC entries: {len(fdc)}", f"- Jan Aushadhi products: {len(ja)}",
                f"- DB size: {size/1e6:.1f} MB", f"- build time: {time.time()-t0:.0f}s", ""]
    rep += ["## WHO antibiotics not present in any Indian product in the dataset (informational)", "",
            ", ".join(sorted(who_missing)), ""]
    with open(f"{a.reports}/report.md", "w", encoding="utf-8") as f:
        f.write("\n".join(rep))
    with open(f"{a.reports}/manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1)
    # vectors for the Kotlin normaliser test
    sample_names = sorted({n for e in vocab.by_key.values() for n in e["names"]})[::3][:4000]
    from normalize import name_tokens  # noqa
    with open(f"{a.reports}/key_vectors.tsv", "w", encoding="utf-8") as f:
        for n in sample_names:
            f.write(f"I\t{n}\t{ingredient_key(n)}\n")
        for r, _, _ in meds[::60]:
            nk, bk = brand_keys(r.name)
            f.write(f"B\t{r.name}\t{nk}|{bk}\n")
    print("\n".join(rep[:12]))


if __name__ == "__main__":
    main()
