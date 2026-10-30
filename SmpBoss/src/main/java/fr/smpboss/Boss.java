package fr.smpboss;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** Un boss en cours (Roi Citrouille ou Reine Araignée) : un seul à la fois sur le serveur. */
public interface Boss {
    boolean isBoss(Entity e);

    boolean isMinion(Entity e);

    boolean active();

    double hp();

    double maxHp();

    int phase();

    int fighters();

    void start(Player summoner);

    /** Un joueur frappe le boss. false = coup ignoré. */
    boolean hurt(Player by, double amount);

    boolean inFight(Player p);

    void leave(String msgKey);

    void cleanup();

    /** L'arène où se passe le combat. */
    Arena arena();

    /** "roi" ou "reine" (messages, quêtes, butin). */
    String kind();
}
