"""Textures des effets (style anime : bandes de couleurs nettes + contour), animations image par image."""
import numpy as np, os, json, sys
from PIL import Image
from scipy.ndimage import zoom, gaussian_filter, map_coordinates, binary_dilation, binary_erosion

OUT = sys.argv[1]
T = os.path.join(OUT, 'assets/limitless/textures/item/fx'); os.makedirs(T, exist_ok=True)
F = os.path.join(OUT, 'assets/limitless/textures/font'); os.makedirs(F, exist_ok=True)

# ----------------------------------------------------------------- outils

def vnoise(shape, cells, seed):
    r = np.random.default_rng(seed)
    g = r.random((cells + 3, cells + 3))
    z = zoom(g, (shape[0] / cells, shape[1] / cells), order=3, mode='wrap')
    return z[:shape[0], :shape[1]]

def fbm(shape, cells, seed, oct=5):
    out = np.zeros(shape); a = 1; tot = 0
    for o in range(oct):
        out += vnoise(shape, cells * 2 ** o, seed + o * 17) * a; tot += a; a *= 0.5
    out /= tot
    return (out - out.min()) / (out.max() - out.min() + 1e-9)

def tile_fbm(n, cells, seed, oct=5):
    """bruit périodique (se raccorde sur les bords) : sert pour faire défiler / tourner."""
    return fbm((n, n), cells, seed, oct)

def sample(field, yy, xx):
    """lit un bruit périodique en coordonnées 0..1"""
    n = field.shape[0]
    return map_coordinates(field, [(yy % 1) * n, (xx % 1) * n], order=1, mode='wrap')

def grid(n):
    y, x = np.mgrid[0:n, 0:n]
    return (x + 0.5) / n * 2 - 1, (y + 0.5) / n * 2 - 1

def save(name, img, frametime=None, folder=None):
    img = np.clip(img, 0, 255).astype(np.uint8)
    p = os.path.join(folder or T, name + '.png')
    Image.fromarray(img, 'RGBA').save(p, optimize=True)
    if frametime:
        json.dump({"animation": {"frametime": frametime, "interpolate": True}}, open(p + '.mcmeta', 'w'))

def ramp(v, stops):
    v = np.clip(v, 0, 1); out = np.zeros(v.shape + (3,))
    for i in range(len(stops) - 1):
        p0, c0 = stops[i]; p1, c1 = stops[i + 1]
        m = (v >= p0) & (v <= p1)
        t = ((v - p0) / (p1 - p0 + 1e-9))[m][:, None]
        out[m] = np.array(c0) * (1 - t) + np.array(c1) * t
    return out

def rgba(rgb, a):
    return np.dstack([rgb, np.clip(a, 0, 1) * 255])

# palette feu anime : blanc chaud -> jaune -> orange -> rouge, contour rouge sombre
HOT = np.array([255, 252, 230]); YEL = np.array([255, 222, 70]); ORA = np.array([255, 138, 18])
RED = np.array([232, 48, 12]); EDGE = np.array([120, 8, 4])

def cel_fire(d, alpha_cut=0.2, bands=(0.36, 0.55, 0.76), outline=2, soft=0.0):
    """d = densité 0..1 -> image anime en bandes nettes avec contour sombre"""
    a = d > alpha_cut
    col = np.zeros(d.shape + (3,))
    col[:] = RED
    col[d > bands[0]] = ORA
    col[d > bands[1]] = YEL
    col[d > bands[2]] = HOT
    if outline:
        inner = binary_erosion(a, iterations=outline)
        rim = a & ~inner
        col[rim] = EDGE
    alpha = a.astype(float)
    if soft:
        alpha = np.clip(gaussian_filter(alpha, soft) * 1.3, 0, 1) * a + 0
    # halo très léger autour (lueur)
    glow = gaussian_filter(a.astype(float), max(2, d.shape[0] / 40)) * 0.35
    out_a = np.maximum(alpha, glow * (~a))
    gcol = np.zeros_like(col); gcol[:] = ORA
    col = np.where(a[..., None], col, gcol)
    return rgba(col, out_a)

# ----------------------------------------------------------------- 1. explosion de feu (16 images)

