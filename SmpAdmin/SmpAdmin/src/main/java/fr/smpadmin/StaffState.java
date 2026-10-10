package fr.smpadmin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** État du staff conservé entre les redémarrages (data.yml) : vanish, gelés, suspects, chat bloqué. */
public final class StaffState {

    public record Suspect(UUID id, String name, String reason, String staff, long date) {
    }

    private final SmpAdmin plugin;
    private final File file;
    public final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    public final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    public final Set<UUID> staffChat = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Suspect> suspects = new ConcurrentHashMap<>();
    public volatile boolean chatLocked;

    StaffState(SmpAdmin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        vanished.clear();
        frozen.clear();
        suspects.clear();
        for (String s : y.getStringList("vanished")) parse(s, vanished);
        for (String s : y.getStringList("frozen")) parse(s, frozen);
        chatLocked = y.getBoolean("chat-locked", false);
        ConfigurationSection sec = y.getConfigurationSection("suspects");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    suspects.put(id, new Suspect(id, sec.getString(k + ".name", "?"), sec.getString(k + ".reason", "?"),
                            sec.getString(k + ".staff", "?"), sec.getLong(k + ".date")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private static void parse(String s, Set<UUID> into) {
        try {
            into.add(UUID.fromString(s));
        } catch (IllegalArgumentException ignored) {
        }
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("vanished", vanished.stream().map(UUID::toString).toList());
        y.set("frozen", frozen.stream().map(UUID::toString).toList());
        y.set("chat-locked", chatLocked);
        for (Suspect s : suspects.values()) {
            String b = "suspects." + s.id() + ".";
            y.set(b + "name", s.name());
            y.set(b + "reason", s.reason());
            y.set(b + "staff", s.staff());
            y.set(b + "date", s.date());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible de sauvegarder data.yml : " + e.getMessage());
        }
    }

    public Suspect suspect(UUID id) {
        return suspects.get(id);
    }

    public void addSuspect(Suspect s) {
        suspects.put(s.id(), s);
        save();
    }

    public boolean removeSuspect(UUID id) {
        boolean r = suspects.remove(id) != null;
        if (r) save();
        return r;
    }

    public List<Suspect> suspects() {
        List<Suspect> l = new ArrayList<>(suspects.values());
        l.sort(Comparator.comparingLong(Suspect::date).reversed());
        return l;
    }
}
