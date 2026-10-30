"""Portail perso (remplace les portails du Nether) : tourbillon animé teintable fx/w_portal.
usage : portal_fx.py <racine du pack>"""
import numpy as np, os, sys, json, math
from PIL import Image

OUT = sys.argv[1]
NS = 'limitless'
T = os.path.join(OUT, 'assets', NS, 'textures/item/fx')
M = os.path.join(OUT, 'assets', NS, 'models/fx')
I = os.path.join(OUT, 'assets', NS, 'items/fx')
for d in (T, M, I): os.makedirs(d, exist_ok=True)

N, F = 64, 16
y, x = np.mgrid[0:N, 0:N]
u = (x + 0.5) / N * 2 - 1
v = (y + 0.5) / N * 2 - 1
# rectangle aux coins arrondis (le cadre du portail), bord doux
q = np.maximum(np.abs(u), np.abs(v))
rr = np.sqrt(u * u + v * v)
edge = np.clip((0.98 - np.maximum(q, rr * 0.86)) / 0.08, 0, 1)
th = np.arctan2(v, u)
rng = np.random.default_rng(5)
stars = [(rng.uniform(-0.9, 0.9), rng.uniform(-0.9, 0.9), rng.uniform(0, 1)) for _ in range(26)]
tint_frames, core_frames = [], []
for f in range(F):
    ph = f / F * 2 * math.pi
    swirl = 0.5 + 0.5 * np.sin(5 * th + 9 * rr - ph * 2)
    swirl2 = 0.5 + 0.5 * np.sin(-3 * th + 14 * rr + ph * 3)
    val = 0.35 + 0.45 * swirl * (1 - rr * 0.4) + 0.2 * swirl2
    val *= 0.75 + 0.25 * (1 - np.clip(rr, 0, 1))
    a = np.clip(0.55 + 0.35 * swirl, 0, 1) * edge
    img = np.zeros((N, N, 4), np.uint8)
    g = np.clip(val * 255, 0, 255)
    img[..., 0] = img[..., 1] = img[..., 2] = g.astype(np.uint8)
    img[..., 3] = (a * 235).astype(np.uint8)
    tint_frames.append(img)
    # cœur blanc : filaments brillants + petites étoiles qui tournent vers le centre
    c = np.zeros((N, N, 4), np.uint8)
    fil = (np.abs(np.sin(5 * th + 9 * rr - ph * 2)) > 0.97) & (rr < 0.95)
    c[fil] = (255, 255, 255, 170)
    center = np.clip(1 - rr / 0.28, 0, 1)
    c[..., 3] = np.maximum(c[..., 3], (center * 200).astype(np.uint8))
    c[..., :3] = np.where(center[..., None] > 0, 255, c[..., :3])
    for sx, sy, sp in stars:
        k = (sp + f / F) % 1
        r0 = math.hypot(sx, sy) * (1 - k)
        a0 = math.atan2(sy, sx) + k * 3
        px = int((math.cos(a0) * r0 + 1) / 2 * N); py = int((math.sin(a0) * r0 + 1) / 2 * N)
        if 0 <= px < N and 0 <= py < N and edge[py, px] > 0.5:
            c[py, px] = (255, 255, 255, 255)
    c[..., 3] = (c[..., 3] * edge).astype(np.uint8)
    core_frames.append(c)

def strip(frames, name):
    Image.fromarray(np.concatenate(frames, axis=0)).save(os.path.join(T, name + '.png'))
    json.dump({'animation': {'frametime': 2, 'interpolate': True}}, open(os.path.join(T, name + '.png.mcmeta'), 'w'))
strip(tint_frames, 'w_portal')
strip(core_frames, 'w_portal_c')

def tface(tint=True, tx='#0'):
    f = {'uv': [0, 0, 16, 16], 'texture': tx}
    if tint: f['tintindex'] = 0
    return f
model = {'textures': {'0': f'{NS}:item/fx/w_portal', '1': f'{NS}:item/fx/w_portal_c', 'particle': f'{NS}:item/fx/w_portal'},
         'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'faces': {'north': tface(), 'south': tface()}},
                      {'from': [0, 0, 8.05], 'to': [16, 16, 8.05], 'shade': False, 'faces': {'north': tface(False, '#1'), 'south': tface(False, '#1')}},
                      {'from': [0, 0, 7.95], 'to': [16, 16, 7.95], 'shade': False, 'faces': {'north': tface(False, '#1'), 'south': tface(False, '#1')}}]}
json.dump(model, open(os.path.join(M, 'w_portal.json'), 'w'))
json.dump({'model': {'type': 'minecraft:model', 'model': f'{NS}:fx/w_portal',
                     'tints': [{'type': 'minecraft:custom_model_data', 'index': 0, 'default': -1}]}}, open(os.path.join(I, 'w_portal.json'), 'w'))
# aperçu
prev = np.zeros((N, N * 4, 4), np.uint8)
for i in range(4):
    t = tint_frames[i * 4].astype(float); c = core_frames[i * 4].astype(float)
    col = np.array([90, 140, 255]) / 255
    rgb = t[..., :3] * col
    al = t[..., 3:4] / 255
    rgb = rgb * (1 - c[..., 3:4] / 255) + c[..., :3] * (c[..., 3:4] / 255)
    out = np.concatenate([rgb, np.maximum(al, c[..., 3:4] / 255) * 255], -1)
    prev[:, i * N:(i + 1) * N] = out.astype(np.uint8)
bg = Image.new('RGBA', (N * 4, N), (20, 20, 30, 255))
bg.alpha_composite(Image.fromarray(prev))
bg.resize((N * 8, N * 2), Image.NEAREST).save(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'portal_prev.png'))
print('portail ok')
