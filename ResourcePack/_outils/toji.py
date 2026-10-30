"""Sons originaux de la Lance du Ciel Inversé (synthèse, aucun extrait d'anime).
 - toji.special : la Percée (ruée, transpercement, 5 entailles, coup final) calée sur le Java (ticks 0, 4, 10..30, 44)
 - toji.equip   : la Restriction Céleste s'active (battement sourd + souffle grave)
 - toji.slash   : une entaille seule
usage : toji.py <racine du pack>"""
import numpy as np, os, sys, json, subprocess
from scipy.signal import lfilter

OUT = sys.argv[1]
SR = 44100
rng = np.random.default_rng(1989)
SND = os.path.join(OUT, 'assets/limitless/sounds/toji'); os.makedirs(SND, exist_ok=True)

def t_(sec): return np.arange(int(sec * SR)) / SR
def lp(x, a): return lfilter([a], [1, -(1 - a)], x)
def hp(x, a=0.95): return lfilter([a, -a], [1, -a], x)
def reverb(x, mix=0.25, size=1.0):
    out = np.zeros_like(x)
    for d, g in [(1557, 0.84), (1617, 0.83), (1491, 0.85), (1422, 0.86), (1277, 0.87)]:
        d = int(d * size); b = np.zeros(d + 1); b[d] = 1; a = np.zeros(d + 1); a[0] = 1; a[d] = -g
        out += lfilter(b, a, x)
    return x * (1 - mix) + out / 5 * mix

def whoosh(dur, up=True):
    t = t_(dur); n = len(t)
    env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2
    cut = 0.02 + 0.25 * (t / dur if up else 1 - t / dur)
    y = np.empty(n); acc = 0.0; z = rng.normal(0, 1, n)
    for i in range(n):
        acc += cut[i] * (z[i] - acc); y[i] = acc
    return hp(y, 0.97) * env * 2.2

def shing(dur=1.2, f=2100):
    """lame d'acier : partiels inharmoniques qui sonnent"""
    t = t_(dur); x = np.zeros(len(t))
    for r, a, d in [(1, 1, 3.5), (1.47, 0.6, 4.5), (2.09, 0.45, 6), (2.83, 0.3, 8), (3.71, 0.2, 10)]:
        x += a * np.sin(2 * np.pi * f * r * t + rng.random() * 6) * np.exp(-t * d)
    x += hp(rng.normal(0, 1, len(t)), 0.98) * np.exp(-t * 60) * 0.8
    return x * 0.5

def flesh(dur=0.35, strength=1.0):
    """impact sourd et mouillé"""
    t = t_(dur)
    body = np.sin(2 * np.pi * np.cumsum(140 * np.exp(-t * 25) + 55) / SR) * np.exp(-t * 18)
    wet = lp(rng.normal(0, 1, len(t)), 0.18) * np.exp(-t * 22)
    crunch = hp(rng.normal(0, 1, len(t)), 0.9) * np.exp(-t * 70) * 0.5
    return (body * 1.2 + wet * 0.9 + crunch) * strength

def swish(dur=0.22):
    t = t_(dur)
    z = hp(rng.normal(0, 1, len(t)), 0.9)
    f = lp(z, 0.35) * np.sin(np.pi * t / dur) ** 1.5
    tone = np.sin(2 * np.pi * np.cumsum(1800 - 900 * t / dur) / SR) * np.sin(np.pi * t / dur) * 0.15
    return (f + tone) * 1.4

def boom(dur=2.0, f0=48):
    t = t_(dur)
    x = np.sin(2 * np.pi * np.cumsum(f0 * (1 + 1.2 * np.exp(-t * 9))) / SR) * np.exp(-t * 2.2)
    x += lp(rng.normal(0, 1, len(t)), 0.05) * np.exp(-t * 4) * 0.8
    return x

def place(buf, sig, at, g=1.0):
    i = int(at * SR); j = min(len(buf), i + len(sig)); buf[i:j] += sig[:j - i] * g

def write(name, x, gain=-1.0, mix=0.25, size=1.0):
    x = reverb(x, mix, size)
    x = np.tanh(x / (np.max(np.abs(x)) + 1e-9) * 1.4)
    x = x / (np.max(np.abs(x)) + 1e-9) * 10 ** (gain / 20)
    f = int(0.004 * SR); x[:f] *= np.linspace(0, 1, f); x[-f:] *= np.linspace(1, 0, f)
    raw = (np.clip(x, -1, 1) * 32767).astype('<i2').tobytes()
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '1', '-i', '-', '-c:a', 'libvorbis', '-q:a', '6',
                    os.path.join(SND, name + '.ogg')], input=raw, check=True)

# ---------------------------------------------------------------- la Percée (3.6 s)
buf = np.zeros(int(3.6 * SR))
place(buf, whoosh(0.28, True), 0.0, 1.0)                     # ruée
place(buf, shing(1.4, 2300), 0.2, 0.9)                       # la lame traverse
place(buf, flesh(0.4, 1.4), 0.2, 1.0)
place(buf, boom(1.2, 60), 0.2, 0.7)
for k, at in enumerate([0.5, 0.75, 1.0, 1.25, 1.5]):         # 5 entailles
    place(buf, swish(0.2), at - 0.06, 0.9)
    place(buf, flesh(0.3, 0.9 + 0.1 * k), at, 0.8)
    place(buf, shing(0.5, 1900 + 120 * k), at, 0.25)
place(buf, whoosh(0.5, True), 1.7, 0.7)                      # il prend son élan
place(buf, shing(1.6, 2600), 2.2, 1.0)                       # coup final
place(buf, flesh(0.5, 1.8), 2.2, 1.2)
place(buf, boom(1.4, 44), 2.2, 1.2)
write('special', buf, mix=0.28, size=1.2)

# ---------------------------------------------------------------- Restriction Céleste
t = t_(1.6)
beat = np.zeros(len(t))
for at, g in [(0.0, 1.0), (0.22, 0.7)]:
    place(beat, boom(0.6, 52), at, g)
breath = lp(rng.normal(0, 1, len(t)), 0.03) * np.sin(np.pi * t / 1.6) * 0.6
write('equip', beat + breath, gain=-4, mix=0.3)

# ---------------------------------------------------------------- entaille seule
sl = np.zeros(int(0.6 * SR)); place(sl, swish(0.2), 0, 1); place(sl, flesh(0.3), 0.06, 0.7); place(sl, shing(0.5, 2000), 0.06, 0.3)
write('slash', sl, gain=-3, mix=0.15)

sj = os.path.join(OUT, 'assets/limitless/sounds.json'); d = json.load(open(sj))
for k in ('special', 'equip', 'slash'):
    d['toji.' + k] = {'sounds': [{'name': 'limitless:toji/' + k, 'stream': k == 'special'}]}
json.dump(d, open(sj, 'w'), indent=1)
print('toji ok')
