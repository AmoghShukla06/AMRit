#!/usr/bin/env python3
"""How exactly do candidate vision models transcribe a dense composition (the user's Becosules Z strip)?
    backend/.venv/Scripts/python -I eval/bench_composition.py model1 model2 ...
Scores each run against the true composition printed on the strip: ingredient + strength pairs exactly right."""
import base64, io, json, os, re, sys, time

import httpx
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
os.environ.setdefault("AMRIT_DB", os.path.join(os.path.dirname(__file__), "out", "bench.db"))
import app as backend  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..")
KEY = [l.split("=", 1)[1].strip() for l in open(os.path.join(ROOT, "backend", ".env")) if l.startswith("OPENROUTER_API_KEY=")][0]
TRUTH = {"thiamine": "10mg", "riboflavin": "10mg", "pyridoxine": "3mg", "b12": "15mcg", "niacinamide": "100mg",
         "pantothenate": "50mg", "folic": "1.5mg", "biotin": "100mcg", "ascorbic": "150mg", "zinc": "41.4mg|15mg"}

im = Image.open(os.path.join(ROOT, "data", "test_photos", "own", "becosules_z_user.png")).convert("RGB")
im.thumbnail((1600, 1600))
buf = io.BytesIO(); im.save(buf, "JPEG", quality=85)
IMG = base64.b64encode(buf.getvalue()).decode()


def score(reading):
    got = {}
    for i in reading.get("ingredients", []):
        n = i["name"].lower(); s = re.sub(r"\s", "", (i.get("strength") or "").lower())
        for k in TRUTH:
            if k in n.replace("vitamin b12", "b12").replace("vitamin b 12", "b12"):
                got.setdefault(k, s)
    ok = sum(1 for k, v in TRUTH.items() if got.get(k) in v.split("|"))
    extra = len(reading.get("ingredients", [])) - len(TRUTH)
    return ok, extra


for model in sys.argv[1:]:
    res = []
    for run in range(2):
        body = {"model": model, "messages": [{"role": "system", "content": backend.PROMPT}, {"role": "user", "content": [
            {"type": "text", "text": "Transcribe this medicine strip."},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{IMG}"}}]}],
            "response_format": {"type": "json_schema", "json_schema": {"name": "strip", "strict": True, "schema": backend.SCHEMA}},
            "temperature": 0, "max_tokens": 1200, "usage": {"include": True}}
        t = time.time()
        try:
            d = httpx.post("https://openrouter.ai/api/v1/chat/completions", json=body, timeout=90,
                           headers={"Authorization": f"Bearer {KEY}"}).json()
            c = d["choices"][0]["message"]["content"]
            j = json.loads(re.search(r"\{.*\}", c, re.S).group(0))
            ok, extra = score(j)
            res.append(f"{ok}/10 extra={extra} {time.time()-t:.1f}s ${float((d.get('usage') or {}).get('cost') or 0):.5f}")
        except Exception as e:  # noqa: BLE001
            res.append(f"ERR {type(e).__name__}")
    print(f"{model:42s} " + " | ".join(res), flush=True)
