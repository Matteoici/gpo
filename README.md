# SmpMarket : le marché des joueurs

Les joueurs louent un **stand** aux enchères, le décorent comme ils veulent et exposent leurs objets dans des **vitrines**. Chaque vitrine est un objet qui tourne sur lui-même avec un panneau flottant indiquant le prix et le stock. Les autres joueurs achètent en un clic droit, **même quand le vendeur n'est pas connecté**.

Dépendance : **Vault** (avec EssentialsX pour l'argent, comme sur ton serveur).

## 1. Construire le marché (admin)

1. Construis une rue avec des stands, par exemple 5x5 blocs chacun : sol, poteaux, toit. C'est toi qui choisis le style.
2. `/marche admin wand` : tu reçois la **Baguette du marché**.
3. Pour chaque stand :
   - **Clic gauche** sur un coin au **sol**, puis **clic droit** sur le coin opposé **en haut**. Toute la hauteur doit être incluse : c'est la zone que le locataire pourra modifier.
   - Place-toi **devant l'entrée** du stand, regarde vers lui, et tape `/marche admin create a1 2000`.
     - `a1` est le nom du stand.
     - `2000` est la mise à prix.
   - Le panneau flottant **« ★ STAND À LOUER ★ »** apparaît au-dessus de toi.
4. Recommence pour `a2`, `a3`… Les stands bien placés (entrée du marché, angles) peuvent avoir une mise à prix plus élevée.

En créatif, un admin peut construire partout dans les stands.

## 2. Les enchères

- **Stand libre** : l'enchère est ouverte en permanence. Le compte à rebours (**12 h**) démarre à la **première offre**.
- **Retenue de l'argent** : l'offre est retirée tout de suite du compte. Si quelqu'un surenchérit, l'argent est **rendu automatiquement** et le joueur est prévenu, même s'il est hors ligne.
- **Surenchère minimale** : +100 $ ou +5 %.
- **Anti-dernière seconde** : une offre dans les 5 dernières minutes prolonge l'enchère de 5 minutes.
- **Durée de location** : le gagnant obtient le stand pour **7 jours**.
- **Fin de location** : **24 h avant la fin**, une enchère s'ouvre pour la semaine suivante.
  - Le locataire peut surenchérir pour garder son stand. Il est prévenu si quelqu'un tente de le lui prendre.
  - Si personne n'enchérit, le stand est **renouvelé automatiquement** à la mise à prix. Le locataire peut désactiver cette option.
  - S'il perd son stand, tout son stock part dans **`/marche colis`**. Rien n'est jamais perdu.
- **Limite** : 1 stand par joueur, réglable dans la config.

## 3. Vendre (locataire)

1. Prends l'objet à vendre en main, avec toute la quantité à mettre en stock.
2. Vise un bloc **dans ton stand** (tonneau, dalle, bloc décoratif…).
3. Tape `/marche vitrine 250`, où `250` est le prix **à l'unité**. Tu peux écrire `2.5k` ou `1m`.
4. L'objet apparaît au-dessus du bloc et tourne lentement, avec son prix et son stock.

**Clic droit sur ta propre vitrine** : ajouter du stock depuis ton inventaire, retirer une pile ou tout, changer le prix, supprimer la vitrine.

**Clic droit sur ton panneau** (ou `/marche gerer`) :
- liste des vitrines avec leurs ventes et recettes
- renommer la boutique (le panneau affiche « 🏪 Le Bazar de Kev »)
- amis autorisés à construire
- renouvellement automatique
- enchère en cours pour garder le stand

L'argent des ventes arrive directement sur ton compte, moins une **taxe de 5 %**. Cette taxe disparaît de l'économie, ce qui limite l'inflation. Quand tu te reconnectes, un message résume tes ventes pendant ton absence.

## 4. Acheter

- **Clic droit sur une vitrine** : choix de la quantité (x1, x4, x8, x16, x32, x64 ou **le maximum** possible selon ton argent, ta place et le stock).
- **Clic droit sur le panneau d'une boutique** : son catalogue complet.
- **`/marche chercher diamant`** : toutes les vitrines qui vendent des diamants, **de la moins chère à la plus chère**, avec téléportation au stand. La recherche comprend le français : *épée, pioche, bûche de chêne, pomme dorée, lingot de fer, laine rouge, élytres…*

## Commandes

| Commande | Effet |
|---|---|
| `/marche` (`/mk`, `/stand`) | Menu du marché |
| `/marche chercher <objet>` | Meilleurs prix |
| `/marche encheres` | Stands à louer et enchères en cours |
| `/marche encherir <stand> <montant>` | Enchérir sans passer par le menu |
| `/marche vitrine <prix>` | Exposer l'objet en main sur le bloc visé |
| `/marche gerer` | Gérer sa boutique |
| `/marche nom <nom>` | Renommer sa boutique |
| `/marche ami <joueur>` · `/marche retirer-ami <joueur>` | Autoriser / retirer un constructeur |
| `/marche tp <stand ou joueur>` | Aller à un stand |
| `/marche colis` | Récupérer ses objets rendus |
| `/marche admin ...` | `wand`, `create`, `setentrance`, `price`, `showcases`, `give`, `end`, `reset`, `delete`, `list`, `reload` |

`/market` n'est **pas** utilisé, pour ne pas entrer en conflit avec PlayerMarketGUI.

## Protection

- Seuls le locataire et ses amis peuvent construire, casser ou ouvrir les coffres dans le stand. Les explosions ne l'abîment pas.
- Le bloc qui porte une vitrine ne peut pas être cassé tant que la vitrine existe.
- Si ton spawn est protégé (WorldGuard ou spawn-protection), le locataire peut quand même construire dans **son** stand. Désactivable avec `override-other-protections`.

## Pour tester seul

1. Construis un petit stand, `/marche admin wand`, sélectionne les 2 coins, place-toi devant et tape `/marche admin create test 100`.
2. `/marche admin give test <ton pseudo>` : le stand est à toi sans enchère.
3. Prends 64 diamants en main, vise un bloc du stand et tape `/marche vitrine 50`.
4. Avec un deuxième compte, fais un clic droit sur la vitrine et achète.
5. Pour tester l'enchère : `/marche admin reset test`, enchéris avec un compte, puis `/marche admin end test` pour la terminer tout de suite.

## Fichiers

- `config.yml` : durées, taxe, nombre de vitrines, protection, visuels.
- `messages.yml` : tous les textes (panneaux, menus, annonces).
- `stalls.yml` : les stands, leurs vitrines et leur stock. Les objets sont enregistrés avec leurs enchantements et leurs noms.
- `mailbox.yml` : colis et messages en attente des joueurs hors ligne.

Les panneaux et objets exposés ne sont pas sauvegardés dans le monde : ils sont recréés à chaque chargement de chunk. Si tu retires le plugin, il ne reste aucune entité orpheline.
