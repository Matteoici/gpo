"""Aperçu vidéo du Fûga : rejoue la chorégraphie (mêmes timings que FugaFx) avec les textures du pack."""
import numpy as np, os, sys, math, subprocess, random
from PIL import Image, ImageDraw

TEX = sys.argv[1]; OUTV = sys.argv[2]
W, H = 640, 360
random.seed(3)
cache = {}

def tex(name):
    if name not in cache:
        p = os.path.join(TEX, name + '.png')
        cache[name] = Image.open(p).convert('RGBA')
    return cache[name]

def frame_of(name, tick, ft=2):
    im = tex(name)
    if im.height > im.width:
        n = im.height // im.width
        f = (tick // ft) % n
        return im.crop((0, f * im.width, im.width, (f + 1) * im.width))
    return im

# ----------------------------------------------------------------- scène
sprites = []   # dict: kind, model, t0, t1, pos(t), size(t)->(sx,sy), rot(t), ft
def add(kind, model, t0, t1, pos, size, rot=lambda t: 0.0, ft=2, frames=None, every=1):
    sprites.append(dict(kind=kind, model=model, t0=t0, t1=t1, pos=pos, size=size, rot=rot, ft=ft, frames=frames, every=every))

def ease(a, b, t0, dur, t):
    u = min(1, max(0, (t - t0) / dur))
    u = 1 - (1 - u) ** 3
    return a + (b - a) * u

def pop(kind, model, t0, at, sx, sy, grow=2, hold=1, shrink=4):
    def size(t):
        if t < t0 + 1 + grow:
            u = ease(0.05, 1, t0 + 1, grow, t)
        elif t < t0 + 1 + grow + hold:
            u = 1
        else:
            u = ease(1, 0.02, t0 + 1 + grow + hold, shrink, t)
        return (sx * u, sy * u)
    add(kind, model, t0, t0 + 2 + grow + hold + shrink, lambda t: at, size)

def flip(kind, at, prefix, n, every, s0, s1, t0, posf=None):
    add(kind, prefix, t0, t0 + n * every, posf or (lambda t: at),
        lambda t: (ease(s0, s1, t0 + 1, n * every, t),) * 2, frames=[prefix + str(i) for i in range(n)], every=every)

CASTER = np.array([0.0, 0.0, 0.0])
DIR = np.array([0.0, 0.08, 1.0]); DIR /= np.linalg.norm(DIR)
RT = np.cross(DIR, [0, 1, 0]); RT /= np.linalg.norm(RT); UP = np.cross(RT, DIR)
SPEED = 1.25; RANGE = 50
REL = 124
FLY_TICKS = int(RANGE / SPEED)
IMP = REL + FLY_TICKS
IMPACT = CASTER + np.array([0, 1.6, 0]) + DIR * RANGE
GC = np.array([IMPACT[0], 0.0, IMPACT[2]])
COLH = 120
rnd = random.Random(5)

# ---- incantation
chest = CASTER + [0, 1.1, 0]
pop('bb', 'glow_white', 0, chest, 6, 6, 1, 1, 6)
add('bb', 'glow', 2, REL + 4, lambda t: chest, lambda t: (ease(0.5, 6, 2, 8, t) * (1 + 0.06 * math.sin(t * 0.3)),) * 2)
add('bb', 'aura', 2, REL + 4, lambda t: chest, lambda t: (ease(0.5, 5, 2, 10, t),) * 2, rot=lambda t: t * 0.09)
flip('bb', CASTER + [0, 1.4, 0], 'burst_', 16, 1, 3, 9, 52)
pop('flat', 'shock', 52, CASTER + [0, 0.1, 0], 12, 12, 6, 0, 4)
add('flat', 'circle', 55, REL + 4, lambda t: CASTER + [0, 0.06, 0], lambda t: (ease(0.2, 10, 56, 6, t),) * 2, rot=lambda t: t * 0.05)
add('flat', 'circle2', 55, REL + 4, lambda t: CASTER + [0, 0.08, 0], lambda t: (ease(0.2, 7, 56, 6, t),) * 2, rot=lambda t: -t * 0.08)
for k in range(58, REL, 5):
    a = rnd.uniform(0, 6.28); d = rnd.uniform(1.2, 3.6)
    flip('bb', CASTER + [math.cos(a) * d, 0.7, math.sin(a) * d], 'burst_', 16, 1, 0.8, 2.4, k)
for k in range(57, REL, 3):
    a = rnd.uniform(0, 6.28); d = rnd.uniform(0.8, 3.2)
    at = CASTER + [math.cos(a) * d, rnd.uniform(0.4, 3.8), math.sin(a) * d]
    s = rnd.uniform(0.9, 2.2)
    pop('bb', rnd.choice(['star', 'star', 'star_dark']), k, at, s, s)
for k in range(60, REL, 8):
    at = CASTER + [rnd.uniform(-1.5, 1.5), rnd.uniform(0.6, 3.5), rnd.uniform(-1.5, 1.5)]
    pop('bb', 'flare', k, at, rnd.uniform(4, 9), 0.35, 2, 2, 5)
for k in range(58, REL, 6):
    a = rnd.uniform(0, 6.28); d = rnd.uniform(3, 5)
    at = CASTER + [math.cos(a) * d, 0.6, math.sin(a) * d]
    flip('bb', at, 'dust_', 12, 2, 1.2, 2.6, k)
flip('bb', CASTER + [0, 1.6, 0] + DIR * 1.6, 'burst_', 16, 1, 2, 6, 116)

# ---- vol
def fpos(t):
    u = min(FLY_TICKS, max(0, t - REL))
    return CASTER + [0, 1.6, 0] + DIR * (2 + u * SPEED)
add('bb', 'glow', REL, IMP, fpos, lambda t: (7, 7))
add('bb', 'core', REL, IMP, fpos, lambda t: (3, 3), rot=lambda t: t * 0.3)
add('bb', 'glow_white', REL, IMP, fpos, lambda t: (2.6, 2.6))
for k in range(REL + 1, IMP):
    for i in range(3):
        def pt(kk, i=i):
            a = kk * 0.42 + math.pi * 2 * i / 3; rad = 0.7 + min(0.5, (kk - REL) * 0.02)
            return fpos(kk) + RT * math.cos(a) * rad + UP * math.sin(a) * rad
        a0, a1 = pt(k - 1), pt(k)
        ext = (a1 - a0) * 0.45; a0, a1 = a0 - ext, a1 + ext
        add('ribbon', 'ribbon_red' if i == 2 else 'ribbon', k, k + 15, (lambda a0, a1: lambda t: (a0, a1))(a0, a1),
            (lambda k: lambda t: (ease(0.75, 0.05, k + 1, 13, t),) * 2)(k), ft=1)
    if k % 4 == 0:
        pop('bb', 'star', k, fpos(k), 1.4, 1.4, 1, 0, 3)
    if k % 2 == 0:
        flip('bb', fpos(k) - DIR * 1.2, 'burst_', 16, 1, 1.6, 3.8, k)
    if k % 3 == 0:
        flip('bb', fpos(k) + np.array([rnd.uniform(-.3, .3), rnd.uniform(-.3, .3), 0]), 'dust_', 12, 2, 0.8, 2.2, k)

# ---- impact
flip('bb', GC + [0, 6, 0], 'boom_', 16, 2, 14, 30, IMP)
for j in range(3):
    off = np.array([rnd.uniform(-7, 7), rnd.uniform(2, 5), rnd.uniform(-7, 7)])
    flip('bb', GC + off, 'burst_', 16, 1, 6, 15, IMP + 2 + j * 3)
flip('flat', GC + [0, 0.15, 0], 'splash_', 12, 2, 6, 36, IMP)
pop('flat', 'shock', IMP, GC + [0, 0.3, 0], 46, 46, 10, 0, 5)
pop('bb', 'shock', IMP, GC + [0, 4, 0], 30, 30, 8, 0, 4)
pop('bb', 'glow_white', IMP, GC + [0, 4, 0], 26, 26, 1, 2, 8)
pop('flat', 'ring', IMP + 8, GC + [0, 0.4, 0], 30, 30, 10, 0, 6)
for k in range(IMP + 6, IMP + 64, 2):
    at = GC + [rnd.uniform(-8, 8), rnd.uniform(1, 6), rnd.uniform(-8, 8)]
    s = rnd.uniform(8, 14)
    flip('bb', at, 'smoke_', 12, 3, s * 0.4, s, k, posf=(lambda at, k: lambda t: at + [0, (t - k) * 0.18, 0])(at, k))
for k in range(IMP + 44, IMP + 64, 2):
    y = (k - IMP - 44) * 2.2
    pop('bb', rnd.choice(['star_red', 'star_red', 'star_dark']), k, GC + [rnd.uniform(-1, 1), y + 2, rnd.uniform(-1, 1)], 2.4, 2.4, 1, 1, 3)
    pop('bb', 'flare_red', k, GC + [0, y + 1, 0], rnd.uniform(10, 18), 0.6, 1, 2, 4)
C0 = IMP + 64
pop('bb', 'glow_white', C0, GC + [0, 10, 0], 60, 60, 1, 2, 10)
NSEG = 8
for s in range(NSEG):
    h = COLH / NSEG
    add('col', 'column', C0, C0 + 96, (lambda s: lambda t: GC + [0, -4 + h * s + h / 2, 0])(s),
        lambda t: (ease(0.3, 10, C0 + 1, 4, t) if t < C0 + 86 else ease(10, 0.05, C0 + 86, 10, t), h), ft=1)
    add('col', 'column_core', C0, C0 + 96, (lambda s: lambda t: GC + [0, -4 + h * s + h / 2, 0])(s),
        lambda t: (ease(0.2, 2.4, C0 + 1, 4, t) if t < C0 + 86 else ease(2.4, 0.05, C0 + 86, 10, t), h), ft=1)
add('col', 'column_glow', C0, C0 + 96, lambda t: GC + [0, COLH / 2 - 4, 0], lambda t: (ease(0.3, 16, C0 + 1, 5, t) if t < C0 + 86 else ease(16, .05, C0 + 86, 10, t), COLH))
for i in range(8):
    a = math.pi * 2 * i / 8
    add('vert', 'basefire', C0, C0 + 96, (lambda a: lambda t: GC + [math.cos(a) * 5.5, 2.2, math.sin(a) * 5.5])(a), lambda t: (ease(0.3, 5.5, C0 + 2, 5, t),) * 2)
pop('flat', 'shock', C0, GC + [0, 0.3, 0], 40, 40, 8, 0, 5)
for k in range(C0 + 4, C0 + 86, 2):
    pop('bb', rnd.choice(['flare', 'flare', 'flare_red']), k, GC + [0, rnd.uniform(4, COLH * 0.75), 0], rnd.uniform(20, 40), rnd.uniform(1.2, 2.0), 1, 2, 5)
for k in range(C0 + 4, C0 + 86, 3):
    pop('bb', rnd.choice(['star', 'star', 'star_dark']), k, GC + [rnd.uniform(-5, 5), rnd.uniform(3, COLH * 0.6), rnd.uniform(-5, 5)], 3, 3, 1, 1, 4)
END = C0 + 100

# ----------------------------------------------------------------- caméra et rendu
def camera(t):
    # incantation : derrière le lanceur ; vol : suit la flèche ; impact : loin, de côté
    if t < REL:
        eye = CASTER + np.array([4.5, 3.2, -9]); look = CASTER + [0, 1.5, 2]
    elif t < IMP:
        p = fpos(t); eye = p + np.array([5, 3, -12]); look = p + DIR * 4
    else:
        eye = GC + np.array([22, 10, -55]); look = GC + [0, 22 if t > C0 else 6, 0]
    return eye, look

def basis(eye, look):
    f = look - eye; f = f / np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0]); r /= np.linalg.norm(r)
    u = np.cross(r, f)
    return f, r, u

