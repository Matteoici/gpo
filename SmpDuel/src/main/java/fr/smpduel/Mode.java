package fr.smpduel;

import java.util.Locale;

/** Les trois sortes de duel. */
enum Mode {
    /** keepInventory : on ne perd rien */
    AMICAL("amical"),
    /** on perd tout son stuff, l'autre peut le ramasser */
    MORT("mort"),
    /** chacun mise des objets : le gagnant repart avec les deux mises (keepInventory pour le reste) */
    PARI("pari");

    final String id;

    Mode(String id) {
        this.id = id;
    }

    static Mode of(String s) {
        if (s == null) return null;
        String l = s.toLowerCase(Locale.ROOT);
        for (Mode m : values()) if (m.id.equals(l)) return m;
        return switch (l) {
            case "a", "ami", "friendly", "fun" -> AMICAL;
            case "m", "death", "a-mort", "amort" -> MORT;
            case "p", "paris", "mise", "bet", "wager" -> PARI;
            default -> null;
        };
    }

    boolean keepsInventory() {
        return this != MORT;
    }
}
