# AMRit reference DB build report

- products: 253,774 (overlay fixed 1,175 truncated compositions)
- compositions: 11,914
- ingredients: 2,865 (237 WHO antibiotics)
- interactions kept: 43,783
- banned FDC entries: 187
- Jan Aushadhi products: 1638
- DB size: 23.8 MB
- build time: 93s


## Key collisions among WHO/DDInter names (review)

- benzilpenicilin: ['benzylpenicillin', 'benzylpenicillin (potassium)', 'benzylpenicillin (sodium)']
- cefalexin: ['cefalexin', 'cephalexin']
- cefditoren: ['cefditoren', 'cefditoren-pivoxil']
- cefpodoxim: ['cefpodoxime', 'cefpodoxime-proxetil']
- ceftarolin fosamil: ['ceftaroline fosamil', 'ceftaroline-fosamil']
- nalidixat: ['nalidixic acid', 'nalidixic-acid']
- polimixin b: ['polymyxin b', 'polymyxin-b']
- hidrocortison: ['hydrocortisone', 'hydrocortisone butyrate']
- fenofibrat: ['fenofibrate', 'fenofibric acid']
- estron: ['estrone', 'estrone sulfate']
- levomefolat: ['levomefolic acid', 'levomefolic acid (calcium)']
- edetat: ['edetate calcium disodium anhydrous', 'edetate disodium anhydrous']
- glicerofosfat: ['calcium glycerophosphate', 'sodium glycerophosphate']
- silver topical: ['silver (topical)', 'silver nitrate (topical)']

## CDSCO prohibited FDCs: 187 entries

Component mapping that was not exact (review):

