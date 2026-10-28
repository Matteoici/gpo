package fr.smpduel;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ELO + victoires / défaites par mode. */
final class Stats {

    static final class Entry {
        String name = "?";
        int elo = 1000, streak, best;
        final Map<Mode, int[]> wl = new HashMap<>(); // [victoires, défaites]

        int[] of(Mode m) {
            return wl.computeIfAbsent(m, k -> new int[2]);
        }

        int wins() {
            int n = 0;
            for (int[] v : wl.values()) n += v[0];
            return n;
        }

        int losses() {
            int n = 0;
            for (int[] v : wl.values()) n += v[1];
            return n;
        }
    }

    private final File file;
    private final Map<UUID, Entry> data = new HashMap<>();
    private boolean dirty;

    Stats(File file) {
        this.file = file;
        load();
    }

    Entry of(UUID id) {
        return data.computeIfAbsent(id, k -> new Entry());
    }

    Entry peek(UUID id) {
        return data.get(id);
    }

    /** Résultat d'un duel : renvoie les points gagnés par le vainqueur. */
    int record(UUID winner, String wName, UUID loser, String lName, Mode mode, int k) {
        Entry w = of(winner), l = of(loser);
        w.name = wName;
        l.name = lName;
        double expected = 1 / (1 + Math.pow(10, (l.elo - w.elo) / 400.0));
        int delta = Math.max(1, (int) Math.round(k * (1 - expected)));
        w.elo += delta;
        l.elo = Math.max(0, l.elo - delta);
        w.of(mode)[0]++;
        l.of(mode)[1]++;
        w.streak++;
        w.best = Math.max(w.best, w.streak);
        l.streak = 0;
        dirty = true;
        return delta;
    }

    List<Map.Entry<UUID, Entry>> top(int n) {
        List<Map.Entry<UUID, Entry>> all = new ArrayList<>(data.entrySet());
        all.removeIf(e -> e.getValue().wins() + e.getValue().losses() == 0);
        all.sort((a, b) -> b.getValue().elo - a.getValue().elo);
        return all.subList(0, Math.min(n, all.size()));
    }

    int rank(UUID id) {
        List<Map.Entry<UUID, Entry>> all = top(Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) if (all.get(i).getKey().equals(id)) return i + 1;
        return 0;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            try {
                Entry e = new Entry();
                e.name = y.getString(k + ".name", "?");
                e.elo = y.getInt(k + ".elo", 1000);
                e.streak = y.getInt(k + ".streak");
                e.best = y.getInt(k + ".best");
                for (Mode m : Mode.values()) {
                    List<Integer> v = y.getIntegerList(k + "." + m.id);
                    if (v.size() == 2) e.wl.put(m, new int[]{v.get(0), v.get(1)});
                }
                data.put(UUID.fromString(k), e);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> en : data.entrySet()) {
            String k = en.getKey().toString();
            Entry e = en.getValue();
            y.set(k + ".name", e.name);
            y.set(k + ".elo", e.elo);
            y.set(k + ".streak", e.streak);
            y.set(k + ".best", e.best);
            for (Map.Entry<Mode, int[]> m : e.wl.entrySet()) y.set(k + "." + m.getKey().id, List.of(m.getValue()[0], m.getValue()[1]));
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException ignored) {
        }
    }
}
