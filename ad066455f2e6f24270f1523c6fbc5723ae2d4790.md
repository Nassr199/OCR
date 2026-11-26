# OCR bench — commit ad066455f2e6f24270f1523c6fbc5723ae2d4790 — 2026-11-26T00:01:00.167626302Z

Vision: no VISION_API_KEY secret — Lens only

## 01_habibi_credits_bg0.jpg — credits both sides, background variant 0
truth: «حبيبي، هل أنت بخير؟»

- Lens one look ✓ shape ✓ «حبيبي، هل أنت بخير؟» strays 0
    full «Saç Tasarım ⏎ Mustafa Gümüş ⏎ حبيبي، هل أنت بخير؟ ⏎ Makyaj Tasarım ⏎ Beste Bayoğlu»
    lines Saç Tasarım[0.187,0.410,0.085,0.085 p0] Mustafa Gümüş[0.187,0.654,0.245,0.259 p1] حبيبي، هل أنت بخير؟[0.500,0.665,0.335,0.259 p2] Makyaj Tasarım[0.821,0.413,0.115,0.100 p3] Beste Bayoğlu[0.822,0.597,0.219,0.169 p4]
- Lens app ✓ shape ✓ «حبيبي، هل أنت بخير؟»

## 01_habibi_credits_bg1.jpg — credits both sides, background variant 1
truth: «حبيبي، هل أنت بخير؟»

- Lens one look ✓ shape ✓ «حبيبي، هل أنت بخير؟» strays 0
    full «Saç Tasarım ⏎ Mustafa Gümüş ⏎ حبيبي، هل أنت بخير؟ ⏎ Makyaj Tasarım ⏎ Beste Bayoğlu»
    lines Saç Tasarım[0.187,0.413,0.087,0.090 p0] Mustafa Gümüş[0.189,0.652,0.243,0.254 p1] حبيبي، هل أنت بخير؟[0.499,0.663,0.337,0.254 p2] Makyaj Tasarım[0.820,0.413,0.115,0.100 p3] Beste Bayoğlu[0.822,0.602,0.219,0.169 p4]
- Lens app ✓ shape ✓ «حبيبي، هل أنت بخير؟»

## 01_habibi_credits_bg2.jpg — credits both sides, background variant 2
truth: «حبيبي، هل أنت بخير؟»

- Lens one look ✓ shape ✓ «حبيبي، هل أنت بخير؟» strays 0
    full «Saç Tasarım ⏎ Mustafa Gümüş ⏎ حبيبي، هل أنت بخير؟ ⏎ Makyaj Tasarım ⏎ Beste Bayoğlu»
    lines Saç Tasarım[0.187,0.408,0.086,0.090 p0] Mustafa Gümüş[0.187,0.650,0.245,0.259 p1] حبيبي، هل أنت بخير؟[0.500,0.661,0.334,0.259 p2] Makyaj Tasarım[0.820,0.413,0.115,0.100 p3] Beste Bayoğlu[0.822,0.602,0.220,0.169 p4]
- Lens app ✓ shape ✓ «حبيبي، هل أنت بخير؟»

## 02_habibi_alone.jpg — no credits
truth: «حبيبي، هل أنت بخير؟»

- Lens one look ✓ shape ✓ «حبيبي، هل أنت بخير؟» strays 0
    full «حبيبي، هل أنت بخير؟»
    lines حبيبي، هل أنت بخير؟[0.500,0.667,0.336,0.239 p0]
- Lens app ✓ shape ✓ «حبيبي، هل أنت بخير؟»

## 03_abi_over_credit_a255.jpg — box covers the middle of a credit name, credit alpha 255
truth: «أبي»