def fire_burst(n, frames, seed, spikes=10, smoke=False, debris=False, grow=(0.22, 0.98)):
    base = tile_fbm(256, 4, seed)
    warp = tile_fbm(256, 3, seed + 5)
    holes = tile_fbm(256, 6, seed + 9)
    x, y = grid(n)
    r = np.sqrt(x * x + y * y); th = np.arctan2(y, x) / (2 * np.pi) + 0.5
    out = []
    for f in range(frames):
        t = f / (frames - 1)
        e = 1 - (1 - t) ** 2.2                       # sortie rapide puis ralentit
        R = grow[0] + (grow[1] - grow[0]) * e
        # langues de feu : bruit en polaire qui file vers l'extérieur
        w = sample(warp, th * 2, r * 0.6 - t * 0.5) * 0.35
        tongues = sample(base, th * spikes / 4 + w, r * 0.9 - t * 1.4)
        ang = np.abs(np.sin((th + w * 0.3) * np.pi * spikes)) ** 0.7
        shape = R * (0.55 + 0.32 * ang + 0.22 * (tongues - 0.5))
        d = np.clip(1 - r / np.maximum(shape, 1e-3), 0, 1)
        d = d ** 0.8 * (1.15 - t * 0.55) + (tongues - 0.5) * 0.25
        # à la fin le feu se casse en morceaux
        if t > 0.45:
            h = sample(holes, y * 0.5 + 0.5 + t * 0.2, x * 0.5 + 0.5)
            d = d * np.clip((h - (t - 0.45) * 1.3 + 0.35) / 0.12, 0, 1)
        img = cel_fire(d, alpha_cut=0.12 + t * 0.08)
        if debris:
            dn = tile_fbm(256, 12, seed + 30)
            dv = sample(dn, th * 3, r * 0.5 - t * 0.6)
            m = (dv > 0.84) & (r > R * 0.55) & (r < R * 1.2)
            img[m] = [28, 18, 14, 255]
        out.append(img)
    return out

for i, im in enumerate(fire_burst(256, 16, 30, spikes=9)):
    save('burst_%d' % i, im)
for i, im in enumerate(fire_burst(256, 16, 41, spikes=13, debris=True, grow=(0.3, 1.0))):
    save('boom_%d' % i, im)

# ----------------------------------------------------------------- 2. fumée anime (12 images)

def smoke_frames(n, frames, seed, dark=True):
    rng = np.random.default_rng(seed)
    blobs = [(rng.uniform(-0.35, 0.35), rng.uniform(-0.25, 0.3), rng.uniform(0.25, 0.45)) for _ in range(9)]
    x, y = grid(n)
    nz = tile_fbm(256, 5, seed + 3)
    out = []
    for f in range(frames):
        t = f / (frames - 1)
        field = np.zeros((n, n))
        for bx, by, br in blobs:
            rr = br * (0.55 + 0.6 * t)
            cx, cy = bx * (1 + t * 0.6), by * (1 + t * 0.5) - t * 0.25
            field = np.maximum(field, 1 - np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / rr)
        field += (sample(nz, y * 0.5 + 0.5 + t * 0.3, x * 0.5 + 0.5) - 0.5) * (0.12 + 0.2 * t)
        a = field > (0.05 + t * 0.55)
        light = np.clip(-(y) * 0.6 + field * 0.8, 0, 1)
        if dark:
            col = ramp((light > 0.45) * 0.5 + (light > 0.75) * 0.5, [(0, (24, 18, 20)), (0.5, (46, 38, 40)), (1, (78, 66, 66))])
        else:
            col = ramp((light > 0.45) * 0.5 + (light > 0.75) * 0.5, [(0, (150, 150, 155)), (0.5, (195, 195, 200)), (1, (240, 240, 245))])
        inner = binary_erosion(a, iterations=2)
        col[a & ~inner] = (12, 8, 8) if dark else (120, 120, 128)
        out.append(rgba(col, a * (0.95 - t * 0.25)))
    return out

for i, im in enumerate(smoke_frames(128, 12, 80)):
    save('smoke_%d' % i, im)
for i, im in enumerate(smoke_frames(128, 12, 90, dark=False)):
    save('dust_%d' % i, im)

# ----------------------------------------------------------------- 3. boule de feu de la flèche (animée, en boucle)

def fireball(n, frames, seed):
    nz = tile_fbm(256, 4, seed)
    x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x) / (2 * np.pi) + 0.5
    imgs = []
    for f in range(frames):
        t = f / frames
        tong = sample(nz, th * 2 + t * 0.5, r * 0.8 - t)
        shape = 0.42 + 0.34 * tong
        d = np.clip(1 - r / shape, 0, 1) ** 0.6 + (tong - 0.5) * 0.2
        core = np.exp(-(r / 0.32) ** 2)
        d = np.maximum(d, core * 1.05)
        imgs.append(cel_fire(d, alpha_cut=0.1, bands=(0.3, 0.5, 0.7), outline=2))
    return np.vstack(imgs)

