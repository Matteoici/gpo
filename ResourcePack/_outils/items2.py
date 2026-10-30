"""Textures d'objets custom, faites à partir des formes vanilla (16x16, même orientation en main) recolorées
avec des dégradés à la main + détails pixel par pixel. Certaines sont animées (.mcmeta)."""
import numpy as np, os, sys, json, colorsys, math
from PIL import Image, ImageDraw

VAN = sys.argv[1]           # dossier des textures vanilla items/
OUT = sys.argv[2]           # racine du pack
D = os.path.join(OUT, 'assets/limitless/textures/item/items')
os.makedirs(D, exist_ok=True)
for f in os.listdir(D):
    os.remove(os.path.join(D, f))

def hx(s):
    s = s.lstrip('#'); return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)

def load(name):
    return np.array(Image.open(os.path.join(VAN, name + '.png')).convert('RGBA')).astype(np.int32)

def lum(a):
    return 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]

def hsv(a):
    h = np.zeros(a.shape[:2]); s = np.zeros(a.shape[:2]); v = np.zeros(a.shape[:2])
    for y in range(a.shape[0]):
        for x in range(a.shape[1]):
            h[y, x], s[y, x], v[y, x] = colorsys.rgb_to_hsv(*(a[y, x, :3] / 255))
    return h * 360, s, v

def remap(a, mask, ramp):
    """dégradé par rang de luminosité : les teintes vanilla deviennent les couleurs de la rampe (sombre -> clair)"""
    out = a.copy()
    if not mask.any(): return out
    L = np.round(lum(a)).astype(int)
    vals = sorted(set(L[mask].tolist()))
    for i, v in enumerate(vals):
        k = 0 if len(vals) == 1 else round(i / (len(vals) - 1) * (len(ramp) - 1))
        sel = mask & (L == v)
        out[sel] = ramp[k]
    return out

def classes(a):
    h, s, v = hsv(a)
    op = a[..., 3] > 0
    brown = op & (h >= 15) & (h <= 50) & (s > 0.3) & (v < 0.75)
    return op, brown

def save(a, name, frames=None, ft=2):
    if frames is None:
        Image.fromarray(a.astype(np.uint8)).save(os.path.join(D, name + '.png'))
        return
    strip = np.concatenate([f.astype(np.uint8) for f in frames], axis=0)
    Image.fromarray(strip).save(os.path.join(D, name + '.png'))
    json.dump({'animation': {'frametime': ft, 'interpolate': False}}, open(os.path.join(D, name + '.png.mcmeta'), 'w'))

def px(a, pts, col):
    for x, y in pts:
        if 0 <= x < 16 and 0 <= y < 16: a[y, x] = col

def outline_of(a):
    op = a[..., 3] > 0
    ring = np.zeros_like(op)
    for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        ring |= np.roll(np.roll(op, dy, 0), dx, 1)
    return ring & ~op

def glow_ring(a, col, alpha=255):
    """halo d'un pixel autour de la forme (pour les objets "magiques")"""
    r = outline_of(a)
    out = a.copy(); out[r] = (*col[:3], alpha)
    return out

# ------------------------------------------------------------------ épées

def sword(base, name, blade, handle, guard=None, deco=None, frames=None):
    a = load(base); op, brown = classes(a)
    b = remap(a, op & ~brown, blade)
    b = remap(b, brown, handle)
    if guard:
        # la garde vanilla : la diagonale perpendiculaire autour de (4..6, 9..11)
        for (x, y) in [(3, 10), (4, 11), (5, 12), (6, 13), (4, 9), (5, 10), (6, 11), (7, 12)]:
            if a[y, x, 3] > 0 and not brown[y, x]: b[y, x] = guard[(x + y) % len(guard)]
    if deco: deco(b)
    if frames:
        save(b, name, [f(b.copy()) for f in frames], ft=3)
    else:
        save(b, name)
    return b

# lame citrouille : lame orange flamboyante, garde-citrouille verte
ORANGE = [hx('3a1203'), hx('8c3305'), hx('d9600b'), hx('ff9020'), hx('ffc24a'), hx('fff0a8')]
def citrouille(b):
    px(b, [(4, 11), (5, 10)], hx('2f6b1e'))
    px(b, [(3, 12)], hx('5ca33a'))
def flicker(k):
    def f(b):
        tips = [[(13, 1), (14, 2)], [(14, 1), (12, 2)], [(13, 0), (15, 2)]][k]
        px(b, tips, hx('ffe066'))
        return b
    return f
sword('diamond_sword', 'lame_citrouille', ORANGE, [hx('1d2b12'), hx('2f4a1c'), hx('47702a'), hx('62963a')], deco=citrouille,
      frames=[flicker(0), flicker(1), flicker(2)])

# croc de givre : glace bleue -> blanc, poignée bleu nuit, éclats qui scintillent
ICE = [hx('0f2a57'), hx('1f5fa8'), hx('45a6e8'), hx('8fd8ff'), hx('d4f4ff'), hx('ffffff')]
def sparkle(k):
    pts = [[(10, 4)], [(12, 3), (8, 7)], [(6, 9)], [(11, 5), (9, 6)]][k]
    def f(b):
        for x, y in pts:
            if b[y, x, 3] > 0: b[y, x] = hx('ffffff')
        return b
    return f
sword('diamond_sword', 'croc_de_givre', ICE, [hx('0b1433'), hx('16245c'), hx('25378a'), hx('3a52b8')],
      frames=[sparkle(i) for i in range(4)])

# tranche (netherite) : lame rouge sang, garde noire, gouttes
BLOOD = [hx('1a0306'), hx('4a0710'), hx('85101e'), hx('c41c2c'), hx('ff4a4a'), hx('ffb0a8')]
def drip(b):
    px(b, [(9, 8)], hx('ff4a4a'))
sword('netherite_sword', 'tranche_mace', BLOOD, [hx('0d0b0d'), hx('1e1a1e'), hx('332c33'), hx('4a404a')], deco=drip)

# ------------------------------------------------------------------ haches

