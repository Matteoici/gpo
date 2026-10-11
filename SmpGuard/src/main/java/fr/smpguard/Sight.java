package fr.smpguard;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Ligne de vue entre deux points.
 * Les blocs transparents (verre, feuilles, barrières, dalles...) ne bloquent pas la vue :
 * on préfère rater un tricheur que d'accuser un joueur qui voyait à travers une vitre.
 */
public final class Sight {

    private static int rays;

    private Sight() {
    }

    /** Nombre de lancers de rayons depuis le dernier appel (pour le budget de performances). */
    public static int takeRayCount() {
        int r = rays;
        rays = 0;
        return r;
    }

    public static int peekRayCount() {
        return rays;
    }

    public static boolean clear(World w, Vector from, Vector to) {
        Vector dir = to.clone().subtract(from);
        double len = dir.length();
        if (len < 0.05) return true;
        dir.multiply(1 / len);
        Vector pos = from.clone();
        double done = 0;
        for (int i = 0; i < 10; i++) {
            rays++;
            RayTraceResult r = w.rayTraceBlocks(pos.toLocation(w), dir, len - done, FluidCollisionMode.NEVER, true);
            if (r == null || r.getHitBlock() == null) return true;
            Block b = r.getHitBlock();
            if (b.getType().isOccluding()) return false;
            // bloc transparent : on continue derrière lui
            Vector hit = r.getHitPosition();
            double step = hit.clone().subtract(pos).length();
            Vector p = hit.clone();
            int guard = 0;
            while (guard++ < 40 && sameBlock(p, b)) {
                p.add(dir.clone().multiply(0.1));
                step += 0.1;
            }
            done += step;
            if (done >= len) return true;
            pos = p;
        }
        return true;
    }

    private static boolean sameBlock(Vector p, Block b) {
        return p.getBlockX() == b.getX() && p.getBlockY() == b.getY() && p.getBlockZ() == b.getZ();
    }

    /** Vrai si au moins un des points cibles est visible depuis un des points d'observation. */
    public static boolean anyClear(World w, Vector[] eyes, Vector[] targets) {
        for (Vector t : targets) {
            for (Vector e : eyes) {
                if (clear(w, e, t)) return true;
            }
        }
        return false;
    }

    /** Points d'un joueur : tête, poitrine, pieds, et les côtés à hauteur de poitrine. */
    public static Vector[] body(Vector feet, double height) {
        double h = Math.max(0.6, height);
        return new Vector[]{
                feet.clone().add(new Vector(0, h - 0.1, 0)),
                feet.clone().add(new Vector(0, h * 0.55, 0)),
                feet.clone().add(new Vector(0, 0.15, 0)),
                feet.clone().add(new Vector(0.35, h * 0.55, 0)),
                feet.clone().add(new Vector(-0.35, h * 0.55, 0)),
                feet.clone().add(new Vector(0, h * 0.55, 0.35)),
                feet.clone().add(new Vector(0, h * 0.55, -0.35)),
        };
    }

    /** Angle (degrés) entre la direction du regard et la direction vers un point. */
    public static double angle(Location eye, Vector target) {
        Vector look = eye.getDirection();
        Vector to = target.clone().subtract(eye.toVector());
        double len = to.length();
        if (len < 1e-6) return 0;
        double cos = look.dot(to) / (look.length() * len);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }
}
