# AMRit evaluation report

Source: `eval\out\device` · measured on: motorola edge 60 fusion  
Photos: 116 ({clear=69, glare=12, blur=11, not_in_db=24})  
Decisions use the app's evidence rules (core/Resolver.kt): accept only on corroborated evidence, otherwise ask or fall back.

Accuracy = right ingredients for products in the DB; for products not in the DB, saying not found or showing the right ingredients.  
Wrong = the app would show another product's ingredients. A simulated user on the confirm screen picks the right option only if it is offered.

| Set | n | Accuracy | Wrong | Zero-API | Hit API | Not resolved | p50 | p95 |
|---|---|---|---|---|---|---|---|---|
| all | 116 | 99.1% | 0.0% | 76.7% | 23.3% | 8.6% | 111 ms | 3311 ms |
| clear | 69 | 98.6% | 0.0% | 89.9% | 10.1% | 1.4% | 96 ms | 2463 ms |
| glare+blur | 23 | 100.0% | 0.0% | 73.9% | 26.1% | 0.0% | 153 ms | 4138 ms |
| not in DB | 24 | 100.0% | 0.0% | 41.7% | 58.3% | 37.5% | 264 ms | 2376 ms |

API cost per 100 scans: $0.0045 (27 of 116 scans call the API × $0.000192 measured per call)

## Paths

- ON_DEVICE: 59
- CONFIRMED: 30
- API_NOT_FOUND: 8
- API: 17
- NEEDS_API: 2

## Wrong answers

None.

## Per photo

