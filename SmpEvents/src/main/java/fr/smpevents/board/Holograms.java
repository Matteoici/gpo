package fr.smpevents.board;

import fr.smpevents.DataStore;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static fr.smpevents.Texts.ph;
import static fr.smpevents.Texts.pp;

/**
 * Classements flottants. Ce sont des textes d'affichage non sauvegardés dans le monde :
 * recréés au chargement du chunk, rien ne traîne si le plugin est retiré.
 */
public final class Holograms {

    public static final class Holo {
        public final String id;
        public final String world;
        public final double x, y, z;
        public final List<Board> boards;
        int index;
        TextDisplay display;

        Holo(String id, String world, double x, double y, double z, List<Board> boards) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.boards = boards;
        }

        public Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        public Board current() {
            return boards.get(Math.floorMod(index, boards.size()));
        }
    }

    private final SmpEvents plugin;
    private final Map<String, Holo> holos = new LinkedHashMap<>();
    private final File file;
    public int rotateSeconds;
    public float scale;
    public int background;

    public Holograms(SmpEvents plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "holograms.yml");
    }

    public void loadSettings(ConfigurationSection c) {
        if (c == null) c = plugin.getConfig().createSection("leaderboards");
        rotateSeconds = Math.max(3, c.getInt("rotate-seconds", 10));
        scale = (float) Math.max(0.3, Math.min(4, c.getDouble("hologram-scale", 1.0)));
        background = Math.max(0, Math.min(255, c.getInt("hologram-background-opacity", 90)));
    }

    public void load() {
        removeAll();
        holos.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("holograms");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            List<Board> boards = new ArrayList<>();
            for (String b : s.getStringList("boards")) {
                Board bo = Board.of(b);
                if (bo != null) boards.add(bo);
            }
            if (boards.isEmpty()) continue;
            holos.put(id, new Holo(id, s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), boards));
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Holo h : holos.values()) {
            String p = "holograms." + h.id + ".";
            y.set(p + "world", h.world);
            y.set(p + "x", h.x);
            y.set(p + "y", h.y);
            y.set(p + "z", h.z);
            List<String> b = new ArrayList<>();
            for (Board bo : h.boards) b.add(bo.key);
            y.set(p + "boards", b);
        }
        DataStore.write(file, y.saveToString());
    }

    // ================================================================= gestion

    public Holo create(Location at, List<Board> boards) {
        int n = 1;
        while (holos.containsKey(String.valueOf(n))) n++;
        Holo h = new Holo(String.valueOf(n), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), boards);
        holos.put(h.id, h);
        save();
        ensure(h);
        return h;
    }

    public boolean remove(String id) {
        Holo h = holos.remove(id);
        if (h == null) return false;
        kill(h);
        save();
        return true;
    }

    public Holo nearest(Location l, double max) {
        Holo best = null;
        double bd = max * max;
        for (Holo h : holos.values()) {
            Location hl = h.location();
            if (hl == null || hl.getWorld() != l.getWorld()) continue;
            double d = hl.distanceSquared(l);
            if (d <= bd) {
                bd = d;
                best = h;
            }
        }
        return best;
    }

    public Holo get(String id) {
        return holos.get(id);
    }

    public List<Holo> all() {
        return new ArrayList<>(holos.values());
    }

    // ================================================================= affichage

    public void ensureAll() {
        for (Holo h : holos.values()) ensure(h);
    }

    private void ensure(Holo h) {
        if (h.display != null && h.display.isValid()) return;
        Location l = h.location();
        if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        h.display = l.getWorld().spawn(l, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(plugin.key(), PersistentDataType.BYTE, (byte) 1);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setBackgroundColor(Color.fromARGB(background, 0, 0, 0));
            d.setShadowed(true);
            d.setLineWidth(400);
            d.setViewRange(2f);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            Transformation tr = d.getTransformation();
            d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(scale, scale, scale),
                    tr.getRightRotation()));
            d.text(text(h));
        });
    }

    public void onChunkLoad(String world, int cx, int cz) {
        for (Holo h : holos.values()) {
            if (h.world.equals(world) && ((int) Math.floor(h.x)) >> 4 == cx && ((int) Math.floor(h.z)) >> 4 == cz) ensure(h);
        }
    }

    public void renderAll() {
        for (Holo h : holos.values()) {
            if (h.display != null && h.display.isValid()) h.display.text(text(h));
        }
    }

    /** Passe au classement suivant sur les hologrammes qui en montrent plusieurs. */
    public void rotate() {
        for (Holo h : holos.values()) {
            if (h.boards.size() < 2) continue;
            h.index++;
            if (h.display != null && h.display.isValid()) h.display.text(text(h));
        }
    }

    private Component text(Holo h) {
        Texts t = plugin.texts();
        Board b = h.current();
        Boards boards = plugin.boards();
        Component out = t.get("hologram.title." + b.key);
        out = out.append(Component.newline()).append(t.get("hologram.separator"));
        List<Boards.Entry> top = boards.top(b, boards.size);
        for (int i = 0; i < boards.size; i++) {
            out = out.append(Component.newline());
            String color = i == 0 ? t.raw("hologram.rank-color.1") : i == 1 ? t.raw("hologram.rank-color.2")
                    : i == 2 ? t.raw("hologram.rank-color.3") : t.raw("hologram.rank-color.other");
            if (i < top.size()) {
                Boards.Entry e = top.get(i);
                out = out.append(t.get("hologram.line", pp("color", color), ph("rank", i + 1), ph("player", e.name()),
                        ph("value", boards.format(b, e.value()))));
            } else {
                out = out.append(t.get("hologram.empty", pp("color", color), ph("rank", i + 1)));
            }
        }
        out = out.append(Component.newline()).append(t.get("hologram.separator"));
        if (h.boards.size() > 1) {
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < h.boards.size(); i++) {
                dots.append(i == Math.floorMod(h.index, h.boards.size()) ? t.raw("hologram.dot-on") : t.raw("hologram.dot-off"));
                if (i < h.boards.size() - 1) dots.append(' ');
            }
            out = out.append(Component.newline()).append(Texts.parse(dots.toString()));
        }
        out = out.append(Component.newline()).append(t.get("hologram.footer"));
        return out;
    }

    // ================================================================= suppression

    private void kill(Holo h) {
        if (h.display != null) h.display.remove();
        h.display = null;
    }

    public void removeAll() {
        for (Holo h : holos.values()) kill(h);
    }

    /** Supprime les textes du plugin qui ne sont plus suivis (doublons après un crash...). */
    public void prune() {
        java.util.Set<java.util.UUID> known = new java.util.HashSet<>();
        for (Holo h : holos.values()) if (h.display != null) known.add(h.display.getUniqueId());
        if (plugin.koth() != null) known.addAll(plugin.koth().entityIds());
        if (plugin.afk() != null) known.addAll(plugin.afk().entityIds());
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(TextDisplay.class)) {
                if (e.getPersistentDataContainer().has(plugin.key(), PersistentDataType.BYTE) && !known.contains(e.getUniqueId())) {
                    e.remove();
                }
            }
        }
    }
}
