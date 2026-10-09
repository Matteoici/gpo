# GpoTrade 2.0.0

Fenêtre d'échange inspirée de Grand Piece Online, pour un serveur **Paper 26.2** (Java 25).

## Fonctionnalités

- Fenêtre d'échange avec ta grille (gauche) et celle de ton partenaire (droite), en direct.
- **Minuteur de 10 s** au milieu, du vert au rouge, avec une **boss bar** et des sons.
  Il démarre quand les deux joueurs ont accepté. Toute modification de l'offre l'annule.
- **Chat privé** : clique sur le livre « Chat », écris ton message, la fenêtre se rouvre toute seule.
- **Monnaie** (Vault) : lingot d'or sous chaque grille.
- **Avis** : après un échange, chacun note l'autre de 1 à 5 étoiles. Les étoiles et le nombre
  d'échanges s'affichent sur la tête du joueur, comme dans GPO.
- **Fond graphique** (resource pack) **envoyé automatiquement** aux joueurs par le plugin.
- Anti-arnaque : délai avant de pouvoir accepter après un changement, alerte quand l'autre modifie son offre.
- Demande d'échange par `/trade <joueur>` ou **accroupi + clic droit** sur le joueur.
- Journal des échanges dans `plugins/GpoTrade/trades.log` (utile en cas de litige).

## Commandes

| Commande | Effet |
|---|---|
| `/trade <joueur>` | Proposer un échange |
| `/trade accept` / `decline` | Accepter / refuser une demande |
| `/trade open` / `cancel` | Rouvrir / annuler l'échange en cours |
| `/trade reload` | Recharger la config (op) |

## Installation

1. Mets `GpoTrade-2.0.0.jar` dans `plugins/`.
2. Optionnel, pour la monnaie : **Vault** + un plugin d'économie (ex. **EssentialsX**).
3. Redémarre le serveur (pas de `/reload`).

## Fond graphique

Le plugin héberge lui-même le pack sur le port **8163** et l'envoie aux joueurs à la connexion.
Ce port doit être ouvert chez ton hébergeur. Sinon, deux solutions dans `config.yml` :

- changer `resource-pack.port` pour un port autorisé ;
- ou héberger `plugins/GpoTrade/GpoTrade-ResourcePack.zip` toi-même et mettre le lien direct dans `resource-pack.url`.

Si l'IP détectée est mauvaise, renseigne `resource-pack.public-address`.
Sans le pack, tout fonctionne, avec une fenêtre au look simple.
