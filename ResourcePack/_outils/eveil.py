"""Éveil de la Restriction Céleste : musique originale de 135,5 s (même durée que le mode adrénaline).
Ré mineur, 140 bpm : battements de cœur, taikos, ostinato de violoncelles, chœur, cuivres synthétiques, montée finale.
Le fichier peut être remplacé par n'importe quelle musique de la même durée (assets/limitless/sounds/toji/eveil.ogg).
usage : eveil.py <racine du pack>"""
import os, sys, json
HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, 'music.py')).read().split('# ================================================================ « Le Bal')[0])

DUR = 135.5
bpm = 140; beat = 60 / bpm; bar = beat * 4
tr = Track(DUR)

def taiko(dur=0.8, f=70, g=1.0):
    n = int(dur * SR); t = np.arange(n) / SR
    x = np.sin(2 * np.pi * np.cumsum(f * (1 + 0.5 * np.exp(-t * 30))) / SR) * np.exp(-t * 5)
    x += lfilter([0.2], [1, -0.8], rng.normal(0, 1, n)) * np.exp(-t * 40) * 0.6
    return x * g

def brass(m, dur):
    f = mf(m); n = int(dur * SR); t = np.arange(n) / SR
    x = sum((1 / k) * np.sin(2 * np.pi * f * k * t) for k in range(1, 10))
    x = lfilter([0.15], [1, -0.85], x)
    return x * env(n, 0.05, 0.2, 0.85, 0.3, 0.8) / 2

def hat(dur=0.05):
    n = int(dur * SR); x = rng.normal(0, 1, n); x = x - lfilter([0.5], [1, -0.5], x)
    return x * np.exp(-np.arange(n) / SR * 80) * 0.3

prog = [38, 38, 34, 36, 38, 38, 41, 40]      # ré, ré, sib, do, ré, ré, fa, mi
osti = [0, 7, 12, 7, 10, 7, 12, 15]
nbars = int(DUR / bar)
for b in range(nbars):
    t0 = b * bar
    root = prog[b % 8]
    sec = 0 if b < 4 else (1 if b < 20 else (2 if b < 52 else (3 if b < 68 else 4)))
    # battements de cœur (intro et pont)
    if sec == 0 or sec == 3:
        tr.add(heartbeat(1.0), t0, 0.9, 0); tr.add(heartbeat(1.0), t0 + 2 * beat, 0.7, 0)
    # ostinato de violoncelles en croches
    if sec >= 1:
        for i in range(8):
            tr.add(cello(root + 12 + osti[i], beat / 2 + 0.05, a=0.01, r=0.05), t0 + i * beat / 2, 0.32 if i % 4 == 0 else 0.22, -0.3)
    # taikos
    if sec >= 1 and sec != 3:
        for i in (0, 1.5, 2, 3, 3.5):
            tr.add(taiko(0.8, 62 if i in (0, 2) else 90, 1.0 if i in (0, 2) else 0.6), t0 + i * beat, 0.8, 0)
    if sec in (2, 4):
        for i in range(8): tr.add(hat(), t0 + i * beat / 2 + beat / 4, 0.5, 0.4)
    # chœur et cuivres
    if sec in (2, 4) and b % 2 == 0:
        for k, iv in enumerate((0, 3, 7)):
            tr.add(choir(root + 24 + iv, bar * 2, a=0.3, r=0.6), t0, 0.22, -0.4 + 0.4 * k)
    if sec == 4 or (sec == 2 and b % 4 == 3):
        for k, iv in enumerate((0, 7, 12)):
            tr.add(brass(root + 24 + iv, beat * 1.5), t0 + 2 * beat, 0.35, -0.2 + 0.2 * k)
    # montée (cordes qui grimpent) juste avant la fin
    if sec == 3:
        tr.add(cello(root + 24 + (b - 52) % 12, bar + 0.3, a=bar * 0.8, r=0.2), t0, 0.35, 0.3)
# coup final pile à la fin
end = DUR - 1.6
tr.add(taiko(1.6, 48, 1.6), end, 1.0, 0)
for iv in (0, 7, 12, 15):
    tr.add(brass(38 + 24 + iv, 1.4), end, 0.4, 0)
finish(tr, 'toji/eveil', mix=0.25, size=1.1, gain_db=-3)

sj = os.path.join(OUT, 'assets/limitless/sounds.json')
d = json.load(open(sj))
d['toji.eveil'] = {'sounds': [{'name': 'limitless:toji/eveil', 'stream': True}]}
json.dump(d, open(sj, 'w'), indent=1)
print('eveil ok', DUR)
