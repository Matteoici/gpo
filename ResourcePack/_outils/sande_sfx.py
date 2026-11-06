"""Sons originaux du Sandevistan (synthèse) : activation, boucle, arrêt. Lancer dans un dossier vide, puis copier les .ogg dans assets/limitless/sounds/cyber/ (sounds.json : cyber.sande_on / sande_loop / sande_off)."""
import numpy as np, wave, subprocess, sys
SR = 44100
rng = np.random.default_rng(7)
def env(n, a, d):
    e = np.ones(n); ai = int(a * SR); di = int(d * SR)
    if ai: e[:ai] = np.linspace(0, 1, ai)
    if di: e[-di:] *= np.linspace(1, 0, di) ** 2
    return e
def lp(x, k):  # passe-bas simple (moyenne glissante)
    return np.convolve(x, np.ones(k) / k, 'same')
def sweep(f0, f1, dur, kind='sin'):
    t = np.arange(int(dur * SR)) / SR
    f = f0 * (f1 / f0) ** (t / dur)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) if kind == 'sin' else np.sign(np.sin(ph)) * 0.4 + np.sin(ph) * 0.6
def write(name, x):
    x = x / (np.max(np.abs(x)) + 1e-9) * 0.9
    st = np.stack([x, x], 1)
    with wave.open(name + '.wav', 'wb') as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((st * 32767).astype(np.int16).tobytes())
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', name + '.wav', '-ac', '1', '-c:a', 'libvorbis', '-q:a', '5', name + '.ogg'], check=True)

# activation : coup sourd, montée électrique qui s'emballe, puis un accord de verre qui reste suspendu
D = 2.6; n = int(D * SR); t = np.arange(n) / SR
x = np.zeros(n)
thump = np.sin(2 * np.pi * 48 * t[:int(0.5 * SR)] * (1 - t[:int(0.5 * SR)] * 0.6)) * np.exp(-t[:int(0.5 * SR)] * 7)
x[:len(thump)] += thump * 1.2
rise = sweep(180, 2600, 0.55, 'sq') * env(int(0.55 * SR), 0.05, 0.08) * 0.35
x[int(0.05 * SR):int(0.05 * SR) + len(rise)] += rise
noise = lp(rng.normal(0, 1, int(0.6 * SR)), 6) * np.linspace(0, 1, int(0.6 * SR)) ** 2 * env(int(0.6 * SR), 0, 0.1) * 0.5
x[:len(noise)] += noise
k = int(0.6 * SR); tt = t[k:] - t[k]
chord = sum(np.sin(2 * np.pi * f * tt) * a for f, a in [(880, 0.3), (1318.5, 0.22), (1760, 0.15), (2637, 0.08)])
trem = 0.75 + 0.25 * np.sin(2 * np.pi * 7 * tt)
x[k:] += chord * np.exp(-tt * 1.3) * trem
sub = np.sin(2 * np.pi * 55 * tt) * np.exp(-tt * 1.6) * 0.5
x[k:] += sub
write('sande_on', x)

# boucle : bourdonnement grave, battement lent, petites étincelles
D = 2.0; n = int(D * SR); t = np.arange(n) / SR
x = np.sin(2 * np.pi * 62 * t) * 0.5 + np.sin(2 * np.pi * 93 * t) * 0.25
x *= 0.6 + 0.4 * np.sin(2 * np.pi * 0.5 * t) ** 2
for _ in range(6):
    p0 = rng.integers(0, n - 4000)
    tk = np.arange(3000) / SR
    x[p0:p0 + 3000] += np.sin(2 * np.pi * rng.uniform(3000, 5000) * tk) * np.exp(-tk * 60) * 0.25
x *= env(n, 0.15, 0.25)
write('sande_loop', x)

# arrêt : tout redescend d'un coup, verre qui tinte
D = 1.4; n = int(D * SR); t = np.arange(n) / SR
x = sweep(1800, 90, 0.7, 'sq') * env(int(0.7 * SR), 0.01, 0.2) * 0.45
x = np.concatenate([x, np.zeros(n - len(x))])
x[:int(0.3 * SR)] += lp(rng.normal(0, 1, int(0.3 * SR)), 4) * np.exp(-t[:int(0.3 * SR)] * 12) * 0.5
for i, f in enumerate([2093, 2637, 3136]):
    s0 = int((0.25 + i * 0.07) * SR); tk = t[:n - s0]
    x[s0:] += np.sin(2 * np.pi * f * tk) * np.exp(-tk * 6) * 0.18
write('sande_off', x)
print('ok')
