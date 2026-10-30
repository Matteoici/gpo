"""Textures d'objets custom, faites à partir des formes vanilla (16x16, même orientation en main) recolorées
avec des dégradés à la main + détails pixel par pixel. Certaines sont animées (.mcmeta)."""
import numpy as np, os, sys, json, colorsys, math
from PIL import Image

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

print('ok', sorted(f for f in os.listdir(D) if f.endswith('.png')))
