"""Synthetic hard-sub test videos for the SubScan tracker.

Simulates the subtitle band (bottom 30 %) of a 1920x1080 video at 25 fps, frame by frame:
moving/bright/static-busy backgrounds, scene cuts, white flashes, fades, codec (JPEG q=70)
artifacts that make Arabic dots/tashkeel flicker, gray semi-transparent and yellow text,
very short subtitles and back-to-back look-alike sentences.

Writes <out>.grid (uint8 luma grids, step 3, like Yuv.lumaGrid), <out>.json (ground truth) and a
few full-resolution RGB frames of one subtitle for the composite test.
usage: python3 -I gen_subvideo.py ar|en OUT_PREFIX
"""
import io, json, math, random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

LANG, OUT = sys.argv[1], sys.argv[2]
SEED = int(sys.argv[3]) if len(sys.argv) > 3 else (7 if LANG == 'ar' else 11)
FW = int(sys.argv[4]) if len(sys.argv) > 4 else 1920
FONTFILE = sys.argv[5] if len(sys.argv) > 5 else '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
random.seed(SEED)
rng = np.random.default_rng(SEED)
W, FPS = FW, 25
H = int(W * 9 / 16 * 0.3) // 2 * 2
STEP = max(1, W // 640)
FONT = ImageFont.truetype(FONTFILE, int(54 * W / 1920), layout_engine=ImageFont.Layout.RAQM)

AR = ["الآنَ يا أحمدُ، هلْ أنتَ بخيرٍ؟", "لا", "نعم", "الآن", "هل أنت متأكد؟", "هل أنت بخير؟",
      "سنذهبُ إلى المدينةِ غدًا صباحًا", "لا أعرفُ ماذا حدثَ هناك", "انتظرْ قليلًا من فضلك",
      "كانتْ تلكَ أجملَ أيامِ حياتي", "لماذا تأخرتَ كلَّ هذا الوقت؟", "اتبعني بسرعة!",
      "قالَ لي إنَّهُ سيعودُ قبلَ الغروب", "هذا ليس عدلًا", "أنا آسفٌ جدًّا",
      "المعركةُ لم تنتهِ بعد", "سأحميكَ مهما حدث", "أين كنتَ طوالَ الليل؟",
      "لن أسامحكَ أبدًا", "خذْ هذا المفتاحَ واحفظْهُ جيدًا", "هيا بنا", "شكرًا لك",
      "ما زالَ أمامنا طريقٌ طويل", "هل سمعتَ ذلك الصوت؟"]
EN = ["Now, Ahmed, are you okay?", "No", "Yes", "Now", "Are you sure?", "Are you okay?",
      "We will go to the city tomorrow morning", "I don't know what happened there",
      "Wait a moment, please", "Those were the best days of my life", "Why are you so late?",
      "Follow me, quickly!", "He told me he would return before sunset", "This isn't fair",
      "I'm so sorry", "The battle is not over yet", "I will protect you no matter what",
      "Where were you all night?", "Let's go", "Thank you"]
TEXTS = AR if LANG == 'ar' else EN
TWO_LINE_AR = [("قالَ لي إنَّهُ سيعودُ", "قبلَ غروبِ الشمسِ مباشرةً"), ("لا تقلقْ يا صديقي", "كلُّ شيءٍ سيكونُ بخير")]
TWO_LINE_EN = [("He told me he would", "come back before sunset"), ("Don't worry, my friend", "everything will be fine")]

def render(lines, color=(255, 255, 255), stroke=3):
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    n = len(lines)
    for k, t in enumerate(lines):
        y = H - int(70 * W / 1920) - (n - 1 - k) * int(74 * W / 1920)
        kw = dict(direction='rtl', language='ar') if LANG == 'ar' else dict(direction='ltr', language='en')
        d.text((W // 2, y), t, font=FONT, fill=color + (255,), anchor='mm', stroke_width=stroke, stroke_fill=(0, 0, 0, 255), **kw)
    return np.asarray(img).astype(np.float32) / 255.0

# ---------------------------------------------------------------- script
subs = []   # dict(start, end, lines, color, fade, flash)
t = 12
i = 0
def add(lines, dur, gap, color=(255, 255, 255), fade=0, flash=None, tag=''):
    global t
    subs.append(dict(start=t, end=t + dur, lines=lines, color=color, fade=fade, flash=flash, tag=tag))
    t += dur + gap

for rep in range(2):
    for k, txt in enumerate(TEXTS):
        dur = random.choice([30, 45, 60, 75, 90])
        gap = random.choice([0, 0, 3, 8, 15, 25])
        color = (255, 255, 255)
        tag = ''
        if k % 7 == 3: color, tag = (255, 236, 80), 'yellow'
        if k % 9 == 5: color, tag = (168, 168, 168), 'gray'
        fade = 5 if k % 5 == 2 else 0
        flash = None
        if k % 6 == 1 and dur >= 45: flash = (dur // 2, random.choice([3, 4, 6])); tag += ' flash'
        if txt in ("لا", "نعم", "No", "Yes"): dur, tag = 8, tag + ' short'   # 320 ms
        add([txt], dur, gap, color, fade, flash, tag.strip())
    pairs = TWO_LINE_AR if LANG == 'ar' else TWO_LINE_EN
    for a, b in pairs: add([a, b], 80, 10, tag='two-line')
    # look-alike sentences back to back (must stay separate)
    la = ("هل أنت بخير؟", "هل أنت متأكد؟") if LANG == 'ar' else ("Are you okay?", "Are you sure?")
    add([la[0]], 40, 0, tag='lookalike'); add([la[1]], 40, 12, tag='lookalike')
N = t + 20

# ---------------------------------------------------------------- backgrounds (scene list)
tex = rng.random((H // 6 + 4, W // 6 + 40)).astype(np.float32)
tex = np.kron(tex, np.ones((6, 6), np.float32))
static_city = np.zeros((H, W, 3), np.float32) + 0.12
for _ in range(260):   # lit windows: small static bright rectangles
    x, y = rng.integers(0, W - 20), rng.integers(0, H - 20)
    static_city[y:y + rng.integers(6, 16), x:x + rng.integers(6, 14)] = rng.uniform(0.75, 1.0)
scene_kinds = ['moving', 'sky', 'lights', 'city', 'moving', 'lights', 'sky']
scene_len = 140
if SEED not in (7, 11):
    random.shuffle(scene_kinds); scene_len = random.choice([97, 113, 167])
xs = np.linspace(0, 1, W, dtype=np.float32)[None, :]
ys = np.linspace(0, 1, H, dtype=np.float32)[:, None]

def background(f):
    kind = scene_kinds[(f // scene_len) % len(scene_kinds)]
    ph = f / FPS
    if kind == 'moving':
        base = 0.25 + 0.18 * np.sin(6 * xs + ph * 1.7) * np.cos(4 * ys - ph)
        off = (f * 4) % 40
        t2 = tex[:H, off:off + W] * 0.25
        img = np.stack([base + t2, base * 0.9 + t2, base * 1.2 + t2], -1)
    elif kind == 'sky':
        base = 0.68 + 0.12 * np.sin(2 * xs + ph * 0.6) + 0.05 * ys
        img = np.stack([base * 0.92, base * 0.97, base * 1.05], -1)
    elif kind == 'lights':
        img = np.zeros((H, W, 3), np.float32) + 0.1
        for k in range(5):
            cx = ((ph * (180 + 60 * k) + k * 400) % (W + 400)) - 200
            cy = int((60 + 50 * k) * W / 1920)
            g = np.exp(-(((xs * W - cx) / 60) ** 2 + ((ys * H - cy) / 25) ** 2))
            img += g[..., None] * 1.2
        x0 = int((ph * 900) % W)                       # bright thin neon bar sweeping across
        img[:, x0:x0 + 6] = 1.0
    else:
        img = static_city.copy()
    return np.clip(img, 0, 1)

layers = {}
truth = []
for s in subs:
    key = (tuple(s['lines']), s['color'])
    if key not in layers: layers[key] = render(s['lines'], s['color'])
    truth.append(dict(start=s['start'], end=s['end'], text=' / '.join(s['lines']), tag=s['tag']))

def frame(f):
    img = background(f)
    for s in subs:
        if s['start'] <= f < s['end']:
            a = 1.0
            if s['fade']:
                a = min(1.0, (f - s['start'] + 1) / s['fade'], (s['end'] - f) / s['fade'])
            lay = layers[(tuple(s['lines']), s['color'])]
            alpha = lay[..., 3:4] * a
            jitter = rng.uniform(0.9, 1.0)
            img = img * (1 - alpha) + lay[..., :3] * jitter * alpha
            if s['flash'] and s['start'] + s['flash'][0] <= f < s['start'] + s['flash'][0] + s['flash'][1]:
                img = img * 0.1 + 0.95
    rgb = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    buf = io.BytesIO(); rgb.save(buf, 'JPEG', quality=70); buf.seek(0)
    return np.asarray(Image.open(buf).convert('RGB'))

full_dump = []
target = next((s for s in subs if s['tag'] == '' and s['end'] - s['start'] >= 60 and (s['start'] // scene_len) % 7 == 2), None) or next(s for s in subs if s['end'] - s['start'] >= 45)
if SEED not in (7, 11):
    for s_ in subs: s_['lines'] = list(s_['lines'])
    random.shuffle(TEXTS)
with open(OUT + '.grid', 'wb') as fo:
    for f in range(N):
        a = frame(f).astype(np.float32)
        y = 16 + (65.481 * a[..., 0] + 128.553 * a[..., 1] + 24.966 * a[..., 2]) / 255.0
        g = np.clip(np.rint(y), 0, 255).astype(np.uint8)[::STEP, ::STEP][:H // STEP, :W // STEP]
        fo.write(g.tobytes())
        if target['start'] + 5 <= f < target['end'] - 5 and (f - target['start']) % 10 == 0 and len(full_dump) < 5:
            full_dump.append(f); Image.fromarray(frame_rgb := a.astype(np.uint8)).save(f'{OUT}_full_{len(full_dump)}.png')
json.dump(dict(fps=FPS, frames=N, gw=W // STEP, gh=H // STEP, subs=truth, composite=dict(frames=full_dump, text=' / '.join(target['lines']))),
          open(OUT + '.json', 'w'), ensure_ascii=False, indent=0)
print(LANG, 'frames', N, 'subs', len(truth), 'composite frames', full_dump)
