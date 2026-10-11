# SmpAdmin : panneau d'administration

Tout pour modérer le serveur : panneau en menu, vanish, bans temporaires avec écran de ban, mute, kick, avertissements, freeze pour les vérifications, liste de suspects, spectateur qui fait le tour des joueurs, invsee, casier judiciaire et chat staff.

## Installation

1. Mets `SmpAdmin-1.0.0.jar` dans `plugins/` et redémarre.
2. Donne les permissions à ton staff avec LuckPerms (voir plus bas). Les OP ont tout par défaut.

**EssentialsX** possède aussi `/ban`, `/mute`, `/vanish`, `/kick`, `/invsee`… Par défaut, Essentials laisse la priorité aux autres plugins, donc ce sont les commandes de SmpAdmin qui s'exécutent. Les anciennes restent accessibles avec `/essentials:ban`. Les **anciens bans Essentials ne sont pas importés**.

## Le panneau `/admin`

- **Barre du haut** : vanish, tour spectateur, suspects, nombre de joueurs / staff / bannis, liste des bannis, chat staff, blocage du chat.
- **Tous les joueurs connectés** (les suspects en premier, en surbrillance), avec monde, mode, vie, ping et nombre de sanctions. Des symboles indiquent l'état du joueur : ⚠ suspect, ❄ gelé, 🔇 muet, 👻 vanish.
  - Clic gauche : fiche du joueur
  - Clic droit : l'observer en spectateur
  - Shift + clic : se téléporter à lui
- **Fiche joueur** : se téléporter, le téléporter à soi, observer, inventaire, coffre de l'Ender, geler, suspect, avertir, mute/unmute, kick, ban/unban, casier.
- **Menu de sanction** : choisis une durée (30 min → définitif), puis une raison prédéfinie (chacune a sa durée par défaut) ou « Autre raison… » à écrire dans le chat. Un écran de confirmation suit. Un bouton « Discret » évite l'annonce publique.

`/ban Pseudo`, `/mute Pseudo`, `/kick Pseudo` et `/warn Pseudo` tapés seuls ouvrent directement ce menu.

## Commandes

| Commande | Effet |
|---|---|
| `/admin [joueur]` (`/panel`, `/ap`) | Panneau, ou fiche d'un joueur |
| `/vanish [joueur]` (`/v`) | Invisible pour les joueurs (et absent du TAB). Faux message de déconnexion, vision nocturne, invincible, pas de ramassage, les monstres t'ignorent, les plaques de pression ne s'activent pas. |
| `/ban <joueur> [durée] [raison] [-s]` | Ban (fonctionne aussi hors ligne). `-s` = discret. |
| `/unban <joueur>` · `/banlist` | Débannir · liste des bannis en menu |
| `/mute <joueur> [durée] [raison]` · `/unmute` | Bloque le chat et les `/msg`, `/r`… |
| `/kick <joueur> [raison]` | Expulser avec un bel écran |
| `/warn <joueur> <raison>` | Titre à l'écran + compteur d'avertissements |
| `/history <joueur>` (`/casier`) | Toutes les sanctions. Clic droit pour lever un ban ou un mute. |
| `/freeze <joueur>` (`/ss`) | Gèle le joueur : il ne peut plus bouger, casser, frapper ni taper de commande. S'il se déconnecte : **ban automatique 30 jours**. |
| `/sus <joueur> [raison]` | Marque comme suspect. Le staff reçoit une alerte avec des boutons **[SPEC] [FICHE]** à chaque connexion du joueur. `/sus list`, `/sus remove <joueur>`. |
| `/spec` | **Tour de tous les joueurs** en vue 1re personne, 12 s chacun, avec vie, ping et monde affichés. Te met en vanish, et te ramène à ta position à la fin. |
| `/spec <joueur>` · `/spec next` · `/spec prev` · `/spec stop` | Observer un joueur précis, passer au suivant / précédent, arrêter |
| `/invsee <joueur>` · `/ecsee <joueur>` | Inventaire / coffre de l'Ender (lecture seule sans `smpadmin.invsee.modify`) |
| `/sc [message]` | Chat staff (`/sc` seul = bascule). Raccourci : écrire `#message`. |
| `/clearchat` · `/chatlock` | Nettoyer / bloquer le chat |
| `/admin reload` | Recharger config et messages |

**Durées** : `30m`, `2h`, `7d` (ou `7j`), `2w`, `1mo`, `1y`, combinables (`1d12h`), ou `perm`. Un nombre seul comme `10` est refusé, pour éviter un ban définitif par erreur.

## Permissions (LuckPerms)

Exemple pour un groupe `mod` :

```
/lp group mod permission set smpadmin.panel true
/lp group mod permission set smpadmin.vanish true
/lp group mod permission set smpadmin.vanish.see true
/lp group mod permission set smpadmin.ban true
/lp group mod permission set smpadmin.unban true
/lp group mod permission set smpadmin.mute true
/lp group mod permission set smpadmin.kick true
/lp group mod permission set smpadmin.warn true
/lp group mod permission set smpadmin.history true
/lp group mod permission set smpadmin.freeze true
/lp group mod permission set smpadmin.sus true
/lp group mod permission set smpadmin.spec true
/lp group mod permission set smpadmin.invsee true
/lp group mod permission set smpadmin.staffchat true
/lp group mod permission set smpadmin.alerts true
/lp group mod permission set smpadmin.chat true
```

Réservé aux admins : `smpadmin.ban.perm` (ban définitif), `smpadmin.invsee.modify`, `smpadmin.vanish.others`, `smpadmin.reload`, `smpadmin.exempt` (ne peut pas être sanctionné par un modo). Pour tout donner : `smpadmin.*`.

Les OP ne peuvent pas être sanctionnés par un modo, même hors ligne.

## Fichiers

- `config.yml` : annonces publiques, vanish, freeze, durées et raisons des menus, durée du tour spectateur.
- `messages.yml` : tous les textes, y compris l'écran de ban (ajoute le lien de ton Discord).
- `sanctions.yml` : toutes les sanctions, numérotées.
- `data.yml` : joueurs en vanish, gelés, suspects, état du chat. Tout est conservé après un redémarrage.

## Tester

1. `/admin` : le panneau s'ouvre.
2. `/vanish` : avec un compte non-OP, vérifie que tu disparais du jeu et de la liste TAB.
3. `/ban <compte test> 1m test` : le compte voit l'écran de ban, peut revenir après 1 minute.
4. `/freeze <compte test>`, puis déconnecte ce compte : il est banni automatiquement.
5. `/spec` avec 2 joueurs ou plus connectés : la caméra passe de l'un à l'autre.
