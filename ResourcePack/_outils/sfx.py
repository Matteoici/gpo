"""Sons perso des armes et du boss, synthétisés (bruits filtrés, balayages, cloches, grondements, réverb)."""
import numpy as np, os, sys, subprocess, json
from scipy import signal

OUT = sys.argv[1]
SR = 44100
rng = np.random.default_rng(7)
SND = os.path.join(OUT, 'assets/limitless/sounds')

def t_(d): return np.arange(int(d * SR)) / SR

def env(d, a, r, curve=4.0):
    t = t_(d); e = np.minimum(1, t / max(a, 1e-4)) * np.exp(-np.maximum(0, t - a) * curve / max(r, 1e-4))
    return e

def shaped_noise(d, fc, bw, seed=0):
    """bruit dont la bande passante suit fc(t)/bw(t) (fonctions du temps en s)"""
    n = np.random.default_rng(seed).standard_normal(int(d * SR) + 2048)
    f, tt, Z = signal.stft(n, SR, nperseg=1024)
    F = f[:, None]; T = tt[None, :]
    c = fc(T); b = bw(T)
    Z *= np.exp(-0.5 * ((F - c) / b) ** 2)
    _, x = signal.istft(Z, SR, nperseg=1024)
    x = x[:int(d * SR)]
    return x / (np.abs(x).max() + 1e-9)

def lp(x, f, o=4): return signal.sosfilt(signal.butter(o, f, 'low', fs=SR, output='sos'), x)
def hp(x, f, o=4): return signal.sosfilt(signal.butter(o, f, 'high', fs=SR, output='sos'), x)
def bp(x, a, b, o=3): return signal.sosfilt(signal.butter(o, [a, b], 'band', fs=SR, output='sos'), x)

def sweep(d, f0, f1, shape=1.0, wave='sin'):
    t = t_(d); f = f0 + (f1 - f0) * (t / d) ** shape
    ph = 2 * np.pi * np.cumsum(f) / SR
    if wave == 'saw': return 2 * ((ph / (2 * np.pi)) % 1) - 1
    return np.sin(ph)

def bell(d, f, partials=((1, 1), (2.76, 0.5), (5.4, 0.25), (8.9, 0.12)), decay=3.0):
    t = t_(d); x = np.zeros_like(t)
    for m, a in partials: x += a * np.sin(2 * np.pi * f * m * t) * np.exp(-t * decay * (0.6 + m * 0.3))
    return x

def reverb(x, secs=1.6, mix=0.35, seed=3):
    n = int(secs * SR); r = np.random.default_rng(seed)
    ir = r.standard_normal(n) * np.exp(-np.arange(n) / SR * 5.5 / secs)
    ir = lp(ir, 6000)
    dry = np.concatenate([x, np.zeros(n)])
    wet = signal.fftconvolve(x, ir)[:len(dry)]
    wet = np.concatenate([wet, np.zeros(len(dry) - len(wet))])
    wet /= np.abs(wet).max() + 1e-9
    return dry * (1 - mix) + wet * mix * np.abs(x).max()

def pad(x, d):
    n = int(d * SR)
    return np.concatenate([x, np.zeros(max(0, n - len(x)))])[:max(n, len(x))] if len(x) < n else x

def mix(*parts):
    """parts : (signal, début_s, gain)"""
    L = max(int(s * SR) + len(x) for x, s, g in parts)
    out = np.zeros(L)
    for x, s, g in parts:
        i = int(s * SR); out[i:i + len(x)] += x * g
    return out

def boom(d=1.6, f0=90, f1=32, seed=1):
    t = t_(d)
    sub = sweep(d, f0, f1, 0.4) * np.exp(-t * 2.6)
    crash = lp(np.random.default_rng(seed).standard_normal(len(t)), 900) * np.exp(-t * 4)
    click = hp(np.random.default_rng(seed + 1).standard_normal(len(t)), 2000) * np.exp(-t * 60)
    x = sub * 1.0 + crash * 0.55 / (np.abs(crash).max() + 1e-9) + click * 0.25 / (np.abs(click).max() + 1e-9)
    return np.tanh(x * 1.6)

