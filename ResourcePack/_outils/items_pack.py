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

HANDHELD = ['dague_venin', 'croc_reine', 'faux_tisseuse', 'sceptre_abysse', 'boss_blade', 'lame_citrouille', 'croc_de_givre', 'tranche_mace', 'brise_terre', 'hache_maudite', 'arbalete_spectrale', 'faucille', 'fuga']
RELICS = {'trainee_flammes': 'blaze_powder', 'amulette_recolte': 'golden_carrot', 'lanterne_mineure': 'lantern',
          'couronne': 'golden_helmet', 'lanterne_eternelle': 'soul_lantern', 'bottes_recolte': 'golden_boots',
          'sac_recolte': 'bundle', 'braises_ardentes': 'magma_cream', 'crane_maudit': 'wither_skeleton_skull',
          'cornes_demon': 'goat_horn', 'orbe_infernal': 'magma_block', 'ailes_demon': 'phantom_membrane',
          'oeil_jugement': 'ender_eye', 'couronne_enfers': 'netherite_helmet', 'cape_nuit': 'black_banner',
          'couronne_roi_eternel': 'golden_helmet', 'ailes_eclipse': 'elytra',
          'fil_soie': 'string', 'oeil_araignee': 'spider_eye', 'crochets_venin': 'prismarine_shard', 'cocon_ancien': 'cobweb',
          'pattes_reine': 'black_candle', 'cape_toile': 'blue_banner', 'couronne_reine': 'netherite_helmet', 'coeur_abysse': 'heart_of_the_sea',
          'black_flash': 'redstone', 'black_flash_consecutif': 'nether_star',
          'soie_lunaire': 'white_dye', 'venin_cristallise': 'lime_dye', 'masque_mille_yeux': 'black_dye', 'fuseau_tisseuse': 'lead',
          'ailes_toile': 'phantom_membrane', 'oeil_rouge_abysse': 'ender_eye'}
FLAT = ['oeil_maudit', 'cocon', 'hollow_purple', 'graine', 'egg_rare', 'egg_epique', 'egg_legendaire', 'egg_mythique'] + ['relic_' + r for r in RELICS]
BOWS = ['sonic_bow', 'arc_tempete', 'arc_feux_follets', 'arc_toiles']

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

# ---- la Reine Araignée : corps animé (posé sur des objets vanilla : béton noir, lanterne marine, laine)
def box(a, b, faces, **kw):
    e = {'from': list(a), 'to': list(b), 'faces': faces}; e.update(kw); return e
def allf(tex, uv=(0, 0, 16, 16), skip=()):
    return {k: F(tex, uv) for k in ['north', 'south', 'east', 'west', 'up', 'down'] if k not in skip}
w(f'{MOD}/spider_head.json', {
    'textures': {'c': T + 'spider_chitin', 'e': T + 'spider_eyes', 'f': T + 'spider_fang', 'particle': T + 'spider_chitin'},
    'elements': [
        box((1, 1, 0), (15, 13, 13), allf('#c')),
        box((2.5, 2, 12), (13.5, 11.5, 16), allf('#c')),
        box((2.5, 2, 16.03), (13.5, 11.5, 16.03), {'south': F('#e')}, light_emission=15, shade=False),
        box((4, -3, 13.5), (6.5, 3, 16), allf('#f')),
        box((9.5, -3, 13.5), (12, 3, 16), allf('#f')),
        box((3, 12.5, 2), (13, 14, 11), allf('#c'))]})
w(f'{MOD}/spider_abdomen.json', {
    'textures': {'c': T + 'spider_chitin', 'm': T + 'spider_mark', 'particle': T + 'spider_chitin'},
    'elements': [
        box((1, 1, 0), (15, 15, 16), allf('#c')),
        box((0, 2.5, 1.5), (16, 13.5, 14.5), allf('#c')),
        box((2.5, 0, 2.5), (13.5, 16, 13.5), allf('#c')),
        box((3, 16.04, 2), (13, 16.04, 14), {'up': F('#m')}, light_emission=15, shade=False),
        box((6, 5, -1.5), (10, 9, 0), allf('#c'))]})
w(f'{MOD}/spider_leg_upper.json', {
    'textures': {'l': T + 'spider_leg', 'j': T + 'spider_joint', 'particle': T + 'spider_leg'},
    'elements': [
        box((1, 0, 1), (15, 14, 15), allf('#l')),
        box((0.5, 14, 0.5), (15.5, 16, 15.5), allf('#j', (0, 0, 16, 4)), light_emission=12)]})
w(f'{MOD}/spider_leg_lower.json', {
    'textures': {'l': T + 'spider_leg', 'f': T + 'spider_fang', 'particle': T + 'spider_leg'},
    'elements': [
        box((1.5, 0, 1.5), (14.5, 9, 14.5), allf('#l')),
        box((3.5, 9, 3.5), (12.5, 14, 12.5), allf('#l')),
        box((5.5, 14, 5.5), (10.5, 16.5, 10.5), allf('#f'))]})
w(f'{MOD}/spider_eggsac.json', {
    'textures': {'s': T + 'spider_silk', 'particle': T + 'spider_silk'},
    'elements': [
        box((2, 1, 2), (14, 15, 14), allf('#s')),
        box((1, 3, 3), (15, 13, 13), allf('#s')),
        box((3, 0, 3), (13, 16, 13), allf('#s')),
        box((7, 16, 7), (9, 19, 9), allf('#s'))]})
w(f'{MOD}/spider_hide.json', {'textures': {'particle': T + 'spider_chitin'}, 'elements': []})
SPIDER3D = ['spider_head', 'spider_abdomen', 'spider_leg_upper', 'spider_leg_lower', 'spider_eggsac', 'spider_hide']

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
    if n in ('boss_pumpkin', 'boss_crown') or n in SPIDER3D: return m(f'limitless:item/items/{n}')
    return bow_tree(f'limitless:item/items/{n}') if n in BOWS else m(f'limitless:item/items/{n}')

# matériau vanilla -> objets custom qui l'utilisent ; le repli = la définition vanilla 26.2 exacte
USE = {}
def use(mat, ids):
    USE.setdefault(mat, []).extend(ids)
use('bow', BOWS)
use('diamond_sword', ['lame_citrouille', 'croc_de_givre'])
use('netherite_sword', ['tranche_mace', 'boss_blade', 'croc_reine'])
use('iron_sword', ['dague_venin'])
use('fermented_spider_eye', ['oeil_maudit'])
use('cobweb', ['cocon'])
use('black_concrete', ['spider_head', 'spider_abdomen', 'spider_leg_upper', 'spider_leg_lower'])
use('sea_lantern', ['spider_hide'])
use('white_wool', ['spider_eggsac'])
use('jack_o_lantern', ['boss_pumpkin'])
use('golden_helmet', ['boss_crown'])
use('netherite_axe', ['brise_terre'])
use('diamond_axe', ['hache_maudite'])
use('netherite_hoe', ['arbalete_spectrale', 'faucille', 'faux_tisseuse'])
use('breeze_rod', ['sceptre_abysse'])
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
