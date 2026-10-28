package fr.smpevents.quest;

import fr.smpevents.SmpEvents;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Liste de toutes les quêtes possibles (quests.yml). */
public final class QuestPool {

    private final SmpEvents plugin;
    private final Map<String, QuestDef> all = new LinkedHashMap<>();

    public QuestPool(SmpEvents plugin) {
        this.plugin = plugin;
    }

    public void load() {
        all.clear();
        File f = new File(plugin.getDataFolder(), "quests.yml");
        if (!f.exists()) plugin.saveResource("quests.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        read(y.getConfigurationSection("daily"), false, null);
        read(y.getConfigurationSection("weekly"), true, null);
        // quêtes d'événement (Halloween...) : un fichier à part, actives seulement pendant l'événement
        for (String ev : List.of("halloween")) {
            File ef = new File(plugin.getDataFolder(), "quests-" + ev + ".yml");
            if (!ef.exists()) plugin.saveResource("quests-" + ev + ".yml", false);
            else update(ef, "quests-" + ev + ".yml");
            YamlConfiguration ey = YamlConfiguration.loadConfiguration(ef);
            read(ey.getConfigurationSection("daily"), false, ev);
            read(ey.getConfigurationSection("weekly"), true, ev);
        }
        plugin.getLogger().info(all.size() + " quête(s) chargée(s).");
    }

    /** Fichier déjà installé : ajoute les nouvelles quêtes du plugin (et leurs bonus) sans toucher aux réglages. */
    private void update(File f, String resource) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource(resource);
        if (in == null) return;
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        int have = y.getInt("version", 1), want = def.getInt("version", 1);
        if (have >= want) return;
        for (String part : List.of("daily", "weekly")) {
            ConfigurationSection d = def.getConfigurationSection(part);
            if (d == null) continue;
            for (String id : d.getKeys(false)) {
                String path = part + "." + id;
                if (!y.isConfigurationSection(path)) {
                    y.set(path, d.getConfigurationSection(id));
                } else if (!y.isSet(path + ".commands") && d.isSet(id + ".commands")) {
                    // récompense en plus (œuf, clé) ajoutée à une quête existante
                    y.set(path + ".commands", d.getStringList(id + ".commands"));
                    y.set(path + ".name", d.getString(id + ".name"));
                }
            }
        }
        y.set("version", want);
        if (resource.contains("halloween")) {
            var c = plugin.getConfig();
            if (c.getInt("quests.events.halloween.daily-slots", 2) == 2) c.set("quests.events.halloween.daily-slots", 3);
            if (c.getInt("quests.events.halloween.weekly-slots", 1) == 1) c.set("quests.events.halloween.weekly-slots", 2);
            plugin.saveConfig();
        }
        try {
            y.save(f);
            plugin.getLogger().info(resource + " mis à jour (nouvelles quêtes ajoutées).");
        } catch (java.io.IOException e) {
            plugin.getLogger().warning(resource + " : " + e.getMessage());
        }
    }

    private void read(ConfigurationSection root, boolean weekly, String event) {
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            String key = (weekly ? "w." : "d.") + (event == null ? "" : event + ".") + id;
            QuestType type;
            try {
                type = QuestType.valueOf(s.getString("type", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("quests.yml : type inconnu pour " + id + " (" + s.getString("type") + ")");
                continue;
            }
            Set<String> targets = new HashSet<>();
            for (String t : s.getStringList("targets")) targets.add(t.toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
            long amount = Math.max(1, s.getLong("amount", 1));
            Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
            if (icon == null || !icon.isItem()) icon = Material.PAPER;
            String tier = weekly ? "weekly" : event != null ? event : s.getString("tier", "easy").toLowerCase(Locale.ROOT);
            all.put(key, new QuestDef(key, weekly, tier, type, targets, amount, s.getDouble("reward", 0),
                    s.getStringList("commands"), icon, s.getString("name", id), event));
        }
    }

    public QuestDef get(String id) {
        return all.get(id);
    }

    public List<QuestDef> tier(String tier, boolean weekly) {
        List<QuestDef> out = new ArrayList<>();
        for (QuestDef d : all.values()) if (d.weekly() == weekly && d.tier().equals(tier)) out.add(d);
        return out;
    }

    /** Quêtes de la semaine "normales" (hors événement). */
    public List<QuestDef> weekly() {
        List<QuestDef> out = new ArrayList<>();
        for (QuestDef d : all.values()) if (d.weekly() && d.event() == null) out.add(d);
        return out;
    }

    /** Quêtes de la semaine d'un événement. */
    public List<QuestDef> eventWeekly(String event) {
        List<QuestDef> out = new ArrayList<>();
        for (QuestDef d : all.values()) if (d.weekly() && event.equals(d.event())) out.add(d);
        return out;
    }
}
