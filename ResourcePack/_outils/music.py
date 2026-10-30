"""Musiques d'ambiance originales des deux boss (synthèse, boucles sans coupure).
 - boss/music_pumpkin : « Le Bal du Roi Citrouille » — valse macabre en ré mineur : clavecin, orgue, chœur, timbales, cloches
 - spider/music_queen : « La Toile » — mi phrygien, lent et tendu : bourdon de violoncelle, pizzicati qui grouillent,
   boîte à musique, battements de cœur, cordes dissonantes
usage : music.py <racine du pack> [--wav]"""
import numpy as np, os, sys, subprocess, json
from scipy.signal import lfilter

OUT = sys.argv[1]
SR = 44100
rng = np.random.default_rng(31)

def mf(m):
    return 440.0 * 2 ** ((m - 69) / 12)

def env(n, a, d, s, r, sus_level=0.7):
    """ADSR en secondes, n = durée totale (échantillons)"""
    t = np.arange(n) / SR
    dur = n / SR
    e = np.ones(n)
    e = np.where(t < a, t / max(a, 1e-4), e)
    e = np.where((t >= a) & (t < a + d), 1 - (1 - sus_level) * (t - a) / max(d, 1e-4), e)
    e = np.where((t >= a + d), sus_level, e)
    rs = max(dur - r, 0)
    e = np.where(t >= rs, e * np.clip(1 - (t - rs) / max(r, 1e-4), 0, 1), e)
    return e

# ---------------------------------------------------------------- instruments (mono, renvoient un tableau)

def pluck(m, dur, bright=1.0, decay=2.0):
    """clavecin / pizzicato : harmoniques qui s'éteignent vite, les aiguës plus vite"""
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    x = np.zeros(n)
    for k in range(1, 14):
        if f * k > 9000: break
        amp = (1 / k ** (1.1 - 0.3 * bright)) * (0.6 if k % 2 == 0 else 1)
        x += amp * np.sin(2 * np.pi * f * k * 1.0008 ** (k - 1) * t) * np.exp(-t * (decay + k * 1.6))
    x *= np.minimum(1, t / 0.002)
    return x / 3

def organ(m, dur, a=0.04, r=0.12):
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    x = np.zeros(n)
    for k, amp in zip([1, 2, 3, 4, 6, 8], [1, 0.55, 0.32, 0.22, 0.12, 0.07]):
        for det in (-0.6, 0.6):
            x += amp * np.sin(2 * np.pi * (f * k + det) * t + k)
    x *= 1 + 0.12 * np.sin(2 * np.pi * 5.6 * t)
    return x * env(n, a, 0.2, 0.85, r, 0.85) / 5

def choir(m, dur, a=0.6, r=0.8):
    """chœur « ouuh » : dent de scie filtrée + vibrato + formants grossiers"""
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    vib = 1 + 0.006 * np.sin(2 * np.pi * 5.2 * t + rng.random() * 6)
    ph = 2 * np.pi * f * np.cumsum(vib) / SR
    x = np.zeros(n)
    for k in range(1, 24):
        if f * k > 5000: break
        fk = f * k
        form = np.exp(-((fk - 400) / 260) ** 2) * 1.0 + np.exp(-((fk - 800) / 300) ** 2) * 0.5 + np.exp(-((fk - 2600) / 500) ** 2) * 0.12
        x += (form + 0.03) / k ** 0.5 * np.sin(k * ph)
    return x * env(n, a, 0.3, 0.9, r, 0.9) / 4

def bell(m, dur, decay=1.4):
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    x = np.zeros(n)
    for ratio, amp, dk in [(1, 1, 1), (2.0, 0.6, 1.4), (2.76, 0.45, 2.0), (5.4, 0.25, 3.2), (8.9, 0.12, 5)]:
        x += amp * np.sin(2 * np.pi * f * ratio * t) * np.exp(-t * decay * dk)
    return x * np.minimum(1, t / 0.003) / 2.5

def musicbox(m, dur):
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    x = np.sin(2 * np.pi * f * t) * np.exp(-t * 2.2) + 0.35 * np.sin(2 * np.pi * f * 3.01 * t) * np.exp(-t * 6) \
        + 0.15 * np.sin(2 * np.pi * f * 5.98 * t) * np.exp(-t * 10)
    return x * np.minimum(1, t / 0.002) / 1.6

