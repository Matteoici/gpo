package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fûga avec le resource pack du serveur (modèles, textures et sons "limitless:").
 * Chronologie calquée sur la vidéo de référence :
 * 0 s nuit + aura · 2,6 s explosion de feu aux pieds · cercle magique et reflets · 6,2 s la flèche part
 * · impact en flammes style anime + fumée noire · 3,2 s plus tard flash blanc, le jour revient, colonne de feu géante.
 */
final class FugaFx {

    static final String NS = "limitless";
    private static final String SND_CHARGE = NS + ":fuga.charge", SND_SHOT = NS + ":fuga.shot", SND_IMPACT = NS + ":fuga.impact";
    private static final char WHITE = '', ORANGE = '';

    private final SmpBoss plugin;
    private final Ultimates ult;
    private final Set<Entity> live = new HashSet<>();

    FugaFx(SmpBoss plugin, Ultimates ult) {
        this.plugin = plugin;
        this.ult = ult;
    }

    void removeAll() {
        for (Entity e : live) e.remove();
        live.clear();
    }

    // ================================================================= outils

    private static ItemStack model(String name) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey(NS, "fx/" + name));
        it.setItemMeta(m);
        return it;
    }

    private ItemDisplay sprite(Location at, String name, float sx, float sy, float sz, Display.Billboard bb, Quaternionf rot) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        ItemDisplay d = at.getWorld().spawn(l, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setItemStack(model(name));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(bb);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(8f);
            e.setShadowRadius(0);
            e.setTeleportDuration(1);
            e.setTransformation(new Transformation(new Vector3f(), rot == null ? new Quaternionf() : rot, new Vector3f(sx, sy, sz), new Quaternionf()));
        });
        live.add(d);
        return d;
    }

    private static void tween(ItemDisplay d, Vector3f tr, Quaternionf rot, Vector3f scale, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(tr, rot == null ? new Quaternionf() : rot, scale, new Quaternionf()));
    }

    private void later(int ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, r, Math.max(1, ticks));
    }

    private void kill(ItemDisplay d, int ticks) {
        later(ticks, () -> {
            d.remove();
            live.remove(d);
        });
    }

    /** Apparaît, grossit, reste un peu, puis rétrécit et disparaît. */
    private ItemDisplay pop(Location at, String name, float sx, float sy, float sz, int grow, int hold, int shrink, Display.Billboard bb, Quaternionf rot) {
        ItemDisplay d = sprite(at, name, sx * 0.05f, sy * 0.05f, sz * 0.05f, bb, rot);
        later(1, () -> tween(d, new Vector3f(), rot, new Vector3f(sx, sy, sz), grow));
        later(1 + grow + hold, () -> tween(d, new Vector3f(), rot, new Vector3f(sx * 0.02f, sy * 0.02f, sz * 0.02f), shrink));
        kill(d, 2 + grow + hold + shrink);
        return d;
    }

    /** Animation image par image (explosions en style anime). */
    private void flip(Location at, String prefix, int frames, int every, float s0, float s1) {
        ItemDisplay d = sprite(at, prefix + "0", s0, s0, s0, Display.Billboard.CENTER, null);
        later(1, () -> tween(d, new Vector3f(), null, new Vector3f(s1, s1, s1), frames * every));
        for (int f = 1; f < frames; f++) {
            int ff = f;
            later(f * every, () -> {
                if (d.isValid()) d.setItemStack(model(prefix + ff));
            });
        }
        kill(d, frames * every + 1);
    }

    /** Son du pack (stéréo) : chaque joueur proche l'entend, moins fort de loin. */
    private void sound(Location c, String key, double radius) {
        for (Player o : c.getWorld().getPlayers()) {
            double d = o.getLocation().distance(c);
            if (d > radius) continue;
            o.playSound(o, key, SoundCategory.PLAYERS, (float) Math.max(0.25, 1 - d / radius), 1f);
        }
    }

    private void stopSound(Location c, String key, double radius) {
        for (Player o : c.getWorld().getPlayers()) if (o.getLocation().distanceSquared(c) <= radius * radius) o.stopSound(key, SoundCategory.PLAYERS);
    }

    /** Flash plein écran (image du pack affichée en titre). */
    private void flash(Location c, double radius, char glyph, int in, int stay, int out) {
        Component t = Component.text(String.valueOf(glyph)).font(Key.key(NS, "fx")).color(NamedTextColor.WHITE).shadowColor(ShadowColor.shadowColor(0));
        Title title = Title.title(t, Component.empty(), Title.Times.times(Duration.ofMillis(in * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(out * 50L)));
        for (Player o : c.getWorld().getPlayers()) if (o.getLocation().distanceSquared(c) <= radius * radius) o.showTitle(title);
    }

    private void dayAgain(Set<UUID> dark) {
        for (UUID id : dark) {
            Player o = Bukkit.getPlayer(id);
            if (o != null) o.resetPlayerTime();
            ult.night.remove(id);
        }
        dark.clear();
    }

    private static Vector right(Vector dir) {
        Vector r = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (r.lengthSquared() < 1e-4) r = new Vector(1, 0, 0);
        return r.normalize();
    }

    // ================================================================= 1. incantation (6,2 s)

    void cast(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        double R = ult.cfg("fuga.radius", 200);
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.fuga.chant");
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        sound(base, SND_CHARGE, 250);
        // le ciel passe à la nuit pour tous ceux qui sont autour
        Set<UUID> dark = new HashSet<>();
        for (Player o : w.getPlayers()) {
            if (o.getLocation().distanceSquared(base) > R * R) continue;
            o.setPlayerTime(18000, false);
            ult.night.add(o.getUniqueId());
            dark.add(o.getUniqueId());
        }
        flash(base, 60, WHITE, 0, 1, 8);
        Location chest = base.clone().add(0, 1.1, 0);
        ItemDisplay glow = sprite(chest, "glow", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay aura1 = sprite(chest, "aura", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay aura2 = sprite(chest, "aura", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        later(1, () -> {
            tween(glow, new Vector3f(), null, new Vector3f(7, 7, 7), 8);
            tween(aura1, new Vector3f(), null, new Vector3f(7, 7, 7), 10);
            tween(aura2, new Vector3f(), null, new Vector3f(9.5f, 9.5f, 9.5f), 12);
        });
        ItemDisplay[] circle = new ItemDisplay[2];
        int release = 124;
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            List<ItemDisplay> mine = new ArrayList<>(List.of(glow, aura1, aura2));
            for (ItemDisplay c : circle) if (c != null) mine.add(c);
            if (!p.isOnline() || p.isDead() || p.getWorld() != w) {
                task[0].cancel();
                ult.casting.remove(p.getUniqueId());
                for (ItemDisplay d : mine) kill(d, 1);
                stopSound(base, SND_CHARGE, 260);
                dayAgain(dark);
                return;
            }
            Location feet = p.getLocation(), at = feet.clone().add(0, 1.1, 0);
            at.setYaw(0);
            at.setPitch(0);
            glow.teleport(at);
            aura1.teleport(at);
            aura2.teleport(at);
            if (!lines.isEmpty() && k % 36 == 0 && k / 36 < lines.size()) ult.chat(base, p, lines.get(k / 36));
            // l'aura tourne et respire
            if (k > 12 && k % 4 == 0) {
                float s = (float) (7 + Math.sin(k * 0.15) * 0.6);
                tween(aura1, new Vector3f(), new Quaternionf().rotateZ(k * 0.09f), new Vector3f(s, s, s), 4);
                tween(aura2, new Vector3f(), new Quaternionf().rotateZ(-k * 0.06f), new Vector3f(s * 1.35f, s * 1.35f, s * 1.35f), 4);
            }
            // les mains en feu
            double yaw = Math.toRadians(feet.getYaw());
            for (int side = -1; side <= 1; side += 2) {
                Location hand = feet.clone().add(-Math.cos(yaw) * 0.42 * side - Math.sin(yaw) * 0.2, 0.9, -Math.sin(yaw) * 0.42 * side + Math.cos(yaw) * 0.2);
                w.spawnParticle(Particle.FLAME, hand, 2, 0.08, 0.12, 0.08, 0.01);
            }
            // 2,6 s : explosion de feu aux pieds, puis le cercle magique
            if (k == 52) flip(feet.clone().add(0, 1.2, 0), "burst_", 8, 1, 2.5f, 6.5f);
            if (k == 55) {
                Location g = feet.clone().add(0, 0.06, 0);
                circle[0] = sprite(g, "circle", 0.2f, 0.2f, 0.2f, Display.Billboard.FIXED, null);
                circle[1] = sprite(g.clone().add(0, 0.02, 0), "circle2", 0.2f, 0.2f, 0.2f, Display.Billboard.FIXED, null);
                later(1, () -> {
                    tween(circle[0], new Vector3f(), null, new Vector3f(9, 1, 9), 6);
                    tween(circle[1], new Vector3f(), null, new Vector3f(6, 1, 6), 6);
                });
            }
            if (k > 62 && circle[0] != null) {
                Location g = feet.clone().add(0, 0.06, 0);
                circle[0].teleport(g);
                circle[1].teleport(g.clone().add(0, 0.02, 0));
                if (k % 5 == 0) {
                    float pulse = (float) (1 + Math.sin(k * 0.3) * 0.04);
                    tween(circle[0], new Vector3f(), new Quaternionf().rotateY(k * 0.05f), new Vector3f(9 * pulse, 1, 9 * pulse), 5);
                    tween(circle[1], new Vector3f(), new Quaternionf().rotateY(-k * 0.09f), new Vector3f(6, 1, 6), 5);
                }
            }
            // reflets : étoiles blanches, croix noires, traits de lumière
            if (k > 56 && k < release && k % 3 == 0) {
                double a = rnd.nextDouble(Math.PI * 2), d = rnd.nextDouble(0.8, 3.2);
                Location g = feet.clone().add(Math.cos(a) * d, rnd.nextDouble(0.4, 3.8), Math.sin(a) * d);
                boolean darkStar = rnd.nextInt(3) == 0;
                float s = (float) rnd.nextDouble(0.9, 2.2);
                pop(g, darkStar ? "star_dark" : "star", s, s, s, 2, 1, 4, Display.Billboard.CENTER, null);
            }
            if (k > 58 && k < release && k % 8 == 0) {
                Location g = feet.clone().add(rnd.nextDouble(-1.5, 1.5), rnd.nextDouble(0.6, 3.5), rnd.nextDouble(-1.5, 1.5));
                pop(g, "flare", (float) rnd.nextDouble(4, 9), 0.7f, 1, 2, 2, 5, Display.Billboard.CENTER, null);
            }
            if (k == 80 || k == 106) pop(at.clone().add(0, 0.6, 0), "flare", 14, 1.1f, 1, 2, 3, 6, Display.Billboard.CENTER, null);
            if (k > 56 && k % 4 == 0) {
                double a = rnd.nextDouble(Math.PI * 2), d = rnd.nextDouble(2.5, 4.5);
                w.spawnParticle(Particle.WHITE_SMOKE, feet.clone().add(Math.cos(a) * d, 0.3, Math.sin(a) * d), 3, 0.5, 0.2, 0.5, 0.01);
                w.spawnParticle(Particle.SMALL_FLAME, feet.clone().add(Math.cos(a) * d * 0.7, 0.2, Math.sin(a) * d * 0.7), 2, 0.4, 0.1, 0.4, 0.02);
            }
            // 5,8 s : boule de feu devant toi, l'écran du lanceur devient orange
            if (k == 116) {
                Vector dir = p.getEyeLocation().getDirection();
                flip(p.getEyeLocation().add(dir.multiply(1.6)), "burst_", 8, 1, 2f, 5f);
                flash(base, 8, ORANGE, 2, 3, 6);
            }
            if (k >= release) {
                task[0].cancel();
                ult.casting.remove(p.getUniqueId());
                for (ItemDisplay d : mine) {
                    tween(d, new Vector3f(), null, new Vector3f(0.02f, 0.02f, 0.02f), 4);
                    kill(d, 5);
                }
                ult.chat(base, p, plugin.msg("weapons.fuga.final"));
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection().normalize();
                fly(p, eye.clone().add(dir.clone().multiply(2)), dir, dark);
            }
        }, 0L, 1L);
    }

    // ================================================================= 2. la flèche de feu

    private void fly(Player p, Location start, Vector dir, Set<UUID> dark) {
        World w = start.getWorld();
        double range = ult.cfg("fuga.range", 140), speed = ult.cfg("fuga.speed", 1.25);
        sound(start, SND_SHOT, 250);
        Vector rt = right(dir), up = rt.clone().crossProduct(dir).normalize();
        Location pos = start.clone();
        ItemDisplay core = sprite(pos, "core", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay halo = sprite(pos, "glow", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        later(1, () -> {
            tween(core, new Vector3f(), null, new Vector3f(2.8f, 2.8f, 2.8f), 3);
            tween(halo, new Vector3f(), null, new Vector3f(7, 7, 7), 3);
        });
        Location[] prev = new Location[3];
        int[] t = {0};
        double[] dist = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            RayTraceResult rtb = w.rayTraceBlocks(pos, dir, speed, FluidCollisionMode.NEVER, true);
            double step = rtb == null ? speed : rtb.getHitPosition().distance(pos.toVector());
            boolean stop = rtb != null;
            Location hitAt = null;
            for (double s = 0; s <= step && hitAt == null; s += 0.5) {
                Location at = pos.clone().add(dir.clone().multiply(s));
                if (!ult.targets(p, at, 1.8).isEmpty()) hitAt = at;
            }
            if (hitAt != null) {
                pos.set(hitAt.getX(), hitAt.getY(), hitAt.getZ());
                stop = true;
            } else pos.add(dir.clone().multiply(step));
            dist[0] += step;
            core.teleport(pos);
            halo.teleport(pos);
            // les rubans de feu qui s'enroulent derrière
            double rad = 0.7 + Math.min(0.5, k * 0.02);
            for (int i = 0; i < 3; i++) {
                double a = k * 0.42 + Math.PI * 2 * i / 3;
                Location pt = pos.clone().add(rt.clone().multiply(Math.cos(a) * rad)).add(up.clone().multiply(Math.sin(a) * rad));
                if (prev[i] != null && prev[i].getWorld() == pt.getWorld()) ribbon(prev[i], pt, i == 2 ? "ribbon_red" : "ribbon");
                prev[i] = pt;
            }
            if (k % 3 == 0) w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, pos, 2, 0.4, 0.4, 0.4, 0.01, null, true);
            w.spawnParticle(Particle.FLAME, pos, 4, 0.3, 0.3, 0.3, 0.03, null, true);
            if (k % 4 == 0) pop(pos.clone(), "star", 1.4f, 1.4f, 1.4f, 1, 0, 3, Display.Billboard.CENTER, null);
            if (dist[0] >= range) stop = true;
            if (!p.isOnline() && k > 200) stop = true;
            if (stop) {
                task[0].cancel();
                core.remove();
                halo.remove();
                live.remove(core);
                live.remove(halo);
                Location c = rtb != null && hitAt == null ? pos.clone().subtract(dir.clone().multiply(0.6)) : pos.clone();
                stopSound(c, SND_SHOT, 300);
                impact(p, c, dark);
            }
        }, 1L, 1L);
    }

    /** Un bout de ruban entre deux points, qui s'amincit puis disparaît. */
    private void ribbon(Location a, Location b, String model) {
        Vector seg = b.toVector().subtract(a.toVector());
        double len = seg.length();
        if (len < 0.05) return;
        Location mid = a.clone().add(seg.clone().multiply(0.5));
        Vector n = seg.clone().normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) n.getX(), (float) n.getY(), (float) n.getZ()));
        float l = (float) (len * 1.2);
        ItemDisplay d = sprite(mid, model, 1.0f, 1.0f, l, Display.Billboard.FIXED, q);
        later(1, () -> tween(d, new Vector3f(), q, new Vector3f(0.05f, 0.05f, l), 13));
        kill(d, 15);
    }

    // ================================================================= 3. impact, fumée noire, puis la colonne

    private void impact(Player p, Location c, Set<UUID> dark) {
        World w = c.getWorld();
        double R = ult.cfg("fuga.radius", 200), H = ult.cfg("fuga.column-height", 170);
        int burn = (int) (ult.cfg("fuga.burn-seconds", 10) * 20);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        sound(c, SND_IMPACT, 300);
        List<LivingEntity> list = ult.targets(p, c, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) dist.put(le.getUniqueId(), le.getLocation().distance(c));
        Location g0 = Ultimates.ground(c.clone().add(0, 1, 0));
        Location gc = g0 == null ? c.clone() : g0;
        // l'explosion en flammes style anime, les éclaboussures au sol, l'anneau
        flip(gc.clone().add(0, 5, 0), "boom_", 10, 2, 12, 26);
        ItemDisplay splash = sprite(gc.clone().add(0, 0.15, 0), "splash", 2, 1, 2, Display.Billboard.FIXED, null);
        later(1, () -> tween(splash, new Vector3f(), new Quaternionf().rotateY(0.4f), new Vector3f(30, 1, 30), 8));
        later(14, () -> tween(splash, new Vector3f(), new Quaternionf().rotateY(0.6f), new Vector3f(0.1f, 1, 0.1f), 14));
        kill(splash, 30);
        pop(gc.clone().add(0, 0.3, 0), "ring", 36, 1, 36, 10, 0, 6, Display.Billboard.FIXED, null);
        pop(gc.clone().add(0, 4, 0), "ring_up", 22, 22, 1, 8, 0, 5, Display.Billboard.CENTER, null);
        w.spawnParticle(Particle.BLOCK, gc.clone().add(0, 2, 0), 220, 4, 3, 4, 0.6, Material.BLACKSTONE.createBlockData(), true);
        w.spawnParticle(Particle.LAVA, gc, 60, 6, 1, 6, 0, null, true);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, gc, 3, 3, 1, 3, 0, null, true);
        ult.shards(gc, 40, 20, 170);
        ult.crater(p, gc, "fuga", new Material[]{Material.MAGMA_BLOCK, Material.BLACKSTONE, Material.NETHERRACK}, true);
        ult.shake(c, R + 30, 9, 3);
        Set<UUID> done = new HashSet<>();
        List<ItemDisplay> column = new ArrayList<>();
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            // ---- l'onde de feu frappe chacun (dégâts selon la distance)
            if (k <= 46) {
                double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    ult.zoneHit(p, le, d, DamageType.MAGIC, "fuga", burn);
                    w.spawnParticle(Particle.FLAME, le.getLocation().add(0, 1, 0), 30, 0.4, 0.7, 0.4, 0.1);
                }
            }
            // ---- fumée noire qui monte, braises au sol
            if (k >= 6 && k < 64 && k % 3 == 0) {
                Location s = gc.clone().add(rnd.nextDouble(-7, 7), rnd.nextDouble(1, 6), rnd.nextDouble(-7, 7));
                ItemDisplay sm = sprite(s, rnd.nextBoolean() ? "smoke_0" : "smoke_1", 2.5f, 2.5f, 2.5f, Display.Billboard.CENTER, null);
                float big = (float) rnd.nextDouble(7, 11);
                later(1, () -> tween(sm, new Vector3f(0, 7, 0), null, new Vector3f(big, big, big), 30));
                later(32, () -> tween(sm, new Vector3f(0, 10, 0), null, new Vector3f(0.1f, 0.1f, 0.1f), 12));
                kill(sm, 45);
            }
            if (k < 70 && k % 2 == 0) {
                w.spawnParticle(Particle.FLAME, gc, 6, 6, 0.3, 6, 0.02, null, true);
                w.spawnParticle(Particle.LARGE_SMOKE, gc.clone().add(0, 3, 0), 3, 5, 2, 5, 0.02, null, true);
            }
            // ---- reflets rouges qui montent du cratère juste avant la colonne
            if (k >= 44 && k < 64 && k % 2 == 0) {
                double y = (k - 44) * 2.2;
                pop(gc.clone().add(rnd.nextDouble(-1, 1), y + 2, rnd.nextDouble(-1, 1)), rnd.nextInt(3) == 0 ? "star_dark" : "star", 2.2f, 2.2f, 2.2f, 1, 1, 3, Display.Billboard.CENTER, null);
                pop(gc.clone().add(0, y + 1, 0), "flare", (float) rnd.nextDouble(10, 18), 1.0f, 1, 2, 1, 4, Display.Billboard.CENTER, null);
            }
            // ---- flash blanc : le jour revient, la colonne jaillit
            if (k == 64) {
                flash(gc, 150, WHITE, 1, 2, 12);
                dayAgain(dark);
                float h = (float) H;
                Location mid = gc.clone().add(0, h / 2 - 4, 0);
                ItemDisplay col = sprite(mid, "column", 0.3f, h, 0.3f, Display.Billboard.FIXED, null);
                ItemDisplay glow = sprite(mid, "column_glow", 0.3f, h, 0.3f, Display.Billboard.FIXED, null);
                column.add(col);
                column.add(glow);
                later(1, () -> {
                    tween(col, new Vector3f(), null, new Vector3f(10, h, 10), 4);
                    tween(glow, new Vector3f(), null, new Vector3f(15, h, 15), 5);
                });
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI * 2 * i / 6;
                    ItemDisplay f = sprite(gc.clone().add(Math.cos(a) * 6, 2.8, Math.sin(a) * 6), "basefire", 0.5f, 0.5f, 0.5f, Display.Billboard.VERTICAL, null);
                    column.add(f);
                    later(2, () -> tween(f, new Vector3f(), null, new Vector3f(7.5f, 7.5f, 7.5f), 5));
                }
                ult.shake(gc, R, 3, 4);
            }
            if (k > 68 && k < 150) {
                if (k % 10 == 0 && column.size() >= 2) {
                    tween(column.get(0), new Vector3f(), new Quaternionf().rotateY(k * 0.04f), new Vector3f(10, (float) H, 10), 10);
                    tween(column.get(1), new Vector3f(), new Quaternionf().rotateY(-k * 0.03f), new Vector3f(15, (float) H, 15), 10);
                }
                // traits de lumière horizontaux qui traversent la colonne, étincelles
                if (k % 2 == 0) {
                    Location fl = gc.clone().add(0, rnd.nextDouble(4, H * 0.75), 0);
                    pop(fl, "flare", (float) rnd.nextDouble(18, 36), (float) rnd.nextDouble(1.0, 1.8), 1, 2, 2, 5, Display.Billboard.CENTER, null);
                }
                if (k % 3 == 0) {
                    Location st = gc.clone().add(rnd.nextDouble(-5, 5), rnd.nextDouble(3, H * 0.6), rnd.nextDouble(-5, 5));
                    pop(st, rnd.nextInt(4) == 0 ? "star_dark" : "star", 3f, 3f, 3f, 1, 1, 4, Display.Billboard.CENTER, null);
                }
                if (k % 2 == 0) w.spawnParticle(Particle.FLAME, gc.clone().add(0, 1, 0), 12, 6, 0.6, 6, 0.06, null, true);
            }
            if (k == 150) {
                for (ItemDisplay d : column) {
                    Vector3f sc = d.getTransformation().getScale();
                    tween(d, new Vector3f(), null, new Vector3f(0.05f, sc.y, 0.05f), 10);
                    kill(d, 12);
                }
            }
            if (k >= 172) {
                task[0].cancel();
                dayAgain(dark);
                ult.chat(c, p, plugin.msg("weapons.fuga.summary").replace("<n>", String.valueOf(done.size())));
            }
        }, 0L, 1L);
    }
}
