"""Tenues d'animé (Toji, Gojo, Sukuna) : textures portées sur le corps (équipement 1.21.4+, 64 x 32)
 - assets/limitless/equipment/<tenue>.json
 - textures/entity/equipment/humanoid/<tenue>.png          (tête, buste + bras, bottes)
 - textures/entity/equipment/humanoid_leggings/<tenue>.png (pantalon)
usage : outfits.py <racine du pack>"""
import numpy as np, os, sys, json
from PIL import Image

OUT = sys.argv[1]
A = os.path.join(OUT, 'assets/limitless')
EQ = os.path.join(A, 'equipment'); H = os.path.join(A, 'textures/entity/equipment/humanoid'); L = os.path.join(A, 'textures/entity/equipment/humanoid_leggings')
for d in (EQ, H, L): os.makedirs(d, exist_ok=True)
rng = np.random.default_rng(3)

def hx(s, a=255):
    s = s.lstrip('#'); return np.array([int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a], np.float32)

# faces : nom -> (x, y, largeur, hauteur)
HEAD = {'top': (8, 0, 8, 8), 'bottom': (16, 0, 8, 8), 'right': (0, 8, 8, 8), 'front': (8, 8, 8, 8), 'left': (16, 8, 8, 8), 'back': (24, 8, 8, 8)}
BODY = {'top': (20, 16, 8, 4), 'bottom': (28, 16, 8, 4), 'right': (16, 20, 4, 12), 'front': (20, 20, 8, 12), 'left': (28, 20, 4, 12), 'back': (32, 20, 8, 12)}
ARM = {'top': (44, 16, 4, 4), 'bottom': (48, 16, 4, 4), 'right': (40, 20, 4, 12), 'front': (44, 20, 4, 12), 'left': (48, 20, 4, 12), 'back': (52, 20, 4, 12)}
LEG = {'top': (4, 16, 4, 4), 'bottom': (8, 16, 4, 4), 'right': (0, 20, 4, 12), 'front': (4, 20, 4, 12), 'left': (8, 20, 4, 12), 'back': (12, 20, 4, 12)}
SIDES = ('right', 'front', 'left', 'back')

def new():
    return np.zeros((32, 64, 4), np.float32)

def fill(a, part, col, rows=None, faces=None, noise=10):
    """peint les faces d'une partie ; rows = lignes (0 = haut de la face latérale) à peindre"""
    for f, (x, y, w, h) in part.items():
        if faces and f not in faces: continue
        if f in ('top', 'bottom'):
            if rows is not None and f == 'top' and 0 not in rows: continue
            if rows is not None and f == 'bottom' and (h * 3 - 1) not in rows and max(rows) < 11: continue
            sub = a[y:y + h, x:x + w]
            sub[:] = col
            sub[..., :3] += rng.normal(0, noise, (h, w, 1))
            continue
        for r in range(h):
            if rows is not None and r not in rows: continue
            a[y + r, x:x + w] = col
            a[y + r, x:x + w, :3] += rng.normal(0, noise, (w, 1))

def px(a, part, face, cx, cy, col):
    x, y, w, h = part[face]
    if 0 <= cx < w and 0 <= cy < h: a[y + cy, x + cx] = col

def shade_sides(a, part, k=0.82):
    """côté droit et dos un peu plus sombres : du volume"""
    for f in ('right', 'back'):
        x, y, w, h = part[f]
        a[y:y + h, x:x + w, :3] *= k

def save(a, path):
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(path)

# =================================================================== TOJI : t-shirt noir moulant, pantalon blanc cassé ample, chaussures noires
h = new(); l = new()
BLACK = hx('17171b'); BLACK2 = hx('222228')
fill(h, BODY, BLACK, noise=6)
# plis du t-shirt (muscles)
for (cx, cy) in [(2, 3), (5, 3), (3, 6), (4, 6), (2, 8), (5, 8)]:
    px(h, BODY, 'front', cx, cy, BLACK2)
fill(h, ARM, BLACK, rows=range(0, 4), faces=SIDES + ('top',), noise=6)  # manches courtes
fill(h, LEG, BLACK, rows=range(9, 12), faces=SIDES + ('bottom',), noise=5)  # chaussures
fill(h, LEG, hx('3a3a40'), rows=[11], faces=SIDES)
shade_sides(h, BODY); shade_sides(h, ARM); shade_sides(h, LEG)
CREAM = hx('d8d2c4'); CREAM2 = hx('b8b2a4')
fill(l, BODY, CREAM, rows=range(8, 12), faces=SIDES, noise=5)
fill(l, BODY, hx('1c1a1c'), rows=[8], faces=SIDES, noise=3)  # ceinture
fill(l, LEG, CREAM, rows=range(0, 12), faces=SIDES + ('top',), noise=6)
for f in SIDES:
    for r in range(1, 12, 3): px(l, LEG, f, 1 + (r % 2), r, CREAM2)  # plis amples
fill(l, LEG, CREAM2, rows=[11], faces=SIDES)
shade_sides(l, BODY); shade_sides(l, LEG)
save(h, os.path.join(H, 'toji.png')); save(l, os.path.join(L, 'toji.png'))

