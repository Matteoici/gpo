package fr.smpfriends;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stockage de tous les profils dans plugins/SmpFriends/data.yml (sauvegarde asynchrone). */
public final class FriendStore {

    private final FriendsPlugin plugin;
    private final File file;
    private final Map<UUID, Profile> profiles = new HashMap<>();

    FriendStore(FriendsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        profiles.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            Profile p = new Profile(id, s.getString("name", "?"));
            readIds(s.getStringList("friends"), p.friends);
            readIds(s.getStringList("favorites"), p.favorites);
            readIds(s.getStringList("requests"), p.requests);
            ConfigurationSection set = s.getConfigurationSection("settings");
            if (set != null) {
                for (Setting st : Setting.values()) {
                    if (set.contains(st.name())) p.set(st, set.getBoolean(st.name()));
                }
            }
            profiles.put(id, p);
        }
    }

    private static void readIds(List<String> raw, Set<UUID> into) {
        for (String s : raw) {
            try {
                into.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** Profil du joueur (créé s'il n'existe pas encore). */
    public Profile get(UUID id) {
        return profiles.computeIfAbsent(id, u -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            return new Profile(u, op.getName() != null ? op.getName() : "?");
        });
    }

    public Collection<Profile> all() {
        return profiles.values();
    }

    /** Sérialise sur le thread principal puis écrit le fichier en arrière-plan. */
    public void save() {
        String data = serialize();
        if (!plugin.isEnabled()) {
            write(data);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    void saveNow() {
        write(serialize());
    }

    private String serialize() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Profile p : profiles.values()) {
            String base = "players." + p.id;
            yml.set(base + ".name", p.name);
            yml.set(base + ".friends", ids(p.friends));
            yml.set(base + ".favorites", ids(p.favorites));
            yml.set(base + ".requests", ids(p.requests));
            for (Setting st : Setting.values()) {
                if (p.settings.containsKey(st)) yml.set(base + ".settings." + st.name(), p.settings.get(st));
            }
        }
        return yml.saveToString();
    }

    private static List<String> ids(Set<UUID> set) {
        List<String> out = new ArrayList<>();
        for (UUID u : set) out.add(u.toString());
        return out;
    }

    private synchronized void write(String data) {
        try {
            Files.writeString(file.toPath(), data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible de sauvegarder data.yml : " + e.getMessage());
        }
    }
}