def write(name, x, stereo_spread=0.012, rev=None, gain_db=-1.0):
    x = np.asarray(x, float).copy()
    fo = min(len(x), int(0.08 * SR)); x[-fo:] *= np.linspace(1, 0, fo) ** 2
    fi = int(0.002 * SR); x[:fi] *= np.linspace(0, 1, fi)
    if rev: x = reverb(x, *rev)
    x = x / (np.abs(x).max() + 1e-9) * 10 ** (gain_db / 20)
    d = int(stereo_spread * SR * 0.05)
    L = x; R = np.concatenate([np.zeros(d), x])[:len(x)]
    st = np.stack([L, R], 1)
    # fondu de fin
    f = min(len(st), int(0.03 * SR)); st[-f:] *= np.linspace(1, 0, f)[:, None]
    os.makedirs(os.path.join(SND, os.path.dirname(name)), exist_ok=True)
    raw = (st * 32767).astype(np.int16).tobytes()
    p = os.path.join(SND, name + '.ogg')
    # Minecraft : les sons positionnés doivent être mono ; on garde mono pour qu'ils viennent bien de l'endroit
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '2', '-i', '-', '-ac', '1', '-c:a', 'libvorbis', '-q:a', '6', p], input=raw, check=True)
    return name

made = []

# ---------------------------------------------------------------- Sonic Bow : charge qui monte + onde sonique
whoosh = shaped_noise(0.7, lambda T: 300 + 3000 * (T / 0.7) ** 2, lambda T: 200 + 600 * T, 11) * env(0.7, 0.55, 0.15)
pulse = boom(1.2, 140, 45, 2)
ring = sweep(1.2, 1400, 900, 0.5) * np.exp(-t_(1.2) * 5) * 0.5 + sweep(1.2, 2100, 1300, 0.5) * np.exp(-t_(1.2) * 7) * 0.3
made.append(write('weapons/sonic', mix((whoosh, 0, 0.6), (pulse, 0.62, 1.0), (ring, 0.62, 0.6)), rev=(1.2, 0.3)))

# ---------------------------------------------------------------- Tranche : coup de lame + tintement métallique
sw = shaped_noise(0.32, lambda T: 5000 - 3500 * T / 0.32, lambda T: 1500 + 0 * T, 21) * env(0.32, 0.06, 0.2)
metal = bell(1.2, 1650, ((1, 1), (1.73, 0.6), (2.61, 0.4), (3.9, 0.25)), 4.5)
thump = boom(0.5, 120, 60, 3) * 0.5
made.append(write('weapons/slash', mix((sw, 0, 1.0), (metal, 0.07, 0.35), (thump, 0.07, 0.6)), rev=(0.9, 0.22)))

# ---------------------------------------------------------------- Brise-Terre : séisme (grondement, craquements, boum)
d = 2.6; t = t_(d)
rumble = lp(np.cumsum(rng.standard_normal(len(t))), 140); rumble /= np.abs(rumble).max()
rumble *= np.minimum(1, t / 0.05) * np.exp(-t * 1.1)
cracks = np.zeros(len(t))
for k in range(26):
    i = int(rng.uniform(0, 1.4) * SR); L = int(rng.uniform(0.01, 0.05) * SR)
    cracks[i:i + L] += hp(rng.standard_normal(L), 1200) * np.exp(-np.arange(L) / L * 4) * rng.uniform(0.3, 1)
made.append(write('weapons/quake', mix((boom(2.0, 70, 25, 4), 0, 1.0), (rumble, 0, 0.9), (cracks, 0.02, 0.5)), rev=(1.8, 0.3)))

# ---------------------------------------------------------------- Faux des Âmes : lance fantôme (chœur + souffle inversé)
d = 1.8; t = t_(d)
choir = np.zeros(len(t))
for f in [220, 261.6, 329.6, 440, 523.2]:
    vib = 1 + 0.012 * np.sin(2 * np.pi * 5.5 * t + f)
    choir += np.sin(2 * np.pi * np.cumsum(f * vib) / SR) * 0.3
choir = bp(choir + 0.2 * rng.standard_normal(len(t)), 180, 2500) * (np.minimum(1, t / 0.5) * np.exp(-np.maximum(0, t - 0.9) * 3))
rev_sw = shaped_noise(1.0, lambda T: 600 + 2500 * T, lambda T: 400 + 0 * T, 31) * (t_(1.0) / 1.0) ** 2
made.append(write('weapons/soul', mix((rev_sw, 0, 0.7), (choir, 0.2, 0.9), (sweep(0.6, 900, 200, 0.6) * np.exp(-t_(0.6) * 5), 1.0, 0.4)), rev=(2.2, 0.45)))
made.append(write('weapons/soul_boom', mix((boom(1.6, 110, 35, 5), 0, 1.0), (choir[:int(1.2 * SR)], 0, 0.6), (bell(1.6, 880, decay=2.5), 0, 0.25)), rev=(2.0, 0.4)))

