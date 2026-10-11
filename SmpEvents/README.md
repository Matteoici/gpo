# SmpEvents

Quêtes du jour / de la semaine, classements en hologrammes, roi de la colline (KOTH), primes anti-abus.
Dépendances facultatives : Vault (argent), SmpGuilds, SmpFriends, SmpAdmin, PlaceholderAPI.

## Joueurs
- `/quetes` : 3 quêtes du jour (facile, moyenne, difficile) + 2 de la semaine, bonus si tout est fini, série de jours.
- `/classement` : tous les classements et ta place.
- `/koth` : prochain roi de la colline.
- `/prime <joueur> <montant>` : mettre une prime (`/prime` = liste). Pas versée entre amis / guilde / alliés / même connexion.

## Admin
- `/classement placer argent` (ou `argent,kills,temps` pour qu'il tourne), `/classement retirer`, `/classement liste`
- `/koth creer <nom> [rayon]`, `/koth lancer [nom]`, `/koth stop`, `/koth liste`, `/koth supprimer <nom>`
- `/prime retirer <joueur>`, `/quetes reset <joueur>`, `/smpevents reload`

Réglages : `config.yml` (récompenses, horaires KOTH, primes), `quests.yml` (liste des quêtes), `messages.yml` (textes).