- Lens one look ✗ (3) shape ✗ «» «» strays 0
    full «Yardımcı Yönetmen ⏎ Gökhan Yılmaz ⏎ Yapım Koordinatörü ⏎ 2. Ekip Görüntü Yönetmeni ⏎ Tuğberk Süel»
    lines Yardımcı Yönetmen[0.190,0.399,0.139,0.070 p0] Gökhan Yılmaz[0.192,0.584,0.231,0.149 p1] Yapım Koordinatörü[0.499,0.410,0.141,0.095 p2] 2. Ekip Görüntü Yönetmeni[0.809,0.408,0.188,0.100 p3] Tuğberk Süel[0.810,0.596,0.203,0.179 p4]
- Lens app ✓ shape ✓ «أبي»
    looks: الصورة «Yardımcı Yönetmen ⏎ Yapım Koordinatörü ⏎ 2. Ekip Görüntü Yönetmeni ⏎ Gökhan Yılm» | ×2 «Yardımcı Yönetmen ⏎ Yapım Koordinatörü ⏎ 2. Ekip Görüntü Yönetmeni ⏎ Gökhan Yılm» | الوسط «en ⏎ Yapım Koordinatörü ⏎ 2. Ek ⏎ maz Dan أبي ines Tu» → ×2

## 03_abi_over_credit_a150.jpg — box covers the middle of a credit name, credit alpha 150
truth: «أبي»

- Lens one look ✗ (1) shape ✗ «بي» «بي» strays 0
    full «Yardımcı Yönetmen ⏎ Gökhan Yılmaz ⏎ Yapım Koordinatörü ⏎ بي ⏎ 2. Ekip Görüntü Yönetmeni ⏎ Tuğberk Süel»
    lines Yardımcı Yönetmen[0.190,0.401,0.139,0.075 p0] Gökhan Yılmaz[0.191,0.584,0.233,0.139 p1] Yapım Koordinatörü[0.498,0.411,0.142,0.095 p2] بي[0.492,0.732,0.037,0.114 p3] 2. Ekip Görüntü Yönetmeni[0.810,0.410,0.189,0.095 p4] Tuğberk Süel[0.809,0.591,0.202,0.169 p5]
- Lens app ✓ shape ✓ «أبي»
    looks: الصورة «Yardımcı Yönetmen ⏎ Yapım Koordinatörü ⏎ 2. Ekip Görüntü Yönetmeni ⏎ Gökhan Yılm» | ×2 «Yardımcı Yönetmen ⏎ Yapım Koordinatörü ⏎ 2. Ekip Görüntü Yönetmeni ⏎ Gökhan Yılm» | حول الترجمة «Yapım Koordinat ⏎ أبي ⏎ n» | شريط الترجمة «3:» → حول الترجمة

## 04_two_lines.jpg — two lines
truth: «أجل، أنا بخير. ⏎ لا تقلق عليّ.»

- Lens one look ✓ shape ✗ «أجل، أنا بخير. ⏎ لا تقلق علي.» «أجل، أنا بخير. لا تقلق علي.» strays 0
    full «أجل، أنا بخير. ⏎ لا تقلق علي.»
    lines أجل، أنا بخير.[0.499,0.431,0.179,0.219 p0] لا تقلق علي.[0.500,0.743,0.173,0.179 p0]
- Lens app ✓ shape ✗ «أجل، أنا بخير. ⏎ لا تقلق علي.» «أجل، أنا بخير. لا تقلق علي.»

## 05_long_line_credits.jpg — long line, short credits at the edges
truth: «لا أعرف ماذا سأفعل الآن، كل شيء تغيّر»

- Lens one look ✓ shape ✓ «لا أعرف ماذا سأفعل الآن، كل شيء تغير» strays 0
    full «Kurgu ⏎ Ali Kaya ⏎ لا أعرف ماذا سأفعل الآن، كل شيء تغيّر ⏎ Müzik ⏎ Can Ercan»
    lines Kurgu[0.119,0.412,0.048,0.095 p0] Ali Kaya[0.121,0.645,0.129,0.244 p1] لا أعرف ماذا سأفعل الآن، كل شيء تغيّر[0.498,0.654,0.541,0.254 p2] Müzik[0.876,0.403,0.049,0.070 p3] Can Ercan[0.878,0.656,0.160,0.244 p4]
