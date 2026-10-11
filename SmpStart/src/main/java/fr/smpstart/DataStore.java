package fr.smpstart;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Sauvegarde de l'avancement des joueurs dans plugins/SmpStart/players.yml. */
public final class DataStore {

    private final SmpStart plugin;
    private final File file;
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private int counter;
    private boolean dirty;

    DataStore(SmpStart plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    void load() {
        data.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        counter = y.getInt("meta.count", 0);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) return;
        for (String key : ps.getKeys(false)) {
            ConfigurationSection s = ps.getConfigurationSection(key);
            if (s == null) continue;
            try {
                PlayerData d = new PlayerData();
                d.first = s.getLong("first", System.currentTimeMillis());
                d.number = s.getInt("number", 0);
                d.protectionSec = s.getInt("protection", 0);
                d.step = s.getInt("step", 0);
                d.progress = s.getInt("progress", 0);
                d.finished = s.getBoolean("finished", false);
                d.kitGiven = s.getBoolean("kit", false);
                d.bar = s.getBoolean("bar", true);
                data.put(UUID.fromString(key), d);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Entrée illisible dans players.yml : " + key);
            }
        }
    }

    public PlayerData get(UUID id) {
        return data.get(id);
    }

    public PlayerData create(UUID id) {
        PlayerData d = new PlayerData();
        data.put(id, d);
        dirty = true;
        return d;
    }

    public void remove(UUID id) {
        data.remove(id);
        dirty = true;
    }

    public int nextNumber() {
        dirty = true;
        return ++counter;
    }

    public void markDirty() {
        dirty = true;
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        y.set("meta.count", counter);
        data.forEach((id, d) -> {
            String b = "players." + id;
            y.set(b + ".first", d.first);
            y.set(b + ".number", d.number);
            y.set(b + ".protection", d.protectionSec);
            y.set(b + ".step", d.step);
            y.set(b + ".progress", d.progress);
            y.set(b + ".finished", d.finished);
            y.set(b + ".kit", d.kitGiven);
            y.set(b + ".bar", d.bar);
        });
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Sauvegarde de players.yml impossible : " + e.getMessage());
        }
    }
}
