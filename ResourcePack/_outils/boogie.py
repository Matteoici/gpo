"""Sons originaux de la Boogie Bombe (synthèse) : lancer, explosion de paillettes, petite boucle disco de 5 s.
usage : boogie.py <racine du pack>"""
import numpy as np, os, sys, json, subprocess
from scipy.signal import lfilter
OUT = sys.argv[1]; SR = 44100; rng = np.random.default_rng(42)
D = os.path.join(OUT, 'assets/limitless/sounds/boogie'); os.makedirs(D, exist_ok=True)
def t_(s): return np.arange(int(s * SR)) / SR
def mf(m): return 440 * 2 ** ((m - 69) / 12)
def hp(x, a=0.95): return lfilter([a, -a], [1, -a], x)
def lp(x, a): return lfilter([a], [1, -(1 - a)], x)
def place(b, s, at, g=1.0):
    i = int(at * SR); j = min(len(b), i + len(s)); b[i:j] += s[:j - i] * g
def write(name, x, gain=-2):
    x = x / (np.max(np.abs(x)) + 1e-9) * 10 ** (gain / 20)
    f = int(0.004 * SR); x[:f] *= np.linspace(0, 1, f); x[-f:] *= np.linspace(1, 0, f)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '1', '-i', '-', '-c:a', 'libvorbis', '-q:a', '5',
                    os.path.join(D, name + '.ogg')], input=(np.clip(x, -1, 1) * 32767).astype('<i2').tobytes(), check=True)
def kick():
    t = t_(0.35); return np.sin(2 * np.pi * np.cumsum(55 + 120 * np.exp(-t * 30)) / SR) * np.exp(-t * 9)
def hat(open_=False):
    t = t_(0.25 if open_ else 0.06); return hp(rng.normal(0, 1, len(t)), 0.98) * np.exp(-t * (14 if open_ else 70)) * 0.35
def clap():
    t = t_(0.2); z = hp(rng.normal(0, 1, len(t)), 0.9); e = np.exp(-t * 25) + 0.6 * np.exp(-np.maximum(t - 0.012, 0) * 25) * (t > 0.012)
    return z * e * 0.5
def bass(m, d):
    t = t_(d); f = mf(m); x = 2 * ((f * t) % 1) - 1; x = lp(x, 0.12)
    return x * np.minimum(1, t / 0.005) * np.exp(-t * 2.5) * 0.9
def chord(ms, d):
    t = t_(d); x = sum(np.sign(np.sin(2 * np.pi * mf(m) * t)) * 0.3 + np.sin(2 * np.pi * mf(m) * 2 * t) * 0.2 for m in ms)
    return lp(x, 0.25) * np.exp(-t * 6) * np.minimum(1, t / 0.004) / len(ms)
def bell(m, d=0.6):
    t = t_(d); f = mf(m); return (np.sin(2 * np.pi * f * t) + 0.4 * np.sin(2 * np.pi * f * 2.76 * t) * np.exp(-t * 6)) * np.exp(-t * 5) * 0.4
# ---- boucle disco 120 BPM, 10 temps = 5 s
bpm = 120; beat = 60 / bpm; L = 5.0
b = np.zeros(int(L * SR) + SR)
prog = [[57, 60, 64], [57, 60, 64], [55, 59, 62], [53, 57, 60], [53, 57, 60], [55, 59, 62], [57, 60, 64], [57, 60, 64], [52, 56, 59], [52, 56, 59]]
bl = [33, 33, 31, 29, 29, 31, 33, 33, 28, 28]
for i in range(10):
    t0 = i * beat
    place(b, kick(), t0, 1.0)
    place(b, hat(True), t0 + beat / 2, 0.8)
    place(b, hat(), t0 + beat / 4, 0.6); place(b, hat(), t0 + 3 * beat / 4, 0.6)
    if i % 2 == 1: place(b, clap(), t0, 0.8)
    place(b, bass(bl[i], beat / 2), t0, 0.7); place(b, bass(bl[i] + 12, beat / 2), t0 + beat / 2, 0.6)
    place(b, chord(prog[i], beat * 0.4), t0 + beat / 2, 0.8)
    mel = [76, 79, 81, 79, 76, 74, 76, 72, 71, 69]
    if i % 2 == 0: place(b, bell(mel[i], 0.5), t0 + beat * 0.75, 0.6)
write('dance', b[:int(L * SR)], gain=-3)
# ---- lancer : petit sifflement qui monte
t = t_(0.5); x = np.sin(2 * np.pi * np.cumsum(600 + 1400 * t / 0.5) / SR) * np.sin(np.pi * t / 0.5) * 0.5 + hp(rng.normal(0, 1, len(t)), 0.97) * 0.1
write('throw', x, gain=-6)
# ---- explosion : pop + pluie de paillettes + accord brillant
bb = np.zeros(int(1.6 * SR))
t = t_(0.3); place(bb, np.sin(2 * np.pi * np.cumsum(180 * np.exp(-t * 20) + 60) / SR) * np.exp(-t * 12), 0, 1.2)
for k in range(40):
    place(bb, bell(rng.integers(76, 100), 0.4), rng.uniform(0.02, 0.9), rng.uniform(0.15, 0.4))
place(bb, chord([69, 73, 76, 81], 1.2), 0.05, 1.0)
write('burst', bb, gain=-2)
sj = os.path.join(OUT, 'assets/limitless/sounds.json'); d = json.load(open(sj))
for k in ('dance', 'throw', 'burst'):
    d['boogie.' + k] = {'sounds': [{'name': 'limitless:boogie/' + k}]}
json.dump(d, open(sj, 'w'), indent=1)
print('boogie ok')