FOCAL = W / 2 / math.tan(math.radians(70) / 2)

def project(p, eye, f, r, u):
    d = p - eye; z = d @ f
    if z < 0.3: return None, z
    return (W / 2 + (d @ r) / z * FOCAL, H / 2 - (d @ u) / z * FOCAL), z

def homography(src, dst):
    A = []; B = []
    for (x, y), (X, Y) in zip(dst, src):
        A.append([x, y, 1, 0, 0, 0, -X * x, -X * y]); B.append(X)
        A.append([0, 0, 0, x, y, 1, -Y * x, -Y * y]); B.append(Y)
    return np.linalg.solve(np.array(A, float), np.array(B, float))

def draw_quad(img, texim, corners):
    xs = [c[0] for c in corners]; ys = [c[1] for c in corners]
    x0, x1, y0, y1 = int(max(0, min(xs))), int(min(W, max(xs) + 1)), int(max(0, min(ys))), int(min(H, max(ys) + 1))
    if x1 <= x0 or y1 <= y0 or (x1 - x0) * (y1 - y0) > W * H * 4: return
    tw, th = texim.size
    src = [(0, 0), (tw, 0), (tw, th), (0, th)]
    dst = [(c[0] - x0, c[1] - y0) for c in corners]
    try:
        coef = homography(src, dst)
    except np.linalg.LinAlgError:
        return
    warped = texim.transform((x1 - x0, y1 - y0), Image.PERSPECTIVE, tuple(coef), Image.BILINEAR)
    img.alpha_composite(warped, (x0, y0))