| file | cond | expected | path | predicted | evidence |
|---|---|---|---|---|---|
| p000.jpg | clear | Mahacef-CV 200 Tablet | ON_DEVICE ✓ | Mahacef-CV 200 Tablet | BRAND_CORROBORATED |
| p001.jpg | clear | Amoxyclav 625 Tablet | ON_DEVICE ✓ | Amoxyclav 625 Tablet | BRAND_CORROBORATED |
| p002.jpg | clear | Ampoxin 250 Capsule | ON_DEVICE ✓ | Ampoxin 250 Capsule | BRAND_CORROBORATED |
| p003.jpg | clear | Zocon-T Kit | CONFIRMED ✓ | Zocon-T Kit | user |
| p004.jpg | clear | Zatura 250 Tablet | ON_DEVICE ✓ | Zatura 250 Tablet | BRAND_CORROBORATED |
| p005.jpg | clear | Moxikind-CV 375 Tablet | ON_DEVICE ✓ | Moxikind-CV 375 Tablet | BRAND_CORROBORATED |
| p006.jpg | clear | Azikem 500mg Tablet | ON_DEVICE ✓ | Azikem 500mg Tablet | BRAND_CORROBORATED |
| p007.jpg | clear | Zifi 50 DT Tablet | ON_DEVICE ✓ | Zifi 50 DT Tablet | BRAND_CORROBORATED |
| p008.jpg | clear | Rifaxigyl 550mg Tablet | ON_DEVICE ✓ | Rifaxigyl 550mg Tablet | BRAND_CORROBORATED |
| p009.jpg | clear | Loxof 750mg Tablet | ON_DEVICE ✓ | Loxof 750mg Tablet | BRAND_CORROBORATED |
| p010.jpg | clear | Metrogyl 200 Tablet | API_NOT_FOUND | – | - |
| p011.jpg | clear | Oflox TZ Tablet | ON_DEVICE ✓ | Oflox TZ Tablet | BRAND_CORROBORATED |
| p012.jpg | clear | Gudcef 100mg Tablet DT | ON_DEVICE ✓ | Gudcef 100mg Tablet DT | BRAND_CORROBORATED |
| p013.jpg | clear | Zipod 200mg Tablet | ON_DEVICE ✓ | Zipod 200mg Tablet | BRAND_CORROBORATED |
| p014.jpg | clear | Levomac 750 Tablet | ON_DEVICE ✓ | Levomac 750 Tablet | BRAND_CORROBORATED |
| p015.jpg | clear | Oflomac TZ 200 mg/600 mg Tablet | ON_DEVICE ✓ | Oflomac TZ 200 mg/600 mg Tablet | BRAND_CORROBORATED |
| p016.jpg | clear | Metrogyl Compound Plus  Tablet | API ✓ | Metrogyl Compound Plus  Tablet | BRAND_CORROBORATED |
| p017.jpg | clear | Nifty-SR Tablet | ON_DEVICE ✓ | Nifty-SR Tablet | BRAND_CORROBORATED |
| p018.jpg | clear | Grandcef-CV Tablet | ON_DEVICE ✓ | Grandcef-CV Tablet | BRAND_CORROBORATED |
| p019.jpg | clear | Taxim-O 400 Tablet | ON_DEVICE ✓ | Taxim-O 400 Tablet | BRAND_CORROBORATED |
| p020.jpg | clear | Norflox 200 Tablet | ON_DEVICE ✓ | Norflox 200 Tablet | BRAND_CORROBORATED |
| p021.jpg | clear | Kelvin 500mg Tablet | CONFIRMED ✓ | Ciproflox 500mg Tablet | user |
| p022.jpg | clear | Albimol 650mg Tablet | ON_DEVICE ✓ | Albimol 650mg Tablet | BRAND_CORROBORATED |
| p023.jpg | clear | Xtpara Tablet SR | ON_DEVICE ✓ | Paracetamol 300 mg (generic) | COMPOSITION |
| p024.jpg | clear | Malidens 650 Tablet | ON_DEVICE ✓ | Malidens 650 Tablet | BRAND_CORROBORATED |
| p025.jpg | clear | Pyrigesic 650 Tablet | ON_DEVICE ✓ | Pyrigesic 650 Tablet | BRAND_CORROBORATED |
| p026.jpg | clear | Algina 650 Tablet | ON_DEVICE ✓ | Paracetamol Tablet | BRAND_CORROBORATED |
| p027.jpg | clear | Parawel 650mg Tablet | ON_DEVICE ✓ | Parawel 650mg Tablet | BRAND_CORROBORATED |
| p028.jpg | clear | Pacimol 650 Tablet | ON_DEVICE ✓ | Pacimol 650 Tablet | BRAND_CORROBORATED |
| p029.jpg | clear | Zisper Forte Tablet | API ✓ | Zisper Forte Tablet | BRAND_CORROBORATED |
| p030.jpg | clear | Neuroprime-PG Capsule | CONFIRMED ✓ | Neuroprime-PG Capsule | user |
| p031.jpg | clear | Zoryl-M 1 Tablet PR | CONFIRMED ✓ | Zoryl-M 1 Tablet PR | user |
| p032.jpg | clear | Winbp 20 H Tablet | API ✓ | Olmesartan Medoxomil 20 mg + Hydrochlorothiazide 12.5 mg (generic) | COMPOSITION |
| p033.jpg | clear | Glucobay M50 Tablet | ON_DEVICE ✓ | Glucobay M50 Tablet | BRAND_CORROBORATED |
| p034.jpg | clear | Laformin GV 2mg/500mg/0.2mg Tablet ER | ON_DEVICE ✓ | Laformin GV 2mg/500mg/0.2mg Tablet ER | BRAND_CORROBORATED |
| p035.jpg | clear | Intaglip-M Tablet | ON_DEVICE ✓ | Intaglip-M Tablet | BRAND_CORROBORATED |
| p036.jpg | clear | Aldigesic-Rab Capsule SR | ON_DEVICE ✓ | Aldigesic-Rab Capsule SR | BRAND_CORROBORATED |
| p037.jpg | clear | Pregalin X 75 Capsule | CONFIRMED ✓ | Pregalin X 75 Capsule | user |
| p038.jpg | clear | Etoshine MR Tablet | ON_DEVICE ✓ | Etoshine MR Tablet | BRAND_CORROBORATED |
| p039.jpg | clear | Olmat MT 50 Tablet ER | ON_DEVICE ✓ | Olmat MT 50 Tablet ER | BRAND_CORROBORATED |
| p040.jpg | clear | Montina-L Tablet | ON_DEVICE ✓ | Montina-L Tablet | BRAND_CORROBORATED |
| p041.jpg | clear | Arvast CV 10 Capsule | ON_DEVICE ✓ | Arvast CV 10 Capsule | BRAND_CORROBORATED |
| p042.jpg | clear | Zosa-L Capsule PR | ON_DEVICE ✓ | Zosa-L Capsule PR | BRAND_UNIQUE |
| p043.jpg | clear | Levorid D Tablet | ON_DEVICE ✓ | Levorid D Tablet | BRAND_CORROBORATED |
| p044.jpg | clear | Lupin Acp 100mg/325mg Tablet | ON_DEVICE ✓ | Lupin Acp 100mg/325mg Tablet | BRAND_CORROBORATED |
| p045.jpg | clear | Zenflox-UTI Tablet | ON_DEVICE ✓ | Zenflox-UTI Tablet | BRAND_CORROBORATED |
| p046.jpg | clear | Norflokem TZ Tablet | CONFIRMED ✓ | Norflokem TZ Tablet | user |
| p047.jpg | clear | Ketoflam-P Tablet | CONFIRMED ✓ | Ketoflam-P Tablet | user |
| p048.jpg | clear | Walaphage 500 Tablet | API ✓ | Walaphage 500 Tablet | BRAND_CORROBORATED |
| p049.jpg | clear | Tazloc 80 Tablet | ON_DEVICE ✓ | Tazloc 80 Tablet | BRAND_CORROBORATED |
| p050.jpg | clear | Monit 30 SR Tablet | ON_DEVICE ✓ | Monit 30 SR Tablet | BRAND_CORROBORATED |
| p051.jpg | clear | Hizet 10mg Tablet | API ✓ | Hizet 10mg Tablet | BRAND_CORROBORATED |
| p052.jpg | clear | Gestofit 200 Capsule | CONFIRMED ✓ | Gestofit 200 Capsule | user |
| p053.jpg | clear | Goodova-L 5mg Tablet | ON_DEVICE ✓ | Goodova-L 5mg Tablet | BRAND_CORROBORATED |
| p054.jpg | clear | Goodflo 150mg Capsule | ON_DEVICE ✓ | Goodflo 150mg Capsule | BRAND_CORROBORATED |
| p055.jpg | clear | Vorifast 200 Tablet | API ✓ | Vorifast 200 Tablet | BRAND_CORROBORATED |
| p056.jpg | clear | Desirox 250mg Tablet | ON_DEVICE ✓ | Desirox 250mg Tablet | BRAND_CORROBORATED |
| p057.jpg | clear | Bilashine Tablet | ON_DEVICE ✓ | Bilashine Tablet | BRAND_CORROBORATED |
| p058.jpg | clear | Atorva 5 Tablet | ON_DEVICE ✓ | Atorva 5 Tablet | BRAND_CORROBORATED |
| p059.jpg | clear | Lcfex Tablet | ON_DEVICE ✓ | Lcfex Tablet | BRAND_CORROBORATED |
| p060.jpg | clear | Nexito 5 Tablet | CONFIRMED ✓ | Nexito 10 Tablet | user |
| p061.jpg | clear | Normabrain 800mg Tablet | ON_DEVICE ✓ | Normabrain 800mg Tablet | BRAND_CORROBORATED |
| p062.jpg | clear | Nimica 100 DT Tablet | ON_DEVICE ✓ | Nimica 100 DT Tablet | BRAND_CORROBORATED |
| p063.jpg | clear | Akubliss Tablet | ON_DEVICE ✓ | Akubliss Tablet | BRAND_CORROBORATED |
| p064.jpg | clear | Lopamide Tablet | CONFIRMED ✓ | Lopamide Tablet | user |
| p065.jpg | clear | Valrate CR 300 Tablet | ON_DEVICE ✓ | Valrate CR 300 Tablet | BRAND_CORROBORATED |
| p066.jpg | clear | Amide 100 Tablet | CONFIRMED ✓ | Amide 100 Tablet | user |
| p067.jpg | clear | Rosuvas 5 Tablet | CONFIRMED ✓ | Rosuvas 10 Tablet | user |
| p068.jpg | clear | H-Quine Tablet | CONFIRMED ✓ | H-Quine Tablet | user |
| p041_glare.jpg | glare | Arvast CV 10 Capsule | ON_DEVICE ✓ | Arvast CV 10 Capsule | BRAND_CORROBORATED |
| p043_blur.jpg | blur | Levorid D Tablet | CONFIRMED ✓ | Levorid D Tablet | user |
| p061_glare.jpg | glare | Normabrain 800mg Tablet | API ✓ | Normabrain 800mg Tablet | BRAND_CORROBORATED |
| p030_blur.jpg | blur | Neuroprime-PG Capsule | CONFIRMED ✓ | Neuroprime-PG Capsule | user |
| p052_glare.jpg | glare | Gestofit 200 Capsule | ON_DEVICE ✓ | Gestofit 200 Capsule | BRAND_CORROBORATED |
| p000_blur.jpg | blur | Mahacef-CV 200 Tablet | API ✓ | Mahacef-CV 200 Tablet | BRAND_CORROBORATED |
| p051_glare.jpg | glare | Hizet 10mg Tablet | CONFIRMED ✓ | Hizet Oral Solution | user |
| p012_blur.jpg | blur | Gudcef 100mg Tablet DT | CONFIRMED ✓ | Cepod 100mg Tablet DT | user |
| p066_glare.jpg | glare | Amide 100 Tablet | ON_DEVICE ✓ | Amide 100 Tablet | BRAND_CORROBORATED |
| p027_blur.jpg | blur | Parawel 650mg Tablet | API ✓ | Parawel 650mg Tablet | BRAND_CORROBORATED |
| p026_glare.jpg | glare | Algina 650 Tablet | ON_DEVICE ✓ | Algina 650 Tablet | BRAND_CORROBORATED |
| p034_blur.jpg | blur | Laformin GV 2mg/500mg/0.2mg Tablet ER | CONFIRMED ✓ | Laformin GV 2mg/500mg/0.2mg Tablet ER | user |
| p024_glare.jpg | glare | Malidens 650 Tablet | ON_DEVICE ✓ | Malidens 650 Tablet | BRAND_CORROBORATED |
| p004_blur.jpg | blur | Zatura 250 Tablet | API ✓ | Zatura 250 Tablet | BRAND_CORROBORATED |
| p035_glare.jpg | glare | Intaglip-M Tablet | ON_DEVICE ✓ | Intaglip-M Tablet | BRAND_CORROBORATED |
| p054_blur.jpg | blur | Goodflo 150mg Capsule | API ✓ | Goodflo 150mg Capsule | BRAND_CORROBORATED |
| p021_glare.jpg | glare | Kelvin 500mg Tablet | CONFIRMED ✓ | Ciproflox 500mg Tablet | user |
| p060_blur.jpg | blur | Nexito 5 Tablet | ON_DEVICE ✓ | Nexito 5 Tablet | BRAND_CORROBORATED |
| p056_glare.jpg | glare | Desirox 250mg Tablet | CONFIRMED ✓ | Desirox 250mg Tablet | user |
| p003_blur.jpg | blur | Zocon-T Kit | CONFIRMED ✓ | Zocon-T Kit | user |
| p018_glare.jpg | glare | Grandcef-CV Tablet | ON_DEVICE ✓ | Grandcef-CV Tablet | BRAND_CORROBORATED |
| p011_blur.jpg | blur | Oflox TZ Tablet | API ✓ | Oflox TZ Tablet | BRAND_CORROBORATED |
| p007_glare.jpg | glare | Zifi 50 DT Tablet | ON_DEVICE ✓ | Zifi 50 DT Tablet | BRAND_CORROBORATED |
| n000.jpg | not_in_db | Amlopres Trio Tablet | NEEDS_API ✓ | – | - |
| n001.jpg | not_in_db | Rivofonet Tablet | API_NOT_FOUND ✓ | – | - |
| n002.jpg | not_in_db | Auxisoda Tablet | CONFIRMED ✓ | Auxisoda-EC 500 Tablet | user |
| n003.jpg | not_in_db | Alfugress Tablet PR | ON_DEVICE ✓ | Alfuzosin Tablet | BRAND_CORROBORATED |
| n004.jpg | not_in_db | Ambronac Tablet | API ✓ | Ambroxol 30 mg + Acetylcysteine 200 mg (generic) | COMPOSITION |
| n005.jpg | not_in_db | Alastin 10mg Tablet | API ✓ | Loratadine 10 mg (generic) | COMPOSITION |
| n006.jpg | not_in_db | Amlokind-L Tablet | API_NOT_FOUND ✓ | – | - |
| n007.jpg | not_in_db | Pantotas D 10mg/40mg Tablet | CONFIRMED ✓ | Pentotas D 10 mg/40 mg Tablet | user |
| n008.jpg | not_in_db | Telmikaa MT 50mg Tablet | API_NOT_FOUND ✓ | – | - |
| n009.jpg | not_in_db | Afdura Tablet ER | CONFIRMED ✓ | Alfuzosin-D Tablet ER | user |
| n010.jpg | not_in_db | Amcard-AT Tablet | API ✓ | Amcardia AT 2.5 mg/50 mg Tablet | WEAK |
| n011.jpg | not_in_db | Nervijen-NP Tablet | API_NOT_FOUND ✓ | – | - |
| n012.jpg | not_in_db | Atorva TG Tablet | CONFIRMED ✓ | Atorva F Tablet | user |
| n013.jpg | not_in_db | Telmikaa MT 25 Tablet ER | API ✓ | Telmisartan 40 mg + Metoprolol Succinate 23.75 mg (generic) | COMPOSITION |
| n014.jpg | not_in_db | Defwave  6 Tablet | CONFIRMED ✓ | Deflazacort 6 mg (generic) | user |
| n015.jpg | not_in_db | Anabrez Tablet | API_NOT_FOUND ✓ | – | - |
| n016.jpg | not_in_db | Panum-DSR Capsule | CONFIRMED ✓ | Panum-D Capsule PR | user |
| n017.jpg | not_in_db | Alrista SR Tablet | API ✓ | Alrista 50mg Tablet | BRAND_CORROBORATED |
| n018.jpg | not_in_db | Rabsig-DSR Capsule | CONFIRMED ✓ | Rabsin DSR 30mg/20mg Capsule | user |
| n019.jpg | not_in_db | Aceclo Sera 100mg/325mg/15mg Tablet | API_NOT_FOUND ✓ | – | - |
| n020.jpg | not_in_db | Atocor-F Tablet | CONFIRMED ✓ | Atorvastatin 10 mg + Fenofibrate 160 mg (generic) | user |
| n021.jpg | not_in_db | Dovy-M Tablet | API_NOT_FOUND ✓ | – | - |
| n022.jpg | not_in_db | Silofast D 8 Tablet | NEEDS_API ✓ | – | - |
| n023.jpg | not_in_db | Diomilin-NT Tablet | CONFIRMED ✓ | Domylin NT 75mg/10mg/1500mcg Tablet | user |
