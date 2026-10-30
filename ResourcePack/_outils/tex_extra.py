"""Retouches + textures en plus :
- colonne du Fûga 100 % opaque (sinon elle paraît transparente et disparaît derrière les autres effets, surtout avec des shaders)
- Violet Imaginaire : traits de lumière qui partent du centre, encre violette sombre qui gicle (comme sur la vidéo)"""
from texlib import *
from PIL import Image, ImageDraw
import math

# ---- colonne opaque
for n in ['column', 'column_core']:
    p = os.path.join(T, n + '.png')
    a = np.array(Image.open(p).convert('RGBA'))
    a[..., 3] = np.where(a[..., 3] > 60, 255, 0)
    Image.fromarray(a).save(p)

# ---- trait de lumière (blanc au centre, rose, bord violet), long et effilé
W, H = 64, 256
y, x = np.mgrid[0:H, 0:W]
u = (y + 0.5) / H                       # 0 = bout pointu, 1 = base
half = (0.08 + 0.42 * u ** 1.6) * W / 2  # largeur qui grossit vers la base
dx = np.abs(x + 0.5 - W / 2)
d = np.clip(1 - dx / np.maximum(half, 0.5), 0, 1)
d *= np.clip((1 - u) * 6, 0, 1) * 0 + np.clip(u * 8, 0, 1)   # pointe nette
col = np.zeros((H, W, 3)); col[:] = (150, 40, 230)
col[d > 0.25] = (230, 90, 255); col[d > 0.55] = (255, 190, 255); col[d > 0.8] = (255, 255, 255)
save('spike_purple', rgba(col, (d > 0.04).astype(float)))

# ---- encre violette sombre (4 formes) : traînées courbes, déchiquetées, reflet violet clair sur un bord
for k in range(4):
    rng = np.random.default_rng(700 + k)
    S = 512
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0)); dr = ImageDraw.Draw(img)
    hl = Image.new('RGBA', (S, S), (0, 0, 0, 0)); dh = ImageDraw.Draw(hl)
    # une tige principale qui part du bas-gauche (côté centre de l'explosion) et se courbe
    a0 = rng.uniform(-0.3, 0.3); curv = rng.uniform(-0.035, 0.035)
    px, py = 120.0, S - 120.0; ang = -math.pi / 4 + a0
    left, right, hi = [], [], []
    steps = 34
    for i in range(steps):
        t = i / (steps - 1)
        w = (1 - t) ** 0.6 * 34 * (0.85 + 0.3 * math.sin(t * 9 + k)) + 3 + (rng.uniform(0, 10) if rng.random() < 0.25 else 0)
        nx, ny = -math.sin(ang), math.cos(ang)
        left.append((px + nx * w, py + ny * w)); right.append((px - nx * w * 0.8, py - ny * w * 0.8))
        hi.append((px + nx * w * 0.45, py + ny * w * 0.45))
        px += math.cos(ang) * 9; py += math.sin(ang) * 9
        ang += curv * 1.6 + rng.uniform(-0.06, 0.06)
        # petites branches / gouttes
        if 6 < i < steps - 6 and rng.random() < 0.18:
            side = rng.choice([-1, 1]); bl = rng.uniform(30, 70); ba = ang + side * rng.uniform(0.6, 1.1)
            bx, by = px + math.cos(ba) * bl, py + math.sin(ba) * bl
            dr.polygon([(px + nx * 9, py + ny * 9), (bx, by), (px - nx * 9, py - ny * 9)], fill=(34, 6, 66, 255))
        if rng.random() < 0.12:
            gx, gy = px + rng.uniform(-30, 30), py + rng.uniform(-30, 30); gr = rng.uniform(6, 13)
            dr.ellipse([gx - gr, gy - gr, gx + gr, gy + gr], fill=(34, 6, 66, 255))
    dr.polygon(left + right[::-1], fill=(34, 6, 66, 255))
    dh.line(hi[2:-6], fill=(170, 80, 255, 255), width=7)
    a = np.array(img); h = np.array(hl)
    inside = a[..., 3] > 0
    m = (h[..., 3] > 0) & inside
    a[m] = h[m]
    # contour très sombre
    from scipy.ndimage import binary_erosion
    rim = inside & ~binary_erosion(inside, iterations=3)
    a[rim] = (12, 0, 28, 255)
    im = Image.fromarray(a); bb = im.getbbox(); im = im.crop(bb)
    side = max(im.size) + 16; sq = Image.new('RGBA', (side, side), (0, 0, 0, 0))
    sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    sq.resize((256, 256), Image.NEAREST).save(os.path.join(T, 'ink_%d.png' % k))

