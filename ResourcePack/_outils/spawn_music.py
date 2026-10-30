"""Musique d'ambiance du spawn (médiévale, calme) : « Le Village de Limitless ».
Ré dorien, 6/8 : vielle à roue (bourdon), luth en arpèges, flûte à bec, harpe, bodhran et tambourin.
usage : spawn_music.py <racine du pack> [--wav]"""
import os, sys, json
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, 'music.py')).read().split('# ================================================================ « Le Bal')[0]
exec(src)  # outils de music.py (instruments, Track, reverb, finish), sans générer les musiques des boss

# ---------------------------------------------------------------- instruments en plus

def recorder(m, dur, a=0.06, r=0.18):
    """flûte à bec : presque une sinusoïde, un peu de souffle, vibrato qui arrive doucement"""
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    vib = 1 + 0.005 * np.sin(2 * np.pi * 5.0 * t) * np.clip((t - 0.25) / 0.4, 0, 1)
    ph = 2 * np.pi * f * np.cumsum(vib) / SR
    x = np.sin(ph) + 0.22 * np.sin(2 * ph) + 0.08 * np.sin(3 * ph)
    br = lfilter([0.02], [1, -0.98], rng.normal(0, 1, n))
    br = br - lfilter([0.3], [1, -0.7], br)
    x += br * 1.2
    chiff = rng.normal(0, 1, n) * np.exp(-t * 60) * 0.25
    return (x + chiff) * env(n, a, 0.1, 0.9, r, 0.9) / 2.2

def lute(m, dur):
    """luth : pincé plus chaud et plus long que le clavecin"""
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    x = np.zeros(n)
    for k in range(1, 10):
        if f * k > 6000: break
        amp = (1 / k ** 1.4) * (0.5 if k == 2 else 1)
        x += amp * np.sin(2 * np.pi * f * k * 1.0004 ** (k - 1) * t) * np.exp(-t * (2.2 + k * 1.1))
    body = lfilter([0.15], [1, -0.85], x)
    return (x * 0.7 + body * 0.6) * np.minimum(1, t / 0.003) / 2

def gurdy(m, dur):
    """vielle à roue : bourdon nasal un peu grinçant"""
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    ph = 2 * np.pi * f * np.cumsum(1 + 0.0015 * np.sin(2 * np.pi * 0.7 * t)) / SR
    x = np.zeros(n)
    for k in range(1, 16):
        if f * k > 5000: break
        form = 1 + 1.5 * np.exp(-((f * k - 1100) / 400) ** 2)
        x += form / k * np.sin(k * ph)
    rasp = lfilter([0.1], [1, -0.9], rng.normal(0, 1, n)) * (1 + 0.5 * np.sin(2 * np.pi * 9 * t))
    return (x + rasp * 0.25) * env(n, 0.8, 0.2, 0.95, 0.8, 0.95) / 6

def harp(m, dur):
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    x = np.sin(2 * np.pi * f * t) + 0.3 * np.sin(4 * np.pi * f * t) * np.exp(-t * 3) + 0.1 * np.sin(6 * np.pi * f * t) * np.exp(-t * 6)
    return x * np.exp(-t * 1.3) * np.minimum(1, t / 0.002) / 2

def bodhran(dur, accent=1.0):
    n = int(dur * SR); t = np.arange(n) / SR
    bend = 70 * (1 + 0.6 * np.exp(-t * 25))
    x = np.sin(2 * np.pi * np.cumsum(bend) / SR) * np.exp(-t * 7)
    x += lfilter([0.2], [1, -0.8], rng.normal(0, 1, n)) * np.exp(-t * 30) * 0.5
    return x * accent

def tambourine(dur):
    n = int(dur * SR); t = np.arange(n) / SR
    x = rng.normal(0, 1, n)
    x = x - lfilter([0.5], [1, -0.5], x)
    x *= np.exp(-t * 16) * (1 + 0.5 * np.sin(2 * np.pi * 34 * t))
    return x * 0.5

# ---------------------------------------------------------------- composition

E8 = 0.26          # croche (6/8 tranquille)
BAR = E8 * 6
CH = {'Dm': (50, 53, 57), 'C': (48, 52, 55), 'Am': (45, 48, 52), 'F': (41, 45, 48), 'G': (43, 47, 50)}
PROG = ['Dm', 'Dm', 'C', 'C', 'Dm', 'Dm', 'Am', 'Am', 'F', 'F', 'C', 'C', 'G', 'G', 'Dm', 'Dm']
# mélodie : 16 mesures, (note, croches)
MEL = [[(69, 3), (67, 1), (65, 1), (64, 1)], [(62, 3), (65, 2), (69, 1)],
       [(67, 3), (64, 2), (60, 1)], [(64, 2), (65, 1), (67, 3)],
       [(69, 2), (72, 1), (74, 3)], [(72, 2), (69, 1), (65, 3)],
       [(64, 2), (65, 1), (67, 2), (69, 1)], [(69, 6)],
       [(65, 2), (69, 1), (72, 3)], [(69, 2), (67, 1), (65, 3)],
       [(64, 2), (67, 1), (72, 3)], [(71, 2), (69, 1), (67, 3)],
       [(71, 3), (74, 2), (71, 1)], [(69, 2), (67, 1), (71, 3)],
       [(69, 2), (65, 1), (64, 2), (65, 1)], [(62, 6)]]
