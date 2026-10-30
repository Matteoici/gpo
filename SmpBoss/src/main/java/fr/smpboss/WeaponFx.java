package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Effets du resource pack pour les capacités des armes (en plus des particules, que tout le monde voit) :
 * entailles façon anime, anneaux, fissures, éclairs, cercles magiques, explosions dessinées, et des sons perso.
 */
final class WeaponFx extends PackFx {

    static final Color VIOLET = Color.fromRGB(170, 90, 255), CYAN = Color.fromRGB(80, 230, 255), BLOOD = Color.fromRGB(215, 20, 40),
            DARK_RED = Color.fromRGB(90, 0, 14), ORANGE_C = Color.fromRGB(255, 120, 20), YELLOW = Color.fromRGB(255, 210, 60),
            TEAL = Color.fromRGB(60, 240, 210), PURPLE = Color.fromRGB(150, 40, 230), ICE = Color.fromRGB(150, 225, 255),
            SKY = Color.fromRGB(140, 205, 255), DUST = Color.fromRGB(120, 95, 70), SOUL = Color.fromRGB(110, 255, 235);

    WeaponFx(SmpBoss plugin, Ultimates ult) {
        super(plugin, ult);
    }

    // ================================================================= outils

    ItemDisplay t(Location at, String name, Color c, float sx, float sy, float sz, Display.Billboard bb, Quaternionf rot) {
        return spriteOf(at, tmodel(name, c), sx, sy, sz, bb, rot);
    }

    /** Apparaît en grossissant, reste, puis rétrécit. */
    ItemDisplay tpop(Location at, String name, Color c, float sx, float sy, float sz, int grow, int hold, int shrink, Display.Billboard bb, Quaternionf rot) {
        ItemDisplay d = t(at, name, c, sx * 0.05f, sy * 0.05f, sz * 0.05f, bb, rot);
        later(1, () -> tween(d, new Vector3f(), rot, new Vector3f(sx, sy, sz), grow));
        later(1 + grow + hold, () -> tween(d, new Vector3f(), rot, new Vector3f(sx * 0.02f, sy * 0.02f, sz * 0.02f), shrink));
        kill(d, 2 + grow + hold + shrink);
        return d;
    }

    /** Anneau qui s'élargit en s'amincissant (onde). */
    void wave(Location at, String name, Color c, float r0, float r1, int ticks, Quaternionf rot, Display.Billboard bb) {
        ItemDisplay d = t(at, name, c, r0, r0, r0, bb, rot);
        later(1, () -> tween(d, new Vector3f(), rot, new Vector3f(r1, r1, r1), ticks));
        kill(d, ticks + 2);
    }

    /** Animation image par image teintée (explosions w_burst_, fumée w_puff_). */
    void tflip(Location at, String prefix, int frames, int every, Color c, float s0, float s1, Display.Billboard bb) {
        ItemDisplay d = t(at, prefix + "0", c, s0, s0, s0, bb, null);
        later(1, () -> tween(d, new Vector3f(), null, new Vector3f(s1, s1, s1), frames * every));
        for (int f = 1; f < frames; f++) {
            int ff = f;
            later(f * every, () -> {
                if (d.isValid()) d.setItemStack(tmodel(prefix + ff, c));
            });
        }
        kill(d, frames * every + 1);
    }

    void boom(Location at, Color c, float size) {
        tflip(at, "w_burst_", 12, 1, c, size * 0.3f, size, Display.Billboard.CENTER);
        tpop(at, "w_glow", c, size * 1.4f, size * 1.4f, size * 1.4f, 1, 1, 5, Display.Billboard.CENTER, null);
        wave(at, "w_shock", c, size * 0.3f, size * 1.8f, 7, null, Display.Billboard.CENTER);
    }

    void puff(Location at, Color c, float size) {
        tflip(at, "w_puff_", 8, 2, c, size * 0.5f, size, Display.Billboard.CENTER);
    }

    /** Son perso du pack, placé dans le monde (seulement ceux qui ont le pack l'entendent). */
    void snd(Location at, String key, float vol, float pitch) {
        double r = 16 * Math.max(1, vol);
        for (Player o : at.getWorld().getPlayers()) {
            if (!PackStatus.has(o) || o.getLocation().distanceSquared(at) > r * r) continue;
            o.playSound(at, NS + ":" + key, SoundCategory.PLAYERS, vol, pitch);
        }
    }

