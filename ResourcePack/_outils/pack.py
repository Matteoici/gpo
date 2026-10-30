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
# Violet Imaginaire
for t in ['orb_blue','orb_red','orb_purple','glow_blue','glow_red','glow_purple','beam_red','star_pink']+['bolt_%d'%i for i in range(4)]+['pburst_%d'%i for i in range(16)]+['shard_%d'%i for i in range(4)]:
    models[t]=quad(t)
models['shock_purple']=flat('shock_purple'); models['shock_purple_up']=quad('shock_purple')
for t in ['spike_purple','ring_pink']+['ink_%d'%i for i in range(4)]: models[t]=quad(t)
models['ribbon_blue']=ribbon('ribbon_blue'); models['ribbon_navy']=ribbon('ribbon_navy')
# ---- effets teintables : couche teintée (tintindex 0) + cœur blanc devant
def tface(rot=0, tint=True, tx='#0'):
    f={"uv":[0,0,16,16],"texture":tx}
    if tint: f["tintindex"]=0
    if rot: f["rotation"]=rot
    return f
def ttex(t): return {"0":f"{NS}:item/fx/{t}","1":f"{NS}:item/fx/{t}_c","particle":f"{NS}:item/fx/{t}"}
def tquad(t, core=True):
    el=[{"from":[0,0,8],"to":[16,16,8],"shade":False,"faces":{"north":tface(),"south":tface()}}]
    if core: el.append({"from":[0,0,8.05],"to":[16,16,8.05],"shade":False,"faces":{"north":tface(tint=False,tx='#1'),"south":tface(tint=False,tx='#1')}})
    return {"textures":ttex(t) if core else {"0":f"{NS}:item/fx/{t}","particle":f"{NS}:item/fx/{t}"},"elements":el}
def tflat(t):
    return {"textures":ttex(t),"elements":[
        {"from":[0,8,0],"to":[16,8,16],"shade":False,"faces":{"up":tface(),"down":tface()}},
        {"from":[0,8.05,0],"to":[16,8.05,16],"shade":False,"faces":{"up":tface(tint=False,tx='#1'),"down":tface(tint=False,tx='#1')}}]}
def tcross(t):  # deux plans croisés le long de Y (éclairs, rayons) : on les oriente dans la direction voulue
    return {"textures":ttex(t),"elements":[
        {"from":[8,0,0],"to":[8,16,16],"shade":False,"faces":{"east":tface(),"west":tface()}},
        {"from":[0,0,8],"to":[16,16,8],"shade":False,"faces":{"north":tface(),"south":tface()}},
        {"from":[8.05,0,0],"to":[8.05,16,16],"shade":False,"faces":{"east":tface(0,False,'#1'),"west":tface(0,False,'#1')}},
        {"from":[0,0,8.05],"to":[16,16,8.05],"shade":False,"faces":{"north":tface(tint=False,tx='#1'),"south":tface(tint=False,tx='#1')}}]}
tinted={}
for t in ['w_glow','w_ring','w_shock','w_spark','w_slash','w_spike']+['w_burst_%d'%i for i in range(12)]: tinted[t]=tquad(t)
for t in ['w_puff_%d'%i for i in range(8)]: tinted[t]=tquad(t, core=False)
for t in ['w_rune','w_crack']: tinted[t+'_flat']=tflat(t)
tinted['w_ring_flat']=tflat('w_ring'); tinted['w_shock_flat']=tflat('w_shock'); tinted['w_slash_flat']=tflat('w_slash')
for t in ['w_beam','w_spike']+['w_bolt_%d'%i for i in range(4)]: tinted[t+'_x']=tcross(t)
tint=[{"type":"minecraft:custom_model_data","index":0,"default":-1}]
for name,m in tinted.items():
    json.dump(m,open(os.path.join(M,name+'.json'),'w'))
    json.dump({"model":{"type":"minecraft:model","model":f"{NS}:fx/{name}","tints":tint}},open(os.path.join(I,name+'.json'),'w'))
for name,m in models.items():
    json.dump(m,open(os.path.join(M,name+'.json'),'w'))
    json.dump({"model":{"type":"minecraft:model","model":f"{NS}:fx/{name}"}},open(os.path.join(I,name+'.json'),'w'))
# sons
S=os.path.join(OUT,'assets',NS)
json.dump({
  "fuga.charge":{"sounds":[{"name":f"{NS}:fuga/charge","stream":True}]},
  "fuga.shot":{"sounds":[{"name":f"{NS}:fuga/shot","stream":True}]},
  "fuga.impact":{"sounds":[{"name":f"{NS}:fuga/impact","stream":True}]},
  "hollow.charge":{"sounds":[{"name":f"{NS}:hollow/charge","stream":True}]},
  "hollow.burst":{"sounds":[{"name":f"{NS}:hollow/burst","stream":True}]}
}, open(os.path.join(S,'sounds.json'),'w'), indent=1)
# police : flash plein écran
os.makedirs(os.path.join(S,'font'),exist_ok=True)
prov=[{"type":"bitmap","file":f"{NS}:font/white.png","ascent":140,"height":256,"chars":["\ue001"]},
      {"type":"bitmap","file":f"{NS}:font/orange.png","ascent":140,"height":256,"chars":["\ue002"]}]
prov+=[{"type":"bitmap","file":f"{NS}:font/whiteout_%d.png"%i,"ascent":140,"height":256,"chars":[chr(0xe010+i)]} for i in range(8)]
json.dump({"providers":prov}, open(os.path.join(S,'font','fx.json'),'w'))
json.dump({"pack":{"description":"Limitless : effets et sons du serveur","pack_format":88,"min_format":88,"max_format":99}},open(os.path.join(OUT,'pack.mcmeta'),'w'),indent=1)
print(len(models),'modeles')
