# Builds reference vectors by running the ORIGINAL Python functions from the user's Windows sources.
import ast, json, random, re, sys, types, unicodedata, logging
from difflib import SequenceMatcher

def load_funcs(path, names, extra_globals):
    src = open(path, encoding='utf-8').read()
    tree = ast.parse(src)
    keep = [n for n in tree.body if (isinstance(n, (ast.FunctionDef,)) and n.name in names) or
            (isinstance(n, ast.Assign) and any(getattr(t,'id',None) in names for t in n.targets))]
    mod = ast.Module(body=keep, type_ignores=[])
    g = dict(extra_globals)
    exec(compile(mod, path, 'exec'), g)
    return g

pro = load_funcs('/home/claude/src_zip/HardSubArabicOCR_v2_PRO.py',
                 {'norm_text','similarity','_clean_suspicious_numbers','arabic_filter','merge_entries','fmt','parse_ts'},
                 {'re':re,'unicodedata':unicodedata,'SequenceMatcher':SequenceMatcher})
class L:  # stub logger
    def info(self,*a,**k): pass
cli = load_funcs('/home/claude/src_zip/ocr_windows_v2.1.py',
                 {'NO_TEXT_PHRASES','DISALLOWED_CHARS_PATTERN','clean_ocr_output','merge_ocr_entries','_calculate_text_similarity'},
                 {'re':re,'json':json,'logger':L()})

random.seed(12345)
AR_WORDS = ['جنة','لأن','لن','كثيرًا','كثيراً','مرحبا','بِسْمِ','اللّهِ','الرَّحْمَنِ','جيهيننا','مدرسة','هذه','لا','أعرف',
            'ماذا','تقول؟','حسنًا،','يا','صديقي!','سـلام','٣٠','10','2026','93324','دقائق','إلى','اللقاء']
NOISE = ['HOTEL','POLICE','EXIT','KURUMLAR','Çok','güzel','abc','000 10','00','000','10 10','5 5','•','-','*','none','N/A',
         'no text','(موسيقى)','[ضحك]','…','–','—','_','‏','‎',' ','\t','ـــ','ًً','٫','،','؛','؟',
         '😀','#','%','"','\'','{x}','“اقتباس”']
def rand_line():
    parts=[]
    for _ in range(random.randint(0,8)):
        r=random.random()
        if r<0.6: parts.append(random.choice(AR_WORDS))
        else: parts.append(random.choice(NOISE))
    sep=random.choice([' ','  ',' ‏',''])
    return sep.join(parts)
def rand_text():
    n=random.randint(0,4)
    lines=[rand_line() for _ in range(n)]
    if lines and random.random()<0.3: lines.append(lines[0])  # duplicate Lens region
    if random.random()<0.15: lines.insert(0,'- '+random.choice(AR_WORDS))
    nl=random.choice(['\n','\r\n','\n\n'])
    return nl.join(lines)

texts=[rand_text() for _ in range(1500)]
texts += ['', ' ', 'none', 'لا يوجد نص عربي', '```json\n{"text_content": "مرحبا"}\n```', '{"text_content":"سلام","text":"نص"} json',
          'json [{"label":"أهلا"},{"label":"بك"}]', 'json {"other":1}', 'json {"text_content": "ابحث" broken',
          'json [1,2] { }', '﻿مرحبا﻿', '""', "''", '"()"', '٠٥ و 50', 'ـمرحبـاً', '000 10 جنة جنة', 'جنة جنة\nجنة جنة']

vec = {'norm':[], 'sus':[], 'arabic':[], 'clean':[], 'ratio':[], 'simpro':[], 'simstrict':[], 'mergepro':[], 'mergestrict':[]}
for t in texts:
    vec['norm'].append([t, pro['norm_text'](t)])
    vec['sus'].append([t, pro['_clean_suspicious_numbers'](t)])
    vec['arabic'].append([t, pro['arabic_filter'](t)])
    try:
        c = cli['clean_ocr_output'](t)
    except Exception as e:
        c = None   # Python crashes -> image skipped
    vec['clean'].append([t, c])

pool = [pro['arabic_filter'](t) for t in texts] + texts[:300]
for _ in range(1500):
    a=random.choice(pool); b=random.choice(pool)
    if random.random()<0.4 and a:
        # small OCR-like edit of a
        l=list(a); 
        for _ in range(random.randint(1,3)):
            if l and random.random()<0.5: del l[random.randrange(len(l))]
            else: l.insert(random.randrange(len(l)+1), random.choice('اأإةهنلي ً'))
        b=''.join(l)
    vec['ratio'].append([a,b,SequenceMatcher(None,a,b).ratio()])
    vec['simpro'].append([a,b,pro['similarity'](a,b)])
    vec['simstrict'].append([a,b,cli['_calculate_text_similarity'](a,b)])
# long strings to exercise autojunk (>=200)
for _ in range(60):
    a=''.join(random.choice('ابت ثجح') for _ in range(random.randint(150,400)))
    b=''.join(random.choice('ابت ثجح') for _ in range(random.randint(150,400)))
    vec['ratio'].append([a,b,SequenceMatcher(None,a,b).ratio()])

# merge vectors on an exact millisecond grid (times as ms ints, python gets seconds)
subs=[t for t in pool if t] 
for case in range(400):
    items=[]; t=random.randint(0,5000)
    base=random.choice(subs)
    for k in range(random.randint(1,25)):
        dur=random.choice([40,41,42,80,120,500,1000,2000,3000])
        txt = base if random.random()<0.5 else random.choice(subs)
        if random.random()<0.2: txt = txt[:-1] if len(txt)>2 else txt
        items.append([t, t+dur, txt])
        gap=random.choice([0,0,0,1,5,9,11,20,100,349,351,400,2000])
        t=t+dur+gap
        if random.random()<0.1: base=random.choice(subs)
    random.shuffle(items)
    py_items=[(a/1000.0,b/1000.0,x) for a,b,x in items]
    mp=pro['merge_entries'](list(py_items),gap=0.35)
    ms=cli['merge_ocr_entries'](list(py_items),3.0)
    vec['mergepro'].append([items,[[round(a*1000),round(b*1000),x] for a,b,x in mp]])
    vec['mergestrict'].append([items,[[round(a*1000),round(b*1000),x] for a,b,x in ms]])
json.dump(vec, open('/home/claude/tests/vectors.json','w',encoding='utf-8'), ensure_ascii=False)
print({k:len(v) for k,v in vec.items()})
