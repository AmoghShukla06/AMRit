# Reference data build

```bash
bash data-build/fetch_data.sh                 # downloads everything into data/raw (git-ignored)
python -m pip install -r data-build/requirements.txt
python -I data-build/build_db.py              # -> app/src/main/assets/amrit_ref.db (~22 MB, ~7.5 MB compressed in the APK)
```

The build is deterministic. `data-build/out/manifest.json` records the SHA-256 of every input file. `data-build/out/report.md` lists every mapping that wasn't an exact match (fuzzy fixes, PDF-split words, new ingredients) for review. `data-build/out/key_vectors.tsv` holds normaliser test vectors that the Kotlin tests replay, so the app's normaliser can't drift from the build's.

## Sources

| Folder | Source | What we use | Licence / terms |
|---|---|---|---|
| `medicines/` | [Indian Medicine Dataset](https://github.com/junioralive/indian-medicine-dataset) | 253,973 brands: name, manufacturer, pack, MRP, composition | MIT |
| `medicines_full_composition/` | [dmedhi/indian-medicines](https://huggingface.co/datasets/dmedhi/indian-medicines) (11,825 brands) | full compositions, used to fix truncated rows (below) | not stated; used for composition text only |
| `ddinter/` | [DDInter](https://ddinter2.scbdd.com/download/) | 222k drug pairs with severity (Major/Moderate/Minor) | free for academic use |
| `who_aware/` | [WHO AWaRe 2025](https://iris.who.int/handle/10665/382244) | Access/Watch/Reserve per antibiotic and route; WHO combinations; WHO "not recommended" FDCs | CC BY-NC-SA 3.0 IGO |
| `cdsco/` | [CDSCO FDC page](https://cdsco.gov.in/opencms/opencms/en/Drugs/FDC/) | prohibited FDCs: 2023 (14), 2024 (156 + S(+)Etodolac/Paracetamol), 2026 (16) | Government of India notification |
| `jan_aushadhi/` | [PIB annexure: 2,110 PMBJP generics till 31.12.2025](https://static.pib.gov.in/WriteReadData/specificdocs/documents/2026/feb/doc202626781701.pdf) + PMBI Product Portfolio List with MRP (April 2025, copy hosted by a distributor) | generic name, drug code, unit size, MRP | Government of India |

## Known data limitations (and what the app does about them)

- **Two-ingredient truncation.** The 254k dataset keeps only the first two ingredients of every product (`short_composition1/2`). The 11.8k overlay fixes 1,175 products. The remaining products listing exactly two ingredients are flagged `maybe_incomplete`. For those, the app adds any further ingredients it reads from the strip's printed composition (exact vocabulary matches with a strength only), so a banned four-ingredient cough syrup isn't checked as a two-ingredient one. The result card says the list may be incomplete.
- **DDInter export has severity only**, no management text. The app shows the severity and tells the user to ask a pharmacist. `Unknown`-severity pairs are dropped.
- **Banned FDC notifications can be form- or strength-specific**: "Nimesulide + Paracetamol *dispersible tablets*", "Aceclofenac *50mg* + Paracetamol *125mg* tablet", "Mefenamic Acid + Paracetamol *Injection*". The DB stores `form_req` and the listed strength variants. Only a full match is shown as **banned**. Same ingredients in another form or strength show a softer "a version of this combination is banned, ask your pharmacist". Without this, 6,447 legal Aceclofenac 100 mg + Paracetamol 325 mg products would have been flagged.
- **CDSCO 2019 list (80 FDCs)** is a scanned image with no text layer, so it is not parsed. The consolidated 2021 list (which includes it, plus the 2016/2018 bans and court stays) is downloaded but **not yet used**. It mixes class-level entries ("FDCs of vitamins with anti-inflammatory agents") with per-product statuses that need a pharmacist's reading. Next step for the pharma teammate.
- **Jan Aushadhi prices** come from the April 2025 PMBI portfolio and are joined to the official Dec-2025 list by drug code. 1,638 products map cleanly to ingredients and strengths. A generic is suggested only for the same ingredients, the same strengths and the same form family, and only when it's cheaper per unit.
- **AWaRe applies to systemic antibiotics.** Topical/eye/ear products with an antibiotic are labelled "antibiotic for local use" with no AWaRe group. The route decides the group where WHO differs (e.g. fosfomycin oral = Watch, IV = Reserve). WHO-classified combinations (amoxicillin/clavulanic acid = Access) take precedence over the per-ingredient maximum.
- **Duplicate dose** flags the same ingredient in two different products in "My medicines". There is no maximum-daily-dose table in the source data, and strips don't show dosing frequency, so the app doesn't compute daily totals. A curated max-dose table checked by a pharmacist is the planned addition.