    static Vector3f v(Vector d) {
        return new Vector3f((float) d.getX(), (float) d.getY(), (float) d.getZ());
    }

    /** Rotation qui envoie l'axe Y du modèle sur "dir". */
    static Quaternionf alongY(Vector dir) {
        return new Quaternionf().rotationTo(new Vector3f(0, 1, 0), v(dir.clone().normalize()));
    }

    /** Rotation dont les axes X et Y du modèle deviennent x et y (perpendiculaires). */
    static Quaternionf basis(Vector x, Vector y) {
        Vector3f X = v(x.clone().normalize()), Y = v(y.clone().normalize());
        Vector3f Z = new Vector3f(X).cross(Y).normalize();
        Y = new Vector3f(Z).cross(X).normalize();
        return new Quaternionf().setFromNormalized(new Matrix3f(X, Y, Z));
    }

    // ================================================================= Sonic Bow : l'onde

    void sonic(Location eye, Vector dir, double len) {
        Vector d = dir.clone().normalize();
        Quaternionf ring = alongY(d);
        snd(eye, "weapons.sonic", 3f, 1f);
        Location muzzle = eye.clone().add(d.clone().multiply(1.3));
        tpop(muzzle, "w_glow", CYAN, 3, 3, 3, 1, 1, 5, Display.Billboard.CENTER, null);
        wave(muzzle, "w_ring_flat", VIOLET, 0.5f, 4.5f, 6, ring, Display.Billboard.FIXED);
        // le rayon lui-même
        Location mid = eye.clone().add(d.clone().multiply(len / 2));
        tpop(mid, "w_beam_x", CYAN, 1.2f, (float) len, 1.2f, 1, 2, 7, Display.Billboard.FIXED, ring);
        tpop(mid, "w_beam_x", VIOLET, 2.4f, (float) len, 2.4f, 2, 1, 6, Display.Billboard.FIXED, ring);
        // les anneaux qui partent le long du rayon (12 blocs par tick)
        int ticks = (int) Math.ceil(len / 12);
        for (int k = 0; k <= ticks; k++) {
            int kk = k;
            later(Math.max(1, k), () -> {
                for (double s = kk * 12; s < Math.min(len, kk * 12 + 12); s += 3) {
                    Location at = eye.clone().add(d.clone().multiply(s + 1.5));
                    wave(at, "w_ring_flat", ((int) s / 3) % 2 == 0 ? VIOLET : CYAN, 1.2f, 3.2f, 8, ring, Display.Billboard.FIXED);
                }
            });
        }
        Location end = eye.clone().add(d.clone().multiply(len));
        later(ticks + 1, () -> {
            boom(end, VIOLET, 5);
            snd(end, "weapons.impact", 1.2f, 1.6f);
        });
    }

    // ================================================================= Tranche : l'entaille qui vole