- 'Mentho' -> 'Menthol' (fuzzy 91)
- 'Amylase' -> NEW ingredient, not in any medicine list (none (best fuzzy 75))
- 'Protease' -> NEW ingredient, not in any medicine list (none (best fuzzy 67))
- 'Glucoamylase' -> NEW ingredient, not in any medicine list (none (best fuzzy 76))
- 'Pectinase' -> NEW ingredient, not in any medicine list (none (best fuzzy 86))
- 'Beta-Gluconase' -> NEW ingredient, not in any medicine list (none (best fuzzy 64))
- 'Cellulase' -> NEW ingredient, not in any medicine list (none (best fuzzy 86))
- 'Xylanase' -> NEW ingredient, not in any medicine list (none (best fuzzy 54))
- 'Hemicellulase' -> NEW ingredient, not in any medicine list (none (best fuzzy 70))
- 'Malt diastase' -> NEW ingredient, not in any medicine list (none (best fuzzy 64))
- 'Invertase' -> NEW ingredient, not in any medicine list (none (best fuzzy 70))
- 'Antimony Potassium Tartrate' -> NEW ingredient, not in any medicine list (none (best fuzzy 63))
- 'Sodium Selenite' -> NEW ingredient, not in any medicine list (none (best fuzzy 80))
- 'Bismuth Ammonium Citrate' -> NEW ingredient, not in any medicine list (none (best fuzzy 76))
- 'Fungal Diastase' -> NEW ingredient, not in any medicine list (none (best fuzzy 53))
- 'Nux vomica Tincture' -> NEW ingredient, not in any medicine list (none (best fuzzy 54))
- 'Cardamom Tincture' -> NEW ingredient, not in any medicine list (none (best fuzzy 56))
- 'Casein Hydrolysed' -> NEW ingredient, not in any medicine list (none (best fuzzy 62))
- 'L- Methylfolate' -> 'L-Methyl Folate' (joined)
- 'L-ornithine Laspartate' -> 'L-Ornithine L-Aspartate' (joined)
- 'Acelofenac' -> 'Aceclofenac' (fuzzy 95)
- 'Tincture Ipecacuanha' -> NEW ingredient, not in any medicine list (none (best fuzzy 50))
- 'Tincture Urgenia' -> NEW ingredient, not in any medicine list (none (best fuzzy 65))
- 'Camphorated Opium Tincture' -> NEW ingredient, not in any medicine list (none (best fuzzy 70))
- 'Aromatic Spirit of Ammonia' -> NEW ingredient, not in any medicine list (none (best fuzzy 54))
- 'Weak Ginger tincture' -> NEW ingredient, not in any medicine list (none (best fuzzy 46))
- 'Peppermint Spirit' -> NEW ingredient, not in any medicine list (none (best fuzzy 75))
- 'Compound Cardamom' -> NEW ingredient, not in any medicine list (none (best fuzzy 50))
- 'Tea Tree Oil' -> NEW ingredient, not in any medicine list (none (best fuzzy 57))
- 'Jojoba oil' -> NEW ingredient, not in any medicine list (none (best fuzzy 42))
- 'Starch' -> NEW ingredient, not in any medicine list (none (best fuzzy 50))
- 'Capsaicin' -> NEW ingredient, not in any medicine list (none (best fuzzy 71))
- 'Hydroxyquinone' -> 'Hydroquinone' (fuzzy 92)
- 'Octyl Methoxycinnamate' -> NEW ingredient, not in any medicine list (none (best fuzzy 59))
- 'Oxybenzone' -> NEW ingredient, not in any medicine list (none (best fuzzy 73))
- 'Mulberry' -> NEW ingredient, not in any medicine list (none (best fuzzy 64))
- 'Sodium Ascorbyl Phosphate' -> NEW ingredient, not in any medicine list (none (best fuzzy 75))
- 'Salicyclic acid' -> 'Salicylic Acid' (fuzzy 95)
- 'Primerose Oil' -> NEW ingredient, not in any medicine list (none (best fuzzy 69))
- 'Levetiracitam' -> 'Levetiracetam' (fuzzy 92)
- 'Ginkgo biloba extracts' -> 'Ginkgo Biloba' (prefix 'Ginkgo biloba')
- 'Ginseng Extract' -> 'Ginseng' (prefix 'Ginseng')
- 'extract of Ginkgo Biloba' -> NEW ingredient, not in any medicine list (none (best fuzzy 51))
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Antipyrine' -> NEW ingredient, not in any medicine list (none (best fuzzy 75))
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Flurometholone' -> 'Fluorometholone' (fuzzy 96)
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Methyl Cellulose' -> 'Methylcellulose' (joined)
- 'Hydroxy Methyl Cellulose' -> NEW ingredient, not in any medicine list (none (best fuzzy 83))
- 'Methyl Cellulose' -> 'Methylcellulose' (joined)
- 'HPMC' -> NEW ingredient, not in any medicine list (none (best fuzzy 46))
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Sodium Carboxy Methyl Cellulose' -> 'Carboxymethylcellulose' (joined)
- 'Stabilized Oxychlorocomplex' -> NEW ingredient, not in any medicine list (none (best fuzzy 85))
- 'Sodium Carboxy Methyl Cellulose' -> 'Carboxymethylcellulose' (joined)
- 'Stabilized Oxychloro complex' -> 'Stabilized Oxychlorocomplex' (joined)
- 'Hydroxy Propyl Methyl Cellulose' -> 'Hydroxypropylmethylcellulose' (joined)
- 'Phenyl Ethyl Alcohol' -> NEW ingredient, not in any medicine list (none (best fuzzy 61))
- 'Cetir izine HCl' -> 'Cetirizine' (joined)
- 'Cetir izine HCl' -> 'Cetirizine' (joined)
- 'Levo cetirizine' -> 'Levocetirizine' (joined)
- 'Levo cetirizine' -> 'Levocetirizine' (joined)
- 'Phen ylephrine HCl' -> 'Phenylephrine' (joined)
- 'Levo cetirizine HCl' -> 'Levocetirizine' (joined)
- 'Levo cetirizine HCl' -> 'Levocetirizine' (joined)
- 'Dieth ylcarbamazine Citrate' -> 'Diethylcarbamazine' (joined)
- 'Dieth ylcarbamazine Citrate' -> 'Diethylcarbamazine' (joined)
- 'Amb roxol HCl' -> 'Ambroxol' (joined)
- 'Brom hexine HCl' -> 'Bromhexine' (joined)
- 'Etofy lline' -> NEW ingredient, not in any medicine list (none (best fuzzy 89))
- 'Amb roxol HCl' -> 'Ambroxol' (joined)
- 'Amb roxol HCl' -> 'Ambroxol' (joined)
- 'Cetir izine HCl' -> 'Cetirizine' (joined)
- 'Dextr omethorphan Hydrobromide' -> 'Dextromethorphan Hydrobromide' (joined)
- 'Salbu tamol Sulphate' -> 'Salbutamol' (joined)
- 'Terb utaline Sulphate' -> 'Terbutaline' (joined)
- 'Chlo rpheniramine Maleate' -> 'Chlorpheniramine Maleate' (joined)
- 'P G Sulphonate' -> NEW ingredient, not in any medicine list (none (best fuzzy 65))
- 'Amin ophylline' -> 'Aminophylline' (joined)
- 'Para cetamol' -> 'Paracetamol' (joined)
- 'Phenyl Propanolamine' -> 'Phenylpropanolamine' (joined)
- 'Trithi oparamethoxyphenyl Propene' -> 'Trithioparamethoxy Phenylpropene' (joined)
- 'Acec lofenac' -> 'Aceclofenac' (joined)
- 'Acec lofenac' -> 'Aceclofenac' (joined)
- 'Aden osine triphosphate diphosphate' -> 'Adenosine' (prefix 'Aden osine')
- 'Magnesium Orotate' -> 'Magnesium' (prefix 'Magnesium')
- 'Amo xicillin Trihydrate' -> 'Amoxicillin' (joined)
- 'Cam ylofin Dihydrochloride' -> 'Camylofin' (joined)
- 'Cefix ime' -> NEW ingredient, not in any medicine list (none (best fuzzy 88))
- 'Ceph alexin Monohydrate' -> 'Cefalexin' (joined)
- 'Cetyl Myristoleate' -> NEW ingredient, not in any medicine list (none (best fuzzy 74))
- 'Diace rin' -> NEW ingredient, not in any medicine list (none (best fuzzy 82))
- 'MSM' -> 'Methyl Sulfonyl Methane' (joined)
- 'Para cetamol' -> 'Paracetamol' (joined)
- 'Diclo fenac sodium' -> 'Diclofenac' (joined)
- 'Doxy cycline' -> 'Doxycycline' (joined)
- 'Lactobacillus Rhamnosus' -> 'Lacto bacillus Rhamnosus' (joined)
- 'Lactobacillus Reuteri RC' -> 'Lactobacillus' (prefix 'Lactobacillus')
- 'Doxy cycline HCl' -> 'Doxycycline' (joined)
- 'Betacyclodextrin' -> 'Beta-Cyclodextrin' (joined)
- 'Etod olac' -> 'Etodolac' (joined)
- 'Flupi rtine Maleate' -> 'Flupirtine' (joined)
- 'Gluc osamine sulphate potassium chloride' -> 'Glucosamine Sulfate Potassium Chloride' (joined)
- 'Gluc osamine sulphate potassium chloride' -> 'Glucosamine Sulfate Potassium Chloride' (joined)
- 'Gluc osamine Sulphate' -> 'Glucosamine' (joined)
- 'Gluc osamine sulphate' -> 'Glucosamine' (joined)
- 'Methylsulfonylmethane' -> 'Methyl Sulfonyl Methane' (joined)
- 'Gluc osamine sulphate' -> 'Glucosamine' (joined)
- 'Methyl sufonyl methane' -> 'Methyl Sulfonyl Methane' (fuzzy 97)
- 'Vit E acetate' -> NEW ingredient, not in any medicine list (none (best fuzzy 71))
- 'Gluc osamine Sulphate' -> 'Glucosamine' (joined)
- 'Gluc osamine Sulphate Potassium chloride' -> 'Glucosamine Sulfate Potassium Chloride' (joined)
- 'Gluc osamine Sulphate Potassium chloride' -> 'Glucosamine Sulfate Potassium Chloride' (joined)
- 'Gluc osamine Sulphate Potassium chloride' -> 'Glucosamine Sulfate Potassium Chloride' (joined)
- 'Vit D' -> NEW ingredient, not in any medicine list (none (best fuzzy 80))
- 'Gluc osamine Sulphate Potassium' -> 'Glucosamine' (joined)
- 'Methyl sulphate Sodium' -> NEW ingredient, not in any medicine list (none (best fuzzy 67))
- 'Sulphonyl Methane' -> NEW ingredient, not in any medicine list (none (best fuzzy 64))
- 'Cupric Oxide' -> NEW ingredient, not in any medicine list (none (best fuzzy 62))
- 'Colloidal Silicon Dioxide' -> NEW ingredient, not in any medicine list (none (best fuzzy 52))
- 'Meth ocarbamol' -> 'Methocarbamol' (joined)
- 'Para cetamol' -> 'Paracetamol' (joined)
- 'Sucr alfate' -> 'Sucralfate' (joined)
- 'Sulfa quinoxaline' -> NEW ingredient, not in any medicine list (none (best fuzzy 69))
- 'Diaveridine HCl' -> NEW ingredient, not in any medicine list (none (best fuzzy 74))
- 'Tram adol HCl' -> 'Tramadol' (joined)
- 'Tram adol HCl' -> 'Tramadol' (joined)
- 'Ethoheptazine' -> NEW ingredient, not in any medicine list (none (best fuzzy 67))
- 'Wheat germ oil' -> NEW ingredient, not in any medicine list (none (best fuzzy 67))
- 'Orange oil' -> NEW ingredient, not in any medicine list (none (best fuzzy 59))
- 'Herbal' -> NEW ingredient, not in any medicine list (none (best fuzzy 53))

