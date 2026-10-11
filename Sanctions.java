package fr.smpadmin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Toutes les sanctions (sanctions.yml). Les méthodes sont synchronisées car la vérification
 * des bannis se fait pendant la connexion, hors du thread principal.
 */
public final class Sanctions {

    private final SmpAdmin plugin;
    private final File file;
    private final Map<Integer, Punishment> all = new LinkedHashMap<>();
    private int nextId = 1;

    Sanctions(SmpAdmin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "sanctions.yml");
    }

    synchronized void load() {
        all.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, y.getInt("next-id", 1));
        ConfigurationSection sec = y.getConfigurationSection("sanctions");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(k);
            if (s == null) continue;
            try {
                int id = Integer.parseInt(k);
                Punishment p = new Punishment(id, Punishment.Type.valueOf(s.getString("type", "WARN")),
                        UUID.fromString(s.getString("target", "")), s.getString("name", "?"), s.getString("staff", "?"),
                        s.getString("reason", "?"), s.getLong("created"), s.getLong("duration", -1));
                p.revoked = s.getBoolean("revoked", false);
                p.revokedBy = s.getString("revoked-by");
                p.revokedAt = s.getLong("revoked-at", 0);
                all.put(id, p);
                nextId = Math.max(nextId, id + 1);
            } catch (Exception e) {
                plugin.getLogger().warning("Sanction illisible #" + k + " : " + e.getMessage());
            }
        }
    }

    synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Punishment p : all.values()) {
            String b = "sanctions." + p.id + ".";
            y.set(b + "type", p.type.name());
            y.set(b + "target", p.target.toString());
            y.set(b + "name", p.targetName);
            y.set(b + "staff", p.staff);
            y.set(b + "reason", p.reason);
            y.set(b + "created", p.created);
            y.set(b + "duration", p.duration);
            if (p.revoked) {
                y.set(b + "revoked", true);
                y.set(b + "revoked-by", p.revokedBy);
                y.set(b + "revoked-at", p.revokedAt);
            }
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible de sauvegarder sanctions.yml : " + e.getMessage());
        }
    }

    public synchronized Punishment add(Punishment.Type type, UUID target, String name, String staff, String reason, long duration) {
        Punishment p = new Punishment(nextId++, type, target, name, staff, reason, System.currentTimeMillis(),
                type == Punishment.Type.BAN || type == Punishment.Type.MUTE ? duration : -1);
        all.put(p.id, p);
        save();
        return p;
    }

    public synchronized void revoke(Punishment p, String by) {
        p.revoked = true;
        p.revokedBy = by;
        p.revokedAt = System.currentTimeMillis();
        save();
    }

    public synchronized Punishment activeBan(UUID id) {
        return active(id, Punishment.Type.BAN);
    }

    public synchronized Punishment activeMute(UUID id) {
        return active(id, Punishment.Type.MUTE);
    }

    private Punishment active(UUID id, Punishment.Type type) {
        long now = System.currentTimeMillis();
        Punishment best = null;
        for (Punishment p : all.values()) {
            if (p.type == type && p.target.equals(id) && p.isActive(now)) best = p;
        }
        return best;
    }

    public synchronized List<Punishment> activeBans() {
        long now = System.currentTimeMillis();
        List<Punishment> out = new ArrayList<>();
        for (Punishment p : all.values()) if (p.type == Punishment.Type.BAN && p.isActive(now)) out.add(p);
        out.sort(Comparator.comparingLong((Punishment p) -> p.created).reversed());
        return out;
    }

    /** Sanctions d'un joueur, de la plus récente à la plus ancienne. */
    public synchronized List<Punishment> history(UUID id) {
        List<Punishment> out = new ArrayList<>();
        for (Punishment p : all.values()) if (p.target.equals(id)) out.add(p);
        out.sort(Comparator.comparingLong((Punishment p) -> p.created).reversed());
        return out;
    }

    public synchronized int count(UUID id, Punishment.Type type) {
        int n = 0;
        for (Punishment p : all.values()) if (p.target.equals(id) && (type == null || p.type == type)) n++;
        return n;
    }
}
