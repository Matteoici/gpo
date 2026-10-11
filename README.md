# SmpGuard : anticheat ESP / X-ray / Freecam

## À lire d'abord : ce qui est possible et ce qui ne l'est pas

L'**ESP** (voir les joueurs à travers les murs) et le **freecam** (caméra libre) sont des triches **d'affichage**. Elles tournent sur l'ordinateur du tricheur et n'envoient presque rien d'anormal au serveur. **Aucun plugin ne peut les prouver à 100 %.** C'est vrai aussi pour les anticheats payants.

SmpGuard travaille donc en trois couches :

| Couche | Ce qu'elle fait |
|---|---|
| 1. **Rendre la triche inutile** | Les joueurs derrière un mur ne sont plus envoyés au client : l'ESP et le freecam n'ont rien à afficher. Pour les minerais, l'**anti-xray de Paper** remplace ceux qui sont cachés par de faux blocs (configuration plus bas). |
| 2. **Repérer les comportements typiques** | Scores de suspicion avec un % de confiance, **pondérés par le lag** |
| 3. **Vérifier** | Test appât, `/spec`, fiche `/admin` : c'est le staff qui décide |

SmpGuard **ne bannit jamais tout seul**. Il alerte le staff et peut marquer le joueur *suspect* dans `/admin`.

## Les détections

### ESP : visée à travers les murs
Regarder par hasard vers un joueur caché, ça arrive à tout le monde. **Le suivre** pendant qu'il bouge derrière la roche, non. Une « poursuite » n'est comptée que si **toutes** ces conditions sont réunies :
- la visée reste sur la cible (à ~3,5° près) pendant au moins 1,5 s ;
- la cible est cachée (aucun des 7 points de son corps n'est visible) **et l'était déjà 1,5 s avant**, sinon le joueur l'a vue passer et anticipe ;
- la cible bouge, et le joueur a tourné la tête pour la suivre. Une cible qui passe dans l'axe d'un joueur immobile ne compte pas ;
- les deux joueurs ne sont pas dans la même guilde (SmpGuilds).

Les blocs transparents (verre, feuilles, barrières, dalles…) ne bloquent pas la vue : on préfère rater un tricheur que d'accuser quelqu'un qui voyait à travers une vitre.

### X-ray : minerais cachés
Sur une fenêtre de 20 minutes : combien de blocs le joueur mine pour trouver chaque filon rare (diamant, émeraude, débris antiques), et quelle part de ces filons était **totalement enfermée dans la roche** (sans grotte, eau ou lave autour, seulement des trous creusés par le joueur). Il faut au moins 4 filons pour conclure. Les filons dégagés par une explosion (TNT, lits dans le Nether) comptent comme visibles, donc pas de faux positif pour les mineurs de netherite à la TNT.

### Freecam / blink
Minecraft envoie au serveur l'état des touches de déplacement. Si le joueur **appuie sur avancer pendant plus de 4 secondes sans que son corps bouge d'un millimètre**, alors qu'il n'y a ni mur devant, ni véhicule, ni échelle, ni bord de bloc en étant accroupi, ni freeze du staff, c'est très probablement une caméra détachée (freecam) ou un « blink ».

**Limite :** un freecam qui bloque aussi l'envoi des touches est invisible pour le serveur. Contre lui, la protection anti-ESP (couche 1) l'empêche de voir les joueurs à travers les murs.

### Test appât : `/ac bait <joueur>`
L'outil de vérification pour le staff :
1. Un **faux joueur** (tête, armure, épée), **visible uniquement par le suspect**, se déplace pendant 30 s dans la roche autour de lui, toujours caché derrière des blocs pleins.
2. Un joueur normal ne peut pas le voir. Avec un ESP qui affiche les entités, le suspect le remarque et le regarde.
3. SmpGuard compare le temps passé à viser l'appât avec le temps passé à viser un **point témoin** (la position symétrique, où il n'y a rien). Le hasard touche les deux pareil, l'ESP seulement l'appât.
4. Résultat : « TRÈS SUSPECT », « SUSPECT » ou « RIEN D'ANORMAL ».

Lance-le quand le joueur est sous terre ou dans une base, et ne lui dis rien. Un ESP réglé sur « joueurs uniquement » peut ignorer l'appât, donc un résultat « rien d'anormal » n'innocente pas à 100 %.

## Prise en compte du lag

Chaque mesure reçoit une **fiabilité de 0 à 100 %**, visible dans `/ac` :

