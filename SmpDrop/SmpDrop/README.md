# SmpDrop : largages aériens PvP

Toutes les 3 heures, une caisse remplie de stuff PvP tombe en parachute à un endroit au hasard de la carte. Le premier joueur qui tient **10 secondes** à côté sans se faire frapper remporte le butin.

## Installation

1. Mets `SmpDrop-1.0.0.jar` dans `plugins/` et redémarre.
2. Dans `plugins/SmpDrop/config.yml`, vérifie `location.world` (nom de ton monde principal) et `center-x` / `center-z` (le spawn).
3. Teste avec `/largage start now`.

Pas de dépendance obligatoire. Si ces plugins sont présents, SmpDrop les utilise automatiquement :
- **SmpGuilds** : la caisse ne tombe jamais dans un territoire de guilde.
- **SmpStart** : un joueur sous protection débutant ne peut pas ouvrir la caisse (sinon il serait impossible de l'interrompre).
- **PlaceholderAPI** : placeholders pour TAB ou le scoreboard.

## Déroulement d'un largage

| Moment | Ce qui se passe |
|---|---|
| T-30, T-10, T-5 min | Message « Prochain largage dans X minutes » |
| Début | Corne de raid, grand titre, message avec les coordonnées. Une barre en haut de l'écran de **tous les joueurs** affiche le compte à rebours, une **flèche** vers la caisse et la **distance**. Les boussoles pointent vers la caisse. |
| 5 min plus tard | La caisse apparaît 90 blocs au-dessus du sol avec un parachute rouge et blanc. Elle descend en ~24 s en tournant doucement, avec une traînée de fumée. Un cercle rouge au sol montre le point d'impact. |
| Atterrissage | Éclair (sans dégâts), nuage de poussière, le parachute se replie. Un faisceau lumineux et une colonne de fumée visible de loin apparaissent, et la caisse brille à travers les murs. |
| Ouverture | Clic droit, puis 10 s à moins de 4 blocs. Un anneau de particules se remplit du rouge au vert. Les joueurs à moins de 250 blocs sont prévenus et la barre de tout le monde passe au rouge : **« ⚠ Pseudo OUVRE LA CAISSE 60 % »**. Le moindre coup reçu (joueur, monstre, flèche, explosion) annule l'ouverture et la remet à zéro. Attaquer quelqu'un l'annule aussi. |
| Ouverte | Feux d'artifice (sans dégâts) aux couleurs de la meilleure rareté, pluie de totems, annonce à tout le serveur avec le meilleur objet. On peut survoler le message pour voir tout le contenu. Les objets légendaires et mythiques ont leur propre annonce. |
| Pillage | Le gagnant ouvre le coffre. Les autres peuvent venir se servir. Après 2 min, ce qui reste jaillit au sol. |

Si personne n'ouvre la caisse en 20 minutes, elle s'autodétruit.

### Pourquoi c'est sans lag
- La caisse et le parachute sont des *block displays* : ils n'ont aucune physique ni collision, et c'est le client qui anime la descente (téléportation interpolée toutes les 2 ticks).
- Le lieu de chute est cherché avec un chargement **asynchrone** des chunks.
- Tout l'événement tourne dans une seule tâche. Rien ne tourne entre deux largages, sauf une vérification par seconde.
- Les entités ne sont jamais sauvegardées sur le disque. Si le serveur s'arrête pendant un largage, rien ne reste au sol, et le bloc invisible sous la caisse est remis comme avant au redémarrage.

## Le butin

Chaque caisse contient 5 à 7 objets, dont **au moins un épique**. Chances par caisse, calculées sur 200 000 caisses simulées :

| Objet | Rareté | Chance par caisse |
|---|---|---|
| **Élytres** (+32 fusées) | Mythique | **2,5 %** (le plus rare) |
| Masse du Ciel | Légendaire | 3,6 % |
| Pomme de Notch | Légendaire | 3,6 % |
| Pièce en netherite enchantée | Légendaire | 3,6 à 4,5 % chacune |
| Au moins un légendaire | | ~30 % |
| Totem d'immortalité | Épique / Légendaire | ~18 % |
| Pièce en diamant Prot IV | Épique | |
| Pièce en diamant Prot II-III | Rare | ~27 % (plastron) |
| Pommes dorées, perles, toiles, potions, wind charges | Commun | ~40 % |

Avec 8 largages par jour, on obtient des élytres environ **une fois tous les 5 jours**.

Tout se règle dans `config.yml`, section `loot` : poids des raretés, liste des objets, enchantements (`sharpness: 4-5` pour un niveau au hasard), potions, nom personnalisé, maximum par caisse, objets bonus. `/largage chances` affiche les vrais pourcentages calculés à partir de ta config.

## Commandes

| Commande | Description |
|---|---|
| `/largage` (alias `airdrop`, `ld`, `drop`) | Prochain largage ou position du largage en cours |
| `/largage chances` | Chances de chaque rareté et des meilleurs objets |
| `/largage top` | Joueurs qui ont ouvert le plus de caisses |
| `/largage start` | **Admin** : lancer un largage (annonce de 5 min) |
| `/largage start now` | **Admin** : lancer un largage avec une annonce de 5 s seulement |
| `/largage here [secondes]` | **Admin** : faire tomber la caisse là où tu es |
| `/largage stop` | **Admin** : annuler le largage en cours |
| `/largage tp` | **Admin** : se téléporter à la caisse |
| `/largage next <minutes>` | **Admin** : changer l'heure du prochain largage |
| `/largage loot` | **Admin** : voir un tirage de butin au hasard (test) |
| `/largage reload` | **Admin** : recharger config et messages |

Permissions : `smpdrop.use` (tout le monde), `smpdrop.admin` (OP).

## Placeholders (PlaceholderAPI)

- `%smpdrop_next%` : temps avant le prochain largage (`1h42`, `4:05`, ou `en cours`)
- `%smpdrop_status%` : `aucun`, `annonce`, `chute`, `au sol`, `pillage`
- `%smpdrop_x%` / `%smpdrop_z%` : position du largage en cours
- `%smpdrop_opened%` : nombre de caisses ouvertes par le joueur

Par exemple, dans le scoreboard TAB : `&bLargage : &f%smpdrop_next%`.

## Tester

1. Mets-toi en **survie** : en créatif, on ne peut pas ouvrir la caisse.
2. `/largage here 5` : la caisse tombe sur toi dans 5 s, puis met ~24 s à descendre.
3. Clic droit sur la caisse et attends 10 s. Pour tester l'interruption, demande à un ami de te frapper.
4. `/largage loot` pour voir plusieurs tirages possibles.
