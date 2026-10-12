package fr.smpdrop;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Planning des largages, statistiques et sauvegarde (data.yml). */
public final class DropManager {

    private final SmpDrop plugin;
    private final File file;
    private YamlConfiguration data;
    private DropEvent current;
    private long nextDrop;
    private final Set<Integer> warned = new HashSet<>();
    private final Map<UUID, Integer> wins = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    /** joueurs qui veulent toujours être prévenus (titre, sons, flèche) */
    private final Set<UUID> alwaysNotify = new HashSet<>();

    public boolean alwaysNotify(UUID id) {
        return alwaysNotify.contains(id);
    }

    /** true = activé */
    public boolean toggleNotify(UUID id) {
        boolean on = !alwaysNotify.remove(id);
        if (on) alwaysNotify.add(id);
        save();
        return on;
    }

    DropManager(SmpDrop plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        data = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();
        nextDrop = data.getLong("next-drop", 0);
        if (nextDrop <= now) nextDrop = now + Math.max(plugin.settings().startupDelayMs, 60_000L);
        // n'annonce pas d'un coup les avertissements déjà dépassés
        for (int w : plugin.settings().warningsMinutes) if (nextDrop - now <= w * 60_000L) warned.add(w);
        ConfigurationSection st = data.getConfigurationSection("stats");
        if (st != null) {
            for (String k : st.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    wins.put(id, st.getInt(k + ".opened"));
                    names.put(id, st.getString(k + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (String s : data.getStringList("notify")) {
            try {
                alwaysNotify.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        cleanLeftoverBarrier();
    }

    void save() {
        data.set("next-drop", nextDrop);
        data.set("notify", alwaysNotify.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, Integer> e : wins.entrySet()) {
            data.set("stats." + e.getKey() + ".opened", e.getValue());
            data.set("stats." + e.getKey() + ".name", names.get(e.getKey()));
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible de sauvegarder data.yml : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ planning (appelé chaque seconde)

    void tickSecond() {
        if (current != null) return;
        Settings s = plugin.settings();
        long now = System.currentTimeMillis();
        long rem = nextDrop - now;
        for (int w : s.warningsMinutes) {
            if (warned.contains(w) || rem > w * 60_000L) continue;
            warned.add(w);
            if (Bukkit.getOnlinePlayers().size() >= s.minPlayers) {
                for (Player p : Bukkit.getOnlinePlayers()) plugin.texts().send(p, "warning", Texts.ph("time", w + " min"));
            }
        }
        if (rem > 0) return;
        if (Bukkit.getOnlinePlayers().size() < s.minPlayers) {
            nextDrop = now + s.retryMs;
            warned.clear();
            for (int w : s.warningsMinutes) if (s.retryMs <= w * 60_000L) warned.add(w);
            save();
            return;
        }
        start(false);
    }

    /** Lance un largage à un endroit au hasard. */
    boolean start(boolean instant) {
        if (current != null) return false;
        current = new DropEvent(plugin);
        reschedule();
        current.startRandom(instant);
        return true;
    }

    boolean startAt(Location loc, int seconds) {
        if (current != null) return false;
        current = new DropEvent(plugin);
        reschedule();
        current.startAt(loc, seconds);
        return true;
    }

    private void reschedule() {
        nextDrop = System.currentTimeMillis() + plugin.settings().intervalMs;
        warned.clear();
        save();
    }

    void setNext(long ms) {
        nextDrop = System.currentTimeMillis() + ms;
        warned.clear();
        for (int w : plugin.settings().warningsMinutes) if (ms <= w * 60_000L) warned.add(w);
        save();
    }

    void ended(DropEvent e) {
        if (current == e) current = null;
    }

    void stopAll() {
        if (current != null) current.end();
        current = null;
    }

    public DropEvent current() {
        return current;
    }

    public long nextDrop() {
        return nextDrop;
    }

    // ------------------------------------------------------------ statistiques

    void addWin(Player p) {
        wins.merge(p.getUniqueId(), 1, Integer::sum);
        names.put(p.getUniqueId(), p.getName());
        save();
    }

    public int wins(UUID id) {
        return wins.getOrDefault(id, 0);
    }

    List<Map.Entry<String, Integer>> top(int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : wins.entrySet()) list.add(Map.entry(names.getOrDefault(e.getKey(), "?"), e.getValue()));
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.subList(0, Math.min(n, list.size()));
    }

    // ------------------------------------------------------------ barrière (si le serveur s'arrête en plein largage)

    void rememberBarrier(Block b, BlockData old) {
        data.set("barrier.world", b.getWorld().getName());
        data.set("barrier.x", b.getX());
        data.set("barrier.y", b.getY());
        data.set("barrier.z", b.getZ());
        data.set("barrier.old", old.getAsString());
        save();
    }

    void forgetBarrier() {
        if (data.contains("barrier")) {
            data.set("barrier", null);
            save();
        }
    }

    private void cleanLeftoverBarrier() {
        if (!data.contains("barrier.world")) return;
        World w = Bukkit.getWorld(data.getString("barrier.world", ""));
        if (w != null) {
            Block b = w.getBlockAt(data.getInt("barrier.x"), data.getInt("barrier.y"), data.getInt("barrier.z"));
            if (b.getType() == Material.BARRIER) {
                BlockData old;
                try {
                    old = Bukkit.createBlockData(data.getString("barrier.old", "minecraft:air"));
                } catch (IllegalArgumentException e) {
                    old = Material.AIR.createBlockData();
                }
                b.setBlockData(old, false);
            }
        }
        forgetBarrier();
    }
}
