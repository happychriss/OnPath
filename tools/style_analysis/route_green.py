"""Route-band colour analysis for the OnPath map (see docs/design/map-colour-analysis.md).

Finds the green for the transparent route band that stays most visible on the WEAKEST ground of the map
(forest, forest in hill shadow, meadow, farmland, rock, ...) while the black path drawn on top keeps a
minimum luminance contrast. Pure Python, no dependencies. Run: python3 tools/style_analysis/route_green.py

Metrics
- band visibility: CIEDE2000 colour difference between (ground) and (ground with the band composited on top)
- path legibility: WCAG 2 contrast ratio between the path ink #33251a and the composited band
"""
import math
def h2r(h): return [int(h[i:i+2],16) for i in (1,3,5)]
def lin(v): v/=255; return v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4
def lab(c):
    r,g,b=[lin(v) for v in c]
    x=(r*.4124+g*.3576+b*.1805)/.95047; y=r*.2126+g*.7152+b*.0722; z=(r*.0193+g*.1192+b*.9505)/1.08883
    f=lambda t: t**(1/3) if t>.008856 else 7.787*t+16/116
    x,y,z=f(x),f(y),f(z); return (116*y-16,500*(x-y),200*(y-z))
def de2000(c1,c2):
    L1,a1,b1=lab(c1);L2,a2,b2=lab(c2)
    C1=math.hypot(a1,b1);C2=math.hypot(a2,b2);Cb=(C1+C2)/2
    G=.5*(1-math.sqrt(Cb**7/(Cb**7+25**7)));a1p=(1+G)*a1;a2p=(1+G)*a2
    C1p=math.hypot(a1p,b1);C2p=math.hypot(a2p,b2)
    h1=math.degrees(math.atan2(b1,a1p))%360;h2=math.degrees(math.atan2(b2,a2p))%360
    dL=L2-L1;dC=C2p-C1p
    dh=h2-h1
    if C1p*C2p==0: dh=0
    elif dh>180: dh-=360
    elif dh<-180: dh+=360
    dH=2*math.sqrt(C1p*C2p)*math.sin(math.radians(dh/2))
    Lb=(L1+L2)/2;Cbp=(C1p+C2p)/2
    hb=(h1+h2)/2 if abs(h1-h2)<=180 else (h1+h2+360)/2
    if C1p*C2p==0: hb=h1+h2
    T=1-.17*math.cos(math.radians(hb-30))+.24*math.cos(math.radians(2*hb))+.32*math.cos(math.radians(3*hb+6))-.2*math.cos(math.radians(4*hb-63))
    dt=30*math.exp(-((hb-275)/25)**2);RC=2*math.sqrt(Cbp**7/(Cbp**7+25**7))
    SL=1+.015*(Lb-50)**2/math.sqrt(20+(Lb-50)**2);SC=1+.045*Cbp;SH=1+.015*Cbp*T;RT=-math.sin(math.radians(2*dt))*RC
    return math.sqrt((dL/SL)**2+(dC/SC)**2+(dH/SH)**2+RT*(dC/SC)*(dH/SH))
def lum(c): r,g,b=[lin(v) for v in c]; return .2126*r+.7152*g+.0722*b
def wcag(a,b): la,lb=sorted([lum(a),lum(b)],reverse=True); return (la+.05)/(lb+.05)
def mix(a,b,t): return [a[i]*(1-t)+b[i]*t for i in range(3)]
def lch2rgb(L,C,H):
    a=C*math.cos(math.radians(H));b=C*math.sin(math.radians(H))
    fy=(L+16)/116;fx=fy+a/500;fz=fy-b/200
    inv=lambda t: t**3 if t**3>.008856 else (t-16/116)/7.787
    x,y,z=inv(fx)*.95047,inv(fy),inv(fz)*1.08883
    r=3.2406*x-1.5372*y-.4986*z;g=-.9689*x+1.8758*y+.0415*z;bb=.0557*x-.2040*y+1.0570*z
    def enc(v):
        v=12.92*v if v<=.0031308 else 1.055*v**(1/2.4)-.055
        return v*255
    out=[enc(v) for v in (r,g,bb)]
    if any(v<-.5 or v>255.5 for v in out): return None
    return [min(255,max(0,v)) for v in out]

SHADOW_BLUE = [50, 62, 96]      # hill shadow until 0.6.9
SHADOW_GREEN = [40, 70, 35]     # proposal: greenish, applied at 0.7 x strength
GROUNDS = {
    "today":   {"wood": "#a9cd8b", "grass": "#d2e2b4", "leisure": "#cfe0b5", "wetland": "#d4e6d0", "farmland": "#eeecd5",
                "land": "#f4f1e8", "residential": "#e3ddd5", "rock": "#e7e2d9", "sand": "#efe6c9", "ice": "#f6fafc"},
    "lighter": {"wood": "#c3dcab", "grass": "#dfeacb", "leisure": "#d9e8c4", "wetland": "#d4e6d0", "farmland": "#f1f0de",
                "land": "#f6f4ec", "residential": "#e3ddd5", "rock": "#e7e2d9", "sand": "#efe6c9", "ice": "#f6fafc"},
}
INK = h2r("#33251a")


def hexs(c):
    return '#' + ''.join('%02x' % round(v) for v in c)


def grounds(name, shadow, strength):
    g = {k: h2r(v) for k, v in GROUNDS[name].items()}
    g["wood in shadow"] = mix(g["wood"], shadow, strength)
    g["grass in shadow"] = mix(g["grass"], shadow, strength)
    return g


def score(rgb, alpha, G):
    worst, path = (1e9, None), 1e9
    for k, bg in G.items():
        comp = mix(bg, rgb, alpha)
        d = de2000(comp, bg)
        if d < worst[0]:
            worst = (d, k)
        path = min(path, wcag(comp, INK))
    return worst, path


def optimum(alpha, G, path_floor, hues=range(120, 151, 3)):
    best = None
    for L in range(20, 92, 2):
        for C in range(20, 85, 4):
            for H in hues:
                rgb = lch2rgb(L, C, H)
                if rgb is None:
                    continue
                (d, k), p = score(rgb, alpha, G)
                if p >= path_floor and (best is None or d > best[0]):
                    best = (d, k, p, hexs(rgb))
    return best


if __name__ == "__main__":
    for gname, sname, sh, s in (("today", "blue shadow", SHADOW_BLUE, .30), ("lighter", "green shadow", SHADOW_GREEN, .25 * .7)):
        G = grounds(gname, sh, s)
        print(f"== ground {gname}, {sname}")
        for label, h in (("today light", "#c9f29b"), ("yellow-green", "#d6f25a"), ("today dark", "#3e9a4e"),
                         ("dark", "#2e7d32"), ("chosen #168403", "#168403"), ("very dark", "#014e08")):
            for a in (.4, .75):
                (d, k), p = score(h2r(h), a, G)
                print(f"  {label:15} alpha {a:.2f}: band dE2000 {d:5.1f} (weakest: {k:15}) path contrast {p:4.1f}")
        for floor in (3.5, 4.5):
            b = optimum(.4, G, floor)
            print(f"  optimum alpha 0.40, path >= {floor}: {b[3]} band {b[0]:.1f} ({b[1]}) path {b[2]:.1f}")