- Lens app ✓ shape ✓ «لا أعرف ماذا سأفعل الآن، كل شيء تغير»

## 06_short_words.jpg — short words, one credit
truth: «لا، لم يحدث شيء»

- Lens one look ✓ shape ✓ «لا، لم يحدث شيء» strays 0
    full «Styling ⏎ Aslı Çolak ⏎ لا، لم يحدث شيء»
    lines Styling[0.187,0.416,0.051,0.104 p0] Aslı Çolak[0.188,0.595,0.156,0.154 p1] لا، لم يحدث شيء[0.501,0.656,0.273,0.209 p2]
- Lens app ✓ shape ✓ «لا، لم يحدث شيء»

## 07_short_0_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «نعم»

- Lens one look ✓ shape ✓ «نعم» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Görüntü Yönetmeni ⏎ Sel ⏎ نعم ⏎ dın ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.401,0.115,0.075 p0] İsmail Durmaz[0.192,0.572,0.226,0.154 p1] Görüntü Yönetmeni[0.498,0.401,0.137,0.075 p2] Sel[0.437,0.582,0.051,0.119 p3] نعم[0.501,0.656,0.054,0.129 p4] dın[0.564,0.577,0.046,0.149 p5] Kostüm Sorumlusu[0.809,0.399,0.131,0.070 p6] Hilcen Can[0.811,0.580,0.170,0.139 p7]
- Lens app ✓ shape ✓ «نعم»
    looks: الصورة «Sanat Yönetmeni ⏎ Görüntü Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Sel ⏎ d» | ×2 «Sanat Yönetmeni ⏎ Görüntü Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Sel ⏎ d» | حول الترجمة «Görüntü Yönetmeni ⏎ نعم ⏎ el» | شريط الترجمة «Ismall urmaz ⏎ icen Can» → الصورة

## 07_short_0_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «نعم»

- Lens one look ✓ shape ✓ «نعم» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Fortintü Yönetmeni ⏎ Sel ⏎ نعم ⏎ dın ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.401,0.115,0.075 p0] İsmail Durmaz[0.192,0.573,0.226,0.154 p1] Fortintü Yönetmeni[0.503,0.403,0.125,0.080 p2] Sel[0.438,0.582,0.054,0.119 p3] نعم[0.501,0.653,0.054,0.134 p4] dın[0.564,0.577,0.050,0.149 p5] Kostüm Sorumlusu[0.809,0.401,0.130,0.075 p6] Hilcen Can[0.810,0.580,0.170,0.139 p7]
- Lens app ✓ shape ✓ «نعم»
    looks: الصورة «Sanat Yönetmeni ⏎ Fortintü Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Sel ⏎ » | ×2 «Sanat Yönetmeni ⏎ Görüntü Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Hilcen » | حول الترجمة «Görüntü Yönetmeni ⏎ el ⏎ نعم» | شريط الترجمة «Ismall Durmaz ⏎ Se ⏎ ain نعم ⏎ Hlicen Can» → الصورة

## 07_short_1_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «أمي»

- Lens one look ✓ shape ✓ «أمي» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Sanat Yönetmeni ⏎ İsma أمي maz ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.401,0.114,0.075 p0] İsmail Durmaz[0.192,0.577,0.225,0.154 p1] Sanat Yönetmeni[0.499,0.401,0.115,0.075 p2] İsma أمي maz[0.500,0.646,0.225,0.284 p3] Kostüm Sorumlusu[0.809,0.398,0.131,0.070 p4] Hilcen Can[0.810,0.579,0.170,0.139 p5]
- Lens app ✓ shape ✓ «أمي»
    looks: الصورة «Sanat Yönetmeni ⏎ Sanat Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz İsma أمي ma» | ×2 «Sanat Yönetmeni ⏎ Sanat Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Hilcen Ca» | حول الترجمة «Sanat Yönetmeni ⏎ Z İsma ⏎ أمى ⏎ maz» | شريط الترجمة «İsmail Durmaz İsma ⏎ أمي ⏎ 'maz ⏎ Hilcen Can» → الصورة

