package fr.smpstart;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
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

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SmpStart plugin;
    private YamlConfiguration yml;

    Texts(SmpStart plugin) {
        this.plugin = plugin;
    }

    void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        yml = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                // les clés absentes du fichier du serveur reprennent le texte par défaut
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

    /** Texte sans italique (utile pour les objets). */
    public Component get(String key, TagResolver... r) {
        return parse(raw(key), r);
    }

    public Component parse(String miniMessage, TagResolver... r) {
        return MM.deserialize(miniMessage, r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> list(String key, TagResolver... r) {
        List<Component> out = new ArrayList<>();
        for (String s : yml.getStringList(key)) out.add(parse(s, r));
        return out;
    }

    public Component prefix() {
        return MM.deserialize(raw("prefix"));
    }

    public void send(CommandSender to, String key, TagResolver... r) {
        String s = raw(key);
        if (s.isEmpty()) return;
        to.sendMessage(prefix().append(MM.deserialize(s, r)));
    }

    /** Envoie chaque ligne d'une liste, sans préfixe. */
    public void sendLines(CommandSender to, String key, TagResolver... r) {
        for (Component c : list(key, r)) to.sendMessage(c);
    }

    public static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver comp(String key, ComponentLike value) {
        return Placeholder.component(key, value);
    }
}
