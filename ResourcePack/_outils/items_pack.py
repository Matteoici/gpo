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

HANDHELD = ['pioche_3x3', 'pioche_5x5', 'lance_ciel', 'tribaton', 'dague_venin', 'croc_reine', 'faux_tisseuse', 'sceptre_abysse', 'boss_blade', 'lame_citrouille', 'croc_de_givre', 'tranche_mace', 'brise_terre', 'hache_maudite', 'arbalete_spectrale', 'faucille', 'fuga']
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
          'ailes_toile': 'phantom_membrane', 'oeil_rouge_abysse': 'ender_eye', 'projet_capricorne': 'echo_shard', 'restriction_celeste': 'iron_chain', 'boogie_bombe': 'firework_star'}
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

# ---- Adam Smasher : colosse cybernétique (design original), un objet par morceau du corps
# Les morceaux "posés" (bassin, torse, tête, épaulières, pieds) sont centrés sur (8, 8, 8), face avant = sud (+Z).
# Les membres (bras, avant-bras, canon, cuisses, tibias) vont de y=0 (articulation du haut) à y=16 (bout).
SM = {'p': T + 'smasher_plate', 'd': T + 'smasher_dark', 'r': T + 'smasher_red', 'j': T + 'smasher_joint',
      'b': T + 'smasher_barrel', 'm': T + 'smasher_muzzle', 'v': T + 'smasher_visor', 'c': T + 'smasher_core'}
def smodel(name, tex, elems):
    t = {k: SM[k] for k in tex}; t['particle'] = SM['p']
    w(f'{MOD}/{name}.json', {'textures': t, 'elements': elems})
def glowbox(a, b, face, tex):
    return box(a, b, {face: F(tex)}, light_emission=15, shade=False)
smodel('smasher_torso', 'pdrcbjm', [
    box((0, 7, 1), (16, 16, 15), allf('#p')),                 # poitrine
    box((3, 0, 3), (13, 7, 13), allf('#j')),                  # taille (vérins)
    box((2, 1, 2), (14, 3, 14), allf('#d')),
    box((1, 9, 14.6), (15, 15.6, 15.6), allf('#d')),          # plastron
    box((6, 10.5, 15.6), (10, 14, 16.2), allf('#d')),
    glowbox((6.5, 11, 16.25), (9.5, 13.5, 16.25), 'south', '#c'),  # réacteur
    box((0.6, 8, 14.8), (4, 9, 15.8), allf('#r')),            # liserés rouges
    box((12, 8, 14.8), (15.4, 9, 15.8), allf('#r')),
    box((-0.4, 12, 3), (0.6, 15, 13), allf('#r')),
    box((15.4, 12, 3), (16.4, 15, 13), allf('#r')),
    box((2, 8, -0.5), (14, 15, 1), allf('#d')),               # dos
    box((3, 11, -3), (6, 19, 0), allf('#b')),                 # cheminées
    box((10, 11, -3), (13, 19, 0), allf('#b')),
    box((3.5, 19, -2.5), (5.5, 19.5, -0.5), allf('#m')),
    box((10.5, 19, -2.5), (12.5, 19.5, -0.5), allf('#m')),
    box((5, 15.5, 4), (11, 17, 12), allf('#j'))])              # col
smodel('smasher_head', 'pdrv', [
    box((4, 3, 4), (12, 12, 12.5), allf('#p')),               # casque
    box((4.5, 3, 9), (11.5, 6.5, 13.5), allf('#d')),          # mâchoire blindée
    box((3.6, 7.2, 11.8), (12.4, 9.6, 13), allf('#d')),       # cadre de visière
    glowbox((4, 7.6, 13.05), (12, 9.2, 13.05), 'south', '#v'),
    box((7.2, 12, 5), (8.8, 13.4, 12), allf('#r')),           # crête
    box((3, 6, 6), (4, 10, 9), allf('#d')),                   # oreillettes
    box((12, 6, 6), (13, 10, 9), allf('#d')),
    box((12.3, 9, 6.5), (12.9, 14, 7.1), allf('#r'))])        # antenne
smodel('smasher_pelvis', 'pdr', [
    box((1, 3, 2), (15, 13, 14), allf('#d')),
    box((0.6, 9, 1.6), (15.4, 11, 14.4), allf('#r')),         # ceinture
    box((5, 1, 12), (11, 9, 14.6), allf('#p')),               # plaque avant
    box((1.5, 2, 4), (4, 8, 12), allf('#p')),                 # hanches
    box((12, 2, 4), (14.5, 8, 12), allf('#p'))])
smodel('smasher_shoulder', 'pdr', [
    box((0, 3, 0), (16, 13, 16), allf('#p')),
    box((-0.4, 3, -0.4), (16.4, 5, 16.4), allf('#r')),
    box((2, 13, 2), (14, 14.5, 14), allf('#d')),
    box((6, 14.5, 4), (10, 16, 12), allf('#d'))])
smodel('smasher_arm', 'pjrd', [
    box((2, 0, 2), (14, 16, 14), allf('#j')),
    box((1, 2, 1), (15, 12, 15), allf('#p')),
    box((0.8, 6, 0.8), (15.2, 7.2, 15.2), allf('#r')),
    box((1.5, 13, 1.5), (14.5, 16, 14.5), allf('#d'))])       # coude
smodel('smasher_forearm', 'pdj', [
    box((2.5, 0, 2.5), (13.5, 10, 13.5), allf('#p')),
    box((1.5, 2, 1.5), (14.5, 8, 14.5), allf('#d')),
    box((1, 10, 1), (15, 16, 15), allf('#j')),                # poing
    box((0.5, 12.5, 0.5), (15.5, 15.5, 15.5), allf('#d'))])