## 07_short_1_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «أمي»

- Lens one look ✗ (2) shape ✗ «ي» «ي» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Sanat Yönetmeni ⏎ ـي ⏎ maz ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.398,0.114,0.070 p0] İsmail Durmaz[0.192,0.572,0.227,0.164 p1] Sanat Yönetmeni[0.499,0.407,0.114,0.085 p2] ـي[0.490,0.731,0.029,0.104 p3] maz[0.581,0.597,0.064,0.100 p4] Kostüm Sorumlusu[0.808,0.399,0.132,0.070 p5] Hilcen Can[0.810,0.580,0.171,0.129 p6]
- Lens app ✗ (1) shape ✗ «امي» «امي»
    looks: الصورة «Sanat Yönetmeni ⏎ Sanat Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ maz ⏎ Hil» | ×2 «Sanat Yönetmeni ⏎ Sanat Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Hilcen Ca» | الوسط «Sanat Yönetmeni ⏎ maz ⏎ H ⏎ امي» → الوسط

## 07_short_2_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «ماذا؟»

- Lens one look ✓ shape ✓ «ماذا؟» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Kostüm ⏎ an ماذا؟ Hi ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.401,0.115,0.075 p0] İsmail Durmaz[0.192,0.572,0.226,0.154 p1] Kostüm[0.498,0.398,0.056,0.070 p2] an ماذا؟ Hi[0.500,0.607,0.173,0.169 p3] Kostüm Sorumlusu[0.809,0.401,0.131,0.075 p4] Hilcen Can[0.811,0.579,0.169,0.139 p5]
- Lens app ✓ shape ✓ «ماذا؟»
    looks: الصورة «Sanat Yönetmeni ⏎ Kostüm ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ an ماذا؟ Hi ⏎ Hilc» | ×2 «Sanat Yönetmeni ⏎ Kostüm ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Hi ⏎ an ماذا؟ ⏎ Hi» | حول الترجمة «Kostüm ⏎ an ماذا؟ Hi» | شريط الترجمة «İsmail Durmaz ⏎ Hi ⏎ an ماذا؟ ⏎ Hilcen Can» → الصورة

## 07_short_2_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «ماذا؟»

- Lens one look ✓ shape ✓ «ماذا؟» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Kostüm ⏎ an ماذا؟ Hi ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.401,0.115,0.075 p0] İsmail Durmaz[0.192,0.570,0.226,0.159 p1] Kostüm[0.499,0.401,0.058,0.065 p2] an ماذا؟ Hi[0.500,0.605,0.173,0.164 p3] Kostüm Sorumlusu[0.809,0.399,0.131,0.070 p4] Hilcen Can[0.811,0.580,0.168,0.139 p5]
- Lens app ✓ shape ✓ «ماذا؟»
    looks: الصورة «Sanat Yönetmeni ⏎ Kostüm ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ an ماذا؟ Hi ⏎ Hilc» | ×2 «Sanat Yönetmeni ⏎ Kostüm ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ an ماذا؟ Hi ⏎ Hilc» | حول الترجمة «Kostüm ⏎ an ماذا؟ Hi» | شريط الترجمة «İsmail Durmaz ⏎ Hi ⏎ an ماذا؟ ⏎ Hilcen Can» → الصورة

## 07_short_3_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «حسنًا»