## Jan Aushadhi: 2111 on official list, 2479 rows in MRP list, 1638 mapped to ingredients, 115 not mappable (skipped, mostly devices/nutraceuticals)

## WHO: 268 AWaRe entries (19 combinations); 109/116 'not recommended' FDCs mapped

## Uses: 480 phrases, 1589 ingredient sets, 759 single ingredients; 219,518 products have an exact-set phrase; 4320 translations loaded

## CDSCO drug alerts: 5,307 NSQ/spurious batch records, 45 reporting months

## WHO antibiotics not present in any Indian product in the dataset (informational)

Arbekacin, Aspoxicillin, Azanidazole, Azidocillin, Azlocillin, Bekanamycin, Benzathine-benzylpenicillin, Benzathine-phenoxymethylpenicillin, Benzylpenicillin, Biapenem, Brodimoprim, Carindacillin, Carumonam, Cefacetrile, Cefaloridine, Cefalotin, Cefamandole, Cefapirin, Cefatrizine, Cefazedone, Cefbuperazone, Cefcapene-pivoxil, Cefiderocol, Cefmenoxime, Cefmetazole, Cefminox, Cefodizime, Cefonicid, Ceforanide, Cefoselis, Cefotetan, Cefotiam, Cefoxitin, Cefozopran, Cefpiramide, Cefradine, Cefroxadine, Cefsulodin, Ceftaroline-fosamil, Cefteram-pivoxil, Ceftezole, Ceftobiprole-medocaril, Chlortetracycline, Cinoxacin, Clofoctol, Clometocillin, Clomocycline, Dalbavancin, Delafloxacin, Dibekacin, Dirithromycin, Enoxacin, Epicillin, Eravacycline, Fidaxomicin, Fleroxacin, Flomoxef, Flumequine, Flurithromycin, Furazidin, Gepotidacin, Grepafloxacin, Hetacillin, Iclaprim, Isepamicin, Josamycin, Lascufloxacin, Latamoxef, Lefamulin, Loracarbef, Mecillinam, Metacycline, Metampicillin, Meticillin, Mezlocillin, Micronomicin, Midecamycin, Miocamycin, Nafcillin, Nemonoxacin, Nifurtoinol, Oleandomycin, Omadacycline, Oritavancin, Oxacillin, Oxolinic-acid, Panipenem, Paromomycin, Pazufloxacin, Penamecillin, Penimepicycline, Pheneticillin, Phenoxymethylpenicillin, Pipemidic-acid, Piromidic-acid, Pivampicillin, Pivmecillinam, Plazomicin, Pristinamycin, Procaine-benzylpenicillin, Propenidazole, Propicillin, Ribostamycin, Rifamycin, Rokitamycin, Rolitetracycline, Rosoxacin, Rufloxacin, Sarecycline, Sitafloxacin, Solithromycin, Spectinomycin, Streptoduocin, Sulbenicillin, Sulfadimethoxine, Sulfadimidine, Sulfafurazole, Sulfaisodimidine, Sulfalene, Sulfamazone, Sulfamerazine, Sulfamethizole, Sulfamethoxypyridazine, Sulfametomidine, Sulfametoxydiazine, Sulfamoxole, Sulfanilamide, Sulfaperin, Sulfaphenazole, Sulfapyridine, Sulfathiazole, Sulfathiourea, Sultamicillin, Talampicillin, Tazobactam, Tebipenem, Tedizolid, Telavancin, Telithromycin, Temafloxacin, Temocillin, Thiamphenicol, Tosufloxacin, Troleandomycin, Trovafloxacin
