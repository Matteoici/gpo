"""Projet Capricorne (relique prestige) : éclairs rouges du Black Wall (façon Edgerunners), écrans d'éclairs, sons.
Rien de "visible" à part des éclairs : arcs rouges très fins et ramifiés, cœur rose-blanc, halo rouge, qui scintillent.
usage : capricorne.py <racine du pack>"""
import numpy as np, os, sys, json, math, subprocess
from PIL import Image, ImageDraw, ImageFilter
from scipy.signal import lfilter

OUT = sys.argv[1]
NS = 'limitless'
A = os.path.join(OUT, 'assets', NS)
T = os.path.join(A, 'textures/item/fx'); M = os.path.join(A, 'models/fx'); I = os.path.join(A, 'items/fx')
FONT = os.path.join(A, 'textures/font'); SND = os.path.join(A, 'sounds/capricorn')
for d in (T, M, I, FONT, SND): os.makedirs(d, exist_ok=True)
rng = np.random.default_rng(2077)

# anciens éléments (murs, spectres...) retirés
for base in ('bw_wall', 'bw_ghost', 'bw_glyph', 'bw_circle'):
    for p in (os.path.join(T, base + '.png'), os.path.join(T, base + '.png.mcmeta'), os.path.join(M, base + '.json'), os.path.join(I, base + '.json')):
        if os.path.exists(p): os.remove(p)

GLOW = (255, 20, 40); BODY = (255, 46, 64); CORE = (255, 214, 220)

# =================================================================== tracé d'éclair (déplacement du point milieu + branches)
def bolt_path(p0, p1, disp, depth):
    if depth == 0: return [p0, p1]
    mx, my = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]; L = math.hypot(dx, dy) + 1e-9
    nx, ny = -dy / L, dx / L
    o = rng.normal(0, disp)
    m = (mx + nx * o, my + ny * o)
    return bolt_path(p0, m, disp * 0.55, depth - 1)[:-1] + bolt_path(m, p1, disp * 0.55, depth - 1)

def bolt_tree(p0, p1, disp, depth, branches, blen, out, width):
    pts = bolt_path(p0, p1, disp, depth)
    out.append((pts, width))
    if branches <= 0: return
    for _ in range(branches):
        i = rng.integers(1, len(pts) - 1)
        a = pts[i]
        main = math.atan2(p1[1] - p0[1], p1[0] - p0[0])
        ang = main + rng.choice([-1, 1]) * rng.uniform(0.35, 1.0)
        l = blen * rng.uniform(0.5, 1.0)
        b = (a[0] + math.cos(ang) * l, a[1] + math.sin(ang) * l)
        bolt_tree(a, b, disp * 0.6, max(2, depth - 2), branches - 1 if rng.random() < 0.6 else 0, blen * 0.5, out, max(1, width - 1))

