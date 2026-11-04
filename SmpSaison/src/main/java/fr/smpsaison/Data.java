package fr.smpsaison;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Tout ce que le plugin retient sur les joueurs (data.yml). */
final class Data {

    static final class P {
        final UUID id;
        String name = "?";
        /** métier -> [niveau, xp dans le niveau] */
        final Map<String, double[]> jobs = new HashMap<>();
        /** métier -> nombre de prestiges */
        final Map<String, Integer> prestige = new HashMap<>();
        long seasonXp;
        int seasonNumber;
        boolean premium;
        final Set<Integer> claimedFree = new LinkedHashSet<>(), claimedPremium = new LinkedHashSet<>();
        String lastLoginDay = "";
        /** classement -> valeur de la semaine */
        final Map<String, Double> weekly = new HashMap<>();
        /** récompenses en attente (joueur hors ligne) */
        final List<String> pending = new ArrayList<>();

        P(UUID id) {
            this.id = id;
        }

        double[] job(String j) {
            return jobs.computeIfAbsent(j, k -> new double[]{1, 0});
        }

        int level(String j) {
            return (int) job(j)[0];
        }
    }

    private final File file;
    private final Logger log;
    private final Map<UUID, P> players = new LinkedHashMap<>();
    String week = "";
    boolean dirty;

    Data(File folder, Logger log) {
        this.file = new File(folder, "data.yml");
        this.log = log;
    }

    P of(UUID id) {
        return players.computeIfAbsent(id, P::new);
    }

    P peek(UUID id) {
        return players.get(id);
    }

    Collection<P> all() {
        return players.values();
    }

    void load() {
        players.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        week = y.getString("week", "");
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) return;
        for (String k : ps.getKeys(false)) {
            try {
                ConfigurationSection s = ps.getConfigurationSection(k);
                P p = new P(UUID.fromString(k));
                p.name = s.getString("name", "?");
                ConfigurationSection j = s.getConfigurationSection("jobs");
                if (j != null) for (String job : j.getKeys(false)) {
                    p.jobs.put(job, new double[]{j.getInt(job + ".level", 1), j.getDouble(job + ".xp", 0)});
                }
                ConfigurationSection pr = s.getConfigurationSection("prestige");
                if (pr != null) for (String job : pr.getKeys(false)) p.prestige.put(job, pr.getInt(job));
                p.seasonXp = s.getLong("season.xp");
                p.seasonNumber = s.getInt("season.number");
                p.premium = s.getBoolean("season.premium");
                p.claimedFree.addAll(s.getIntegerList("season.free"));
                p.claimedPremium.addAll(s.getIntegerList("season.premium-claimed"));
                p.lastLoginDay = s.getString("login-day", "");
                ConfigurationSection w = s.getConfigurationSection("weekly");
                if (w != null) for (String b : w.getKeys(false)) p.weekly.put(b, w.getDouble(b));
                p.pending.addAll(s.getStringList("pending"));
                players.put(p.id, p);
            } catch (RuntimeException e) {
                log.warning("data.yml : joueur " + k + " ignoré (" + e.getMessage() + ")");
            }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("week", week);
        for (P p : players.values()) {
            String b = "players." + p.id + ".";
            y.set(b + "name", p.name);
            for (Map.Entry<String, double[]> e : p.jobs.entrySet()) {
                y.set(b + "jobs." + e.getKey() + ".level", (int) e.getValue()[0]);
                y.set(b + "jobs." + e.getKey() + ".xp", Math.round(e.getValue()[1] * 100) / 100.0);
            }
            for (Map.Entry<String, Integer> e : p.prestige.entrySet()) y.set(b + "prestige." + e.getKey(), e.getValue());
            y.set(b + "season.xp", p.seasonXp);
            y.set(b + "season.number", p.seasonNumber);
            y.set(b + "season.premium", p.premium);
            y.set(b + "season.free", new ArrayList<>(p.claimedFree));
            y.set(b + "season.premium-claimed", new ArrayList<>(p.claimedPremium));
            y.set(b + "login-day", p.lastLoginDay);
            for (Map.Entry<String, Double> e : p.weekly.entrySet()) y.set(b + "weekly." + e.getKey(), Math.round(e.getValue() * 100) / 100.0);
            if (!p.pending.isEmpty()) y.set(b + "pending", p.pending);
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            log.warning("data.yml : " + e.getMessage());
        }
    }
}