    void slash(Location start, Vector fwd, Vector axis, int steps) {
        Vector f = fwd.clone().normalize();
        Quaternionf q = basis(axis, f);
        snd(start, "weapons.slash", 2f, 1f);
        ItemDisplay big = t(start.clone().add(f.clone().multiply(1.5)), "w_slash", BLOOD, 0.5f, 0.3f, 1, Display.Billboard.FIXED, q);
        ItemDisplay dark = t(start.clone().add(f.clone().multiply(0.8)), "w_slash", DARK_RED, 0.5f, 0.3f, 1, Display.Billboard.FIXED, q);
        later(1, () -> {
            tween(big, new Vector3f(), q, new Vector3f(7, 4.5f, 1), 3);
            tween(dark, new Vector3f(), q, new Vector3f(8, 5, 1), 4);
        });
        int[] i = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = i[0]++;
            if (k >= steps || !big.isValid()) {
                task[0].cancel();
                tween(big, new Vector3f(), q, new Vector3f(0.1f, 0.1f, 1), 4);
                tween(dark, new Vector3f(), q, new Vector3f(0.1f, 0.1f, 1), 4);
                kill(big, 5);
                kill(dark, 5);
                return;
            }
            Location c = start.clone().add(f.clone().multiply(1.5 + k * 1.1));
            big.teleport(c);
            dark.teleport(start.clone().add(f.clone().multiply(0.9 + k * 1.1)));
            if (k % 2 == 0) tpop(c.clone().add(axis.clone().multiply(rnd(2.5))), "w_spark", Color.WHITE, 1.2f, 1.2f, 1.2f, 1, 0, 3, Display.Billboard.CENTER, null);
            if (c.getBlock().getType().isSolid()) {
                boom(c, BLOOD, 4);
                i[0] = steps;
            }
        }, 1L, 1L);
    }

    void slashHit(Location at) {
        tpop(at, "w_spark", Color.WHITE, 3, 3, 3, 1, 1, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) ThreadLocalRandom.current().nextDouble(6.28)));
        tpop(at, "w_glow", BLOOD, 3.5f, 3.5f, 3.5f, 1, 1, 4, Display.Billboard.CENTER, null);
    }

    // ================================================================= Brise-Terre : la faille

    void rift(Location origin, Vector fwd, int len) {
        Vector f = fwd.clone().setY(0).normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(1, 0, 0), v(f));
        snd(origin, "weapons.quake", 3f, 1f);
        ult.quake(origin, 20, 12, 2.5f);
        wave(origin.clone().add(0, 0.15, 0), "w_shock_flat", ORANGE_C, 1, 9, 8, null, Display.Billboard.FIXED);
        for (int i = 0; i < len; i += 3) {
            int ii = i;
            later(1 + i, () -> {
                Location probe = origin.clone().add(f.clone().multiply(ii + 1.5)).add(0, 3, 0);
                Location g = groundBelow(probe, 8);
                if (g == null) return;
                Location gc = g.add(0, 0.08, 0);
                ItemDisplay cr = t(gc, "w_crack_flat", ORANGE_C, 0.2f, 1, 3.6f, Display.Billboard.FIXED, q);
                later(1, () -> tween(cr, new Vector3f(), q, new Vector3f(3.8f, 1, 3.6f), 2));
                later(50, () -> tween(cr, new Vector3f(), q, new Vector3f(3.8f, 1, 0.05f), 15));
                kill(cr, 66);
                puff(gc.clone().add(0, 1, 0), DUST, 3.2f);
                if (ii % 6 == 0) tflip(gc.clone().add(0, 1.2, 0), "w_burst_", 12, 1, ORANGE_C, 1, 3.5f, Display.Billboard.CENTER);
            });
        }
    }

    void aftershock(Location mid) {
        snd(mid, "weapons.quake", 3f, 0.8f);
        boom(mid.clone().add(0, 1, 0), ORANGE_C, 7);
        wave(mid.clone().add(0, 0.2, 0), "w_shock_flat", ORANGE_C, 1, 16, 10, null, Display.Billboard.FIXED);
        ult.quake(mid, 26, 16, 3.5f);
    }

    // ================================================================= Faux des Âmes

    void soulLance(Location eye, Vector dir, double len) {
        Vector d = dir.clone().normalize();
        Quaternionf q = alongY(d);
        snd(eye, "weapons.soul", 2f, 1.1f);
        Location mid = eye.clone().add(d.clone().multiply(len / 2));
        tpop(mid, "w_spike_x", SOUL, 1.6f, (float) len, 1.6f, 2, 2, 6, Display.Billboard.FIXED, q);
        tpop(mid, "w_beam_x", TEAL, 0.7f, (float) len, 0.7f, 1, 2, 6, Display.Billboard.FIXED, q);
        for (double s = 2; s < len; s += 2.5) {
            Location at = eye.clone().add(d.clone().multiply(s));
            int dl = (int) (s / 8);
            later(1 + dl, () -> wave(at, "w_ring_flat", TEAL, 0.4f, 1.8f, 6, q, Display.Billboard.FIXED));
        }
    }

    void markHit(Location at) {
        tpop(at, "w_rune_flat", TEAL, 2.2f, 1, 2.2f, 2, 6, 4, Display.Billboard.FIXED, null);
        tpop(at, "w_glow", SOUL, 2.5f, 2.5f, 2.5f, 1, 1, 5, Display.Billboard.CENTER, null);
    }

    void detonate(Location c) {
        snd(c, "weapons.soul_boom", 2.5f, 1f);
        boom(c, TEAL, 6);
        wave(c.clone().add(0, -0.9, 0), "w_shock_flat", SOUL, 1, 8, 8, null, Display.Billboard.FIXED);
        ult.quake(c, 14, 8, 2f);
    }

    void soulRain(Location center, double r, int ticks) {
        snd(center, "weapons.soul", 2.5f, 0.8f);
        ItemDisplay rune = t(center.clone().add(0, 0.1, 0), "w_rune_flat", TEAL, 0.2f, 1, 0.2f, Display.Billboard.FIXED, null);
        float s = (float) (r * 2.2);
        later(1, () -> tween(rune, new Vector3f(), null, new Vector3f(s, 1, s), 6));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int k = 0; k < ticks; k += 2) {
            int kk = k;
            later(2 + k, () -> {
                if (!rune.isValid()) return;
                tween(rune, new Vector3f(), new Quaternionf().rotateY(kk * 0.12f), new Vector3f(s, 1, s), 2);
                double a = rnd.nextDouble(Math.PI * 2), rr = rnd.nextDouble(r);
                Location top = center.clone().add(Math.cos(a) * rr, 9, Math.sin(a) * rr);
                ItemDisplay sp = t(top, "w_spike_x", SOUL, 0.8f, 3.5f, 0.8f, Display.Billboard.FIXED, alongY(new Vector(0, -1, 0)));
                later(1, () -> tween(sp, new Vector3f(0, -8.5f, 0), alongY(new Vector(0, -1, 0)), new Vector3f(0.8f, 3.5f, 0.8f), 4));
                kill(sp, 6);
                later(5, () -> wave(top.clone().add(0, -8.8, 0), "w_ring_flat", TEAL, 0.3f, 2.2f, 5, null, Display.Billboard.FIXED));
            });
        }
        later(ticks + 2, () -> tween(rune, new Vector3f(), null, new Vector3f(0.05f, 1, 0.05f), 8));
        kill(rune, ticks + 12);
    }

    // ================================================================= épiques

    void pumpkinSlash(Location feet, Vector f, double reach) {
        Vector d = f.clone().setY(0).normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, -1), v(d));
        Location c = feet.clone().add(0, 1.1, 0).add(d.clone().multiply(reach * 0.45));
        snd(feet, "weapons.fire_slash", 2f, 1f);
        float s = (float) (reach * 2.1);
        ItemDisplay a = t(c, "w_slash_flat", ORANGE_C, 0.3f, 1, 0.3f, Display.Billboard.FIXED, q);
        ItemDisplay b = t(c.clone().add(0, 0.25, 0), "w_slash_flat", YELLOW, 0.3f, 1, 0.3f, Display.Billboard.FIXED, q);
        later(1, () -> tween(a, new Vector3f(), q, new Vector3f(s, 1, s * 0.75f), 2));
        later(2, () -> tween(b, new Vector3f(), q, new Vector3f(s * 0.8f, 1, s * 0.6f), 2));
        later(6, () -> {
            tween(a, new Vector3f(), q, new Vector3f(s * 1.1f, 1, 0.05f), 4);
            tween(b, new Vector3f(), q, new Vector3f(s, 1, 0.05f), 4);
        });
        kill(a, 11);
        kill(b, 11);
        for (int i = -1; i <= 1; i++) {
            Location at = feet.clone().add(0, 1.1, 0).add(d.clone().rotateAroundY(Math.toRadians(i * 40)).multiply(reach * 0.85));
            later(2, () -> tflip(at, "w_burst_", 12, 1, ORANGE_C, 0.6f, 2.4f, Display.Billboard.CENTER));
        }
    }

    void wispShot(Location eye, List<? extends Entity> arrows) {
        snd(eye, "weapons.wisp", 2f, 1f);
        tpop(eye, "w_glow", CYAN, 2.5f, 2.5f, 2.5f, 1, 1, 5, Display.Billboard.CENTER, null);
        tpop(eye, "w_spark", Color.WHITE, 2, 2, 2, 1, 1, 3, Display.Billboard.CENTER, null);
        int[] k = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean any = false;
            for (Entity a : arrows) {
                if (!a.isValid() || (a instanceof org.bukkit.entity.AbstractArrow ar && ar.isInBlock())) continue;
                any = true;
                tpop(a.getLocation(), "w_glow", k[0] % 2 == 0 ? CYAN : SOUL, 1.1f, 1.1f, 1.1f, 1, 0, 6, Display.Billboard.CENTER, null);
                if (k[0] % 3 == 0) tpop(a.getLocation().add(rnd(0.4), rnd(0.4), rnd(0.4)), "w_spark", Color.WHITE, 0.6f, 0.6f, 0.6f, 1, 0, 3, Display.Billboard.CENTER, null);
            }
            if (!any || ++k[0] > 60) task[0].cancel();
        }, 1L, 1L);
    }

    void cursedSlam(Location c, double r) {
        snd(c, "weapons.curse", 2.5f, 1f);
        ult.quake(c, 14, 10, 3f);
        Location g = c.clone().add(0, 0.12, 0);
        float s = (float) (r * 2.3);
        ItemDisplay rune = t(g, "w_rune_flat", PURPLE, 0.3f, 1, 0.3f, Display.Billboard.FIXED, null);
        later(1, () -> tween(rune, new Vector3f(), new Quaternionf().rotateY(1.2f), new Vector3f(s, 1, s), 4));
        later(12, () -> tween(rune, new Vector3f(), new Quaternionf().rotateY(2f), new Vector3f(0.05f, 1, 0.05f), 8));
        kill(rune, 22);
        wave(g, "w_shock_flat", VIOLET, 1, s * 1.3f, 8, null, Display.Billboard.FIXED);
        boom(c.clone().add(0, 1, 0), PURPLE, 5);
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6;
            puff(c.clone().add(Math.cos(a) * r * 0.7, 0.6, Math.sin(a) * r * 0.7), Color.fromRGB(60, 30, 80), 2.6f);
        }
    }

    // ================================================================= Croc de Givre

    void frostDash(Player p, int ticks) {
        snd(p.getLocation(), "weapons.frost", 2.5f, 1f);
        tpop(p.getLocation().add(0, 1, 0), "w_glow", ICE, 3, 3, 3, 1, 1, 5, Display.Billboard.CENTER, null);
        int[] k = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++k[0] > ticks || !p.isOnline() || p.isDead()) {
                task[0].cancel();
                if (p.isOnline()) {
                    Location l = p.getLocation().add(0, 1, 0);
                    boom(l, ICE, 4);
                    for (int i = 0; i < 6; i++) tpop(l.clone().add(rnd(1.5), rnd(1), rnd(1.5)), "w_spark", Color.WHITE, 1.4f, 1.4f, 1.4f, 1, 1, 4, Display.Billboard.CENTER, null);
                }
                return;
            }
            Location l = p.getLocation().add(0, 1, 0);
            puff(l, ICE, 2.2f);
            tpop(l.clone().add(rnd(0.6), rnd(0.8), rnd(0.6)), "w_spark", Color.WHITE, 1f, 1f, 1f, 1, 0, 4, Display.Billboard.CENTER, null);
            Vector vel = p.getVelocity();
            if (vel.lengthSquared() > 0.05) tpop(l, "w_spike_x", ICE, 0.9f, 2.6f, 0.9f, 1, 0, 5, Display.Billboard.FIXED, alongY(vel.clone().multiply(-1)));
        }, 1L, 1L);
    }

    void frostHit(Location c) {
        tflip(c, "w_burst_", 12, 1, ICE, 1, 3.5f, Display.Billboard.CENTER);
        tpop(c, "w_spark", Color.WHITE, 2.4f, 2.4f, 2.4f, 1, 1, 4, Display.Billboard.CENTER, null);
    }

    // ================================================================= Arc Tempête

    void stormArrow(Entity arrow) {
        int[] k = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!arrow.isValid() || (arrow instanceof org.bukkit.entity.AbstractArrow a && a.isInBlock()) || ++k[0] > 100) {
                task[0].cancel();
                return;
            }
            tpop(arrow.getLocation(), "w_glow", SKY, 1.2f, 1.2f, 1.2f, 1, 0, 5, Display.Billboard.CENTER, null);
            if (k[0] % 2 == 0) tpop(arrow.getLocation(), "w_spark", Color.WHITE, 0.8f, 0.8f, 0.8f, 1, 0, 2, Display.Billboard.CENTER, new Quaternionf().rotateZ(k[0]));
        }, 1L, 1L);
    }

    void skyBolt(Location at) {
        snd(at, "weapons.thunder", 4f, 1f);
        Quaternionf q = alongY(new Vector(0, 1, 0));
        Location mid = at.clone().add(0, 14, 0);
        ItemDisplay b = t(mid, "w_bolt_0_x", SKY, 3, 30, 3, Display.Billboard.FIXED, q);
        for (int f = 1; f < 6; f++) {
            int ff = f;
            later(f, () -> {
                if (b.isValid()) b.setItemStack(tmodel("w_bolt_" + (ff % 4) + "_x", ff % 2 == 0 ? SKY : Color.WHITE));
            });
        }
        kill(b, 7);
        boom(at.clone().add(0, 1, 0), SKY, 5);
        wave(at.clone().add(0, 0.15, 0), "w_shock_flat", SKY, 1, 10, 8, null, Display.Billboard.FIXED);
        ult.quake(at, 18, 8, 2f);
    }

    void chain(Location from, Location to) {
        Vector seg = to.toVector().subtract(from.toVector());
        double len = seg.length();
        if (len < 0.3) return;
        Quaternionf q = alongY(seg);
        Location mid = from.clone().add(seg.clone().multiply(0.5));
        ItemDisplay b = t(mid, "w_bolt_" + ThreadLocalRandom.current().nextInt(4) + "_x", SKY, 1.6f, (float) len, 1.6f, Display.Billboard.FIXED, q);
        later(2, () -> {
            if (b.isValid()) b.setItemStack(tmodel("w_bolt_" + ThreadLocalRandom.current().nextInt(4) + "_x", Color.WHITE));
        });
        kill(b, 5);
        tflip(to, "w_burst_", 12, 1, SKY, 0.8f, 3, Display.Billboard.CENTER);
        snd(to, "weapons.thunder", 1.5f, 1.4f);
    }

    // ================================================================= armes de la Reine Araignée

    static final Color VENOM = Color.fromRGB(70, 235, 130), WEB = Color.fromRGB(225, 235, 255), ABYSS = Color.fromRGB(40, 90, 255);

    void venomDash(Player p, int ticks) {
        snd(p.getLocation(), "spider.hiss", 2f, 1.4f);
        int[] k = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++k[0] > ticks || !p.isOnline() || p.isDead()) {
                task[0].cancel();
                return;
            }
            Location l = p.getLocation().add(0, 1, 0);
            Vector vel = p.getVelocity();
            if (vel.lengthSquared() > 0.05) tpop(l, "w_spike_x", VENOM, 0.9f, 2.6f, 0.9f, 1, 0, 5, Display.Billboard.FIXED, alongY(vel.clone().multiply(-1)));
            puff(l, Color.fromRGB(30, 90, 50), 1.6f);
        }, 1L, 1L);
    }

    void venomHit(Location at) {
        tflip(at, "w_burst_", 12, 1, VENOM, 0.8f, 2.8f, Display.Billboard.CENTER);
        tpop(at, "w_spark", Color.WHITE, 1.8f, 1.8f, 1.8f, 1, 1, 3, Display.Billboard.CENTER, null);
    }

    void webArrow(Entity arrow) {
        Location[] prev = {arrow.getLocation()};
        int[] k = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!arrow.isValid() || (arrow instanceof org.bukkit.entity.AbstractArrow a && a.isInBlock()) || ++k[0] > 80) {
                task[0].cancel();
                return;
            }
            Location now = arrow.getLocation();
            Vector seg = now.toVector().subtract(prev[0].toVector());
            if (seg.lengthSquared() > 0.04) {
                Location mid = prev[0].clone().add(seg.clone().multiply(0.5));
                tpop(mid, "w_beam_x", WEB, 0.12f, (float) seg.length(), 0.12f, 1, 14, 6, Display.Billboard.FIXED, alongY(seg));
            }
            prev[0] = now;
        }, 1L, 1L);
    }

    void webTrap(Location at) {
        snd(at, "spider.web", 2f, 1f);
        Location g = at.clone().add(0, 0.1, 0);
        ItemDisplay web = t(g, "w_web_flat", WEB, 0.3f, 1, 0.3f, Display.Billboard.FIXED, null);
        later(1, () -> tween(web, new Vector3f(), null, new Vector3f(5, 1, 5), 3));
        later(70, () -> tween(web, new Vector3f(), null, new Vector3f(0.05f, 1, 0.05f), 8));
        kill(web, 80);
        tpop(at.clone().add(0, 1, 0), "w_web", WEB, 3.5f, 3.5f, 3.5f, 2, 4, 4, Display.Billboard.CENTER, null);
    }

    void queenFang(Location c, double r, List<? extends Entity> caught) {
        snd(c, "spider.screech", 2.5f, 1f);
        ult.quake(c, 12, 8, 2.5f);
        float s = (float) (r * 2.2);
        ItemDisplay web = t(c.clone().add(0, 0.12, 0), "w_web_flat", ABYSS, 0.3f, 1, 0.3f, Display.Billboard.FIXED, null);
        later(1, () -> tween(web, new Vector3f(), new Quaternionf().rotateY(0.6f), new Vector3f(s, 1, s), 5));
        later(16, () -> tween(web, new Vector3f(), new Quaternionf().rotateY(1.2f), new Vector3f(0.05f, 1, 0.05f), 8));
        kill(web, 26);
        Location hand = c.clone().add(0, 1.2, 0);
        for (Entity en : caught) {
            Location to = en.getLocation().add(0, en.getHeight() * 0.6, 0);
            Vector seg = to.toVector().subtract(hand.toVector());
            if (seg.lengthSquared() < 0.3) continue;
            Location mid = hand.clone().add(seg.clone().multiply(0.5));
            tpop(mid, "w_beam_x", WEB, 0.18f, (float) seg.length(), 0.18f, 1, 10, 4, Display.Billboard.FIXED, alongY(seg));
        }
    }

    void queenFangBurst(Location c, double r) {
        snd(c, "weapons.impact", 2f, 1.4f);
        boom(c.clone().add(0, 1, 0), VENOM, (float) Math.max(4, r * 0.7));
        wave(c.clone().add(0, 0.2, 0), "w_shock_flat", ABYSS, 1, (float) (r * 2.4), 8, null, Display.Billboard.FIXED);
        ult.quake(c, 14, 10, 3f);
    }

    // ---------------------------------------------------------------- Faux de la Tisseuse : le filet
    static final Color DEEP = Color.fromRGB(18, 26, 80);

    void weaverNet(Player p, Vector f, double range, List<? extends Entity> caught) {
        Location eye = p.getEyeLocation();
        snd(eye, "spider.web", 2f, 0.8f);
        Location start = eye.clone().add(f.clone().multiply(1.2)).add(0, -0.3, 0);
        Quaternionf face = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), v(f));
        ItemDisplay net = t(start, "w_web", WEB, 1.2f, 1.2f, 1.2f, Display.Billboard.FIXED, face);
        float big = (float) (range * 1.15);
        Vector3f fly = v(f.clone().multiply(range * 0.7));
        later(1, () -> tween(net, fly, new Quaternionf(face).rotateZ(1.2f), new Vector3f(big, big, big), 6));
        later(10, () -> tween(net, fly, new Quaternionf(face).rotateZ(1.6f), new Vector3f(0.05f, 0.05f, 0.05f), 8));
        kill(net, 20);
        // particules pour tout le monde (avec ou sans pack)
        Particle.DustOptions silk = new Particle.DustOptions(Color.fromRGB(230, 238, 255), 1.2f);
        for (int i = 1; i <= 6; i++) {
            int ii = i;
            later(i, () -> {
                Location c = start.clone().add(f.clone().multiply(range * 0.7 * ii / 6.0));
                double w = 0.6 + ii * range * 0.12;
                Vector side = new Vector(-f.getZ(), 0, f.getX());
                for (int k = -4; k <= 4; k++)
                    c.getWorld().spawnParticle(Particle.DUST, c.clone().add(side.clone().multiply(w * k / 4.0)).add(0, Math.sin(k) * 0.3, 0), 2, 0.05, 0.25, 0.05, 0, silk);
            });
        }
        Location hand = eye.clone().add(0, -0.5, 0);
        later(5, () -> {
            for (Entity en : caught) {
                if (!en.isValid()) continue;
                Location to = en.getLocation().add(0, en.getHeight() * 0.6, 0);
                Vector seg = to.toVector().subtract(hand.toVector());
                if (seg.lengthSquared() < 0.3) continue;
                tpop(hand.clone().add(seg.clone().multiply(0.5)), "w_beam_x", WEB, 0.14f, (float) seg.length(), 0.14f, 1, 8, 4, Display.Billboard.FIXED, alongY(seg));
            }
        });
    }

    // ---------------------------------------------------------------- Sceptre de l'Abysse : les huit pattes
    void abyssLegsWarn(Location c, double r) {
        Location g = c.clone().add(0, 0.08, 0);
        float d = (float) (r * 2.1);
        ItemDisplay rune = t(g, "w_rune_flat", ABYSS, 0.3f, 1, 0.3f, Display.Billboard.FIXED, null);
        later(1, () -> tween(rune, new Vector3f(), new Quaternionf().rotateY(1.4f), new Vector3f(d, 1, d), 12));
        later(14, () -> tween(rune, new Vector3f(), new Quaternionf().rotateY(2.2f), new Vector3f(0.05f, 1, 0.05f), 6));
        kill(rune, 22);
        wave(g, "w_shock_flat", DEEP, d, 0.4f, 14, null, Display.Billboard.FIXED);
        snd(c, "spider.hiss", 2.5f, 0.6f);
        Particle.DustOptions blue = new Particle.DustOptions(ABYSS, 1.6f);
        for (int i = 0; i < 16; i += 2) {
            int ii = i;
            later(i, () -> {
                for (int k = 0; k < 24; k++) {
                    double a = Math.PI * 2 * k / 24 + ii * 0.1;
                    c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 1, 0, 0, 0, 0, blue);
                }
            });
        }
    }

    void abyssLegs(Location c, double r) {
        snd(c, "spider.screech", 3f, 0.6f);
        snd(c, "weapons.impact", 2.5f, 0.7f);
        Location target = c.clone().add(0, 0.6, 0);
        for (int k = 0; k < 8; k++) {
            double a = Math.PI * 2 * k / 8 + 0.2;
            Location base = c.clone().add(Math.cos(a) * (r + 2.5), -0.6, Math.sin(a) * (r + 2.5));
            Location knee = c.clone().add(Math.cos(a) * (r * 0.75), r * 0.9 + 1.5, Math.sin(a) * (r * 0.75));
            Location tip = target.clone().add(Math.cos(a) * 0.7, 0, Math.sin(a) * 0.7);
            int dl = k % 2;
            for (Location[] sg : new Location[][]{{base, knee}, {knee, tip}}) {
                Vector seg = sg[1].toVector().subtract(sg[0].toVector());
                Location mid = sg[0].clone().add(seg.clone().multiply(0.5));
                float th = sg[0] == base ? 1.5f : 1.1f;
                later(dl, () -> tpop(mid, "w_spike_x", DEEP, th, (float) seg.length(), th, 3, 10, 6, Display.Billboard.FIXED, alongY(seg)));
            }
            later(dl, () -> wave(base.clone().add(0, 0.7, 0), "w_shock_flat", ABYSS, 0.5f, 3.5f, 8, null, Display.Billboard.FIXED));
        }
        later(3, () -> {
            boom(target.clone().add(0, 0.6, 0), ABYSS, (float) Math.max(4, r * 0.9));
            wave(c.clone().add(0, 0.15, 0), "w_shock_flat", WEB, 1, (float) (r * 2.6), 9, null, Display.Billboard.FIXED);
            ItemDisplay cr = t(c.clone().add(0, 0.06, 0), "w_crack_flat", ABYSS, (float) r * 2, 1, (float) r * 2, Display.Billboard.FIXED, null);
            kill(cr, 40);
            ult.quake(c, 18, 12, 3.5f);
            c.getWorld().spawnParticle(Particle.SCULK_SOUL, c.clone().add(0, 1, 0), 40, r * 0.4, 0.6, r * 0.4, 0.05);
            c.getWorld().spawnParticle(Particle.EXPLOSION, c.clone().add(0, 1, 0), 3, r * 0.3, 0.3, r * 0.3, 0);
        });
    }
}
