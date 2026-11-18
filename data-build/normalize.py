"""Text normalisation shared by the DB build. core/src/main/kotlin/.../Normalize.kt mirrors this exactly;
build_db.py exports test vectors (out/key_vectors.tsv) that the Kotlin unit tests replay, so the two can't drift."""
import re

# Descriptors that never change the active moiety; always dropped (unless nothing else is left).
DESCRIPTORS = set("""anhydrous monohydrate dihydrate trihydrate hemihydrate sesquihydrate pentahydrate heptahydrate hydrate
dried elemental light heavy micronized micronised ip bp usp ep nf""".split())
# Counter-ions / ester groups that name the salt form, not the drug (diclofenac sodium == diclofenac).
SALTS = set("""hydrochloride hcl hydrobromide hbr sodium potassium maleate sulphate sulfate phosphate acetate citrate
tartrate bitartrate mesylate mesilate besylate besilate succinate fumarate propionate dipropionate valerate butyrate
furoate nitrate bromide gluconate lactate malate pamoate embonate stearate palmitate estolate ethylsuccinate axetil
proxetil pivoxil cilexetil medoxomil disodium dihydrochloride bisulphate bisulfate hyclate oxalate napsylate tosylate
xinafoate decanoate magnesium calcium benzoate tromethamine trometamol""".split())
# If only these remain after removing salt words, the salt IS the drug (sodium chloride, ferrous sulphate, ...): keep all.
IONS = set("""chloride carbonate bicarbonate hydroxide oxide citrate sulphate sulfate phosphate gluconate lactate acetate
iodide fluoride picosulfate picosulphate trisilicate silicate stearate zinc ferrous ferric sodium potassium calcium magnesium
aluminium aluminum ammonium lithium silver copper selenium chromium manganese bismuth""".split())
ESTER_PREFIXES = {"methyl", "ethyl", "benzyl", "propyl", "butyl", "isopropyl"}


def _words(s: str) -> list[str]:
    s = s.lower().replace("µ", "u")
    s = re.sub(r"([a-z]{3,}ic)(acid)\b", r"\1 \2", s)  # 'ursodeoxycholicacid' -> 'ursodeoxycholic acid'
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return s.split()


def _phonetic(w: str) -> str:
    if w.isdigit():
        return w
    w = w.replace("ph", "f").replace("ae", "e").replace("oe", "e").replace("th", "t").replace("y", "i")
    w = re.sub(r"(.)\1+", r"\1", w)  # collapse doubled letters
    if len(w) > 4 and w.endswith("e"):
        w = w[:-1]
    return w


def ingredient_key(name: str) -> str:
    """Spelling-insensitive key for an ingredient name. Amoxycillin Trihydrate IP -> amoxicilin."""
    words = _words(name)
    words = [w for w in words if w not in DESCRIPTORS] or words
    stem = [w for w in words if w not in SALTS]
    if stem and not all(w in IONS for w in stem) and not (len(stem) == 1 and stem[0] in ESTER_PREFIXES):
        words = stem
    out = []
    i = 0
    while i < len(words):
        w = words[i]
        if i + 1 < len(words) and words[i + 1] == "acid" and w.endswith("ic") and len(w) > 4:
            out.append(w[:-2] + "ate")  # clavulanic acid == clavulanate
            i += 2
            continue
        out.append(w)
        i += 1
    return " ".join(_phonetic(w) for w in out)


FORM_WORDS = set("""tablet tablets tab tabs capsule capsules cap caps syrup suspension injection inj drop drops cream gel
ointment lotion solution spray inhaler respules respule rotacap rotacaps powder sachet sachets granules soap shampoo kit
lozenge lozenges infusion oral dry eye ear nasal liquid emulsion paste serum mouthwash gargle jelly elixir linctus softgel
softgels transcaps redimix vial ampoule bottle strip sugar free flavour flavor""".split())


def name_tokens(s: str) -> list[str]:
    s = s.lower().replace("&", " ")
    s = re.sub(r"(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])", " ", s)  # pan40 -> pan 40
    s = re.sub(r"[^a-z0-9.]+", " ", s)
    s = re.sub(r"(?<![0-9])\.|\.(?![0-9])", " ", s)  # keep decimal points only
    return [t for t in s.split() if t]


def brand_keys(product_name: str) -> tuple[str, str]:
    """(name_key, base_key). 'Augmentin 625 Duo Tablet' -> ('augmentin 625 duo', 'augmentin')."""
    toks = name_tokens(product_name)
    cut = len(toks)
    for i, t in enumerate(toks):
        if i > 0 and t in FORM_WORDS:
            cut = i
            break
    units = ("mg", "mcg", "gm", "g", "ml", "iu", "w", "v")
    toks = toks[:cut]
    # drop a unit only right after a number ('500 mg'); 'Laformin-G' keeps its 'g'
    toks = [t for i, t in enumerate(toks) if not (t in units and i > 0 and toks[i - 1][0].isdigit())]
    base = toks
    for i, t in enumerate(toks):
        if i > 0 and any(c.isdigit() for c in t):
            base = toks[:i]
            break
    return " ".join(toks), " ".join(base)


_UNIT = {"mg": "mg", "mcg": "mcg", "ug": "mcg", "g": "g", "gm": "g", "iu": "IU", "ml": "ml", "%": "%"}
STRENGTH_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(mg|mcg|ug|µg|gm|g|iu|ml|%)(?![a-z])", re.I)


def parse_strength(s: str):
    """'500mg' -> (500.0, 'mg'); '15mg/5ml' -> (15.0, 'mg/5ml'); '1% w/w' -> (1.0, '%'). None if not parseable."""
    s = (s or "").strip().lower().replace("µg", "mcg")
    m = STRENGTH_RE.search(s)
    if not m:
        return None
    val, unit = float(m.group(1)), _UNIT.get(m.group(2).lower(), m.group(2).lower())
    if unit == "g":
        val, unit = val * 1000.0, "mg"
    rest = s[m.end():]
    per = re.match(r"\s*(?:/|per)\s*(\d+(?:\.\d+)?)?\s*(ml|g|gm)\b", rest)
    if per:
        unit = f"{unit}/{per.group(1) or '1'}{_UNIT.get(per.group(2), per.group(2))}"
    return round(val, 4), unit
