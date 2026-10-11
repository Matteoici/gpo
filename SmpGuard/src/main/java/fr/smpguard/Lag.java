package fr.smpguard;

import org.bukkit.Bukkit;

/**
 * Prise en compte du lag.
 * weight() : confiance de 0 à 1 accordée aux mesures d'un joueur à cet instant.
 *   - 0 si le serveur rame, si le joueur vient d'arriver / d'être téléporté, ou si sa connexion est mauvaise
 *   - diminue progressivement entre good-ping et max-ping, et avec la gigue (ping instable)
 * delayTicks() : retard entre ce que le joueur voit et la position réelle sur le serveur.
 */
public final class Lag {

    private final SmpGuard plugin;

    Lag(SmpGuard plugin) {
        this.plugin = plugin;
    }

    public static double tps() {
        try {
            return Bukkit.getTPS()[0];
        } catch (Throwable t) {
            return 20;
        }
    }

    public double weight(Profile p) {
        Settings s = plugin.settings();
        if (System.currentTimeMillis() < p.graceUntil) return 0;
        double tps = tps();
        if (tps < s.minTps) return 0;
        int ping = p.avgPing();
        double jitter = p.jitter();
        if (ping > s.maxPing || jitter > s.maxJitter) return 0;
        double w = 1;
        if (ping > s.goodPing) w *= 1 - (ping - s.goodPing) / (double) (s.maxPing - s.goodPing);
        w *= 1 - 0.5 * Math.min(1, jitter / s.maxJitter);
        w *= Math.min(1, Math.max(0, (tps - s.minTps) / Math.max(0.1, 19.5 - s.minTps)));
        return Math.max(0, Math.min(1, w));
    }

    /**
     * Ce que le client affiche au moment où il tourne la tête correspond à la position serveur
     * d'il y a environ ping + 150 ms (aller-retour + interpolation des entités).
     */
    public static int delayTicks(Profile p) {
        return Math.max(0, Math.min(40, (int) Math.round((p.avgPing() + 150) / 50.0)));
    }

    public static String describe(Profile p) {
        return p.avgPing() + " ms ±" + Math.round(p.jitter());
    }
}