def render(segs, W, H, ss=4, glow_r=3.0, core=True):
    """Dessine les éclairs : halo rouge flou, corps rouge, cœur clair (sur-échantillonné puis réduit)."""
    big = Image.new('L', (W * ss, H * ss), 0); thin = Image.new('L', (W * ss, H * ss), 0)
    db, dt = ImageDraw.Draw(big), ImageDraw.Draw(thin)
    for pts, w in segs:
        P = [(x * ss, y * ss) for x, y in pts]
        db.line(P, fill=255, width=max(1, int(w * ss * 1.0)), joint='curve')
        if core and w >= 2: dt.line(P, fill=255, width=max(1, int(w * ss * 0.35)))
    body = np.array(big.resize((W, H), Image.LANCZOS)).astype(np.float32) / 255
    cor = np.array(thin.resize((W, H), Image.LANCZOS)).astype(np.float32) / 255
    halo = np.array(Image.fromarray((body * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(glow_r))).astype(np.float32) / 255
    a = np.zeros((H, W, 4), np.float32)
    ha = np.clip(halo * 1.6, 0, 1) * 0.75
    a[..., :3] = np.array(GLOW) * ha[..., None]
    a[..., 3] = ha * 255
    b = np.clip(body * 1.4, 0, 1)
    a[..., :3] = a[..., :3] * (1 - b[..., None]) + np.array(BODY) * b[..., None]
    a[..., 3] = np.maximum(a[..., 3], b * 255)
    c = np.clip(cor * 1.5, 0, 1)
    a[..., :3] = a[..., :3] * (1 - c[..., None]) + np.array(CORE) * c[..., None]
    a[..., 3] = np.maximum(a[..., 3], c * 255)
    # un peu de noir dans le halo (le Black Wall est rouge ET noir)
    dark = (ha > 0.15) & (b < 0.2) & (rng.random((H, W)) < 0.18)
    a[dark, :3] *= 0.25
    return np.clip(a, 0, 255)

def save_anim(frames, name, ft=1):
    Image.fromarray(np.concatenate(frames, axis=0).astype(np.uint8)).save(os.path.join(T, name + '.png'))
    if len(frames) > 1:
        json.dump({'animation': {'frametime': ft, 'interpolate': False}}, open(os.path.join(T, name + '.png.mcmeta'), 'w'))

# =================================================================== arcs verticaux (64 x 256, 4 images qui scintillent) x 4 variantes
for v in range(4):
    fr = []
    for f in range(4):
        segs = []
        bolt_tree((32 + rng.normal(0, 3), 2), (32 + rng.normal(0, 3), 254), 15, 7, 3, 60, segs, 3)
        segs = [([(min(max(x, 7), 57), y) for x, y in pts], w) for pts, w in segs]
        a = render(segs, 64, 256, glow_r=3.2)
        a[..., 3] *= [1, 0.85, 1, 0.7][f]
        fr.append(a)
    save_anim(fr, f'bw_arc_{v}', ft=1)

# =================================================================== gerbe d'éclairs (128 x 128, 3 images)
fr = []
for f in range(3):
    segs = []
    n = 9
    for i in range(n):
        ang = 2 * math.pi * i / n + rng.uniform(-0.3, 0.3)
        L = rng.uniform(40, 62)
        bolt_tree((64, 64), (64 + math.cos(ang) * L, 64 + math.sin(ang) * L), 9, 6, 2, 22, segs, 3)
    segs = [([(min(max(x, 4), 124), min(max(y, 4), 124)) for x, y in pts], w) for pts, w in segs]
    a = render(segs, 128, 128, glow_r=3.5)
    yy, xx = np.mgrid[0:128, 0:128]; r = np.hypot(xx - 64, yy - 64)
    hot = np.clip(1 - r / 14, 0, 1)
    a[..., :3] = a[..., :3] * (1 - hot[..., None]) + np.array(CORE) * hot[..., None]
    a[..., 3] = np.maximum(a[..., 3], hot * 255)
    fr.append(a)
save_anim(fr, 'bw_burst', ft=1)

# =================================================================== éclairs qui rampent au sol (128 x 128, 3 images)
fr = []
for f in range(3):
    segs = []
    for i in range(7):
        ang = 2 * math.pi * i / 7 + rng.uniform(-0.4, 0.4)
        bolt_tree((64, 64), (64 + math.cos(ang) * 62, 64 + math.sin(ang) * 62), 10, 6, 3, 26, segs, 2)
    for i in range(10):
        a0 = 2 * math.pi * i / 10; a1 = a0 + 2 * math.pi / 10
        p0 = (64 + math.cos(a0) * 60, 64 + math.sin(a0) * 60); p1 = (64 + math.cos(a1) * 60, 64 + math.sin(a1) * 60)
        segs.append((bolt_path(p0, p1, 5, 5), 2))
    segs = [([(min(max(x, 4), 124), min(max(y, 4), 124)) for x, y in pts], w) for pts, w in segs]
    fr.append(render(segs, 128, 128, glow_r=2.5))
save_anim(fr, 'bw_ground', ft=1)

# =================================================================== modèles (non teintés)
def quad(t):
    return {'textures': {'0': f'{NS}:item/fx/{t}', 'particle': f'{NS}:item/fx/{t}'},
            'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False,
                          'faces': {'north': {'uv': [0, 0, 16, 16], 'texture': '#0'}, 'south': {'uv': [0, 0, 16, 16], 'texture': '#0'}}}]}
