# PauseMenu 1.0.0

Ajoute un bouton **« ★ Menu du serveur ★ »** dans le menu pause (Échap) de Minecraft.
Il ouvre une fenêtre avec des boutons cliquables vers tes plugins : boutique, vente, amis, échange,
quêtes, RTP, spawn, guilde, primes…

Un bouton n'apparaît que si le plugin correspondant est installé sur ton serveur.

## Comment ça marche

Minecraft sait afficher des fenêtres personnalisées envoyées par le serveur (les « dialogues »).
Le plugin crée un petit datapack avec ces fenêtres dans `<monde>/datapacks/PauseMenu`.
Le jeu place lui-même le bouton dans le menu pause : l'endroit exact dépend de Minecraft, pas du plugin.
Le menu est aussi accessible avec la touche « Actions rapides » (réglable dans les options de contrôles).

## Installation

1. Mets `PauseMenu-1.0.0.jar` dans `plugins/`.
2. Démarre le serveur : le datapack est créé.
3. **Redémarre le serveur une deuxième fois** (le jeu ne charge les dialogues qu'au démarrage).
4. Reconnecte-toi : le bouton est dans le menu Échap.

## Modifier les boutons

Ouvre `plugins/PauseMenu/config.yml`. Les commandes sont mises à des valeurs probables :
**vérifie-les avec les commandes réelles de tes plugins** (par exemple `/market`, `/crates`, `/guild`).
Ensuite : `/pausemenu reload`, **redémarre**, reconnecte-toi.

- `plugin:` nom du plugin requis (laisser vide = toujours affiché).
- `mode: suggest` écrit la commande dans le chat (pour celles qui demandent un pseudo, comme `/trade`).
- `flat: true` met tous les boutons dans une seule fenêtre au lieu des catégories.
- `columns`, `button-width`, couleurs : tout est réglable.

## Commandes

| Commande | Effet |
|---|---|
| `/pausemenu` | Même menu, dans le chat (cliquable) |
| `/pausemenu reload` | Recharge la config et réécrit le datapack (op) |

## Si le serveur ne démarre plus

Si une fenêtre du datapack est refusée par ta version de Minecraft, supprime le dossier
`<monde>/datapacks/PauseMenu` et envoie-moi l'erreur de la console.

## Limites

- Les joueurs Bedrock (Geyser) ne voient pas ce menu : ils ont `/pausemenu` dans le chat.
- Le menu ne se met à jour qu'après un redémarrage du serveur et une reconnexion.