save('core', fireball(128, 8, 10), frametime=2)

# ----------------------------------------------------------------- 4. halos, étoiles, reflets

x, y = grid(128); r = np.sqrt(x * x + y * y)
g = np.exp(-(r / 0.55) ** 2) * np.clip((1 - r) * 3, 0, 1)
save('glow', rgba(ramp(g, [(0, (255, 70, 0)), (0.5, (255, 140, 30)), (1, (255, 236, 190))]), g * 0.9))
g2 = np.exp(-(r / 0.5) ** 2) * np.clip((1 - r) * 3, 0, 1)
save('glow_white', rgba(ramp(g2, [(0, (255, 200, 140)), (1, (255, 255, 255))]), g2 * 0.95))

def star(n, w=0.022, rays=(1.0, 0.35), glow=0.12):
    x, y = grid(n)
    def ray(a, b): return np.exp(-np.abs(a) * 4.5) * np.exp(-(b / w) ** 2)
    s = np.maximum(ray(x, y), ray(y, x))
    d1, d2 = (x + y) / np.sqrt(2), (x - y) / np.sqrt(2)
    s = np.maximum(s, rays[1] * np.maximum(ray(d1 * 1.8, d2), ray(d2 * 1.8, d1)))
    s = np.maximum(s, np.exp(-(np.sqrt(x * x + y * y) / glow) ** 2))
    return np.clip(s * 1.3, 0, 1)

s = star(128)
save('star', rgba(ramp(s, [(0, (255, 150, 60)), (0.45, (255, 225, 180)), (1, (255, 255, 255))]), s))
sd = star(128, w=0.035, rays=(1, 0), glow=0.07)
save('star_dark', rgba(np.zeros(sd.shape + (3,)) + np.array([16, 6, 4]), np.clip(sd * 1.4, 0, 1)))
sr = star(128, w=0.03, glow=0.1)
save('star_red', rgba(ramp(sr, [(0, (255, 20, 60)), (0.6, (255, 120, 140)), (1, (255, 240, 240))]), sr))

w_, h_ = 512, 32
yy, xx = np.mgrid[0:h_, 0:w_]; X = (xx + 0.5) / w_ * 2 - 1; Y = (yy + 0.5) / h_ * 2 - 1
core = np.exp(-(Y / 0.12) ** 2) * (1 - np.abs(X)) ** 2.2
haze = np.exp(-(Y / 0.5) ** 2) * (1 - np.abs(X)) ** 1.2 * 0.5
dot = np.exp(-((X / 0.05) ** 2 + (Y / 0.7) ** 2))
v = np.clip(np.maximum(core, np.maximum(haze, dot)), 0, 1)
save('flare', rgba(ramp(v, [(0, (255, 80, 10)), (0.4, (255, 170, 50)), (0.8, (255, 240, 200)), (1, (255, 255, 255))]), np.clip(v * 1.5, 0, 1)))
save('flare_red', rgba(ramp(v, [(0, (200, 10, 20)), (0.5, (255, 60, 40)), (1, (255, 220, 200))]), np.clip(v * 1.5, 0, 1)))

# ----------------------------------------------------------------- 5. cercle magique (512, très détaillé) + spirale animée

n = 512; x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x)
nz = fbm((n, n), 6, 2)
d = np.zeros((n, n))
for rr, w, s_ in [(0.96, 0.008, 1), (0.92, 0.004, 0.9), (0.80, 0.006, 1), (0.62, 0.005, 0.9), (0.58, 0.003, 0.8), (0.30, 0.006, 1)]:
    d = np.maximum(d, s_ * np.exp(-((r - rr) / w) ** 2))
# runes entre les deux anneaux extérieurs
rng = np.random.default_rng(5)
seg = ((th / (2 * np.pi) + 0.5) * 48).astype(int)
rune_on = rng.random(48) > 0.25
band = (r > 0.83) & (r < 0.9)
glyph = band & rune_on[seg % 48] & ((((th / (2 * np.pi) + 0.5) * 48 * 3) % 3) < 1.8) & (np.abs(((r - 0.83) / 0.07) * 4 % 2 - 1) < 0.7)
d = np.maximum(d, glyph * 0.85)
# étoile à 6 branches (deux triangles)
for k in range(6):
    a = k * np.pi / 3
    ax, ay = np.cos(a) * 0.80, np.sin(a) * 0.80
    bx, by = np.cos(a + 2 * np.pi / 3) * 0.80, np.sin(a + 2 * np.pi / 3) * 0.80
    vx, vy = bx - ax, by - ay; L = np.hypot(vx, vy)
    tproj = np.clip(((x - ax) * vx + (y - ay) * vy) / L ** 2, 0, 1)
    dist = np.hypot(x - (ax + tproj * vx), y - (ay + tproj * vy))
    d = np.maximum(d, 0.8 * np.exp(-(dist / 0.006) ** 2))
