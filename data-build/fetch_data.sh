#!/usr/bin/env bash
# Downloads every raw dataset into data/raw (one folder per source). Then: python -I data-build/build_db.py
# Each source's licence/terms: see data-build/README.md.
set -euo pipefail
cd "$(dirname "$0")/.."
R=data/raw
UA="Mozilla/5.0"
mkdir -p $R/medicines $R/medicines_full_composition $R/ddinter $R/who_aware $R/cdsco $R/jan_aushadhi

# 1. Indian Medicine Dataset (MIT) - 253,973 brands
GH=https://raw.githubusercontent.com/junioralive/Indian-Medicine-Dataset/main
curl -fsSL -o $R/medicines/indian_medicine_data.csv $GH/DATA/indian_medicine_data.csv
curl -fsSL -o $R/medicines/updated_indian_medicine_data.csv $GH/DATA/updated_indian_medicine_data.csv
curl -fsSL -o $R/medicines/LICENSE $GH/LICENSE

# 2. Full compositions for 11.8k brands (fixes the 2-ingredient truncation above for those brands)
curl -fsSL -o $R/medicines_full_composition/indian_medicines_11k.parquet \
  https://huggingface.co/datasets/dmedhi/indian-medicines/resolve/main/data/train-00000-of-00001.parquet

# 3. DDInter drug-drug interactions (free, no registration)
for c in A B D H L P R V; do
  curl -fsSL -o $R/ddinter/ddinter_downloads_code_$c.csv https://ddinter2.scbdd.com/static/media/download/ddinter_downloads_code_$c.csv
done

# 4. WHO AWaRe classification 2025 (CC BY-NC-SA 3.0 IGO), IRIS handle 10665/382244
curl -fsSL -A "$UA" -o $R/who_aware/WHO-AWaRe-2025-B09489-eng.xlsx \
  https://iris.who.int/server/api/core/bitstreams/a8c53afc-fa43-4883-9a59-21a9e069f40c/content

# 5. CDSCO prohibited FDC lists (cdsco.gov.in > Drugs > Fixed Dose Combination)
C=https://cdsco.gov.in/opencms/resources/UploadCDSCOWeb/2018
curl -fsSL -A "$UA" -o $R/cdsco/fdc_2023_14_list.pdf "$C/UploadNewsFiles/List%20of%20Prohibited%20FDC%20drugs_%2014%20FDCs_%20dated%2002.06.2023.pdf"
curl -fsSL -A "$UA" -o $R/cdsco/fdc_2024_156_list.pdf "$C/UploadNewsFiles/List%20of%20Prohibited%20FDC%20drugs_%20156%20FDCs_%20dated%2002.08.2024.pdf"
curl -fsSL -A "$UA" -o $R/cdsco/fdc_2024_etodolac_paracetamol.pdf "$C/UploadNewsFiles/List%20of%20Prohibited%20FDC_%20S(+)%20Etodolac%20+%20Paracetamol_%2012.08.2024.pdf"
curl -fsSL -A "$UA" -o $R/cdsco/fdc_2026_16_list.pdf "$C/UploadNewsFiles/List%20of%2016%20FDCs%20banned%20dated%2011.06.2026.pdf"
curl -fsSL -A "$UA" -o $R/cdsco/prohibited_consolidated_2021.pdf "$C/UploadNewsFiles/Updated%20Banned%20Drugs%20List.pdf"
curl -fsSL -A "$UA" -o $R/cdsco/fdc_2019_80_list.pdf "$C/UploadPublic_NoticesFiles/80fdc.pdf"

# 6. Jan Aushadhi: official list of 2,110 PMBJP generics (PIB annexure, 31.12.2025) + PMBI product portfolio with MRP (Apr 2025)
curl -fsSL -A "$UA" -o $R/jan_aushadhi/pib_pmbjp_2110_generics_2025-12-31.pdf \
  https://static.pib.gov.in/WriteReadData/specificdocs/documents/2026/feb/doc202626781701.pdf
curl -fsSL -A "$UA" -o $R/jan_aushadhi/pmbi_product_portfolio_mrp_2025-04.pdf \
  https://www.gpvdspharma.com/wp-content/uploads/2025/04/Jan-Aushadhi-Drug-Code-List-2025.pdf

echo "done. sha256 of every input is recorded in data-build/out/manifest.json by build_db.py"

# 7. Schedule H2 (top 300 brands, G.S.R. 823(E) 17 Nov 2022): coverage benchmark only (data-build/schedule_h2.py).
#    Copy of the Gazette notification as mirrored with the notification text; the list itself is the official one.
mkdir -p $R/h2
curl -fsSL -A "$UA" -o $R/h2/gsr823e_schedule_h2.pdf \
  "https://thehealthmaster.com/wp-content/uploads/2022/11/GSR-823E-dt-17-11-2022-Schedule-H2-Drugs-Rules-Eighth-Amendment-2022.pdf"

# 8. CDSCO monthly drug alerts (NSQ / spurious batches), from the public searchable alert page
python data-build/fetch_cdsco_alerts.py --raw "$R" --since 2023
