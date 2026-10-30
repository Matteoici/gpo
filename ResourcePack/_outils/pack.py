import json, os, sys
OUT=sys.argv[1]
NS='limitless'
M=os.path.join(OUT,'assets',NS,'models','fx'); I=os.path.join(OUT,'assets',NS,'items','fx')
os.makedirs(M,exist_ok=True); os.makedirs(I,exist_ok=True)
def tex(t): return {"0":f"{NS}:item/fx/{t}","particle":f"{NS}:item/fx/{t}"}
def face(rot=0):
    f={"uv":[0,0,16,16],"texture":"#0"}
    if rot: f["rotation"]=rot
    return f
def quad(t):   # carré face caméra (billboard)
    return {"textures":tex(t),"elements":[{"from":[0,0,8],"to":[16,16,8],"shade":False,"faces":{"north":face(),"south":face()}}]}
def flat(t):   # posé à plat (sol)
    return {"textures":tex(t),"elements":[{"from":[0,8,0],"to":[16,8,16],"shade":False,"faces":{"up":face(),"down":face()}}]}
def ribbon(t): # deux plans croisés le long de Z (traînée)
    return {"textures":tex(t),"elements":[
        {"from":[8,0,0],"to":[8,16,16],"shade":False,"faces":{"east":face(),"west":face()}},
        {"from":[0,8,0],"to":[16,8,16],"shade":False,"faces":{"up":face(90),"down":face(90)}}]}
def column(t): # prisme à 8 faces (deux boîtes, une tournée de 45°), sans dessus ni dessous
    sides={"north":face(),"south":face(),"east":face(),"west":face()}
    return {"textures":tex(t),"elements":[
        {"from":[0,0,0],"to":[16,16,16],"shade":False,"faces":sides},
        {"from":[0,0,0],"to":[16,16,16],"shade":False,"rotation":{"angle":45,"axis":"y","origin":[8,8,8]},"faces":sides}]}
models={}
quads=['glow','glow_white','aura','star','star_dark','star_red','flare','flare_red','core','basefire','spark']
quads+=['burst_%d'%i for i in range(16)]+['boom_%d'%i for i in range(16)]+['smoke_%d'%i for i in range(12)]+['dust_%d'%i for i in range(12)]
for t in quads: models[t]=quad(t)
for t in ['circle','circle2','ring','shock']+['splash_%d'%i for i in range(12)]: models[t]=flat(t)
models['shock_up']=quad('shock')
models['ribbon']=ribbon('ribbon'); models['ribbon_red']=ribbon('ribbon_red')
for t in ['column','column_core','column_glow']: models[t]=column(t)
for name,m in models.items():
    json.dump(m,open(os.path.join(M,name+'.json'),'w'))
    json.dump({"model":{"type":"minecraft:model","model":f"{NS}:fx/{name}"}},open(os.path.join(I,name+'.json'),'w'))
# sons
S=os.path.join(OUT,'assets',NS)
json.dump({
  "fuga.charge":{"sounds":[{"name":f"{NS}:fuga/charge","stream":True}]},
  "fuga.shot":{"sounds":[{"name":f"{NS}:fuga/shot","stream":True}]},
  "fuga.impact":{"sounds":[{"name":f"{NS}:fuga/impact","stream":True}]}
}, open(os.path.join(S,'sounds.json'),'w'), indent=1)
# police : flash plein écran
os.makedirs(os.path.join(S,'font'),exist_ok=True)
json.dump({"providers":[
  {"type":"bitmap","file":f"{NS}:font/white.png","ascent":140,"height":256,"chars":[""]},
  {"type":"bitmap","file":f"{NS}:font/orange.png","ascent":140,"height":256,"chars":[""]}
]}, open(os.path.join(S,'font','fx.json'),'w'))
json.dump({"pack":{"description":"Limitless : effets et sons du serveur","pack_format":88,"min_format":88,"max_format":99}},open(os.path.join(OUT,'pack.mcmeta'),'w'),indent=1)
print(len(models),'modeles')
