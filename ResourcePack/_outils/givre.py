"""Interface « Givre » (verre dépoli) pour tout le jeu : conteneurs, HUD, boutons, infobulles, barres de boss.
Part des textures vanilla 26.2 et les retravaille en 2x (1 pixel d'interface = 2 texels).
usage : givre.py <dossier gui vanilla> <racine du pack> <dossier shaders vanilla>"""
import numpy as np, os, sys, json, shutil, glob
from PIL import Image, ImageFilter

VG, PACK, VSH = sys.argv[1], sys.argv[2], sys.argv[3]
HERE = os.path.dirname(os.path.abspath(__file__))
OUTG = os.path.join(PACK, 'assets/minecraft/textures/gui')
_argv = sys.argv
sys.argv = ['gui.py', '/tmp/_givre_unused', '', '']
src = open(os.path.join(HERE, 'gui.py')).read().split("# ============================================================================ textures de l'inventaire")[0]
exec(src)
sys.argv = _argv

S = 2
GLASS = np.array([18, 24, 36]); GA = 178
ACC = np.array([120, 210, 255])

def load(p):
    return np.array(Image.open(p).convert('RGBA')).astype(np.float32)

def save_png(a, rel):
    p = os.path.join(OUTG, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(p)
    m = os.path.join(VG, rel + '.mcmeta')
    if os.path.exists(m): shutil.copy(m, p + '.mcmeta')

def up(a, k=S):
    return np.repeat(np.repeat(a, k, 0), k, 1)

def erode1(m, n=1):
    for _ in range(n):
        p = np.pad(m, 1, constant_values=False)
        m = p[1:-1, 1:-1] & p[:-2, 1:-1] & p[2:, 1:-1] & p[1:-1, :-2] & p[1:-1, 2:]
    return m

# ============================================================================ recoloration générique
def greyish(v):
    """gris, ou beige/brun peu saturé (fonds de la table d'enchantement...) : traité comme du gris"""
    r, g, b = v[..., 0], v[..., 1], v[..., 2]
    grey = (np.abs(r - g) < 10) & (np.abs(g - b) < 10)
    tan = (r >= g) & (g >= b) & ((r - b) / np.maximum(r, 1) < 0.45) & ((r - b) > 0)
    return grey | tan

def recolor(v, panel=False, base=None):
    """v : image vanilla RGBA (1x). Rend une image 2x en verre dépoli :
       - la couleur de fond (gris clair) devient du verre sombre translucide
       - les traits gris (bords des cases, flèches...) deviennent des traits blancs translucides
       - les grandes zones noires deviennent du verre plus sombre
       - les pixels colorés gardent leur couleur
       panel=True : bord extérieur arrondi + liseré blanc (grands panneaux)."""
    h, w = v.shape[:2]
    A = v[..., 3] > 0
    r, g, b = v[..., 0], v[..., 1], v[..., 2]
    grey = greyish(v)
    red = (r > 200) & (g < 40) & (b < 40)
    lum = (r + g + b) / 3
    lum = np.where(red, 30, lum)
    if base is None:
        vals = lum[A & grey].astype(int)
        base = np.bincount(vals).argmax() if len(vals) else 198
    dev = lum - base
    # zones noires pleines (3x3 tout sombre)
    dark = A & (grey | red) & (lum < 70)
    p = np.pad(dark, 1)
    full_dark = dark & p[:-2, 1:-1] & p[2:, 1:-1] & p[1:-1, :-2] & p[1:-1, 2:] & p[:-2, :-2] & p[2:, 2:] & p[:-2, 2:] & p[2:, :-2]
    out = np.zeros((h, w, 4), np.float32)
    # verre partout où il y a quelque chose
    out[A, :3] = GLASS; out[A, 3] = GA
    # traits gris -> blanc translucide
    line = A & grey & (np.abs(dev) >= 8) & ~full_dark
    alpha = np.clip(np.abs(dev) * 0.5, 14, 82)
    alpha = np.where(dev > 0, np.clip(dev * 0.6, 14, 60), alpha)  # traits clairs : plus doux
    W = np.zeros((h, w, 4), np.float32); W[line, :3] = 255; W[line, 3] = alpha[line]
    # zones sombres : verre plus foncé
    D = np.zeros((h, w, 4), np.float32); D[full_dark] = [4, 6, 10, 120]
    # couleurs gardées
    col = A & ~grey & ~red
    C = np.zeros((h, w, 4), np.float32); C[col] = v[col]
    # pixels déjà transparents à moitié (ombres) : on garde l'alpha d'origine
    semi = A & (v[..., 3] < 250)
    o2 = up(out); W2 = up(W); D2 = up(D); C2 = up(C)
    if panel:
        # bande de bord vanilla (contour noir + biseaux) remplacée par le liseré givre
        inner = erode1(A, 3)
        for X in (W2, D2):
            X[~up(inner)] = 0
        m = up(A).astype(np.float32)
        sm = np.array(Image.fromarray((m * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2.2))) / 255.0
        M = sm > 0.5
        o2[:] = 0
        yy = np.arange(M.shape[0])[:, None].repeat(M.shape[1], 1)
        o2[M, :3] = GLASS; o2[M, 3] = GA
        # reflet sur le haut de chaque panneau (dégradé léger)
        o2[M, :3] += np.clip(12 - (yy[M] % 400) * 0.08, 0, 12)[:, None]
        e1 = M & ~erode1(M, 1); e2 = erode1(M, 1) & ~erode1(M, 2)
        o2[e1] = [255, 255, 255, 100]; o2[e2] = [255, 255, 255, 34]
    over(o2, D2); over(o2, W2); over(o2, C2)
    if semi.any():
        o2[up(semi), 3] = np.minimum(o2[up(semi), 3], up(v[..., 3])[up(semi)])
    return o2

# ============================================================================ 1. conteneurs (grands panneaux)
n = 0
for p in glob.glob(os.path.join(VG, 'container', '**', '*.png'), recursive=True):
    rel = os.path.relpath(p, VG)
    v = load(p)
    save_png(recolor(v, panel=True, base=198), rel)
    n += 1
for rel in ('advancements/window.png', 'recipe_book.png'):
    p = os.path.join(VG, rel)
    if os.path.exists(p):
        save_png(recolor(load(p), panel=True, base=198), rel); n += 1
print('conteneurs', n)

# ============================================================================ 2. petits sprites de conteneurs (flèches, boutons, cases...)
n = 0
for p in glob.glob(os.path.join(VG, 'sprites', 'container', '**', '*.png'), recursive=True):
    rel = os.path.relpath(p, VG)
    v = load(p)
    if v.shape[0] * v.shape[1] > 300 * 300: continue
    lumA = v[..., 3] > 0
    if not lumA.any(): continue
    # sprites colorés (flammes, flèches de progression, icônes) : gardés tels quels mais en 2x
    grey = greyish(v)
    base_name = os.path.basename(rel)
    if base_name.startswith('slot_highlight'):
        a = blank(v.shape[1] * 2, v.shape[0] * 2)
        m = mask_shape(a.shape[1], a.shape[0], 'round', 4)
        a[m] = [255, 255, 255, 46 if 'back' in base_name else 34]
        e = m & ~erode(m, 1); a[e] = [*ACC, 150] if 'front' in base_name else [255, 255, 255, 60]
        save_png(a, rel); n += 1
        continue
    if 'highlight' in base_name and (lumA & ~grey).sum() > 0.3 * lumA.sum():
        g = v.copy(); L = (v[..., 0] * 0.3 + v[..., 1] * 0.59 + v[..., 2] * 0.11)
        g[..., 0] = g[..., 1] = g[..., 2] = L
        o = recolor(g)
        on = o[..., 3] > 0
        o[on, :3] = o[on, :3] * 0.55 + ACC * 0.45
        o[on, 3] = np.maximum(o[on, 3], 200)
        save_png(o, rel); n += 1
        continue
    if (lumA & ~grey).sum() > 0.3 * lumA.sum():
        save_png(up(v), rel)
    else:
        save_png(recolor(v), rel)
    n += 1
for p in glob.glob(os.path.join(VG, 'sprites', 'advancements', 'tab_*.png')) + glob.glob(os.path.join(VG, 'sprites', 'recipe_book', 'tab*.png')):
    rel = os.path.relpath(p, VG)
    save_png(recolor(load(p)), rel); n += 1
print('sprites de conteneurs', n)

# ============================================================================ 3. boutons et widgets (verre arrondi)
def glass_box(w, h, r, fill, fa, border, ba, inner=None):
    a = blank(w, h); m = mask_shape(w, h, 'round', r)
    a[m] = [*fill, fa]
    e = m & ~erode(m, 1); a[e] = [*border, ba]
    if inner is not None:
        e2 = erode(m, 1) & ~erode(m, 2); a[e2] = [*inner[0], inner[1]]
    yy = np.mgrid[0:h, 0:w][0]
    top = erode(m, 1) & (yy < h * 0.45)
    a[top, :3] = np.minimum(255, a[top, :3] + 10)
    return a

W200 = (400, 40)
save_png(glass_box(*W200, 8, (24, 32, 46), 200, (255, 255, 255), 90), 'sprites/widget/button.png')
save_png(glass_box(*W200, 8, (40, 64, 90), 220, ACC, 255, (ACC, 90)), 'sprites/widget/button_highlighted.png')
save_png(glass_box(*W200, 8, (14, 16, 22), 150, (255, 255, 255), 30), 'sprites/widget/button_disabled.png')
save_png(glass_box(*W200, 8, (8, 10, 16), 170, (255, 255, 255), 50), 'sprites/widget/slider.png')
save_png(glass_box(*W200, 8, (12, 16, 24), 190, ACC, 200), 'sprites/widget/slider_highlighted.png')
hv = load(os.path.join(VG, 'sprites/widget/slider_handle.png')); hh, hw = hv.shape[:2]
save_png(glass_box(hw * 2, hh * 2, 4, (230, 240, 250), 235, (255, 255, 255), 255), 'sprites/widget/slider_handle.png')
save_png(glass_box(hw * 2, hh * 2, 4, (170, 225, 255), 245, (255, 255, 255), 255), 'sprites/widget/slider_handle_highlighted.png')
tv = load(os.path.join(VG, 'sprites/widget/text_field.png')); th_, tw_ = tv.shape[:2]
save_png(glass_box(tw_ * 2, th_ * 2, 6, (4, 6, 10), 200, (255, 255, 255), 70), 'sprites/widget/text_field.png')
save_png(glass_box(tw_ * 2, th_ * 2, 6, (4, 6, 10), 210, ACC, 255), 'sprites/widget/text_field_highlighted.png')
for t in ('tab', 'tab_highlighted', 'tab_selected', 'tab_selected_highlighted'):
    v = load(os.path.join(VG, f'sprites/widget/{t}.png')); h, w = v.shape[:2]
    sel = 'selected' in t; hi = 'highlighted' in t
    a = glass_box(w * 2, h * 2 + 12, 8, (30, 44, 62) if sel else (14, 18, 26), 215 if sel else 170, ACC if hi else (255, 255, 255), 230 if hi else 80)
    save_png(a[:h * 2], f'sprites/widget/{t}.png')
for t in ('scroller', 'scroller_background'):
    v = load(os.path.join(VG, f'sprites/widget/{t}.png')); h, w = v.shape[:2]
    a = glass_box(w * 2, h * 2, 4, (220, 235, 250) if t == 'scroller' else (6, 8, 12), 220 if t == 'scroller' else 150,
                  (255, 255, 255), 200 if t == 'scroller' else 50)
    save_png(a, f'sprites/widget/{t}.png')
print('widgets ok')

# ============================================================================ 4. infobulles
bg = blank(200, 200); m = mask_shape(200, 200, 'round', 12)
bg[m] = [10, 14, 22, 236]
yy = np.mgrid[0:200, 0:200][0]; t = m & (yy < 60); bg[t, :3] += (14 * (1 - yy[t] / 60))[:, None]
save_png(bg, 'sprites/tooltip/background.png')
fr = blank(200, 200); e = m & ~erode(m, 2); fr[e] = [255, 255, 255, 120]
e2 = erode(m, 2) & ~erode(m, 3); fr[e2] = [*ACC, 90]
save_png(fr, 'sprites/tooltip/frame.png')
print('infobulles ok')

# ============================================================================ 5. HUD
st = Frost()
save_png(st.hotbar(), 'sprites/hud/hotbar.png')
save_png(st.selection(), 'sprites/hud/hotbar_selection.png')
for side in ('left', 'right'):
    v = load(os.path.join(VG, f'sprites/hud/hotbar_offhand_{side}.png')); h, w = v.shape[:2]
    a = blank(w * 2, h * 2)
    box = glass_box(44, 44, 10, (16, 22, 34), 150, (255, 255, 255), 80)
    over(a, box, 0 if side == 'left' else w * 2 - 44, (h * 2 - 44) // 2 + 1)
    save_png(a, f'sprites/hud/hotbar_offhand_{side}.png')

HCOL = {'': ('ff5c8a', 'ffd6e2', 'b02856'), 'poisoned_': ('8ad85a', 'd8ffc2', '3e8a2a'), 'withered_': ('55555f', '9a9aa6', '1e1e26'),
        'absorbing_': ('ffd25c', 'fff2c2', 'c0902a'), 'frozen_': ('8ad8ff', 'e8f8ff', '3a8ab8'), 'vehicle_': ('ffa45c', 'ffe0c2', 'c0602a')}
def mk_heart(kind, cols, blink=False, hardcore=False):
    f, l, d = (hx(c) for c in cols)
    if blink:
        f = tuple(int(x * 0.5 + 255 * 0.5) for x in f[:3]) + (255,)
    a = heart_icon(kind, fill=f, light=l, dark=d, line=hx('ffffff', 255 if blink else 210), empty=hx('ffffff', 40),
                   emptyline=hx('ffffff', 230 if blink else 140), soft=True)
    if hardcore and kind != 'empty':
        a[2:4, 7:11] = [255, 255, 255, 255]; a[4, 8:10] = [255, 255, 255, 255]
    return a
for f in os.listdir(os.path.join(VG, 'sprites/hud/heart')):
    if not f.endswith('.png'): continue
    nm = f[:-4]
    blink = nm.endswith('_blinking'); core = nm.replace('_blinking', '')
    hard = 'hardcore' in core
    core = core.replace('hardcore_', '').replace('_hardcore', '')
    if core.startswith('container') or core == 'vehicle_container':
        a = mk_heart('empty', HCOL[''], blink, hard)
    else:
        typ = ''
        for k in HCOL:
            if k and core.startswith(k): typ = k
        part = core[len(typ):]
        a = mk_heart('half' if part == 'half' else 'full', HCOL[typ], blink, hard)
    save_png(a, 'sprites/hud/heart/' + f)

for kind in ('full', 'half', 'empty'):
    save_png(st.food(kind), f'sprites/hud/food_{kind}.png')
    save_png(food_icon(kind, meat=hx('8ad85a'), light=hx('d8ffc2'), dark=hx('3e8a2a'), bone=hx('ffffff'), line=hx('ffffff', 210),
                       empty=hx('ffffff', 40), emptyline=hx('ffffff', 140)), f'sprites/hud/food_{kind}_hunger.png')
    save_png(st.armor(kind), f'sprites/hud/armor_{kind}.png')

def bubble(kind):
    a = blank(18, 18); yy, xx = np.mgrid[0:18, 0:18]; r = np.hypot(xx - 8.5, yy - 8.5)
    ring = (r <= 7.6) & (r >= 6.2); fill = r < 6.2
    if kind == 'empty':
        a[ring] = [255, 255, 255, 70]; return a
    if kind == 'bursting':
        ring &= ((np.arctan2(yy - 8.5, xx - 8.5) * 3).astype(int) % 2 == 0)
        a[ring] = [255, 255, 255, 220]; return a
    a[fill] = [120, 210, 255, 160]; a[ring] = [255, 255, 255, 230]
    a[(np.hypot(xx - 6, yy - 6) < 1.8)] = [255, 255, 255, 255]
    return a
save_png(bubble('full'), 'sprites/hud/air.png'); save_png(bubble('bursting'), 'sprites/hud/air_bursting.png'); save_png(bubble('empty'), 'sprites/hud/air_empty.png')
save_png(st.xp(0), 'sprites/hud/experience_bar_background.png')
save_png(st.xp(1), 'sprites/hud/experience_bar_progress.png')

def bar(w, h, col, bgonly):
    a = blank(w * 2, h * 2); m = mask_shape(w * 2, h * 2, 'round', h - 1)
    if bgonly:
        a[m] = [255, 255, 255, 45]; e = m & ~erode(m, 1); a[e] = [255, 255, 255, 90]
    else:
        c = np.array(col)
        xx = np.mgrid[0:h * 2, 0:w * 2][1]
        a[m, :3] = (c * (0.8 + 0.2 * xx / (w * 2)))[m] if False else c
        a[m, 3] = 255
        yy = np.mgrid[0:h * 2, 0:w * 2][0]; a[m & (yy == 1), :3] = np.minimum(255, c + 90)
    return a
for nm in ('jump_bar_background', 'jump_bar_cooldown', 'jump_bar_progress'):
    v = load(os.path.join(VG, f'sprites/hud/{nm}.png')); h, w = v.shape[:2]
    save_png(bar(w, h, (150, 230, 255) if 'progress' in nm else (90, 100, 120), 'background' in nm), f'sprites/hud/{nm}.png')
# viseur fin
cr = blank(30, 30)
cr[14:16, 4:12] = [255, 255, 255, 230]; cr[14:16, 18:26] = [255, 255, 255, 230]
cr[4:12, 14:16] = [255, 255, 255, 230]; cr[18:26, 14:16] = [255, 255, 255, 230]
cr[14:16, 14:16] = [255, 255, 255, 255]
save_png(cr, 'sprites/hud/crosshair.png')
print('HUD ok')

# ============================================================================ 6. barres de boss
BC = {'blue': (90, 160, 255), 'green': (110, 230, 120), 'pink': (255, 110, 190), 'purple': (180, 110, 255), 'red': (255, 80, 100),
      'white': (235, 245, 255), 'yellow': (255, 215, 90)}
for c, col in BC.items():
    save_png(bar(182, 5, col, True), f'sprites/boss_bar/{c}_background.png')
    save_png(bar(182, 5, col, False), f'sprites/boss_bar/{c}_progress.png')
for nn in (6, 10, 12, 20):
    for kind in ('background', 'progress'):
        v = load(os.path.join(VG, f'sprites/boss_bar/notched_{nn}_{kind}.png'))
        a = blank(364, 10)
        for i in range(1, nn):
            x = int(364 * i / nn)
            a[2:8, x] = [255, 255, 255, 120 if kind == 'background' else 200]
        save_png(a, f'sprites/boss_bar/notched_{nn}_{kind}.png')
print('barres de boss ok')

# ============================================================================ 7. texte des menus en clair (shader)
# Les titres des menus (« Inventaire », nom des coffres...) sont dessinés en gris foncé #404040 : on les éclaircit.
src = open(os.path.join(VSH, 'core/text.vsh')).read()
old = '''#else
    vertexColor = Color;
#endif'''
new = '''#else
    vertexColor = Color;
#ifdef IS_GUI
    // Limitless (interface Givre) : le gris foncé des titres de menus devient blanc bleuté
    if (all(lessThan(abs(Color.rgb - vec3(64.0 / 255.0)), vec3(0.012)))) vertexColor = vec4(0.88, 0.94, 1.0, Color.a);
#endif
#endif'''
assert old in src
os.makedirs(os.path.join(PACK, 'assets/minecraft/shaders/core'), exist_ok=True)
open(os.path.join(PACK, 'assets/minecraft/shaders/core/text.vsh'), 'w').write(src.replace(old, new))
print('shader ok')