def tool(base, name, head, handle, deco=None, frames=None, ft=3):
    a = load(base); op, brown = classes(a)
    b = remap(a, op & ~brown, head)
    b = remap(b, brown, handle)
    if deco: deco(b)
    if frames: save(b, name, [f(b.copy()) for f in frames], ft=ft)
    else: save(b, name)
    return b

STONE = [hx('121014'), hx('26222a'), hx('3d3743'), hx('59515f'), hx('7a7183')]
WOOD_DARK = [hx('1f1209'), hx('3b2412'), hx('5c3a1e'), hx('7d532c')]
def lava(k):
    def f(b):
        a = load('netherite_axe'); op, brown = classes(a)
        head = op & ~brown
        ys, xs = np.nonzero(head)
        rng = np.random.default_rng(3)
        cracks = [(x, y) for x, y in zip(xs, ys) if rng.random() < 0.28]
        c = [hx('ff5a00'), hx('ff9a1f'), hx('ffd04a')][k]
        for i, (x, y) in enumerate(cracks):
            b[y, x] = c if (i + k) % 3 else hx('b02a00')
        return b
    return f
tool('netherite_axe', 'brise_terre', STONE, WOOD_DARK, frames=[lava(0), lava(1), lava(2), lava(1)], ft=4)

CURSE = [hx('1b0730'), hx('3c1166'), hx('6421a3'), hx('9447d9'), hx('c98bff'), hx('f1d6ff')]
def curse_pulse(k):
    def f(b):
        g = [hx('ff6bf0'), hx('ffa6f7'), hx('ffffff'), hx('ffa6f7')][k]
        a = load('diamond_axe'); op, brown = classes(a)
        r = outline_of(np.where((op & ~brown)[..., None], a, 0))
        b[r & (b[..., 3] == 0)] = (*g[:3], 150 if k % 2 else 90)
        return b
    return f
tool('diamond_axe', 'hache_maudite', CURSE, [hx('140b17'), hx('261629'), hx('3a2240'), hx('4f3057')],
     frames=[curse_pulse(i) for i in range(4)], ft=4)

# faux spectrale (houe netherite) : lame fantôme turquoise translucide
SPECTRE = [hx('053b3b'), hx('0f6e6a'), hx('25b3a6'), hx('6ff2dc'), hx('d2fff6')]
def ghost(b):
    a = load('iron_hoe'); op, brown = classes(a)
    blade = op & ~brown
    b[blade, 3] = 225
tool('iron_hoe', 'arbalete_spectrale', SPECTRE, [hx('10161c'), hx('1e2933'), hx('2f3f4d'), hx('44596b')], deco=ghost)

# faucille du boss d'Halloween : lame orange dorée
SICKLE = [hx('3b1a02'), hx('8a4204'), hx('d97b0d'), hx('ffb733'), hx('fff0b0')]
tool('iron_hoe', 'faucille', SICKLE, [hx('1a0d05'), hx('331c0c'), hx('4d2c14'), hx('6b421f')])

# ------------------------------------------------------------------ arcs (repos + 3 tensions)

def bow(name, wood, string, arrow_shaft, arrow_head, gem):
    for st in ['', '_pulling_0', '_pulling_1', '_pulling_2']:
        a = load('bow' + st)
        h, s, v = hsv(a)
        op = a[..., 3] > 0
        grey = op & (s < 0.2)                       # corde
        arrowp = np.zeros_like(op)
        if st:
            base = load('bow')
            arrowp = op & (base[..., 3] == 0) & ~grey  # flèche = pixels qui n'existent pas sur l'arc au repos
            # les plumes / pointe vanilla sont grises : la flèche entière
            arrowp |= op & (base[..., 3] == 0) & grey & (lum(a) > 120)
        woodm = op & ~grey & ~arrowp
        b = remap(a, woodm, wood)
        b = remap(b, grey & ~arrowp, string)
        if st:
            b = remap(b, arrowp, arrow_shaft)
            # pointe de flèche : le pixel le plus en haut à gauche de la flèche
            ys, xs = np.nonzero(arrowp)
            if len(xs):
                i = np.argmin(xs + ys)
                b[ys[i], xs[i]] = arrow_head
        # gemme au milieu de l'arc
        ys, xs = np.nonzero(woodm)
        if len(xs):
            i = np.argmin(xs + ys)  # le point le plus en haut à gauche (milieu de la courbe)
            b[ys[i], xs[i]] = gem
        save(b, name + st)

bow('sonic_bow', [hx('1c0a3d'), hx('3d1a7a'), hx('6a35c2'), hx('9c6bff'), hx('cdb3ff')], [hx('3fd9c8'), hx('9dfff0')],
    [hx('2a6b66'), hx('5ad9c8'), hx('b8fff4')], hx('e8fffb'), hx('3fffe6'))
bow('arc_tempete', [hx('0d1633'), hx('1d2f66'), hx('34529e'), hx('5c80d6'), hx('a6c4ff')], [hx('d6b81f'), hx('fff27a')],
    [hx('6b5a10'), hx('d6b81f'), hx('fff27a')], hx('ffffff'), hx('ffe23a'))
bow('arc_feux_follets', [hx('0a2226'), hx('14434a'), hx('22707a'), hx('3aa6b0'), hx('8ae6ee')], [hx('7ff7ff'), hx('d6ffff')],
    [hx('1f6f7a'), hx('6fe8f2'), hx('d6ffff')], hx('ffffff'), hx('8afcff'))

# ------------------------------------------------------------------ fûga (bâton de blaze) : flèche de feu animée

def fuga_frames():
    a = load('arrow')
    op, brown = classes(a)
    h, s, v = hsv(a)
    grey = op & (s < 0.25)
    out = []
    for k in range(4):
        b = remap(a, op & brown, [hx('2b0c02'), hx('5c1a04'), hx('8c2a06'), hx('b33c0a')])
        b = remap(b, grey, [hx('8c2a06'), hx('ff6a00'), hx('ffb02e'), hx('fff1b8')])
        b = remap(b, op & ~brown & ~grey, [hx('8c2a06'), hx('ff6a00'), hx('ffb02e'), hx('fff1b8')])
        # flammes qui lèchent le long du manche (bas-gauche -> haut-droite) et qui bougent
        rng = np.random.default_rng(10 + k)
        for t in range(2, 12):
            x, y = t, 15 - t
            for off in (-1, 1):
                if rng.random() < 0.45:
                    xx, yy = x + off, y + off
                    if 0 <= xx < 16 and 0 <= yy < 16 and b[yy, xx, 3] == 0:
                        b[yy, xx] = [hx('ff4a00'), hx('ff8a12'), hx('ffc43a')][rng.integers(3)]
        out.append(b)
    return out
