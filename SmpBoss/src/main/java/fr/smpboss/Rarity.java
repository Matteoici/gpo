package fr.smpboss;

import java.util.Locale;

/** Raretés du butin de boss. Couleurs : blanc, bleu, violet, arc-en-ciel, noir et blanc. */
public enum Rarity {
    COMMON("<white>", "<gray>Commun", "WHITE"),
    RARE("<#4aa8ff>", "<#4aa8ff><b>RARE", "BLUE"),
    EPIC("<#b45cff>", "<#b45cff><b>ÉPIQUE", "PURPLE"),
    LEGENDARY("<rainbow>", "<rainbow><b>✦ LÉGENDAIRE ✦", "RAINBOW"),
    SECRET("<gradient:#ffffff:#6b6b6b:#ffffff>", "<gradient:#ffffff:#4a4a4a:#ffffff><b>✧ SECRET ✧", "SECRET");

    /** Couleur MiniMessage à placer devant le nom. */
    public final String color;
    /** Étiquette affichée dans la description. */
    public final String tag;
    public final String frame;

    Rarity(String color, String tag, String frame) {
        this.color = color;
        this.tag = tag;
        this.frame = frame;
    }

    public static Rarity of(String s) {
        if (s == null) return COMMON;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "rare" -> RARE;
            case "epic", "epique", "épique" -> EPIC;
            case "legendary", "legendaire", "légendaire" -> LEGENDARY;
            case "secret" -> SECRET;
            default -> COMMON;
        };
    }

    /** Nom coloré ; l'arc-en-ciel peut être décalé (animation) avec phase 0..9. */
    public String name(String text, int phase) {
        String safe = SmpBoss.MM.escapeTags(text);
        return switch (this) {
            case LEGENDARY -> "<rainbow:" + phase + "><b>" + safe;
            case SECRET -> (phase % 2 == 0 ? "<gradient:#ffffff:#5a5a5a:#ffffff>" : "<gradient:#5a5a5a:#ffffff:#5a5a5a>")
                    + "<b>" + safe;
            default -> color + "<b>" + safe;
        };
    }
}