def cross(t):
    f = {'uv': [0, 0, 16, 16], 'texture': '#0'}
    return {'textures': {'0': f'{NS}:item/fx/{t}', 'particle': f'{NS}:item/fx/{t}'},
            'elements': [{'from': [8, 0, 0], 'to': [8, 16, 16], 'shade': False, 'faces': {'east': f, 'west': f}},
                         {'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'faces': {'north': f, 'south': f}}]}
def flat(t):
    f = {'uv': [0, 0, 16, 16], 'texture': '#0'}
    return {'textures': {'0': f'{NS}:item/fx/{t}', 'particle': f'{NS}:item/fx/{t}'},
            'elements': [{'from': [0, 8, 0], 'to': [16, 8, 16], 'shade': False, 'faces': {'up': f, 'down': f}}]}
models = {'bw_burst': quad('bw_burst'), 'bw_ground': flat('bw_ground')}
for v in range(4):
    models[f'bw_arc_{v}'] = quad(f'bw_arc_{v}')
    models[f'bw_arc_{v}_x'] = cross(f'bw_arc_{v}')
for name, m in models.items():
    json.dump(m, open(os.path.join(M, name + '.json'), 'w'))
    json.dump({'model': {'type': 'minecraft:model', 'model': f'{NS}:fx/{name}'}}, open(os.path.join(I, name + '.json'), 'w'))

# =================================================================== écrans : éclairs rouges sur les bords (police, 512 x 256, transparent au centre)
def screen(k):
    segs = []
    starts = [(0, rng.uniform(20, 236)) for _ in range(3)] + [(512, rng.uniform(20, 236)) for _ in range(3)] + \
             [(rng.uniform(20, 492), 0) for _ in range(2)] + [(rng.uniform(20, 492), 256) for _ in range(2)]
    for sx, sy in starts:
        tx = sx + (256 - sx) * rng.uniform(0.35, 0.7) + rng.normal(0, 40)
        ty = sy + (128 - sy) * rng.uniform(0.35, 0.7) + rng.normal(0, 25)
        bolt_tree((sx, sy), (tx, ty), 28, 7, 3, 70, segs, 3 if k == 0 else 4)
    a = render(segs, 512, 256, ss=2, glow_r=4)
    yy, xx = np.mgrid[0:256, 0:512]
    e = np.maximum(np.abs(xx - 256) / 256, np.abs(yy - 128) / 128)
    vig = np.clip((e - 0.55) / 0.45, 0, 1) ** 1.6 * (0.55 if k == 0 else 0.8)
    col = np.array([120, 0, 14]) if k == 0 else np.array([200, 10, 30])
    keep = a[..., 3] / 255
    a[..., :3] = a[..., :3] * keep[..., None] + col * vig[..., None] * (1 - keep[..., None])
    a[..., 3] = np.maximum(a[..., 3], vig * 255)
    if k == 1:
        a[..., :3] = a[..., :3] * keep[..., None] + np.array([200, 10, 30]) * (1 - keep[..., None])
        a[..., 3] = np.maximum(a[..., 3], 70)
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(os.path.join(FONT, 'bw_screen_%d.png' % k))
screen(0); screen(1)
fj = os.path.join(A, 'font/fx.json')
fd = json.load(open(fj))
fd['providers'] = [p for p in fd['providers'] if 'bw_screen' not in p.get('file', '')]
fd['providers'] += [{'type': 'bitmap', 'file': f'{NS}:font/bw_screen_{i}.png', 'ascent': 140, 'height': 256, 'chars': [chr(0xe040 + i)]} for i in range(2)]
json.dump(fd, open(fj, 'w'))

# =================================================================== sons
SR = 44100
def t_(sec): return np.arange(int(sec * SR)) / SR
def crush(x, bits=5, down=4):
    q = 2 ** bits
    y = np.round(x * q) / q
    return np.repeat(y[::down], down)[:len(x)]
def stutter(x, n=6, chunk=0.03):
    y = x.copy(); L = int(chunk * SR)
    for _ in range(n):
        p = rng.integers(0, max(1, len(x) - L * 4)); seg = x[p:p + L]
        for k in range(1, rng.integers(2, 5)):
            q = p + k * L
            if q + L < len(y): y[q:q + L] = seg
    return y
def lp(x, a=0.15):
    if np.ndim(a) == 0:
        return lfilter([a], [1, -(1 - a)], x)
    y = np.empty_like(x); acc = 0.0
    for i in range(len(x)):
        acc += a[i] * (x[i] - acc); y[i] = acc
    return y
def hp(x, a=0.95):
    return lfilter([a, -a], [1, -a], x)
def env(n, att, rel):
    e = np.ones(n); a = int(att * SR); r = int(rel * SR)
    if a: e[:a] = np.linspace(0, 1, a)
    if r: e[-r:] *= np.linspace(1, 0, r)
    return e
def saw(f, t):
    return 2 * ((f * t) % 1) - 1
def reverb(x, mix=0.25):
    out = np.zeros_like(x)
    for dly, g in [(1557, 0.8), (1617, 0.79), (1491, 0.81), (1277, 0.83)]:
        b = np.zeros(dly + 1); b[dly] = 1; aa = np.zeros(dly + 1); aa[0] = 1; aa[dly] = -g
        out += lfilter(b, aa, x)
    return x * (1 - mix) + out / 4 * mix
def crackle(n, density=0.004, decay=900):
    """Craquements électriques : impulsions aléatoires qui décroissent vite (densité constante ou variable)."""
    x = np.zeros(n)
    dens = density if np.ndim(density) else np.full(n, density)
    idx = np.where(rng.random(n) < dens)[0]
    for i in idx:
        L = min(n - i, int(rng.uniform(0.3, 1.5) * decay))
        x[i:i + L] += rng.normal(0, 1, L) * np.exp(-np.arange(L) / (decay * 0.25)) * rng.uniform(0.3, 1)
    return hp(x, 0.8)
def write(name, x, gain=-1.0):
    x = x / (np.max(np.abs(x)) + 1e-9) * 10 ** (gain / 20)
    fade = int(0.004 * SR); x[:fade] *= np.linspace(0, 1, fade); x[-fade:] *= np.linspace(1, 0, fade)
    raw = (np.clip(x, -1, 1) * 32767).astype('<i2').tobytes()
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '1', '-i', '-', '-c:a', 'libvorbis', '-q:a', '6',
                    os.path.join(SND, name + '.ogg')], input=raw, check=True)

t = t_(0.4)
x = crackle(len(t), 0.02, 300) + np.sin(2 * np.pi * (900 + 2400 * t) * t) * 0.3 * (np.sin(2 * np.pi * 37 * t) > 0)
x = crush(stutter(x, 8, 0.012), 4, 6) * env(len(t), 0.002, 0.15)
write('glitch', reverb(x, 0.2))

t = t_(3.4)
u = t / t[-1]
sub = np.sin(2 * np.pi * np.cumsum(38 + 22 * u ** 2) / SR)
hum = lp(np.tanh(3 * (np.sin(2 * np.pi * 60 * t) + 0.5 * saw(120, t))) * (0.2 + 0.8 * u), 0.25)
cr = crackle(len(t), 0.0005 + 0.01 * u ** 2, 700) * (0.3 + u)
x = stutter(sub * 1.1 + hum * 0.5 + cr * 0.9, 8, 0.04) * env(len(t), 0.3, 0.25)
write('breach', reverb(np.tanh(x * 1.6), 0.3))

t = t_(1.1)
snap = hp(rng.normal(0, 1, len(t)), 0.97) * np.exp(-t * 30)
boom = np.sin(2 * np.pi * np.cumsum(90 * np.exp(-t * 4) + 32) / SR) * np.exp(-t * 4)
buzz = np.tanh(4 * np.sin(2 * np.pi * 60 * t)) * np.exp(-t * 3) * 0.4 + crackle(len(t), 0.01, 400) * np.exp(-t * 2.5)
write('wall', reverb(np.tanh((boom + snap * 1.2 + buzz) * 1.5), 0.25))

t = t_(0.38)
z = crackle(len(t), 0.05, 200) + hp(rng.normal(0, 1, len(t)), 0.9) * np.exp(-t * 25)
z += np.sin(2 * np.pi * (3200 - 2600 * t / t[-1]) * t) * 0.3
write('zap', reverb(crush(z, 6, 2) * env(len(t), 0.001, 0.2), 0.15), gain=-3)

t = t_(3.2)
n = len(t); cut = int(1.15 * SR)
uu = np.linspace(0, 1, cut)
suck = lp(rng.normal(0, 1, cut), 0.03 + 0.4 * uu ** 3) * uu ** 2.2
suck += np.tanh(3 * np.sin(2 * np.pi * np.cumsum(60 + 400 * uu ** 3) / SR)) * uu ** 2 * 0.5
suck += crackle(cut, 0.002 + 0.03 * uu ** 2, 400) * uu
tt = t[:n - cut]
hit = np.sin(2 * np.pi * np.cumsum(70 * np.exp(-tt * 3) + 26) / SR) * np.exp(-tt * 1.6) * 1.6
hit += hp(rng.normal(0, 1, len(tt)), 0.9) * np.exp(-tt * 12) * 1.2
hit += lp(rng.normal(0, 1, len(tt)), 0.2) * np.exp(-tt * 2.2) * (1 + 0.5 * np.sin(2 * np.pi * 7 * tt))
hit += crackle(len(tt), 0.01, 500) * np.exp(-tt * 1.5)
write('collapse', reverb(np.tanh(np.concatenate([suck, hit]) * 1.8), 0.35))

sj = os.path.join(A, 'sounds.json'); sd = json.load(open(sj))
for k in ['glitch', 'breach', 'wall', 'zap', 'collapse']:
    sd['capricorn.' + k] = {'sounds': [{'name': f'{NS}:capricorn/{k}', 'stream': k in ('breach', 'collapse')}]}
json.dump(sd, open(sj, 'w'), indent=1)
print('capricorne ok')
