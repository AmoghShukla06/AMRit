"""Download CDSCO's public Not-of-Standard-Quality (NSQ) and spurious drug alerts, month by month.

    python data-build/fetch_cdsco_alerts.py [--raw data/raw] [--since 2023]

Source: the searchable alert page embedded at cdsco.gov.in > Notifications > NSQ Alerts
(https://cdscoonline.gov.in/CDSCO/viewPublicNSQDrug), which CDSCO moved its monthly alerts to in Aug 2025. Uses the same
public JSON endpoints the page calls, one request per second. Writes data/raw/cdsco_alerts/{nsq,spurious}_YYYY-MM.json
exactly as received (records: product, brand, batch, mfg/expiry dates, manufacturer, result, reporting lab/state, month).
"""
import argparse, http.cookiejar, json, os, time, urllib.parse, urllib.request

BASE = "https://cdscoonline.gov.in/CDSCO"
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="data/raw")
    ap.add_argument("--since", type=int, default=2023, help="first reporting year to fetch")
    a = ap.parse_args()
    out = f"{a.raw}/cdsco_alerts"
    os.makedirs(out, exist_ok=True)
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    opener.addheaders = [("User-Agent", "Mozilla/5.0 (AMRit data build)"), ("Referer", f"{BASE}/viewPublicNSQDrug")]

    def get(path, **q):
        time.sleep(1.0)  # be polite to a government server
        with opener.open(f"{BASE}/{path}?{urllib.parse.urlencode(q)}", timeout=120) as r:
            return r.read()

    get("viewPublicNSQDrug")  # session cookie, as a browser would
    total = 0
    for tab, endpoint in (("nsq", "filteredNsqDrugTable"), ("spurious", "filteredSpuriousDrugTable")):
        years = [int(y) for y in json.loads(get("reportingYears", tab=tab)) if int(y) >= a.since]
        for y in years:
            for m in json.loads(get("publicReportingMonths", year=y, tab=tab)):
                body = get(endpoint, month=f"{m}-{y}", source="All", tab=tab)
                rows = json.loads(body.decode("utf-8", errors="replace")).get("aaData", [])
                path = f"{out}/{tab}_{y}-{MONTHS.index(m[:3].title()) + 1:02d}.json"
                with open(path, "wb") as f:
                    f.write(body)
                total += len(rows)
                print(f"{tab} {m}-{y}: {len(rows)} records")
    print(f"done: {total} records in {out}")


if __name__ == "__main__":
    main()