def background(t, eye, f, r, u):
    night = 0 <= t < C0
    y = np.linspace(0, 1, H)[:, None]
    if night:
        top, bot = np.array([10, 20, 60]), np.array([40, 130, 210])
    else:
        top, bot = np.array([70, 150, 240]), np.array([170, 215, 255])
    sky = top * (1 - y) + bot * y
    img = np.repeat(sky[:, None, :], W, axis=1).reshape(H, W, 3)
    # sol (plan y=0) : on lance un rayon par pixel (une ligne sur deux pour aller vite)
    jj, ii = np.mgrid[0:H, 0:W]
    dx = (ii - W / 2) / FOCAL; dy = -(jj - H / 2) / FOCAL
    dirs = f[None, None, :] + dx[..., None] * r[None, None, :] + dy[..., None] * u[None, None, :]
    with np.errstate(divide='ignore', invalid='ignore'):
        tt = -eye[1] / dirs[..., 1]
    hit = tt > 0
    px = eye[0] + dirs[..., 0] * tt; pz = eye[2] + dirs[..., 2] * tt
    chk = ((np.floor(px) + np.floor(pz)) % 2).astype(float)
    grass = np.where(chk[..., None] > 0, [70, 140, 60], [64, 130, 55]) if not night else np.where(chk[..., None] > 0, [30, 70, 35], [27, 64, 32])
    fog = np.clip(tt / 160, 0, 1)[..., None]
    g = grass * (1 - fog) + sky.reshape(H, 1, 3) * fog
    img = np.where(hit[..., None], g, img)
    return Image.fromarray(img.astype(np.uint8), 'RGB').convert('RGBA')

