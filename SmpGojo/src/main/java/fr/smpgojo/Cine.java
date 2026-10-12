package fr.smpgojo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mise en scène : impact frames plein écran, bandes noires de cinéma, tremblements,
 * arrêts sur image, incantations.
 * Les écrans colorés sont des affichages placés juste devant les yeux de chaque joueur,
 * visibles par lui seul.
 */
final class Cine {

    record Frame(Color color, int ticks) {
    }

    static Color argb(int a, Color c) {
        return Color.fromARGB(a, c.getRed(), c.getGreen(), c.getBlue());
    }

    static final Color CLEAR = Color.fromARGB(0, 0, 0, 0);

    static Frame f(int alpha, Color c, int ticks) {
        return new Frame(argb(alpha, c), ticks);
    }

    // séquences prêtes à l'emploi
    static final Frame[] WHITE_BLACK = {f(235, Draw.WHITE, 1), f(255, Draw.BLACK, 2), f(150, Draw.WHITE, 1)};
    static final Frame[] RED = {f(240, Draw.BLACK, 1), f(200, Draw.RED, 2), f(150, Draw.WHITE, 1)};
    static final Frame[] PURPLE = {f(245, Draw.WHITE, 1), f(215, Draw.PURPLE, 2), f(255, Draw.BLACK, 1), f(120, Draw.WHITE, 1)};
    static final Frame[] NUKE = {f(255, Draw.WHITE, 2), f(255, Draw.BLACK, 2), f(225, Draw.PURPLE, 2), f(210, Draw.WHITE, 1),
            f(255, Draw.BLACK, 1), f(170, Draw.PURPLE, 2), f(90, Draw.WHITE, 2)};
    static final Frame[] ORANGE = {f(250, Draw.BLACK, 1), f(210, Draw.ORANGE, 2), f(255, Draw.BLACK, 1), f(150, Draw.YELLOW, 1)};
    static final Frame[] BLACK_FLASH = {f(255, Draw.BLACK, 2), f(215, Draw.RED, 1), f(255, Draw.BLACK, 2), f(230, Draw.WHITE, 1),
            f(120, Draw.RED, 1)};
    static final Frame[] SHADOW = {f(255, Draw.BLACK, 2), f(200, Draw.SHADOW, 2), f(120, Draw.WHITE, 1)};
    static final Frame[] FLASH = {f(170, Draw.WHITE, 1), f(80, Draw.WHITE, 1)};

    private final SmpGojo pl;
    private int now;
    private final Map<UUID, TextDisplay> tints = new HashMap<>();
    private final Map<UUID, Integer> tintEnd = new HashMap<>();
    private final Map<UUID, BlockDisplay[]> bars = new HashMap<>();
    private final Map<UUID, Integer> barsEnd = new HashMap<>();
    private final Map<UUID, Location> locks = new HashMap<>();
    private final Map<UUID, Integer> lockEnd = new HashMap<>();
    private final Map<UUID, Integer> held = new HashMap<>();
    private final Map<UUID, Integer> glowEnd = new HashMap<>();

    Cine(SmpGojo pl) {
        this.pl = pl;
    }

    int now() {
        return now;
    }

