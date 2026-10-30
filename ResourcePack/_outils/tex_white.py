"""Effets "teintables" : chaque effet = une couche colorée par le plugin (gris -> couleur choisie) + un cœur blanc.
Le plugin donne la couleur via custom_model_data (colors), donc une seule texture sert pour toutes les armes."""
from texlib import *
from PIL import Image, ImageDraw
import math, json

def layers(name, d, cuts=(0.10, 0.35, 0.62), core=0.78, smooth=False):
    """d (0..1) -> name (couche teintée, niveaux de gris en bandes) + name_c (cœur blanc)"""
    if smooth:
        a = np.clip(d, 0, 1)
        g = np.clip(0.55 + 0.45 * d, 0, 1)
        col = np.dstack([g, g, g]) * 255
        save(name, rgba(col, a))
        c = np.clip((d - core) / (1 - core + 1e-6), 0, 1)
        save(name + '_c', rgba(np.full(d.shape + (3,), 255.0), c ** 1.5))
        return
    a = d > cuts[0]
    g = np.full(d.shape, 0.55); g[d > cuts[1]] = 0.8; g[d > cuts[2]] = 1.0
    col = np.dstack([g, g, g]) * 255
    save(name, rgba(col, a.astype(float)))
    save(name + '_c', rgba(np.full(d.shape + (3,), 255.0), (d > core).astype(float)))

n = 128; x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x)

# lueur douce
layers('w_glow', np.clip(1 - r, 0, 1) ** 1.8, smooth=True, core=0.55)
# anneau épais
layers('w_ring', np.clip(1 - np.abs(r - 0.78) / 0.17, 0, 1), core=0.7)
# onde fine irrégulière
nz = tile_fbm(n, 6, 901)
layers('w_shock', np.clip(1 - np.abs(r - 0.86 - (nz - 0.5) * 0.04) / 0.07, 0, 1), core=0.75)
# étincelle à 4 branches
st = np.clip(1 - (np.abs(x) ** 0.5 + np.abs(y) ** 0.5) / 1.05, 0, 1) * 1.6
layers('w_spark', np.clip(st, 0, 1), core=0.6)
# cercle magique (runes)
d = np.clip(1 - np.abs(r - 0.9) / 0.035, 0, 1) + np.clip(1 - np.abs(r - 0.72) / 0.025, 0, 1)
ang = (th / (2 * np.pi) + 0.5)
d += ((np.abs(r - 0.81) < 0.05) & ((ang * 24) % 1 < 0.35)).astype(float)
for k in range(6):
    a0 = k * math.pi / 3
    px_, py_ = np.cos(a0) * 0.72, np.sin(a0) * 0.72
    px2, py2 = np.cos(a0 + 2 * math.pi / 3) * 0.72, np.sin(a0 + 2 * math.pi / 3) * 0.72
    # ligne de l'hexagramme
    vx, vy = px2 - px_, py2 - py_; L2 = vx * vx + vy * vy
    tt = np.clip(((x - px_) * vx + (y - py_) * vy) / L2, 0, 1)
    dist = np.hypot(x - (px_ + tt * vx), y - (py_ + tt * vy))
    d += np.clip(1 - dist / 0.02, 0, 1)
d += np.clip(1 - r / 0.12, 0, 1)
layers('w_rune', np.clip(d, 0, 1), cuts=(0.15, 0.4, 0.7), core=0.85)

# entaille (croissant façon anime) : épaisse au milieu, effilée aux bouts, ouverte vers le bas
rho = np.hypot(x, y - 0.6); phi = np.arctan2(y - 0.6, x)
tpos = (phi - (-math.pi + 0.3)) / (math.pi - 0.6)
wv = np.where((tpos >= 0) & (tpos <= 1), 0.42 * np.sin(np.pi * np.clip(tpos, 0, 1)) ** 0.9, 0)
dd = np.where(wv > 0.02, np.clip((rho - 1.05) / np.maximum(wv, 1e-3), 0, 1.0001), 0)
dd = np.where((rho > 1.05) & (rho < 1.05 + wv), 0.15 + 0.85 * dd, 0)
layers('w_slash', np.clip(dd, 0, 1), cuts=(0.08, 0.3, 0.55), core=0.72)

# trait (effilé)
W, H = 32, 128
yy, xx = np.mgrid[0:H, 0:W]
u = (yy + 0.5) / H
half = (0.08 + 0.42 * u ** 1.4) * W / 2
dd = np.clip(1 - np.abs(xx + 0.5 - W / 2) / np.maximum(half, 0.5), 0, 1) * np.clip(u * 6, 0, 1)
layers('w_spike', dd, cuts=(0.05, 0.3, 0.6), core=0.75)

# rayon vertical (profil)
W, H = 32, 64
xx = np.mgrid[0:H, 0:W][1]
dd = np.clip(1 - np.abs(xx + 0.5 - W / 2) / (W / 2), 0, 1) ** 0.8
layers('w_beam', dd, cuts=(0.05, 0.35, 0.65), core=0.8)

