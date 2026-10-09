package fr.gpotrade;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Construit le titre de la fenêtre quand le joueur a le resource pack :
 * l'image de fond (glyphe ) + les pseudos écrits dans le bandeau du haut.
 *
 * Repère : x = 0 au début du titre (qui est à 8 px du bord gauche de la fenêtre).
 */
final class TitleArt {

    static final Key FONT = Key.key("gpotrade", "trade");
    private static final String BACKGROUND = "";
    /** Avance de l'image : largeur utile (176 px) + 1. */
    private static final int BG_ADVANCE = 177;
    private static final int NAME_MAX_WIDTH = 74;

    private TitleArt() {
    }

    static Component title(String left, String right) {
        TextComponent.Builder b = Component.text();
        b.append(glyphs(shift(-8) + BACKGROUND));
        int cursor = -8 + BG_ADVANCE;

        String l = fit(left);
        b.append(glyphs(shift(0 - cursor)));
        b.append(Component.text(l, NamedTextColor.WHITE));
        cursor = width(l);

        String r = fit(right);
        int rightStart = 160 - width(r); // se termine à 8 px du bord droit
        b.append(glyphs(shift(rightStart - cursor)));
        b.append(Component.text(r, NamedTextColor.WHITE));
        return b.build();
    }

    private static Component glyphs(String s) {
        return Component.text(s, NamedTextColor.WHITE).font(FONT);
    }

    /** Décalage horizontal de px pixels avec les espaces du pack (.. = -1,-2,-4… ; .. = +1,+2,+4…). */
    static String shift(int px) {
        StringBuilder sb = new StringBuilder();
        int base = px < 0 ? 0xF801 : 0xF821;
        int n = Math.abs(px);
        for (int bit = 8; bit >= 0; bit--) {
            while (n >= (1 << bit)) {
                sb.append((char) (base + bit));
                n -= 1 << bit;
            }
        }
        return sb.toString();
    }

    private static String fit(String name) {
        if (width(name) <= NAME_MAX_WIDTH) return name;
        String s = name;
        while (!s.isEmpty() && width(s + "..") > NAME_MAX_WIDTH) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    /** Largeur (avance) d'un texte dans la police par défaut de Minecraft. */
    static int width(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += charWidth(c);
        return w;
    }

    private static int charWidth(char c) {
        return switch (c) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case 'I', 't', '[', ']', ' ' -> 4;
            case 'f', 'k', '<', '>', '(', ')', '{', '}', '*', '"' -> 5;
            default -> 6;
        };
    }
}