# ---- anneau rose épais (onde de l'explosion, face caméra)
n = 256; x, y = grid(n); r = np.sqrt(x * x + y * y)
v = np.clip(1 - np.abs(r - 0.8) / 0.12, 0, 1)
col = np.zeros((n, n, 3)); col[:] = (200, 60, 255); col[v > 0.4] = (255, 140, 250); col[v > 0.75] = (255, 245, 255)
save('ring_pink', rgba(col, (v > 0.05).astype(float)))
print('ok')

# ---- images d'impact (plein écran, police) : noir/blanc/violet avec lignes de vitesse, style anime
FF = os.path.join(OUT, 'assets/limitless/textures/font')
S = 256
def impact(bg, line, center, n_lines, seed, hole):
    rng = np.random.default_rng(seed)
    img = Image.new('RGBA', (S * 2, S), bg); dr = ImageDraw.Draw(img)
    c = S / 2; cx0 = S
    for i in range(n_lines):
        a = rng.uniform(0, 2 * math.pi); w = rng.uniform(0.012, 0.05)
        r0 = rng.uniform(hole * 0.9, hole * 1.6); r1 = S * 1.6
        pts = [(cx0 + math.cos(a) * r0, c + math.sin(a) * r0), (cx0 + math.cos(a + w) * r1, c + math.sin(a + w) * r1),
               (cx0 + math.cos(a - w) * r1, c + math.sin(a - w) * r1)]
        dr.polygon(pts, fill=line)
    if center: dr.ellipse([cx0 - hole, c - hole, cx0 + hole, c + hole], fill=center)
    img.save(os.path.join(FF, 'impact_%d.png' % seed))
impact((0, 0, 0, 255), (255, 255, 255, 255), (255, 255, 255, 255), 110, 0, 26)
impact((255, 255, 255, 255), (0, 0, 0, 255), (20, 0, 40, 255), 110, 1, 34)
impact((150, 30, 230, 255), (255, 210, 255, 255), (255, 255, 255, 255), 100, 2, 40)
impact((25, 0, 45, 255), (200, 80, 255, 255), (255, 255, 255, 255), 120, 3, 18)
print('impact ok')

