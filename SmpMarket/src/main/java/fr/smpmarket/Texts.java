package fr.smpmarket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Tous les textes du plugin, lus dans messages.yml (format MiniMessage). */
public final class Texts {

    public static final MiniMessage MM = MiniMessage.miniMessage();
    private final SmpMarket plugin;
    private YamlConfiguration yml;

    Texts(SmpMarket plugin) {
        this.plugin = plugin;
    }

    void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        yml = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                yml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("messages.yml par défaut illisible : " + e.getMessage());
        }
    }

    public String raw(String key) {
        String s = yml.getString(key);
        return s != null ? s : "<red>[" + key + "]";
    }

    public Component get(String key, TagResolver... r) {
        return parse(raw(key), r);
    }

    /** Texte MiniMessage sans italique (les noms d'objets sont en italique par défaut). */
    public static Component parse(String miniMessage, TagResolver... r) {
        return MM.deserialize(miniMessage, r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> list(String key, TagResolver... r) {
        List<Component> out = new ArrayList<>();
        for (String s : yml.getStringList(key)) out.add(parse(s, r));
        return out;
    }

    /**
     * Comme list(), mais une ligne qui vaut exactement "<nom>" est remplacée par plusieurs lignes
     * (ex. la liste des meilleurs objets d'une boutique dans une description d'objet).
     */
    public List<Component> listWith(String key, String marker, List<Component> insert, TagResolver... r) {
        List<Component> out = new ArrayList<>();
        for (String s : yml.getStringList(key)) {
            if (s.trim().equals("<" + marker + ">")) out.addAll(insert);
            else out.add(parse(s, r));
        }
        return out;
    }

    /** Plusieurs lignes réunies (pour les textes flottants). */
    public Component lines(String key, TagResolver... r) {
        List<Component> l = list(key, r);
        Component out = Component.empty();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(l.get(i));
        }
        return out;
    }

    public Component prefixed(String key, TagResolver... r) {
        return MM.deserialize(raw("prefix")).append(MM.deserialize(raw(key), r));
    }

    public void send(CommandSender to, String key, TagResolver... r) {
        if (raw(key).isEmpty()) return;
        to.sendMessage(prefixed(key, r));
    }

    public void sendLines(CommandSender to, String key, TagResolver... r) {
        for (Component c : list(key, r)) to.sendMessage(c);
    }

    /** Texte brut (les balises du joueur ne sont pas interprétées). */
    public static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** Texte qui contient lui-même du MiniMessage (noms de rareté...). */
    public static TagResolver pp(String key, String miniMessage) {
        return Placeholder.parsed(key, miniMessage);
    }

    public static TagResolver pc(String key, Component value) {
        return Placeholder.component(key, value);
    }
}