fr = fuga_frames(); save(None, 'fuga', fr, ft=2)

# ------------------------------------------------------------------ hollow purple (éclat d'améthyste) : orbe tourbillonnante animée

def hollow_frames():
    out = []
    y, x = np.mgrid[0:16, 0:16]; cx = cy = 7.5
    r = np.hypot(x - cx, y - cy); th = np.arctan2(y - cy, x - cx)
    for k in range(8):
        b = np.zeros((16, 16, 4), np.int32)
        sw = np.sin(th * 2 - r * 0.9 + k * math.pi / 4)
        blue, red = np.array(hx('3a6bff')), np.array(hx('ff2a4a'))
        col = np.where((sw > 0)[..., None], blue, red)
        col = np.where((r < 4.2)[..., None], np.array(hx('b04aff')), col)
        col = np.where((r < 2.2)[..., None], np.array(hx('f3d9ff')), col)
        m = r < 6.6
        b[m] = col[m]
        edge = m & (r > 5.6)
        b[edge, :3] = (b[edge, :3] * 0.65).astype(int)
        # étincelles autour
        ang = k * math.pi / 4
        for j in range(3):
            aa = ang + j * 2 * math.pi / 3
            sx, sy = round(cx + math.cos(aa) * 7.4), round(cy + math.sin(aa) * 7.4)
            if 0 <= sx < 16 and 0 <= sy < 16: b[sy, sx] = hx('ffb3ff')
        out.append(b)
    return out
save(None, 'hollow_purple', hollow_frames(), ft=2)

# ------------------------------------------------------------------ graine du boss : graines de citrouille maudites

a = load('pumpkin_seeds'); op = a[..., 3] > 0
b = remap(a, op, [hx('2e0510'), hx('6b0d22'), hx('a8173a'), hx('ff5a3a')])
ys, xs = np.nonzero(op)
for x, y in zip(xs, ys):
    if (x * 7 + y * 3) % 9 == 0: b[y, x] = hx('ffb347')
save(b, 'graine')

# ------------------------------------------------------------------ œufs de familiers (rare / épique)

def egg(name, ramp, spot, sparkle):
    a = load('egg'); op = a[..., 3] > 0
    b = remap(a, op, ramp)
    for (x, y) in [(6, 9), (9, 6), (10, 11), (7, 13), (11, 8)]:
        if op[y, x]: b[y, x] = spot
    frames = []
    for k in range(4):
        f = b.copy()
        pts = [[(4, 4)], [(12, 3)], [(13, 12)], [(3, 11)]][k]
        for x, y in pts:
            px(f, [(x, y)], sparkle)
            px(f, [(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)], (*sparkle[:3], 140))
        frames.append(f)
    save(None, name, frames, ft=6)

egg('egg_rare', [hx('10306b'), hx('1f56b3'), hx('3d86e6'), hx('7ab8ff'), hx('c2e0ff')], hx('0f2f80'), hx('ffffff'))
egg('egg_epique', [hx('2d0d4d'), hx('55208a'), hx('8a3fd1'), hx('b77bff'), hx('e6ccff')], hx('3a0f66'), hx('ffd6ff'))

egg('egg_legendaire', [hx('4a2a02'), hx('8f5a06'), hx('d99a12'), hx('ffd23f'), hx('fff4b8')], hx('6b3a02'), hx('ffffff'))
egg('egg_mythique', [hx('12020a'), hx('3d0620'), hx('7a0f3d'), hx('c42a5e'), hx('ff7aa8')], hx('ff2a6a'), hx('ffb3d1'))


# ================================================================== RELIQUES

def shade(mask, ramp, glint=True):
    """peint une forme avec un dégradé pixel art : clair en haut à gauche, sombre en bas à droite, bords marqués"""
    out = np.zeros((16, 16, 4), np.int32)
    ys, xs = np.nonzero(mask)
    if not len(xs): return out
    cx, cy = xs.mean(), ys.mean(); size = max(np.ptp(xs), np.ptp(ys), 1)
    n = len(ramp)
    for x, y in zip(xs, ys):
        v = 0.55 + 0.5 * (-(x - cx) - (y - cy)) / size
        if y + 1 > 15 or not mask[y + 1, x] or x + 1 > 15 or not mask[y, x + 1]: v -= 0.28
        if y - 1 < 0 or not mask[y - 1, x] or x - 1 < 0 or not mask[y, x - 1]: v += 0.22
        out[y, x] = ramp[int(np.clip(round(v * (n - 1)), 0, n - 1))]
    return out

def over(a, b):
    m = b[..., 3] > 0; a = a.copy(); a[m] = b[m]; return a

def outline(a, col=hx('140a10')):
    r = outline_of(a); a = a.copy(); a[r] = col; return a

def disk(cx, cy, r):
    y, x = np.mgrid[0:16, 0:16]; return (x - cx) ** 2 + (y - cy) ** 2 <= r * r

def rect(x0, y0, x1, y1):
    m = np.zeros((16, 16), bool); m[y0:y1 + 1, x0:x1 + 1] = True; return m

def spike(cx, top, bottom, w=1.6):
    m = np.zeros((16, 16), bool)
    for y in range(top, bottom + 1):
        hw = (y - top) / max(1, bottom - top) * w + 0.3
        for x in range(16):
            if abs(x + 0.5 - cx) <= hw: m[y, x] = True
    return m

GOLD = [hx('5a3405'), hx('9a5c08'), hx('d99a12'), hx('ffd23f'), hx('fff4b8')]
NETH = [hx('140f12'), hx('2a2026'), hx('43363d'), hx('5e4c55'), hx('7f6b74')]