# ---------------------------------------------------------------- Croc de Givre : ruée glacée (souffle clair + cristaux + craquement)
d = 1.3
air = shaped_noise(d, lambda T: 2500 + 4000 * np.exp(-T * 3), lambda T: 1800 + 0 * T, 41) * env(d, 0.03, 0.6)
crys = np.zeros(int(d * SR))
for k in range(18):
    s = rng.uniform(0, 0.9); b = bell(0.6, rng.uniform(2200, 5200), decay=9) * rng.uniform(0.2, 0.6)
    i = int(s * SR); crys[i:i + len(b)] += b[:len(crys) - i]
crack = hp(rng.standard_normal(int(0.08 * SR)), 1500) * np.exp(-t_(0.08) * 40)
made.append(write('weapons/frost', mix((air, 0, 0.8), (crys, 0, 0.6), (crack, 0.02, 0.9), (crack, 0.4, 0.5)), rev=(1.4, 0.35)))

# ---------------------------------------------------------------- Arc Tempête : éclair (claquement sec + tonnerre)
d = 3.0; t = t_(d)
snap = hp(rng.standard_normal(int(0.06 * SR)), 900) * np.exp(-t_(0.06) * 50)
zap = sweep(0.25, 3200, 600, 0.3, 'saw') * np.exp(-t_(0.25) * 14) * 0.4
thunder = lp(np.cumsum(rng.standard_normal(len(t))), 260); thunder /= np.abs(thunder).max()
thunder *= np.exp(-t * 1.3) * (1 + 0.6 * np.sin(2 * np.pi * 3.1 * t) * np.exp(-t * 2))
made.append(write('weapons/thunder', mix((snap, 0, 1.0), (zap, 0, 0.7), (thunder, 0.05, 1.0), (boom(1.2, 80, 35, 6), 0.03, 0.6)), rev=(2.0, 0.35)))

# ---------------------------------------------------------------- Lame Citrouille : coup de feu (souffle + crépitements + rugissement)
d = 1.0; t = t_(d)
fsw = shaped_noise(d, lambda T: 3000 - 2200 * np.minimum(1, T / 0.4), lambda T: 1200 + 0 * T, 51) * env(d, 0.04, 0.5)
crack2 = np.zeros(len(t))
for k in range(60):
    i = int(rng.uniform(0, 0.9) * SR); L = int(0.004 * SR)
    crack2[i:i + L] += rng.standard_normal(L) * rng.uniform(0.2, 1)
roar = lp(rng.standard_normal(len(t)), 500) * env(d, 0.05, 0.6); roar /= np.abs(roar).max()
made.append(write('weapons/fire_slash', mix((fsw, 0, 0.9), (hp(crack2, 1500), 0, 0.4), (roar, 0, 0.7)), rev=(1.0, 0.25)))

# ---------------------------------------------------------------- Feux Follets : tir féerique (cloches + scintillement)
d = 1.4
chimes = np.zeros(int(d * SR))
for k, f in enumerate([1318, 1568, 1976, 2637, 3136]):
    b = bell(1.0, f, decay=4.5); i = int(k * 0.05 * SR); chimes[i:i + len(b)] += b[:len(chimes) - i] * 0.4
wsh = shaped_noise(d, lambda T: 4000 + 0 * T, lambda T: 1500 + 0 * T, 61) * env(d, 0.02, 0.4)
made.append(write('weapons/wisp', mix((chimes, 0, 0.8), (wsh, 0, 0.35)), rev=(1.8, 0.45)))

# ---------------------------------------------------------------- Hache Maudite : coup écrasant (sub + drone sombre + fracas)
d = 2.2; t = t_(d)
drone = sum(sweep(d, f, f * 0.94, 1, 'saw') for f in [55, 55.7, 82.4]) / 3
drone = lp(drone, 700) * env(d, 0.02, 1.4)
made.append(write('weapons/curse', mix((boom(1.8, 75, 28, 7), 0, 1.0), (drone, 0, 0.7), (lp(rng.standard_normal(int(0.9 * SR)), 2500) * np.exp(-t_(0.9) * 6), 0, 0.5)), rev=(2.2, 0.4)))

# ---------------------------------------------------------------- Boss : rugissement, explosion, grand coup
d = 2.2; t = t_(d)
f0 = 72 * (1 + 0.15 * np.sin(2 * np.pi * 7 * t)) * (1 - 0.25 * t / d)
saw = 2 * ((np.cumsum(f0) / SR) % 1) - 1
growl = np.zeros(len(t))
for fm, bwd in [(450, 120), (900, 200), (2400, 400)]:
    growl += bp(saw + 0.3 * rng.standard_normal(len(t)), fm - bwd, fm + bwd, 2)
