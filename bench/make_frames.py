#!/usr/bin/env python3
"""Synthetic subtitle crops shaped like the user's real ones (1230x201, white Arabic text in an opaque
black box, Latin crew credits on the same rows, photo-like background). Nothing copyrighted."""
import json, os, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont, features

assert features.check('raqm'), "needs libraqm for Arabic shaping"
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
W, H = 1230, 201
AR = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 40)
AR_SMALL = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 34)
SERIF_B = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf', 34)
SERIF = ImageFont.truetype('/usr/share/fonts/truetype/freefont/FreeSerif.ttf', 21)


def background(seed):
    rnd = random.Random(seed)
    img = Image.new('RGB', (W, H))
    d = ImageDraw.Draw(img)
    top = [rnd.randint(20, 90) for _ in range(3)]
    bot = [rnd.randint(10, 60) for _ in range(3)]
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)))
    for _ in range(14):  # blurred skin/fabric shapes
        x, y = rnd.randint(-100, W), rnd.randint(-80, H)
        r = rnd.randint(60, 260)
        col = rnd.choice([(196, 140, 120), (170, 110, 95), (60, 40, 35), (120, 90, 80), (30, 30, 34), (150, 30, 40)])
        d.ellipse([x, y, x + r * 2, y + int(r * 1.3)], fill=col)
    img = img.filter(ImageFilter.GaussianBlur(24))
    noise = Image.effect_noise((W, H), 10).convert('RGB')
    return Image.blend(img, noise, 0.06)


def credit(d, cx, role, name, alpha=255):
    col = (alpha, alpha, alpha)
    d.text((cx, 82), role, font=SERIF, fill=(int(alpha * .92),) * 3, anchor='mm')
    d.text((cx, 118), name, font=SERIF_B, fill=col, anchor='mm')


