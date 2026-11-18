# AMRit

**India's antibiotic radar, powered by people scanning their medicines.** BioHack 1.0 · Health Tech · World AMR Awareness Week (18–24 Nov).

Point your phone at a medicine strip. AMRit tells you **what it is for**, in your language, and checks four things:

- **Banned in India:** the combination matches a CDSCO prohibition (2023, 2024, 2026 notifications), including its dosage form and strength.
- **Antibiotic:** if it is one, AMRit shows the WHO AWaRe group (Access / Watch / Reserve) and offers a course tracker.
- **Double dose and interactions:** the same ingredient in two medicines you take, and DDInter drug–drug interactions.
- **Cheaper generic:** a matching Jan Aushadhi product with the same ingredients, strength and form, and how much you save per unit.

Beyond one strip, AMRit does what a chatbot looking at one photo cannot:

- **Check my medicine box.** Scan every strip in the house, one after another, and get one report: what to **throw away** (expired, a batch CDSCO reported as fake or substandard, a banned combination), what to **ask your doctor about** (double doses, interactions, leftover antibiotics), and what's fine.
- **Antibiotic passport.** Every antibiotic course each family member took, with its WHO group, whether it was prescribed and whether it was finished, plus an English card to show the doctor. It stays on the phone.
- **What ICMR found.** On an antibiotic's result, the 2024 resistance figures from ICMR's national surveillance report for exactly that drug. For example, only 1.3% of typhoid isolates still responded to ciprofloxacin. All 28 figures are checked against the report PDF by `data-build/verify_amrsn.py`.
- **Antibiotics near you.** Live, k-anonymous totals from everyone's scans: the Watch/Reserve share, the share bought without a prescription, and your 3-digit area.
- **Same medicine, other brands.** After a scan: the cheapest brands with exactly the same salt, strength, dosage form and release type (Dolo 650 has 503), with the saving per tablet, and where to buy near you. Map search uses your PIN code, and there's a link to PMBI's official Jan Aushadhi Kendra finder.
- **Pharmacy bill check.** Photograph a printed bill to see the saving at Jan Aushadhi and a safety check across everything on it. The photo is deleted as soon as it's read.

Every scan also sends **one anonymous event**: ingredients, AWaRe group, banned flag, pincode and hour. These events feed a public map of antibiotic use.

> **The AI reads; the database decides.** The phone's OCR, and the vision model on hard strips, only *transcribe* text. Every alert comes from curated data and fixed rules. If the evidence isn't conclusive, the app asks the user or says *"not found, ask your pharmacist"*. It never guesses.

## How a scan works

```
camera stream ──► ML Kit OCR (on phone, offline, ~3 frames/s, text pooled across frames)
                      │
                      ▼
          matcher (core/, on phone) ── brand index (180k names) + composition reader (2.9k ingredients, all spellings)
                      │
          evidence rules: accept only if the full brand matches AND something independent agrees
          (printed ingredients, the product's number, strength, maker) ─ or the printed composition alone is clear
                      │
     ┌────────────────┼──────────────────────────┐
  ACCEPT           CONFIRM                    FALLBACK
  (no API)     user taps 1 of ≤3           backend proxy ─► shared cache ─► TWO vision readers in parallel
               real candidates                                              (Gemma 4 31B + Gemini 2.5 Flash-Lite,
               (no API)                                                     strict JSON: brand, form, ingredients[])
                                                                            phone accepts only what they agree on
                      │                                  │
                      └──────────────► same matcher + same rules ◄──────────┘
                                             │
               result card: what it's for · verdict · ingredients · alerts · Jan Aushadhi · voice (10 languages)
                                             │
                     anonymous event ─► Room queue ─► WorkManager sync ─► backend ─► dashboard
```

Hard rules that stop wrong answers, each with a regression test from a real failure:

- A strip that says *Tablets* is never resolved to an injection, syrup or cream.
- A strip printing *Vitamin E 200 mg* is never resolved to a 400 mg product. The strength is decided by a majority vote across every reading, so an OCR misread is outvoted.
- A product whose own number (Zevion **400**) appears nowhere on a strip that does print numbers is excluded.
- A candidate that shares no ingredient with a clearly printed composition is never offered.
- A composition-based answer is never shown if any ingredient the model read could not be mapped. Otherwise an ingredient would silently drop out.
- **Two independent AI readers are cross-checked.** A reading that contradicts itself (the same ingredient listed twice) is discarded. If the two readers disagree on a strength, it is shown as "strength unclear", never guessed. If they disagree on the ingredients, the user is asked.
- **Brands missing from the database** (e.g. many supplements such as *Evion 200*) resolve from their printed composition and are labelled "read from the strip". After the fallback reads such a brand and two different phones agree, it becomes a **learned product** that every phone resolves offline.

