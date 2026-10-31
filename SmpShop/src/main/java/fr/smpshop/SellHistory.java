package fr.smpshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Historique des ventes de chaque joueur (plugins/SmpShop/historique/&lt;uuid&gt;.yml).
 * Une vente = date, total, bonus, et le détail par objet. Les plus récentes d'abord, 300 ventes max par joueur.
 */
public final class SellHistory {

    /** Un objet vendu dans une vente. */
    public record Line(Material material, String name, long count, double money) {
    }

    public record Sale(long time, long count, double money, double multiplier, List<Line> lines) {
    }

    private final SmpShop plugin;
    private final File dir;
    private final Map<UUID, List<Sale>> cache = new ConcurrentHashMap<>();

    SellHistory(SmpShop plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "historique");
        dir.mkdirs();
    }

    private int max() {
        return Math.max(10, plugin.getConfig().getInt("historique.max-ventes", 300));
    }

    public List<Sale> of(UUID id) {
        return cache.computeIfAbsent(id, this::load);
    }

    public void add(UUID id, Sale s) {
        List<Sale> l = of(id);
        synchronized (l) {
            l.add(0, s);
            while (l.size() > max()) l.remove(l.size() - 1);
        }
        List<Sale> copy;
        synchronized (l) {
            copy = new ArrayList<>(l);
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> save(id, copy));
    }

    private List<Sale> load(UUID id) {
        List<Sale> out = new ArrayList<>();
        File f = new File(dir, id + ".yml");
        if (!f.exists()) return out;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (Map<?, ?> m : y.getMapList("ventes")) {
            try {
                List<Line> lines = new ArrayList<>();
                Object raw = m.get("objets");
                if (raw instanceof List<?> li) {
                    for (Object o : li) {
                        String[] p = String.valueOf(o).split(";", 4);
                        Material mat = Material.matchMaterial(p[0]);
                        lines.add(new Line(mat == null ? Material.PAPER : mat, p.length > 3 ? p[3] : "", Long.parseLong(p[1]), Double.parseDouble(p[2])));
                    }
                }
                out.add(new Sale(((Number) m.get("date")).longValue(), ((Number) m.get("objets-vendus")).longValue(),
                        ((Number) m.get("argent")).doubleValue(), m.get("bonus") instanceof Number n ? n.doubleValue() : 1, lines));
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private void save(UUID id, List<Sale> sales) {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Sale s : sales) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", s.time());
            m.put("objets-vendus", s.count());
            m.put("argent", s.money());
            m.put("bonus", s.multiplier());
            List<String> lines = new ArrayList<>();
            for (Line l : s.lines()) lines.add(l.material().name() + ";" + l.count() + ";" + l.money() + ";" + l.name().replace("\n", " "));
            m.put("objets", lines);
            list.add(m);
        }
        y.set("ventes", list);
        try {
            y.save(new File(dir, id + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Historique de vente non sauvegardé : " + e.getMessage());
        }
    }
}
