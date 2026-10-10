package fr.smpfriends;

import org.bukkit.Material;

/** Réglages de confidentialité que chaque joueur peut changer. */
public enum Setting {
    SHOW_LOCATION("Montrer ma position", "Tes amis voient ton monde et tes coordonnées.", Material.COMPASS),
    SHOW_MONEY("Montrer mon argent", "Tes amis voient ton solde.", Material.GOLD_INGOT),
    SHOW_STATS("Montrer mes stats", "Kills, morts, temps de jeu...", Material.EXPERIENCE_BOTTLE),
    ALLOW_REQUESTS("Accepter les demandes", "Les joueurs peuvent t'envoyer des demandes d'ami.", Material.WRITABLE_BOOK),
    NOTIFICATIONS("Notifications", "Être prévenu quand un ami se connecte ou part.", Material.BELL);

    public final String label;
    public final String description;
    public final Material icon;

    Setting(String label, String description, Material icon) {
        this.label = label;
        this.description = description;
        this.icon = icon;
    }
}
