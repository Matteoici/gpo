# SmpFriends 1.0.0

Menu d'amis complet pour un SMP sous **Paper 26.2** (Java 25).

## Ce que ça fait

**Menu `/amis`** : tous tes amis sous forme de têtes, favoris en premier, puis ceux en ligne.
Le menu se met à jour tout seul toutes les 2 secondes.
- Statut : en ligne, AFK (avec la durée), ou hors ligne (avec « vu il y a… »).
- Position : monde et coordonnées.
- Argent (Vault).
- Clic gauche : fiche détaillée. Clic droit : demande de téléportation. Shift + clic : favori.
- Filtre « tous / en ligne », pages de 28 amis.

**Fiche d'un ami** :
- Position, argent, statistiques (kills, morts, K/D, mobs tués, temps de jeu).
- « Ce qu'il fait » : objet en main, mode de jeu, vie, faim, niveau, ping.
- Activité : statut, et depuis combien de temps il joue sur le serveur.
- Actions :
  - téléportation (`/tpa` d'EssentialsX) ;
  - message privé ;
  - échange (`/trade` de GpoTrade) ;
  - envoi d'argent ;
  - **suivi** : une flèche dans la barre d'action te guide vers lui ;
  - favori, retrait de tes amis (avec confirmation).

**Envoyer de l'argent** : montants rapides, ou un montant libre (`2500`, `10k`, `1.5m`...).

**Demandes d'ami** : boutons cliquables dans le chat, menu dédié, « tout accepter / tout refuser ».
Les demandes restent enregistrées même si le joueur est hors ligne.

**Réglages** (chaque joueur choisit) : montrer sa position, son argent, ses stats,
accepter les demandes, notifications de connexion.

**Notifications** : un son et un message quand un ami se connecte ou part.
À la connexion : « 3/12 amis en ligne » et le nombre de demandes en attente.

**Chat d'amis** : `/fc <message>` envoie à tous tes amis connectés.

## Commandes

| Commande | Effet |
|---|---|
| `/amis` | Ouvrir le menu |
| `/amis <joueur>` ou `/amis add <joueur>` | Envoyer une demande |
| `/amis accept` / `deny` / `remove <joueur>` | Gérer tes amis |
| `/amis msg <joueur> [message]` | Message privé |
| `/amis requests` / `settings` / `list` | Raccourcis |
| `/fc <message>` | Chat d'amis |
| `/amis reload` | Recharger la config (op) |

Alias : `/friends`, `/friend`, `/ami`.

## Permissions

- `smpfriends.use` (tout le monde) : utiliser le plugin.
- `smpfriends.unlimited` (op) : pas de limite d'amis.
- `smpfriends.admin` (op) : `/amis reload`.

## Compatibilité

- Vault et EssentialsX (ou un autre plugin d'économie) : optionnels. Sans eux, les boutons concernés sont grisés.
- GpoTrade : le bouton « Proposer un échange » lance `/trade`.
- Les commandes des boutons se changent dans `config.yml` (`commands.teleport`, `commands.trade`).
- Les menus fonctionnent aussi pour les joueurs Bedrock (Geyser).