os.makedirs('/tmp/prevframes', exist_ok=True)
for fn in os.listdir('/tmp/prevframes'): os.remove('/tmp/prevframes/' + fn)
for t in range(0, END):
    eye, look = camera(t)
    f, r, u = basis(eye, look)
    img = background(t, eye, f, r, u)
    # caster (petit bonhomme)
    items = []
    for s in sprites:
        if not (s['t0'] <= t < s['t1']): continue
        name = s['frames'][min(len(s['frames']) - 1, (t - s['t0']) // s['every'])] if s['frames'] else s['model']
        if s['kind'] == 'ribbon':
            a0, a1 = s['pos'](t); c = (a0 + a1) / 2
        else:
            c = s['pos'](t)
        d = (c - eye) @ f
        items.append((d, s, name))
    items.sort(key=lambda x: -x[0])
    # le lanceur
    pc, pz = project(CASTER + [0, 0.9, 0], eye, f, r, u)
    for d, s, name in items:
        tim = frame_of(name, t, s['ft'])
        sx, sy = s['size'](t)
        c = s['pos'](t)
        if s['kind'] in ('bb', 'vert'):
            p, z = project(c, eye, f, r, u)
            if p is None: continue
            w_, h_ = sx * FOCAL / z, sy * FOCAL / z
            if w_ < 1 or h_ < 1 or w_ > W * 3: continue
            im = tim.resize((max(1, int(w_)), max(1, int(h_))), Image.BILINEAR)
            if s['rot'](t): im = im.rotate(math.degrees(s['rot'](t)), resample=Image.BILINEAR)
            img.alpha_composite(im, (int(p[0] - im.width / 2), int(p[1] - im.height / 2)))
        elif s['kind'] == 'flat':
            a = s['rot'](t); ca, sa = math.cos(a), math.sin(a)
            pts = []
            for ox, oz in [(-1, -1), (1, -1), (1, 1), (-1, 1)]:
                x_ = (ox * ca - oz * sa) * sx / 2; z_ = (ox * sa + oz * ca) * sy / 2
                pp, z = project(c + [x_, 0, z_], eye, f, r, u)
                if pp is None: break
                pts.append(pp)
            if len(pts) == 4: draw_quad(img, tim, pts)
        elif s['kind'] == 'col':
            hr = r * sx / 2
            pts = []
            for ox, oy in [(-1, 1), (1, 1), (1, -1), (-1, -1)]:
                pp, z = project(c + hr * ox + np.array([0, oy * sy / 2, 0]), eye, f, r, u)
                if pp is None: break
                pts.append(pp)
            if len(pts) == 4: draw_quad(img, tim, pts)
        elif s['kind'] == 'ribbon':
            a0, a1 = s['pos'](t)
            p0, z0 = project(a0, eye, f, r, u); p1, z1 = project(a1, eye, f, r, u)
            if p0 is None or p1 is None: continue
            dv = np.array(p1) - np.array(p0); L = np.linalg.norm(dv)
            if L < 0.5: continue
            nrm = np.array([-dv[1], dv[0]]) / L
            wpx = sx * FOCAL / ((z0 + z1) / 2) / 2
            pts = [tuple(np.array(p0) - nrm * wpx), tuple(np.array(p1) - nrm * wpx), tuple(np.array(p1) + nrm * wpx), tuple(np.array(p0) + nrm * wpx)]
            tim2 = tim.rotate(90, expand=True)
            draw_quad(img, tim2, pts)
        if pc and d > pz:
            pass
    if pc:
        dr = ImageDraw.Draw(img)
        sz = 1.9 * FOCAL / pz
        dr.rectangle([pc[0] - sz * 0.18, pc[1] - sz * 0.5, pc[0] + sz * 0.18, pc[1] + sz * 0.5], fill=(30, 40, 70, 255))
    # flashs plein écran
    def overlay(col, a):
        ov = Image.new('RGBA', (W, H), col + (int(255 * a),)); img.alpha_composite(ov)
    for t0, col, fin in [(0, (255, 255, 255), 9), (IMP, (255, 255, 255), 0), (C0, (255, 255, 255), 13), (116, (255, 120, 20), 11)]:
        if t0 <= t < t0 + fin:
            a = 1 - (t - t0) / fin
            overlay(col, a * (0.78 if col[1] == 120 else 1))
    img.convert('RGB').save('/tmp/prevframes/%04d.png' % t)
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', '20', '-i', '/tmp/prevframes/%04d.png', '-pix_fmt', 'yuv420p', '-vf', 'scale=960:-2', OUTV], check=True)
print('frames', END)
