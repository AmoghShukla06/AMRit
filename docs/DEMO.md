# AMRit demo script (BioHack 1.0, about 4 minutes)

**The line to land:** ChatGPT sees one photo. AMRit remembers your family's medicines, checks government lists offline, and turns every scan into India's antibiotic map.

## Before the judges arrive (10 minutes)

1. **Phone:** charge it, set brightness high, turn Do Not Disturb off, and unlock it.
2. **Demo mode on:** Settings → long-press the "Settings" title → Developer → Demo mode on.
   - Demo scans run the real pipeline but never reach the surveillance data.
   - Turn Demo mode **off** for step 4, so the judge's real scan counts.
3. **Print** (A4/A5, 100%):
   - `docs/demo_labels.html`: six labels, cut out.
   - `docs/demo_bill.html`: one sample bill.
   - Every printed sheet says DEMO.
4. **Medicine box:** put these in a small box or pouch:
   - the Dolo, Crocin, Combiflam-expired, Telma-AM-batch and DEMO-ban labels;
   - the Azithral label, or a real Azithral strip;
   - a real antibiotic strip for step 4 (any ciprofloxacin or azithromycin brand).
5. **Clean start:** open Medicines and remove any old entries.
6. **Passport history:** start one Azithral course from a result (Track course), answering **No** to the prescription question.
7. **Live map:** check "Antibiotics near you" loads on Home (it needs the internet). Leave a laptop tab open on the dashboard as a backup.

## The script

| # | Do | Say | They see |
|---|----|-----|----------|
| 1 | Home → **Check my medicine box**. Show the strips one by one; each beeps as it's added. Tap **See report**. | "Every Indian home has a box like this. Let's check it in 30 seconds." | **Throw away:** Combiflam expired, Telma-AM fake batch (CDSCO), Nixar banned. **Ask your doctor:** Dolo + Crocin double paracetamol, leftover Azithral. |
| 2 | Tap **Antibiotic passport** under the leftover. Then **Show to doctor**. | "Doctors always ask: what antibiotics have you taken? Nobody remembers. This does, and it never leaves the phone unless you share it." | The timeline with WHO groups, "Without a prescription", the prompts card, and the English doctor card in the share sheet. |
| 3 | Scan the real **ciprofloxacin** strip (or search "Ciplox"). Scroll to the ICMR card. | "This is what ICMR found in Indian hospitals in 2024: only 1.3% of typhoid bacteria still respond to ciprofloxacin. That's from the government's own report, with the page number." | The **What ICMR found** card: typhoid 1.3%, urine E. coli 22.4%, staph 18.3%. |
| 4 | Demo mode off. A judge scans a real antibiotic strip and answers the prescription question. Then Home → **Antibiotics near you** → refresh. | "Your scan just became part of India's antibiotic map: anonymous, 3-digit areas only." | The antibiotic count goes up; Watch/Reserve share, share bought without a prescription, top antibiotics. |
| 4b | On any scanned result, scroll to **Same medicine, other brands**. | “Same salt, same strength, same form: 503 brands of Dolo 650’s medicine, and the cheapest is 86% less. Then where to buy it near you.” | The cheapest same-salt brands with “% cheaper”, and the Jan Aushadhi / pharmacy map buttons. |
| 5 | Home → **Check a pharmacy bill** → photograph the demo bill. | "And the money: same medicines, Jan Aushadhi prices." | "You could save about ₹…", the double dose and antibiotic flags across the bill, and **Find a Jan Aushadhi Kendra**. |
| 6 | Settings → Reading → **Larger text** and **Read results aloud**; switch to **हिन्दी**. | "Built for our parents: big text, read aloud, ten Indian languages." | The same screens, larger, in Hindi. |

## What makes this different (for Q&A)

- **Memory over time.** The medicine box, family profiles, course tracking and the antibiotic passport are a household's history, kept on the phone. A chatbot starts from zero with every photo.
- **Official data, checked offline.** No AI is involved in any safety answer. The sources:
  - CDSCO banned combinations (187);
  - CDSCO substandard and fake batch alerts (5,307, refreshed daily);
  - WHO AWaRe 2025;
  - DDInter interactions (43,783 pairs);
  - Jan Aushadhi prices (1,638 generics);
  - ICMR AMRSN 2024 resistance figures (28 rows, each checked against the PDF by `data-build/verify_amrsn.py`).
- **Every scan is surveillance.** Anonymous events (medicine group, 3-digit area, hour) feed "Antibiotics near you". There is little ground-level data on antibiotics sold without a prescription in India; this collects it as a side effect of a useful app.
- **Accuracy:** 99.1% brand-to-ingredient accuracy and 0 wrong answers on 116 evaluation images replayed on the phone. With no confident match, the app says "not found, ask your pharmacist" and never guesses.
- **Privacy:** no accounts, no photos stored (the bill photo is deleted as soon as it's read), and no personal data in events.

## If something goes wrong

- **A label won't read:** use the DEMO row under the shutter; tapping a label runs the same pipeline (in box mode too).
- **No internet for step 4:** "Antibiotics near you" shows the last saved snapshot with its date. Say so, and show the laptop dashboard.
- **Bill photo fails:** use **Choose a photo** and pick `demo_bill_photo.jpg` (already in the phone's Pictures; a copy is in `docs/`). Verified on the Moto: 4 medicines, quantities and amounts read, ₹239 saving.
- **Phone dies:** the backup APK is `app/build/outputs/apk/debug/app-debug.apk`; every screen is in `app/screenshots/`.