def subtitle(img, lines, cx=615, font=AR, pad=(16, 10)):
    d = ImageDraw.Draw(img)
    boxes = [d.textbbox((0, 0), l, font=font, direction='rtl', language='ar') for l in lines]
    lh = max(b[3] - b[1] for b in boxes) + 14
    bw = max(b[2] - b[0] for b in boxes) + pad[0] * 2
    bh = lh * len(lines) + pad[1] * 2
    top = H - bh - 14 if len(lines) > 1 else 92
    d.rectangle([cx - bw // 2, top, cx + bw // 2, top + bh], fill=(0, 0, 0))
    LAST_BOX[:] = [cx - bw // 2, top, cx + bw // 2, top + bh]
    for i, l in enumerate(lines):
        d.text((cx, top + pad[1] + lh * i + lh // 2), l, font=font, fill=(255, 255, 255), anchor='mm', direction='rtl', language='ar')
        mark_text(d, (cx, top + pad[1] + lh * i + lh // 2), l, font)


cases = []
LAST_BOX = [0, 0, 0, 0]
TEXT_BOX = [10 ** 6, 10 ** 6, -1, -1]


def save(name, img, truth, note):
    p = os.path.join(OUT, name + '.jpg')
    img.save(p, quality=95)
    cases.append({'file': name + '.jpg', 'truth': truth, 'note': note, 'box': list(LAST_BOX), 'text': list(TEXT_BOX)})
    TEXT_BOX[:] = [10 ** 6, 10 ** 6, -1, -1]


def mark_text(d, xy, line, font, stroke=0):
    """Grows TEXT_BOX by the drawn letters of the subtitle (the area a crop must never cut)."""
    b = d.textbbox(xy, line, font=font, anchor='mm', direction='rtl', language='ar', stroke_width=stroke)
    TEXT_BOX[:] = [min(TEXT_BOX[0], b[0]), min(TEXT_BOX[1], b[1]), max(TEXT_BOX[2], b[2]), max(TEXT_BOX[3], b[3])]


# 1: like the «حبيبي» frame: box in the middle, credits left and right on the same rows
for v in range(3):
    img = background(10 + v)
    d = ImageDraw.Draw(img)
    credit(d, 232, 'Saç Tasarım', 'Mustafa Gümüş')
    credit(d, 1012, 'Makyaj Tasarım', 'Beste Bayoğlu')
    subtitle(img, ['حبيبي، هل أنت بخير؟'])
    save(f'01_habibi_credits_bg{v}', img, 'حبيبي، هل أنت بخير؟', 'credits both sides, background variant %d' % v)

# 2: the same subtitle alone (control)
img = background(10)
subtitle(img, ['حبيبي، هل أنت بخير؟'])
save('02_habibi_alone', img, 'حبيبي، هل أنت بخير؟', 'no credits')

# 3: like the «أبي» frames: a small box covering the middle of a credit name
for v, alpha in enumerate([255, 150]):
    img = background(30 + v)
    d = ImageDraw.Draw(img)
    credit(d, 236, 'Yardımcı Yönetmen', 'Gökhan Yılmaz', alpha)
    credit(d, 615, 'Yapım Koordinatörü', 'Damla Güneş', alpha)
    credit(d, 997, '2. Ekip Görüntü Yönetmeni', 'Tuğberk Süel', alpha)
    subtitle(img, ['أبي'], cx=612)
    save(f'03_abi_over_credit_a{alpha}', img, 'أبي', 'box covers the middle of a credit name, credit alpha %d' % alpha)

# 4: two-line subtitle, no credits
img = background(50)
subtitle(img, ['أجل، أنا بخير.', 'لا تقلق عليّ.'], font=AR_SMALL)
save('04_two_lines', img, 'أجل، أنا بخير.\nلا تقلق عليّ.', 'two lines')

# 5: long line next to credits
img = background(60)
d = ImageDraw.Draw(img)
credit(d, 150, 'Kurgu', 'Ali Kaya')
credit(d, 1080, 'Müzik', 'Can Ercan')
subtitle(img, ['لا أعرف ماذا سأفعل الآن، كل شيء تغيّر'], font=AR_SMALL)
save('05_long_line_credits', img, 'لا أعرف ماذا سأفعل الآن، كل شيء تغيّر', 'long line, short credits at the edges')

# 6: short words that OCR likes to break
img = background(70)
d = ImageDraw.Draw(img)
credit(d, 232, 'Styling', 'Aslı Çolak')
subtitle(img, ['لا، لم يحدث شيء'])
save('06_short_words', img, 'لا، لم يحدث شيء', 'short words, one credit')

# 7: short subtitles in small boxes over/near a credit name (what Lens tends to miss)
SHORT = [('نعم', 'Görüntü Yönetmeni', 'Selin Aydın'), ('أمي', 'Sanat Yönetmeni', 'İsmail Durmaz'),
         ('ماذا؟', 'Kostüm', 'Hilcen Can'), ('حسنًا', 'Yapımcı', 'Kerem Çatay'), ('لماذا؟', 'Senaryo', 'Ece Yörenç')]
for i, (word, role, name) in enumerate(SHORT):
    for alpha in (255, 170):
        img = background(80 + i)
        d = ImageDraw.Draw(img)
        credit(d, 236, 'Sanat Yönetmeni', 'İsmail Durmaz', alpha)
        credit(d, 615, role, name, alpha)
        credit(d, 997, 'Kostüm Sorumlusu', 'Hilcen Can', alpha)
        subtitle(img, [word], cx=618)
        save(f'07_short_{i}_a{alpha}', img, word, 'short word in a small box over a credit name, alpha %d' % alpha)

# 8: outlined white text without a box (dialogue dashes, two lines, punctuation) — the shape must survive
OUT_FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 44)


def outlined(img, lines, top=None):
    d = ImageDraw.Draw(img)
    lh = 64
    y0 = top if top is not None else (H2 - lh * len(lines)) // 2 + 10
    for i, l in enumerate(lines):
        d.text((W // 2, y0 + lh * i + lh // 2), l, font=OUT_FONT, fill=(255, 255, 255), anchor='mm',
               direction='rtl', language='ar', stroke_width=3, stroke_fill=(0, 0, 0))
        mark_text(d, (W // 2, y0 + lh * i + lh // 2), l, OUT_FONT, 3)
    LAST_BOX[:] = [0, 0, 0, 0]


H2 = 230


def bright(seed):
    rnd = random.Random(seed)
    img = Image.new('RGB', (W, H2), (200, 196, 180))
    d = ImageDraw.Draw(img)
    for _ in range(18):
        x, y = rnd.randint(-100, W), rnd.randint(-80, H2)
        r = rnd.randint(40, 220)
        col = rnd.choice([(230, 228, 215), (180, 40, 45), (90, 120, 70), (240, 240, 235), (60, 70, 50), (200, 150, 130)])
        d.ellipse([x, y, x + r * 2, y + int(r * 1.2)], fill=col)
    return img.filter(ImageFilter.GaussianBlur(14))


DIALOGUE = [
    ('08_dialogue_dashes', ['- هوليا', '- ابتعدي يا جوليزار']),
    ('09_two_lines_period', ['هوليا. ساعدوني', 'ماذا حدث؟']),
    ('10_dash_one_line', ['- لا أعرف']),
    ('11_ellipsis', ['انتظر... لا تذهب!']),
    ('12_dialogue_question', ['- هل أنت بخير؟', '- نعم، أنا بخير']),
]
for i, (name, lines) in enumerate(DIALOGUE):
    img = bright(200 + i)
    outlined(img, lines)
    save(name, img, '\n'.join(lines), 'outlined white text, the shape (lines, dashes, punctuation) must be kept')

# 13-18: a two-line dialogue in the black box like the real frames — the second line is the wider one,
# so its dash is the outermost mark of the subtitle (the one a tight crop or a missed look loses)
AR_THIN = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 31)
DIALOGUE_BOX = [
    ('13_dialogue_box', ['- هوليا', '- ابتعدي يا جوليزار'], False, AR_SMALL),
    ('14_dialogue_box_credits', ['- هوليا', '- ابتعدي يا جوليزار'], True, AR_SMALL),
    ('15_dialogue_box_thin_credits', ['- هوليا', '- ابتعدي يا جوليزار'], True, AR_THIN),
    ('16_dialogue_box_long2_credits', ['- ماذا؟', '- لا شيء، عودي إلى البيت الآن'], True, AR_SMALL),
    ('17_dialogue_box_nospace_credits', ['-هوليا', '-ابتعدي يا جوليزار'], True, AR_SMALL),
    # one speaker, a sentence wrapped on two lines: the dash belongs to the first line only
    ('18_one_speaker_wrap_credits', ['- لا أعرف ماذا سأفعل', 'بعد كل ما حدث'], True, AR_SMALL),
]
for i, (name, lines, credits, font) in enumerate(DIALOGUE_BOX):
    img = background(300 + i)
    if credits:
        d = ImageDraw.Draw(img)
        credit(d, 175, 'Görüntü Yönetmeni', 'Selin Aydın')
        credit(d, 1060, 'Kurgu', 'Ali Kaya')
    subtitle(img, lines, font=font)
    truth = '\n'.join('- ' + l[1:] if l.startswith('-') and not l.startswith('- ') else l for l in lines)
    save(name, img, truth, 'two-line subtitle in the box, second line wider' + (', credits on its rows' if credits else ''))

# 19-25: white text with a thin dark outline and a soft drop shadow, no box — the style of the user's
# frames where Lens gave words twice («… انظري إلي هل تسمعينني؟ هل», «… على الجرح الجرح»)
SERIF_AR = ImageFont.truetype('/usr/share/fonts/truetype/freefont/FreeSerifBold.ttf', 52)
SANS_AR = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 44)


def shadowed(img, lines, font, cy, lh):
    """Shadow (dark, offset, blurred) under white letters with a 2 px black outline."""
    w, h = img.size
    shadow = Image.new('L', (w, h), 0)
    sd = ImageDraw.Draw(shadow)
    y0 = cy - lh * len(lines) // 2
    for i, l in enumerate(lines):
        sd.text((w // 2 + 3, y0 + lh * i + lh // 2 + 4), l, font=font, fill=200, anchor='mm', direction='rtl',
                language='ar', stroke_width=2, stroke_fill=200)
    shadow = shadow.filter(ImageFilter.GaussianBlur(3))
    img.paste(Image.new('RGB', (w, h), (10, 10, 12)), (0, 0), shadow)
    d = ImageDraw.Draw(img)
    for i, l in enumerate(lines):
        d.text((w // 2, y0 + lh * i + lh // 2), l, font=font, fill=(255, 255, 255), anchor='mm', direction='rtl',
               language='ar', stroke_width=2, stroke_fill=(0, 0, 0))
        mark_text(d, (w // 2, y0 + lh * i + lh // 2), l, font, 2)
    LAST_BOX[:] = [0, 0, 0, 0]


def scene(seed, w, h):
    """Bright blurred garden/wall with a dark suit in the middle (the subtitle crosses both)."""
    rnd = random.Random(seed)
    img = Image.new('RGB', (w, h), (215, 212, 196))
    d = ImageDraw.Draw(img)
    for _ in range(26):
        x, y = rnd.randint(-150, w), rnd.randint(-120, h)
        r = rnd.randint(40, 260)
        col = rnd.choice([(235, 232, 220), (70, 110, 60), (110, 140, 80), (245, 245, 240), (190, 170, 150), (160, 40, 45)])
        d.ellipse([x, y, x + r * 2, y + int(r * 1.2)], fill=col)
    d.polygon([(w * 0.28, h), (w * 0.36, h * 0.45), (w * 0.62, h * 0.42), (w * 0.72, h)], fill=(28, 32, 52))
    d.polygon([(w * 0.45, h * 0.5), (w * 0.52, h * 0.47), (w * 0.53, h), (w * 0.47, h)], fill=(235, 235, 240))
    return img.filter(ImageFilter.GaussianBlur(10))


HEAR = 'هل تسمعينني؟ هل تسمعينني؟ انظري إلي'
WOUND = 'قمت بالضغط المباشر على الجرح'
for name, text, font, seed in [('19_shadow_hear_me', HEAR, SANS_AR, 400), ('20_shadow_hear_me_serif', HEAR, SERIF_AR, 401),
                               ('21_shadow_wound', WOUND, SANS_AR, 402), ('22_shadow_wound_serif', WOUND, SERIF_AR, 403)]:
    img = scene(seed, W, H2)
    shadowed(img, [text], font, H2 // 2, 70)
    save(name, img, text, 'white text, thin outline, soft drop shadow, no box (bright scene, dark suit)')
FW, FH = 1500, 844
for name, lines, seed in [('23_frame_hear_me', [HEAR], 410), ('24_frame_wound', [WOUND], 411),
                          ('25_frame_dialogue', ['- هوليا', '- ابتعدي يا جوليزار'], 412)]:
    img = scene(seed, FW, FH)
    shadowed(img, lines, SERIF_AR, int(FH * 0.885) - (35 if len(lines) > 1 else 0), 70)
    save(name, img, '\n'.join(lines), 'whole frame 1500×844, shadowed subtitle at the bottom')

# 26-31 (2.9.0): the video mode's picture — the subtitle area of a 1080p frame (1920×432), white
# letters with a black outline and a soft shadow, on the bright scenes where Gemini 3.5 Flash-Lite
# misread the user's frames: «هوليا» → «خوليا» (light floor, white dress), the short first line
# «- هوليا» left out, a dot added before «هوليا. ساعدوني».
RW, RH = 1920, 432
ROI_FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 54)


def roi_scene(kind, seed):
    rnd = random.Random(seed)
    if kind == 'tiles':
        img = Image.new('RGB', (RW, RH), (226, 221, 206))
        d = ImageDraw.Draw(img)
        for x in range(0, RW, 170):
            d.line([(x, 0), (x, RH)], fill=(196, 190, 174), width=3)
        for y in range(40, RH, 170):
            d.line([(0, y), (RW, y)], fill=(196, 190, 174), width=3)
        for _ in range(40):
            x, y = rnd.randint(0, RW), rnd.randint(RH // 2, RH)
            d.ellipse([x, y, x + rnd.randint(10, 26), y + rnd.randint(30, 70)], fill=rnd.choice([(120, 90, 140), (90, 120, 80), (150, 120, 160)]))
        return img.filter(ImageFilter.GaussianBlur(2))
    if kind == 'lace':
        img = Image.new('RGB', (RW, RH), (238, 234, 228))
        d = ImageDraw.Draw(img)
        for _ in range(60):
            x, y = rnd.randint(-50, RW), rnd.randint(-50, RH)
            r = rnd.randint(20, 90)
            d.arc([x, y, x + r, y + r], rnd.randint(0, 180), rnd.randint(180, 360), fill=(205, 200, 194), width=2)
        for _ in range(80):
            x, y = rnd.randint(0, RW), rnd.randint(0, RH)
            d.ellipse([x, y, x + 4, y + 7], fill=(140, 30, 30))
        d.rectangle([0, 0, RW * 0.3, RH], fill=(40, 36, 40))
        return img.filter(ImageFilter.GaussianBlur(1.5))
    if kind == 'red_skin':
        img = Image.new('RGB', (RW, RH), (226, 230, 224))
        d = ImageDraw.Draw(img)
        d.ellipse([RW * 0.3, -RH * 0.6, RW * 0.62, RH * 0.75], fill=(214, 160, 140))
        d.polygon([(RW * 0.28, RH), (RW * 0.33, RH * 0.45), (RW * 0.58, RH * 0.4), (RW * 0.66, RH)], fill=(200, 30, 40))
        d.ellipse([RW * 0.62, RH * 0.1, RW * 0.8, RH * 1.2], fill=(40, 44, 70))
        d.ellipse([RW * 0.05, RH * 0.2, RW * 0.25, RH * 1.1], fill=(120, 140, 90))
        return img.filter(ImageFilter.GaussianBlur(6))
    img = Image.new('RGB', (RW, RH), (200, 196, 186))
    d = ImageDraw.Draw(img)
    for _ in range(70):
        x, y = rnd.randint(-80, RW), rnd.randint(-80, int(RH / 1.3))
        r = rnd.randint(30, 140)
        d.ellipse([x, y, x + r, y + r], fill=rnd.choice([(60, 100, 50), (80, 120, 60), (40, 70, 40), (110, 140, 70)]))
    d.polygon([(RW * 0.2, RH * 0.55), (RW * 0.7, RH * 0.5), (RW * 0.72, RH * 0.85), (RW * 0.25, RH * 0.9)], fill=(25, 25, 30))
    return img.filter(ImageFilter.GaussianBlur(3))


def roi_subtitle(img, lines):
    """White letters, 3 px black outline, soft dark shadow, centred low in the area as in the video."""
    w, h = img.size
    lh = 74
    y0 = h - 40 - lh * len(lines)
    shadow = Image.new('L', (w, h), 0)
    sd = ImageDraw.Draw(shadow)
    for i, l in enumerate(lines):
        sd.text((w // 2 + 3, y0 + lh * i + lh // 2 + 4), l, font=ROI_FONT, fill=190, anchor='mm', direction='rtl',
                language='ar', stroke_width=3, stroke_fill=190)
    shadow = shadow.filter(ImageFilter.GaussianBlur(3))
    img.paste(Image.new('RGB', (w, h), (8, 8, 10)), (0, 0), shadow)
    d = ImageDraw.Draw(img)
    for i, l in enumerate(lines):
        d.text((w // 2, y0 + lh * i + lh // 2), l, font=ROI_FONT, fill=(255, 255, 255), anchor='mm', direction='rtl',
               language='ar', stroke_width=3, stroke_fill=(0, 0, 0))
        mark_text(d, (w // 2, y0 + lh * i + lh // 2), l, ROI_FONT, 3)
    LAST_BOX[:] = [0, 0, 0, 0]


for name, lines, kind, seed in [
    ('26_roi_wake_tiles', ['هوليا. استيقظي'], 'tiles', 500),
    ('27_roi_last_worry_lace', ['هوليا هي آخر همي الآن'], 'lace', 501),
    ('28_roi_dialogue_red', ['- هوليا', '- ابتعدي يا جوليزار'], 'red_skin', 502),
    ('29_roi_help_garden', ['هوليا. ساعدوني', 'ماذا حدث؟'], 'garden', 503),
    ('30_roi_fine_tiles', ['بخير بخير أجل أنا بخير'], 'tiles', 504),
    ('31_roi_mom_lace', ['أريد أمي'], 'lace', 505),
]:
    img = roi_scene(kind, seed)
    roi_subtitle(img, lines)
    save(name, img, '\n'.join(lines), 'video mode area 1920×432, outlined white subtitle on a bright scene (' + kind + ')')

with open(os.path.join(OUT, 'truth.json'), 'w', encoding='utf-8') as f:
    json.dump(cases, f, ensure_ascii=False, indent=1)
print(len(cases), 'images')
