package fr.smpstart;

/** Ce que le joueur doit faire pour valider une étape. */
public enum StepType {
    /** Casser des blocs (targets = matériaux, ex. *_LOG). */
    BREAK,
    /** Poser des blocs. */
    PLACE,
    /** Fabriquer des objets. */
    CRAFT,
    /** Tuer des créatures (targets = types, ou HOSTILE pour tous les monstres). */
    KILL,
    /** Utiliser une commande (targets = commande sans le /). */
    COMMAND,
    /** Jouer N minutes. */
    PLAYTIME
}
