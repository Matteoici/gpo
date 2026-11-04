"""Menu du passe de saison, style PARCHEMIN.
 - fond du coffre (6 lignes) dessiné en glyphe de police : limitless:gui   (décalé de -8 par )
 - icônes : sceaux de palier (atteint / en cours / verrouillé), coche, flèches, coffret, sceau royal, sablier, case vide
usage : pass_gui.py <racine du pack> <textures vanilla (assets/minecraft/textures)>"""
import os, sys, json
import numpy as np
from PIL import Image, ImageDraw
OUT, VT = sys.argv[1], sys.argv[2]
A = os.path.join(OUT, 'assets/limitless')
GUI = os.path.join(A, 'textures/gui'); TEX = os.path.join(A, 'textures/item/pass'); MOD = os.path.join(A, 'models/item/pass')
for d in (GUI, TEX, MOD, os.path.join(A, 'font')): os.makedirs(d, exist_ok=True)
FONT = Image.open(os.path.join(VT, 'font/ascii.png')).convert('RGBA')
rng = np.random.default_rng(1215)

def hx(s, a=255): s = s.lstrip('#'); return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)
def gw(ch):
    c = ord(ch)
    if c == 32: return 4
    g = np.array(FONT.crop(((c % 16) * 8, (c // 16) * 8, (c % 16) * 8 + 8, (c // 16) * 8 + 8)))[..., 3]
    cols = np.nonzero(g.max(axis=0))[0]; return (cols.max() + 2) if len(cols) else 4
def tw(s): return sum(gw(c) for c in s)
def text(img, x, y, s, col, shadow=None):
    for ch in s:
        c = ord(ch); g = FONT.crop(((c % 16) * 8, (c // 16) * 8, (c % 16) * 8 + 8, (c // 16) * 8 + 8)); m = Image.fromarray(np.array(g)[..., 3])
        if shadow: img.paste(Image.new('RGBA', (8, 8), shadow), (x + 1, y + 1), m)
        img.paste(Image.new('RGBA', (8, 8), col), (x, y), m); x += gw(ch)

PAPER = hx('#e9d8b0'); PAPER2 = hx('#dcc597'); PAPER3 = hx('#cdb688'); INK = hx('#4a2c12'); INK2 = hx('#7a4a22')
WELL, WELL_L, WELL_D = hx('#cdb688'), hx('#f5e8c8'), hx('#8a744e')
GOLD, GOLD_L, GOLD_D = hx('#e8c15a'), hx('#fff2b8'), hx('#9a7416')
WAX = hx('#8a2018')

def slot_xy(i): return 8 + (i % 9) * 18, 18 + (i // 9) * 18
def well(d, i, fill, light, dark):
    x, y = slot_xy(i)
    d.rectangle([x - 1, y - 1, x + 16, y + 16], fill=fill)
    d.line([x - 1, y - 1, x + 16, y - 1], fill=dark); d.line([x - 1, y - 1, x - 1, y + 16], fill=dark)
    d.line([x - 1, y + 16, x + 16, y + 16], fill=light); d.line([x + 16, y - 1, x + 16, y + 16], fill=light)

# ============================================================ fond 176 x 126
W, H = 176, 126
bg = np.zeros((H, W, 4), np.float32)
bg[..., :3] = PAPER[:3]; bg[..., 3] = 255
# grain du papier + bords brûlés
yy, xx = np.mgrid[0:H, 0:W]
bg[..., :3] += rng.normal(0, 4, (H, W, 1))
edge = np.minimum.reduce([xx, yy, W - 1 - xx, H - 1 - yy]).astype(np.float32)
burn = np.clip(1 - edge / 9, 0, 1) ** 2
bg[..., 0] -= burn * 70; bg[..., 1] -= burn * 85; bg[..., 2] -= burn * 80
for _ in range(120):  # taches
    cx, cy = rng.integers(4, W - 4), rng.integers(4, H - 4)
    bg[cy, cx, :3] -= rng.integers(6, 16)
img = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8))
d = ImageDraw.Draw(img)
# coins arrondis transparents (comme le coffre vanilla)
for (x, y) in [(0, 0), (1, 0), (0, 1), (W - 1, 0), (W - 2, 0), (W - 1, 1)]:
    img.putpixel((x, y), (0, 0, 0, 0))
# double filet d'encre
d.rectangle([2, 2, W - 3, H - 1], outline=INK)
d.rectangle([4, 4, W - 5, H - 1], outline=INK2)
# titre + petites enluminures
title = 'PASSE DE SAISON'
tx = (W - tw(title)) // 2
text(img, tx, 6, title, INK, shadow=hx('#c9ad7a'))
for k, x0 in enumerate((tx - 14, tx + tw(title) + 4)):
    d.line([x0, 9, x0 + 9, 9], fill=WAX); d.point([(x0 + 4, 8), (x0 + 4, 10)], fill=WAX)
# ligne 1 : case de l'étoile de saison au milieu (slot 4) dans un cartouche
well(d, 4, WELL, WELL_L, WELL_D)
# ligne 2 : la piste (un ruban rouge sous les sceaux)
ty = 18 + 18 + 8
d.rectangle([6, ty - 1, W - 7, ty + 1], fill=hx('#b8a47c'))
d.line([6, ty - 1, W - 7, ty - 1], fill=hx('#9a8a6c'))
# lignes 3 et 5 : cases gratuit / premium (feuille d'or)
for i in range(9):
    well(d, 18 + i, WELL, WELL_L, WELL_D)
    well(d, 36 + i, GOLD, GOLD_L, GOLD_D)
# ligne 4 : bandeau
by = 18 + 3 * 18
d.rectangle([6, by + 1, W - 7, by + 15], fill=PAPER2)
d.line([6, by + 1, W - 7, by + 1], fill=PAPER3); d.line([6, by + 15, W - 7, by + 15], fill=PAPER3)
text(img, 10, by + 5, '^ GRATUIT', INK)
s = 'PREMIUM v'; text(img, W - 10 - tw(s), by + 5, s, GOLD_D)
for k in range(4): d.point([(W // 2 - 6 + k * 4, by + 8)], fill=INK2)
# ligne 6 : boutons
for i in (45, 47, 49, 51, 53): well(d, i, WELL, WELL_L, WELL_D)
img.save(os.path.join(GUI, 'passe.png'))
json.dump({'providers': [
    {'type': 'space', 'advances': {'': -8, '': -1, '': -169}},
    {'type': 'bitmap', 'file': 'limitless:gui/passe.png', 'ascent': 13, 'height': H, 'chars': ['']}]},
    open(os.path.join(A, 'font/gui.json'), 'w'), ensure_ascii=False, indent=1)

# ============================================================ icônes 16 x 16
def new(): return Image.new('RGBA', (16, 16)), None
def save(im, n):
    im.save(os.path.join(TEX, n + '.png'))
    json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'limitless:item/pass/{n}'}}, open(os.path.join(MOD, n + '.json'), 'w'))

def seal(hi, mid, lo, star=None, lock=False, ribbon=None):
    im = Image.new('RGBA', (16, 16)); d = ImageDraw.Draw(im)
    if ribbon:  # rubans sous le sceau
        d.polygon([(4, 10), (7, 10), (6, 15), (5, 14), (4, 15)], fill=ribbon)
        d.polygon([(9, 10), (12, 10), (12, 15), (11, 14), (10, 15)], fill=ribbon)
    # bord irrégulier de la cire
    pts = []
    import math
    for k in range(16):
        a = k / 16 * math.tau; r = 6.2 + (0.7 if k % 2 else 0)
        pts.append((7.5 + math.cos(a) * r, 7.0 + math.sin(a) * r))
    d.polygon(pts, fill=lo)
    d.ellipse([2, 1, 13, 12], fill=mid)
    d.ellipse([4, 3, 11, 10], outline=lo)
    d.point([(4, 3), (5, 2), (3, 4)], fill=hi)
    if star:
        for (x, y) in [(7, 4), (8, 4), (6, 6), (7, 6), (8, 6), (9, 6), (7, 8), (8, 8), (6, 9), (9, 9), (7, 5), (8, 5)]:
            im.putpixel((x, y), star)
    if lock:
        d.rectangle([6, 6, 9, 9], fill=hx('#3a3028')); d.arc([6, 3, 9, 8], 180, 360, fill=hx('#3a3028')); im.putpixel((7, 7), hx('#8a7a5a'))
    return im

save(seal(hx('#ff8a78'), hx('#b0201c'), hx('#5a0a08'), star=hx('#ffd0a0'), ribbon=hx('#2a4a8a')), 'pass_node_on')
save(seal(hx('#fff2b8'), hx('#e0b040'), hx('#8a5a10'), star=hx('#ffffff'), ribbon=hx('#b0201c')), 'pass_node_cur')
save(seal(hx('#d8c8a8'), hx('#a8987a'), hx('#6a5c48'), lock=True), 'pass_node_off')

def check():
    im = Image.new('RGBA', (16, 16)); d = ImageDraw.Draw(im)
    d.rectangle([2, 3, 13, 13], fill=hx('#f5e8c8'), outline=hx('#8a744e'))
    d.line([4, 8, 6, 11], fill=hx('#2a7a3a'), width=2); d.line([6, 11, 11, 5], fill=hx('#2a7a3a'), width=2)
    return im
save(check(), 'pass_check')

def arrow(left):
    im = Image.new('RGBA', (16, 16)); d = ImageDraw.Draw(im)
    # plume d'oie qui sert de flèche
    d.line([3, 8, 12, 8], fill=INK, width=2)
    d.polygon([(1, 8), (5, 4), (5, 12)], fill=WAX)
    for k in range(4): d.line([9 + k, 8, 11 + k, 4 + k // 2], fill=hx('#f5e8c8')); d.line([9 + k, 8, 11 + k, 12 - k // 2], fill=hx('#dcc597'))
    return im.transpose(Image.FLIP_LEFT_RIGHT) if not left else im
save(arrow(True), 'pass_prev'); save(arrow(False), 'pass_next')

def coffer():
    im = Image.new('RGBA', (16, 16)); d = ImageDraw.Draw(im)
    d.rectangle([1, 6, 14, 14], fill=hx('#7a4a22'), outline=hx('#3a200a'))
    d.rectangle([1, 3, 14, 7], fill=hx('#9a6230'), outline=hx('#3a200a'))
    d.line([1, 7, 14, 7], fill=hx('#e8c15a')); d.rectangle([6, 6, 9, 9], fill=hx('#e8c15a'), outline=hx('#9a7416'))
    for x in (4, 11): d.line([x, 3, x, 14], fill=hx('#e8c15a'))
    d.point([(3, 4), (13, 12)], fill=hx('#c08040'))
    return im
save(coffer(), 'pass_claim')

def royal(active):
    im = seal(hx('#fff2b8'), hx('#e8c15a'), hx('#9a7416'), ribbon=hx('#8a2018') if active else hx('#6a5c48'))
    d = ImageDraw.Draw(im)  # couronne
    d.polygon([(4, 8), (4, 5), (6, 7), (7.5, 4), (9, 7), (11, 5), (11, 8)], fill=hx('#b0201c') if active else hx('#6a5c48'))
    if not active:
        d.rectangle([10, 9, 14, 13], fill=hx('#3a3028')); d.arc([10, 7, 14, 11], 180, 360, fill=hx('#3a3028'))
    return im
save(royal(False), 'pass_premium'); save(royal(True), 'pass_premium_on')

def hourglass():
    im = Image.new('RGBA', (16, 16)); d = ImageDraw.Draw(im)
    d.rectangle([3, 1, 12, 2], fill=hx('#7a4a22')); d.rectangle([3, 13, 12, 14], fill=hx('#7a4a22'))
    d.polygon([(4, 3), (11, 3), (8, 8), (7, 8)], fill=hx('#f5f0e0')); d.polygon([(7, 8), (8, 8), (11, 12), (4, 12)], fill=hx('#f5f0e0'))
    d.polygon([(6, 10), (9, 10), (11, 12), (4, 12)], fill=hx('#e0b040')); d.polygon([(5, 4), (10, 4), (8, 6), (7, 6)], fill=hx('#e0b040'))
    d.line([7, 7, 7, 10], fill=hx('#e0b040'))
    return im
save(hourglass(), 'pass_week')

def scroll():
    im = Image.new('RGBA', (16, 16)); d = ImageDraw.Draw(im)
    d.rectangle([3, 3, 12, 12], fill=hx('#f5e8c8'), outline=hx('#8a744e'))
    d.ellipse([1, 1, 5, 5], fill=hx('#dcc597'), outline=hx('#8a744e')); d.ellipse([10, 10, 14, 14], fill=hx('#dcc597'), outline=hx('#8a744e'))
    for (x, y) in [(7, 5), (8, 5), (6, 7), (7, 7), (8, 7), (9, 7), (7, 9), (8, 9), (7, 6), (8, 6), (6, 10), (9, 10)]:
        im.putpixel((x, y), hx('#b0201c'))
    return im
save(scroll(), 'pass_season')
save(Image.new('RGBA', (16, 16)), 'pass_blank')
print('passe ok')

# ============================================================ TOUS LES MENUS : fonds parchemin génériques (1 à 6 lignes)
def parchment(h, seed):
    r = np.random.default_rng(seed)
    a = np.zeros((h, W, 4), np.float32); a[..., :3] = PAPER[:3]; a[..., 3] = 255
    yy, xx = np.mgrid[0:h, 0:W]
    a[..., :3] += r.normal(0, 4, (h, W, 1))
    e = np.minimum.reduce([xx, yy, W - 1 - xx, h - 1 - yy + 6]).astype(np.float32)
    b = np.clip(1 - e / 9, 0, 1) ** 2
    a[..., 0] -= b * 70; a[..., 1] -= b * 85; a[..., 2] -= b * 80
    for _ in range(h): a[r.integers(4, h - 2), r.integers(4, W - 4), :3] -= r.integers(6, 16)
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)); d = ImageDraw.Draw(im)
    for (x, y) in [(0, 0), (1, 0), (0, 1), (W - 1, 0), (W - 2, 0), (W - 1, 1)]: im.putpixel((x, y), (0, 0, 0, 0))
    d.rectangle([2, 2, W - 3, h - 1], outline=INK); d.rectangle([4, 4, W - 5, h - 1], outline=INK2)
    return im, d

providers = json.load(open(os.path.join(A, 'font/gui.json')))['providers']
for rows in range(1, 7):
    h = 17 + rows * 18
    im, d = parchment(h, 100 + rows)
    d.line([8, 15, W - 9, 15], fill=hx('#c9ad7a'))  # filet sous le titre
    for i in range(rows * 9): well(d, i, WELL, WELL_L, WELL_D)
    im.save(os.path.join(GUI, f'menu_{rows}.png'))
    providers.append({'type': 'bitmap', 'file': f'limitless:gui/menu_{rows}.png', 'ascent': 13, 'height': h, 'chars': [chr(0xe100 + rows)]})

# ---- métiers (3 lignes) : médaillons pour les 5 métiers, rubans de niveau dessous
h = 17 + 3 * 18
im, d = parchment(h, 210)
t = 'TES METIERS'; text(im, (W - tw(t)) // 2, 6, t, INK, shadow=hx('#c9ad7a'))
for x0 in ((W - tw(t)) // 2 - 14, (W + tw(t)) // 2 + 4):
    d.line([x0, 9, x0 + 9, 9], fill=WAX)
for i in range(11, 16):  # médaillon rond autour de la case du métier
    x, y = slot_xy(i)
    d.ellipse([x - 2, y - 2, x + 17, y + 17], fill=hx('#dcc597'), outline=INK2)
    d.ellipse([x - 1, y - 1, x + 16, y + 16], outline=hx('#f5e8c8'))
for i in range(20, 25):
    x, y = slot_xy(i)
    d.polygon([(x - 1, y + 3), (x + 16, y + 3), (x + 14, y + 8), (x + 16, y + 13), (x - 1, y + 13), (x + 1, y + 8)], fill=hx('#b0201c'), outline=hx('#5a0a08'))
well(d, 22 - 9 + 9, WELL, WELL_L, WELL_D) if False else None
# case du passe (en bas à droite)
well(d, 26, WELL, WELL_L, WELL_D)
im.save(os.path.join(GUI, 'jobs.png'))
providers.append({'type': 'bitmap', 'file': 'limitless:gui/jobs.png', 'ascent': 13, 'height': h, 'chars': ['']})

# ---- classements de la semaine (3 lignes) : podium au milieu
im, d = parchment(h, 220)
t = 'CLASSEMENTS DE LA SEMAINE'; text(im, (W - tw(t)) // 2, 6, t, INK, shadow=hx('#c9ad7a'))
for i in (10, 11, 12, 14, 15, 16, 22):
    well(d, i, WELL, WELL_L, WELL_D)
x, y = slot_xy(13)  # le podium doré au centre (classement XP de saison)
d.rectangle([x - 2, y - 2, x + 17, y + 17], fill=GOLD, outline=GOLD_D); d.rectangle([x - 1, y - 1, x + 16, y + 16], outline=GOLD_L)
for k, (dx, hh) in enumerate([(-1, 4), (6, 7), (13, 3)]):
    pass
# lauriers de chaque côté du podium
for s in (-1, 1):
    cx = x + 7 + s * 14
    for k in range(5):
        d.ellipse([cx - 1 + s * (k % 2), y + 1 + k * 3, cx + 1 + s * (k % 2), y + 3 + k * 3], fill=hx('#5a7a2a'))
im.save(os.path.join(GUI, 'weekly.png'))
providers.append({'type': 'bitmap', 'file': 'limitless:gui/weekly.png', 'ascent': 13, 'height': h, 'chars': ['']})
json.dump({'providers': providers}, open(os.path.join(A, 'font/gui.json'), 'w'), ensure_ascii=False, indent=1)

# ---- morceau de parchemin qui recouvre une case (les vitres de remplissage des menus)
patch = np.zeros((16, 16, 4), np.float32); patch[..., :3] = PAPER[:3]; patch[..., 3] = 255
patch[..., :3] += rng.normal(0, 4, (16, 16, 1))
Image.fromarray(np.clip(patch, 0, 255).astype(np.uint8)).save(os.path.join(TEX, 'ui_patch.png'))
json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': 'limitless:item/pass/ui_patch'},
           'display': {'gui': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [1.13, 1.13, 1]}}},
          open(os.path.join(MOD, 'ui_patch.json'), 'w'))

# ---- ruban de niveau (métiers) et icônes de métiers en médaillon
save(Image.new('RGBA', (16, 16)), 'ui_level')  # le ruban est dans le fond ; seul le nombre (quantité) s'affiche
def job_icon(kind):
    im = Image.new('RGBA', (16, 16)); d = ImageDraw.Draw(im)
    wood = hx('#7a4a22'); wood_d = hx('#4a2c12'); steel = hx('#c8ccd4'); steel_d = hx('#6a707c')
    if kind == 'mineur':
        d.line([3, 13, 11, 5], fill=wood_d, width=3); d.line([3, 13, 11, 5], fill=wood, width=1)
        d.arc([4, 1, 15, 12], 200, 340, fill=steel_d, width=3); d.arc([4, 1, 15, 12], 205, 335, fill=steel, width=1)
        d.point([(12, 3)], fill=hx('#8fd3ff'))
    elif kind == 'bucheron':
        d.line([3, 14, 10, 4], fill=wood_d, width=3); d.line([3, 14, 10, 4], fill=wood, width=1)
        d.polygon([(8, 2), (14, 3), (14, 9), (10, 7)], fill=steel_d); d.polygon([(9, 3), (13, 4), (13, 8), (10, 6)], fill=steel)
    elif kind == 'fermier':
        for k, x in enumerate((4, 7, 10)):
            d.line([x, 14, x + 1, 5], fill=hx('#9a7a2a')); d.ellipse([x - 1, 2 + k % 2, x + 3, 7 + k % 2], fill=hx('#e8c15a'), outline=hx('#9a7416'))
        d.line([3, 11, 13, 11], fill=hx('#b0201c'), width=2)
    elif kind == 'chasseur':
        d.line([3, 13, 12, 4], fill=steel_d, width=3); d.line([3, 13, 12, 4], fill=steel, width=1)
        d.line([2, 10, 6, 14], fill=hx('#e8c15a'), width=2); d.point([(13, 3)], fill=hx('#ffffff'))
        d.ellipse([10, 10, 14, 14], fill=hx('#b0201c'))
    else:  # pêcheur
        d.line([2, 14, 12, 2], fill=wood_d, width=2); d.line([12, 2, 13, 10], fill=hx('#e0e0e0'))
        d.polygon([(9, 10), (14, 12), (9, 14)], fill=hx('#5ec8ff')); d.point([(10, 12)], fill=hx('#0a2a4a'))
    return im
for k in ('mineur', 'bucheron', 'fermier', 'chasseur', 'pecheur'): save(job_icon(k), 'job_' + k)
print('menus ok')
