package fr.smpparkour;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Un parcours : départ, checkpoints, arrivée, blocs posés par le générateur, meilleurs temps. */
final class Course {

    final String name;
    Location start, finish, board;
    final List<Location> checkpoints = new ArrayList<>();
    /** Blocs posés par le générateur ("world;x;y;z" -> ancien bloc), pour pouvoir le démonter. */
    final Map<String, String> placed = new LinkedHashMap<>();
    /** Étiquettes flottantes : {position, texte MiniMessage}. */
    final List<String[]> labels = new ArrayList<>();
    /** Meilleur temps (ms) de chaque joueur, et son pseudo. */
    final Map<UUID, Long> best = new HashMap<>();
    final Map<UUID, String> names = new HashMap<>();
    /** Dernière récompense "every-completion" (ms). */
    final Map<UUID, Long> rewarded = new HashMap<>();

    Course(String name) {
        this.name = name;
    }

    private Double minY;

    /** Point le plus bas du parcours (pour ne pas renvoyer au checkpoint un joueur qui descend normalement). */
    double minY() {
        if (minY != null) return minY;
        double m = Double.MAX_VALUE;
        for (Location l : checkpoints) m = Math.min(m, l.getY());
        if (start != null) m = Math.min(m, start.getY());
        if (finish != null) m = Math.min(m, finish.getY());
        for (String k : placed.keySet()) {
            String[] p = k.split(";");
            if (p.length == 4) {
                try {
                    m = Math.min(m, Integer.parseInt(p[2]) + 1);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        minY = m == Double.MAX_VALUE ? 0 : m;
        return minY;
    }

    void invalidate() {
        minY = null;
    }

    boolean ready() {
        return start != null && finish != null;
    }

    static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    /** Le joueur est-il sur ce point (plaque à ses pieds, ou bloc juste dessous) ? */
    static boolean on(Location feet, Location spot) {
        if (spot == null || feet.getWorld() != spot.getWorld()) return false;
        int x = feet.getBlockX(), y = feet.getBlockY(), z = feet.getBlockZ();
        return x == spot.getBlockX() && z == spot.getBlockZ() && (y == spot.getBlockY() || y - 1 == spot.getBlockY());
    }

    /** Top N (UUID, temps) du plus rapide au plus lent. */
    List<Map.Entry<UUID, Long>> top(int n) {
        List<Map.Entry<UUID, Long>> l = new ArrayList<>(best.entrySet());
        l.sort(Map.Entry.comparingByValue());
        return l.subList(0, Math.min(n, l.size()));
    }

    // ================================================================= fichier

    void save(ConfigurationSection s) {
        s.set("start", loc(start));
        s.set("finish", loc(finish));
        s.set("board", loc(board));
        List<String> cps = new ArrayList<>();
        for (Location l : checkpoints) cps.add(loc(l));
        s.set("checkpoints", cps);
        s.set("placed", null);
        for (var e : placed.entrySet()) s.set("placed." + e.getKey().replace('.', '_'), e.getValue());
        List<Map<String, String>> ls = new ArrayList<>();
        for (String[] l : labels) ls.add(Map.of("at", l[0], "text", l[1]));
        s.set("labels", ls);
        s.set("best", null);
        for (var e : best.entrySet()) {
            s.set("best." + e.getKey() + ".ms", e.getValue());
            s.set("best." + e.getKey() + ".name", names.getOrDefault(e.getKey(), "?"));
        }
        s.set("rewarded", null);
        for (var e : rewarded.entrySet()) s.set("rewarded." + e.getKey(), e.getValue());
    }

    static Course load(String name, ConfigurationSection s) {
        Course c = new Course(name);
        c.start = loc(s.getString("start"));
        c.finish = loc(s.getString("finish"));
        c.board = loc(s.getString("board"));
        for (String l : s.getStringList("checkpoints")) {
            Location x = loc(l);
            if (x != null) c.checkpoints.add(x);
        }
        ConfigurationSection p = s.getConfigurationSection("placed");
        if (p != null) for (String k : p.getKeys(false)) c.placed.put(k, p.getString(k));
        for (Map<?, ?> m : s.getMapList("labels")) {
            Object at = m.get("at"), text = m.get("text");
            if (at != null && text != null) c.labels.add(new String[]{at.toString(), text.toString()});
        }
        ConfigurationSection b = s.getConfigurationSection("best");
        if (b != null) {
            for (String k : b.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    c.best.put(id, b.getLong(k + ".ms"));
                    c.names.put(id, b.getString(k + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection r = s.getConfigurationSection("rewarded");
        if (r != null) {
            for (String k : r.getKeys(false)) {
                try {
                    c.rewarded.put(UUID.fromString(k), r.getLong(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return c;
    }

    static String loc(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    static Location loc(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null || p.length < 4) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0, p.length > 5 ? Float.parseFloat(p[5]) : 0);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
