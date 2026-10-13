package fr.smpgojo;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/** Petits outils de dessin en particules. */
final class Draw {

    static final Color BLUE = Color.fromRGB(40, 120, 255), LIGHT_BLUE = Color.fromRGB(150, 210, 255),
            RED = Color.fromRGB(255, 30, 30), PURPLE = Color.fromRGB(150, 40, 255), PINK = Color.fromRGB(255, 120, 255),
            WHITE = Color.fromRGB(255, 255, 255), BLACK = Color.fromRGB(8, 8, 10), BLOOD = Color.fromRGB(150, 0, 0),
            ORANGE = Color.fromRGB(255, 120, 0), YELLOW = Color.fromRGB(255, 210, 60), SHADOW = Color.fromRGB(25, 0, 40),
            CURSED = Color.fromRGB(70, 150, 255), GOLD = Color.fromRGB(255, 200, 40), TONGUE = Color.fromRGB(230, 90, 120);

    private Draw() {
    }

    /** Particule de couleur, taille toujours dans les limites du jeu (0,01 à 4). */
    static Particle.DustOptions dustOpt(Color c, float size) {
        return new Particle.DustOptions(c, Math.max(0.05f, Math.min(4f, size)));
    }

    static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }

    static void dust(Location l, Color c, float size) {
        l.getWorld().spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, dustOpt(c, size), true);
    }

    static void fx(Location l, Particle p, int count, double spread, double speed) {
        l.getWorld().spawnParticle(p, l, count, spread, spread, spread, speed, null, true);
    }

    static void fx(Location l, Particle p, int count, double sx, double sy, double sz, double speed) {
        l.getWorld().spawnParticle(p, l, count, sx, sy, sz, speed, null, true);
    }

    /** Points au hasard sur une sphère. */
    static void sphere(Location c, double r, Color color, float size, int points) {
        Particle.DustOptions dust = dustOpt(color, size);
        World w = c.getWorld();
        ThreadLocalRandom rnd = rnd();
        for (int i = 0; i < points; i++) {
            double u = rnd.nextDouble() * 2 - 1, t = rnd.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - u * u);
            w.spawnParticle(Particle.DUST, c.getX() + s * Math.cos(t) * r, c.getY() + u * r, c.getZ() + s * Math.sin(t) * r,
                    1, 0, 0, 0, 0, dust, true);
        }
    }

    /** Remplit une boule (pour les noyaux lumineux). */
    static void ball(Location c, double r, Color color, float size, int points) {
        Particle.DustOptions dust = dustOpt(color, size);
        World w = c.getWorld();
        ThreadLocalRandom rnd = rnd();
        for (int i = 0; i < points; i++) {
            Vector v = randomDir().multiply(Math.cbrt(rnd.nextDouble()) * r);
            w.spawnParticle(Particle.DUST, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(), 1, 0, 0, 0, 0, dust, true);
        }
    }

    /** Cercle horizontal. */
    static void ring(Location c, double r, Color color, float size, int points) {
        Particle.DustOptions dust = dustOpt(color, size);
        World w = c.getWorld();
        double off = rnd().nextDouble() * Math.PI;
        for (int i = 0; i < points; i++) {
            double a = off + i * Math.PI * 2 / points;
            w.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, dust, true);
        }
    }

    static void ring(Location c, double r, Particle p, int points) {
        World w = c.getWorld();
        for (int i = 0; i < points; i++) {
            double a = i * Math.PI * 2 / points;
            w.spawnParticle(p, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, null, true);
        }
    }

    /** Anneau penché qui tourne (orbite autour d'une sphère). */
    static void orbit(Location c, double r, double phase, double tilt, Color color, float size, int points) {
        Particle.DustOptions dust = dustOpt(color, size);
        World w = c.getWorld();
        for (int i = 0; i < points; i++) {
            double a = phase + i * Math.PI * 2 / points;
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            double y = z * Math.sin(tilt);
            z = z * Math.cos(tilt);
            w.spawnParticle(Particle.DUST, c.getX() + x, c.getY() + y, c.getZ() + z, 1, 0, 0, 0, 0, dust, true);
        }
    }

    /** Une coupure : trait principal + liseré. */
    static void slash(Location c, Vector u, double half, Color core, Color edge) {
        Particle.DustOptions a = dustOpt(core, half > 4 ? 1.6f : 1.1f), b = dustOpt(edge, 0.7f);
        World w = c.getWorld();
        double step = half > 4 ? 0.35 : 0.16;
        for (double t = -half; t <= half; t += step) {
            double fade = 1 - Math.abs(t) / half;
            double x = c.getX() + u.getX() * t, y = c.getY() + u.getY() * t, z = c.getZ() + u.getZ() * t;
            w.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, a, true);
            if (fade > 0.3) w.spawnParticle(Particle.DUST, x, y + 0.05, z, 1, 0.02, 0.02, 0.02, 0, b, true);
        }
    }

    /** Segment droit. */
    static void segment(Location a, Location b, Color c, float size, double step) {
        Particle.DustOptions dust = dustOpt(c, size);
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.001) return;
        d.multiply(1 / len);
        World w = a.getWorld();
        for (double t = 0; t <= len; t += step) {
            w.spawnParticle(Particle.DUST, a.getX() + d.getX() * t, a.getY() + d.getY() * t, a.getZ() + d.getZ() * t, 1, 0, 0, 0, 0, dust, true);
        }
    }

    /** Éclair en zigzag. */
    static void bolt(Location from, Vector dir, double length, Color core, Color edge, float size) {
        Location cur = from.clone();
        Vector d = dir.clone().normalize();
        int segs = Math.max(3, (int) (length / 0.7));
        for (int i = 0; i < segs; i++) {
            Vector jitter = randomDir().multiply(0.45);
            Location next = cur.clone().add(d.clone().multiply(length / segs)).add(jitter);
            segment(cur, next, core, size, 0.12);
            if (edge != null) segment(cur.clone().add(0.04, 0.04, 0), next.clone().add(0.04, 0.04, 0), edge, size * 0.55f, 0.2);
            cur = next;
            if (rnd().nextDouble() < 0.25) bolt(cur, d.clone().add(randomDir().multiply(0.9)), length * 0.35, core, edge, size * 0.7f);
        }
    }

    /** Particules qui se resserrent vers un point (charge). */
    static void converge(Location c, double r, Color color, float size, int n) {
        Particle.DustOptions dust = dustOpt(color, size);
        World w = c.getWorld();
        for (int i = 0; i < n; i++) {
            Vector v = randomDir().multiply(r * (0.3 + rnd().nextDouble() * 0.7));
            w.spawnParticle(Particle.DUST, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(), 1, 0, 0, 0, 0, dust, true);
        }
        // traînées qui filent vers le centre
        for (int i = 0; i < n / 3; i++) {
            Vector v = randomDir().multiply(r);
            w.spawnParticle(Particle.END_ROD, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(), 0,
                    -v.getX(), -v.getY(), -v.getZ(), 0.08, null, true);
        }
    }

    static Vector randomDir() {
        ThreadLocalRandom rnd = rnd();
        Vector v;
        do {
            v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1));
        } while (v.lengthSquared() > 1 || v.lengthSquared() < 0.01);
        return v.normalize();
    }

    /** Un axe perpendiculaire au regard, incliné de "tilt" (radians). */
    static Vector perpendicular(Vector d, double tilt) {
        Vector side = d.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
        side.normalize();
        Vector up = side.clone().crossProduct(d).normalize();
        return side.multiply(Math.cos(tilt)).add(up.multiply(Math.sin(tilt))).normalize();
    }

    static void blood(Entity e, int n) {
        e.getWorld().spawnParticle(Particle.BLOCK, e.getLocation().add(0, e.getHeight() * 0.6, 0), n, 0.3, 0.4, 0.3, 0,
                Material.REDSTONE_BLOCK.createBlockData(), true);
        e.getWorld().spawnParticle(Particle.DUST, e.getLocation().add(0, e.getHeight() * 0.6, 0), n / 2, 0.3, 0.4, 0.3, 0,
                dustOpt(BLOOD, 1.3f), true);
    }
}
