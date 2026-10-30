"""Projet Capricorne (relique prestige) : murs du Black Wall, spectres d'IA, glyphes, écrans de corruption, sons.
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

RED = np.array([255, 28, 48]); DRED = np.array([120, 0, 14]); BLK = np.array([8, 0, 4]); WHITE = np.array([255, 235, 238])

def save_anim(frames, name, ft=2, interp=False):
    Image.fromarray(np.concatenate(frames, axis=0).astype(np.uint8)).save(os.path.join(T, name + '.png'))
    json.dump({'animation': {'frametime': ft, 'interpolate': interp}}, open(os.path.join(T, name + '.png.mcmeta'), 'w'))

# =================================================================== mur du Black Wall (32 x 128, 12 images)
W, H, F = 32, 128, 12
cols = [(rng.uniform(0.6, 2.2), rng.integers(0, H), rng.uniform(0.25, 0.9)) for _ in range(W)]
glyph_rows = rng.random((H * 2, W)) < 0.42
cracks = []
for _ in range(5):
    x = rng.uniform(4, W - 4); y = 0.0; pts = [(x, y)]
    while y < H:
        y += rng.uniform(3, 9); x += rng.uniform(-3, 3); pts.append((float(np.clip(x, 1, W - 2)), y))
    cracks.append(pts)
top = (np.sin(np.arange(W) * 0.9) * 3 + rng.uniform(0, 6, W)).astype(int)
frames = []
for f in range(F):
    a = np.zeros((H, W, 4), np.float32)
    yy, xx = np.mgrid[0:H, 0:W]
    a[..., :3] = BLK
    a[..., 3] = 238
    # flux de données qui montent
    for x, (spd, off, br) in enumerate(cols):
        shift = int(off + f * spd * 4) % (H * 2)
        col = np.roll(glyph_rows[:, x], -shift)[:H]
        bright = col & (rng.random(H) < 0.9)
        k = br * (0.45 + 0.55 * ((np.arange(H) + shift) % 11 == 0))[:, None]
        a[bright, x, :3] = (DRED * (1 - k) + RED * k)[bright]
    # bords rougeoyants
    for e in range(3):
        g = [1.0, 0.6, 0.3][e]
        for x in (e, W - 1 - e):
            a[:, x, :3] = a[:, x, :3] * (1 - g) + RED * g
    # fissures blanches-rouges
    img = Image.fromarray(a.astype(np.uint8)); d = ImageDraw.Draw(img)
    for pts in cracks:
        d.line(pts, fill=(255, 90, 100, 255), width=1)
    a = np.array(img).astype(np.float32)
    # déchirures horizontales (glitch) : des bandes décalées, une ligne blanche
    for _ in range(3):
        y0 = rng.integers(0, H - 6); h = rng.integers(1, 5); s = rng.integers(-6, 7)
        a[y0:y0 + h] = np.roll(a[y0:y0 + h], s, axis=1)
        if rng.random() < 0.5: a[y0, :, :3] = WHITE
    # haut déchiqueté (transparent)
    for x in range(W):
        a[:max(0, top[x] + (f % 3)), x, 3] = 0
    # bas : lueur qui sort du sol
    a[H - 10:, :, :3] = a[H - 10:, :, :3] * 0.6 + RED * 0.4 * np.linspace(0, 1, 10)[:, None, None]
    frames.append(np.clip(a, 0, 255))
save_anim(frames, 'bw_wall', ft=2)

# =================================================================== spectre d'IA (32 x 64, 6 images)
W2, H2 = 32, 64
frames = []
for f in range(6):
    img = Image.new('RGBA', (W2, H2), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    def body(dx, col):
        d.ellipse([11 + dx, 3, 21 + dx, 14], fill=col)                       # tête
        d.polygon([(6 + dx, 18), (26 + dx, 18), (23 + dx, 44), (9 + dx, 44)], fill=col)   # torse
        d.polygon([(6 + dx, 18), (1 + dx, 38), (4 + dx, 40), (9 + dx, 24)], fill=col)     # bras
        d.polygon([(26 + dx, 18), (31 + dx, 38), (28 + dx, 40), (23 + dx, 24)], fill=col)
        d.polygon([(9 + dx, 44), (23 + dx, 44), (20 + dx, 62), (12 + dx, 62)], fill=col)  # traînée
    body(-1, (0, 230, 255, 110)); body(1, (255, 20, 50, 150)); body(0, (10, 0, 4, 230))
    a = np.array(img).astype(np.float32)
    # lignes de balayage + yeux
    a[::2, :, 3] *= 0.75
    a[8:10, 13:15, :3] = RED; a[8:10, 13:15, 3] = 255
    a[8:10, 18:20, :3] = RED; a[8:10, 18:20, 3] = 255
    for _ in range(2):
        y0 = rng.integers(0, H2 - 3); s = rng.integers(-4, 5)
        a[y0:y0 + 2] = np.roll(a[y0:y0 + 2], s, axis=1)
    # bas qui se dissout
    a[50:, :, 3] *= np.linspace(1, 0, 14)[:, None]
    a[..., 3] *= [1, 0.85, 1, 0.6, 1, 0.9][f]
    frames.append(np.clip(a, 0, 255))
save_anim(frames, 'bw_ghost', ft=2)

# =================================================================== glyphe de données (16 x 16, 4 images)
frames = []
for f in range(4):
    a = np.zeros((16, 16, 4), np.float32)
    a[3:13, 3:13, :3] = BLK; a[3:13, 3:13, 3] = 230
    a[3, 3:13, :3] = RED; a[12, 3:13, :3] = RED; a[3:13, 3, :3] = RED; a[3:13, 12, :3] = RED
    bits = rng.random((8, 8)) < 0.45
    a[4:12, 4:12, :3][bits] = [RED, WHITE, RED * 0.6, RED][f]
    a[4:12, 4:12, 3][bits] = 255
    frames.append(a)
save_anim(frames, 'bw_glyph', ft=3)

# =================================================================== cercle de corruption au sol (64 x 64)
N = 64; yy, xx = np.mgrid[0:N, 0:N]; u = (xx + 0.5) / N * 2 - 1; v = (yy + 0.5) / N * 2 - 1
r = np.sqrt(u * u + v * v); th = np.arctan2(v, u)
frames = []
for f in range(8):
    a = np.zeros((N, N, 4), np.float32)
    ring = (np.abs(r - 0.92) < 0.04) | (np.abs(r - 0.7) < 0.025)
    ticks = (np.abs(r - 0.81) < 0.07) & (((th / (2 * np.pi) * 48 + f * 0.5) % 1) < 0.35)
    hexes = (r < 0.66) & ((((u * 7 + v * 4) % 1) < 0.08) | (((v * 8) % 1) < 0.08)) & (rng.random((N, N)) < 0.7)
    a[ring] = [255, 28, 48, 255]; a[ticks] = [255, 120, 130, 230]; a[hexes] = [180, 0, 20, 160]
    a[r < 0.12] = [255, 240, 240, 255]
    frames.append(a)
save_anim(frames, 'bw_circle', ft=2)

# =================================================================== modèles (non teintés)
def quad(t):
    return {'textures': {'0': f'{NS}:item/fx/{t}', 'particle': f'{NS}:item/fx/{t}'},
            'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False,
                          'faces': {'north': {'uv': [0, 0, 16, 16], 'texture': '#0'}, 'south': {'uv': [0, 0, 16, 16], 'texture': '#0'}}}]}
def flat(t):
    return {'textures': {'0': f'{NS}:item/fx/{t}', 'particle': f'{NS}:item/fx/{t}'},
            'elements': [{'from': [0, 8, 0], 'to': [16, 8, 16], 'shade': False,
                          'faces': {'up': {'uv': [0, 0, 16, 16], 'texture': '#0'}, 'down': {'uv': [0, 0, 16, 16], 'texture': '#0'}}}]}
def wall(t):
    # deux plans croisés : le mur se voit de partout (et en biais)
    face = {'uv': [0, 0, 16, 16], 'texture': '#0'}
    return {'textures': {'0': f'{NS}:item/fx/{t}', 'particle': f'{NS}:item/fx/{t}'},
            'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'faces': {'north': face, 'south': face}},
                         {'from': [0, 0, 7.6], 'to': [16, 16, 7.6], 'shade': False, 'faces': {'north': face, 'south': face}}]}
for name, m in [('bw_wall', wall('bw_wall')), ('bw_ghost', quad('bw_ghost')), ('bw_glyph', quad('bw_glyph')), ('bw_circle', flat('bw_circle'))]:
    json.dump(m, open(os.path.join(M, name + '.json'), 'w'))
    json.dump({'model': {'type': 'minecraft:model', 'model': f'{NS}:fx/{name}'}}, open(os.path.join(I, name + '.json'), 'w'))

# =================================================================== écrans de corruption (police, 512 x 256)
def screen(k):
    img = Image.new('RGBA', (512, 256), (6, 0, 3, 255) if k == 0 else (90, 0, 12, 255)); d = ImageDraw.Draw(img)
    # pluie de code
    for x in range(0, 512, 6):
        h0 = rng.integers(0, 256)
        for y in range(0, 256, 5):
            if rng.random() < 0.45:
                c = (255, 30, 48, 255) if k == 0 else (10, 0, 4, 255)
                if (y - h0) % 40 < 4: c = (255, 220, 225, 255)
                d.rectangle([x, y, x + 3, y + 2], fill=c)
    # grosses déchirures
    a = np.array(img)
    for _ in range(9):
        y0 = rng.integers(0, 240); h = rng.integers(3, 18); s = rng.integers(-60, 60)
        a[y0:y0 + h] = np.roll(a[y0:y0 + h], s, axis=1)
        if rng.random() < 0.4: a[y0:y0 + 2, :, :3] = (255, 255, 255)
    # vignette et lignes de balayage
    a[::3, :, :3] = (a[::3, :, :3] * 0.6).astype(np.uint8)
    img = Image.fromarray(a); d = ImageDraw.Draw(img)
    # bloc d'avertissement au centre (formes, pas de texte)
    d.rectangle([196, 108, 316, 148], fill=(0, 0, 0, 255), outline=(255, 30, 48, 255), width=3)
    for i in range(5):
        d.polygon([(206 + i * 22, 140), (216 + i * 22, 116), (224 + i * 22, 116), (214 + i * 22, 140)], fill=(255, 30, 48, 255))
    img.save(os.path.join(FONT, 'bw_screen_%d.png' % k))
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
    y = np.repeat(y[::down], down)[:len(x)]
    return y
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
def write(name, x, gain=-1.0):
    x = x / (np.max(np.abs(x)) + 1e-9) * 10 ** (gain / 20)
    fade = int(0.004 * SR); x[:fade] *= np.linspace(0, 1, fade); x[-fade:] *= np.linspace(1, 0, fade)
    raw = (np.clip(x, -1, 1) * 32767).astype('<i2').tobytes()
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '1', '-i', '-', '-c:a', 'libvorbis', '-q:a', '6',
                    os.path.join(SND, name + '.ogg')], input=raw, check=True)

# glitch : bégaiement numérique court
t = t_(0.4)
x = np.sin(2 * np.pi * (900 + 2400 * t) * t) * 0.5 + rng.normal(0, 0.6, len(t)) * (np.sin(2 * np.pi * 37 * t) > 0)
x = crush(stutter(x, 8, 0.012), 4, 6) * env(len(t), 0.002, 0.15)
write('glitch', reverb(x, 0.2))

# brèche : grondement grave qui monte, essaim numérique, rugissement filtré
t = t_(3.4)
f0 = 38 + 22 * (t / t[-1]) ** 2
sub = np.sin(2 * np.pi * np.cumsum(f0) / SR)
cluster = sum(saw(f * (1 + 0.004 * k), t) for k, f in enumerate([55, 82.5, 110, 116.5, 164.8]))
cluster = lp(cluster, 0.02 + 0.2 * (t / t[-1]) ** 2)
roar = rng.normal(0, 1, len(t))
roar = lp(hp(roar, 0.9), 0.08) * (0.3 + 0.7 * np.abs(np.sin(2 * np.pi * 11 * t)))
digital = crush(np.sin(2 * np.pi * (220 + 1800 * (t / t[-1]) ** 3) * t), 3, 8) * 0.25
x = sub * 1.2 + cluster * 0.35 + roar * 0.5 * (t / t[-1]) + digital
x = stutter(x, 10, 0.04) * env(len(t), 0.3, 0.25)
write('breach', reverb(np.tanh(x * 1.6), 0.3))

# mur : choc + montée numérique
t = t_(1.1)
boom = np.sin(2 * np.pi * np.cumsum(90 * np.exp(-t * 4) + 32) / SR) * np.exp(-t * 3.2)
crack = hp(rng.normal(0, 1, len(t)), 0.97) * np.exp(-t * 18)
rise = crush(np.sin(2 * np.pi * (300 + 1600 * t) * t), 4, 5) * np.exp(-((t - 0.25) / 0.2) ** 2) * 0.35
write('wall', reverb(np.tanh((boom * 1.4 + crack * 0.6 + rise) * 1.5), 0.25))

# décharge (tentacule de données qui frappe)
t = t_(0.38)
z = rng.normal(0, 1, len(t)) * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 95 * t)))
z += np.sin(2 * np.pi * (3200 - 2600 * t / t[-1]) * t) * 0.4
write('zap', reverb(crush(hp(z, 0.9), 5, 3) * env(len(t), 0.002, 0.2), 0.15), gain=-3)

# effondrement : aspiration inversée puis implosion énorme, traîne glitchée
t = t_(3.2)
n = len(t); cut = int(1.15 * SR)
suck = lp(rng.normal(0, 1, cut), 0.03 + 0.4 * np.linspace(0, 1, cut) ** 3) * np.linspace(0, 1, cut) ** 2.2
suck += np.sin(2 * np.pi * np.cumsum(60 + 300 * np.linspace(0, 1, cut) ** 3) / SR) * np.linspace(0, 1, cut) ** 2 * 0.6
tt = t[:n - cut]
hit = np.sin(2 * np.pi * np.cumsum(70 * np.exp(-tt * 3) + 26) / SR) * np.exp(-tt * 1.6) * 1.6
hit += lp(rng.normal(0, 1, len(tt)), 0.25) * np.exp(-tt * 5)
hit = stutter(hit, 6, 0.05)
hit += crush(hit, 3, 10) * 0.3
x = np.concatenate([suck, hit])
write('collapse', reverb(np.tanh(x * 1.8), 0.35))

sj = os.path.join(A, 'sounds.json'); sd = json.load(open(sj))
for k in ['glitch', 'breach', 'wall', 'zap', 'collapse']:
    sd['capricorn.' + k] = {'sounds': [{'name': f'{NS}:capricorn/{k}', 'stream': k in ('breach', 'collapse')}]}
json.dump(sd, open(sj, 'w'), indent=1)
print('capricorne ok')
