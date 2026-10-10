package fr.smpguilds;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
import java.util.Locale;

/** Tous les textes du plugin, lus dans messages.yml (format MiniMessage). */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SmpGuilds plugin;
    private YamlConfiguration yml;

    Messages(SmpGuilds plugin) {
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

    /** Texte sans italique (utile pour les items). */
    public Component get(String key, TagResolver... r) {
        return MM.deserialize(raw(key), r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> list(String key, TagResolver... r) {
        List<Component> out = new ArrayList<>();
        for (String s : yml.getStringList(key)) {
            out.add(MM.deserialize(s, r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
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

    /** Bouton cliquable qui lance une commande. */
    public Component button(String key, String command, TagResolver... r) {
        return MM.deserialize(raw(key), r)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command)));
    }

    public Component parse(String miniMessage, TagResolver... r) {
        return MM.deserialize(miniMessage, r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------ placeholders

    public static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver comp(String key, ComponentLike value) {
        return Placeholder.component(key, value);
    }

    public static TagResolver guild(Guild g) {
        return TagResolver.resolver(
                Placeholder.unparsed("guild", g.name),
                Placeholder.component("tag", g.tagComponent()),
                Placeholder.component("gname", g.nameComponent()));
    }

    public static String money(double v) {
        if (v == Math.floor(v)) return String.format(Locale.ROOT, "%,.0f", v).replace(',', ' ');
        return String.format(Locale.ROOT, "%,.2f", v).replace(',', ' ');
    }

    public static String time(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        return m > 0 ? m + "m" + (s < 10 ? "0" : "") + s + "s" : s + "s";
    }
}
