"""Construit le resource pack (dossier ResourcePack, sans _outils) en un zip reproductible :
mêmes fichiers = même zip = même SHA-1. usage : build_pack.py <dossier> <sortie.zip>"""
import os, sys, zipfile, hashlib
src, out = sys.argv[1], sys.argv[2]
files = []
for root, dirs, names in os.walk(src):
    rel = os.path.relpath(root, src)
    if rel.split(os.sep)[0] in ('_outils', '.git'):
        continue
    for n in names:
        if n.startswith('.'):
            continue
        p = os.path.join(root, n)
        files.append((os.path.relpath(p, src).replace(os.sep, '/'), p))
files.sort()
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for arc, p in files:
        info = zipfile.ZipInfo(arc, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED if arc.endswith(('.ogg', '.png')) else zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(p, 'rb') as f:
            z.writestr(info, f.read())
print(hashlib.sha1(open(out, 'rb').read()).hexdigest())