smodel('smasher_cannon', 'pdbmr', [
    box((0.5, 0, 0.5), (15.5, 7, 15.5), allf('#p')),          # boîtier
    box((0, 2, 0), (16, 4, 16), allf('#r')),
    box((3, 7, 3), (13, 16, 13), allf('#b')),                 # canon
    box((2, 13.5, 2), (14, 16, 14), allf('#d')),
    box((3, 16.02, 3), (13, 16.02, 13), {'up': F('#m')}),
    box((13, 1, 6), (16.5, 9, 10), allf('#d'))])              # chargeur
smodel('smasher_thigh', 'pjrd', [
    box((2, 0, 2), (14, 16, 14), allf('#j')),
    box((1, 1, 1), (15, 12, 15), allf('#p')),
    box((0.7, 4, 0.7), (15.3, 5.2, 15.3), allf('#r')),
    box((1.5, 12.5, 1.5), (14.5, 16, 14.5), allf('#d'))])     # genou
smodel('smasher_shin', 'pdj', [
    box((3, 0, 3), (13, 16, 13), allf('#j')),
    box((1.5, 1, 1.5), (14.5, 12, 14.5), allf('#p')),
    box((2, 3, 1), (14, 10, 2), allf('#d')),
    box((2, 3, 14), (14, 10, 15), allf('#d'))])
smodel('smasher_foot', 'pdr', [
    box((1, 3, 0), (15, 13, 16), allf('#d')),
    box((0.5, 3, 11), (15.5, 10, 16.5), allf('#p')),          # pointe
    box((2, 13, 2), (14, 14, 12), allf('#r'))])
SMASHER3D = ['smasher_torso', 'smasher_head', 'smasher_pelvis', 'smasher_shoulder', 'smasher_arm', 'smasher_forearm',
             'smasher_cannon', 'smasher_thigh', 'smasher_shin', 'smasher_foot']

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
    if n.startswith(('pass_', 'ui_', 'job_')): return m(f'limitless:item/pass/{n}')
    if n in ('boss_pumpkin', 'boss_crown') or n in SPIDER3D or n in SMASHER3D: return m(f'limitless:item/items/{n}')
    return bow_tree(f'limitless:item/items/{n}') if n in BOWS else m(f'limitless:item/items/{n}')

# matériau vanilla -> objets custom qui l'utilisent ; le repli = la définition vanilla 26.2 exacte
USE = {}
def use(mat, ids):
    USE.setdefault(mat, []).extend(ids)
use('bow', BOWS)
use('diamond_sword', ['lame_citrouille', 'croc_de_givre'])
use('netherite_sword', ['tranche_mace', 'boss_blade', 'croc_reine', 'lance_ciel'])
use('iron_sword', ['dague_venin'])
use('fermented_spider_eye', ['oeil_maudit'])
use('cobweb', ['cocon'])
use('black_concrete', ['spider_head', 'spider_abdomen', 'spider_leg_upper', 'spider_leg_lower'] + SMASHER3D)
use('sea_lantern', ['spider_hide'])
use('white_wool', ['spider_eggsac'])
use('jack_o_lantern', ['boss_pumpkin'])
use('golden_helmet', ['boss_crown'])
use('netherite_axe', ['brise_terre'])
use('diamond_axe', ['hache_maudite'])
use('netherite_hoe', ['arbalete_spectrale', 'faucille', 'faux_tisseuse'])
use('breeze_rod', ['sceptre_abysse'])
use('netherite_shovel', ['tribaton'])
use('diamond_pickaxe', ['pioche_3x3'])
use('netherite_pickaxe', ['pioche_5x5'])
# menu du passe de saison (pass_gui.py)
use('paper', ['pass_node_on', 'pass_node_cur', 'pass_node_off', 'pass_check', 'pass_prev', 'pass_next', 'pass_claim', 'pass_premium', 'pass_premium_on', 'pass_week', 'pass_season', 'pass_blank', 'ui_level'])
use('iron_pickaxe', ['job_mineur'])
use('iron_axe', ['job_bucheron'])
use('iron_hoe', ['job_fermier'])
use('iron_sword', ['job_chasseur'])
use('fishing_rod', ['job_pecheur'])
OUTFIT_ICONS = {'leather_chestplate': ['toji_haut', 'gojo_haut', 'sukuna_haut'], 'leather_leggings': ['toji_pantalon', 'gojo_pantalon', 'sukuna_pantalon'],
                'leather_boots': ['toji_bottes', 'gojo_bottes', 'sukuna_bottes'], 'leather_helmet': ['gojo_bandeau']}
for _m, _ids in OUTFIT_ICONS.items():
    use(_m, _ids)
    for _n in _ids:
        w(f'{MOD}/{_n}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'limitless:item/items/{_n}'}})
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

# ---- vitres de remplissage des menus : dans un menu (gui) et avec un nom (" "), elles deviennent un morceau de parchemin
PATCH = {'type': 'minecraft:model', 'model': 'limitless:item/pass/ui_patch'}
for pane in ('gray_stained_glass_pane', 'black_stained_glass_pane', 'light_gray_stained_glass_pane'):
    v = json.load(open(os.path.join(VDEF, pane + '.json')))
    van = v['model']
    in_gui = {'type': 'minecraft:select', 'property': 'minecraft:display_context', 'cases': [{'when': 'gui', 'model': PATCH}], 'fallback': van}
    v['model'] = {'type': 'minecraft:condition', 'property': 'minecraft:has_component', 'component': 'minecraft:custom_name',
                  'on_true': in_gui,
                  'on_false': {'type': 'minecraft:condition', 'property': 'minecraft:has_component', 'component': 'minecraft:tooltip_display',
                               'on_true': in_gui, 'on_false': van}}
    w(f'{DEF}/{pane}.json', v)
print('vitres de menu ok')