# =================================================================== GOJO : cheveux blancs + bandeau noir, uniforme bleu nuit à col haut
h = new(); l = new()
WHITE = hx('f2f4fa'); WHITE2 = hx('c8d4ea'); BAND = hx('101014'); NAVY = hx('1c2134'); NAVY2 = hx('2a3048'); GOLD = hx('d6b04a')
# cheveux : dessus, côtés (haut), arrière
fill(h, HEAD, WHITE, faces=('top',), noise=8)
for f in ('right', 'left'):
    fill(h, HEAD, WHITE, rows=range(0, 5), faces=(f,), noise=8)
fill(h, HEAD, WHITE, rows=range(0, 7), faces=('back',), noise=8)
fill(h, HEAD, WHITE, rows=range(0, 3), faces=('front',), noise=8)
# mèches en pointe sur le front
for cx in (0, 2, 3, 5, 7):
    px(h, HEAD, 'front', cx, 2, WHITE2)
for cx in (1, 4, 6):
    px(h, HEAD, 'front', cx, 3, WHITE)
# bandeau noir tout autour (lignes 2 à 4 de la face)
for f in SIDES:
    fill(h, HEAD, BAND, rows=range(4, 6) if f == 'front' else range(4, 6), faces=(f,), noise=3)
# mèches qui dépassent au-dessus du bandeau à l'arrière
for cx in (1, 4, 6):
    px(h, HEAD, 'back', cx, 6, WHITE)
shade_sides(h, HEAD, 0.9)
# uniforme
fill(h, BODY, NAVY, noise=5)
for r in range(0, 12): px(h, BODY, 'front', 4, r, NAVY2)  # fermeture
fill(h, BODY, NAVY2, rows=[0, 1], faces=SIDES, noise=3)  # col haut
px(h, BODY, 'front', 3, 1, GOLD); px(h, BODY, 'front', 4, 1, GOLD)  # bouton en spirale
fill(h, ARM, NAVY, noise=5)
fill(h, ARM, NAVY2, rows=[10, 11], faces=SIDES, noise=3)  # poignets
fill(h, LEG, hx('0e0e12'), rows=range(9, 12), faces=SIDES + ('bottom',), noise=3)  # chaussures
shade_sides(h, BODY); shade_sides(h, ARM); shade_sides(h, LEG)
PANT = hx('15161e')
fill(l, BODY, PANT, rows=range(8, 12), faces=SIDES, noise=4)
fill(l, LEG, PANT, rows=range(0, 12), faces=SIDES + ('top',), noise=4)
for f in ('front',):
    for r in range(0, 12): px(l, LEG, f, 1, r, hx('1e2030'))
shade_sides(l, BODY); shade_sides(l, LEG)
save(h, os.path.join(H, 'gojo.png')); save(l, os.path.join(L, 'gojo.png'))

# =================================================================== SUKUNA : kimono blanc croisé, obi noir et rouge, hakama noir, sandales
h = new(); l = new()
KIMO = hx('eee8dc'); KIMO2 = hx('d2cabc'); COLL = hx('2a2024'); OBI = hx('19141a'); RED = hx('8c1020')
fill(h, BODY, KIMO, noise=5)
# col croisé (gauche sur droite) jusqu'à la ceinture
for r in range(0, 7):
    px(h, BODY, 'front', min(7, 1 + r), r, COLL)
    if r < 4: px(h, BODY, 'front', max(0, 6 - r), r, COLL)
    if r < 6: px(h, BODY, 'front', max(0, 6 - r), r, KIMO2)
fill(h, BODY, OBI, rows=[7, 8], faces=SIDES, noise=3)
fill(h, BODY, RED, rows=[8], faces=SIDES, noise=3)
fill(h, ARM, KIMO, noise=5)  # grandes manches
fill(h, ARM, KIMO2, rows=[10, 11], faces=SIDES, noise=3)
# sandales : semelle + lanières (le reste laisse voir le hakama)
fill(h, LEG, hx('5a3c28'), rows=[11], faces=SIDES + ('bottom',), noise=4)
for f in SIDES:
    px(h, LEG, f, 1, 10, hx('2a1a12')); px(h, LEG, f, 2, 9, hx('2a1a12'))
shade_sides(h, BODY); shade_sides(h, ARM)
HAK = hx('1e1a1e'); HAK2 = hx('302a30')
fill(l, BODY, HAK, rows=range(9, 12), faces=SIDES, noise=4)
fill(l, LEG, HAK, rows=range(0, 11), faces=SIDES + ('top',), noise=4)
for f in SIDES:
    for r in range(0, 11): px(l, LEG, f, 2 if f in ('front', 'back') else 1, r, HAK2)  # plis du hakama
shade_sides(l, BODY); shade_sides(l, LEG)
save(h, os.path.join(H, 'sukuna.png')); save(l, os.path.join(L, 'sukuna.png'))

for n in ('toji', 'gojo', 'sukuna'):
    json.dump({'layers': {'humanoid': [{'texture': f'limitless:{n}'}], 'humanoid_leggings': [{'texture': f'limitless:{n}'}]}},
              open(os.path.join(EQ, n + '.json'), 'w'), indent=1)
print('tenues ok')