- **Ping :** fiabilité pleine sous 120 ms, puis elle baisse jusqu'à 350 ms. Au-delà, **rien n'est compté**.
- **Gigue :** si le ping varie de plus de 120 ms, rien n'est compté.
- **Serveur :** sous 17 TPS, rien n'est compté pour personne.
- **Pauses :** rien n'est compté pendant 15 s après la connexion, 4 à 6 s après une téléportation, une mort, un changement de monde ou de mode de jeu.
- **Position comparée :** on utilise celle que **le client voyait vraiment** (position d'il y a ping + 150 ms), pas la position actuelle du serveur.
- **Tolérance de visée :** elle s'élargit selon la vitesse de la cible, la distance et l'instabilité du ping.
- **Freecam :** le temps minimum augmente avec le ping et la gigue.
- **Points :** les points de suspicion sont multipliés par la fiabilité, et les scores baissent tout seuls avec le temps.

Un joueur qui lague peut être *moins bien surveillé*, mais il ne peut pas être *accusé à tort* à cause de son lag.

## Lien avec le panneau admin (SmpAdmin)

- **`/admin` :** nouveau bouton **Anticheat** (bouclier). La fiche de chaque joueur a un bouton **Rapport anticheat**.
- **Alertes :** chaque alerte a des boutons cliquables **[SPEC]** (spectateur), **[FICHE]** (`/admin`) et **[RAPPORT]** (`/ac check`).
- **Seuil `sus-vl` :** le joueur est automatiquement **marqué suspect** (`/sus`). Le staff est alors prévenu à chacune de ses connexions.
- **Joueurs ignorés :** les joueurs gelés (`/freeze`) ou en vanish ne sont pas analysés.

## Commandes et permissions

| Commande | Effet |
|---|---|
| `/ac` | Panneau : joueurs triés par suspicion, ping, fiabilité, scores |
| `/ac check <joueur>` | Rapport détaillé + boutons (spec, appât, fiche, suspect) |
| `/ac bait <joueur> [secondes]` | Test appât |
| `/ac alerts` | Couper / remettre ses alertes |
| `/ac status` | État du plugin |
| `/ac reset <joueur>` · `/ac reload` | Admin |

Permissions :
- `smpguard.staff` : panneau, rapports, appât
- `smpguard.alerts` : recevoir les alertes
- `smpguard.admin` : reset, reload
- `smpguard.see-all` : voit tout le monde même avec l'anti-ESP
- `smpguard.bypass` : jamais analysé

Pour tes modos :
```
/lp group mod permission set smpguard.staff true
/lp group mod permission set smpguard.alerts true
/lp group mod permission set smpguard.see-all true
```

## Protection anti-ESP (joueurs cachés derrière les murs)

Elle est **désactivée par défaut**. Pour l'activer : `anti-esp.hide-players-behind-walls: true` dans `config.yml`, puis `/ac reload`. Teste-la d'abord avec quelques joueurs.

- Un joueur caché réapparaît dès qu'**un seul** rayon passe. On anticipe le mouvement des deux joueurs selon le ping, et personne n'est caché à moins de 6 blocs ni pendant un combat.
- **Effets secondaires :**
  - un joueur caché disparaît aussi de la **liste TAB** de celui qui ne le voit pas, tant qu'il est derrière un mur ;
  - on n'entend plus ses pas à travers les murs.
- **Performances :** les calculs sont limités à `budget-per-tick` rayons par tick (400 par défaut).

## Anti-xray de Paper (fortement conseillé)

C'est la meilleure protection contre le x-ray : les minerais cachés sont remplacés par de faux minerais partout, donc le tricheur ne sait plus où creuser. Dans `config/paper-world-defaults.yml`, section `anticheat` :

```yaml
anticheat:
  anti-xray:
    enabled: true
    engine-mode: 2
```

Pour le Nether, ajoute dans `world_nether/paper-world.yml` les débris antiques à `hidden-blocks`. Vérifie le nom exact des options dans la documentation Paper de ta version, car elles changent parfois. Ensuite, redémarre.

## Réglages (`config.yml`)

Tous les seuils sont réglables : précision de visée, nombre de filons, durées, seuils d'alerte (`alert-vl`) et de suspect (`sus-vl`), vitesse de baisse des scores. Si tu as trop d'alertes, augmente `alert-vl`. Si tu n'en as jamais, baisse-le. Les alertes sont aussi écrites dans `plugins/SmpGuard/logs/`.
