"""Coverage benchmark: the government's Schedule H2 list of India's top 300 drug brands (G.S.R. 823(E), 17 Nov 2022).

    python data-build/schedule_h2.py [--raw data/raw] [--db app/src/main/assets/amrit_ref.db]

Parses the numbered list from the notification PDF and checks each entry against the reference DB:
  exact  - a product with this brand, the same strength numbers and the same dosage-form group
  brand  - the brand is in the DB, but not this strength/form
  none   - the brand is not in the DB
Writes data-build/out/h2_top300.tsv (the parsed list) and data-build/out/h2_coverage.tsv (per-entry result).
"""
import argparse, os, re, sqlite3, sys

import pdfplumber

sys.path.insert(0, os.path.dirname(__file__))
from normalize import brand_keys, name_tokens  # noqa: E402

ENTRY = re.compile(r"(?:^|\s)(\d{1,3})\.\s+(.+?)(?=\s\d{1,3}\.\s|$)")

# form words in the list -> the DB's form groups (same grouping as the app's dosage-form rule)
GROUPS = {
    "solid": {"tablet", "tablets", "capsule", "capsules", "sachet"},
    "liquid": {"syrup", "suspension", "expectorant", "liquid", "drops", "solution", "linctus", "elixir"},
    "injection": {"injection", "vial"},
    "topical": {"cream", "ointment", "gel", "lotion"},
    "inhaled": {"inhaler", "respules", "rotacap", "rotacaps", "mdi"},
}
DB_GROUP = {"tablet": "solid", "tablet_dispersible": "solid", "capsule": "solid", "sachet": "solid", "oral_liquid": "liquid",
            "injection": "injection", "topical": "topical", "inhaled": "inhaled", "eye_ear_nose": "eye_ear"}


HEADER = re.compile(r"GAZETTE OF INDIA|राजपत्र|^Serial|Brand name of drug formulation|^number$|^\d{1,3}$")


def parse(pdf: str) -> list[tuple[int, str]]:
    """Numbered table rows after the table header; page headers skipped, wrapped rows joined, stops at the signature."""
    with pdfplumber.open(pdf) as p:
        lines = [l.strip() for pg in p.pages for l in (pg.extract_text() or "").splitlines()]
    start = next(i for i, l in enumerate(lines) if "Brand name of drug formulation" in l)
    out: dict[int, str] = {}
    cur = None
    for l in lines[start + 1:]:
        if l.startswith("[No.") or "Jt. Secy" in l:
            break
        if not l or HEADER.search(l):
            continue
        m = re.match(r"^(\d{1,3})\.\s+(.+)$", l)
        if m and int(m.group(1)) == (cur or 0) + 1:
            cur = int(m.group(1))
            out[cur] = m.group(2)
        elif cur is not None:
            out[cur] += " " + l  # wrapped row
    return [(n, re.sub(r"\s+", " ", e).rstrip(".”\" ").strip()) for n, e in sorted(out.items())]


def entry_parts(name: str):
    """'DOLO 650 MG TABLET 15' -> (name without pack count, numbers, form group)."""
    toks = name.split()
    if toks and re.fullmatch(r"\d+", toks[-1]):  # trailing pack count
        toks = toks[:-1]
    if len(toks) >= 2 and re.fullmatch(r"\d+(\.\d+)?", toks[-2]) and toks[-1].lower() in {"ml", "gm", "g", "mdi"}:
        toks = toks[:-2]  # trailing pack volume "100 ML"
    core = " ".join(toks)
    words = set(name_tokens(core))
    group = next((g for g, ws in GROUPS.items() if words & ws), None)
    nums = {float(x) for x in re.findall(r"\d+(?:\.\d+)?", core)}
    return core, nums, group


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="data/raw")
    ap.add_argument("--db", default="app/src/main/assets/amrit_ref.db")
    a = ap.parse_args()
    entries = parse(f"{a.raw}/h2/gsr823e_schedule_h2.pdf")
    if len(entries) != 300:
        missing = sorted(set(range(1, 301)) - {n for n, _ in entries})
        sys.exit(f"parsed {len(entries)} entries, expected 300; missing numbers: {missing[:30]}. Not guessing; check the PDF.")
    db = sqlite3.connect(a.db)
    by_base = {}
    for pid, name, base, form, comp in db.execute("SELECT id, name, base_key, form, composition_id FROM product"):
        by_base.setdefault(base, []).append((pid, name, form, comp))
    strengths = {}
    for comp, s in db.execute("SELECT composition_id, strength FROM composition_item WHERE strength IS NOT NULL"):
        strengths.setdefault(comp, set()).add(float(s))

    os.makedirs("data-build/out", exist_ok=True)
    rows, counts = [], {"exact": 0, "brand": 0, "none": 0}
    for n, name in entries:
        core, nums, group = entry_parts(name)
        _, base = brand_keys(core)
        cands = by_base.get(base, [])
        # descriptor words the DB names leave out ("LIMCEE CHEW ORANGE" -> limcee, "ASCORIL D PLUS NEW" -> ascoril d plus);
        # the strength + form check below still decides whether it is the same product
        toks = base.split()
        while not cands and len(toks) > 1:
            toks = toks[:-1]
            if sum(c.isalpha() for c in "".join(toks)) >= 4:
                cands = by_base.get(" ".join(toks), [])
                if cands:
                    base = " ".join(toks)
        level, match = "none", ""
        if cands:
            level, match = "brand", cands[0][1]
            for pid, pname, form, comp in cands:
                have = {float(x) for x in re.findall(r"\d+(?:\.\d+)?", pname)} | strengths.get(comp, set())
                form_ok = group is None or DB_GROUP.get(form) == group
                if form_ok and nums <= have:
                    level, match = "exact", pname
                    break
        counts[level] += 1
        rows.append((n, name, base, level, match))
    with open("data-build/out/h2_top300.tsv", "w", encoding="utf-8") as f:
        f.write("n\tentry\n" + "".join(f"{n}\t{e}\n" for n, e in entries))
    with open("data-build/out/h2_coverage.tsv", "w", encoding="utf-8") as f:
        f.write("n\tentry\tbase_key\tlevel\tdb_product\n" + "".join("\t".join(map(str, r)) + "\n" for r in rows))
    print(f"Schedule H2 top-300 coverage: exact product {counts['exact']}/300, brand known {counts['exact'] + counts['brand']}/300, "
          f"brand missing {counts['none']}/300")


if __name__ == "__main__":
    main()