growl = np.tanh(growl * 3) * env(d, 0.12, 1.2)
made.append(write('boss/roar', mix((growl, 0, 1.0), (boom(1.5, 60, 30, 8), 0, 0.35)), rev=(2.4, 0.4)))
made.append(write('boss/boom', mix((boom(3.0, 85, 22, 9), 0, 1.0), (lp(np.cumsum(rng.standard_normal(int(2.5 * SR))), 200) * np.exp(-t_(2.5) * 1.5) / 300, 0.05, 1.0)), rev=(2.6, 0.35)))
made.append(write('boss/whoosh', shaped_noise(0.9, lambda T: 400 + 1800 * np.sin(np.pi * T / 0.9), lambda T: 400 + 0 * T, 71) * np.sin(np.pi * t_(0.9) / 0.9) ** 2, rev=(0.8, 0.2)))
made.append(write('boss/magic', mix((bell(2.0, 330, decay=1.5), 0, 0.5), (bell(2.0, 415, decay=1.5), 0.05, 0.4), (shaped_noise(2.0, lambda T: 1200 + 800 * np.sin(T * 6), lambda T: 500 + 0 * T, 81) * env(2.0, 0.3, 1.2), 0, 0.5)), rev=(2.5, 0.5)))

# ---------------------------------------------------------------- impact lourd (en plus pour Fûga / Violet Imaginaire)
made.append(write('weapons/impact', mix((boom(3.2, 70, 20, 10), 0, 1.0), (boom(1.0, 160, 60, 11), 0, 0.4)), rev=(3.0, 0.4)))

# ---------------------------------------------------------------- Reine Araignée
# cri aigu (FM qui grince) + souffle
d = 1.6; t = t_(d)
car = 900 + 500 * np.sin(2 * np.pi * 7 * t) * np.exp(-t * 1.5)
mod = np.sin(2 * np.pi * 1300 * t) * 3.5
scr = np.sin(2 * np.pi * np.cumsum(car) / SR + mod)
scr = np.tanh(bp(scr + 0.3 * rng.standard_normal(len(t)), 500, 6000) * 2.5) * env(d, 0.06, 1.0)
made.append(write('spider/screech', mix((scr, 0, 1.0), (shaped_noise(d, lambda T: 3000 + 0 * T, lambda T: 1500 + 0 * T, 91) * env(d, 0.02, 0.8), 0, 0.4), (boom(0.8, 90, 40, 12), 0, 0.35)), rev=(1.8, 0.35)))
# sifflement (bruit filtré haut, enveloppe en bosse)
d = 1.0
made.append(write('spider/hiss', shaped_noise(d, lambda T: 5200 - 1500 * T, lambda T: 1800 + 0 * T, 92) * np.sin(np.pi * t_(d) / d) ** 1.5, rev=(0.7, 0.2)))
# toile : étirement collant (bruit grave modulé + craquements)
d = 1.1; t = t_(d)
stretch = lp(rng.standard_normal(len(t)), 900) * (0.5 + 0.5 * np.sin(2 * np.pi * 23 * t)) * env(d, 0.15, 0.7)
creak = np.zeros(len(t))
for k in range(30):
    i = int(rng.uniform(0, 0.9) * SR); L = int(0.003 * SR)
    creak[i:i + L] += rng.standard_normal(L)
made.append(write('spider/web', mix((stretch / (np.abs(stretch).max() + 1e-9), 0, 0.8), (bp(creak, 1500, 5000), 0, 0.5), (sweep(0.6, 300, 180, 1) * np.exp(-t_(0.6) * 5), 0, 0.3)), rev=(1.0, 0.25)))
# pas : cliquetis rapides
d = 0.7
clk = np.zeros(int(d * SR))
for k in range(16):
    i = int((k * 0.04 + rng.uniform(0, 0.01)) * SR); L = int(0.006 * SR)
    clk[i:i + L] += hp(rng.standard_normal(L), 2000) * np.exp(-np.arange(L) / L * 5) * rng.uniform(0.4, 1)
made.append(write('spider/skitter', clk, rev=(0.5, 0.15)))

# sounds.json : on ajoute nos sons à ceux qui existent
sj = os.path.join(OUT, 'assets/limitless/sounds.json')
data = json.load(open(sj)) if os.path.exists(sj) else {}
for n in made:
    data[n.replace('/', '.')] = {'sounds': [{'name': 'limitless:' + n}]}
json.dump(data, open(sj, 'w'), indent=1)
print('sons', made)
