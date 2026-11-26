# Synthetic hard-sub video: moving/flashing background + outlined subtitles with fades, back-to-back changes, yellow text.
import cv2, numpy as np, json, random
random.seed(7); np.random.seed(7)
W,H,FPS=1280,720,24
x0,y0,x1,y1 = 0, int(H*0.75), W, H
step = max(1, (x1-x0)//480)
subs = [  # (start_frame, end_frame, text, color, fade)
 (24, 80, "Hello my friend", (255,255,255), 0),
 (80, 130, "Where are you going", (255,255,255), 0),     # back-to-back change
 (150, 210, "Hello my friend", (255,255,255), 4),       # repeat after gap, fade in/out
 (230, 300, "Yellow line here", (0,230,255), 0),         # yellow (BGR)
 (300, 312, "x", (255,255,255), 0),                      # ultra short tiny (should vanish or tiny)
 (330, 420, "Two lines A", (255,255,255), 0),
 (430, 520, "Explosion behind text", (255,255,255), 0),  # bright flash background frames 450-470
]
N=560
def background(i):
    yy,xx = np.mgrid[0:H,0:W]
    g = (127 + 60*np.sin((xx+i*9)/80.0) + 40*np.cos((yy-i*5)/60.0)).clip(0,255).astype(np.uint8)
    img = cv2.merge([g, (g*0.8).astype(np.uint8), (255-g)//2])
    # moving bright rectangle in the subtitle zone (car headlights etc.)
    cx = (i*17) % (W+200) - 100
    cv2.rectangle(img, (cx, 560), (cx+120, 640), (235,235,235), -1)
    if 450 <= i <= 470: img = cv2.addWeighted(img, 0.3, np.full_like(img, 250), 0.7, 0)
    noise = np.random.randint(-12, 12, img.shape, dtype=np.int16)
    return (img.astype(np.int16)+noise).clip(0,255).astype(np.uint8)
def draw_sub(img, text, color, alpha):
    over = img.copy()
    font=cv2.FONT_HERSHEY_DUPLEX; sc=1.6; th=3
    (tw,tht),_=cv2.getTextSize(text,font,sc,th)
    org=((W-tw)//2, H-60)
    cv2.putText(over,text,org,font,sc,(0,0,0),th+6,cv2.LINE_AA)
    cv2.putText(over,text,org,font,sc,color,th,cv2.LINE_AA)
    return cv2.addWeighted(over, alpha, img, 1-alpha, 0)
grids=[]
for i in range(N):
    img=background(i)
    for (s,e,t,c,fade) in subs:
        if s<=i<e:
            a=1.0
            if fade:
                if i<s+fade: a=(i-s+1)/(fade+1)
                if i>=e-fade: a=min(a,(e-i)/(fade+1))
            img=draw_sub(img,t,c,a)
    ok,buf=cv2.imencode('.jpg',img,[cv2.IMWRITE_JPEG_QUALITY,70]); img=cv2.imdecode(buf,1)
    Y=cv2.cvtColor(img,cv2.COLOR_BGR2YUV)[:,:,0]
    g=Y[y0:y1:step, x0:x1:step]
    grids.append(g)
gh,gw=grids[0].shape
with open('/home/claude/tests/grids.bin','wb') as f:
    for g in grids: f.write(np.ascontiguousarray(g).tobytes())
json.dump({'w':gw,'h':gh,'n':N,'fps':FPS,'subs':[[s,e,t] for s,e,t,c,f in subs]}, open('/home/claude/tests/grids.json','w'))
cv2.imwrite('/home/claude/tests/sample_frame.png', draw_sub(background(460),"Explosion behind text",(255,255,255),1.0))
print('grid', gw, gh, 'frames', N)