# ================================================================ BLACK FLASH (éclairs rouges à cœur noir, comme dans la vidéo)
from PIL import ImageFilter
def jag_bolt(dr, rng, x0, y0, x1, y1, segs, amp, widths, cols, depth):
    pts = [(x0, y0)]
    for i in range(1, segs):
        t = i / segs
        pts.append((x0 + (x1 - x0) * t + rng.uniform(-amp, amp), y0 + (y1 - y0) * t + rng.uniform(-amp * 0.4, amp * 0.4)))
    pts.append((x1, y1))
    for wdt, col in zip(widths, cols):
        dr.line(pts, fill=col, width=wdt, joint='curve')
    if depth > 0:
        for _ in range(2):
            i = int(rng.integers(1, segs - 1)); bx, by = pts[i]
            ang = math.atan2(y1 - y0, x1 - x0) + rng.choice([-1, 1]) * rng.uniform(0.4, 1.1)
            L = math.hypot(x1 - x0, y1 - y0) * rng.uniform(0.25, 0.45)
            jag_bolt(dr, rng, bx, by, bx + math.cos(ang) * L, by + math.sin(ang) * L, max(3, segs // 2), amp * 0.7,
                     [max(1, w // 2 + 1) for w in widths], cols, depth - 1)

RED_GLOW, RED, DARK, BLACK_ = (255, 20, 40, 150), (255, 35, 45, 255), (120, 0, 10, 255), (8, 0, 2, 255)
for k in range(4):
    rng = np.random.default_rng(1200 + k)
    img = Image.new('RGBA', (64, 256), (0, 0, 0, 0)); dr = ImageDraw.Draw(img)
    jag_bolt(dr, rng, 32, 2, 32 + rng.uniform(-6, 6), 254, 12, 11, [16, 9, 5, 2], [RED_GLOW, RED, DARK, BLACK_], 2)
    img = Image.alpha_composite(img.filter(ImageFilter.GaussianBlur(2.5)), img)
    a = np.array(img); a[..., 3] = np.where(a[..., 3] > 30, np.maximum(a[..., 3], 200), 0); img = Image.fromarray(a)
    img.save(os.path.join(T, 'bf_bolt_%d.png' % k))

# étoile à 4 branches rouge, cœur noir, liseré blanc
n = 256; x, y = grid(n)
def star4(scale):
    return np.clip(1 - (np.abs(x / scale) ** 0.45 + np.abs(y / scale) ** 0.45) / 1.0, 0, 1)
s_out, s_mid, s_in = star4(1.0), star4(0.82), star4(0.5)
col = np.zeros((n, n, 3)); a = np.zeros((n, n))
m = s_out > 0.02; col[m] = (255, 255, 255); a[m] = 1
m = s_mid > 0.02; col[m] = (255, 30, 45)
m = s_in > 0.02; col[m] = (10, 0, 4)
save('bf_star', rgba(col, a))

# pointes blanches déchiquetées qui jaillissent (le "blanc" du black flash)
rng = np.random.default_rng(1300)
img = Image.new('RGBA', (256, 256), (0, 0, 0, 0)); dr = ImageDraw.Draw(img)
for i in range(16):
    a0 = i * math.pi * 2 / 16 + rng.uniform(-0.15, 0.15); L = rng.uniform(80, 126); W = rng.uniform(0.16, 0.28)
    tip = (128 + math.cos(a0) * L, 128 + math.sin(a0) * L)
    b1 = (128 + math.cos(a0 + W) * 22, 128 + math.sin(a0 + W) * 22)
    b2 = (128 + math.cos(a0 - W) * 22, 128 + math.sin(a0 - W) * 22)
    mid = (128 + math.cos(a0 + W * 0.3) * L * 0.55 + rng.uniform(-6, 6), 128 + math.sin(a0 + W * 0.3) * L * 0.55 + rng.uniform(-6, 6))
    dr.polygon([b1, mid, tip, b2], fill=(255, 255, 255, 255), outline=(10, 0, 4, 255))
    dr.line([b1, tip], fill=(255, 60, 70, 255), width=1)
save_img = img
save_img.save(os.path.join(T, 'bf_spikes.png'))

# images d'impact plein écran (police) : noir fissuré de rouge / rouge fissuré de noir
FF = os.path.join(OUT, 'assets/limitless/textures/font')
for k, (bg, cols) in enumerate([((4, 0, 2, 255), [(255, 30, 45, 160), (255, 35, 45, 255), (255, 200, 200, 255)]),
                                ((170, 0, 18, 255), [(60, 0, 6, 200), (10, 0, 2, 255), (255, 255, 255, 255)])]):
    rng = np.random.default_rng(1400 + k)
    img = Image.new('RGBA', (512, 256), bg); dr = ImageDraw.Draw(img)
    for i in range(18):
        a0 = rng.uniform(0, math.pi * 2)
        jag_bolt(dr, rng, 256, 128, 256 + math.cos(a0) * 420, 128 + math.sin(a0) * 260, 10, 22, [7, 3, 1], cols, 1)
    dr.ellipse([236, 108, 276, 148], fill=cols[2])
    img.save(os.path.join(FF, 'bf_impact_%d.png' % k))
print('black flash ok')