def crown(name, ramp, gems, tip, frames_fx=None):
    m = rect(2, 9, 13, 12)
    for cx, top in [(3, 5), (8, 3), (13, 5)]: m |= spike(cx, top, 9)
    m |= spike(5.5, 7, 9, 1.0) | spike(10.5, 7, 9, 1.0)
    a = shade(m, ramp)
    for x in range(2, 14): a[9, x] = ramp[-1] if x % 2 == 0 else ramp[-2]
    for x in range(2, 14): a[12, x] = ramp[1]
    for (x, y), g in zip([(4, 10), (7, 10), (8, 10), (11, 10)], gems): a[y, x] = g
    for x, y in [(3, 4), (8, 2), (13, 4)]: a[y, x] = tip
    a = outline(a)
    if frames_fx:
        save(None, name, [f(a.copy()) for f in frames_fx], ft=4)
    else:
        save(a, name)

def tw(pts, col):
    def f(b):
        for x, y in pts:
            if 0 <= x < 16 and 0 <= y < 16: b[y, x] = col
        return b
    return f

crown('relic_couronne', GOLD, [hx('ff7a10'), hx('ff9a20'), hx('ff9a20'), hx('ff7a10')], hx('ff8a1a'))
crown('relic_couronne_roi_eternel', [hx('6b3f02'), hx('b87808'), hx('f2bd2e'), hx('ffe680'), hx('ffffff')],
      [hx('ff2a4a'), hx('ffffff'), hx('ffffff'), hx('3a8bff')], hx('ffffff'),
      frames_fx=[tw([(8, 1)], hx('ffffff')), tw([(2, 3), (14, 3)], hx('ffffff')), tw([(6, 6)], hx('fff4b8')), tw([(10, 8)], hx('ffffff'))])
crown('relic_couronne_enfers', NETH, [hx('ff5a00'), hx('ffb02e'), hx('ffb02e'), hx('ff5a00')], hx('ff6a00'),
      frames_fx=[tw([(3, 3), (8, 1), (13, 3)], hx('ff9a1f')), tw([(3, 2), (8, 0), (13, 2)], hx('ffd04a')), tw([(3, 3), (8, 1), (13, 3)], hx('ff5a00'))])

# amulette : chaîne en U + médaillon doré avec une graine de citrouille orange
def amulet():
    a = np.zeros((16, 16, 4), np.int32)
    for t in np.linspace(0, math.pi, 30):
        x = 8 + math.cos(t) * 5.5; y = 1 + math.sin(t) * 6.5
        a[int(round(y)), int(round(x))] = hx('c48210') if int(t * 10) % 2 else hx('ffd23f')
    m = disk(8, 11, 3.6)
    a = over(a, shade(m, GOLD))
    a = over(a, shade(disk(8, 11, 1.9), [hx('8a2f02'), hx('d95f0b'), hx('ff9a20'), hx('ffd27a')]))
    a[12, 7] = hx('2f6b1e'); a[9, 8] = hx('fff4b8')
    save(outline(a), 'relic_amulette_recolte')
amulet()

