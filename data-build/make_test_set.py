#!/usr/bin/env python3
"""Builds the labelled evaluation photo set in data/test_photos.

    python -I data-build/make_test_set.py [--n 70] [--seed 7]

Clear photos: real Indian product photos (brand packshots/strips) with their labelled name + composition, from the
11.8k-product dataset (image URLs point to the 1mg CDN; licence unknown, so photos are used only for local evaluation
and are git-ignored, never shipped). Selection is stratified so the set covers antibiotics, several paracetamol brands,
combinations and products that hit a CDSCO ban.

Degraded photos: a deterministic subset re-rendered with synthetic foil glare + blur (labelled 'glare'/'blur') to
exercise the fallback path. These are synthetic and are reported separately from the clear photos.

Negatives: photos of products absent from the reference DB (condition 'not_in_db'); correct outcome = not found.

Your own phone photos: drop JPGs into data/test_photos/own/ and add rows to labels.csv with condition 'own'.
"""
import argparse, csv, io, os, random, re, sqlite3, sys, time, urllib.request

import pandas as pd
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


# Labels corrected after looking at the photo: the source dataset attaches this image to a same-name product with a
# different composition. Keyed by image URL fragment -> ingredients actually printed on the pack in the photo.
PHOTO_SHOWS = {
    "Kelvin 500mg Tablet": "Ciprofloxacin",  # photo is a KELVIN ciprofloxacin 500 mg box; DB row is a paracetamol 'Kelvin'
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=70)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--negatives", type=int, default=24)
    ap.add_argument("--db", default="app/src/main/assets/amrit_ref.db")
    ap.add_argument("--src", default="data/raw/medicines_full_composition/indian_medicines_11k.parquet")
    ap.add_argument("--out", default="data/test_photos")
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    os.makedirs(a.out, exist_ok=True)
    db = sqlite3.connect(a.db)

    def db_product(name):
        r = db.execute("SELECT p.id, p.form, c.ingr_key FROM product p JOIN composition c ON c.id=p.composition_id "
                       "WHERE p.name=? AND p.form IN ('tablet','tablet_dispersible','capsule')", (name,)).fetchone()
        if not r:
            return None
        ings = [x[0] for x in db.execute(
            "SELECT i.name FROM composition_item ci JOIN ingredient i ON i.id=ci.ingredient_id WHERE ci.composition_id="
            "(SELECT composition_id FROM product WHERE id=?) ORDER BY i.name", (r[0],))]
        abx = db.execute("SELECT COUNT(*) FROM composition_item ci JOIN ingredient i ON i.id=ci.ingredient_id "
                         "WHERE ci.composition_id=(SELECT composition_id FROM product WHERE id=?) AND i.is_antibiotic=1",
                         (r[0],)).fetchone()[0]
        banned = db.execute("SELECT COUNT(*) FROM banned_fdc WHERE ingr_key=?", (r[2],)).fetchone()[0]
        return {"ingredients": ings, "antibiotic": abx > 0, "banned_set": banned > 0}

    df = pd.read_parquet(a.src)
    rows = []
    for name, url in zip(df["name"], df["image_url"]):
        if not isinstance(url, str) or not url.startswith("https://"):
            continue
        info = db_product(name.strip())
        if info:
            rows.append((name.strip(), url, info))
    rnd.shuffle(rows)
    strata = {"antibiotic": [], "paracetamol": [], "combo": [], "banned": [], "other": []}
    for r in rows:
        info = r[2]
        if info["banned_set"]:
            strata["banned"].append(r)
        elif info["antibiotic"]:
            strata["antibiotic"].append(r)
        elif info["ingredients"] == ["Paracetamol"]:
            strata["paracetamol"].append(r)
        elif len(info["ingredients"]) > 1:
            strata["combo"].append(r)
        else:
            strata["other"].append(r)
    quota = {"antibiotic": 0.3, "paracetamol": 0.12, "combo": 0.2, "banned": 0.08, "other": 0.3}
    picked = []
    for k, f in quota.items():
        picked += [(k, r) for r in strata[k][: max(2, int(a.n * f))]]
    print({k: len(v) for k, v in strata.items()}, "->", len(picked))

    labels = []
    for i, (stratum, (name, url, info)) in enumerate(picked):
        fn = f"p{i:03d}.jpg"
        path = os.path.join(a.out, fn)
        if not os.path.exists(path):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                data = urllib.request.urlopen(req, timeout=30).read()
                Image.open(io.BytesIO(data)).convert("RGB").save(path, quality=92)
                time.sleep(0.2)
            except Exception as e:  # noqa: BLE001
                print("  skip", name, e)
                continue
        ingredients = PHOTO_SHOWS.get(name, "|".join(info["ingredients"]))
        labels.append({"file": fn, "product_name": name, "ingredients": ingredients,
                       "condition": "clear", "stratum": stratum, "source": url})

    # synthetic degradations of a fixed subset
    clear = [l for l in labels]
    rnd.shuffle(clear)
    for j, l in enumerate(clear[: max(10, len(clear) // 3)]):
        img = Image.open(os.path.join(a.out, l["file"])).convert("RGB")
        kind = "glare" if j % 2 == 0 else "blur"
        if kind == "glare":
            img = add_glare(img, rnd)
        else:
            img = img.filter(ImageFilter.GaussianBlur(radius=rnd.uniform(2.2, 3.2)))
        fn = l["file"].replace(".jpg", f"_{kind}.jpg")
        img.save(os.path.join(a.out, fn), quality=80)
        labels.append({**l, "file": fn, "condition": kind})

    # negatives: real strips/boxes of products that are NOT in the reference DB at all (many supplements, e.g. popular
    # vitamin brands, are missing from the source dataset). The only correct outcome for these is "not found";
    # without them the confidence threshold can never learn to refuse.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from normalize import brand_keys
    known_bases = {r[0] for r in db.execute("SELECT DISTINCT base_key FROM product")}
    neg_rnd = random.Random(a.seed + 1)
    negs = []
    from normalize import ingredient_key
    alias = dict(db.execute("SELECT a.key, i.name FROM ingredient_alias a JOIN ingredient i ON i.id=a.ingredient_id"))

    def true_ingredients(comp):
        """Canonical names of the labelled composition; None if any part isn't in the vocabulary."""
        out = []
        for part in str(comp).split("+"):
            nm = re.sub(r"\(.*?\)", "", part).strip()
            k = alias.get(ingredient_key(nm))
            if not k:
                return None
            out.append(k)
        return sorted(set(out))

    for name, url, comp in zip(df["name"], df["image_url"], df["composition"]):
        if not isinstance(url, str) or not url.startswith("https://") or not re.search(r"\b(Tablet|Capsule)s?\b", name):
            continue
        base = brand_keys(name)[1]
        if base and base not in known_bases:
            ti = true_ingredients(comp)
            if ti:
                negs.append((name.strip(), url, ti))
    neg_rnd.shuffle(negs)
    for i, (name, url, ti) in enumerate(negs[: a.negatives]):
        fn = f"n{i:03d}.jpg"
        path = os.path.join(a.out, fn)
        if not os.path.exists(path):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                Image.open(io.BytesIO(urllib.request.urlopen(req, timeout=30).read())).convert("RGB").save(path, quality=92)
                time.sleep(0.2)
            except Exception as e:  # noqa: BLE001
                print("  skip", name, e)
                continue
        labels.append({"file": fn, "product_name": name, "ingredients": "|".join(ti), "condition": "not_in_db",
                       "stratum": "not_in_db", "source": url})

    with open(os.path.join(a.out, "labels.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["file", "product_name", "ingredients", "condition", "stratum", "source"])
        w.writeheader()
        w.writerows(labels)
    print(len(labels), "labelled photos ->", a.out)


def add_glare(img, rnd):
    """Specular foil highlight: a bright soft ellipse + a streak, washing out part of the text."""
    w, h = img.size
    over = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(over)
    cx, cy = rnd.uniform(0.3, 0.7) * w, rnd.uniform(0.25, 0.6) * h
    rx, ry = rnd.uniform(0.25, 0.4) * w, rnd.uniform(0.12, 0.22) * h
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=235)
    d.polygon([(0, cy + ry * 1.5), (w, cy - ry * 2.5), (w, cy - ry * 1.6), (0, cy + ry * 2.4)], fill=170)
    over = over.filter(ImageFilter.GaussianBlur(radius=w * 0.04))
    white = Image.new("RGB", (w, h), (255, 255, 255))
    out = Image.composite(white, img, over)
    return out.filter(ImageFilter.GaussianBlur(radius=0.8))


if __name__ == "__main__":
    main()