# variation (3e passage) : ornements et plus haut
MEL2 = [[(74, 3), (72, 1), (69, 1), (67, 1)], [(65, 2), (67, 1), (69, 3)],
        [(67, 2), (69, 1), (72, 2), (67, 1)], [(64, 3), (67, 3)],
        [(69, 1), (72, 1), (74, 1), (77, 3)], [(76, 2), (74, 1), (72, 3)],
        [(72, 2), (71, 1), (69, 2), (67, 1)], [(69, 6)],
        [(72, 3), (69, 2), (72, 1)], [(77, 3), (76, 2), (74, 1)],
        [(72, 2), (74, 1), (76, 3)], [(74, 2), (72, 1), (71, 3)],
        [(74, 3), (71, 2), (67, 1)], [(69, 2), (71, 1), (74, 3)],
        [(72, 2), (69, 1), (67, 2), (64, 1)], [(62, 6)]]

ROUNDS = 3
bars = 16 * ROUNDS
tr = Track(bars * BAR)
for b in range(bars):
    t0 = b * BAR
    rnd = b // 16; i16 = b % 16
    ch = CH[PROG[i16]]
    # bourdon de vielle (ré + la), relancé toutes les 4 mesures en se chevauchant
    if b % 4 == 0:
        tr.add(gurdy(38, BAR * 4 + 1.6), t0 - 0.3, 0.32, -0.25)
        tr.add(gurdy(45, BAR * 4 + 1.6), t0 - 0.3, 0.2, 0.25)
    # luth : arpège en croches (fondamentale, quinte, octave, tierce, octave, quinte)
    r_, th, fi = ch
    pat = [r_, fi, r_ + 12, th + 12, r_ + 12, fi]
    for k in range(6):
        if rnd == 0 and i16 < 2 and k > 0 and k % 3 != 0: continue  # entrée douce
        acc = 1.0 if k in (0, 3) else 0.72
        tr.add(lute(pat[k], 1.6), t0 + k * E8 + rng.uniform(0, 0.008), 0.42 * acc, -0.35)
    # flûte : mélodie aux 2e et 3e passages (au 1er, juste quelques notes lointaines)
    mel = MEL if rnd == 1 else (MEL2 if rnd == 2 else None)
    if mel:
        pos = 0
        for (n_, ln) in mel[i16]:
            d = ln * E8
            tr.add(recorder(n_, d + 0.12), t0 + pos * E8, 0.5, 0.15)
            pos += ln
    elif i16 in (4, 5, 12, 13):
        pos = 0
        for (n_, ln) in MEL[i16]:
            tr.add(recorder(n_ - 12, ln * E8 + 0.12), t0 + pos * E8, 0.28, 0.4)
            pos += ln
    # harpe : petites cascades à la fin des phrases
    if i16 in (7, 15) or (rnd == 2 and i16 % 2 == 1):
        for k, n_ in enumerate([ch[0] + 24, ch[2] + 24, ch[1] + 24 + 12, ch[0] + 36]):
            tr.add(harp(n_, 2.4), t0 + 3 * E8 + k * E8 / 2, 0.22, 0.55)
    # percussions : bodhran dès le 2e passage, tambourin au 3e
    if rnd >= 1:
        tr.add(bodhran(0.5, 1.0), t0, 0.5, 0)
        tr.add(bodhran(0.4, 0.55), t0 + 3 * E8, 0.5, 0)
        if i16 % 4 == 3: tr.add(bodhran(0.3, 0.4), t0 + 5 * E8, 0.5, 0)
    if rnd == 2:
        tr.add(tambourine(0.4), t0 + 3 * E8, 0.18, 0.5)
        if i16 % 2 == 1: tr.add(tambourine(0.3), t0 + 5 * E8, 0.12, 0.5)

finish(tr, 'spawn/music_village', mix=0.32, size=1.15, gain_db=-4)

sj = os.path.join(OUT, 'assets/limitless/sounds.json')
d = json.load(open(sj))
d['spawn.music_village'] = {'sounds': [{'name': 'limitless:spawn/music_village', 'stream': True}]}
json.dump(d, open(sj, 'w'), indent=1)
print('durée', round(bars * BAR, 2), 's')
