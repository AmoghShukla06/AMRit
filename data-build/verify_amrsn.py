"""Checks every row of amrsn_2024.csv against the ICMR AMRSN Annual Report 2024 PDF, so no figure is typed from memory.

For each row: the printed report page (PDF page = printed + 2) must contain "<susceptible> / <tested>" and "(<pct>",
and susceptible / tested must round to the printed percentage. Needs pdfplumber (only for this check, not for the build).
Usage: python verify_amrsn.py <path to the report PDF>
Source: https://www.icmr.gov.in/icmrobject/uploads/Report/1763981012_icmramrsnannualreport2024.pdf
"""
import csv, re, sys
from pathlib import Path
import pdfplumber

rows = list(csv.DictReader(open(Path(__file__).with_name("amrsn_2024.csv"), encoding="utf-8")))
bad = 0
with pdfplumber.open(sys.argv[1]) as pdf:
    for r in rows:
        page = int(r["report_page"]) + 2
        text = re.sub(r"\s+", " ", pdf.pages[page - 1].extract_text() or "")
        s, n, pct = int(r["susceptible"]), int(r["tested"]), float(r["pct"])
        # a pair can wrap across lines in the table ("10221 /" ... "13476"): accept both halves on the page
        pair = re.search(rf"\b{s}\s*/\s*{n}\b", text) or (re.search(rf"\b{s}\s*/", text) and re.search(rf"\b{n}\b", text))
        printed = re.search(rf"\({re.escape(r['pct'])}\b", text)
        exact = abs(round(100 * s / n, 1) - pct) <= 0.06
        ok = bool(pair and printed and exact and r["antibiotic"].split("-")[0][:8].lower() in text.lower())
        if not ok:
            bad += 1
            print(f"MISMATCH {r['organism']} / {r['antibiotic']}: pair={bool(pair)} printed={bool(printed)} ratio={100*s/n:.2f} vs {pct}")
print(f"{len(rows) - bad}/{len(rows)} rows verified against the PDF")
sys.exit(1 if bad else 0)
