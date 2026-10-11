# SmpStart 1.0.0

Début de partie guidé pour un SMP sous **Paper 26.2** (Java 25).
Il remplace les kits d'un plugin comme EssentialsX : **SmpStart donne lui-même le kit**, tu n'as rien d'autre à installer.

## Pourquoi ce plugin

La plupart des joueurs qui quittent un serveur le font dans les **30 premières minutes** : ils ne savent
pas quoi faire, se font tuer par un autre joueur, ou ne découvrent jamais les plugins du serveur.
SmpStart règle ces trois problèmes :

1. **Ils sont équipés** : kit de départ, argent, lit, nourriture.
2. **Ils sont protégés** : 20 minutes de jeu sans PvP.
3. **Ils savent quoi faire** : 17 étapes dans l'ordre, qui se valident toutes seules, chacune avec une récompense.
   Les étapes font découvrir `/sethome`, `/sell`, `/shop`, `/rtp`, les quêtes, la récompense du jour, les amis et les guildes.

## Les 30 premières minutes d'un nouveau joueur

| Minute | Ce qui se passe |
|---|---|
| **0:00** | Il se connecte. Un titre « Bienvenue ! » s'affiche, un message lui explique les trois points ci-dessus. Il reçoit son **kit** et **500 $**. Les autres joueurs voient « X rejoint le serveur pour la première fois ! Dis-lui bienvenue ». |
| **0:00 - 1:00** | Les joueurs qui écrivent « bienvenue » dans la première minute reçoivent **50 $** (5 maximum). Le nouveau se sent attendu. |
| **0:30** | Il ouvre son **guide** (livre dans l'inventaire, ou `/guide`). Étape 1 validée : +100 $. |
| **1 - 6** | **Bois** (8 bûches), **établi**, **lit**, **home**. Il sait désormais fabriquer, dormir et revenir chez lui. |
| **6 - 15** | **Charbon**, **fer**, **four** : il descend sous terre, c'est le cœur du jeu. |
| **15 - 20** | **/sell** puis **/shop** : il comprend l'économie. Il a déjà reçu plus de 1 500 $ en récompenses. |
| **20** | Sa **protection débutant** se termine (message). **Chasse** de 8 monstres. |
| **20 - 30** | **/rtp** (explorer), **quêtes**, **récompense du jour** (une raison de revenir demain), **amis**, **guilde**. |
| **30** | **Temps de jeu** de 30 minutes, puis fin du guide : **1 500 $, 5 diamants, 3 pommes d'or** et une annonce à tout le serveur. |

Total donné pendant le guide : environ **5 250 $** (500 de départ + 3 250 d'étapes + 1 500 de fin), à adapter à ton économie.

## Ce que contient le kit (modifiable)

Livre « Guide du voyageur » (clic droit pour ouvrir le guide), pioche / hache / pelle / épée en pierre
(légèrement enchantées), armure en cuir (équipée automatiquement), 16 steaks, 8 pains, 32 torches,
un lit, 4 pousses de chêne, et 500 $.
Il n'est donné **qu'une seule fois**, à la première connexion.

## Fonctionnement des étapes

- Elles se valident **dans l'ordre**, seule l'étape en cours avance : le joueur n'est jamais perdu.
- À chaque étape terminée : titre, son, récompense, puis la consigne suivante dans le chat avec une
  **commande cliquable**.
- Une **barre de progression** en haut de l'écran montre l'étape en cours (désactivable par le joueur).
- Un **rappel** dans le chat toutes les 6 minutes tant que le guide n'est pas fini.
- Le menu `/guide` montre toutes les étapes : terminées (✔), en cours (▶), à venir (🔒), avec leurs récompenses.
- Les joueurs déjà présents avant l'installation **n'ont pas de guide** (réglable : `include-existing-players`).

## Personnaliser

Tout est dans `plugins/SmpStart/config.yml`, puis `/guide reload`.
Chaque étape a un **type** :

| Type | Valide quand le joueur... | `targets` |
|---|---|---|
| `BREAK` | casse des blocs | `OAK_LOG`, `*_LOG` (tous les troncs), `COAL*`... |
| `PLACE` | pose des blocs | `*_BED`... |
| `CRAFT` | fabrique un objet | `CRAFTING_TABLE`, `FURNACE`... |
| `KILL` | tue des créatures | `ZOMBIE`, `HOSTILE` (tous les monstres) |
| `COMMAND` | utilise une commande | la commande sans `/` : `sell`, `guild create` |
| `PLAYTIME` | joue N minutes | (aucune) |

Une récompense peut contenir de l'argent (`money`), des objets (`items: ["DIAMOND:5"]`) et des commandes
console (`commands: ["give {player} ..."]`). Tu peux ajouter, retirer ou réordonner des étapes (28 maximum affichées).

> ⚠ Les commandes des étapes (`/quests`, `/dailyreward`, `/sell`...) sont des valeurs probables.
> **Vérifie-les avec les commandes réelles de tes plugins**, sinon l'étape ne se validera jamais
> (ajoute plusieurs noms dans `targets` si besoin).
> Une commande compte dès qu'elle est tapée, même si elle échoue.

Tous les textes (titres, messages, menu) sont dans `messages.yml`, au format MiniMessage.

## Commandes

| Commande | Effet |
|---|---|
| `/guide` (alias `/start`, `/debut`, `/tuto`) | Ouvrir le guide |
| `/guide bar [on/off]` | Afficher / masquer la barre de progression |
| `/guide protection [off]` | Voir / retirer sa protection débutant |
| `/guide reload` | Recharger la config (admin) |
| `/guide reset <joueur>` | Remettre le guide à zéro (admin) |
| `/guide complete <joueur>` | Terminer le guide d'un joueur (admin) |
| `/guide kit <joueur>` | Redonner le kit (admin) |

Permissions : `smpstart.use` (tout le monde), `smpstart.admin` (op).

## Installation

1. Mets `SmpStart-1.0.0.jar` dans `plugins/` et redémarre.
2. **Vault + EssentialsX** pour l'argent (sinon les récompenses en argent sont désactivées).
3. Ouvre `config.yml` et vérifie les commandes des étapes.
4. Teste avec un second compte, ou : `/guide reset <toi>` puis `/guide kit <toi>`.

## Conseils pour un meilleur accueil

- Place le **spawn** (`/setspawn`) dans une zone claire et fais-y apparaître les nouveaux
  (`first-join-commands: ["spawn {player}"]`).
- Écris un `motd.txt` et un `rules.txt` courts dans EssentialsX (les règles sont aussi une étape possible).
- Ajoute le bouton « Guide » dans ton menu Échap avec PauseMenu.
