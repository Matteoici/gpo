"""Textures du corps d'Adam Smasher (colosse cybernétique, design original) : plaques de métal, peinture rouge,
articulations, canon, visière et réacteur lumineux.  python3 smasher.py <pack>   (après items2.py)"""
import numpy as np, os, json, sys
from PIL import Image

OUT = sys.argv[1]
D = os.path.join(OUT, 'assets/limitless/textures/item/items')
os.makedirs(D, exist_ok=True)
N = 32
rng = np.random.default_rng(1337)
yy, xx = np.mgrid[0:N, 0:N]


def hx(s):
    return np.array([int(s[i:i + 2], 16) for i in (0, 2, 4)] + [255], np.int32)


def save(name, frames, ft=None):
    if isinstance(frames, np.ndarray):
        frames = [frames]
    strip = np.concatenate([np.clip(f, 0, 255).astype(np.uint8) for f in frames], axis=0)
    Image.fromarray(strip, 'RGBA').save(os.path.join(D, name + '.png'))
    if len(frames) > 1:
        json.dump({'animation': {'frametime': ft or 3, 'interpolate': True}}, open(os.path.join(D, name + '.png.mcmeta'), 'w'))


def shade(base, v):
    """base RGB * v (v ~ 0.5..1.3)"""
    a = np.zeros((N, N, 4), np.int32)
    a[..., :3] = (np.array(base[:3])[None, None, :] * v[..., None]).astype(np.int32)
    a[..., 3] = 255
    return a


def noise(sigma=0.05):
    return rng.normal(0, sigma, (N, N))


