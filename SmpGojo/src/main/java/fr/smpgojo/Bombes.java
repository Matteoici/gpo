package fr.smpgojo;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bombes atomiques (pour troller) : Tsar Bomba larguée en parachute, et le missile RS-28 Satan
 * qui se divise en plusieurs ogives. Boule de feu, cratère, souffle, champignon atomique.
 * Même système que le Violet creux : effacement avec un budget de temps (pas de lag), tout revient après.
 */
final class Bombes {

    private static final Color FIRE = Color.fromRGB(255, 140, 20), FIRE_CORE = Color.fromRGB(255, 230, 120),
            SMOKE = Color.fromRGB(90, 85, 80), SMOKE_DARK = Color.fromRGB(50, 45, 42), EMBER = Color.fromRGB(200, 60, 10);

    private final SmpGojo pl;

    Bombes(SmpGojo pl) {
        this.pl = pl;
    }

    // ================================================================= modèles en blocs

    /** Un objet fait de plusieurs blocs, qu'on déplace et qu'on oriente d'un coup. */
    private final class Model {
        final List<BlockDisplay> parts = new ArrayList<>();
        final List<Vector3f[]> boxes = new ArrayList<>();

        void add(Location at, Material m, float x, float y, float z, float sx, float sy, float sz) {
            BlockData data = m.createBlockData();
            parts.add(at.getWorld().spawn(at, BlockDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "prop");
                e.setBlock(data);
                e.setTeleportDuration(1);
                e.setViewRange(12f);
            }));
            boxes.add(new Vector3f[]{new Vector3f(x, y, z), new Vector3f(sx, sy, sz)});
        }

        void place(Location c, Quaternionf q, int interp) {
            for (int k = 0; k < parts.size(); k++) {
                BlockDisplay d = parts.get(k);
                if (!d.isValid()) continue;
                Vector3f[] b = boxes.get(k);
                d.teleport(c);
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(interp);
                d.setTransformation(new Transformation(q.transform(new Vector3f(b[0])), new Quaternionf(q), new Vector3f(b[1]), new Quaternionf()));
            }
        }

        void remove() {
            for (BlockDisplay d : parts) d.remove();
        }
    }

    /** La Tsar Bomba : 8 m de long, pointe vers le bas, ailerons, sous un énorme parachute. */
    private Model tsarModel(Location at) {
        Model m = new Model();
        m.add(at, Material.LIGHT_GRAY_CONCRETE, -1.05f, -3f, -1.05f, 2.1f, 6f, 2.1f);
        m.add(at, Material.GRAY_CONCRETE, -0.75f, -4.1f, -0.75f, 1.5f, 1.1f, 1.5f);
        m.add(at, Material.BLACK_CONCRETE, -0.35f, -4.5f, -0.35f, 0.7f, 0.4f, 0.7f);
        m.add(at, Material.GRAY_CONCRETE, -0.8f, 3f, -0.8f, 1.6f, 1.6f, 1.6f);
        m.add(at, Material.IRON_BLOCK, -2.1f, 3f, -0.08f, 4.2f, 1.8f, 0.16f);
        m.add(at, Material.IRON_BLOCK, -0.08f, 3f, -2.1f, 0.16f, 1.8f, 4.2f);
        m.add(at, Material.YELLOW_CONCRETE, -1.1f, 1.2f, -1.1f, 2.2f, 0.35f, 2.2f);
        m.add(at, Material.RED_CONCRETE, -1.1f, -1.8f, -1.1f, 2.2f, 0.25f, 2.2f);
        // le parachute
        m.add(at, Material.WHITE_WOOL, -6f, 10f, -6f, 12f, 1f, 12f);
        m.add(at, Material.ORANGE_WOOL, -4.5f, 10.8f, -4.5f, 9f, 0.9f, 9f);
        m.add(at, Material.WHITE_WOOL, -2.5f, 11.5f, -2.5f, 5f, 0.6f, 5f);
        return m;
    }

    /** Le missile RS-28 : long corps vert sombre, pointe rouge (axe +Y = vers l'avant). */
    private Model missileModel(Location at) {
        Model m = new Model();
        m.add(at, Material.GREEN_TERRACOTTA, -0.9f, -7f, -0.9f, 1.8f, 13f, 1.8f);
        m.add(at, Material.WHITE_CONCRETE, -0.95f, 2f, -0.95f, 1.9f, 0.5f, 1.9f);
        m.add(at, Material.WHITE_CONCRETE, -0.95f, -3f, -0.95f, 1.9f, 0.5f, 1.9f);
        m.add(at, Material.RED_CONCRETE, -0.65f, 6f, -0.65f, 1.3f, 1.6f, 1.3f);
        m.add(at, Material.BLACK_CONCRETE, -0.3f, 7.6f, -0.3f, 0.6f, 0.6f, 0.6f);
        m.add(at, Material.GRAY_CONCRETE, -1.6f, -7f, -0.1f, 3.2f, 2f, 0.2f);
        m.add(at, Material.GRAY_CONCRETE, -0.1f, -7f, -1.6f, 0.2f, 2f, 3.2f);
        return m;
    }

    /** Une ogive (pointe vers le bas). */
    private Model warheadModel(Location at) {
        Model m = new Model();
        m.add(at, Material.GRAY_CONCRETE, -0.45f, -0.9f, -0.45f, 0.9f, 1.8f, 0.9f);
        m.add(at, Material.BLACK_CONCRETE, -0.3f, -1.5f, -0.3f, 0.6f, 0.6f, 0.6f);
        m.add(at, Material.RED_CONCRETE, -0.47f, 0.4f, -0.47f, 0.94f, 0.2f, 0.94f);
        return m;
    }

    private static Quaternionf pointing(Vector dir) {
        Vector3f v = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        if (v.lengthSquared() < 1e-6) return new Quaternionf();
        return new Quaternionf().rotationTo(new Vector3f(0, 1, 0), v.normalize());
    }

    private Location target(Player p) {
        Location at = pl.aimPoint(p, pl.cfg("bombes.range", 400));
        World w = at.getWorld();
        Block g = Ruin.surface(w, at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (g == null) g = w.getHighestBlockAt(at.getBlockX(), at.getBlockZ());
        return g.getLocation().add(0.5, 1, 0.5);
    }

    private void alert(Location ground, String name) {
        List<Player> vs = pl.viewers(ground, 700);
        pl.cine.title(vs, "<red><b>⚠ ALERTE NUCLÉAIRE ⚠", "<gold>" + name + " <gray>en approche - X " + ground.getBlockX() + " Z " + ground.getBlockZ(), 60);
        for (Player v : vs) {
            v.sendMessage(SmpGojo.mm("<dark_red><b>☢ ALERTE NUCLÉAIRE</b></dark_red> <gold>" + name + " <gray>en approche de <white>X "
                    + ground.getBlockX() + " Z " + ground.getBlockZ() + "<gray>. Mettez-vous à l'abri."));
        }
        for (int k = 0; k < 3; k++) {
            pl.later(k * 40L, () -> {
                for (Player v : pl.viewers(ground, 700)) v.playSound(net.kyori.adventure.sound.Sound.sound(
                        net.kyori.adventure.key.Key.key("minecraft", "event.raid.horn"), net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 0.8f));
            });
        }
    }

    // ================================================================= Tsar Bomba

    void tsar(Player p) {
        Location ground = target(p);
        World w = ground.getWorld();
        int fall = pl.cfgi("bombes.tsar.fall-seconds", 8) * 20;
        double startH = Math.min(pl.cfg("bombes.tsar.drop-height", 140), w.getMaxHeight() - 20 - ground.getY());
        Location c = ground.clone().add(0, startH, 0);
        alert(ground, "Tsar Bomba");
        Model bomb = tsarModel(c);
        Runnable[] clean = {null};
        clean[0] = () -> {
            bomb.remove();
            pl.pending.remove(clean[0]);
        };
        pl.pending.add(clean[0]);
        bomb.place(c, new Quaternionf(), 0);
        double detonateAt = pl.cfg("bombes.tsar.burst-height", 4);
        pl.repeat(1, fall, i -> {
            double t = (i + 1) / (double) fall;
            Location at = ground.clone().add(Math.sin(i * 0.05) * 1.5, detonateAt + (startH - detonateAt) * (1 - t), Math.cos(i * 0.04) * 1.5);
            Quaternionf q = new Quaternionf().rotateZ((float) (Math.sin(i * 0.08) * 0.08)).rotateX((float) (Math.cos(i * 0.06) * 0.08));
            bomb.place(at, q, 1);
            // suspentes du parachute
            if (i % 2 == 0) {
                Location tail = at.clone().add(0, 4.5, 0);
                for (int k = 0; k < 4; k++) {
                    double a = k * Math.PI / 2 + Math.PI / 4;
                    Draw.segment(tail, at.clone().add(Math.cos(a) * 5.5, 10, Math.sin(a) * 5.5), Draw.WHITE, 0.4f, 0.8);
                }
            }
            if (i % 20 == 0) {
                for (Player v : pl.viewers(ground, 700)) {
                    int left = (fall - i) / 20;
                    v.sendActionBar(SmpGojo.mm("<red><b>☢</b> <gold>Tsar Bomba <gray>- impact dans <white>" + left + " s"));
                }
                SmpGojo.sound(at, "block.note_block.bit", 3f, 0.5f);
            }
            if (i % 30 == 0) SmpGojo.sound(at, "item.elytra.flying", 3f, 0.5f);
        }, () -> {
            clean[0].run();
            detonate(p, ground, pl.cfg("bombes.tsar.radius", 60), true);
        });
    }

    // ================================================================= RS-28 Satan

    void satan(Player p) {
        Location ground = target(p);
        World w = ground.getWorld();
        alert(ground, "RS-28 Satan");
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double a0 = rnd.nextDouble(Math.PI * 2);
        double splitH = Math.min(90, w.getMaxHeight() - 10 - ground.getY());
        Location split = ground.clone().add(0, splitH, 0);
        Location start = split.clone().add(Math.cos(a0) * 220, Math.min(120, w.getMaxHeight() - 5 - split.getY()), Math.sin(a0) * 220);
        Model missile = missileModel(start);
        Runnable[] clean = {null};
        clean[0] = () -> {
            missile.remove();
            pl.pending.remove(clean[0]);
        };
        pl.pending.add(clean[0]);
        int travel = pl.cfgi("bombes.satan.flight-ticks", 120);
        Vector total = split.toVector().subtract(start.toVector());
        SmpGojo.sound(start, "entity.firework_rocket.launch", 10f, 0.5f);
        pl.repeat(1, travel, i -> {
            double t = (i + 1) / (double) travel;
            // trajectoire balistique : légère courbe vers le bas
            Location at = start.clone().add(total.clone().multiply(t)).add(0, Math.sin(t * Math.PI) * 25, 0);
            double t2 = Math.min(1, t + 0.01);
            Location nxt = start.clone().add(total.clone().multiply(t2)).add(0, Math.sin(t2 * Math.PI) * 25, 0);
            Vector dir = nxt.toVector().subtract(at.toVector());
            missile.place(at, pointing(dir), 1);
            Location tail = at.clone().subtract(dir.clone().normalize().multiply(8));
            Draw.fx(tail, Particle.FLAME, 12, 0.4, 0.05);
            Draw.fx(tail, Particle.CAMPFIRE_SIGNAL_SMOKE, 4, 0.5, 0.02);
            Draw.fx(tail, Particle.LARGE_SMOKE, 6, 0.6, 0.02);
            if (i % 10 == 0) SmpGojo.sound(at, "entity.blaze.shoot", 8f, 0.4f);
            if (i % 20 == 0) {
                for (Player v : pl.viewers(ground, 700)) {
                    v.sendActionBar(SmpGojo.mm("<red><b>☢</b> <gold>RS-28 Satan <gray>- séparation des ogives dans <white>" + ((travel - i) / 20) + " s"));
                }
            }
        }, () -> {
            clean[0].run();
            mirv(p, ground, split);
        });
    }

    /** Le missile se sépare en plusieurs ogives qui tombent autour de la cible. */
    private void mirv(Player p, Location ground, Location split) {
        World w = ground.getWorld();
        int n = Math.max(1, pl.cfgi("bombes.satan.warheads", 6));
        double spread = pl.cfg("bombes.satan.spread", 45), R = pl.cfg("bombes.satan.radius", 22);
        SmpGojo.sound(split, "entity.generic.explode", 10f, 1.4f);
        SmpGojo.sound(split, "block.piston.extend", 10f, 0.5f);
        Draw.fx(split, Particle.EXPLOSION, 6, 2, 0);
        pl.cine.title(pl.viewers(ground, 700), "", "<red>Séparation : <white>" + n + " ogives", 30);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int k = 0; k < n; k++) {
            Location spot;
            if (k == n - 1) {
                spot = ground.clone();
            } else {
                double a = k * Math.PI * 2 / (n - 1) + rnd.nextDouble(-0.2, 0.2);
                Location raw = ground.clone().add(Math.cos(a) * spread, 0, Math.sin(a) * spread);
                Block g = Ruin.surface(w, raw.getBlockX(), ground.getBlockY(), raw.getBlockZ());
                if (g == null) g = w.getHighestBlockAt(raw.getBlockX(), raw.getBlockZ());
                spot = g.getLocation().add(0.5, 1, 0.5);
            }
            int fall = 40 + k * 6;
            Location target = spot;
            Model wh = warheadModel(split);
            Runnable[] clean = {null};
            clean[0] = () -> {
                wh.remove();
                pl.pending.remove(clean[0]);
            };
            pl.pending.add(clean[0]);
            Vector side = target.toVector().subtract(split.toVector()).setY(0);
            pl.repeat(1, fall, i -> {
                double t = (i + 1) / (double) fall;
                double ease = t * t;
                Location at = split.clone().add(side.clone().multiply(Math.sin(t * Math.PI / 2)))
                        .add(0, (target.getY() + 2 - split.getY()) * ease, 0);
                double t2 = Math.min(1, t + 0.02), e2 = t2 * t2;
                Location nxt = split.clone().add(side.clone().multiply(Math.sin(t2 * Math.PI / 2))).add(0, (target.getY() + 2 - split.getY()) * e2, 0);
                Vector dir = at.toVector().subtract(nxt.toVector());
                wh.place(at, pointing(dir), 1);
                Draw.fx(at.clone().add(0, 1.5, 0), Particle.CAMPFIRE_SIGNAL_SMOKE, 2, 0.2, 0.01);
                Draw.fx(at.clone().add(0, 1.2, 0), Particle.FLAME, 3, 0.1, 0.02);
                if (i % 8 == 0) SmpGojo.sound(at, "entity.phantom.swoop", 4f, 0.5f);
            }, () -> {
                clean[0].run();
                detonate(p, target, R, false);
            });
        }
    }

    // ================================================================= la détonation

    private void detonate(Player p, Location ground, double R, boolean big) {
        World w = ground.getWorld();
        Location c = ground.clone().add(0, R * 0.25, 0);
        double reach = Math.min(700, R * 10);
        List<Player> vs = pl.viewers(ground, reach);
        double dmg = pl.cfg("damage.bombe", 1000);

        // l'éclair nucléaire
        pl.cine.impact(vs, null, new Cine.Frame[]{Cine.f(255, Draw.WHITE, 3), Cine.f(240, FIRE_CORE, 2), Cine.f(200, FIRE, 2),
                Cine.f(150, Draw.WHITE, 2), Cine.f(90, FIRE, 2), Cine.f(40, FIRE_CORE, 2)});
        pl.cine.shake(vs, big ? 40 : 15, 2);
        SmpGojo.sound(c, "entity.lightning_bolt.thunder", 10f, 0.5f);
        SmpGojo.sound(c, "entity.generic.explode", 10f, 0.4f);
        // le son arrive plus tard pour ceux qui sont loin (vitesse du son)
        for (Player v : vs) {
            double dist = v.getLocation().distance(ground);
            long delay = (long) Math.max(0, dist / 17);
            pl.later(delay, () -> {
                if (!v.isOnline()) return;
                Location l = v.getLocation();
                float vol = big ? 3f : 2f;
                v.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("minecraft", "entity.generic.explode"),
                        net.kyori.adventure.sound.Sound.Source.MASTER, vol, 0.4f), l.getX(), l.getY(), l.getZ());
                v.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("minecraft", "entity.wither.death"),
                        net.kyori.adventure.sound.Sound.Source.MASTER, vol * 0.6f, 0.4f), l.getX(), l.getY(), l.getZ());
                pl.cine.shake(List.of(v), 6, 2);
            });
        }

        // la boule de feu efface tout (avec un budget de temps : pas de lag)
        MegaHole hole = pl.ruin.mega(c, new Vector(0, -1, 0), 0.01, R, pl.cfgi("bombes.restore-seconds", 360));
        if (hole != null) hole.trigger();
        Set<UUID> hit = new HashSet<>(), burnt = new HashSet<>();
        double[] cur = {2};
        double[] wave = {R * 0.5};
        int[] tick = {0};
        org.bukkit.scheduler.BukkitTask[] loop = new org.bukkit.scheduler.BukkitTask[1];
        loop[0] = org.bukkit.Bukkit.getScheduler().runTaskTimer(pl, () -> {
            int i = tick[0]++;
            double target = hole == null || !hole.erasing() ? R : hole.radiusProgress();
            cur[0] = Math.min(R, Math.max(cur[0] + 0.3, Math.min(target, cur[0] + 4)));
            double e0 = cur[0];
            Draw.sphere(c, e0, FIRE, 5f, (int) Math.min(320, 60 + e0 * 5));
            Draw.sphere(c, e0 * 0.85, FIRE_CORE, 4f, (int) Math.min(200, 40 + e0 * 3));
            Draw.ball(c, Math.max(2, e0 * 0.4), Draw.WHITE, 4f, 60);
            Draw.fx(c, Particle.FLAME, 60, e0 * 0.5, 0.3);
            Draw.fx(c, Particle.LAVA, 10, e0 * 0.4, 0);
            // l'onde de choc file bien plus loin que la boule de feu
            wave[0] += 3;
            if (wave[0] < R * 4) {
                Draw.ring(ground.clone().add(0, 1, 0), wave[0], Particle.CLOUD, (int) Math.min(220, wave[0] * 2));
                Draw.ring(ground.clone().add(0, 2.5, 0), wave[0] * 0.98, SMOKE, 3f, (int) Math.min(160, wave[0] * 1.5));
                if (i % 3 == 0) pl.ruin.pop(ground, wave[0], (int) Math.min(30, 6 + wave[0] / 4), 0.6);
                for (LivingEntity le : pl.targets(p, ground, wave[0])) {
                    double dd = le.getLocation().distance(ground);
                    if (dd < wave[0] - 4 || !burnt.add(le.getUniqueId())) continue;
                    Vector out = le.getLocation().toVector().subtract(ground.toVector()).setY(0);
                    if (out.lengthSquared() < 0.01) out = new Vector(1, 0, 0);
                    double force = Math.max(0.4, 3.5 * (1 - dd / (R * 4)));
                    le.setVelocity(out.normalize().multiply(force).setY(0.6));
                    if (dd < R * 2.5) {
                        le.setFireTicks(Math.max(le.getFireTicks(), 200));
                        pl.hit(p, le, Math.max(4, 40 * (1 - dd / (R * 2.5))), "bombe");
                    }
                }
            }
            if (i % 5 == 0) {
                for (LivingEntity le : pl.targets(p, c, e0 + 1)) {
                    if (hit.add(le.getUniqueId())) pl.hit(p, le, dmg, "bombe");
                    le.setFireTicks(300);
                }
                for (Entity e : w.getNearbyEntities(c, e0, e0, e0)) {
                    if (e instanceof Item && e.getLocation().distanceSquared(c) <= e0 * e0) e.remove();
                }
            }
            if (i % 8 == 0) SmpGojo.sound(c, "entity.generic.explode", 10f, 0.4f);
            boolean finished = e0 >= R && (hole == null || !hole.erasing()) && wave[0] >= R * 4;
            if (finished || i >= 1200) {
                loop[0].cancel();
                aftermath(ground, R, big);
            }
        }, 1L, 1L);
    }

    /** Sol brûlé, fissures, vitres soufflées, puis le champignon atomique. */
    private void aftermath(Location ground, double R, boolean big) {
        pl.ruin.scorch(ground, Math.min(110, R * 1.7), 0.75);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int cracks = big ? 14 : 6;
        for (int k = 0; k < cracks; k++) {
            double a = k * Math.PI * 2 / cracks + rnd.nextDouble(-0.2, 0.2);
            pl.later(k * 2L, () -> pl.ruin.crack(ground, a, R * 0.9, Math.min(120, R * (0.6 + rnd.nextDouble() * 0.6)), true));
        }
        pl.ruin.shatter(ground, Math.min(30, R + 10));
        mushroom(ground, R, pl.cfgi(big ? "bombes.tsar.cloud-seconds" : "bombes.satan.cloud-seconds", big ? 40 : 20) * 20);
    }

    /** Le champignon atomique : une colonne qui monte, coiffée d'un anneau de feu et de fumée qui roule sur lui-même. */
    private void mushroom(Location ground, double R, int ticks) {
        World w = ground.getWorld();
        double maxH = Math.min(R * 2.6, w.getMaxHeight() - 10 - ground.getY());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        pl.repeat(2, ticks / 2, i -> {
            double t = Math.min(1, i / (ticks / 2.0 * 0.5));
            double age = i / (ticks / 2.0);
            double h = maxH * (0.15 + 0.85 * Math.sqrt(t));
            double stemR = R * 0.22 * (1 + 0.4 * t);
            // colonne
            for (int k = 0; k < 30; k++) {
                double y = rnd.nextDouble() * h * 0.9, a = rnd.nextDouble(Math.PI * 2), r0 = stemR * (0.6 + rnd.nextDouble() * 0.5) * (1 - y / h * 0.3);
                Location l = ground.clone().add(Math.cos(a) * r0, y, Math.sin(a) * r0);
                Draw.dust(l, y < h * 0.25 && age < 0.6 ? EMBER : SMOKE, 4f);
            }
            w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, ground.clone().add(0, h * 0.4, 0), 6, stemR * 0.5, h * 0.3, stemR * 0.5, 0.02, null, true);
            // chapeau : un tore qui roule
            Location top = ground.clone().add(0, h, 0);
            double big = R * 0.55 * (0.5 + 0.5 * t) * (1 + age * 0.4), small = big * 0.45;
            for (int k = 0; k < 110; k++) {
                double a = rnd.nextDouble(Math.PI * 2), b = rnd.nextDouble(Math.PI * 2) + i * 0.08;
                double rr = big + small * Math.cos(b);
                Location l = top.clone().add(Math.cos(a) * rr, small * Math.sin(b) * 0.8, Math.sin(a) * rr);
                Color col = age < 0.35 && Math.sin(b) < 0 ? FIRE : age < 0.7 && Math.sin(b) < -0.3 ? EMBER : (Math.sin(b) > 0.3 ? SMOKE : SMOKE_DARK);
                Draw.dust(l, col, 5f);
            }
            w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, top, 10, big * 0.5, small * 0.4, big * 0.5, 0.02, null, true);
            // anneau de condensation autour de la colonne
            if (t < 1 && i % 2 == 0) Draw.ring(ground.clone().add(0, h * 0.55, 0), stemR * 2.2, Particle.CLOUD, 40);
            // braises au sol
            if (age < 0.7) {
                for (int k = 0; k < 8; k++) {
                    Location l = ground.clone().add(rnd.nextDouble(-R, R), 0.5, rnd.nextDouble(-R, R));
                    Draw.fx(l, Particle.FLAME, 2, 0.3, 0.02);
                }
            }
            if (i % 30 == 0) SmpGojo.sound(ground, "entity.generic.explode", 6f, 0.3f);
        }, null);
    }
}
