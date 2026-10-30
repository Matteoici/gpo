"""Interfaces perso Limitless : vraies textures du pack (inventaire + HUD) pour 3 styles, et aperçus en jeu.
Les textures sont en 2x (1 pixel d'interface = 2 texels) : plus fin que le vanilla, même mise en page.
usage : gui.py <dossier de sortie> <fond in-game 1920x1080> <dossier textures item vanilla>"""
import numpy as np, os, sys, math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT, BGF, ITEMS = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)
S = 2  # texels par pixel d'interface
rng = np.random.default_rng(7)

def hx(s, a=255):
    s = s.lstrip('#'); return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)

def blank(w, h):
    return np.zeros((h, w, 4), np.float32)

def put(a, x, y, c):
    if 0 <= y < a.shape[0] and 0 <= x < a.shape[1]:
        a[y, x] = c

def over(dst, src, x=0, y=0):
    """Colle src (RGBA float) sur dst avec alpha."""
    h, w = src.shape[:2]
    x0, y0 = max(0, x), max(0, y); x1, y1 = min(dst.shape[1], x + w), min(dst.shape[0], y + h)
    if x1 <= x0 or y1 <= y0: return
    s = src[y0 - y:y1 - y, x0 - x:x1 - x]; d = dst[y0:y1, x0:x1]
    sa = s[..., 3:4] / 255; da = d[..., 3:4] / 255
    oa = sa + da * (1 - sa)
    rgb = (s[..., :3] * sa + d[..., :3] * da * (1 - sa)) / np.maximum(oa, 1e-6)
    d[..., :3] = rgb; d[..., 3:4] = oa * 255

def mask_shape(w, h, kind, r):
    yy, xx = np.mgrid[0:h, 0:w]
    if kind == 'cut':      # coins coupés (cyber)
        m = (xx + yy >= r) & ((w - 1 - xx) + yy >= r) & (xx + (h - 1 - yy) >= r) & ((w - 1 - xx) + (h - 1 - yy) >= r)
    elif kind == 'round':
        cx = np.clip(xx, r, w - 1 - r); cy = np.clip(yy, r, h - 1 - r)
        m = (xx - cx) ** 2 + (yy - cy) ** 2 <= r * r + r * 0.6
    else:
        m = np.ones((h, w), bool)
    return m

def erode(m, n=1):
    for _ in range(n):
        m = m & np.roll(m, 1, 0) & np.roll(m, -1, 0) & np.roll(m, 1, 1) & np.roll(m, -1, 1)
        m[0, :] = m[-1, :] = False; m[:, 0] = m[:, -1] = False
    return m

def blur_alpha(a, r):
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r))
    return np.array(im).astype(np.float32)

# ============================================================================ STYLES
class Style:
    name = ''; title = ''
    def panel(self, w, h): ...
    def slot(self, big=False): ...
    def label(self): ...