d = np.clip(d * 1.2, 0, 1)
col = ramp(d, [(0, (220, 30, 10)), (0.5, (255, 140, 30)), (1, (255, 245, 200))])
save('circle', rgba(col, np.clip(d * 1.5 + gaussian_filter(d, 4) * 0.8, 0, 1)))

# spirale de flammes au centre (animée, tourne toute seule)
n = 256
spf = tile_fbm(256, 5, 3)
x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x) / (2 * np.pi) + 0.5
imgs = []
for f in range(16):
    t = f / 16
    sw = sample(spf, th * 3 - r * 1.6 + t, r * 0.6 - t * 0.5)
    arms = np.sin((th * 3 + np.log(r + 0.05) * 1.1 - t) * 2 * np.pi) * 0.5 + 0.5
    dd = arms ** 2 * 1.2 * np.clip(1.05 - r, 0, 1) + (sw - 0.5) * 0.6
    dd *= np.clip((r - 0.08) / 0.1, 0, 1) * (r < 0.98)
    imgs.append(cel_fire(dd, alpha_cut=0.3, bands=(0.45, 0.62, 0.82), outline=1))
save('circle2', np.vstack(imgs), frametime=2)

# ----------------------------------------------------------------- 6. aura (fumée orange / turquoise qui tourne, animée)

n = 256; x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x) / (2 * np.pi) + 0.5
af = tile_fbm(256, 4, 1)
imgs = []
for f in range(16):
    t = f / 16
    nz = sample(af, th * 2 + t, r * 0.7 - t * 0.3)
    ring = np.exp(-((r - 0.6 - 0.15 * (nz - 0.5)) / 0.22) ** 2) * np.clip((1 - r) * 4, 0, 1)
    sw = np.sin((th * 4 + r * 1.5 + nz * 0.8 + t) * 2 * np.pi) * 0.5 + 0.5
    a = ring * (0.3 + 0.7 * sw)
    col = ramp(sw * 0.7 + nz * 0.3, [(0, (40, 190, 210)), (0.4, (255, 110, 25)), (0.8, (255, 180, 60)), (1, (255, 235, 160))])
    imgs.append(rgba(col, a * 0.32))
save('aura', np.vstack(imgs), frametime=2)

# ----------------------------------------------------------------- 7. rubans de feu (animés, ils coulent)

rf = tile_fbm(256, 4, 20)
n = 64
yy, xx = np.mgrid[0:n, 0:n]; U = (xx + 0.5) / n; V = (yy + 0.5) / n * 2 - 1
for name, cols in [('ribbon', [(0, (255, 110, 10)), (0.55, (255, 205, 50)), (1, (255, 252, 220))]),
                   ('ribbon_red', [(0, (190, 20, 0)), (0.6, (255, 90, 10)), (1, (255, 190, 70))])]:
    imgs = []
    for f in range(8):
        t = f / 8
        nz = sample(rf, V * 0.25 + 0.5, U - t)
        wid = 0.55 + 0.35 * (nz - 0.5)
        dd = np.clip(1 - np.abs(V) / wid, 0, 1)
        q = (dd > 0.08) * 0.33 + (dd > 0.45) * 0.33 + (dd > 0.78) * 0.34
        imgs.append(rgba(ramp(q, [(0, cols[0][1]), (0.34, cols[0][1]), (0.67, cols[1][1]), (1, cols[2][1])]), dd > 0.08))
    save(name, np.vstack(imgs), frametime=1)

# ----------------------------------------------------------------- 8. sol : éclaboussures de feu (12 images), anneaux

n = 256; x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x) / (2 * np.pi) + 0.5
sp = tile_fbm(256, 5, 60)
for f in range(12):
    t = f / 11
    e = 1 - (1 - t) ** 2
    st = np.abs(np.sin((th + sample(sp, th, r * 0.3) * 0.08) * np.pi * 11)) ** 3
    R = 0.25 + 0.72 * e
    dd = st * np.clip(1 - np.abs(r - R * 0.75) / (0.25 + 0.2 * (1 - t)), 0, 1) * 1.3 * (1 - t * 0.6)
    dd += (sample(sp, th * 2, r - t) - 0.5) * 0.3
    dd *= (r < 0.99)
    save('splash_%d' % f, cel_fire(dd, alpha_cut=0.22, bands=(0.4, 0.6, 0.85), outline=2))

