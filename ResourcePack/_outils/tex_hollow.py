"""Textures du Violet Imaginaire (style anime, bandes nettes + contour)."""
from texlib import *
from scipy.ndimage import binary_erosion, gaussian_filter
import math

def cel(d, pal, edge, alpha_cut=0.12, bands=(0.32, 0.52, 0.74), outline=2, glowc=None):
    a = d > alpha_cut
    col = np.zeros(d.shape + (3,)); col[:] = pal[0]
    col[d > bands[0]] = pal[1]; col[d > bands[1]] = pal[2]; col[d > bands[2]] = pal[3]
    if outline:
        rim = a & ~binary_erosion(a, iterations=outline); col[rim] = edge
    glow = gaussian_filter(a.astype(float), max(2, d.shape[0] / 30)) * 0.45
    gc = np.zeros_like(col); gc[:] = glowc if glowc is not None else pal[1]
    col = np.where(a[..., None], col, gc)
    return rgba(col, np.maximum(a.astype(float), glow * (~a)))

BLUE = [(40, 90, 255), (60, 150, 255), (150, 225, 255), (245, 255, 255)]; NAVY = (8, 14, 70)
REDP = [(200, 10, 40), (255, 50, 70), (255, 150, 160), (255, 245, 245)]; CRIM = (70, 0, 12)
PURP = [(120, 20, 200), (200, 40, 230), (255, 140, 250), (255, 245, 255)]; VIOL = (30, 0, 60)

def orb(n, frames, seed, pal, edge, wisps=7, spiky=False):
    nz = tile_fbm(256, 4, seed)
    x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x) / (2 * np.pi) + 0.5
    out = []
    for f in range(frames):
        t = f / frames
        sw = sample(nz, th * 2 + r * 0.8 + t, r * 0.5 - t * 0.7)
        wav = np.abs(np.sin((th * wisps + r * 1.5 + t) * 2 * np.pi)) ** (3 if spiky else 1.5)
        shape = 0.46 + (0.16 if spiky else 0.08) * (sw - 0.5) + (0.3 if spiky else 0.05) * wav
        d = np.clip(1 - r / shape, 0, 1) ** 0.7
        d = np.maximum(d, np.exp(-(r / 0.25) ** 2) * 1.05)
        d += (sw - 0.5) * 0.18
        out.append(cel(d, pal, edge, alpha_cut=0.08, bands=(0.28, 0.5, 0.72)))
    return np.vstack(out)

save('orb_blue', orb(128, 8, 201, BLUE, NAVY), frametime=2)
save('orb_red', orb(128, 8, 202, REDP, CRIM, wisps=5), frametime=2)
save('orb_purple', orb(128, 8, 203, PURP, VIOL, wisps=9, spiky=True), frametime=2)

x, y = grid(128); r = np.sqrt(x * x + y * y)
g = np.exp(-(r / 0.55) ** 2) * np.clip((1 - r) * 3, 0, 1)
for name, c0, c1 in [('glow_blue', (30, 80, 255), (170, 230, 255)), ('glow_red', (255, 20, 50), (255, 180, 190)), ('glow_purple', (150, 20, 230), (255, 190, 255))]:
    save(name, rgba(ramp(g, [(0, c0), (1, c1)]), g * 0.9))

# rubans (traînée de la boule bleue) : clair au centre, bords bleu nuit
rf = tile_fbm(256, 4, 220)
n = 64; yy, xx = np.mgrid[0:n, 0:n]; U = (xx + 0.5) / n; V = (yy + 0.5) / n * 2 - 1
for name, cols, edge in [('ribbon_blue', [(40, 90, 255), (110, 190, 255), (235, 250, 255)], NAVY),
                         ('ribbon_navy', [(10, 16, 60), (25, 40, 120), (60, 90, 200)], (2, 4, 20))]:
    imgs = []
    for f in range(8):
        t = f / 8
        nz = sample(rf, V * 0.25 + 0.5, U - t)
        wid = 0.5 + 0.4 * (nz - 0.5)
        dd = np.clip(1 - np.abs(V) / wid, 0, 1)
        a = dd > 0.06
        col = np.zeros((n, n, 3)); col[:] = cols[0]; col[dd > 0.4] = cols[1]; col[dd > 0.75] = cols[2]
        rim = a & ~binary_erosion(a, iterations=2); col[rim] = edge
        imgs.append(rgba(col, a))
    save(name, np.vstack(imgs), frametime=1)

