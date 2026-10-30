"""Modèles + définitions d'objets pour les objets custom.
On remplace la définition vanilla des matériaux utilisés par un "select" sur le custom_model_data texte :
- l'objet a la bonne chaîne (mise par les plugins) -> notre modèle
- sinon -> le modèle vanilla normal (donc rien ne change pour les objets normaux)
Sans le pack, le client affiche juste l'objet vanilla."""
import json, os, sys, shutil

OUT = sys.argv[1]
A = os.path.join(OUT, 'assets')
MOD = os.path.join(A, 'limitless/models/item/items')
DEF = os.path.join(A, 'minecraft/items')
for d in (MOD, DEF):
    shutil.rmtree(d, ignore_errors=True); os.makedirs(d)

def w(path, obj):
    with open(path, 'w') as f: json.dump(obj, f, indent=1)

HANDHELD = ['lame_citrouille', 'croc_de_givre', 'tranche_mace', 'brise_terre', 'hache_maudite', 'arbalete_spectrale', 'faucille', 'fuga']
FLAT = ['hollow_purple', 'graine', 'egg_rare', 'egg_epique', 'egg_legendaire', 'egg_mythique']
BOWS = ['sonic_bow', 'arc_tempete', 'arc_feux_follets']

for n in HANDHELD:
    w(f'{MOD}/{n}.json', {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'limitless:item/items/{n}'}})
for n in FLAT:
    w(f'{MOD}/{n}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'limitless:item/items/{n}'}})
for n in BOWS:
    for st in ['', '_pulling_0', '_pulling_1', '_pulling_2']:
        w(f'{MOD}/{n}{st}.json', {'parent': 'minecraft:item/bow', 'textures': {'layer0': f'limitless:item/items/{n}{st}'}})

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
    return bow_tree(f'limitless:item/items/{n}') if n in BOWS else m(f'limitless:item/items/{n}')

# matériau vanilla -> (modèle vanilla de secours, objets custom qui l'utilisent)
MATS = {
    'bow': (bow_tree('minecraft:item/bow'), BOWS),
    'diamond_sword': (m('minecraft:item/diamond_sword'), ['lame_citrouille', 'croc_de_givre']),
    'netherite_sword': (m('minecraft:item/netherite_sword'), ['tranche_mace']),
    'netherite_axe': (m('minecraft:item/netherite_axe'), ['brise_terre']),
    'diamond_axe': (m('minecraft:item/diamond_axe'), ['hache_maudite']),
    'netherite_hoe': (m('minecraft:item/netherite_hoe'), ['arbalete_spectrale', 'faucille']),
    'blaze_rod': (m('minecraft:item/blaze_rod'), ['fuga']),
    'amethyst_shard': (m('minecraft:item/amethyst_shard'), ['hollow_purple']),
    'pumpkin_seeds': (m('minecraft:item/pumpkin_seeds'), ['graine']),
}
EGGS = ['egg_rare', 'egg_epique', 'egg_legendaire', 'egg_mythique']
for mat, fb in [('blue_egg', 'minecraft:item/blue_egg'), ('turtle_egg', 'minecraft:item/turtle_egg'),
                ('sniffer_egg', 'minecraft:item/sniffer_egg'), ('dragon_egg', 'minecraft:block/dragon_egg'), ('egg', 'minecraft:item/egg')]:
    MATS[mat] = (m(fb), EGGS)

for mat, (fallback, ids) in MATS.items():
    w(f'{DEF}/{mat}.json', {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': 0,
        'cases': [{'when': n, 'model': custom(n)} for n in ids],
        'fallback': fallback}})
print('modèles', len(os.listdir(MOD)), 'définitions', len(os.listdir(DEF)))
