"""Titres en badges pixel (police limitless:titles) : courts, colorés, lisibles dans le chat, le TAB et au-dessus de la tête.
usage : titles.py <racine du pack> <textures vanilla> -> écrit aussi la liste (id -> caractère) dans _outils/titles_chars.json"""
import os, sys, json
import numpy as np
from PIL import Image, ImageDraw
OUT, VT = sys.argv[1], sys.argv[2]
A = os.path.join(OUT, 'assets/limitless'); D = os.path.join(A, 'textures/font/titles'); os.makedirs(D, exist_ok=True)
FONT = Image.open(os.path.join(VT, 'font/ascii.png')).convert('RGBA')
def hx(s): s = s.lstrip('#'); return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)
def glyph(ch):
    c = ord(ch); g = np.array(FONT.crop(((c % 16) * 8, (c // 16) * 8, (c % 16) * 8 + 8, (c // 16) * 8 + 8)))[..., 3] > 0
    cols = np.nonzero(g.any(axis=0))[0]
    return (g[:, :cols.max() + 1] if len(cols) else np.zeros((8, 3), bool))
def textmask(s):
    parts = []
    for ch in s:
        if ch == ' ': parts.append(np.zeros((8, 3), bool)); continue
        parts.append(glyph(ch)); parts.append(np.zeros((8, 1), bool))
    return np.concatenate(parts[:-1], axis=1)

ICONS = {  # 7x7
    'crown': ["       ", "X  X  X", "XX XXX ", "XXXXXXX", "XXXXXXX", " XXXXX ", "       "],
    'skull': [" XXXXX ", "XXXXXXX", "X  X  X", "XXXXXXX", " XXXXX ", " X X X ", "       "],
    'pumpkin': ["   X   ", " XXXXX ", "XX X XX", "XXXXXXX", "X XXX X", " XXXXX ", "       "],
    'spider': ["X     X", " X X X ", "  XXX  ", "XXXXXXX", "  XXX  ", " X X X ", "X     X"],
    'check': ["      X", "     XX", "X   XX ", "XX XX  ", " XXX   ", "  X    ", "       "],
    'star': ["   X   ", "   X   ", "XXXXXXX", " XXXXX ", "  XXX  ", " XX XX ", " X   X "],
}
# id : (icône, texte, fond haut, fond bas, contour, couleur du texte)
TITLES = [
    ('koth_1', 'crown', 'BARON', '#e8b04a', '#b07818', '#5a3a08', '#fff6d8'),
    ('koth_5', 'crown', 'ROI', '#ffd36b', '#ff8a1f', '#6a2a04', '#ffffff'),
    ('koth_20', 'crown', 'EMPEREUR', '#ff5a2a', '#9a0a2a', '#3a0010', '#ffe08a'),
    ('bounty_1', 'skull', 'TRAQUEUR', '#d04848', '#8a1c1c', '#3a0808', '#ffe0e0'),
    ('bounty_10', 'skull', 'CHASSEUR', '#ff3a5a', '#a0102a', '#40000c', '#ffffff'),
    ('bounty_50', 'skull', 'FAUCHEUR', '#3a0a10', '#120004', '#ff3a3a', '#ff5a5a'),
    ('boss_1', 'pumpkin', 'BRISEUR', '#ff9a2a', '#c85a08', '#4a1a00', '#fff2d0'),
    ('boss_10', 'pumpkin', 'TERREUR', '#ff7a00', '#5a1a8a', '#20063a', '#ffe0a0'),
    ('reine_1', 'spider', 'TISSEUR', '#6aa8ff', '#2a5ac8', '#0a1a4a', '#eaf2ff'),
    ('reine_10', 'spider', 'VENIN', '#5aff8a', '#0a7a3a', '#002a10', '#f0fff4'),
    ('quest_10', 'check', 'BOSSEUR', '#7ad65a', '#3a8a2a', '#123a0a', '#f4ffe8'),
    ('quest_100', 'check', 'MACHINE', '#a8e063', '#2a7a5a', '#0a2a20', '#ffffff'),
    ('event_1', 'star', 'CHAMPION', '#4ad8ff', '#1a7ad8', '#062a5a', '#f0fbff'),
    ('event_5', 'star', 'MYTHE', '#c8a8ff', '#5a2ad8', '#1a0a4a', '#ffffff'),
]
providers = []; chars = {}
for k, (tid, icon, txt, top, bot, edge, ink) in enumerate(TITLES):
    tm = textmask(txt)
    ic = np.array([[c == 'X' for c in row] for row in ICONS[icon]])
    w = 1 + 2 + 7 + 2 + tm.shape[1] + 3 + 1
    h = 10
    im = Image.new('RGBA', (w, h)); a = np.zeros((h, w, 4), np.uint8)
    t, b, e, ik = hx(top), hx(bot), hx(edge), hx(ink)
    for y in range(h):
        f = y / (h - 1); col = tuple(int(t[i] * (1 - f) + b[i] * f) for i in range(3)) + (255,)
        a[y, :] = col
    a[0, :] = e; a[-1, :] = e; a[:, 0] = e; a[:, -1] = e
    for (x, y) in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]: a[y, x] = (0, 0, 0, 0)
    a[1, 1:-1] = tuple(min(255, int(c * 1.25 + 20)) for c in t[:3]) + (255,)  # reflet
    # icône
    for y in range(7):
        for x in range(7):
            if ic[y, x]: a[2 + y, 3 + x] = ik
    # texte + ombre
    x0 = 3 + 7 + 2
    sh = tuple(int(c * 0.45) for c in b[:3]) + (255,)
    for y in range(min(8, tm.shape[0])):
        for x in range(tm.shape[1]):
            if tm[y, x] and y < 7:
                if 3 + y < h - 1 and x0 + x + 1 < w - 1: a[3 + y, x0 + x + 1] = sh
    for y in range(min(8, tm.shape[0])):
        for x in range(tm.shape[1]):
            if tm[y, x] and y < 7: a[2 + y, x0 + x] = ik
    Image.fromarray(a).save(os.path.join(D, tid + '.png'))
    ch = chr(0xe200 + k); chars[tid] = ch
    providers.append({'type': 'bitmap', 'file': f'limitless:font/titles/{tid}.png', 'ascent': 8, 'height': 10, 'chars': [ch]})
json.dump({'providers': providers}, open(os.path.join(A, 'font/titles.json'), 'w'), ensure_ascii=False, indent=1)
json.dump(chars, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'titles_chars.json'), 'w'), ensure_ascii=False)
# planche d'aperçu
sheet = Image.new('RGBA', (220, 12 * len(TITLES) + 6), (30, 30, 34, 255))
for k, (tid, *_ ) in enumerate(TITLES):
    im = Image.open(os.path.join(D, tid + '.png')); sheet.paste(im, (4, 4 + k * 12), im)
sheet.resize((sheet.width * 4, sheet.height * 4), Image.NEAREST).save(sys.argv[3] if len(sys.argv) > 3 else '/dev/null', 'PNG') if len(sys.argv) > 3 else None
print('titres ok', len(TITLES))