## Evaluation

Measured **on the phone** (Motorola Edge 60 Fusion): real ML Kit OCR, real latency, real calls to both vision readers through the backend. Reproduce with `bash eval/run_on_device.sh api`. The per-photo report is `eval/out/device/report.md`.

Test set: 116 labelled photos.

- **69 clear** product photos of Indian brands.
- **23 glare/blur** variants (synthetic degradation of clear photos).
- **24 products not in the database at all.** The correct answer for these is "not found", or the right ingredients read from the printed composition.

One label was corrected after inspection: the source dataset attaches a ciprofloxacin *Kelvin* box photo to a paracetamol *Kelvin* row.

| Set | n | Brand→ingredient accuracy | Wrong answers | Zero API calls | Hit the API | p50 / p95 latency |
|---|---|---|---|---|---|---|
| all | 116 | **99.1%** | **0** | 75.9% | 24.1% | 111 ms / 3.7 s |
| clear | 69 | 98.6% | 0 | 88.4% | 11.6% | 96 ms / 2.7 s |
| glare + blur | 23 | 100% | 0 | 73.9% | 26.1% | 153 ms / 4.1 s |
| not in DB | 24 | 100% (refused or right ingredients) | 0 | 41.7% | 58.3% | 264 ms / 2.4 s |

**The first phone run found 4 wrong answers** that the desktop replay had missed. All 4 came from rule gaps, now closed, and each has a regression test (`deviceRegressionsNeverAcceptWrongSibling`):

- **Sibling variants:** "Amlopres **Trio**" was taken as Amlopres 10, "Silofast **D**8" as Silofast 8 and "LAFORMIN**GV**2" as Laformin-G 2. A variant printed right after the brand that the product's name lacks now means "ask", never "accept".
- **Maker as brand:** "Cipla" on a Desirox strip was taken as the product *Cipla Inhaler*. A brand that is only the maker's name can't corroborate itself, and inhalers now have their own dosage-form group.
- **Namesake:** the numbers above are re-scored on the phone's own OCR. Fixing the first two exposed one more case. *Kelvin* in the database is paracetamol, while the strip prints ciprofloxacin 500 mg. When a product contains none of the drugs printed on the strip, a matching number no longer counts as evidence.

The same rules replayed on desktop OCR (RapidOCR): 98.3% accuracy, 0 wrong.

