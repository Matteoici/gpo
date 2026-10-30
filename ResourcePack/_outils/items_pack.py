"""Modèles + définitions d'objets pour les objets custom.
On remplace la définition vanilla des matériaux utilisés par un "select" sur le custom_model_data texte :
- l'objet a la bonne chaîne (mise par les plugins) -> notre modèle
- sinon -> le modèle vanilla normal (donc rien ne change pour les objets normaux)
Sans le pack, le client affiche juste l'objet vanilla."""
import json, os, sys, shutil

OUT = sys.argv[1]
VDEF = sys.argv[2]   # définitions vanilla 26.2 (assets/minecraft/items)
A = os.path.join(OUT, 'assets')
MOD = os.path.join(A, 'limitless/models/item/items')
DEF = os.path.join(A, 'minecraft/items')
for d in (MOD, DEF):
    shutil.rmtree(d, ignore_errors=True); os.makedirs(d)

def w(path, obj):
    with open(path, 'w') as f: json.dump(obj, f, indent=1)

HANDHELD = ['boss_blade', 'lame_citrouille', 'croc_de_givre', 'tranche_mace', 'brise_terre', 'hache_maudite', 'arbalete_spectrale', 'faucille', 'fuga']
RELICS = {'trainee_flammes': 'blaze_powder', 'amulette_recolte': 'golden_carrot', 'lanterne_mineure': 'lantern',
          'couronne': 'golden_helmet', 'lanterne_eternelle': 'soul_lantern', 'bottes_recolte': 'golden_boots',
          'sac_recolte': 'bundle', 'braises_ardentes': 'magma_cream', 'crane_maudit': 'wither_skeleton_skull',
          'cornes_demon': 'goat_horn', 'orbe_infernal': 'magma_block', 'ailes_demon': 'phantom_membrane',
          'oeil_jugement': 'ender_eye', 'couronne_enfers': 'netherite_helmet', 'cape_nuit': 'black_banner',
          'couronne_roi_eternel': 'golden_helmet', 'ailes_eclipse': 'elytra'}
FLAT = ['hollow_purple', 'graine', 'egg_rare', 'egg_epique', 'egg_legendaire', 'egg_mythique'] + ['relic_' + r for r in RELICS]
BOWS = ['sonic_bow', 'arc_tempete', 'arc_feux_follets']

for n in HANDHELD:
    w(f'{MOD}/{n}.json', {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'limitless:item/items/{n}'}})