# fissure au sol (vue de dessus)
img = Image.new('L', (128, 128), 0); dr = ImageDraw.Draw(img)
rng = np.random.default_rng(55)
def crack(x0, y0, a, L, w, depth):
    px_, py_ = x0, y0
    for s in range(int(L / 6)):
        a += rng.uniform(-0.5, 0.5)
        nx_, ny_ = px_ + math.cos(a) * 6, py_ + math.sin(a) * 6
        dr.line([(px_, py_), (nx_, ny_)], fill=255, width=max(1, int(w)))
        px_, py_ = nx_, ny_
        w *= 0.93
        if depth and rng.random() < 0.18: crack(px_, py_, a + rng.choice([-1, 1]) * rng.uniform(0.6, 1.2), L * 0.4, w * 0.7, depth - 1)
crack(4, 64, 0, 124, 9, 2)
cm = np.array(img) / 255.0
from scipy.ndimage import gaussian_filter
glow = gaussian_filter(cm, 3) * 2.2
layers('w_crack', np.clip(np.maximum(cm, glow * 0.75), 0, 1), cuts=(0.12, 0.35, 0.8), core=0.95)

# éclairs (croisés, à orienter)
for k in range(4):
    rng = np.random.default_rng(910 + k)
    imgL = Image.new('L', (64, 256), 0); dr = ImageDraw.Draw(imgL)
    def bolt(x0, y0, x1, y1, w, depth):
        pts = [(x0, y0)]
        segs = 9
        for i in range(1, segs):
            t = i / segs
            pts.append((x0 + (x1 - x0) * t + rng.uniform(-9, 9), y0 + (y1 - y0) * t + rng.uniform(-4, 4)))
        pts.append((x1, y1))
        dr.line(pts, fill=255, width=w, joint='curve')
        if depth > 0:
            i = int(rng.integers(2, segs - 1)); bx, by = pts[i]
            bolt(bx, by, bx + rng.uniform(-24, 24), by + rng.uniform(20, 60), max(1, w - 2), depth - 1)
    bolt(32, 0, 32 + rng.uniform(-8, 8), 255, 5, 2)
    bm = np.array(imgL) / 255.0
    g = gaussian_filter(bm, 3) * 2.5
    layers('w_bolt_%d' % k, np.clip(np.maximum(bm, g * 0.7), 0, 1), cuts=(0.1, 0.3, 0.75), core=0.9)

# explosion (12 images)
base = tile_fbm(128, 4, 920); warp = tile_fbm(128, 3, 921); holes = tile_fbm(128, 6, 922)
thn = th / (2 * np.pi) + 0.5
for f in range(12):
    t = f / 11; e = 1 - (1 - t) ** 2.2
    R = 0.3 + 0.7 * e
    w_ = sample(warp, thn * 2, r * 0.6 - t * 0.5) * 0.3
    tong = sample(base, thn * 3 + w_, r * 0.9 - t * 1.4)
    spikes = np.abs(np.sin((thn + w_ * 0.2) * np.pi * 12)) ** 4
    shape = R * (0.55 + 0.3 * spikes + 0.2 * (tong - 0.5))
    d = np.clip(1 - r / np.maximum(shape, 1e-3), 0, 1) ** 0.8 * (1.2 - t * 0.6) + (tong - 0.5) * 0.25
    if t > 0.4:
        h = sample(holes, y * 0.5 + 0.5 + t * 0.2, x * 0.5 + 0.5)
        d = d * np.clip((h - (t - 0.4) * 1.3 + 0.35) / 0.12, 0, 1)
    layers('w_burst_%d' % f, np.clip(d, 0, 1), cuts=(0.12 + t * 0.06, 0.4, 0.7), core=0.82)

# fumée / poussière (8 images) : boules qui gonflent et se trouent
blob = tile_fbm(128, 5, 930)
for f in range(8):
    t = f / 7
    R = 0.45 + 0.45 * t
    nzv = sample(blob, y * 0.5 + 0.5 - t * 0.3, x * 0.5 + 0.5)
    d = np.clip(1 - r / R + (nzv - 0.5) * 0.6, 0, 1)
    d *= np.clip((nzv - t * 0.8 + 0.4) / 0.15, 0, 1)
    a = d > 0.08
    g = np.full(d.shape, 0.6); g[d > 0.35] = 0.85; g[d > 0.6] = 1.0
    save('w_puff_%d' % f, rgba(np.dstack([g, g, g]) * 255, a.astype(float)))
print('ok')

# toile d'araignée (rayons + spirale), pour les pièges et le sol de la Reine
n = 256; x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x)
spokes = 14
d = np.zeros((n, n))
ang = (th / (2 * np.pi) * spokes) % 1
d = np.maximum(d, np.clip(1 - (np.minimum(ang, 1 - ang) * 2 * np.pi * r / spokes) / 0.014, 0, 1) * (r < 0.98))
# spirale : anneaux un peu "affaissés" entre les rayons
sag = np.sin(ang * np.pi) * 0.03
for k in range(1, 9):
    rr = 0.11 * k - sag * (0.5 + k * 0.08)
    d = np.maximum(d, np.clip(1 - np.abs(r - rr) / 0.012, 0, 1) * (r < 0.98))
d = np.maximum(d, np.clip(1 - r / 0.05, 0, 1))
layers('w_web', np.clip(d, 0, 1), cuts=(0.2, 0.5, 0.8), core=0.6)
print('web ok')
