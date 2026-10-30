"""Textures des effets (style anime : bandes de couleurs nettes + contour), animations image par image."""
import numpy as np, os, json, sys
from PIL import Image
from scipy.ndimage import zoom, gaussian_filter, map_coordinates, binary_dilation, binary_erosion

OUT = sys.argv[1] if len(sys.argv) > 1 else '.'
T = os.path.join(OUT, 'assets/limitless/textures/item/fx'); os.makedirs(T, exist_ok=True)
F = os.path.join(OUT, 'assets/limitless/textures/font'); os.makedirs(F, exist_ok=True)

# ----------------------------------------------------------------- outils

def vnoise(shape, cells, seed):
    r = np.random.default_rng(seed)
    g = r.random((cells + 3, cells + 3))
    z = zoom(g, (shape[0] / cells, shape[1] / cells), order=3, mode='wrap')
    return z[:shape[0], :shape[1]]

def fbm(shape, cells, seed, oct=5):
    out = np.zeros(shape); a = 1; tot = 0
    for o in range(oct):
        out += vnoise(shape, cells * 2 ** o, seed + o * 17) * a; tot += a; a *= 0.5
    out /= tot
    return (out - out.min()) / (out.max() - out.min() + 1e-9)

def tile_fbm(n, cells, seed, oct=5):
    """bruit périodique (se raccorde sur les bords) : sert pour faire défiler / tourner."""
    return fbm((n, n), cells, seed, oct)

def sample(field, yy, xx):
    """lit un bruit périodique en coordonnées 0..1"""
    n = field.shape[0]
    return map_coordinates(field, [(yy % 1) * n, (xx % 1) * n], order=1, mode='wrap')

def grid(n):
    y, x = np.mgrid[0:n, 0:n]
    return (x + 0.5) / n * 2 - 1, (y + 0.5) / n * 2 - 1

def save(name, img, frametime=None, folder=None):
    img = np.clip(img, 0, 255).astype(np.uint8)
    p = os.path.join(folder or T, name + '.png')
    Image.fromarray(img, 'RGBA').save(p, optimize=True)
    if frametime:
        json.dump({"animation": {"frametime": frametime, "interpolate": True}}, open(p + '.mcmeta', 'w'))

def ramp(v, stops):
    v = np.clip(v, 0, 1); out = np.zeros(v.shape + (3,))
    for i in range(len(stops) - 1):
        p0, c0 = stops[i]; p1, c1 = stops[i + 1]
        m = (v >= p0) & (v <= p1)
        t = ((v - p0) / (p1 - p0 + 1e-9))[m][:, None]
        out[m] = np.array(c0) * (1 - t) + np.array(c1) * t
    return out

def rgba(rgb, a):
    return np.dstack([rgb, np.clip(a, 0, 1) * 255])

# palette feu anime : blanc chaud -> jaune -> orange -> rouge, contour rouge sombre
HOT = np.array([255, 252, 230]); YEL = np.array([255, 222, 70]); ORA = np.array([255, 138, 18])
RED = np.array([232, 48, 12]); EDGE = np.array([120, 8, 4])

def cel_fire(d, alpha_cut=0.2, bands=(0.36, 0.55, 0.76), outline=2, soft=0.0):
    """d = densité 0..1 -> image anime en bandes nettes avec contour sombre"""
    a = d > alpha_cut
    col = np.zeros(d.shape + (3,))
    col[:] = RED
    col[d > bands[0]] = ORA
    col[d > bands[1]] = YEL
    col[d > bands[2]] = HOT
    if outline:
        inner = binary_erosion(a, iterations=outline)
        rim = a & ~inner
        col[rim] = EDGE
    alpha = a.astype(float)
    if soft:
        alpha = np.clip(gaussian_filter(alpha, soft) * 1.3, 0, 1) * a + 0
    # halo très léger autour (lueur)
    glow = gaussian_filter(a.astype(float), max(2, d.shape[0] / 40)) * 0.35
    out_a = np.maximum(alpha, glow * (~a))
    gcol = np.zeros_like(col); gcol[:] = ORA
    col = np.where(a[..., None], col, gcol)
    return rgba(col, out_a)