def timpani(m, dur):
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    bend = f * (1 + 0.15 * np.exp(-t * 18))
    ph = 2 * np.pi * np.cumsum(bend) / SR
    x = np.sin(ph) * np.exp(-t * 2.4) + 0.4 * np.sin(1.5 * ph) * np.exp(-t * 4)
    noise = rng.normal(0, 1, n) * np.exp(-t * 40) * 0.3
    return (x + noise) / 1.5

def heartbeat(dur):
    n = int(dur * SR); t = np.arange(n) / SR
    x = np.zeros(n)
    for off, amp in [(0, 1.0), (0.17, 0.7)]:
        tt = t - off; on = tt >= 0
        bend = 55 * (1 + 0.8 * np.exp(-np.maximum(tt, 0) * 30))
        ph = 2 * np.pi * np.cumsum(bend * on) / SR
        x += on * amp * np.sin(ph) * np.exp(-np.maximum(tt, 0) * 9)
    return x

def cello(m, dur, a=0.5, r=0.6):
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    vib = 1 + 0.004 * np.sin(2 * np.pi * 4.8 * t)
    ph = 2 * np.pi * f * np.cumsum(vib) / SR
    x = np.zeros(n)
    for k in range(1, 18):
        if f * k > 4000: break
        x += (1 / k) * np.sin(k * ph) * (1.0 if k < 6 else 0.6)
    bow = lfilter([0.05], [1, -0.95], rng.normal(0, 1, n)) * 0.15
    return (x + bow) * env(n, a, 0.4, 0.9, r, 0.9) / 3

def ticks(dur, density=10):
    """petites pattes qui courent : grains de bruit aigu"""
    n = int(dur * SR); x = np.zeros(n)
    for _ in range(int(dur * density)):
        p = rng.integers(0, max(1, n - 800)); L = 300
        g = rng.normal(0, 1, L) * np.exp(-np.arange(L) / 40)
        g = np.diff(np.concatenate([[0], g]))  # passe-haut
        x[p:p + L] += g * rng.uniform(0.2, 0.6)
    return x

# ---------------------------------------------------------------- mixage

class Track:
    def __init__(self, seconds):
        self.n = int(seconds * SR)
        self.L = np.zeros(self.n + SR * 8)
        self.R = np.zeros(self.n + SR * 8)

    def add(self, sig, at, gain=1.0, pan=0.0):
        if at < 0:
            k = int(-at * SR)
            self.add(sig[:k], self.n / SR + at, gain, pan)
            sig = sig[k:]
            at = 0
        i = int(at * SR)
        if i >= len(self.L): return
        sig = sig[:len(self.L) - i]
        gl, gr = gain * np.sqrt((1 - pan) / 2), gain * np.sqrt((1 + pan) / 2)
        self.L[i:i + len(sig)] += sig * gl
        self.R[i:i + len(sig)] += sig * gr

def reverb(x, mix=0.3, size=1.0):
    out = np.zeros_like(x)
    for d, g in [(1557, 0.84), (1617, 0.83), (1491, 0.85), (1422, 0.86), (1277, 0.87), (1356, 0.86)]:
        d = int(d * size)
        b = np.zeros(d + 1); b[0] = 0; b[d] = 1
        a = np.zeros(d + 1); a[0] = 1; a[d] = -g
        out += lfilter(b, a, x)
    out /= 6
    for d in (225, 556, 441):
        b = np.zeros(d + 1); b[0] = -0.5; b[d] = 1
        a = np.zeros(d + 1); a[0] = 1; a[d] = -0.5
        out = lfilter(b, a, out)
    return x * (1 - mix) + out * mix

