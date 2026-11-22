import ast, numpy as np, cv2, json
from PIL import Image, ImageEnhance, ImageFilter, ImageOps
V='/mnt/user-data/uploads/22bdff79-ocr_windows_v2.6.py'
t=ast.parse(open(V,encoding='utf-8').read())
keep=[n for n in t.body if isinstance(n,ast.FunctionDef) and n.name=='prepare_image_for_ocr']
g={'Image':Image,'ImageEnhance':ImageEnhance,'ImageFilter':ImageFilter,'ImageOps':ImageOps,'RESAMPLE_LANCZOS':Image.Resampling.LANCZOS}
exec(compile(ast.Module(body=keep,type_ignores=[]),V,'exec'),g)
prep=g['prepare_image_for_ocr']
rng=np.random.default_rng(5)
cases=[]
for i,(w,h) in enumerate([(1920,200),(1280,320),(800,150),(1920,1080),(640,360),(1500,260),(333,97),(70,40)]):
    bg=(rng.random((h,w,3))*255*0.6).astype(np.uint8)
    bg=cv2.GaussianBlur(bg,(0,0),3)
    cv2.putText(bg,"Subtitle test %d"%i,(w//8,int(h*0.8)),cv2.FONT_HERSHEY_DUPLEX,max(0.4,h/150),(0,0,0),6,cv2.LINE_AA)
    cv2.putText(bg,"Subtitle test %d"%i,(w//8,int(h*0.8)),cv2.FONT_HERSHEY_DUPLEX,max(0.4,h/150),(255,255,255),2,cv2.LINE_AA)
    im=Image.fromarray(bg[:,:,::-1].copy(),'RGB')
    im.save('img/in%d.png'%i)
    out=prep(im).convert('L')
    open('img/out%d.raw'%i,'wb').write(out.tobytes())
    cases.append([i,out.width,out.height])
json.dump(cases,open('img/cases.json','w'))
print(cases)