- Lens one look ✗ (4) shape ✗ «» «» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Yapımcı ⏎ Ketay ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.398,0.115,0.070 p0] İsmail Durmaz[0.192,0.570,0.226,0.159 p1] Yapımcı[0.497,0.410,0.061,0.095 p2] Ketay[0.502,0.604,0.198,0.159 p3] Kostüm Sorumlusu[0.809,0.399,0.130,0.070 p4] Hilcen Can[0.811,0.580,0.170,0.139 p5]
- Lens app ✓ shape ✓ «حسنا»
    looks: الصورة «Sanat Yönetmeni ⏎ Yapımcı ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Ketay ⏎ Hilcen Ca» | ×2 «Sanat Yönetmeni ⏎ Yapımcı ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ Ker ⏎ tay حسنًا ⏎» | الوسط «Yapımcı ⏎ H ⏎ tay ⏎ حسنًا ⏎ Kei ⏎ maz» → ×2

## 07_short_3_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «حسنًا»

- Lens one look ✗ (4) shape ✗ «» «» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Yapımcı ⏎ Ketay ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.403,0.115,0.070 p0] İsmail Durmaz[0.192,0.568,0.225,0.154 p1] Yapımcı[0.498,0.408,0.062,0.090 p2] Ketay[0.501,0.604,0.199,0.159 p3] Hilcen Can[0.809,0.583,0.174,0.109 p4]
- Lens app ✗ (4) shape ✗ «» «»
    looks: الصورة «Sanat Yönetmeni ⏎ Yapımcı ⏎ İsmail Durmaz ⏎ Ketay ⏎ Hilcen Can» | ×2 «Sanat Yönetmeni ⏎ Yapımcı ⏎ İsmail Durmaz ⏎ Hilcen Can» | الوسط «Yapımcı ⏎ maz ⏎ Ketay ⏎ H» → لا ترجمة

## 07_short_4_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «لماذا؟»

- Lens one look ✓ shape ✓ «لماذا؟» strays 0
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ Senaryo ⏎ E ⏎ لماذا؟ ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.401,0.115,0.075 p0] İsmail Durmaz[0.192,0.577,0.225,0.154 p1] Senaryo[0.499,0.409,0.056,0.080 p2] E[0.425,0.610,0.023,0.164 p3] لماذا؟[0.502,0.610,0.093,0.164 p4] Kostüm Sorumlusu[0.809,0.401,0.130,0.075 p5] Hilcen Can[0.811,0.579,0.168,0.139 p6]
- Lens app ✓ shape ✓ «لماذا؟»

## 07_short_4_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «لماذا؟»

- Lens one look ✓ shape ✓ «لماذا؟» strays 2
    full «Sanat Yönetmeni ⏎ İsmail Durmaz ⏎ - ⏎ لماذا ؟ ⏎ و ⏎ Kostüm Sorumlusu ⏎ Hilcen Can»
    lines Sanat Yönetmeni[0.191,0.398,0.115,0.070 p0] İsmail Durmaz[0.192,0.573,0.227,0.164 p1] -[0.426,0.609,0.035,0.154 p2] لماذا ؟[0.502,0.609,0.094,0.154 p3] و[0.573,0.609,0.024,0.154 p4] Kostüm Sorumlusu[0.809,0.401,0.131,0.075 p5] Hilcen Can[0.811,0.580,0.168,0.139 p6]
- Lens app ✓ shape ✓ «لماذا؟»
    looks: الصورة «Sanat Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ لماذا ؟ ⏎ Hilcen Can» | ×2 «Sanat Yönetmeni ⏎ Kostüm Sorumlusu ⏎ İsmail Durmaz ⏎ لماذا ؟ ⏎ Hilcen Can» | حول الترجمة «لماذا؟» | شريط الترجمة «İsmail Durmaz ⏎ لماذا ؟ ⏎ Hilcen Can» → الصورة

## 08_dialogue_dashes.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

- Lens one look ✓ shape ✓ «هوليا ابتعدي يا جوليزار» strays 0
    full «- هوليا ⏎ - ابتعدي يا جوليزار»
    lines - هوليا[0.501,0.420,0.116,0.196 p0] - ابتعدي يا جوليزار[0.500,0.702,0.332,0.213 p1]