# crâne maudit : crâne sombre, yeux violets qui pulsent
def skull():
    m = disk(8, 7, 5.6) & ~rect(0, 13, 15, 15)
    m |= rect(5, 11, 10, 13)
    base = shade(m, [hx('1a1a1e'), hx('2c2c33'), hx('44444d'), hx('5c5c66')])
    for (x0, y0) in [(4, 7), (9, 7)]:
        for dx in range(3):
            for dy in range(2): base[y0 + dy, x0 + dx] = hx('050508')
    base[10, 7] = hx('050508'); base[10, 8] = hx('050508')
    for x in (6, 8): base[13, x] = hx('0a0a0e')
    base = outline(base)
    fr = []
    for c in [hx('9a3cff'), hx('c77bff'), hx('f0d0ff'), hx('c77bff')]:
        f = base.copy(); f[7, 5] = c; f[7, 10] = c; f[8, 5] = (c[0] // 2, c[1] // 2, c[2] // 2, 255); f[8, 10] = f[8, 5]
        fr.append(f)
    save(None, 'relic_crane_maudit', fr, ft=5)
skull()

# ailes du démon : deux ailes de chauve-souris rouge sombre
def wings(name, membrane, bone, mirror_gap=1):
    m = np.zeros((16, 16), bool)
    # aile gauche : bord du haut qui monte vers la pointe extérieure, bas festonné (entre les "doigts")
    for x in range(0, 7):
        top = int(round(2 + (x / 6) ** 1.5 * 4))
        scal = [11, 8, 12, 9, 12, 10, 11][x]
        m[top:scal + 1, x] = True
    m[6:11, 7] = True
    m |= m[:, ::-1]
    a = shade(m, membrane)
    for x in range(0, 7):
        top = int(round(2 + (x / 6) ** 1.5 * 4)); a[top, x] = bone; a[top, 15 - x] = bone
    for (x0, y0, x1, y1) in [(6, 6, 2, 11), (6, 6, 0, 9), (6, 6, 4, 12)]:
        for t in np.linspace(0, 1, 8):
            x = int(round(x0 + (x1 - x0) * t)); y = int(round(y0 + (y1 - y0) * t))
            if m[y, x]: a[y, x] = bone; a[y, 15 - x] = bone
    save(outline(a), name)
wings('relic_ailes_demon', [hx('2a0408'), hx('4f0a12'), hx('7a1420'), hx('a8222f')], hx('d94a4a'))

# cape de la nuit : cape bleu nuit, lune rouge, étoiles
def cape():
    m = np.zeros((16, 16), bool)
    for y in range(2, 15):
        hw = 2.5 + (y - 2) * 0.42
        for x in range(16):
            if abs(x + 0.5 - 8) <= hw: m[y, x] = True
    for x in range(16):
        if x % 3 == 0: m[14, x] = False
    a = shade(m, [hx('05060f'), hx('0d1026'), hx('1a1f45'), hx('2c3470')])
    for x in range(5, 11): a[2, x] = hx('f2bd2e')
    a[3, 8] = hx('ff2a4a')
    a = over(a, shade(disk(8, 9, 2.2), [hx('7a0b22'), hx('c41c3a'), hx('ff5a5a')]))
    for x, y in [(5, 6), (11, 7), (6, 12), (10, 12)]: a[y, x] = hx('ffffff')
    save(outline(a), 'relic_cape_nuit')
cape()

# orbes infernaux : trois boules de magma qui tournent
def orbs():
    fr = []
    for k in range(6):
        a = np.zeros((16, 16, 4), np.int32)
        for i in range(3):
            ang = k * math.pi / 9 + i * 2 * math.pi / 3 - math.pi / 2
            cx, cy = 8 + math.cos(ang) * 4.6, 8 + math.sin(ang) * 4.6
            a = over(a, shade(disk(cx, cy, 2.4), [hx('5c1002'), hx('b02a00'), hx('ff6a00'), hx('ffc43a')]))
        for t in range(0, 360, 30):
            x = int(round(8 + math.cos(math.radians(t + k * 20)) * 4.6)); y = int(round(8 + math.sin(math.radians(t + k * 20)) * 4.6))
            if a[y, x, 3] == 0: a[y, x] = (255, 120, 20, 120)
        fr.append(outline(a))
    save(None, 'relic_orbe_infernal', fr, ft=2)
orbs()

# à partir des textures vanilla
def recolor(base, name, ramp, extra=None, frames=None, ft=3, mask_fn=None, ramp2=None):
    a = load(base); op = a[..., 3] > 0
    m1 = op if mask_fn is None else (op & mask_fn(a))
    b = remap(a, m1, ramp)
    if ramp2 is not None: b = remap(b, op & ~m1, ramp2)
    if extra: extra(b)
    if frames: save(None, name, [f(b.copy()) for f in frames], ft=ft)
    else: save(b, name)

def bright(a):
    h, s, v = hsv(a); return (v > 0.75) & (s > 0.3) & (h > 20) & (h < 70)
def cyan(a):
    h, s, v = hsv(a); return (h > 150) & (h < 220) & (s > 0.3)

def flames(pts, cols):
    return [tw(pts, c) for c in cols]

recolor('blaze_powder', 'relic_trainee_flammes', [hx('5c1002'), hx('b02a00'), hx('ff5a00'), hx('ff9a1f'), hx('ffe066')],
        frames=[tw([(7, 3), (9, 5)], hx('fff1b8')), tw([(6, 4), (10, 4)], hx('ffd04a')), tw([(8, 2), (5, 6)], hx('fff1b8'))])
recolor('lantern', 'relic_lanterne_mineure', [hx('2b0c02'), hx('8a3305'), hx('ff8a1a'), hx('ffd27a')], mask_fn=bright,
        ramp2=[hx('14100c'), hx('2a2018'), hx('463626'), hx('6b553c')],
        frames=[tw([], hx('000000')), tw([(7, 9)], hx('fff1b8')), tw([(8, 10)], hx('ffffff'))])
recolor('soul_lantern', 'relic_lanterne_eternelle', [hx('053b2a'), hx('0f8a5a'), hx('3fffb0'), hx('d2fff0')], mask_fn=cyan,
        ramp2=[hx('0c1014'), hx('18222a'), hx('283844'), hx('3c5264')],
        frames=[tw([], hx('000000')), tw([(7, 9)], hx('ffffff')), tw([(8, 10)], hx('d2fff0'))])
recolor('golden_boots', 'relic_bottes_recolte', [hx('2b1404'), hx('5c300c'), hx('a85a14'), hx('e8962e'), hx('ffd27a')],
        extra=lambda b: (px(b, [(3, 9), (12, 9)], hx('5ca33a')), px(b, [(4, 8), (11, 8)], hx('2f6b1e'))))
recolor('bundle', 'relic_sac_recolte', [hx('3a1203'), hx('7a2e05'), hx('c45a0b'), hx('ff9020'), hx('ffc24a')],
        frames=[tw([(6, 9), (9, 9), (7, 11), (8, 11)], c) for c in (hx('ffe066'), hx('fff1b8'), hx('ffd04a'))])
recolor('magma_cream', 'relic_braises_ardentes', [hx('3a0a02'), hx('8a1e02'), hx('e04a06'), hx('ff9a1f'), hx('fff1b8')],
        frames=[tw([(4, 4)], hx('ffd04a')), tw([(11, 5), (3, 11)], hx('ffd04a')), tw([(12, 12)], hx('fff1b8'))])
recolor('goat_horn', 'relic_cornes_demon', [hx('120204'), hx('3a060c'), hx('6b0d18'), hx('a8222f'), hx('ff5a4a')])
recolor('ender_eye', 'relic_oeil_jugement', [hx('2b0204'), hx('7a0b10'), hx('d42a1a'), hx('ff8a2a'), hx('ffe066')],
        frames=[tw([], hx('000000')), tw([(7, 7), (8, 8)], hx('ffffff')), tw([], hx('000000')), tw([(7, 8)], hx('fff1b8'))], ft=5)
recolor('elytra', 'relic_ailes_eclipse', [hx('05030a'), hx('120a1f'), hx('24163d'), hx('3d2766'), hx('f2bd2e')])


# ================================================================== REINE ARAIGNÉE
def nav(n=5):
    return [hx('05060c'), hx('0b0f1f'), hx('141c38'), hx('22305e'), hx('3a5aa8')][:n]

# --- armes
sword('iron_sword', 'dague_venin', [hx('06120a'), hx('0f2a16'), hx('1d5a2c'), hx('35a050'), hx('7dffa0')],
      [hx('0a0a10'), hx('16161f'), hx('24243a'), hx('34345a')], frames=[tw([(13, 2)], hx('7dffa0')), tw([(12, 4)], hx('baffcc')), tw([(10, 5)], hx('7dffa0'))])
bow('arc_toiles', [hx('0b0f1f'), hx('1d2a55'), hx('3a5aa8'), hx('8fb0ff'), hx('e1ebff')], [hx('cfd8ff'), hx('ffffff')],
    [hx('3a5aa8'), hx('8fb0ff'), hx('ffffff')], hx('ffffff'), hx('5ac8ff'))
sword('netherite_sword', 'croc_reine', [hx('04050c'), hx('0d1430'), hx('1e2f70'), hx('3a6bff'), hx('e1ebff')],
      [hx('060608'), hx('101018'), hx('1c1c2a'), hx('2a2a40')], frames=[tw([(12, 3), (13, 2)], hx('ffffff')), tw([(11, 4)], hx('8fd0ff')), tw([(13, 1)], hx('5ac8ff'))])

# --- yeux maudits, cocon
recolor('fermented_spider_eye', 'oeil_maudit', [hx('060a1e'), hx('102060'), hx('2a50c0'), hx('5aa0ff'), hx('c8e6ff')],
        frames=[tw([], hx('000000')), tw([(7, 7)], hx('ffffff')), tw([], hx('000000')), tw([(8, 6)], hx('e1ebff'))], ft=5)

def cocoon_tex(name, glow):
    m = disk(8, 8.5, 5.6) | disk(8, 5, 3.6) | disk(8, 12, 3.6)
    a = shade(m, [hx('8e98b8'), hx('b8c2e0'), hx('dce3f5'), hx('f4f7ff')])
    for y in range(3, 15, 2):
        for x in range(3, 14):
            if m[y, x] and (x + y) % 4 == 0: a[y, x] = hx('c8d0ec')
    for (x, y) in [(6, 7), (9, 9), (7, 11), (10, 6)]:
        if m[y, x]: a[y, x] = glow
    a[1, 8] = hx('e1ebff'); a[0, 8] = hx('e1ebff')
    return outline(a, hx('1a2040'))
fr = []
for k in range(4):
    g = [hx('3a6bff'), hx('5aa0ff'), hx('8fd0ff'), hx('5aa0ff')][k]
    fr.append(cocoon_tex('cocon', g))
save(None, 'cocon', fr, ft=5)

# --- reliques
recolor('string', 'relic_fil_soie', [hx('6a78a8'), hx('a8b6e0'), hx('e1ebff'), hx('ffffff')],
        frames=[tw([(4, 4)], hx('8fd0ff')), tw([(8, 8)], hx('8fd0ff')), tw([(11, 11)], hx('8fd0ff'))], ft=4)
recolor('spider_eye', 'relic_oeil_araignee', [hx('060a1e'), hx('123070'), hx('2a6bff'), hx('7fd3ff'), hx('e1f6ff')],
        frames=[tw([], hx('000000')), tw([(6, 6)], hx('ffffff')), tw([], hx('000000')), tw([(9, 7)], hx('ffffff'))], ft=5)

def fangs():
    a = np.zeros((16, 16, 4), np.int32)
    for s_ in (-1, 1):
        m = np.zeros((16, 16), bool)
        for t in np.linspace(0, 1, 30):
            x = 8 + s_ * (4.5 - 3.5 * t) - s_ * math.sin(t * 2.6) * 1.2; y = 2 + 11 * t
            w = 2.0 * (1 - t) + 0.4
            yy, xx = np.mgrid[0:16, 0:16]
            m |= (xx - x) ** 2 + (yy - y) ** 2 <= w * w
        a = over(a, shade(m, [hx('3a4250'), hx('8890a0'), hx('d8dce6'), hx('ffffff')]))
    a = outline(a, hx('101420'))
    fr = []
    for k in range(4):
        f = a.copy()
        dy = k * 1
        px(f, [(5, 13 + dy if 13 + dy < 16 else 15), (10, 13 + dy if 13 + dy < 16 else 15)], hx('46eb82'))
        px(f, [(5, 12), (10, 12)], hx('7dffa0'))
        fr.append(f)
    save(None, 'relic_crochets_venin', fr, ft=4)
fangs()

def old_cocoon():
    a = cocoon_tex('x', hx('46eb82'))
    a[7, 7] = hx('0a0f20'); a[8, 8] = hx('0a0f20')
    save(a, 'relic_cocon_ancien')
old_cocoon()

def legs_icon():
    a = np.zeros((16, 16, 4), np.int32)
    body = disk(8, 8, 2.6)
    for s_ in (-1, 1):
        for j in range(4):
            yy0 = 5.5 + j * 1.7
            kx, ky = 8 + s_ * 4.5, yy0 - 3 + j * 0.5
            fx_, fy = 8 + s_ * 7.2, yy0 + 2 + j * 0.6
            for t in np.linspace(0, 1, 12):
                x = 8 + (kx - 8) * t; y = yy0 + (ky - yy0) * t
                if 0 <= round(y) < 16 and 0 <= round(x) < 16: a[round(y), round(x)] = hx('1d2a55')
                x = kx + (fx_ - kx) * t; y = ky + (fy - ky) * t
                if 0 <= round(y) < 16 and 0 <= round(x) < 16: a[round(y), round(x)] = hx('3a5aa8') if t > 0.8 else hx('141c38')
    a = over(a, shade(body, nav()))
    a[7, 7] = hx('7fd3ff'); a[7, 9] = hx('7fd3ff')
    save(outline(a, hx('05060c')), 'relic_pattes_reine')
legs_icon()

def web_cape():
    m = np.zeros((16, 16), bool)
    for y in range(2, 15):
        hw = 2.5 + (y - 2) * 0.42
        for x in range(16):
            if abs(x + 0.5 - 8) <= hw: m[y, x] = True
    a = shade(m, [hx('05060c'), hx('0b0f1f'), hx('141c38'), hx('22305e')])
    for y in range(3, 15):
        for x in range(16):
            if m[y, x] and (x == 8 or (y - 2) % 3 == 0 and (x + y) % 2 == 0 or abs(x - 8) == (y - 2) // 2): a[y, x] = hx('cfd8ff')
    for x in range(5, 11): a[2, x] = hx('c8d0ec')
    a[3, 8] = hx('5aa0ff')
    save(outline(a), 'relic_cape_toile')
web_cape()

crown('relic_couronne_reine', [hx('3a4250'), hx('6a7488'), hx('a8b4cc'), hx('dce3f5'), hx('ffffff')],
      [hx('3a6bff'), hx('7fd3ff'), hx('7fd3ff'), hx('3a6bff')], hx('e1ebff'),
      frames_fx=[tw([(8, 1)], hx('ffffff')), tw([(2, 3), (14, 3)], hx('8fd0ff')), tw([(6, 6)], hx('e1ebff'))])

recolor('heart_of_the_sea', 'relic_coeur_abysse', [hx('04050c'), hx('0b1640'), hx('1e3fa8'), hx('3a6bff'), hx('8fd0ff')],
        frames=[tw([(7, 7), (8, 7)], hx('ff3248')), tw([(7, 7), (8, 7)], hx('ff7a8a')), tw([(7, 7), (8, 7)], hx('ffffff')), tw([(7, 7), (8, 7)], hx('ff7a8a'))], ft=4)

# --- textures du corps de la Reine (32x32)
def chitin():
    N2 = 32; yy, xx = np.mgrid[0:N2, 0:N2]
    rng = np.random.default_rng(77)
    v = 0.35 + 0.25 * np.sin(yy * 0.45 + np.sin(xx * 0.3) * 1.5) + rng.normal(0, 0.08, (N2, N2))
    pal = [hx('03040a'), hx('070a16'), hx('0d1430'), hx('16224c'), hx('24387a')]
    idx = np.clip((v * len(pal)).astype(int), 0, len(pal) - 1)
    a = np.zeros((N2, N2, 4), np.int32)
    for i, c in enumerate(pal): a[idx == i] = c
    for _ in range(30):
        x, y = rng.integers(0, 32), rng.integers(0, 32)
        a[y, x] = hx('3a5aa8') if rng.random() < 0.5 else hx('020308')
    return a
save(chitin(), 'spider_chitin')
def mark():
    N2 = 32; a = np.zeros((N2, N2, 4), np.int32)
    yy, xx = np.mgrid[0:N2, 0:N2]
    # sablier + chevrons lumineux
    hour = (np.abs(xx - 15.5) <= np.abs(yy - 15.5) * 0.55 + 1.2) & (np.abs(yy - 15.5) < 9)
    chev = np.zeros_like(hour)
    for k in range(3):
        y0 = 3 + k * 3
        chev |= (np.abs(np.abs(xx - 15.5) - (yy - y0) * 1.6) < 1.0) & (yy >= y0) & (yy < y0 + 5)
    a[hour] = hx('3a8bff'); a[hour & (np.abs(xx - 15.5) < 1.5)] = hx('a8e0ff')
    a[chev & ~hour] = hx('2a6bff')
    return a
fr = []
for k in range(4):
    m_ = mark(); g = [1.0, 0.8, 0.65, 0.8][k]
    m_[..., :3] = (m_[..., :3] * g).astype(int)
    fr.append(m_)
save(None, 'spider_mark', fr, ft=5)
def eyes_tex():
    a = np.zeros((16, 16, 4), np.int32)
    for (cx, cy, r) in [(6, 8, 1.8), (10, 8, 1.8), (4, 6, 1.0), (12, 6, 1.0), (5, 4, 0.8), (11, 4, 0.8), (7, 5, 0.8), (9, 5, 0.8)]:
        m = disk(cx, cy, r)
        a[m] = hx('3aa8ff')
        a[disk(cx - 0.4, cy - 0.4, max(0.5, r * 0.4))] = hx('e1f6ff')
    return a
save(eyes_tex(), 'spider_eyes')
red = eyes_tex(); red[(red[..., 2] > 200) & (red[..., 0] < 100)] = hx('ff3248')
save(red, 'spider_eyes_red')
def leg_tex():
    a = chitin()[:16, :16].copy()
    for y in range(16):
        if y % 5 == 0: a[y, :] = hx('22305e')
    rng = np.random.default_rng(5)
    for _ in range(14):
        a[rng.integers(0, 16), rng.integers(0, 16)] = hx('020308')
    return a
save(leg_tex(), 'spider_leg')
j = np.zeros((16, 16, 4), np.int32); j[...] = hx('3a8bff'); j[6:10, :] = hx('a8e0ff')
save(j, 'spider_joint')
f = np.zeros((16, 16, 4), np.int32); f[...] = hx('c8d0e0'); f[10:, :] = hx('3a6bff'); f[:3, :] = hx('6a7488')
save(f, 'spider_fang')
def silk():
    N2 = 32; yy, xx = np.mgrid[0:N2, 0:N2]
    rng = np.random.default_rng(9)
    v = 0.6 + 0.2 * np.sin((xx + yy) * 0.7) + 0.15 * np.sin((xx - yy) * 0.5) + rng.normal(0, 0.05, (N2, N2))
    pal = [hx('9aa4c4'), hx('c0c8e4'), hx('dde4f6'), hx('f6f8ff')]
    a = np.zeros((N2, N2, 4), np.int32)
    idx = np.clip((v * len(pal)).astype(int) - 1, 0, len(pal) - 1)
    for i, c in enumerate(pal): a[idx == i] = c
    for k in range(5):
        x0 = rng.integers(0, 32)
        for y in range(32):
            x = int(x0 + math.sin(y * 0.3 + k) * 3) % 32
            a[y, x] = hx('5a8cff')
    return a
save(silk(), 'spider_silk')
print('reine ok')


# ================================================================== BLACK FLASH (reliques)
def bf_icon(name, ring):
    fr = []
    for k in range(4):
        a = np.zeros((16, 16, 4), np.int32)
        if ring:
            for t in range(0, 360, 20):
                x = int(round(7.5 + math.cos(math.radians(t + k * 15)) * 6.5)); y = int(round(7.5 + math.sin(math.radians(t + k * 15)) * 6.5))
                a[y, x] = hx('ff1e2d') if (t // 20 + k) % 3 else hx('400008')
        # éclair en zigzag : halo rouge, cœur noir
        pts = [(10, 1), (6, 6), (9, 7), (4, 14)] if k % 2 == 0 else [(10, 1), (5, 7), (9, 8), (5, 14)]
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            for t in np.linspace(0, 1, 20):
                x = int(round(x0 + (x1 - x0) * t)); y = int(round(y0 + (y1 - y0) * t))
                for dx, dy in [(0, 0), (1, 0), (-1, 0), (0, 1)]:
                    if 0 <= x + dx < 16 and 0 <= y + dy < 16 and a[y + dy, x + dx, 3] == 0: a[y + dy, x + dx] = hx('ff1e2d')
                a[y, x] = hx('0a0002')
        if ring:
            for (x, y) in [(2, 3), (13, 12), (12, 3)]: a[y, x] = hx('ffffff') if k % 2 == 0 else hx('ff8a90')
        a[1, 10] = hx('ffffff')
        fr.append(outline(a, hx('2a0004')))
    save(None, name, fr, ft=2)
bf_icon('relic_black_flash', False)
bf_icon('relic_black_flash_consecutif', True)

print('ok', sorted(f for f in os.listdir(D) if f.endswith('.png')))

# ================================================================== BOSS (Roi Citrouille) : citrouille 32x32, couronne, lame
import json as _json
def boss_textures():
    N2 = 32
    yy, xx = np.mgrid[0:N2, 0:N2]
    rng = np.random.default_rng(66)
    # côté : côtes verticales sombres, dégradé, taches pourries
    ribs = np.cos((xx + 0.5) / N2 * np.pi * 6) * 0.5 + 0.5
    v = 0.35 + 0.5 * ribs ** 0.6 - 0.18 * ((yy - 16) / 16) ** 2
    v += rng.normal(0, 0.035, v.shape)
    PUMP = [hx('3d1403'), hx('6b2605'), hx('a8420a'), hx('d9650f'), hx('ff8c1f')]
    idx = np.clip((v * (len(PUMP))).astype(int), 0, len(PUMP) - 1)
    side = np.zeros((N2, N2, 4), np.int32)
    for i, c in enumerate(PUMP): side[idx == i] = c
    for _ in range(5):
        cx, cy, r = rng.integers(3, 29), rng.integers(3, 29), rng.uniform(1.2, 2.5)
        side[(xx - cx) ** 2 + (yy - cy) ** 2 < r * r] = hx('2a1004')
    side[0, :] = hx('2a0e02'); side[-1, :] = hx('2a0e02')
    save(side, 'boss_pumpkin_side')
    # dessus : côtes en étoile + tige
    r = np.hypot(xx - 15.5, yy - 15.5); th = np.arctan2(yy - 15.5, xx - 15.5)
    v = 0.4 + 0.45 * (np.cos(th * 6) * 0.5 + 0.5) ** 0.6 - r / 60
    idx = np.clip((v * len(PUMP)).astype(int), 0, len(PUMP) - 1)
    top = np.zeros((N2, N2, 4), np.int32)
    for i, c in enumerate(PUMP): top[idx == i] = c
    top[r < 4] = hx('1f3a12'); top[r < 2.5] = hx('2f5a1c'); top[(r < 1.2)] = hx('4a7a2a')
    save(top, 'boss_pumpkin_top')
    # visage (calque lumineux animé) : yeux triangulaires méchants, bouche à dents
    face = Image.new('RGBA', (N2, N2), (0, 0, 0, 0)); d = ImageDraw.Draw(face)
    d.polygon([(3, 6), (14, 11), (11, 15), (4, 14)], fill=(255, 255, 255, 255))
    d.polygon([(29, 6), (18, 11), (21, 15), (28, 14)], fill=(255, 255, 255, 255))
    d.polygon([(13, 16), (19, 16), (16, 20)], fill=(255, 255, 255, 255))
    mouth = [(2, 19), (7, 22), (10, 19), (13, 23), (16, 19), (19, 23), (22, 19), (25, 22), (30, 19), (27, 28), (23, 25), (20, 29), (16, 26), (12, 29), (9, 25), (5, 28)]
    d.polygon(mouth, fill=(255, 255, 255, 255))
    m = np.array(face)[..., 3] > 0
    from scipy.ndimage import binary_erosion, distance_transform_edt
    dist = distance_transform_edt(m)
    frames = []
    for k in range(4):
        f = np.zeros((N2, N2, 4), np.int32)
        flick = [1.0, 0.85, 1.1, 0.92][k]
        f[m] = hx('ff6a00')
        f[m & (dist * flick > 1.2)] = hx('ffb02e')
        f[m & (dist * flick > 2.2)] = hx('fff1a8')
        rim = m & ~binary_erosion(m)
        f[rim] = hx('c43a00')
        frames.append(f)
    save(None, 'boss_pumpkin_face', frames, ft=3)
    # couronne (texture 16x16 : or + gemmes)
    cr = np.zeros((16, 16, 4), np.int32)
    g = (np.cos(xx[:16, :16] * 0.9) * 0.5 + 0.5) * 0.4 + 0.5 - (yy[:16, :16] / 16) * 0.35
    for i, c in enumerate(GOLD): cr[np.clip((g * len(GOLD)).astype(int), 0, len(GOLD) - 1) == i] = c
    cr[6:9, 3:5] = hx('ff2a4a'); cr[6:9, 11:13] = hx('3a8bff'); cr[6:9, 7:9] = hx('ff8a1a')
    cr[7, 3] = hx('ffb3b3'); cr[7, 11] = hx('b3d4ff'); cr[7, 7] = hx('ffe0b3')
    save(cr, 'boss_crown')
boss_textures()

# lame du boss : épée noire dentelée, tranchant orange qui pulse
def bpulse(k):
    def f(b):
        a = load('netherite_sword'); op, brown = classes(a)
        blade = op & ~brown
        ys, xs = np.nonzero(blade)
        for x, y in zip(xs, ys):
            if x - y >= 0 and (x + y + k) % 3 == 0: b[y, x] = [hx('ff5a00'), hx('ff9a1f'), hx('ffd04a')][(x + k) % 3]
        return b
    return f
sword('netherite_sword', 'boss_blade', [hx('0a0608'), hx('1c1216'), hx('2e2026'), hx('47343c'), hx('ff6a00')],
      [hx('1a0d05'), hx('331c0c'), hx('4d2c14'), hx('6b421f')], frames=[bpulse(0), bpulse(1), bpulse(2)])
print('boss ok')