# ---------------------------------------------------------------------------- 1. BLACK WALL (néon rouge, coins coupés)
class BlackWall(Style):
    name = 'blackwall'; title = 'Style 1 · Black Wall (verre noir, néon rouge)'
    RED = np.array([255, 36, 60]); TXT = (255, 214, 220); TXT2 = (255, 70, 90)
    def panel(self, w, h):
        a = blank(w, h); r = 14
        m = mask_shape(w, h, 'cut', r)
        yy, xx = np.mgrid[0:h, 0:w]
        base = np.stack([8 + 6 * yy / h, 6 + 2 * yy / h, 10 + 4 * yy / h], -1)
        # trame : lignes de balayage + grille fine
        base += ((yy % 4) == 0)[..., None] * 3
        grid = ((xx % 24) == 0) | ((yy % 24) == 0)
        base += grid[..., None] * np.array([10, 2, 4])
        a[m, :3] = base[m]; a[m, 3] = 246
        # néon : bord 1 texel rouge vif + lueur intérieure
        e1 = m & ~erode(m, 2); e2 = erode(m, 2) & ~erode(m, 3)
        a[e1] = [20, 0, 4, 255]
        a[e2, :3] = self.RED; a[e2, 3] = 255
        glow = np.zeros((h, w, 4), np.float32); glow[e2] = [255, 36, 60, 255]
        g = blur_alpha(glow, 4); g[..., 3] *= 0.55; g[~erode(m, 3)] = 0
        over(a, g)
        # petites marques sur les coins coupés et le haut
        for x0 in range(r + 10, w - r - 10, 10):
            if (x0 // 10) % 5 == 0: a[4:6, x0:x0 + 6, :3] = self.RED; a[4:6, x0:x0 + 6, 3] = 255
        for (cx, cy, dx, dy) in [(0, 0, 1, 1), (w - 1, 0, -1, 1), (0, h - 1, 1, -1), (w - 1, h - 1, -1, -1)]:
            for k in range(6):
                put(a, cx + dx * (r + 2 + k), cy + dy * 5, [*self.RED, 255])
                put(a, cx + dx * 5, cy + dy * (r + 2 + k), [*self.RED, 255])
        return a
    def slot(self, big=False):
        n = 36 if not big else 52
        a = blank(n, n)
        a[:, :] = [6, 4, 8, 255]
        a[0, :] = a[-1, :] = a[:, 0] = a[:, -1] = [58, 12, 22, 255]
        L = 6 if not big else 9
        for (x, y, dx, dy) in [(0, 0, 1, 1), (n - 1, 0, -1, 1), (0, n - 1, 1, -1), (n - 1, n - 1, -1, -1)]:
            for k in range(L):
                put(a, x + dx * k, y, [255, 36, 60, 255]); put(a, x, y + dy * k, [255, 36, 60, 255])
        a[1, 1:-1, :3] += 6
        return a
    def heart(self, kind):
        return heart_icon(kind, fill=hx('ff2440'), light=hx('ffb3be'), dark=hx('7a0012'), line=hx('140004'), empty=hx('2a0a10'),
                          emptyline=hx('ff2440', 160), glow=True)
    def food(self, kind):
        return food_icon(kind, meat=hx('ff5a3c'), light=hx('ffc2a8'), dark=hx('8a1a10'), bone=hx('ffe8ec'), line=hx('140004'),
                         empty=hx('2a0a10'), emptyline=hx('ff2440', 150))
    def armor(self, kind):
        return armor_icon(kind, fill=hx('c8ccd8'), light=hx('ffffff'), dark=hx('5a5e70'), line=hx('140004'), accent=hx('ff2440'),
                          empty=hx('22161c'), emptyline=hx('ff2440', 120))
    def hotbar(self):
        w, h = 182 * S, 22 * S
        a = self.panel(w, h)
        for i in range(1, 9):
            x = (1 + 20 * i) * S - 1
            a[6:h - 6, x, :3] = [90, 10, 24]; a[6:h - 6, x, 3] = 255
        return a
    def selection(self):
        w, h = 24 * S, 23 * S
        a = blank(w, h); m = mask_shape(w, h, 'cut', 6)
        e = m & ~erode(m, 2)
        a[e, :3] = [255, 214, 220]; a[e, 3] = 255
        e2 = erode(m, 2) & ~erode(m, 3); a[e2, :3] = self.RED; a[e2, 3] = 255
        g = blank(w, h); g[e] = [255, 36, 60, 255]; g = blur_alpha(g, 3); g[..., 3] *= 0.7
        over(g, a); return g
    def xp(self, frac):
        w, h = 182 * S, 5 * S
        a = blank(w, h); a[:, :] = [14, 4, 8, 235]
        a[0, :] = a[-1, :] = a[:, 0] = a[:, -1] = [90, 10, 24, 255]
        fw = int(w * frac)
        yy = np.arange(h)[:, None]
        if fw > 2:
            a[2:-2, 2:fw, :3] = np.array([255, 36, 60]) * (0.75 + 0.25 * ((np.arange(2, fw)[:, None] // 4) % 2))[None, :, :]
            a[2:-2, 2:fw, 3] = 255; a[2, 2:fw, :3] = [255, 170, 182]
        for x in range(0, w, 18 * S):
            a[1:-1, x:x + 1, :3] = [30, 6, 12]; a[1:-1, x:x + 1, 3] = 255
        return a
    def xp_color(self): return (255, 60, 80)
    def arrow(self):
        return arrow_icon(hx('ff2440'), hx('7a0012'))
    def player_box(self, w, h):
        a = blank(w, h); a[:, :] = [3, 2, 4, 255]
        a[0, :] = a[-1, :] = a[:, 0] = a[:, -1] = [58, 12, 22, 255]
        yy = np.arange(h); a[(yy % 6) == 0, 1:-1, :3] += 5
        return a

# ---------------------------------------------------------------------------- 2. ROYAL (pierre d'obsidienne, cadre d'or, gemmes)
class Royal(Style):
    name = 'royal'; title = 'Style 2 · Royal (obsidienne & or)'
    TXT = (255, 226, 150); TXT2 = (255, 200, 90)
    G0, G1, G2, G3 = hx('3a2508'), hx('8a6018'), hx('d6a63a'), hx('ffe9a0')
    def stone(self, w, h):
        yy, xx = np.mgrid[0:h, 0:w]
        n = rng.normal(0, 1, (h // 4 + 2, w // 4 + 2))
        n = np.array(Image.fromarray(((n - n.min()) / (n.max() - n.min()) * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)).astype(np.float32) / 255
        c0 = np.array([22, 14, 30]); c1 = np.array([46, 28, 62])
        base = c0 + (c1 - c0) * n[..., None]
        # veines violettes
        v = np.abs(np.sin(xx * 0.05 + yy * 0.08 + n * 6)) < 0.04
        base[v] = [90, 50, 120]
        return base
    def frame(self, a, m, width=6):
        rings = [m & ~erode(m, 1)]
        for k in range(1, width): rings.append(erode(m, k) & ~erode(m, k + 1))
        cols = [hx('1a0f04'), self.G1, self.G3, self.G2, self.G2, self.G1, hx('1a0f04')]
        for i, rr in enumerate(rings):
            a[rr] = cols[min(i, len(cols) - 1)]
        return erode(m, width)
    def panel(self, w, h):
        a = blank(w, h)
        m = mask_shape(w, h, 'round', 6)
        inner = self.frame(a, m, 7)
        st = self.stone(w, h); a[inner, :3] = st[inner]; a[inner, 3] = 252
        # ombre intérieure
        sh = np.zeros((h, w, 4), np.float32); sh[~inner] = [0, 0, 0, 255]
        sh = blur_alpha(sh, 3); sh[..., 3] *= 0.6; sh[~inner] = 0; over(a, sh)
        # filigrane d'or au milieu du haut et du bas
        for cy, dy in ((9, 1), (h - 10, -1)):
            for t in np.linspace(-1, 1, 160):
                x = int(w / 2 + t * 70); y = int(cy + dy * (3 * math.cos(t * 9) * (1 - abs(t))))
                put(a, x, y, self.G2); put(a, x, y + dy, self.G1)
        # gemmes dans les coins
        for (cx, cy) in [(7, 7), (w - 8, 7), (7, h - 8), (w - 8, h - 8)]:
            gem(a, cx, cy, 6, hx('b01a3a'), hx('ff6a8a'), hx('4a0010'), self.G2, self.G3)
        return a
    def slot(self, big=False):
        n = 36 if not big else 52
        a = blank(n, n)
        a[:, :] = [12, 8, 16, 255]
        a[0, :] = a[:, 0] = list(self.G1)
        a[-1, :] = a[:, -1] = list(self.G2)
        a[1, 1:-1] = a[1:-1, 1] = [5, 3, 8, 255]
        if big:
            a[1, 1:-1] = list(self.G3); a[-2, 1:-1] = list(self.G1)
        return a
    def heart(self, kind):
        return heart_icon(kind, fill=hx('d01836'), light=hx('ff8aa0'), dark=hx('6a0418'), line=hx('3a2508'), empty=hx('2a1a20'),
                          emptyline=hx('8a6018'), rim=hx('d6a63a'))
    def food(self, kind):
        return food_icon(kind, meat=hx('c8642a'), light=hx('ffc078'), dark=hx('6a2a0a'), bone=hx('ffe9a0'), line=hx('2a1a04'),
                         empty=hx('2a1a20'), emptyline=hx('8a6018'))
    def armor(self, kind):
        return armor_icon(kind, fill=hx('d6a63a'), light=hx('ffe9a0'), dark=hx('6a4a10'), line=hx('2a1a04'), accent=hx('b01a3a'),
                          empty=hx('2a1a20'), emptyline=hx('8a6018'))
    def hotbar(self):
        w, h = 182 * S, 22 * S
        a = blank(w, h); m = mask_shape(w, h, 'round', 4)
        inner = self.frame(a, m, 4)
        st = self.stone(w, h); a[inner, :3] = st[inner]; a[inner, 3] = 245
        for i in range(1, 9):
            x = (1 + 20 * i) * S - 1
            a[5:h - 5, x] = list(self.G1); a[5:h - 5, x + 1] = list(self.G3)
        return a
    def selection(self):
        w, h = 24 * S, 23 * S
        a = blank(w, h); m = mask_shape(w, h, 'round', 4)
        rings = [m & ~erode(m, 1), erode(m, 1) & ~erode(m, 2), erode(m, 2) & ~erode(m, 3), erode(m, 3) & ~erode(m, 4)]
        for rr, c in zip(rings, [hx('1a0f04'), self.G3, self.G2, self.G1]): a[rr] = c
        gem(a, w // 2, 2, 3, hx('b01a3a'), hx('ff6a8a'), hx('4a0010'), self.G2, self.G3)
        return a
    def xp(self, frac):
        w, h = 182 * S, 5 * S
        a = blank(w, h); a[:, :] = [20, 12, 26, 240]
        a[0, :] = list(self.G1); a[-1, :] = list(self.G1); a[:, 0] = list(self.G1); a[:, -1] = list(self.G1)
        fw = int(w * frac)
        a[2:-2, 2:fw] = list(self.G2); a[2, 2:fw] = list(self.G3); a[-3, 2:fw] = list(self.G1)
        for x in range(0, w, 18 * S): a[0:3, x] = list(self.G3)
        return a
    def xp_color(self): return (255, 226, 120)
    def arrow(self):
        return arrow_icon(self.G2, self.G1)
    def player_box(self, w, h):
        a = blank(w, h); a[:, :] = [6, 4, 9, 255]
        a[0, :] = a[:, 0] = list(self.G1); a[-1, :] = a[:, -1] = list(self.G2)
        return a

# ---------------------------------------------------------------------------- 3. GIVRE (verre dépoli transparent, moderne, arrondi)
class Frost(Style):
    name = 'givre'; title = 'Style 3 · Givre (verre dépoli, moderne)'
    TXT = (240, 248, 255); TXT2 = (150, 220, 255)
    ACC = np.array([120, 210, 255])
    def panel(self, w, h):
        a = blank(w, h)
        m = mask_shape(w, h, 'round', 12)
        yy, xx = np.mgrid[0:h, 0:w]
        a[m, :3] = np.stack([18 + 0 * yy, 24 + 0 * yy, 36 + 0 * yy], -1)[m]
        a[m, 3] = (175 - 25 * yy / h)[m]
        # reflet en haut
        hl = m & (yy < h * 0.28)
        a[hl, :3] += 14 * (1 - yy[hl] / (h * 0.28))[:, None]
        e = m & ~erode(m, 1); a[e] = [255, 255, 255, 95]
        e2 = erode(m, 1) & ~erode(m, 2); a[e2] = [255, 255, 255, 30]
        a[2, 14:w - 14] = [255, 255, 255, 120]
        return a
    def slot(self, big=False):
        n = 36 if not big else 52
        a = blank(n, n)
        m = mask_shape(n, n, 'round', 5)
        a[m] = [255, 255, 255, 22]
        e = m & ~erode(m, 1); a[e] = [255, 255, 255, 48]
        if big: a[e] = [*self.ACC, 160]
        return a
    def heart(self, kind):
        return heart_icon(kind, fill=hx('ff5c8a'), light=hx('ffd6e2'), dark=hx('b02856'), line=hx('ffffff', 210), empty=hx('ffffff', 40),
                          emptyline=hx('ffffff', 140), soft=True)
    def food(self, kind):
        return food_icon(kind, meat=hx('ffa45c'), light=hx('ffe0c2'), dark=hx('c0602a'), bone=hx('ffffff'), line=hx('ffffff', 210),
                         empty=hx('ffffff', 40), emptyline=hx('ffffff', 140))
    def armor(self, kind):
        return armor_icon(kind, fill=hx('9adcff'), light=hx('e8f8ff'), dark=hx('4a8ab8'), line=hx('ffffff', 210), accent=hx('ffffff'),
                          empty=hx('ffffff', 40), emptyline=hx('ffffff', 140))
    def hotbar(self):
        w, h = 182 * S, 22 * S
        a = blank(w, h); m = mask_shape(w, h, 'round', 10)
        a[m] = [16, 22, 34, 150]
        e = m & ~erode(m, 1); a[e] = [255, 255, 255, 80]
        for i in range(1, 9):
            x = (1 + 20 * i) * S - 1
            a[10:h - 10, x] = [255, 255, 255, 40]
        return a
    def selection(self):
        w, h = 24 * S, 23 * S
        a = blank(w, h); m = mask_shape(w, h, 'round', 9)
        e = m & ~erode(m, 2); a[e] = [255, 255, 255, 235]
        a[erode(m, 2)] = [255, 255, 255, 26]
        g = blank(w, h); g[e] = [*self.ACC, 255]; g = blur_alpha(g, 3); g[..., 3] *= 0.6; over(g, a)
        return g
    def xp(self, frac):
        w, h = 182 * S, 5 * S
        a = blank(w, h); m = mask_shape(w, h, 'round', 4)
        a[m] = [255, 255, 255, 45]
        fw = int(w * frac)
        f = m.copy(); f[:, fw:] = False
        xx = np.arange(w)[None, :].repeat(h, 0)
        col = np.stack([120 + 60 * xx / w, 210 + 20 * xx / w, 255 + 0 * xx], -1)
        a[f, :3] = col[f]; a[f, 3] = 255
        a[1, :fw][m[1, :fw]] = [255, 255, 255, 200]
        return a
    def xp_color(self): return (150, 230, 255)
    def arrow(self):
        return arrow_icon(hx('ffffff', 220), hx('ffffff', 90))
    def player_box(self, w, h):
        a = blank(w, h); m = mask_shape(w, h, 'round', 6)
        a[m] = [0, 0, 0, 70]; e = m & ~erode(m, 1); a[e] = [255, 255, 255, 40]
        return a

# ============================================================================ icônes (18 x 18 texels = 9 x 9 pixels d'interface)
HEART = ["..XXX.XXX..", ".XXXXXXXXX.", "XXXXXXXXXXX", "XXXXXXXXXXX", "XXXXXXXXXXX", ".XXXXXXXXX.", "..XXXXXXX..", "...XXXXX...", "....XXX....", ".....X....."]
def shape_from(rows, n=18):
    h, w = len(rows), len(rows[0])
    m = np.array([[c == 'X' for c in r] for r in rows])
    im = Image.fromarray((m * 255).astype(np.uint8)).resize((n - 2, int((n - 2) * h / w)), Image.LANCZOS)
    out = np.zeros((n, n), bool); arr = np.array(im) > 110
    oy = (n - arr.shape[0]) // 2
    out[oy:oy + arr.shape[0], 1:1 + arr.shape[1]] = arr
    return out

def shade(m, fill, light, dark):
    a = blank(m.shape[1], m.shape[0])
    a[m] = fill
    yy, xx = np.mgrid[0:m.shape[0], 0:m.shape[1]]
    hl = m & ~np.roll(m, 2, 0) | m & ~np.roll(m, 2, 1)
    a[hl & (yy < m.shape[0] * 0.6)] = light
    sh = m & ~np.roll(m, -2, 0)
    a[sh] = dark
    return a

def outline(a, m, line):
    o = (np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1)) & ~m
    a[o] = line
    return a

def heart_icon(kind, fill, light, dark, line, empty, emptyline, glow=False, rim=None, soft=False):
    m = shape_from(HEART)
    if kind == 'empty':
        a = blank(18, 18); a[m] = empty; outline(a, m, emptyline); return a
    a = shade(m, fill, light, dark)
    if kind == 'half':
        a[:, 9:] = 0; e = blank(18, 18); mm = m.copy(); mm[:, :9] = False; e[mm] = empty; over(e, a); a = e
    yy, xx = np.mgrid[0:18, 0:18]
    a[(m) & (xx >= 4) & (xx <= 5) & (yy >= 4) & (yy <= 5) & (a[..., 3] > 0)] = light if not soft else hx('ffffff')
    outline(a, m, rim if rim is not None else line)
    if rim is not None:
        o2 = (np.roll(m, 2, 0) | np.roll(m, -2, 0) | np.roll(m, 2, 1) | np.roll(m, -2, 1)) & ~m & (a[..., 3] == 0)
        a[o2] = line
    if glow:
        g = blank(18, 18); g[m] = fill; g = blur_alpha(g, 1.6); g[..., 3] *= 0.8; over(g, a); a = g
    return a

DRUM = ["......XXX..", ".....XXXXX.", "....XXXXXXX", "...XXXXXXXX", "...XXXXXXX.", "...XXXXXX..", "..XX.XXX...", ".XX........", "XX.........", "X.........."]
def food_icon(kind, meat, light, dark, bone, line, empty, emptyline):
    m = shape_from(DRUM)
    a = blank(18, 18)
    if kind == 'empty':
        a[m] = empty; outline(a, m, emptyline); return a
    yy, xx = np.mgrid[0:18, 0:18]
    bonem = m & (xx + (17 - yy) < 13)
    meatm = m & ~bonem
    a = shade(meatm, meat, light, dark); a[bonem] = bone
    if kind == 'half':
        a[:, :8] = 0; mm = m.copy(); mm[:, 8:] = False; a[mm] = empty
    outline(a, m, line)
    return a

SHIELD = ["XXXXXXXXXXX", "XXXXXXXXXXX", "XXXXXXXXXXX", "XXXXXXXXXXX", ".XXXXXXXXX.", ".XXXXXXXXX.", "..XXXXXXX..", "...XXXXX...", "....XXX....", ".....X....."]
def armor_icon(kind, fill, light, dark, line, accent, empty, emptyline):
    m = shape_from(SHIELD)
    if kind == 'empty':
        a = blank(18, 18); a[m] = empty; outline(a, m, emptyline); return a
    a = shade(m, fill, light, dark)
    yy, xx = np.mgrid[0:18, 0:18]
    a[m & (np.abs(xx - 8.5) < 1)] = accent
    if kind == 'half':
        mm = m.copy(); mm[:, :9] = False; a[mm] = empty
    outline(a, m, line)
    return a

def gem(a, cx, cy, r, c, light, dark, rim, rim2):
    for y in range(-r - 1, r + 2):
        for x in range(-r - 1, r + 2):
            d = abs(x) + abs(y)
            if d <= r:
                col = light if (x < 0 and y < 0) else (dark if (x > 0 and y > 0) else c)
                if d == r: col = rim
                put(a, cx + x, cy + y, col)
            elif d == r + 1:
                put(a, cx + x, cy + y, hx('1a0f04'))
    put(a, cx - 1, cy - 1, rim2)

def arrow_icon(c, dark):
    w, h = 22 * S, 15 * S
    a = blank(w, h)
    for y in range(h):
        for x in range(w):
            body = x < w - 15 and abs(y - h / 2) < 4
            head = x >= w - 15 and abs(y - h / 2) < (w - x) * 0.95
            if body or head: a[y, x] = c
    m = a[..., 3] > 0
    sh = m & ~np.roll(m, -2, 0); a[sh] = dark
    return a

# ============================================================================ texte façon Minecraft (rendu 1x puis agrandi)
FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 9)
def text1x(s, col, shadow=True):
    w = int(FONT.getlength(s)) + 3; h = 12
    im = Image.new('L', (w, h), 0); ImageDraw.Draw(im).text((0, -1), s, font=FONT, fill=255)
    m = np.array(im) > 120
    a = blank(w + 1, h + 1)
    if shadow:
        sc = (np.array(col[:3]) * 0.25).astype(int)
        a[1:, 1:][m] = [*sc, 255]
    a[:-1, :-1][m] = [*col[:3], 255]
    return a

# ============================================================================ textures de l'inventaire (2x, 512 x 512)
def nearest(a, k):
    return np.repeat(np.repeat(a, k, 0), k, 1)

SLOTS_INV = [(8, 8 + 18 * i) for i in range(4)] + [(77, 62)] + [(98, 18), (116, 18), (98, 36), (116, 36)] + \
            [(8 + 18 * c, 84 + 18 * r) for r in range(3) for c in range(9)] + [(8 + 18 * c, 142) for c in range(9)]

def inventory_tex(st):
    W, H = 176 * S, 166 * S
    tex = blank(256 * S, 256 * S)
    p = st.panel(W, H)
    box = st.player_box(51 * S, 72 * S)
    over(p, box, 25 * S, 7 * S)
    sl = st.slot()
    for (x, y) in SLOTS_INV:
        over(p, sl, (x - 1) * S, (y - 1) * S)
    big = st.slot(True)
    over(p, big, (154 - 5) * S, (28 - 5) * S)
    over(p, st.arrow(), 134 * S, 29 * S)
    # séparation inventaire / barre rapide
    ln = blank(162 * S, 2); ln[:, :] = list(hx('ff2440', 120)) if st.name == 'blackwall' else (list(st.G1) if st.name == 'royal' else [255, 255, 255, 40])
    over(p, ln, 8 * S, 139 * S)
    over(tex, p, 0, 0)
    return tex

def chest_tex(st, rows=6):
    W, H = 176 * S, (rows * 18 + 17 + 97) * S
    tex = blank(256 * S, 256 * S)
    p = st.panel(W, H)
    sl = st.slot()
    for r in range(rows):
        for c in range(9): over(p, sl, (7 + 18 * c) * S, (17 + 18 * r) * S)
    oy = rows * 18 + 17 + 14
    for r in range(3):
        for c in range(9): over(p, sl, (7 + 18 * c) * S, (oy + 18 * r) * S)
    for c in range(9): over(p, sl, (7 + 18 * c) * S, (oy + 58) * S)
    over(tex, p, 0, 0)
    return tex

# ============================================================================ aperçus
G = 4  # échelle d'interface de l'aperçu (texel = 2 px)
def item(name):
    p = os.path.join(ITEMS, name + '.png')
    im = Image.open(p).convert('RGBA')
    if im.height > im.width: im = im.crop((0, 0, im.width, im.width))
    return np.array(im.resize((16, 16), Image.NEAREST)).astype(np.float32)

def place_items(screen, ox, oy, slots, items, counts=None):
    for i, (x, y) in enumerate(slots):
        if i >= len(items) or not items[i]: continue
        it = nearest(item(items[i]), G)
        over(screen, it, ox + x * G, oy + y * G)
        if counts and counts.get(i):
            t = nearest(text1x(str(counts[i]), (255, 255, 255)), G)
            over(screen, t, ox + (x + 17) * G - t.shape[1] + G, oy + (y + 9) * G)

def steve(w, h):
    """Petit joueur de face (pixel art) pour la case du personnage."""
    a = blank(w, h)
    skin, hair, shirt, pants, shoe, eye = hx('c8906a'), hx('3b2a1a'), hx('2aa7a7'), hx('3a3aa0'), hx('4a4a4a'), hx('ffffff')
    u = w // 16
    def rect(x, y, ww, hh, c):
        a[y * u:(y + hh) * u, x * u:(x + ww) * u] = c
    rect(4, 0, 8, 8, skin); rect(4, 0, 8, 2, hair); rect(4, 2, 1, 2, hair); rect(11, 2, 1, 2, hair)
    rect(5, 4, 2, 1, eye); rect(9, 4, 2, 1, eye); rect(6, 4, 1, 1, hx('4a3aa0')); rect(9, 4, 1, 1, hx('4a3aa0'))
    rect(6, 6, 4, 1, hx('7a4a3a'))
    rect(4, 8, 8, 12, shirt); rect(0, 8, 4, 12, shirt); rect(12, 8, 4, 12, shirt)
    rect(0, 17, 4, 3, skin); rect(12, 17, 4, 3, skin)
    rect(4, 20, 4, 10, pants); rect(8, 20, 4, 10, pants); rect(4, 29, 4, 2, shoe); rect(8, 29, 4, 2, shoe)
    m = a[..., 3] > 0
    a[m & ~np.roll(m, -u, 1), :3] *= 0.75
    return a

def bg_world():
    im = Image.open(BGF).convert('RGB').resize((1920, 1080))
    return im

def preview_inventory(st, tex):
    bg = bg_world().filter(ImageFilter.GaussianBlur(10))
    bg = Image.eval(bg, lambda v: int(v * 0.55))
    scr = np.array(bg.convert('RGBA')).astype(np.float32)
    inv = tex[:166 * S, :176 * S]
    big = nearest(inv, G // S)
    ox = (1920 - big.shape[1]) // 2; oy = (1080 - big.shape[0]) // 2 + 20
    over(scr, big, ox, oy)
    # personnage
    pl = steve(16 * 6, 32 * 6)
    pl = nearest(pl, 1)
    over(scr, pl, ox + 50 * G - pl.shape[1] // 2, oy + 75 * G - pl.shape[0] - 6)
    items = ['netherite_helmet', 'netherite_chestplate', 'netherite_leggings', 'netherite_boots', 'totem_of_undying',
             'blaze_powder', 'blaze_powder', None, None,
             'golden_carrot', 'ender_pearl', None, 'arrow', None, None, 'oak_planks' if os.path.exists(os.path.join(ITEMS, 'oak_planks.png')) else 'stick', None, 'torch' if os.path.exists(os.path.join(ITEMS, 'torch.png')) else 'coal',
             None, 'experience_bottle', None, None, 'emerald', None, None, 'diamond', None,
             'iron_ingot', None, None, 'gold_ingot', None, None, None, None, 'bone',
             'netherite_sword', 'bow', 'diamond_pickaxe', 'golden_apple', 'cooked_beef', 'ender_pearl', 'firework_rocket', 'water_bucket', 'shield' if os.path.exists(os.path.join(ITEMS, 'shield.png')) else 'compass']
    items = [i if (i is None or os.path.exists(os.path.join(ITEMS, i + '.png'))) else None for i in items]
    place_items(scr, ox, oy, SLOTS_INV, items, {9: 2, 10: 16, 12: 64, 18: 23, 21: 8, 25: 12, 27: 32, 30: 17, 35: 6, 39: 5, 40: 48, 41: 13, 42: 21})
    place_items(scr, ox, oy, [(154, 28)], ['blaze_rod' if os.path.exists(os.path.join(ITEMS, 'blaze_rod.png')) else 'stick'])
    t = nearest(text1x('Fabrication', st.TXT), G); over(scr, t, ox + 97 * G, oy + 7 * G)
    # titre du style
    lab = text1x(st.title, (255, 255, 255)); lab = nearest(lab, 3)
    plate = blank(lab.shape[1] + 40, lab.shape[0] + 20); plate[:, :] = [0, 0, 0, 170]
    over(plate, lab, 20, 10); over(scr, plate, 30, 30)
    return Image.fromarray(np.clip(scr, 0, 255).astype(np.uint8))

def preview_hud(st):
    bg = np.array(bg_world())
    # on efface le HUD vanilla de la vidéo (bas de l'écran)
    import cv2
    mk = np.zeros(bg.shape[:2], np.uint8); mk[860:1080, 500:1420] = 255; mk[0:60, 520:1400] = 255
    bg = cv2.inpaint(bg, mk, 9, cv2.INPAINT_TELEA)
    bg = np.array(Image.fromarray(bg).filter(ImageFilter.GaussianBlur(0)))
    scr = np.array(Image.fromarray(bg).convert('RGBA')).astype(np.float32)
    k = G // S
    hb = nearest(st.hotbar(), k)
    hx0 = (1920 - hb.shape[1]) // 2; hy0 = 1080 - hb.shape[0]
    over(scr, hb, hx0, hy0)
    hot = ['netherite_sword', 'bow', 'diamond_pickaxe', 'golden_apple', 'cooked_beef', 'ender_pearl', 'firework_rocket', 'water_bucket', 'totem_of_undying']
    hot = [i if os.path.exists(os.path.join(ITEMS, i + '.png')) else None for i in hot]
    place_items(scr, hx0, hy0, [(3 + 20 * i, 3) for i in range(9)], hot, {3: 12, 4: 48, 5: 16, 6: 32})
    sel = nearest(st.selection(), k)
    over(scr, sel, hx0 + (20 * 2 - 1) * G, hy0 - 1 * G)
    # barre d'XP
    xp = nearest(st.xp(0.64), k)
    xy = hy0 - 7 * G; over(scr, xp, hx0, xy)
    lv = nearest(text1x('27', st.xp_color()), G)
    over(scr, lv, (1920 - lv.shape[1]) // 2, xy - 9 * G)
    # vie / armure / faim (comme le vanilla : 10 icônes de 8 pixels d'écart)
    rowy = xy - 10 * G
    for i in range(10):
        kind = 'full' if i < 7 else ('half' if i == 7 else 'empty')
        over(scr, nearest(st.heart(kind), k), hx0 + i * 8 * G, rowy)
        kind = 'full' if i < 8 else ('half' if i == 8 else 'empty')
        over(scr, nearest(st.armor(kind), k), hx0 + i * 8 * G, rowy - 10 * G)
        j = 9 - i
        kind = 'full' if j < 8 else ('half' if j == 8 else 'empty')
        over(scr, nearest(st.food(kind), k), hx0 + 182 * G - 9 * G - i * 8 * G, rowy)
    lab = text1x(st.title, (255, 255, 255)); lab = nearest(lab, 3)
    plate = blank(lab.shape[1] + 40, lab.shape[0] + 20); plate[:, :] = [0, 0, 0, 170]
    over(plate, lab, 20, 10); over(scr, plate, 30, 30)
    return Image.fromarray(np.clip(scr, 0, 255).astype(np.uint8))

def save(a, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(path)

for st in (BlackWall(), Royal(), Frost()):
    d = os.path.join(OUT, st.name)
    inv = inventory_tex(st)
    save(inv, os.path.join(d, 'textures/gui/container/inventory.png'))
    save(chest_tex(st, 6), os.path.join(d, 'textures/gui/container/generic_54.png'))
    save(st.hotbar(), os.path.join(d, 'textures/gui/sprites/hud/hotbar.png'))
    save(st.selection(), os.path.join(d, 'textures/gui/sprites/hud/hotbar_selection.png'))
    for kind, nm in (('full', 'full'), ('half', 'half'), ('empty', 'container')):
        save(st.heart(kind), os.path.join(d, f'textures/gui/sprites/hud/heart/{nm}.png'))
    for kind in ('full', 'half', 'empty'):
        save(st.food(kind), os.path.join(d, f'textures/gui/sprites/hud/food_{kind}.png'))
        save(st.armor(kind), os.path.join(d, f'textures/gui/sprites/hud/armor_{kind}.png'))
    save(st.xp(0), os.path.join(d, 'textures/gui/sprites/hud/experience_bar_background.png'))
    save(st.xp(1), os.path.join(d, 'textures/gui/sprites/hud/experience_bar_progress.png'))
    a = preview_inventory(st, inv); b = preview_hud(st)
    c = Image.new('RGB', (1920, 2160)); c.paste(a, (0, 0)); c.paste(b, (0, 1080))
    c.resize((1280, 1440), Image.LANCZOS).save(os.path.join(OUT, f'apercu_{st.name}.png'))
    print('ok', st.name)
