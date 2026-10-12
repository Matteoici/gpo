package fr.smpshop;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Jetons d'événement (Halloween...) : une monnaie à part, gardée dans tokens.yml. */
public final class Tokens {

    private final SmpShop plugin;
    private final Map<UUID, Long> balances = new HashMap<>();
    private final File file;
    private boolean dirty;

    Tokens(SmpShop plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tokens.yml");
    }

    void load() {
        balances.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("balances");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                balances.put(UUID.fromString(k), s.getLong(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public long get(UUID id) {
        return balances.getOrDefault(id, 0L);
    }

    public void add(UUID id, long amount) {
        set(id, get(id) + amount);
    }

    public boolean take(UUID id, long amount) {
        if (amount <= 0) return true;
        long b = get(id);
        if (b < amount) return false;
        set(id, b - amount);
        return true;
    }

    public void set(UUID id, long amount) {
        if (amount <= 0) balances.remove(id);
        else balances.put(id, amount);
        dirty = true;
    }

    void save(boolean async) {
        if (!dirty && async) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (var e : balances.entrySet()) y.set("balances." + e.getKey(), e.getValue());
        String text = y.saveToString();
        Runnable w = () -> write(text);
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, w);
        else w.run();
    }

    private synchronized void write(String text) {
        try {
            File dir = file.getParentFile();
            if (!dir.exists()) dir.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Sauvegarde des jetons impossible : " + e.getMessage());
        }
    }
}
