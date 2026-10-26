package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static fr.smpboss.SmpBoss.parse;

/**
 * Classement des guildes sur le boss : dégâts infligés au Roi, victoires, combats, membres venus.
 * La guilde de chaque joueur est lue dans SmpGuilds (sans dépendance : par réflexion).
 */
final class GuildTop {

    /** Une guilde telle que vue par SmpGuilds. */
    record G(String id, String name, String tag, String color) {
    }

    private static final class Stat {
        String name = "?", tag = "", color = "gold";
        double damage;
        int wins, fights, best;
        final Set<String> members = new HashSet<>();
    }

    private final SmpBoss plugin;
    private final File file;
    private final Map<String, Stat> stats = new HashMap<>();

    GuildTop(SmpBoss plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "guild-top.yml");
        load();
    }

    // ================================================================= SmpGuilds

    G guildOf(UUID player) {
        Plugin g = Bukkit.getPluginManager().getPlugin("SmpGuilds");
        if (g == null || !g.isEnabled()) return null;
        try {
            Object mgr = g.getClass().getMethod("guilds").invoke(g);
            Object guild = mgr.getClass().getMethod("of", UUID.class).invoke(mgr, player);
            if (guild == null) return null;
            Class<?> c = guild.getClass();
            Object id = c.getField("id").get(guild);
            Object name = c.getField("name").get(guild);
            Object tag = c.getField("tag").get(guild);
            Object color = c.getField("color").get(guild);
            return new G(String.valueOf(id), String.valueOf(name), tag == null ? "" : String.valueOf(tag),
                    color == null ? "gold" : String.valueOf(color).toLowerCase(Locale.ROOT));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // ================================================================= enregistrement

    /**
     * Fin d'un combat : les dégâts de chaque joueur vont à sa guilde. Renvoie le classement de CE combat
     * (guildes triées par dégâts) pour l'annonce.
     */
    List<Map.Entry<G, Double>> record(Map<UUID, Double> damage, boolean won) {
        Map<String, G> byId = new HashMap<>();
        Map<String, Double> fight = new HashMap<>();
        for (var e : damage.entrySet()) {
            G g = guildOf(e.getKey());
            if (g == null) continue;
            byId.put(g.id(), g);
            fight.merge(g.id(), e.getValue(), Double::sum);
            Stat s = stats.computeIfAbsent(g.id(), k -> new Stat());
            s.members.add(e.getKey().toString());
        }
        for (var e : fight.entrySet()) {
            G g = byId.get(e.getKey());
            Stat s = stats.get(e.getKey());
            s.name = g.name();
            s.tag = g.tag();
            s.color = g.color();
            s.damage += e.getValue();
            s.fights++;
            if (won) s.wins++;
            s.best = (int) Math.max(s.best, e.getValue());
        }
        save();
        List<Map.Entry<G, Double>> out = new ArrayList<>();
        for (var e : fight.entrySet()) out.add(Map.entry(byId.get(e.getKey()), e.getValue()));
        out.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return out;
    }

    /** Annonce des guildes d'un combat (fin du boss). */
    void announce(List<Map.Entry<G, Double>> fight, double total) {
        if (fight.isEmpty()) return;
        Bukkit.broadcast(parse(plugin.msg("guild-top.fight-header")));
        for (int i = 0; i < Math.min(3, fight.size()); i++) {
            var e = fight.get(i);
            Bukkit.broadcast(parse(plugin.msg("guild-top.fight-line").replace("<rank>", String.valueOf(i + 1))
                    .replace("<guild>", label(e.getKey().name(), e.getKey().tag(), e.getKey().color()))
                    .replace("<percent>", String.valueOf(Math.round(e.getValue() * 100 / Math.max(1, total))))));
        }
    }

    private static String label(String name, String tag, String color) {
        String c = color == null || color.isBlank() ? "gold" : color;
        String t = tag == null || tag.isBlank() ? "" : "<" + c + ">[" + SmpBoss.MM.escapeTags(tag) + "]</" + c + "> ";
        return t + "<white>" + SmpBoss.MM.escapeTags(name) + "</white>";
    }

    // ================================================================= /topguildes

    void show(CommandSender to) {
        List<Map.Entry<String, Stat>> list = new ArrayList<>(stats.entrySet());
        list.removeIf(e -> e.getValue().fights == 0);
        // d'abord les victoires, puis les dégâts
        list.sort((a, b) -> a.getValue().wins != b.getValue().wins ? Integer.compare(b.getValue().wins, a.getValue().wins)
                : Double.compare(b.getValue().damage, a.getValue().damage));
        to.sendMessage(parse(plugin.msg("guild-top.header")));
        if (list.isEmpty()) {
            to.sendMessage(parse(plugin.msg("guild-top.empty")));
            return;
        }
        String[] medals = {"<#ffd700>①", "<#c0c0c0>②", "<#cd7f32>③"};
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            Stat s = list.get(i).getValue();
            String rank = i < 3 ? medals[i] : "<gray>" + (i + 1) + ".";
            to.sendMessage(parse(plugin.msg("guild-top.line").replace("<rank>", rank)
                    .replace("<guild>", label(s.name, s.tag, s.color))
                    .replace("<wins>", String.valueOf(s.wins))
                    .replace("<fights>", String.valueOf(s.fights))
                    .replace("<damage>", fmt(s.damage))
                    .replace("<members>", String.valueOf(s.members.size()))));
        }
        if (to instanceof org.bukkit.entity.Player p) {
            G mine = guildOf(p.getUniqueId());
            if (mine != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (!list.get(i).getKey().equals(mine.id())) continue;
                    if (i >= 10) {
                        Stat s = list.get(i).getValue();
                        to.sendMessage(parse(plugin.msg("guild-top.mine").replace("<rank>", String.valueOf(i + 1))
                                .replace("<wins>", String.valueOf(s.wins)).replace("<damage>", fmt(s.damage))));
                    }
                    break;
                }
            }
        }
    }

    private static String fmt(double v) {
        if (v >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.ROOT, "%.1fk", v / 1_000);
        return String.valueOf(Math.round(v));
    }

    void reset() {
        stats.clear();
        save();
    }

    // ================================================================= fichier

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("guilds");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(id);
            if (c == null) continue;
            Stat s = new Stat();
            s.name = c.getString("name", "?");
            s.tag = c.getString("tag", "");
            s.color = c.getString("color", "gold");
            s.damage = c.getDouble("damage");
            s.wins = c.getInt("wins");
            s.fights = c.getInt("fights");
            s.best = c.getInt("best");
            s.members.addAll(c.getStringList("members"));
            stats.put(id, s);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : stats.entrySet()) {
            String k = "guilds." + e.getKey();
            Stat s = e.getValue();
            y.set(k + ".name", s.name);
            y.set(k + ".tag", s.tag);
            y.set(k + ".color", s.color);
            y.set(k + ".damage", s.damage);
            y.set(k + ".wins", s.wins);
            y.set(k + ".fights", s.fights);
            y.set(k + ".best", s.best);
            y.set(k + ".members", new ArrayList<>(s.members));
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Sauvegarde du classement des guildes impossible : " + ex.getMessage());
        }
    }
}
