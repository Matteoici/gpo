package fr.smpfriends;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.World;

import java.util.Locale;

/** Petits utilitaires d'affichage. */
public final class Text {

    public static final TextColor ACCENT = TextColor.color(0x7FD7FF);
    public static final TextColor PREFIX_COLOR = TextColor.color(0xFF8AD8);

    private Text() {
    }

    /** Texte sans l'italique automatique des noms d'items. */
    public static Component t(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** « Libellé : valeur » pour les descriptions d'items. */
    public static Component line(String label, String value) {
        return t(label + " ", NamedTextColor.GRAY).append(t(value, NamedTextColor.WHITE));
    }

    public static Component prefix() {
        return Component.text("Amis ", PREFIX_COLOR).decorate(TextDecoration.BOLD)
                .append(Component.text("» ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false));
    }

    public static Component msg(String s, TextColor c) {
        return prefix().append(Component.text(s, c));
    }

    public static Component button(String label, TextColor color, String command, String hover) {
        return Component.text(label, color).decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    public static String money(double v, String symbol) {
        double a = Math.abs(v);
        String s;
        if (a >= 1_000_000_000) s = String.format(Locale.ROOT, "%.2fB", v / 1_000_000_000);
        else if (a >= 1_000_000) s = String.format(Locale.ROOT, "%.2fM", v / 1_000_000);
        else if (a >= 10_000) s = String.format(Locale.ROOT, "%.1fK", v / 1_000);
        else s = String.format(Locale.ROOT, "%,.0f", v);
        return symbol + s;
    }

    /** Durée lisible : « 3 j 4 h », « 2 h 12 min », « 45 s ». */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + " j " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        if (m > 0) return m + " min";
        return s + " s";
    }

    public static String world(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }

    /** DIAMOND_PICKAXE -> Diamond Pickaxe */
    public static String pretty(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String part : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
