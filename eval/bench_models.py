#!/usr/bin/env python3
"""Benchmark candidate vision models for the fallback on the hardest test photos (glare/blur).
    backend/.venv/Scripts/python -I eval/bench_models.py model1 model2 ...
Uses the backend's own prompt + JSON schema. Reports brand accuracy, JSON validity, latency, measured cost."""
import base64, csv, json, os, re, sys, time
import httpx
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
os.environ.setdefault("AMRIT_DB", os.path.join(os.path.dirname(__file__), "out", "bench.db"))
import app as backend  # noqa: E402

key = [l.split("=", 1)[1].strip() for l in open(os.path.join(os.path.dirname(__file__), "..", "backend", ".env")) if l.startswith("OPENROUTER_API_KEY=")][0]
photos = os.path.join(os.path.dirname(__file__), "..", "data", "test_photos")
rows = [r for r in csv.DictReader(open(os.path.join(photos, "labels.csv"), encoding="utf-8")) if r["condition"] in ("glare", "blur")][:8]


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


for model in sys.argv[1:]:
    ok = valid = 0; lat = []; cost = 0.0; errs = []
    for r in rows:
        img = base64.b64encode(open(os.path.join(photos, r["file"]), "rb").read()).decode()
        body = {"model": model, "messages": [{"role": "system", "content": backend.PROMPT}, {"role": "user", "content": [
            {"type": "text", "text": "Transcribe this medicine strip."},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{img}"}}]}],
            "response_format": {"type": "json_schema", "json_schema": {"name": "strip", "strict": True, "schema": backend.SCHEMA}},
            "temperature": 0, "max_tokens": 400, "usage": {"include": True}}
        t = time.time()
        try:
            res = httpx.post("https://openrouter.ai/api/v1/chat/completions", json=body, timeout=40,
                             headers={"Authorization": f"Bearer {key}"})
            lat.append(time.time() - t)
            d = res.json()
            if "choices" not in d:
                errs.append(str(d.get("error", {}).get("message", d))[:80]); continue
            c = d["choices"][0]["message"]["content"]
            m = re.search(r"\{.*\}", c, re.S)
            j = json.loads(m.group(0) if m else c); valid += 1
            cost += float((d.get("usage") or {}).get("cost") or 0)
            want = norm(r["product_name"].split(" ")[0])
            if want and want[:5] in norm(j.get("brand")):
                ok += 1
        except Exception as e:  # noqa: BLE001
            errs.append(type(e).__name__)
    n = len(rows)
    print(f"{model:40s} brand {ok}/{n}  json {valid}/{n}  p50 {sorted(lat)[len(lat)//2] if lat else 0:.1f}s  "
          f"cost/call ${cost/max(valid,1):.6f}  errors {errs[:2]}")
