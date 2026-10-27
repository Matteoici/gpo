package fr.smpsalles;

import org.bukkit.entity.Player;

/** Lien avec un plugin de voice chat (sans dépendance directe : il peut être absent). */
interface VoiceLink {
    /** Appelé régulièrement : room = la salle où est le joueur (null = dehors). Renvoie true si l'état a changé. */
    boolean update(Player p, Room room);

    void forget(Player p);

    void shutdown();
}