n = 256; x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x)
sp = fbm((n, n), 8, 70)
v = np.exp(-((r - 0.86) / 0.035) ** 2) * (0.55 + 0.45 * np.clip(np.sin(th * 26 + sp * 6), 0, 1))
save('ring', rgba(ramp(v, [(0, (255, 20, 60)), (0.6, (255, 110, 120)), (1, (255, 230, 230))]), np.clip(v * 1.6, 0, 1)))
v = np.exp(-((r - 0.84) / 0.06) ** 2) + np.exp(-((r - 0.84) / 0.015) ** 2)
save('shock', rgba(ramp(np.clip(v, 0, 1), [(0, (255, 160, 60)), (1, (255, 255, 240))]), np.clip(v * 0.9, 0, 1)))

# ----------------------------------------------------------------- 9. colonne : flammes qui montent (animée), coeur blanc, halo

cf = tile_fbm(256, 4, 90)
n = 128
yy, xx = np.mgrid[0:n, 0:n]; U = (xx + 0.5) / n; Vv = (yy + 0.5) / n
for name, mode in [('column', 0), ('column_core', 1)]:
    imgs = []
    for f in range(16):
        t = f / 16
        nz = sample(cf, Vv * 0.5 + t, U)                 # défile vers le haut
        stripes = np.sin((U * (7 if mode == 0 else 4) + nz * 0.35) * 2 * np.pi) * 0.5 + 0.5
        fine = np.sin((U * 23 + nz * 0.9) * 2 * np.pi) * 0.5 + 0.5
        dd = stripes * 0.7 + fine * 0.15 + nz * 0.3
        if mode == 0:
            q = np.digitize(dd, [0.32, 0.5, 0.68, 0.85]) / 4
            col = ramp(q, [(0, (70, 0, 0)), (0.25, (170, 10, 0)), (0.5, (240, 60, 0)), (0.75, (255, 160, 20)), (1, (255, 240, 150))])
            imgs.append(rgba(col, np.full((n, n), 0.96)))
        else:
            q = np.digitize(dd, [0.4, 0.62]) / 2
            col = ramp(q, [(0, (255, 170, 40)), (0.5, (255, 230, 120)), (1, (255, 255, 235))])
            imgs.append(rgba(col, np.full((n, n), 0.9)))
    save(name, np.vstack(imgs), frametime=1)
n = 64; yy, xx = np.mgrid[0:n, 0:n]; U = (xx + 0.5) / n
v = np.sin(U * np.pi) ** 0.6 * (0.55 + 0.45 * fbm((n, n), 3, 99))
save('column_glow', rgba(ramp(v, [(0, (255, 50, 0)), (1, (255, 160, 40))]), v * 0.4))

# feu à la base (animé)
bf = tile_fbm(256, 4, 110)
n = 128; yy, xx = np.mgrid[0:n, 0:n]; X = (xx + 0.5) / n; Yb = 1 - (yy + 0.5) / n
imgs = []
for f in range(12):
    t = f / 12
    nz = sample(bf, Yb * 0.6 - t, X)
    tong = np.abs(np.sin((X * 5 + nz * 0.5) * np.pi))
    dd = tong * 0.85 + nz * 0.45 - Yb * 1.25
    imgs.append(cel_fire(dd * 1.4, alpha_cut=0.12, bands=(0.35, 0.55, 0.78), outline=2))
save('basefire', np.vstack(imgs), frametime=2)

# étincelle (petit trait lumineux)
n = 32; x, y = grid(n)
v = np.exp(-(y / 0.18) ** 2) * np.clip(1 - np.abs(x), 0, 1) ** 1.5
save('spark', rgba(ramp(v, [(0, (255, 120, 20)), (1, (255, 250, 220))]), np.clip(v * 1.6, 0, 1)))

# ----------------------------------------------------------------- 10. flash plein écran (police)
save('white', rgba(np.full((16, 16, 3), 255.0), np.ones((16, 16))), folder=F)
save('orange', rgba(np.zeros((16, 16, 3)) + np.array([255, 120, 20]), np.full((16, 16), 0.78)), folder=F)
print('ok')