def finish(tr, name, mix=0.3, size=1.0, gain_db=-3):
    L = reverb(tr.L, mix, size); R = reverb(tr.R, mix, size * 1.07)
    # boucle sans coupure : la queue (réverb) revient au début
    n = tr.n
    tailL, tailR = L[n:], R[n:]
    L, R = L[:n].copy(), R[:n].copy()
    k = min(len(tailL), n)
    L[:k] += tailL[:k]; R[:k] += tailR[:k]
    st = np.stack([L, R], 1)
    st /= np.max(np.abs(st)) + 1e-9
    st *= 10 ** (gain_db / 20)
    # tout petit fondu aux jointures pour éviter un clic
    f = 64
    st[:f] *= np.linspace(0.9, 1, f)[:, None]; st[-f:] *= np.linspace(1, 0.9, f)[:, None]
    raw = (np.clip(st, -1, 1) * 32767).astype('<i2').tobytes()
    p = os.path.join(OUT, 'assets/limitless/sounds', name + '.ogg')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '2', '-i', '-', '-c:a', 'libvorbis', '-q:a', '4', p],
                   input=raw, check=True)
    if '--wav' in sys.argv:
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '2', '-i', '-', p.replace('.ogg', '.wav')], input=raw, check=True)
    print(name, round(n / SR, 1), 's', os.path.getsize(p) // 1024, 'Ko')

# ================================================================ « Le Bal du Roi Citrouille » (ré mineur, 3/4)

def pumpkin():
    beat = 0.42; bar = beat * 3
    D2, E2, G2, A2, Bb1 = 38, 40, 43, 45, 34
    # accords (basse, accord) sur 8 mesures
    prog = [(D2, [62, 65, 69]), (D2, [62, 65, 69]), (G2, [62, 67, 70]), (A2, [61, 64, 69]),
            (D2, [62, 65, 69]), (Bb1 + 12, [62, 65, 70]), (E2, [64, 67, 70]), (A2, [61, 64, 67])]
    melA = [[(69, 1), (74, 1), (77, 1)], [(76, 2), (74, 1)], [(70, 1), (74, 1), (79, 1)], [(77, 1), (76, 1), (73, 1)],
            [(74, 1), (77, 1), (81, 1)], [(79, 2), (77, 1)], [(76, 1), (79, 1), (82, 1)], [(81, 1.5), (79, 0.5), (73, 1)]]
    melB = [[(86, 1), (81, 1), (77, 1)], [(76, 1), (77, 1), (74, 1)], [(79, 1), (82, 1), (86, 1)], [(85, 2), (81, 1)],
            [(77, 1), (76, 1), (74, 1)], [(74, 1), (70, 1), (77, 1)], [(76, 1), (73, 1), (69, 1)], [(74, 3)]]
    bars = 48
    tr = Track(bars * bar)
    for b in range(bars):
        t0 = b * bar
        sec = b // 16          # 0 : A, 1 : B (plein), 2 : pont puis retour
        bass, ch = prog[b % 8]
        # pont (32-39) : basse chromatique qui descend, plus sombre
        if 32 <= b < 40:
            bass = [38, 37, 36, 35, 34, 33, 40, 45][b - 32]
            ch = [[62, 65, 69], [61, 65, 68], [60, 63, 67], [59, 62, 66], [58, 62, 65], [57, 61, 64], [58, 64, 67], [61, 64, 67]][b - 32]
        # oum-pa-pa au clavecin
        tr.add(pluck(bass, 1.2, 0.6, 2.2), t0, 0.9, -0.15)
        tr.add(pluck(bass + 12, 0.8, 0.6, 3), t0, 0.35, -0.15)
        for k in (1, 2):
            for i, n_ in enumerate(ch):
                tr.add(pluck(n_, 0.5, 1.0, 4.5), t0 + k * beat + i * 0.006, 0.32, 0.25 - i * 0.1)
        # mélodie
        if b < 32:
            mel = (melA if (b // 8) % 2 == 0 else melB)[b % 8]
            tt = t0
            for n_, ln in mel:
                d = ln * beat
                if sec == 0:
                    tr.add(organ(n_, d * 0.98), tt, 0.55, 0.1)
                else:
                    tr.add(organ(n_, d * 0.98), tt, 0.45, 0.1)
                    tr.add(bell(n_ + 12, 2.2), tt, 0.22, 0.4)
                tt += d
        elif b < 40:
            # pont : cloches lointaines + chœur
            if b % 2 == 0: tr.add(bell([86, 85, 84, 83][(b - 32) // 2], 3.5, 0.9), t0, 0.3, -0.5)
        else:
            # retour : la mélodie A en sourdine (orgue doux), pour reboucler
            mel = melA[b % 8]
            tt = t0
            for n_, ln in mel:
                tr.add(organ(n_ - 12, ln * beat * 0.98, a=0.08), tt, 0.32, 0.1)
                tt += ln * beat
        # chœur en section B et dans le pont
        if 16 <= b < 40 and b % 2 == 0:
            for i, n_ in enumerate(ch):
                tr.add(choir(n_ - 12 + (12 if i == 2 else 0), bar * 2.05), t0, 0.28, -0.5 + i * 0.5)
        # timbales
        if 16 <= b < 32:
            tr.add(timpani(bass + 12 if bass < 40 else bass, 1.2), t0, 0.5, 0)
            if b % 4 == 3: tr.add(timpani(bass + 12 if bass < 40 else bass, 0.6), t0 + 2 * beat, 0.35, 0)
        if b in (31, 39, 47):
            for k in range(6): tr.add(timpani(45, 0.4), t0 + 1.5 * beat + k * beat / 4, 0.25 + k * 0.05, 0)
    finish(tr, 'boss/music_pumpkin', mix=0.32, size=1.1)

# ================================================================ « La Toile » (mi phrygien, 4/4, lent)

def queen():
    beat = 0.6; bar = beat * 4
    bars = 28
    tr = Track(bars * bar)
    # harmonie par mesure : (racine du bourdon, notes de l'ostinato)
    E, F, A, C, B = 28, 29, 33, 36, 35
    plan = [E, E, F, E, E, E, F, E, A, A, C, B, E, E, F, F, E, E, F, E, A, C, B, B, E, F, E, E]
    osti = {E: [52, 55, 59, 60, 59, 55, 53, 55], F: [53, 57, 60, 64, 60, 57, 53, 57], A: [57, 60, 64, 65, 64, 60, 57, 60],
            C: [55, 60, 64, 67, 64, 60, 55, 60], B: [54, 59, 63, 64, 63, 59, 54, 59]}
    box = [None, None, (83, 2), (84, 1), (83, 1), None, (79, 2), (77, 2), None, (76, 4), None, None]
    for b in range(bars):
        t0 = b * bar
        root = plan[b]
        # bourdon de violoncelle (une note longue toutes les 2 mesures)
        if b % 2 == 0:
            tr.add(cello(root + 12, bar * 2 + 1.6, a=1.4, r=1.6), t0 - 0.4, 0.55, -0.2)
            tr.add(cello(root + 19, bar * 2 + 1.6, a=1.6, r=1.6), t0 - 0.4, 0.22, 0.3)
        # pizzicati qui grouillent (croches), absents au tout début
        if b >= 2:
            pat = osti[root]
            for i in range(8):
                acc = 1.0 if i % 4 == 0 else 0.7
                tr.add(pluck(pat[i], 0.4, 0.5, 7), t0 + i * beat / 2, 0.30 * acc, -0.4 + 0.1 * (i % 8))
            # doubles-croches nerveuses dans la 2e moitié
            if 12 <= b < 24 and b % 2 == 1:
                for i in range(8):
                    tr.add(pluck(pat[(i * 3) % 8] + 12, 0.25, 0.8, 9), t0 + 2 * beat + i * beat / 4, 0.16, 0.4)
        # battements de cœur
        if b >= 8:
            tr.add(heartbeat(1.0), t0, 0.75, 0)
            if b >= 16: tr.add(heartbeat(1.0), t0 + 2 * beat, 0.55, 0)
        # boîte à musique (mélodie lointaine, en haut)
        ph = b % 12
        if box[ph] is not None and b >= 4:
            n_, ln = box[ph]
            tr.add(musicbox(n_, ln * beat + 1.5), t0 + beat, 0.30, 0.55)
            tr.add(musicbox(n_ + 7, 1.2), t0 + beat + 0.003, 0.08, 0.6)
        # cordes dissonantes qui montent (mi + fa + si)
        if b in (12, 24):
            for i, n_ in enumerate([64, 65, 71, 76]):
                tr.add(cello(n_, bar * 4, a=bar * 3.2, r=0.4), t0, 0.16, -0.6 + i * 0.4)
        # petites pattes
        tr.add(ticks(bar, 6 + (6 if b >= 12 else 0)), t0, 0.10, (b % 3 - 1) * 0.6)
    finish(tr, 'spider/music_queen', mix=0.38, size=1.3)

pumpkin()
queen()

# déclaration dans sounds.json (à garder après pack.py / sfx.py)
sj = os.path.join(OUT, 'assets/limitless/sounds.json')
d = json.load(open(sj))
d['boss.music_pumpkin'] = {'sounds': [{'name': 'limitless:boss/music_pumpkin', 'stream': True}]}
d['spider.music_queen'] = {'sounds': [{'name': 'limitless:spider/music_queen', 'stream': True}]}
json.dump(d, open(sj, 'w'), indent=1)
print('sounds.json ok')
