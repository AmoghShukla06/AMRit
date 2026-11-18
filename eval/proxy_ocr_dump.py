#!/usr/bin/env python3
"""DESKTOP PROXY ONLY. Writes RapidOCR (PaddleOCR models on onnxruntime) output in the app's OcrResult JSON format so
the harness can be developed and smoke-tested without a phone. It is NOT ML Kit: numbers from this source are labelled
'proxy' and are not the numbers we report. Reported numbers come from eval/run_on_device.sh (ML Kit on the phone).

    eval/.venv/Scripts/python -I eval/proxy_ocr_dump.py [--photos data/test_photos] [--out eval/out/proxy]
"""
import argparse, csv, json, os
from PIL import Image
from rapidocr_onnxruntime import RapidOCR


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--photos", default="data/test_photos")
    ap.add_argument("--out", default="eval/out/proxy")
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "ocr"), exist_ok=True)
    eng = RapidOCR()
    rows = list(csv.DictReader(open(os.path.join(a.photos, "labels.csv"), encoding="utf-8")))
    for r in rows:
        dst = os.path.join(a.out, "ocr", r["file"] + ".json")
        if os.path.exists(dst):
            continue  # resumable
        img = Image.open(os.path.join(a.photos, r["file"])).convert("RGB")
        import numpy as np
        res, _ = eng(np.asarray(img))
        lines = []
        for box, text, score in res or []:
            ys = [p[1] for p in box]
            lines.append({"text": text, "height": float(max(ys) - min(ys)), "confidence": float(score)})
        json.dump({"lines": lines, "width": img.width, "height": img.height},
                  open(dst, "w", encoding="utf-8"))
    print(len(rows), "photos ->", a.out)


if __name__ == "__main__":
    main()
