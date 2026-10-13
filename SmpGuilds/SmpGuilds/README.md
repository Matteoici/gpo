# SmpGuilds 1.0.0

Plugin de guildes complet pour un SMP sous **Paper 26.2** (Java 25).
Tout se fait depuis un menu (`/guild`) ou en commandes. Tous les textes sont modifiables dans
`messages.yml`, tous les réglages dans `config.yml`.

> ⚠️ Retire l'ancien **GuildPlugin** du serveur : les deux utilisent `/guild`.

## Fonctionnalités

**Création et personnalisation**
- Nom, tag (affiché `[TAG]`), description, couleur (16 couleurs), **bannière** (celle que tu tiens en main).
- Recrutement **ouvert** (tout le monde rejoint) ou **sur candidature**.
- Création et renommage payants (Vault), mots interdits, format des noms réglable.

**Membres**
- Rangs : Recrue, Membre, Officier, Chef. Les droits de chaque rang se règlent dans `config.yml`.
- Invitations cliquables, **candidatures** avec message (accepter / refuser dans un menu).
- Promouvoir, rétrograder, expulser, transmettre la direction.
- Statistiques par membre : kills, morts, argent déposé, date d'arrivée.

**Terrains**
- Claim par chunk, payé par la trésorerie. Terrains collés entre eux, zone tampon entre guildes,
  pas de claim près du spawn ou dans certains mondes.
- Protection complète : blocs, coffres, portes, seaux, pistons, explosions, animaux, cadres, armures.
- Titre à l'entrée d'un territoire, **carte** dans le chat, **auto-claim** en marchant.
- Entretien quotidien optionnel (la guilde perd des terrains si la trésorerie est vide).

**Home de guilde** avec délai (annulé si on bouge ou prend des dégâts) et temps de recharge.

**Trésorerie et coffre**
- Dépôts et retraits (montants rapides ou `10k`, `1.5m`...). À la dissolution, le chef récupère tout.
- **Coffre partagé** entre les membres, plus grand à chaque niveau (jusqu'à 54 cases).

**Niveaux** (10 paliers, modifiables)
- XP gagnée en tuant des joueurs ennemis, des monstres, et surtout en guerre. Anti-farm sur les kills.
- Chaque niveau augmente les membres max, les terrains max, la taille du coffre, et donne des
  **effets bonus** dans vos terrains (Vitesse, Célérité, Vision nocturne, Régénération...).

**Guerres**
- Déclaration payante, préparation (2 min), combat chronométré avec **boss bar** et score en direct.
- Victoire au score, au nombre de kills ou par abandon. Le gagnant prend un % de la trésorerie du perdant.
- Délai entre deux guerres contre la même guilde, minimum de défenseurs connectés.
- Option : autoriser le pillage des terrains ennemis pendant la guerre.

**Alliances** : demandes, pas de dégâts entre alliés, chat commun, limite d'alliés.

**Chat** : `/gc` (guilde) et `/ca` (alliés), en message unique ou en mode permanent. Espion pour les admins.

**Autres** : journal de la guilde, classement, pas de tir ami, sauvegarde automatique.

## Commandes

`/guild` (alias `/g`, `/guilde`, `/clan`) ouvre le menu. Les commandes acceptent aussi des mots français
(`/guild creer`, `/guild inviter`, `/guild guerre`...).

| Commande | Effet |
|---|---|
| `/guild create <nom> <tag>` | Créer une guilde |
| `/guild invite <joueur>` / `join <guilde>` / `apply <guilde> [message]` | Recruter, rejoindre, postuler |
| `/guild accept` / `deny <joueur>` | Gérer les candidatures |
| `/guild kick` / `promote` / `demote` / `transfer <joueur>` | Gérer les membres |
| `/guild leave` / `disband` | Quitter, dissoudre |
| `/guild info [guilde ou joueur]` / `top` / `list` | Infos, classement |
| `/guild description <texte>` / `tag` / `rename` / `color` / `banner` / `open` | Personnaliser |
| `/guild claim` / `unclaim` / `unclaimall` / `autoclaim` / `map` | Terrains |
| `/guild sethome` / `home` | Home |
| `/guild deposit` / `withdraw <montant>` / `bank` / `vault` | Trésorerie, coffre |
| `/guild ally` / `unally <guilde>` / `allies` | Alliances |
| `/guild war <guilde>` / `war surrender` | Guerres |
| `/guild members` / `levels` / `log` / `settings` | Menus |
| `/gc [message]` / `/ca [message]` | Chat de guilde / alliés |

**Admin** (`smpguilds.admin`) : `/guild admin reload | bypass | spy | info | disband | addxp | setxp | setbank | unclaim | save`

## Permissions

- `smpguilds.use` (tout le monde)
- `smpguilds.admin` (op) : commandes admin
- `smpguilds.bypass` (op) : pas de délai de home

## Placeholders (PlaceholderAPI)

Pour TAB, scoreboards, hologrammes, ajLeaderboards :

`%smpguilds_tag_formatted%` (tag coloré, idéal devant le pseudo dans TAB), `%smpguilds_name%`,
`%smpguilds_tag%`, `%smpguilds_role%`, `%smpguilds_level%`, `%smpguilds_xp%`, `%smpguilds_members%`,
`%smpguilds_online%`, `%smpguilds_claims%`, `%smpguilds_bank%`, `%smpguilds_kills%`, `%smpguilds_deaths%`,
`%smpguilds_kdr%`, `%smpguilds_wars_won%`, `%smpguilds_rank%`, `%smpguilds_territory%`, `%smpguilds_at_war%`,
`%smpguilds_top_1_name%`, `%smpguilds_top_1_level%` (de 1 à N).

## Installation

1. Mets `SmpGuilds-1.0.0.jar` dans `plugins/` et **retire GuildPlugin**.
2. Optionnel : Vault + un plugin d'économie (EssentialsX) pour la trésorerie, PlaceholderAPI pour les placeholders.
3. Redémarre le serveur.
4. Règle `plugins/SmpGuilds/config.yml` (prix, niveaux, guerres...) puis `/guild admin reload`.

Les données sont dans `plugins/SmpGuilds/guilds/` (un fichier par guilde).
