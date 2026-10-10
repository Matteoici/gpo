package fr.pausemenu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fabrique les fichiers du datapack : des "dialogues" Minecraft (menus natifs du jeu).
 * Retourne une table chemin relatif -> contenu.
 */
final class Dialogs {

    static final String NAMESPACE = "pausemenu";
    private static final Pattern NAMED = Pattern.compile("[a-z_]+");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private Dialogs() {}

    static Map<String, String> build(Model.Settings s, List<Model.Category> categories) {
        Map<String, String> files = new LinkedHashMap<>();
        List<Model.Category> usable = new ArrayList<>();
        for (Model.Category c : categories) if (!c.entries().isEmpty()) usable.add(c);

        files.put("pack.mcmeta", "{\"pack\":{\"description\":\"PauseMenu - boutons du menu pause\","
                + "\"min_format\":[80,0],\"max_format\":[999,0]}}\n");

        boolean any = !usable.isEmpty();
        if (any) {
            files.put("data/" + NAMESPACE + "/dialog/main.json", main(s, usable));
            if (!s.flat()) {
                for (Model.Category c : usable) {
                    files.put("data/" + NAMESPACE + "/dialog/" + id(c.key()) + ".json", category(s, c));
                }
            }
        }
        String tag = "{\"replace\":false,\"values\":" + (any ? "[\"" + NAMESPACE + ":main\"]" : "[]") + "}\n";
        files.put("data/minecraft/tags/dialog/pause_screen_additions.json", tag);
        files.put("data/minecraft/tags/dialog/quick_actions.json", s.quickActions() ? tag : "{\"replace\":false,\"values\":[]}\n");
        return files;
    }

    // ------------------------------------------------------------------ dialogues

    private static String main(Model.Settings s, List<Model.Category> cats) {
        List<String> actions = new ArrayList<>();
        if (s.flat()) {
            for (Model.Category c : cats) for (Model.Entry e : c.entries()) actions.add(commandButton(s, e, c.color()));
        } else {
            for (Model.Category c : cats) {
                actions.add(button(s, text(c.name(), c.color(), true), text(c.description(), "gray", false),
                        "{\"type\":\"minecraft:show_dialog\",\"dialog\":\"" + NAMESPACE + ":" + id(c.key()) + "\"}"));
            }
        }
        return "{\"type\":\"minecraft:multi_action\","
                + "\"title\":" + text(s.title(), s.titleColor(), true) + ","
                + "\"external_title\":" + text(s.buttonLabel(), s.titleColor(), false) + ","
                + "\"columns\":" + s.columns() + ","
                + "\"actions\":[" + String.join(",", actions) + "],"
                + "\"exit_action\":{\"label\":" + text("Fermer", "red", false) + ",\"width\":" + s.buttonWidth() + "}}\n";
    }

    private static String category(Model.Settings s, Model.Category c) {
        List<String> actions = new ArrayList<>();
        for (Model.Entry e : c.entries()) actions.add(commandButton(s, e, c.color()));
        return "{\"type\":\"minecraft:multi_action\","
                + "\"title\":" + text(c.name(), c.color(), true) + ","
                + "\"columns\":" + s.columns() + ","
                + "\"actions\":[" + String.join(",", actions) + "],"
                + "\"exit_action\":{\"label\":" + text("← Retour", "gray", false) + ",\"width\":" + s.buttonWidth()
                + ",\"action\":{\"type\":\"minecraft:show_dialog\",\"dialog\":\"" + NAMESPACE + ":main\"}}}\n";
    }

    private static String commandButton(Model.Settings s, Model.Entry e, String color) {
        String cmd = e.command().startsWith("/") ? e.command().substring(1) : e.command();
        String action = e.suggest()
                ? "{\"type\":\"minecraft:suggest_command\",\"command\":\"" + esc("/" + cmd) + "\"}"
                : "{\"type\":\"minecraft:run_command\",\"command\":\"" + esc(cmd) + "\"}";
        return button(s, text(e.name(), color, false), text(e.description(), "gray", false), action);
    }

    private static String button(Model.Settings s, String label, String tooltip, String action) {
        return "{\"label\":" + label + ",\"tooltip\":" + tooltip + ",\"width\":" + s.buttonWidth() + ",\"action\":" + action + "}";
    }

    // ------------------------------------------------------------------ utilitaires

    /** Composant texte JSON. */
    static String text(String value, String color, boolean bold) {
        return "{\"text\":\"" + esc(value) + "\",\"color\":\"" + color(color) + "\""
                + (bold ? ",\"bold\":true" : "") + "}";
    }

    static String color(String c) {
        if (c == null) return "white";
        String t = c.trim();
        if (HEX.matcher(t).matches()) return t.toLowerCase(Locale.ROOT);
        t = t.toLowerCase(Locale.ROOT);
        return NAMED.matcher(t).matches() ? t : "white";
    }

    /** Identifiant valide pour un fichier de datapack. */
    static String id(String key) {
        String k = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return "cat_" + k;
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
                }
            }
        }
        return b.toString();
    }
}
