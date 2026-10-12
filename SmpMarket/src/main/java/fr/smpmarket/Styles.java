package fr.smpmarket;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Styles que les locataires peuvent choisir pour leur stand (config.yml > customization) :
 * couleur du nom (thème), fond du panneau et des étiquettes, symbole devant le nom.
 */
public final class Styles {

    public record Theme(String id, String name, Material icon, String format) {
    }

    public record Background(String id, String name, Material icon, Color color) {
    }

    public record Symbol(String id, String symbol) {
    }

    private final List<Theme> themes = new ArrayList<>();
    private final List<Background> backgrounds = new ArrayList<>();
    private final List<Symbol> symbols = new ArrayList<>();

    void load(FileConfiguration c) {
        themes.clear();
        backgrounds.clear();
        symbols.clear();
        ConfigurationSection t = section(c, "customization.themes");
        if (t != null) {
            for (String id : t.getKeys(false)) {
                ConfigurationSection s = t.getConfigurationSection(id);
                if (s == null) continue;
                Material m = Material.matchMaterial(s.getString("icon", "WHITE_DYE"));
                themes.add(new Theme(id, s.getString("name", id), m == null ? Material.WHITE_DYE : m, s.getString("format", "<white>")));
            }
        }
        if (themes.isEmpty()) themes.add(new Theme("or", "Or", Material.YELLOW_DYE, "<gradient:#ffd36b:#ff8a3d>"));
        ConfigurationSection b = section(c, "customization.backgrounds");
        if (b != null) {
            for (String id : b.getKeys(false)) {
                ConfigurationSection s = b.getConfigurationSection(id);
                if (s == null) continue;
                Material m = Material.matchMaterial(s.getString("icon", "BLACK_STAINED_GLASS_PANE"));
                backgrounds.add(new Background(id, s.getString("name", id), m == null ? Material.BLACK_STAINED_GLASS_PANE : m,
                        parseColor(s.getString("color", "#0a0a14"), s.getInt("opacity", 150))));
            }
        }
        if (backgrounds.isEmpty()) backgrounds.add(new Background("sombre", "Sombre", Material.BLACK_STAINED_GLASS_PANE,
                Color.fromARGB(150, 10, 10, 20)));
        for (String s : c.getStringList("customization.symbols")) {
            String v = s.equalsIgnoreCase("aucun") || s.equalsIgnoreCase("none") ? "" : s;
            symbols.add(new Symbol(s, v));
        }
        if (symbols.isEmpty()) symbols.add(new Symbol("aucun", ""));
    }

    /** Section du config.yml du serveur, ou celle par défaut du plugin si elle n'y est pas encore (ancien fichier). */
    private static ConfigurationSection section(FileConfiguration c, String path) {
        ConfigurationSection s = c.isConfigurationSection(path) ? c.getConfigurationSection(path) : null;
        if ((s == null || s.getKeys(false).isEmpty()) && c.getDefaults() != null) s = c.getDefaults().getConfigurationSection(path);
        return s;
    }

    private static Color parseColor(String hex, int opacity) {
        try {
            int rgb = Integer.parseInt(hex.replace("#", ""), 16);
            return Color.fromARGB(Math.max(0, Math.min(255, opacity)), (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
        } catch (Exception e) {
            return Color.fromARGB(150, 10, 10, 20);
        }
    }

    public List<Theme> themes() {
        return themes;
    }

    public List<Background> backgrounds() {
        return backgrounds;
    }

    public List<Symbol> symbols() {
        return symbols;
    }

    public Theme theme(String id) {
        for (Theme t : themes) if (t.id().equalsIgnoreCase(String.valueOf(id))) return t;
        return themes.getFirst();
    }

    public Background background(String id) {
        for (Background b : backgrounds) if (b.id().equalsIgnoreCase(String.valueOf(id))) return b;
        return backgrounds.getFirst();
    }

    public String symbol(String id) {
        if (id == null) return symbols.getFirst().symbol();
        for (Symbol s : symbols) if (s.id().equals(id)) return s.symbol();
        return symbols.getFirst().symbol();
    }

    /** Texte coloré avec le thème (le texte du joueur est protégé : aucune balise ne passe). */
    public Component styled(String themeId, String text, boolean bold) {
        Theme t = theme(themeId);
        String safe = Texts.MM.escapeTags(text == null ? "" : text);
        return Texts.parse(t.format() + (bold ? "<b>" : "") + safe);
    }
}
