# AMRit evaluation report

Source: `eval\out\proxy` (no device timings)  
Photos: 92 ({clear=69, glare=12, blur=11})  
Confidence model: logistic over 10 features, leave-one-out; accept ≥ 0.900, confirm ≥ 0.500

| Set | n | Brand→ingredient accuracy | Wrong (unsafe) | Zero-API | Hit API | Not resolved | p50 | p95 |
|---|---|---|---|---|---|---|---|---|
| all | 92 | 90.2% | 0.0% | 90.2% | 9.8% | 9.8% | n/a | n/a |
| clear | 69 | 94.2% | 0.0% | 94.2% | 5.8% | 5.8% | n/a | n/a |
| glare+blur | 23 | 78.3% | 0.0% | 78.3% | 21.7% | 21.7% | n/a | n/a |

API cost per 100 scans: n/a (no measured per-call cost in this source)

## Paths

- CONFIRMED: 83
- NEEDS_API: 9

## Fitted weights

bias -0.634; brandSim 0.636; margin 0.144; compSeen 0.170; compAgree 0.248; compConflict -0.214; strengthAgree 0.125; variantAgree 0.143; mfrFound 0.581; ocrConf 0.314; lineWeight 0.321

## Per photo

| file | cond | expected | path | predicted | score(LOO) |
|---|---|---|---|---|---|
| p000.jpg | clear | Mahacef-CV 200 Tablet | CONFIRMED ✓ | Mahacef 200 Tablet | 0.812 |
| p001.jpg | clear | Amoxyclav 625 Tablet | CONFIRMED ✓ | Amoxyclav 625 Tablet | 0.860 |
| p002.jpg | clear | Ampoxin 250 Capsule | CONFIRMED ✓ | Ampoxin 500 Capsule | 0.655 |
| p003.jpg | clear | Zocon-T Kit | CONFIRMED ✓ | Zocon-T Kit | 0.723 |
| p004.jpg | clear | Zatura 250 Tablet | CONFIRMED ✓ | Zatura 250 Tablet | 0.850 |
| p005.jpg | clear | Moxikind-CV 375 Tablet | CONFIRMED ✓ | Moxikind-CV 375 Tablet | 0.765 |
| p006.jpg | clear | Azikem 500mg Tablet | CONFIRMED ✓ | Azikem 500mg Tablet | 0.857 |
| p007.jpg | clear | Zifi 50 DT Tablet | CONFIRMED ✓ | Zifi 50 Dry Syrup | 0.622 |
| p008.jpg | clear | Rifaxigyl 550mg Tablet | CONFIRMED ✓ | Rifaxigyl 550mg Tablet | 0.860 |
| p009.jpg | clear | Loxof 750mg Tablet | CONFIRMED ✓ | Levofloxacin 750mg Tablet | 0.786 |
| p010.jpg | clear | Metrogyl 200 Tablet | NEEDS_API | – | 0.383 |
| p011.jpg | clear | Oflox TZ Tablet | CONFIRMED ✓ | Oflox TZ Tablet | 0.755 |
| p012.jpg | clear | Gudcef 100mg Tablet DT | CONFIRMED ✓ | Gudcef 100mg Tablet DT | 0.765 |
| p013.jpg | clear | Zipod 200mg Tablet | CONFIRMED ✓ | Zipod 200mg Tablet | 0.700 |
| p014.jpg | clear | Levomac 750 Tablet | CONFIRMED ✓ | Levomac 750 Tablet | 0.678 |
| p015.jpg | clear | Oflomac TZ 200 mg/600 mg Tablet | NEEDS_API | Ofomak 200mg Tablet | 0.669 |
| p016.jpg | clear | Metrogyl Compound Plus  Tablet | CONFIRMED ✓ | Metrogyl Compound Plus  Tablet | 0.722 |
| p017.jpg | clear | Nifty-SR Tablet | CONFIRMED ✓ | Nifty-SR Tablet | 0.642 |
| p018.jpg | clear | Grandcef-CV Tablet | CONFIRMED ✓ | Grandcef-CV Tablet | 0.760 |
| p019.jpg | clear | Taxim-O 400 Tablet | CONFIRMED ✓ | Taxim-O 400 Tablet | 0.790 |
| p020.jpg | clear | Norflox 200 Tablet | CONFIRMED ✓ | Norflox 200 Tablet | 0.671 |
| p021.jpg | clear | Kelvin 500mg Tablet | CONFIRMED ✓ | Ciproflox 500mg Tablet | 0.752 |
| p022.jpg | clear | Albimol 650mg Tablet | CONFIRMED ✓ | Albimol 650mg Tablet | 0.806 |
| p023.jpg | clear | Xtpara Tablet SR | NEEDS_API | Perin 5% Cream | 0.726 |
| p024.jpg | clear | Malidens 650 Tablet | CONFIRMED ✓ | Malidens 650 Tablet | 0.784 |
| p025.jpg | clear | Pyrigesic 650 Tablet | CONFIRMED ✓ | Pyrigesic 650 Tablet | 0.698 |
| p026.jpg | clear | Algina 650 Tablet | CONFIRMED ✓ | Algine 10mg Tablet | 0.686 |
| p027.jpg | clear | Parawel 650mg Tablet | CONFIRMED ✓ | Parawel 650mg Tablet | 0.869 |
| p028.jpg | clear | Pacimol 650 Tablet | CONFIRMED ✓ | Pacimol 650 Tablet | 0.765 |
| p029.jpg | clear | Zisper Forte Tablet | CONFIRMED ✓ | StayHappi Risperidone+Trihexyphenidyl 3mg/2mg Tablet | 0.686 |
| p030.jpg | clear | Neuroprime-PG Capsule | CONFIRMED ✓ | Neuroprime-PG Capsule | 0.845 |
| p031.jpg | clear | Zoryl-M 1 Tablet PR | CONFIRMED ✓ | Zoryl 2 Tablet | 0.722 |
| p032.jpg | clear | Winbp 20 H Tablet | CONFIRMED ✓ | Winbp 20 Tablet | 0.682 |
| p033.jpg | clear | Glucobay M50 Tablet | CONFIRMED ✓ | Glucobay 50 Tablet | 0.875 |
| p034.jpg | clear | Laformin GV 2mg/500mg/0.2mg Tablet ER | NEEDS_API | Laformin-G 2 Tablet PR | 0.859 |
| p035.jpg | clear | Intaglip-M Tablet | CONFIRMED ✓ | Intaglip-M Tablet | 0.797 |
| p036.jpg | clear | Aldigesic-Rab Capsule SR | CONFIRMED ✓ | Aldigesic-Rab Capsule SR | 0.821 |
| p037.jpg | clear | Pregalin X 75 Capsule | CONFIRMED ✓ | Pregabolin 75mg Tablet | 0.805 |
| p038.jpg | clear | Etoshine MR Tablet | CONFIRMED ✓ | Etoshine MR Tablet | 0.711 |
| p039.jpg | clear | Olmat MT 50 Tablet ER | CONFIRMED ✓ | Olmat MT 50 Tablet ER | 0.790 |
| p040.jpg | clear | Montina-L Tablet | CONFIRMED ✓ | Montina 10 Tablet | 0.702 |
| p041.jpg | clear | Arvast CV 10 Capsule | CONFIRMED ✓ | Arvast CV 10 Capsule | 0.829 |
| p042.jpg | clear | Zosa-L Capsule PR | CONFIRMED ✓ | Zosa-L Capsule PR | 0.646 |
| p043.jpg | clear | Levorid D Tablet | CONFIRMED ✓ | Levorid D Tablet | 0.849 |
| p044.jpg | clear | Lupin Acp 100mg/325mg Tablet | CONFIRMED ✓ | Lupin Acp 100mg/325mg Tablet | 0.813 |
| p045.jpg | clear | Zenflox-UTI Tablet | CONFIRMED ✓ | Flavorate 200mg Tablet | 0.766 |
| p046.jpg | clear | Norflokem TZ Tablet | CONFIRMED ✓ | Tinizole Infusion | 0.759 |
| p047.jpg | clear | Ketoflam-P Tablet | CONFIRMED ✓ | Lupin 10 Rheza Tablet | 0.785 |
| p048.jpg | clear | Walaphage 500 Tablet | CONFIRMED ✓ | Walaphage 500 Tablet | 0.667 |
| p049.jpg | clear | Tazloc 80 Tablet | CONFIRMED ✓ | Tazloc 80 Tablet | 0.757 |
| p050.jpg | clear | Monit 30 SR Tablet | CONFIRMED ✓ | Monit 30 SR Tablet | 0.857 |
| p051.jpg | clear | Hizet 10mg Tablet | CONFIRMED ✓ | Hizet 10mg Tablet | 0.692 |
| p052.jpg | clear | Gestofit 200 Capsule | CONFIRMED ✓ | Gestofit 200 Capsule | 0.865 |
| p053.jpg | clear | Goodova-L 5mg Tablet | CONFIRMED ✓ | Goodova-L 5mg Tablet | 0.749 |
| p054.jpg | clear | Goodflo 150mg Capsule | CONFIRMED ✓ | Goodflo 150mg Capsule | 0.782 |
| p055.jpg | clear | Vorifast 200 Tablet | CONFIRMED ✓ | Vorifast 200 Tablet | 0.799 |
| p056.jpg | clear | Desirox 250mg Tablet | CONFIRMED ✓ | Desirox 250mg Tablet | 0.804 |
| p057.jpg | clear | Bilashine Tablet | CONFIRMED ✓ | Bilashine Tablet | 0.707 |
| p058.jpg | clear | Atorva 5 Tablet | CONFIRMED ✓ | Atorva 5 Tablet | 0.840 |
| p059.jpg | clear | Lcfex Tablet | CONFIRMED ✓ | Lcfex Tablet | 0.735 |
| p060.jpg | clear | Nexito 5 Tablet | CONFIRMED ✓ | Nexito 10 Tablet | 0.757 |
| p061.jpg | clear | Normabrain 800mg Tablet | CONFIRMED ✓ | Normabrain 800mg Tablet | 0.802 |
| p062.jpg | clear | Nimica 100 DT Tablet | CONFIRMED ✓ | Nimesulide Suspension | 0.773 |
| p063.jpg | clear | Akubliss Tablet | CONFIRMED ✓ | Akubliss Tablet | 0.741 |
| p064.jpg | clear | Lopamide Tablet | CONFIRMED ✓ | Lopramide 2mg Tablet | 0.781 |
| p065.jpg | clear | Valrate CR 300 Tablet | CONFIRMED ✓ | Valrate CR 300 Tablet | 0.837 |
| p066.jpg | clear | Amide 100 Tablet | CONFIRMED ✓ | Amide 100 Tablet | 0.860 |
| p067.jpg | clear | Rosuvas 5 Tablet | CONFIRMED ✓ | Rosuvas 10mg Tablet | 0.691 |
| p068.jpg | clear | H-Quine Tablet | CONFIRMED ✓ | H-Quine Tablet | 0.833 |
| p041_glare.jpg | glare | Arvast CV 10 Capsule | CONFIRMED ✓ | Arvast CV 10 Capsule | 0.828 |
| p043_blur.jpg | blur | Levorid D Tablet | NEEDS_API | – | 0.383 |
| p061_glare.jpg | glare | Normabrain 800mg Tablet | CONFIRMED ✓ | Lango 10mg Tablet | 0.624 |
| p030_blur.jpg | blur | Neuroprime-PG Capsule | CONFIRMED ✓ | Neuroprime-PG Capsule | 0.649 |
| p052_glare.jpg | glare | Gestofit 200 Capsule | CONFIRMED ✓ | Gestofit 200 Capsule | 0.849 |
| p000_blur.jpg | blur | Mahacef-CV 200 Tablet | NEEDS_API | – | 0.383 |
| p051_glare.jpg | glare | Hizet 10mg Tablet | CONFIRMED ✓ | Alzero M Tablet | 0.627 |
| p012_blur.jpg | blur | Gudcef 100mg Tablet DT | CONFIRMED ✓ | Gudcef 100mg Tablet DT | 0.697 |
| p066_glare.jpg | glare | Amide 100 Tablet | CONFIRMED ✓ | Amide 100 Tablet | 0.859 |
| p027_blur.jpg | blur | Parawel 650mg Tablet | NEEDS_API | – | 0.383 |
| p026_glare.jpg | glare | Algina 650 Tablet | CONFIRMED ✓ | Algina 650 Tablet | 0.801 |
| p034_blur.jpg | blur | Laformin GV 2mg/500mg/0.2mg Tablet ER | CONFIRMED ✓ | Volibose Tablet | 0.755 |
| p024_glare.jpg | glare | Malidens 650 Tablet | CONFIRMED ✓ | Malidens 650 Tablet | 0.680 |
| p004_blur.jpg | blur | Zatura 250 Tablet | NEEDS_API | Dam Gel | 0.627 |
| p035_glare.jpg | glare | Intaglip-M Tablet | CONFIRMED ✓ | Intaglip-M Tablet | 0.816 |
| p054_blur.jpg | blur | Goodflo 150mg Capsule | CONFIRMED ✓ | Goodflo 150mg Capsule | 0.779 |
| p021_glare.jpg | glare | Kelvin 500mg Tablet | CONFIRMED ✓ | Ciproflox 500mg Tablet | 0.753 |
| p060_blur.jpg | blur | Nexito 5 Tablet | CONFIRMED ✓ | Nexito 5 Tablet | 0.769 |
| p056_glare.jpg | glare | Desirox 250mg Tablet | CONFIRMED ✓ | Desirox 250mg Tablet | 0.805 |
| p003_blur.jpg | blur | Zocon-T Kit | NEEDS_API | Zocon Ointment | 0.686 |
| p018_glare.jpg | glare | Grandcef-CV Tablet | CONFIRMED ✓ | Grandcef-CV Tablet | 0.639 |
| p011_blur.jpg | blur | Oflox TZ Tablet | CONFIRMED ✓ | Oflox TZ Tablet | 0.643 |
| p007_glare.jpg | glare | Zifi 50 DT Tablet | CONFIRMED ✓ | Zifi 50 Dry Syrup | 0.650 |
