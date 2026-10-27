package fr.smpsalles;

/** Lien avec un plugin de voice chat (sans dépendance directe : il peut être absent). */
interface VoiceLink {
    /** Prêt (le serveur vocal a démarré) ? */
    boolean ready();

    /** Erreur éventuelle (pour /salle info). */
    String problem();

    void shutdown();
}