    void tick() {
        now++;
        for (Iterator<Map.Entry<UUID, TextDisplay>> it = tints.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            TextDisplay d = e.getValue();
            if (p == null || !d.isValid() || d.getWorld() != p.getWorld() || now >= tintEnd.getOrDefault(e.getKey(), 0)) {
                d.remove();
                it.remove();
                tintEnd.remove(e.getKey());
                continue;
            }
            d.teleport(front(p, 0.6));
        }
        for (Iterator<Map.Entry<UUID, BlockDisplay[]>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            boolean bad = p == null || now >= barsEnd.getOrDefault(e.getKey(), 0);
            for (BlockDisplay b : e.getValue()) if (!b.isValid() || (p != null && b.getWorld() != p.getWorld())) bad = true;
            if (bad) {
                for (BlockDisplay b : e.getValue()) b.remove();
                it.remove();
                barsEnd.remove(e.getKey());
                continue;
            }
            Location at = front(p, 0.6);
            for (BlockDisplay b : e.getValue()) b.teleport(at);
        }
        lockEnd.entrySet().removeIf(e -> {
            if (now < e.getValue()) return false;
            locks.remove(e.getKey());
            return true;
        });
        held.entrySet().removeIf(e -> {
            if (now < e.getValue()) return false;
            Entity en = Bukkit.getEntity(e.getKey());
            if (en instanceof Mob m) m.setAware(true);
            if (en != null) en.setGravity(true);
            return true;
        });
        for (UUID id : held.keySet()) {
            Entity en = Bukkit.getEntity(id);
            if (en != null) en.setVelocity(new Vector());
        }
        glowEnd.entrySet().removeIf(e -> {
            if (now < e.getValue()) return false;
            Entity en = Bukkit.getEntity(e.getKey());
            if (en != null) en.setGlowing(false);
            return true;
        });
    }

    static Location front(Player p, double d) {
        Location e = p.getEyeLocation();
        return e.add(e.getDirection().multiply(d));
    }

    // ================================================================= écrans

