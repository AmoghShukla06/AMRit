#!/usr/bin/env python3
"""One-off, build-time translation of fixed English strings into Indian languages. The output files are committed
and compiled into the app; nothing is translated live, and every string is labelled machine-translated (review pending).

    backend/.venv/Scripts/python -I data-build/translate.py uses      # data-build/out/use_phrases_en.txt -> i18n/uses_<lang>.tsv
    backend/.venv/Scripts/python -I data-build/translate.py ui        # core/src/main/resources/i18n/en.json -> <lang>.json

Reads OPENROUTER_API_KEY from backend/.env. Skips strings already translated, so it is resumable.
"""
import json, os, re, sys, time

import httpx

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LANGS = {"hi": "Hindi", "bn": "Bengali", "mr": "Marathi", "te": "Telugu", "ta": "Tamil", "gu": "Gujarati",
         "kn": "Kannada", "ml": "Malayalam", "pa": "Punjabi"}
MODEL = os.environ.get("TRANSLATE_MODEL", "google/gemini-2.5-flash-lite")
KEY = [l.split("=", 1)[1].strip() for l in open(os.path.join(ROOT, "backend", ".env"), encoding="utf-8")
       if l.startswith("OPENROUTER_API_KEY=")][0]

RULES = """Rules:
- Register: INFORMATIONAL, the way a pharmacist calmly explains to a patient ("This medicine is given for fever"),
  never commands or warnings shouted at the reader. Only short button labels may be imperative ("Scan", "Add").
- Plain, simple words an ordinary person understands (a patient reading a medicine app), not literary language.
- Keep medicine and ingredient names, brand names, numbers, units (mg, ml), WHO, AWaRe, CDSCO, Jan Aushadhi, and any
  {placeholder} in curly braces exactly as they are (placeholders must appear unchanged).
- Keep well-known medical terms recognisable; you may add the English term in brackets if the native word is rare.
- Do not add advice or information that is not in the English text."""


def call(lang, items):
    """Translate a batch; if the model returns the wrong count, split the batch and retry the halves."""
    try:
        return _call(lang, items)
    except RuntimeError:
        if len(items) == 1:
            return items  # keep English rather than ship nothing
        mid = len(items) // 2
        return call(lang, items[:mid]) + call(lang, items[mid:])


def _call(lang, items):
    prompt = (f"Translate each English string into {LANGS[lang]}.\n{RULES}\n"
              f"Return JSON: {{\"t\": [ ... ]}} with exactly {len(items)} strings, in the same order.\n\n" +
              json.dumps(items, ensure_ascii=False))
    for attempt in range(2):
        try:
            r = httpx.post("https://openrouter.ai/api/v1/chat/completions", timeout=120,
                           headers={"Authorization": f"Bearer {KEY}"},
                           json={"model": MODEL, "temperature": 0, "response_format": {"type": "json_object"},
                                 "messages": [{"role": "user", "content": prompt}]})
            c = r.json()["choices"][0]["message"]["content"]
            out = json.loads(re.search(r"\{.*\}", c, re.S).group(0))["t"]
            if len(out) == len(items):
                return out
        except Exception as e:  # noqa: BLE001
            print("   retry", lang, type(e).__name__)
        time.sleep(2 + attempt * 3)
    raise RuntimeError(f"{lang}: translation failed")


def placeholders_ok(en, tr):
    return sorted(re.findall(r"\{\w+\}", en)) == sorted(re.findall(r"\{\w+\}", tr))


def do_uses():
    phrases = [l.strip() for l in open(os.path.join(HERE, "out", "use_phrases_en.txt"), encoding="utf-8") if l.strip()]
    os.makedirs(os.path.join(HERE, "i18n"), exist_ok=True)
    for lang in LANGS:
        path = os.path.join(HERE, "i18n", f"uses_{lang}.tsv")
        done = {}
        if os.path.exists(path):
            for line in open(path, encoding="utf-8"):
                if "\t" in line:
                    en, tr = line.rstrip("\n").split("\t", 1)
                    done[en] = tr
        todo = [p for p in phrases if p not in done]
        for i in range(0, len(todo), 60):
            batch = todo[i:i + 60]
            for en, tr in zip(batch, call(lang, batch)):
                done[en] = tr.replace("\t", " ").replace("\n", " ").strip()
            with open(path, "w", encoding="utf-8") as f:
                for en in phrases:
                    if en in done:
                        f.write(f"{en}\t{done[en]}\n")
        print(lang, len(done), "phrases")


def do_ui():
    d = os.path.join(ROOT, "core", "src", "main", "resources", "i18n")
    en = json.load(open(os.path.join(d, "en.json"), encoding="utf-8"))
    for lang in LANGS:
        path = os.path.join(d, f"{lang}.json")
        cur = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
        keys = [k for k in en if k not in cur or not placeholders_ok(en[k], cur[k])]
        for i in range(0, len(keys), 40):
            ks = keys[i:i + 40]
            for k, tr in zip(ks, call(lang, [en[k] for k in ks])):
                cur[k] = tr if placeholders_ok(en[k], tr) else en[k]  # never ship a string with broken placeholders
        json.dump({k: cur[k] for k in en if k in cur}, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(lang, len(cur), "ui strings")


if __name__ == "__main__":
    {"uses": do_uses, "ui": do_ui}[sys.argv[1]]()
