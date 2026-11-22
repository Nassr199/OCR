"""Reproduces the reported failure scene: Turkish-series style Arabic subtitles (white text in an
opaque black box, no outline) over bright skin-toned footage, while Turkish credits (serif, light,
soft shadow, three columns, one centred right under/around the box) fade in and out at the same
height, a "2." numeral, and a static channel logo inside the region.
Same output format as gen_subvideo.py.   usage: python3 -I gen_boxed.py OUT_PREFIX [seed]
"""
import io, json, random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1]
SEED = int(sys.argv[2]) if len(sys.argv) > 2 else 5
JUNK = len(sys.argv) > 3 and sys.argv[3] == 'junk'
FS = int(sys.argv[4]) if len(sys.argv) > 4 else 58
random.seed(SEED); rng = np.random.default_rng(SEED)
W, H, FPS, STEP = 1920, 324, 30, 3
AR = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', int(sys.argv[4]) if len(sys.argv) > 4 else 58, layout_engine=ImageFont.Layout.RAQM)
SERIF = '/usr/share/fonts/truetype/freefont/FreeSerifBold.ttf'
CR_BIG = ImageFont.truetype(SERIF, 52); CR_SMALL = ImageFont.truetype(SERIF, 30)

BOXES = {}
def boxed(lines):
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    n = len(lines); ub = [W, H, 0, 0]
    for k, t in enumerate(lines):
        y = H - int(64 * FS / 58) - (n - 1 - k) * int(78 * FS / 58)
        bb = d.textbbox((W // 2, y), t, font=AR, anchor='mm', direction='rtl', language='ar')
        d.rectangle((bb[0] - 16, bb[1] - 14, bb[2] + 16, bb[3] + 14), fill=(0, 0, 0, 255))
        ub = [min(ub[0], bb[0]), min(ub[1], bb[1]), max(ub[2], bb[2]), max(ub[3], bb[3])]
        d.text((W // 2, y), t, font=AR, fill=(255, 255, 255, 255), anchor='mm', direction='rtl', language='ar')
    BOXES[tuple(lines)] = [v // STEP for v in ub]
    return np.asarray(img).astype(np.float32) / 255.0

def credits(cols):
    """cols: list of (x_center, role, name)"""
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    for x, role, name in cols:
        d.text((x, H - 120), role, font=CR_SMALL, fill=(236, 228, 218, 235), anchor='mm')
        d.text((x, H - 70), name, font=CR_BIG, fill=(240, 232, 222, 245), anchor='mm')
    a = np.asarray(img).astype(np.float32) / 255.0
    sh = Image.fromarray((a[..., 3] * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3))
    shadow = np.asarray(sh).astype(np.float32)[..., None] / 255.0 * 0.45
    return a, np.roll(np.roll(shadow, 3, 0), 3, 1)

def numeral():
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.text((W // 2, 40), "2.", font=CR_BIG, fill=(245, 245, 245, 255), anchor='mm', stroke_width=2, stroke_fill=(30, 30, 30, 255))
    return np.asarray(img).astype(np.float32) / 255.0

LOGO = Image.new('RGBA', (W, H), (0, 0, 0, 0)); ImageDraw.Draw(LOGO).text((W - 150, 40), "TV8", font=CR_BIG, fill=(255, 255, 255, 170), anchor='mm')
LOGO = np.asarray(LOGO).astype(np.float32) / 255.0

TEXTS = ["ماذا حدث ؟", "حبيبي، هل أنت بخير؟", "أبي", "لا تتركني وحدي", "سأعود قريبًا", "هل تسمعني؟",
         "لم أقصد ذلك", "أين أمي؟", "انتظري", "كل شيء سيكون بخير", "لماذا فعلتَ هذا؟", "أحبكِ"]
subs = []; t = 20
for rep in range(3):
    for k, txt in enumerate(TEXTS):
        dur = random.choice([38, 60, 75, 95, 120]) if txt != "أبي" else 45
        gap = random.choice([0, 6, 14, 30, 45])
        subs.append(dict(start=t, end=t + dur, lines=[txt], tag='boxed')); t += dur + gap
    subs.append(dict(start=t, end=t + 90, lines=["لا تقلقي يا حبيبتي", "سأكون معكِ دائمًا"], tag='boxed two-line')); t += 100
N = t + 30
# credits blocks: fade in 9 frames, stay, fade out 9 — deliberately overlapping subtitle starts/ends
roles = [("Yardımcı Yönetmen", "Gökhan Yılmaz"), ("Yapım Koordinatörü", "Damla Güneş"), ("2. Ekip Görüntü Yönetmeni", "Tuğberk Süel"),
         ("Kurgu", "Selin Aksoy"), ("Müzik", "Toygar Işıklı"), ("Senaryo", "Ece Yörenç")]
cred = []
for s in subs[::3]:
    c0 = s['start'] + random.choice([-25, -8, 10, 18]); c1 = c0 + random.choice([70, 100, 130])
    cols = [(int(W * 0.26), *random.choice(roles)), (W // 2, *random.choice(roles)), (int(W * 0.74), *random.choice(roles))]
    cred.append(dict(start=c0, end=c1, layer=credits(cols), num=random.random() < 0.5))
NUM = numeral()
layers = {}
for s in subs: layers[tuple(s['lines'])] = boxed(s['lines'])

def ornament():
    img = Image.new('RGBA', (220, 150), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    for k in range(9):
        d.ellipse((10 + k * 22, 20 + (k % 3) * 30, 28 + k * 22, 40 + (k % 3) * 30), fill=(250, 235, 190, 255), outline=(40, 30, 10, 255), width=3)
        d.line((19 + k * 22, 40 + (k % 3) * 30, 25 + k * 22, 130), fill=(245, 225, 170, 255), width=4)
    for k in range(6):   # teeth-like row
        d.rectangle((15 + k * 32, 120, 38 + k * 32, 146), fill=(250, 250, 245, 255), outline=(25, 15, 15, 255), width=4)
    return np.asarray(img).astype(np.float32) / 255.0
ORN = ornament() if JUNK else None
xs = np.linspace(0, 1, W, dtype=np.float32)[None, :]; ys = np.linspace(0, 1, H, dtype=np.float32)[:, None]
def background(f):
    scene = (f // 160) % 4; ph = f / FPS
    if scene in (0, 2):   # bright skin / face close-up, slowly moving
        base = 0.62 + 0.12 * np.sin(3 * xs + ph * 0.7 + scene) * np.cos(2 * ys - ph * 0.4) + 0.06 * np.sin(17 * xs + ph)
        img = np.stack([base * 1.08, base * 0.86, base * 0.74], -1)
    elif scene == 1:      # dark jacket / hair
        base = 0.12 + 0.06 * np.sin(5 * xs - ph) + 0.04 * ys
        img = np.stack([base, base, base * 1.1], -1)
    else:                 # greenish blurred garden
        base = 0.45 + 0.15 * np.sin(4 * xs + ph * 1.3) * np.sin(6 * ys + ph)
        img = np.stack([base * 0.85, base * 0.95, base * 0.6], -1)
    return np.clip(img, 0, 1)

def frame(f):
    img = background(f)
    for c in cred:
        if c['start'] <= f < c['end']:
            a = min(1.0, (f - c['start'] + 1) / 9, (c['end'] - f) / 9)
            lay, sh = c['layer']
            img = img * (1 - sh * a)
            al = lay[..., 3:4] * a
            img = img * (1 - al) + lay[..., :3] * al
            if c['num']:
                al2 = NUM[..., 3:4] * a; img = img * (1 - al2) + NUM[..., :3] * al2
    al = LOGO[..., 3:4]; img = img * (1 - al) + LOGO[..., :3] * al
    if JUNK and (f // 160) % 4 in (0, 2):     # in the bright close-up shots, like the earring / teeth
        ox = int(1180 + 40 * np.sin(f / 23.0)); oy = int(150 + 6 * np.sin(f / 9.0))
        hh, ww = ORN.shape[:2]; y1 = min(H, oy + hh); x1 = min(W, ox + ww)
        sub = ORN[:y1 - oy, :x1 - ox]; al = sub[..., 3:4]
        img[oy:y1, ox:x1] = img[oy:y1, ox:x1] * (1 - al) + sub[..., :3] * al
    for s in subs:
        if s['start'] <= f < s['end']:
            lay = layers[tuple(s['lines'])]; al = lay[..., 3:4]
            img = img * (1 - al) + lay[..., :3] * al
    rgb = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    buf = io.BytesIO(); rgb.save(buf, 'JPEG', quality=72); buf.seek(0)
    return np.asarray(Image.open(buf).convert('RGB'))

dump = []; target = next(s for s in subs if s['lines'] == ["أبي"])
with open(OUT + '.grid', 'wb') as fo:
    for f in range(N):
        a = frame(f).astype(np.float32)
        y = 16 + (65.481 * a[..., 0] + 128.553 * a[..., 1] + 24.966 * a[..., 2]) / 255.0
        fo.write(np.clip(np.rint(y), 0, 255).astype(np.uint8)[::STEP, ::STEP][:H // STEP, :W // STEP].tobytes())
        if target['start'] + 3 <= f < target['end'] - 3 and (f - target['start']) % 8 == 0 and len(dump) < 5:
            dump.append(f); Image.fromarray(a.astype(np.uint8)).save(f'{OUT}_full_{len(dump)}.png')
truth = [dict(start=s['start'], end=s['end'], box=BOXES[tuple(s['lines'])], text=' / '.join(s['lines']), tag=s['tag'] + (' credits' if any(c['start'] < s['end'] and c['end'] > s['start'] for c in cred) else '')) for s in subs]
json.dump(dict(fps=FPS, frames=N, gw=W // STEP, gh=H // STEP, subs=truth, credits=[[c['start'], c['end']] for c in cred],
               composite=dict(frames=dump, text="أبي")), open(OUT + '.json', 'w'), ensure_ascii=False, indent=0)
print('frames', N, 'subs', len(truth), 'credit blocks', len(cred), 'dump', dump)
