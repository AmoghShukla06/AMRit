"""ICMR AMRSN 2024 susceptibility rows -> table `resistance` in the reference DB.

Rows come from amrsn_2024.csv, each checked against the report PDF by verify_amrsn.py (run that whenever the CSV
changes). Only drug/organism pairs printed in the report are stored; nothing is inferred for other drugs of a class.
Called by build_db.py; can also be applied to an existing DB: python resistance.py ../app/src/main/assets/amrit_ref.db
"""
import csv, sqlite3, sys
from pathlib import Path

CSV = Path(__file__).with_name("amrsn_2024.csv")
SOURCE = "ICMR Antimicrobial Resistance Surveillance Network, Annual Report 2024"


def add_resistance(db: sqlite3.Connection) -> int:
    db.execute("DROP TABLE IF EXISTS resistance")
    db.execute("CREATE TABLE resistance(organism TEXT NOT NULL, infection TEXT NOT NULL, specimen TEXT NOT NULL, "
               "antibiotic TEXT NOT NULL, ingredient_ids TEXT NOT NULL, susceptible INTEGER NOT NULL, tested INTEGER NOT NULL, "
               "pct REAL NOT NULL, report_page INTEGER NOT NULL, tbl TEXT NOT NULL)")
    key_id = {k: i for i, k in db.execute("SELECT id, key FROM ingredient")}
    n = 0
    for r in csv.DictReader(open(CSV, encoding="utf-8")):
        ids = sorted(key_id[k] for k in r["ingredient_keys"].split(";"))  # KeyError = a key that isn't in the DB: fix the CSV
        db.execute("INSERT INTO resistance VALUES(?,?,?,?,?,?,?,?,?,?)",
                   (r["organism"], r["infection"], r["specimen"], r["antibiotic"], "+".join(map(str, ids)), int(r["susceptible"]),
                    int(r["tested"]), float(r["pct"]), int(r["report_page"]), r["table"]))
        n += 1
    db.execute("DELETE FROM meta WHERE k='resistance_source'")
    db.execute("INSERT INTO meta VALUES(?,?)", ("resistance_source", SOURCE))
    return n


if __name__ == "__main__":
    con = sqlite3.connect(sys.argv[1])
    print(add_resistance(con), "resistance rows")
    con.commit(); con.close()