    /** Teinte tout l'écran du joueur pendant quelques ticks. */
    void tint(Player p, Color argb, int ticks) {
        if (!pl.getConfig().getBoolean("cinematic.impact-frames", true)) return;
        TextDisplay d = tints.get(p.getUniqueId());
        if (d == null || !d.isValid()) {
            d = p.getWorld().spawn(front(p, 0.6), TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "overlay");
                e.text(Component.text(" "));
                e.setDefaultBackground(false);
                e.setBackgroundColor(argb);
                e.setSeeThrough(true);
                e.setShadowed(false);
                e.setBillboard(Display.Billboard.CENTER);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(1);
                e.setTransformation(new Transformation(new Vector3f(0, -6f, 0), new Quaternionf(), new Vector3f(80, 48, 1),
                        new Quaternionf()));
            });
            p.showEntity(pl, d);
            tints.put(p.getUniqueId(), d);
        } else {
            d.setBackgroundColor(argb);
        }
        tintEnd.put(p.getUniqueId(), now + Math.max(1, ticks));
    }

    /** Une suite d'images d'impact pour tous les spectateurs. Les cibles brillent (silhouettes). */
    void impact(Collection<Player> viewers, Collection<? extends Entity> glow, Frame[] frames) {
        int total = 0;
        for (Frame f : frames) total += f.ticks();
        if (glow != null) glow(glow, total + 2);
        int t = 0;
        for (Frame f : frames) {
            List<Player> vs = new ArrayList<>(viewers);
            pl.later(t, () -> {
                for (Player p : vs) if (p.isOnline()) tint(p, f.color(), f.ticks());
            });
            t += f.ticks();
        }
    }

    /** Bandes noires de cinéma en haut et en bas de l'écran. */
    void letterbox(Collection<Player> viewers, int ticks) {
        if (!pl.getConfig().getBoolean("cinematic.letterbox", true)) return;
        for (Player p : viewers) {
            BlockDisplay[] b = bars.get(p.getUniqueId());
            if (b == null) {
                b = new BlockDisplay[]{bar(p, true), bar(p, false)};
                bars.put(p.getUniqueId(), b);
            }
            barsEnd.merge(p.getUniqueId(), now + ticks, Math::max);
        }
    }

    private BlockDisplay bar(Player p, boolean top) {
        float y = top ? 0.34f : -4.34f;
        BlockDisplay d = p.getWorld().spawn(front(p, 0.6), BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "overlay");
            e.setBlock(Material.BLACK_CONCRETE.createBlockData());
            e.setBillboard(Display.Billboard.CENTER);
            e.setBrightness(new Display.Brightness(0, 0));
            e.setTeleportDuration(1);
            e.setTransformation(new Transformation(new Vector3f(-4f, y, -0.01f), new Quaternionf(), new Vector3f(8f, 4f, 0.02f),
                    new Quaternionf()));
        });
        p.showEntity(pl, d);
        return d;
    }

    /** Tremblement de caméra. */
    void shake(Collection<Player> viewers, int pulses, int every) {
        if (!pl.getConfig().getBoolean("cinematic.shake", true)) return;
        List<Player> vs = new ArrayList<>(viewers);
        pl.repeat(Math.max(1, every), pulses, i -> {
            for (Player p : vs) if (p.isOnline()) p.playHurtAnimation((float) Draw.rnd().nextDouble(360));
        }, null);
    }

    /** Arrêt sur image : les cibles restent figées en l'air quelques ticks. */
    void hitStop(Collection<? extends LivingEntity> es, int ticks) {
        for (LivingEntity e : es) {
            if (e instanceof Player p) lock(p, ticks);
            else hold(e, ticks);
            e.setVelocity(new Vector());
        }
    }

    void hold(LivingEntity e, int ticks) {
        if (e instanceof Player p) {
            lock(p, ticks);
            return;
        }
        if (e instanceof Mob m) m.setAware(false);
        e.setGravity(false);
        held.merge(e.getUniqueId(), now + ticks, Math::max);
    }

    void release(LivingEntity e) {
        if (e instanceof Player p) {
            locks.remove(p.getUniqueId());
            lockEnd.remove(p.getUniqueId());
            return;
        }
        if (held.remove(e.getUniqueId()) != null) {
            if (e instanceof Mob m) m.setAware(true);
            e.setGravity(true);
        }
    }

    void lock(Player p, int ticks) {
        locks.putIfAbsent(p.getUniqueId(), p.getLocation());
        lockEnd.merge(p.getUniqueId(), now + ticks, Math::max);
    }

    Location locked(Player p) {
        return locks.get(p.getUniqueId());
    }

    void glow(Collection<? extends Entity> es, int ticks) {
        for (Entity e : es) {
            e.setGlowing(true);
            glowEnd.merge(e.getUniqueId(), now + ticks, Math::max);
        }
    }

    // ================================================================= textes

    void title(Collection<Player> viewers, String title, String sub, int stayTicks) {
        Title t = Title.title(SmpGojo.mm(title), SmpGojo.mm(sub),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(300)));
        for (Player p : viewers) p.showTitle(t);
    }

    /** Incantation : bandes noires, une ligne après l'autre, puis l'action. */
    void chant(Player caster, double radius, List<String> lines, int perLine, String sound, Runnable then) {
        List<Player> viewers = pl.viewers(caster.getLocation(), radius);
        int total = lines.size() * perLine;
        letterbox(viewers, total + 20);
        caster.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, total, 3, false, false, false));
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            float pitch = 0.6f + i * 0.15f;
            pl.later((long) i * perLine, () -> {
                title(viewers, "", line, perLine - 2);
                SmpGojo.sound(caster.getLocation(), sound, 2.5f, pitch);
            });
        }
        pl.later(total, then);
    }

    void cleanup() {
        for (TextDisplay d : tints.values()) d.remove();
        tints.clear();
        for (BlockDisplay[] b : bars.values()) for (BlockDisplay d : b) d.remove();
        bars.clear();
        for (UUID id : held.keySet()) {
            Entity en = Bukkit.getEntity(id);
            if (en instanceof Mob m) m.setAware(true);
            if (en != null) en.setGravity(true);
        }
        held.clear();
        for (UUID id : glowEnd.keySet()) {
            Entity en = Bukkit.getEntity(id);
            if (en != null) en.setGlowing(false);
        }
        glowEnd.clear();
        locks.clear();
        lockEnd.clear();
    }

    void quit(Player p) {
        TextDisplay d = tints.remove(p.getUniqueId());
        if (d != null) d.remove();
        BlockDisplay[] b = bars.remove(p.getUniqueId());
        if (b != null) for (BlockDisplay x : b) x.remove();
        locks.remove(p.getUniqueId());
        lockEnd.remove(p.getUniqueId());
    }
}
