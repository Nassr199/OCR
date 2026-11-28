import ast, json, random, re, unicodedata
from difflib import SequenceMatcher
def load(path, names, g0):
    t=ast.parse(open(path,encoding='utf-8').read())
    keep=[n for n in t.body if (isinstance(n,ast.FunctionDef) and n.name in names) or (isinstance(n,ast.Assign) and any(getattr(x,'id',None) in names for x in n.targets))]
    g=dict(g0); exec(compile(ast.Module(body=keep,type_ignores=[]),path,'exec'),g); return g
from typing import List
V='/mnt/user-data/uploads/22bdff79-ocr_windows_v2.6.py'
names={'ARABIC_DIACRITICS_PATTERN','ARABIC_TATWEEL','ARABIC_WORD_TOKEN_PATTERN','ARABIC_BASE_LETTER_PATTERN','strip_diacritics','strip_diacritics_and_tatweel','normalize_spaces','normalize_for_temporal_match','count_base_letters','count_diacritics','fix_fragmented_arabic_words','sanitize_word_diacritics','conservative_clean_diacritics','decompose_text_with_marks','compose_text_with_marks','build_group_consensus_text','stabilize_consecutive_diacritics','NO_TEXT_PHRASES','DISALLOWED_CHARS_PATTERN','clean_ocr_output'}
g=load(V,names,{'re':re,'json':json,'List':List})
random.seed(2026)
L='ابتثجحخدذرسشصضطظعغفقكلمنهويءأإآؤئة'
M=['َ','ُ','ِ','ّ','ْ','ً','ٌ','ٍ','ٰ']
def word():
    w=''
    for _ in range(random.randint(1,6)):
        w+=random.choice(L)
        for _ in range(random.choice([0,0,0,1,1,2])): w+=random.choice(M)
        if random.random()<0.05: w+='ـ'
    return w
def line():
    toks=[]
    for _ in range(random.randint(0,7)):
        r=random.random()
        if r<0.15:  # fragmented word
            toks+= [random.choice(L)+random.choice(['']+M) for _ in range(random.randint(2,5))]
        elif r<0.2: toks.append(random.choice(['HOTEL','123','؟','!','"',':','(موسيقى)','٣٠']))
        else: toks.append(word())
    return random.choice([' ','  ','  ']).join(toks)
texts=['\n'.join(line() for _ in range(random.randint(0,3))) for _ in range(1500)]
texts+=['','  ','سِ لِ م تِ','سَلَامٌ عَلَيْكُمْ','ـمـرحـبـا','أ ب ت ث ج','لن لأن','َبداية بحركة']
vec={'cons':[],'frag':[],'san':[],'norm':[],'consensus':[],'stab':[],'clean26':[]}
for t in texts:
    vec['cons'].append([t,g['conservative_clean_diacritics'](t)])
    vec['frag'].append([t,g['fix_fragmented_arabic_words'](t)])
    vec['norm'].append([t,g['normalize_for_temporal_match'](t)])
    vec['clean26'].append([t,g['conservative_clean_diacritics'](g['clean_ocr_output'](t))])
    for w in t.split()[:3]: vec['san'].append([w,g['sanitize_word_diacritics'](w)])
def variant(t):
    # same base, random harakat changes
    out=''
    for ch in t:
        if ch in M:
            r=random.random()
            if r<0.2: continue
            if r<0.3: out+=random.choice(M); continue
        out+=ch
        if ch in L and random.random()<0.1: out+=random.choice(M)
    return out
for _ in range(600):
    base=line() or word()
    grp=[base]+[variant(base) if random.random()<0.7 else base for _ in range(random.randint(0,4))]
    if random.random()<0.1: grp.append(line())
    vec['consensus'].append([grp,g['build_group_consensus_text'](grp)])
for _ in range(300):
    ents=[]; t=random.randint(0,1000); base=line() or word()
    for k in range(random.randint(1,14)):
        dur=random.choice([40,41,80,500,1200]); txt=variant(base) if random.random()<0.75 else (line() or word())
        if random.random()<0.15: base=line() or word()
        ents.append([t,t+dur,txt]); t=t+dur+random.choice([0,0,1,10,49,50,51,200])
    py=[(a/1000.0,b/1000.0,x) for a,b,x in ents]
    st=g['stabilize_consecutive_diacritics'](py)
    vec['stab'].append([ents,[[round(a*1000),round(b*1000),x] for a,b,x in st]])
json.dump(vec,open('vectors26.json','w',encoding='utf-8'),ensure_ascii=False)
print({k:len(v) for k,v in vec.items()})