# rayon rouge vertical (quand la boule rouge apparaît)
n = 128; x, y = grid(n)
v = np.exp(-(x / 0.06) ** 2) * (1 - np.abs(y)) ** 0.6 + np.exp(-(x / 0.22) ** 2) * (1 - np.abs(y)) ** 1.2 * 0.5
v = np.maximum(v, np.exp(-((x / 0.5) ** 2 + (y / 0.04) ** 2)) * 0.8)
v = np.clip(v, 0, 1)
save('beam_red', rgba(ramp(v, [(0, (255, 20, 70)), (0.5, (255, 90, 140)), (1, (255, 245, 250))]), np.clip(v * 1.4, 0, 1)))

# étoile rose
def star(n, w=0.025, glow=0.12):
    x, y = grid(n)
    def ray(a, b): return np.exp(-np.abs(a) * 4.5) * np.exp(-(b / w) ** 2)
    s = np.maximum(ray(x, y), ray(y, x))
    d1, d2 = (x + y) / np.sqrt(2), (x - y) / np.sqrt(2)
    s = np.maximum(s, 0.4 * np.maximum(ray(d1 * 1.8, d2), ray(d2 * 1.8, d1)))
    return np.clip(np.maximum(s, np.exp(-(np.sqrt(x * x + y * y) / glow) ** 2)) * 1.3, 0, 1)
s_ = star(128)
save('star_pink', rgba(ramp(s_, [(0, (220, 40, 255)), (0.5, (255, 160, 250)), (1, (255, 255, 255))]), s_))

# éclairs (4 formes)
for k in range(4):
    rng = np.random.default_rng(300 + k)
    img = Image.new('RGBA', (128, 128), (0, 0, 0, 0))
    from PIL import ImageDraw
    dr = ImageDraw.Draw(img)
    def bolt(x0, y0, x1, y1, w, depth):
        pts = [(x0, y0)]
        segs = 7
        for i in range(1, segs):
            t = i / segs
            pts.append((x0 + (x1 - x0) * t + rng.uniform(-10, 10), y0 + (y1 - y0) * t + rng.uniform(-10, 10)))
        pts.append((x1, y1))
        for wdt, col in [(w + 6, (190, 40, 255, 110)), (w + 2, (240, 120, 255, 255)), (max(1, w - 1), (255, 245, 255, 255))]:
            dr.line(pts, fill=col, width=wdt, joint='curve')
        if depth > 0:
            i = rng.integers(2, segs - 1)
            bx, by = pts[i]
            bolt(bx, by, bx + rng.uniform(-40, 40), by + rng.uniform(10, 40), max(1, w - 2), depth - 1)
    bolt(64 + rng.uniform(-10, 10), 4, 64 + rng.uniform(-20, 20), 124, 4, 2)
    img.save(os.path.join(T, 'bolt_%d.png' % k))