- Lens app ✓ shape ✓ «هوليا ابتعدي يا جوليزار»

## 09_two_lines_period.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «هوليا. ساعدوني ⏎ ماذا حدث؟»

- Lens one look ✓ shape ✓ «هوليا. ساعدوني ماذا حدث؟» strays 0
    full «هوليا. ساعدوني ⏎ ماذا حدث؟»
    lines هوليا. ساعدوني[0.499,0.406,0.282,0.213 p0] ماذا حدث؟[0.500,0.671,0.180,0.170 p0]
- Lens app ✓ shape ✓ «هوليا. ساعدوني ماذا حدث؟»

## 10_dash_one_line.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «- لا أعرف»

- Lens one look ✓ shape ✓ «لا أعرف» strays 0
    full «- لا أعرف»
    lines - لا أعرف[0.499,0.535,0.169,0.243 p0]
- Lens app ✓ shape ✓ «لا أعرف»

## 11_ellipsis.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «انتظر... لا تذهب!»

- Lens one look ✓ shape ✓ «انتظر... لا تذهب!» strays 0
    full «انتظر... لا تذهب!»
    lines انتظر... لا تذهب![0.501,0.558,0.294,0.222 p0]
- Lens app ✓ shape ✓ «انتظر... لا تذهب!»

## 12_dialogue_question.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «- هل أنت بخير؟ ⏎ - نعم، أنا بخير»

- Lens one look ✓ shape ✓ «هل أنت بخير؟ نعم، أنا بخير» strays 0
    full «- هل أنت بخير؟ ⏎ - نعم، أنا بخير»
    lines - هل أنت بخير؟[0.500,0.396,0.268,0.239 p0] - نعم، أنا بخير[0.497,0.679,0.248,0.257 p1]
- Lens app ✓ shape ✓ «هل أنت بخير؟ نعم، أنا بخير»

## 13_dialogue_box.jpg — two-line subtitle in the box, second line wider
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

- Lens one look ✓ shape ✗ «هوليا ⏎ - ابتعدي يا جوليزار» «هوليا ابتعدي يا جوليزار» strays 0
    full «هوليا ⏎ - ابتعدي يا جوليزار»
    lines هوليا[0.491,0.530,0.069,0.154 p0] - ابتعدي يا جوليزار[0.497,0.775,0.251,0.179 p1]
- Lens app ✓ shape ✗ «هوليا ⏎ - ابتعدي يا جوليزار» «هوليا ابتعدي يا جوليزار»

## 14_dialogue_box_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

- Lens one look ✓ shape ✗ «هوليا ⏎ - ابتعدي يا جوليزار» «هوليا ابتعدي يا جوليزار» strays 0
    full «Görüntü Yönetmeni ⏎ Selin Aydın ⏎ هوليا ⏎ - ابتعدي يا جوليزار ⏎ Kurgu ⏎ Ali Kaya»
    lines Görüntü Yönetmeni[0.140,0.402,0.136,0.075 p0] Selin Aydın[0.142,0.599,0.171,0.169 p1] هوليا[0.491,0.530,0.069,0.154 p2] - ابتعدي يا جوليزار[0.498,0.770,0.250,0.179 p3] Kurgu[0.860,0.412,0.048,0.095 p4] Ali Kaya[0.859,0.594,0.128,0.154 p5]
- Lens app ✓ shape ✗ «هوليا ⏎ - ابتعدي يا جوليزار» «هوليا ابتعدي يا جوليزار»

## 15_dialogue_box_thin_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

- Lens one look ✓ shape ✗ «هوليا ⏎ - ابتعدي يا جوليزار» «هوليا ابتعدي يا جوليزار» strays 0
    full «Görüntü Yönetmeni ⏎ Selin Aydın ⏎ هوليا ⏎ - ابتعدي يا جوليزار ⏎ Kurgu ⏎ Ali Kaya»
    lines Görüntü Yönetmeni[0.140,0.401,0.136,0.075 p0] Selin Aydın[0.143,0.596,0.172,0.164 p1] هوليا[0.492,0.550,0.050,0.144 p2] - ابتعدي يا جوليزار[0.498,0.785,0.193,0.169 p3] Kurgu[0.859,0.419,0.047,0.090 p4] Ali Kaya[0.860,0.594,0.127,0.154 p5]
