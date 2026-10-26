package fr.smpevents.quest;

/** Ce qu'il faut faire pour avancer une quête. "targets" = blocs / créatures / objets acceptés (vide = tout). */
public enum QuestType {
    /** Casser des blocs (pas ceux posés par un joueur). targets = blocs */
    MINE,
    /** Récolter des cultures mûres. targets = blocs (WHEAT, CARROTS...) */
    HARVEST,
    /** Poser des blocs. targets = blocs */
    PLACE,
    /** Tuer des créatures. targets = types de créatures (ZOMBIE...) */
    KILL_MOB,
    /** Tuer des joueurs (pas ses amis / sa guilde / ses alliés, une fois par victime toutes les X heures) */
    KILL_PLAYER,
    /** Pêcher. targets = objets pêchés (COD, SALMON...) */
    FISH,
    /** Récupérer des objets cuits dans un four. targets = objets */
    SMELT,
    /** Faire se reproduire des animaux. targets = types d'animaux */
    BREED,
    /** Enchanter des objets à la table */
    ENCHANT,
    /** Échanger avec des villageois */
    VILLAGER_TRADE,
    /** Manger / boire. targets = objets */
    EAT,
    /** Marcher / courir (en blocs, pas en bateau, élytres, vol ou dans l'eau) */
    WALK,
    /** Minutes de jeu (sans être AFK) */
    PLAYTIME,
    /** Secondes passées dans la zone d'un roi de la colline */
    KOTH_TIME,
    /** Primes encaissées */
    BOUNTY,
    /** Dégâts infligés au boss (SmpBoss, compté à la fin du combat) */
    BOSS_DAMAGE,
    /** Victoire contre le boss (SmpBoss, avec assez de dégâts pour le butin) */
    BOSS_WIN
}