# explosion violette avec éclats sombres (16 images)
base = tile_fbm(256, 4, 240); warp = tile_fbm(256, 3, 245); holes = tile_fbm(256, 6, 249)
n = 256; x, y = grid(n); r = np.sqrt(x * x + y * y); th = np.arctan2(y, x) / (2 * np.pi) + 0.5
for f in range(16):
    t = f / 15; e = 1 - (1 - t) ** 2.4
    R = 0.25 + 0.75 * e
    w_ = sample(warp, th * 2, r * 0.6 - t * 0.5) * 0.3
    tong = sample(base, th * 3 + w_, r * 0.9 - t * 1.4)
    spikes = np.abs(np.sin((th + w_ * 0.2) * np.pi * 14)) ** 4
    shape = R * (0.5 + 0.35 * spikes + 0.2 * (tong - 0.5))
    d = np.clip(1 - r / np.maximum(shape, 1e-3), 0, 1) ** 0.8 * (1.15 - t * 0.5) + (tong - 0.5) * 0.25
    if t > 0.5:
        h = sample(holes, y * 0.5 + 0.5 + t * 0.2, x * 0.5 + 0.5)
        d = d * np.clip((h - (t - 0.5) * 1.4 + 0.35) / 0.12, 0, 1)
    img = cel(d, PURP, VIOL, alpha_cut=0.12 + t * 0.08)
    # éclats sombres en pointe autour
    sh = np.abs(np.sin((th + 0.03) * np.pi * 9)) ** 12
    m = (sh > 0.6) & (r > R * 0.7) & (r < R * (1.05 + 0.3 * sh)) & (sample(holes, th, r) > 0.35)
    img[m] = [36, 6, 70, 255]
    save('pburst_%d' % f, img)

# éclats violets qui volent (4 formes)
for k in range(4):
    rng = np.random.default_rng(400 + k)
    img = Image.new('RGBA', (128, 128), (0, 0, 0, 0)); dr = ImageDraw.Draw(img)
    L = rng.uniform(70, 120); W = rng.uniform(14, 26)
    pts = [(64 - L / 2, 64), (64 - L / 6, 64 - W), (64 + L / 2, 64 - rng.uniform(-6, 6)), (64 - L / 8, 64 + W * 0.8)]
    dr.polygon(pts, fill=(40, 6, 80, 255), outline=(10, 0, 25, 255))
    inner = [(64 - L / 3, 64), (64 - L / 8, 64 - W * 0.45), (64 + L / 3, 64), (64 - L / 10, 64 + W * 0.35)]
    dr.polygon(inner, fill=(150, 40, 230, 255))
    dr.line([(64 - L / 4, 64), (64 + L / 4, 64)], fill=(255, 190, 255, 255), width=2)
    img.save(os.path.join(T, 'shard_%d.png' % k))

# ondes violettes (sol et face caméra)
n = 256; x, y = grid(n); r = np.sqrt(x * x + y * y)
v = np.exp(-((r - 0.84) / 0.06) ** 2) + np.exp(-((r - 0.84) / 0.015) ** 2)
save('shock_purple', rgba(ramp(np.clip(v, 0, 1), [(0, (190, 60, 255)), (1, (255, 240, 255))]), np.clip(v * 0.95, 0, 1)))

# écran blanc avec éclats violets / roses / bleus qui passent (8 images plein écran, police)
FF = os.path.join(OUT, 'assets/limitless/textures/font')
for f in range(8):
    rng = np.random.default_rng(500 + f)
    img = Image.new('RGBA', (256, 256), (255, 255, 255, 255)); dr = ImageDraw.Draw(img)
    for s_i in range(5):
        cx, cy = rng.uniform(0, 256), rng.uniform(0, 256)
        ang = rng.uniform(-0.9, -0.3) if rng.random() < 0.7 else rng.uniform(0.3, 0.9)
        L = rng.uniform(40, 110); W = rng.uniform(4, 12)
        dx, dy = math.cos(ang) * L / 2, math.sin(ang) * L / 2
        nx, ny = -math.sin(ang) * W, math.cos(ang) * W
        col = [(170, 60, 255, 170), (255, 120, 220, 150), (90, 140, 255, 140), (60, 10, 110, 190)][rng.integers(0, 4)]
        dr.polygon([(cx - dx, cy - dy), (cx + nx * 0.3, cy + ny * 0.3), (cx + dx, cy + dy), (cx - nx, cy - ny)], fill=col)
    img = img.filter(__import__('PIL.ImageFilter', fromlist=['GaussianBlur']).GaussianBlur(1.2))
    img.save(os.path.join(FF, 'whiteout_%d.png' % f))
print('ok')