- Lens app ✓ shape ✗ «هوليا ⏎ - ابتعدي يا جوليزار» «هوليا ابتعدي يا جوليزار»

## 16_dialogue_box_long2_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- ماذا؟ ⏎ - لا شيء، عودي إلى البيت الآن»

- Lens one look ✓ shape ✓ «ماذا؟ لا شيء، عودي إلى البيت الآن» strays 0
    full «Görüntü Yönetmeni ⏎ Selin Aydın ⏎ - ماذا؟ ⏎ - لا شيء، عودي إلى البيت الآن ⏎ Kurgu ⏎ Ali Kaya»
    lines Görüntü Yönetmeni[0.140,0.402,0.136,0.075 p0] Selin Aydın[0.143,0.597,0.172,0.164 p1] - ماذا؟[0.500,0.475,0.089,0.134 p2] - لا شيء، عودي إلى البيت الآن[0.500,0.752,0.415,0.194 p3] Kurgu[0.860,0.414,0.048,0.090 p4] Ali Kaya[0.860,0.600,0.127,0.164 p5]
- Lens app ✓ shape ✓ «ماذا؟ لا شيء، عودي إلى البيت الآن»

## 17_dialogue_box_nospace_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

- Lens one look ✓ shape ✓ «هوليا ابتعدي يا جوليزار» strays 0
    full «Görüntü Yönetmeni ⏎ Selin Aydın ⏎ -هوليا ⏎ -ابتعدي يا جوليزار ⏎ Kurgu ⏎ Ali Kaya»
    lines Görüntü Yönetmeni[0.140,0.402,0.136,0.075 p0] Selin Aydın[0.142,0.601,0.172,0.164 p1] -هوليا[0.501,0.522,0.080,0.179 p2] -ابتعدي يا جوليزار[0.498,0.770,0.242,0.179 p2] Kurgu[0.860,0.414,0.048,0.090 p3] Ali Kaya[0.860,0.598,0.127,0.164 p4]
- Lens app ✓ shape ✓ «هوليا ابتعدي يا جوليزار»

## 18_one_speaker_wrap_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- لا أعرف ماذا سأفعل ⏎ بعد كل ما حدث»

- Lens one look ✓ shape ✓ «لا أعرف ماذا سأفعل بعد كل ما حدث» strays 0
    full «Görüntü Yönetmeni ⏎ Selin Aydın ⏎ - لا أعرف ماذا سأفعل ⏎ بعد كل ما حدث ⏎ Kurgu ⏎ Ali Kaya»
    lines Görüntü Yönetmeni[0.140,0.401,0.136,0.075 p0] Selin Aydın[0.142,0.598,0.171,0.169 p1] - لا أعرف ماذا سأفعل[0.498,0.443,0.293,0.209 p2] بعد كل ما حدث[0.500,0.746,0.200,0.159 p2] Kurgu[0.860,0.416,0.048,0.095 p3] Ali Kaya[0.860,0.595,0.128,0.159 p4]
- Lens app ✓ shape ✓ «لا أعرف ماذا سأفعل بعد كل ما حدث»

## Summary (after the app's classic cleaning; errors = edit distance to the truth)

| engine | exact | char errors | CER |
|---|---|---|---|
| lens one look | 25/30 | 14 | 3.1% |
| lens app (multi-view) | 28/30 | 5 | 1.1% |

## Shape kept (lines, dialogue dashes, punctuation — after the app's Lens/Vision cleaning)

| engine | exact shape |
|---|---|
| lens one look | 21/30 |
| lens app (multi-view) | 24/30 |