for n in FLAT:
    w(f'{MOD}/{n}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'limitless:item/items/{n}'}})
for n in BOWS:
    for st in ['', '_pulling_0', '_pulling_1', '_pulling_2']:
        w(f'{MOD}/{n}{st}.json', {'parent': 'minecraft:item/bow', 'textures': {'layer0': f'limitless:item/items/{n}{st}'}})

# ---- modèles 3D du boss
def F(tex, uv=(0, 0, 16, 16), **kw):
    d = {'uv': list(uv), 'texture': tex}; d.update(kw); return d
T = 'limitless:item/items/'
w(f'{MOD}/boss_pumpkin.json', {
    'parent': 'minecraft:block/block',
    'textures': {'side': T + 'boss_pumpkin_side', 'top': T + 'boss_pumpkin_top', 'face': T + 'boss_pumpkin_face', 'particle': T + 'boss_pumpkin_side'},
    'elements': [
        {'from': [0, 0, 0], 'to': [16, 16, 16], 'faces': {
            'north': F('#side', cullface=None) if False else F('#side'), 'south': F('#side'), 'east': F('#side'), 'west': F('#side'),
            'up': F('#top'), 'down': F('#top')}},
        {'from': [0, 0, -0.02], 'to': [16, 16, -0.02], 'light_emission': 15, 'shade': False, 'faces': {'north': F('#face')}},
        # petite tige
        {'from': [7, 16, 7], 'to': [9, 19, 9], 'faces': {k: F('#top', (14, 14, 16, 16)) for k in ['north', 'south', 'east', 'west', 'up']}}]})
cr = []
for (a, b) in [((2, 0, 2), (14, 5, 3)), ((2, 0, 13), (14, 5, 14)), ((2, 0, 3), (3, 5, 13)), ((13, 0, 3), (14, 5, 13))]:
    cr.append({'from': list(a), 'to': list(b), 'faces': {k: F('#0') for k in ['north', 'south', 'east', 'west', 'up', 'down']}})
for (x, z) in [(2, 2), (7.25, 2), (12.5, 2), (2, 12.5), (7.25, 12.5), (12.5, 12.5), (2, 7.25), (12.5, 7.25)]:
    h = 9 if (x == 7.25 or z == 7.25) else 7.5
    cr.append({'from': [x, 5, z], 'to': [x + 1.5, h, z + 1.5], 'faces': {k: F('#0', (6, 0, 8, 4)) for k in ['north', 'south', 'east', 'west', 'up']}})
w(f'{MOD}/boss_crown.json', {'textures': {'0': T + 'boss_crown', 'particle': T + 'boss_crown'}, 'elements': cr,
                             'display': {'head': {'translation': [0, 10, 0]}}})

def m(model):
    return {'type': 'minecraft:model', 'model': model}

def bow_tree(prefix):
    return {'type': 'minecraft:condition', 'property': 'minecraft:using_item',
            'on_false': m(prefix),
            'on_true': {'type': 'minecraft:range_dispatch', 'property': 'minecraft:use_duration', 'scale': 0.05,
                        'entries': [{'threshold': 0.65, 'model': m(prefix + '_pulling_1')},
                                    {'threshold': 0.9, 'model': m(prefix + '_pulling_2')}],
                        'fallback': m(prefix + '_pulling_0')}}

def custom(n):
    if n in ('boss_pumpkin', 'boss_crown'): return m(f'limitless:item/items/{n}')
    return bow_tree(f'limitless:item/items/{n}') if n in BOWS else m(f'limitless:item/items/{n}')

# matériau vanilla -> objets custom qui l'utilisent ; le repli = la définition vanilla 26.2 exacte
USE = {}
def use(mat, ids):
    USE.setdefault(mat, []).extend(ids)
use('bow', BOWS)
use('diamond_sword', ['lame_citrouille', 'croc_de_givre'])
use('netherite_sword', ['tranche_mace', 'boss_blade'])
use('jack_o_lantern', ['boss_pumpkin'])
use('golden_helmet', ['boss_crown'])
use('netherite_axe', ['brise_terre'])
use('diamond_axe', ['hache_maudite'])
use('netherite_hoe', ['arbalete_spectrale', 'faucille'])
use('blaze_rod', ['fuga'])
use('amethyst_shard', ['hollow_purple'])
use('pumpkin_seeds', ['graine'])
for mat in ['blue_egg', 'turtle_egg', 'sniffer_egg', 'dragon_egg', 'egg']:
    use(mat, ['egg_rare', 'egg_epique', 'egg_legendaire', 'egg_mythique'])
for r, mat in RELICS.items():
    use(mat, ['relic_' + x for x, mm in RELICS.items() if mm == mat] if mat not in USE or ('relic_' + r) not in USE[mat] else [])
for mat, extra in globals().get('EXTRA_USE', {}).items():
    use(mat, extra)

for mat, ids in USE.items():
    ids = list(dict.fromkeys(ids))
    v = json.load(open(os.path.join(VDEF, mat + '.json')))
    out = dict(v)
    out['model'] = {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': 0,
                    'cases': [{'when': n, 'model': custom(n)} for n in ids], 'fallback': v['model']}
    w(f'{DEF}/{mat}.json', out)
print('modèles', len(os.listdir(MOD)), 'définitions', len(os.listdir(DEF)))