- **API cost:** $0.005 per 100 scans (under a paisa per scan on average). That is 28 fallback scans in 116 on the phone, each read by two models, at a measured $0.00019 per fallback (OpenRouter's reported usage). Cost falls further as the shared cache and learned products grow.
- **Choice of readers:** two benchmarks.
  - Brand legibility on the 8 hardest photos (`eval/bench_models.py`): Gemma 4 26B and Gemini 2.5 Flash-Lite both 8/8; Gemma 3 27B 6/8; GLM 5.3 Flash 4/8; GPT-5 nano and Qwen 3.5 Flash returned no valid JSON.
  - Full composition on a real 10-ingredient strip (Becosules Z, photographed on the Moto), scored per ingredient + strength (`eval/bench_composition.py`), two runs each:

    | Model | Run 1 | Run 2 |
    |---|---|---|
    | Gemma 4 31B | 10/10 | 10/10 |
    | Gemini 2.5 Flash-Lite | 10/10 | 10/10 |
    | Gemini 3.1 Flash-Lite | 10/10 | 10/10 |
    | Claude Haiku 5.5 | 10/10 | 10/10 |
    | Gemma 4 26B | 10/10 | 9/10 |
    | Gemini 2.5 Flash | 9/10 | 9/10 |
    | Qwen3-VL 235B | 9/10 | 9/10 |

    The two cheapest perfect readers from different model families were chosen: Gemma 4 31B and Gemini 2.5 Flash-Lite. Gemma 4 26B is the spare when one is rate-limited.
- **Caveat:** the photos are catalogue and pack photos, not yet foil strips photographed in homes. Capture mode (hidden stats screen: long-press the Settings title) collects real-strip frames to replace them.
- **On the Moto Edge 60 Fusion:** cold start 0.66 s; OCR 360 ms per frame.

## Data

`bash data-build/fetch_data.sh && python -I data-build/build_db.py` builds `app/src/main/assets/amrit_ref.db` (22 MB, 7.5 MB compressed in the APK). The build is reproducible, and the SHA-256 of every input is recorded in `data-build/out/manifest.json`.

| Source | Used for |
|---|---|
| Indian Medicine Dataset (254k brands, MIT) + 11.8k-brand full-composition set | brand → ingredients; **what it's for** (480 use phrases covering 87% of products by exact ingredient set) |
| WHO AWaRe 2025 (CC BY-NC-SA 3.0 IGO) | Access/Watch/Reserve by route; WHO-classified combinations; WHO "not recommended" antibiotic FDCs |
| CDSCO prohibited FDC notifications 2023/2024/2026 | banned combinations, with form- and strength-specific matching |
| DDInter | 43,783 interacting pairs (major/moderate/minor) |
| Jan Aushadhi (PIB list Dec 2025 + PMBI MRP list Apr 2025) | 1,638 generics with prices |
| ICMR AMRSN Annual Report 2024 | 28 susceptibility figures (E. coli from urine, *S.* Typhi from blood, *S. aureus*), table `resistance`, each verified against the PDF |
| CDSCO drug alerts (NSQ / spurious), refreshed daily | 5,307 batch records; a batch is flagged only when batch *and* product match |

Known limits and how each is handled are listed in [data-build/README.md](data-build/README.md). The main ones:

- The 254k dataset truncates compositions to two ingredients. The app adds further ingredients it reads from the strip.
- Many over-the-counter supplements are missing from the dataset. These are handled by strip-read results and learned products.
- Translations into the nine Indian languages are machine-generated at build time (`data-build/translate.py`), committed, and **labelled "review pending" in the app** until a native speaker has checked them.

## Privacy

- No account, no user id and no device id.
- Photos stay on the phone, unless a strip is too hard to read. In that case a cropped, downscaled text region goes to our proxy and on to the model. It is not stored.
- IPs are used only as a salted hash that rotates daily, for rate limiting.
- The antibiotic passport, medicine box and family profiles never leave the phone unless the user shares them.
- A bill photo is read on the phone and deleted immediately. Demo-mode scans never send events.
- Events contain ingredients, AWaRe group, banned flag, an optional 6-digit pincode and the hour (UTC, truncated). Nothing else.
- The OpenRouter key lives only in `backend/.env`. It is never in the app.

## Run it

```bash
# backend (FastAPI): vision proxy + cache + rate limit + daily spend cap + events + learned products
cd backend && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
cp .env.example .env        # put OPENROUTER_API_KEY=... in it
.venv/Scripts/python -m uvicorn app:app --host 0.0.0.0 --port 8000

# app (needs JDK 17 + Android SDK; see env.sh)
./gradlew :core:test        # matcher + rules + i18n regression tests
./gradlew :app:installRelease
adb reverse tcp:8000 tcp:8000   # phone reaches the laptop backend over USB (or set the URL in Settings)
```

**Dashboard feed:** `GET /v1/events/export?since=<iso>` returns anonymous events. Alternatively, set `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` in `.env` to mirror every event into a Supabase `scan_events` table with Realtime. The schema is in `backend/schema.sql`.

**Screens without a phone:** `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'` renders every screen (real DB, real rules) in light/dark and several languages to `app/screenshots/` (Robolectric + Roborazzi).

**Hosted backend:** a Supabase Edge Function (`supabase/functions/api/`, free plan, Mumbai) with the same API as the FastAPI server. It holds the OpenRouter key as a secret and keeps its tables in a schema that Supabase's public REST API does not expose; the export needs a token. Setup is in [backend/DEPLOY.md](backend/DEPLOY.md). The FastAPI server in `backend/` stays for local development and the on-device eval. Enter the URL under Settings → Advanced → Backend URL.

**Demo safety net:** Settings → Demo mode. The demo strips resolve from saved results with no network, and they can also be picked from a list.

## Layout

| Path | Contents |
|---|---|
| `core/` | Pure Kotlin: normaliser, brand index, composition reader, evidence rules, rule engine, i18n templates. The app and the eval harness run the same code. |
| `app/` | Compose UI, CameraX live scanner, ML Kit, Room, WorkManager, TTS |
| `backend/` | FastAPI proxy and event store |
| `data-build/` | Dataset fetch, DB build, translations |
| `eval/` | Harness, on-device runner, model benchmark |

*Information only, not medical advice. Every alert points the user to a pharmacist or doctor.*