def panels(base, rivets=True, light=1.0):
    """une seule grande plaque par face : biseau sur le bord, rivets aux coins, rayures"""
    v = light * (0.95 + noise(0.03)) + (15.5 - yy) / 31 * 0.12
    v += np.where(yy <= 1, 0.2, 0) + np.where(xx <= 1, 0.1, 0) - np.where(yy >= 30, 0.25, 0) - np.where(xx >= 30, 0.15, 0)
    v -= np.where((xx == 0) | (yy == 0) | (xx == N - 1) | (yy == N - 1), 0.4, 0)
    # une ligne de jonction discrète
    v -= np.where((yy == 20) & (xx > 3) & (xx < 28), 0.18, 0)
    for _ in range(5):
        x0, y0 = rng.integers(3, 26), rng.integers(3, 26)
        for k in range(rng.integers(3, 6)):
            v[min(N - 1, y0 + k // 2), min(N - 1, x0 + k)] += 0.14
    a = shade(base, v)
    if rivets:
        for cx in (3, 28):
            for cy in (3, 28):
                a[cy, cx, :3] = (np.array(base[:3]) * 1.5).clip(0, 255)
                a[cy + 1, cx, :3] = (np.array(base[:3]) * 0.5)
    return a


steel = hx('3b3f48')
dark = hx('1d1f25')
red = hx('a3121f')

# plaque principale (acier sombre)
save('smasher_plate', panels(steel))
# plaque noire avec grilles d'aération
a = panels(dark, light=1.0)
vent = ((yy % 3) == 1) & (xx > 7) & (xx < 24) & (yy > 9) & (yy < 18)
a[vent, :3] = [10, 10, 12]
save('smasher_dark', a)
# peinture rouge (bandes, liserés)
a = panels(red, rivets=False, light=1.05)
a[(yy == 15) | (yy == 16), :3] = (a[(yy == 15) | (yy == 16), :3] * 0.75).astype(np.int32)
save('smasher_red', a)
# articulations : câbles et pistons
v = 0.7 + noise(0.04)
cable = (xx % 6 < 3)
v += np.where(cable, 0.25 * np.cos((xx % 6 - 1) / 1.5), -0.1)
v += np.sin(yy * 0.9) * 0.05 * cable
a = shade(hx('2b2d33'), v)
a[(yy % 10) == 0, :3] = [70, 72, 80]
save('smasher_joint', a)
# canon : métal chromé, bandes, évents
v = 0.75 + 0.45 * np.sin(np.pi * (xx + 0.5) / N) ** 2 + noise(0.03)
v -= np.where((yy % 8) == 0, 0.35, 0)
a = shade(hx('6b707c'), v)
holes = ((yy % 8) >= 3) & ((yy % 8) <= 5) & (((xx - 4) % 8) <= 2)
a[holes, :3] = [12, 12, 14]
save('smasher_barrel', a)
# bouche du canon (noire, anneau)
r = np.hypot(xx - 15.5, yy - 15.5)
a = shade(hx('2a2c31'), 0.9 + noise(0.03))
a[r < 11, :3] = [8, 8, 10]
a[(r >= 11) & (r < 13), :3] = [90, 94, 104]
save('smasher_muzzle', a)


def glow(c0, c1, t):
    return (np.array(c0[:3]) * (1 - t)[..., None] + np.array(c1[:3]) * t[..., None]).astype(np.int32)


# visière rouge qui balaie (4 images)
fr = []
for k in range(6):
    a = np.zeros((N, N, 4), np.int32); a[..., 3] = 255
    t = 0.55 + 0.25 * np.exp(-((yy - 15.5) / 7) ** 2)
    scan = np.exp(-((xx - (k * 32 / 6 + 2)) / 3.0) ** 2)
    t = np.clip(t + 0.45 * scan, 0, 1)
    a[..., :3] = glow(hx('4a0008'), hx('ff5a5a'), t)
    a[(yy % 4) == 0, :3] = (a[(yy % 4) == 0, :3] * 0.75).astype(np.int32)
    fr.append(a)
save('smasher_visor', fr, ft=3)
# réacteur de poitrine (pulsation)
fr = []
for k in range(6):
    g = 0.75 + 0.25 * np.sin(k / 6 * 2 * np.pi)
    a = np.zeros((N, N, 4), np.int32); a[..., 3] = 255
    t = np.clip(1 - r / 16, 0, 1) ** 1.2 * g
    a[..., :3] = glow(hx('300004'), hx('ffd0a0'), t)
    ring = (r > 10) & (r < 12.5)
    a[ring, :3] = glow(hx('600008'), hx('ff3a3a'), np.full(ring.sum(), g))
    a[r >= 14, :3] = [26, 27, 31]
    fr.append(a)
save('smasher_core', fr, ft=2)
print('textures Smasher OK')


# ================================================================ icônes des implants (16x16, dessins originaux)
def icon_from(rows, pal, mirror=True):
    a = np.zeros((16, 16, 4), np.int32)
    for y, row in enumerate(rows):
        full = row + row[::-1] if mirror else row.ljust(16, '.')
        assert len(full) == 16, (row, len(full))
        for x, ch in enumerate(full):
            if ch != '.': a[y, x] = hx(pal[ch])
    return a

def save16(name, frames, ft=3):
    if isinstance(frames, np.ndarray): frames = [frames]
    strip = np.concatenate([f.astype(np.uint8) for f in frames], axis=0)
    Image.fromarray(strip, 'RGBA').save(os.path.join(D, name + '.png'))
    if len(frames) > 1:
        json.dump({'animation': {'frametime': ft, 'interpolate': False}}, open(os.path.join(D, name + '.png.mcmeta'), 'w'))

def pulse(rows, pal, keys, cols, mirror=True, n=4):
    """anime les couleurs `keys` en les faisant passer par `cols`"""
    out = []
    for k in range(n):
        p2 = dict(pal)
        for i, key in enumerate(keys): p2[key] = cols[(k + i) % len(cols)]
        out.append(icon_from(rows, p2, mirror))
    return out

SPINE = ["........", "......kg", ".....kgG", "......kg", "....kkkd", "...kgggd", "....kkkd", "......kg",
         "....kkkd", "...kgggd", "....kkkd", "......kg", "....kkkd", "...kgggd", "....kkkd", "......kg"]
SPINE = [r.replace('g', 'a', 1) if i in (5,) else r for i, r in enumerate(SPINE)]
pal = {'k': '0b1410', 'g': '2bd96b', 'G': 'c8ffd8', 'd': '5a6370', 'a': '2bd96b'}
save16('relic_sandevistan_martinez', pulse(SPINE, pal, ['g', 'G'], ['2bd96b', '6bff9a', 'b2ff59', '00c853'], n=4), ft=3)
pal = {'k': '1a0810', 'g': 'ff2a6d', 'G': 'ffc1d6', 'd': '5a4a52', 'a': 'ff2a6d'}
proto = [r for r in SPINE]
proto[7] = "....w.kg"; proto[11] = "...w..kg"
pal['w'] = 'ffcc33'
save16('relic_sandevistan_prototype', pulse(proto, pal, ['g'], ['ff2a6d', 'ff7ab8', 'c8104a', 'ff2a6d'], n=4), ft=3)

CHASSIS = ["........", "........", ".kkkkkkk", ".kpPPppp", "kppppppp", "kppddddd", "kpdrrddc", "kpddddcc",
           "kppddddd", ".kppdddd", ".kpppppp", "..kpprrr", "...kpppp", "....kkkk", "........", "........"]
save16('relic_chassis_smasher', icon_from(CHASSIS, {'k': '0a0a0c', 'p': '3b3f48', 'P': '7a808c', 'd': '1d1f25', 'r': 'b3121f', 'c': 'ff6a3a'}))

MANTIS = ["..............kk", ".............kwk", "............kwsk", "...........kwsk.", "..........kwsk..",
          ".........kwsk...", "........kwsk....", ".......kwsk.....", "..kk..kwsk......", "..krkkwsk.......",
          "...krrsk........", "...kmrrk........", "..kmmkrrk.......", ".kmmk.krk.......", "kmmk...k........", "kkk............."]
save16('relic_lames_mantis', icon_from(MANTIS, {'k': '120a0c', 'w': 'f2f4ff', 's': '8a93a8', 'r': 'ff2a6d', 'm': '3b3f48'}, mirror=False))

GORILLA = ["................", "....kkkkkkk.....", "...kmmkmmkmk....", "..kmMmkMmkMmk...", "..kmmmkmmkmmkk..",
           "..kmmmmmmmmmmrk.", "..kmddddddddmrk.", "..kmmmmmmmmmmrk.", "...kmmmmmmmmrk..", "....kmmmmmmmk...",
           ".....krrrrrk....", ".....kddddk.....", ".....kmmmmk.....", ".....kddddk.....", ".....kmmmmk.....", "......kkkk......"]
save16('relic_bras_gorilla', icon_from(GORILLA, {'k': '0c0c0e', 'm': '6b707c', 'M': 'b8bec9', 'd': '2a2c31', 'r': 'fcee0a'}, mirror=False))

HEART = ["........", "..kkk...", ".kRRrk.k", "kRwRrrkr", "kRRrrrrr", "krrrmmrr", "kmmmmmmm", "krrrrmrr",
         ".krrrrrr", "..krrrrr", "...krrrr", "....krrr", ".....krr", "......kr", ".......k", "........"]
hp = {'k': '1a0406', 'R': 'ff5a6a', 'r': 'c8102e', 'w': 'ffe0e4', 'm': '8a93a8'}
fr = []
for k, (R, r) in enumerate([('ff5a6a', 'c8102e'), ('ff8a96', 'ff2a3a'), ('ff5a6a', 'c8102e'), ('d63a4a', '8a0a1e')]):
    q = dict(hp); q['R'] = R; q['r'] = r; fr.append(icon_from(HEART, q))
save16('relic_second_coeur', fr, ft=4)

EYE = ["........", "........", "...mmmmm", "..mkkkkk", ".mkwwccc", "mkwccCCC", "mkwcCbbb", "mkwcCbbC",
       "mkwcCbbb", "mkwccCCC", ".mkwwccc", "..mkkkkk", "...mmmmm", "........", "........", "........"]
save16('relic_optiques_kiroshi', icon_from(EYE, {'m': '8a93a8', 'k': '0c1014', 'w': 'e8f6ff', 'c': '05a8c8', 'C': '7ff6ff', 'b': '000000'}))

CHIP = ["........", "...y.y.y", "..kkkkkk", ".ykddddd", "..kdgggg", ".ykdgddd", "..kdgdGG", ".ykdgdGG",
        "..kdgdGG", ".ykdgddd", "..kdgggg", ".ykddddd", "..kkkkkk", "...y.y.y", "........", "........"]
save16('relic_kerenzikov', icon_from(CHIP, {'y': 'c8d2e6', 'k': '0a0c10', 'd': '1d2a38', 'g': '05d9e8', 'G': 'b8fbff'}))
save16('relic_puce_combat', icon_from(CHIP, {'y': 'f2bd2e', 'k': '0a0c08', 'd': '1d2a1d', 'g': '3cff78', 'G': 'd8ffd0'}))
save16('puce_smasher', pulse(CHIP, {'y': 'f2bd2e', 'k': '0a0606', 'd': '2a0a0e', 'g': 'ff1e32', 'G': 'ffd0d0'}, ['g'],
                             ['ff1e32', 'ff5a5a', 'b3121f', 'ff1e32'], n=4), ft=4)

BRAIN = ["........", "....kkkk", "..kkpppp", ".kppPppk", "kppppkpp", "kpPpkppp", "kpppkpyy", "kppkpyyk",
         ".kpppyky", ".kppyykp", "..kppppp", "...kkkkm", ".....kmm", ".....kmm", "......kk", "........"]
save16('relic_synapses_boost', icon_from(BRAIN, {'k': '200a18', 'p': 'ff7ab8', 'P': 'ffd0e8', 'y': 'fcee0a', 'm': '8a93a8'}))

SPRING = ["......kkkk......", ".....kmmmmk.....", "......kkkk......", ".....kyk........", "......kyk.......",
          ".......kyk......", "......kyk.......", ".....kyk........", "......kyk.......", ".......kyk......",
          "......kyk.......", ".....kyk........", "......kkkk......", ".....kmmmmk.....", "....kmmmmmmk....", "....kkkkkkkk...."]
save16('relic_tendons_renforces', icon_from(SPRING, {'k': '0c0c0e', 'm': '6b707c', 'y': '05d9e8'}, mirror=False))

PLATE = ["........", "...kkkkk", "..kssssk", ".kssssdk", ".ksssdsk", "kssssdks", "kdddddkd", "ksssdkss",
         "kssdksss", "ksssdkss", ".ksssdks", ".kssssdk", "..kssssk", "...kkkkk", "........", "........"]
save16('relic_armure_subdermique', icon_from(PLATE, {'k': '0a0a0c', 's': '4a5060', 'd': '05d9e8'}))
save16('relic_peau_titane', icon_from(PLATE, {'k': '1a1a1e', 's': 'b8bec9', 'd': 'ff8a2a'}))

VIAL = ["........", ".....kkk", "....kmmm", ".....kkk", "....kgwr", "....kgrr", "...kgrrr", "...kgrRr",
        "..kgrrRR", "..kgrrrR", "..kgrrrr", "..kgrrrr", "...kgrrr", "....kmmm", ".....kkk", "........"]
save16('relic_pompe_sanguine', pulse(VIAL, {'k': '1a0406', 'm': '8a93a8', 'g': 'c8e6ff', 'w': 'ffffff', 'r': 'b3121f', 'R': 'ff3a4a'},
                                     ['R'], ['ff3a4a', 'ff7a8a', 'ff3a4a', 'c8102e'], n=4), ft=5)
print('icônes Smasher OK')
