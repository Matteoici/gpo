package fr.smpboss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static fr.smpboss.ArenaDesign.angDiff;
import static fr.smpboss.ArenaDesign.hash;
import static fr.smpboss.ArenaDesign.key;
import static fr.smpboss.ArenaDesign.pick;
import static fr.smpboss.ArenaDesign.smooth;

/**
 * Le Nid de la Reine : un plan à part, pas la même arène que le Roi Citrouille.
 *  - le sol est une immense toile d'araignée lumineuse (fils bleus qui brillent) sur de l'ardoise sombre et du sculk
 *  - autour du sol : un gouffre sans fond (on y tombe = les toiles te ramènent), traversé au nord par un pont de soie
 *  - une caverne aux parois abruptes, stalagmites, cocons d'œufs, os des anciennes victimes
 *  - au-dessus : une araignée géante accrochée au plafond, son ventre au sablier bleu, ses 8 pattes qui descendent
 *    tout autour de l'arène et une toile tendue entre elles, avec des victimes emmaillotées qui pendent
 *  - au nord : on sort par la gueule d'une tête d'araignée à 8 yeux, entre ses deux crochets
 */
final class SpiderDesign implements ArenaPlan {

    final int r, dome, domeH, protect, front, abyss;
    final Map<Long, String> blocks = new LinkedHashMap<>();
    final Set<Long> soft = new HashSet<>();
    final List<int[]> banners = new ArrayList<>();
    private final Map<Long, Integer> rimHeight = new HashMap<>();
    private static final int PIT = -16; // fond du gouffre

    @Override public Map<Long, String> blocks() { return blocks; }
    @Override public Set<Long> soft() { return soft; }
    @Override public List<int[]> banners() { return banners; }
    @Override public int radius() { return r; }
    @Override public int protect() { return protect; }

    SpiderDesign(int r) {
        this.r = r;
        this.dome = r + 24;
        this.domeH = r + 22;
        this.abyss = r + 7;
        this.front = -(abyss + 1);
        this.protect = dome + 4;
        build();
    }

    private void set(int x, int y, int z, String id) {
        long k = key(x, y, z);
        blocks.put(k, id);
        soft.remove(k);
    }

    private void setSoft(int x, int y, int z, String id) {
        long k = key(x, y, z);
        if (blocks.containsKey(k)) return;
        blocks.put(k, id);
        soft.add(k);
    }

    private String get(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    private boolean isAir(int x, int y, int z) {
        String s = get(x, y, z);
        return s == null || s.equals("air") || s.startsWith("light");
    }

    private static double sq(double a) {
        return a * a;
    }

    private static double domeF(double d, double y, double a, double b) {
        return Math.pow(d / a, 3) + Math.pow(Math.max(0, y) / b, 3);
    }

    private boolean inBubble(double d, double y) {
        return domeF(d, y, dome, domeH) < 1;
    }

    private int rim(int x, int z) {
        Integer h = rimHeight.get(key(x, 0, z));
        return h == null ? 0 : h;
    }

    private String rock(int x, int y, int z, int s) {
        return pick(hash(x, y, z, s), "deepslate", 6, "cobbled_deepslate", 3, "tuff", 2, "blackstone", 2);
    }

    private String chitin(int x, int y, int z) {
        return pick(hash(x, y, z, 301), "black_concrete", 45, "blackstone", 20, "polished_blackstone", 20, "black_wool", 15);
    }

    // ================================================================= construction

    private void build() {
        int box = dome + 3;
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                if (x * x + z * z > box * box) continue;
                double d = Math.sqrt(x * x + z * z);
                for (int y = 1; y <= domeH + 3; y++) if (inBubble(d, y)) set(x, y, z, "air");
            }
        }
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                if (x * x + z * z > box * box) continue;
                column(x, z);
            }
        }
        lights();
        stalagmites();
        eggSacs();
        bridge();
        head();
        bubble();
        legsAndWeb();
        ArenaDesign.connectWalls(blocks);
    }

    private void column(int x, int z) {
        double d = Math.sqrt(x * x + z * z);
        // dessous de l'île : cône de roche (seulement dans le vide, pour ne pas abîmer le terrain)
        double depth = 20 + Math.max(0, dome - d) * 0.35 + hash(x, 0, z, 207) * 3;
        for (int y = PIT - 2; y >= -Math.min(34, (int) depth); y--) setSoft(x, y, z, rock(x, y, z, 203));
        if (d <= r + 0.2) {
            floor(x, z, d);
            for (int y = -2; y >= PIT - 1; y--) set(x, y, z, wallRock(x, y, z, d));
        } else if (d <= r + 1.3) {
            set(x, 0, z, "polished_deepslate");
            set(x, 1, z, "deepslate_tile_wall");
            for (int y = -1; y >= PIT - 1; y--) set(x, y, z, wallRock(x, y, z, d));
        } else if (d <= abyss) {
            // le gouffre
            for (int y = PIT + 1; y <= 0; y++) set(x, y, z, "air");
            set(x, PIT, z, pick(hash(x, PIT, z, 211), "sculk", 10, "deepslate", 4, "sea_lantern", 1));
            set(x, PIT - 1, z, "deepslate");
            if (hash(x, -9, z, 212) < 0.32) set(x, -9, z, "cobweb");
            if (hash(x, -12, z, 213) < 0.18) set(x, -12, z, "cobweb");
        } else {
            terrain(x, z, d);
        }
    }

    /** Parois du gouffre : ardoise veinée de sculk, quelques lichens lumineux. */
    private String wallRock(int x, int y, int z, double d) {
        double h = hash(x, y, z, 221);
        if (h < 0.10) return "sculk";
        if (h < 0.106) return "sea_lantern";
        return rock(x, y, z, 222);
    }

    /** Le sol : une toile d'araignée géante, fils bleus lumineux sur fond sombre. */
    private void floor(int x, int z, double d) {
        if (x == 0 && z == 0) {
            set(0, 0, 0, "crying_obsidian");
            set(0, -1, 0, "deepslate");
            return;
        }
        if (d <= 2.6) {
            set(x, 0, z, "chiseled_deepslate");
            set(x, -1, z, "deepslate");
            return;
        }
        if (web(x, z, d)) {
            set(x, 0, z, "light_blue_stained_glass");
            set(x, -1, z, "sea_lantern");
            return;
        }
        String base = pick(hash(x, 0, z, 231), "polished_deepslate", 34, "deepslate_tiles", 26, "black_concrete", 16,
                "cracked_deepslate_tiles", 10, "sculk", 6, "polished_blackstone", 8);
        set(x, 0, z, base);
        set(x, -1, z, "deepslate");
    }

    private static final int SPOKES = 12;

    private static double segDist(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double t = Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / (vx * vx + vz * vz)));
        return Math.hypot(px - ax - vx * t, pz - az - vz * t);
    }

    /** Fil de toile : 12 rayons + une spirale de capture en segments droits entre les rayons, et le cadre. */
    private boolean web(int x, int z, double d) {
        if (d < 3.3) return d > 2.6;
        if (d > r - 0.7) return true;
        double a = Math.atan2(z, x);
        double step = Math.PI * 2 / SPOKES;
        for (int k = 0; k < SPOKES; k++) {
            double ca = k * step;
            if (Math.abs(Math.sin(a - ca)) * d < 0.55 && Math.cos(a - ca) > 0) return true;
        }
        double th = ((a % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
        int k = Math.min(SPOKES - 1, (int) (th / step));
        double a0 = k * step, a1 = a0 + step;
        double sp = 3.7, r0 = 4.6;
        for (int n = 0; ; n++) {
            double ra = r0 + sp * (n + (double) k / SPOKES), rb = r0 + sp * (n + (k + 1.0) / SPOKES);
            if (ra > r - 1.2) break;
            rb = Math.min(rb, r - 0.7);
            if (segDist(x, z, Math.cos(a0) * ra, Math.sin(a0) * ra, Math.cos(a1) * rb, Math.sin(a1) * rb) < 0.5) {
                // quelques fils cassés vers l'extérieur
                return !(ra > 12 && hash(k, n, 0, 241) < 0.08);
            }
        }
        return false;
    }

    /** Parois abruptes de la caverne. */
    private void terrain(int x, int z, double d) {
        double t = Math.max(0, Math.min(1, (d - abyss) / (dome - abyss)));
        int h = (int) Math.round(1 + Math.pow(t, 1.35) * 22 + smooth(x * 1.3, z * 1.3) * 2.2 + hash(x, 0, z, 251) * 1.2);
        h = Math.max(0, h);
        // reste dans la bulle
        while (h > 0 && !inBubble(d, h + 1)) h--;
        rimHeight.put(key(x, 0, z), h);
        for (int y = PIT - 1; y < h; y++) set(x, y, z, d < abyss + 1.6 ? wallRock(x, y, z, d) : rock(x, y, z, 252));
        String top = pick(hash(x, h, z, 253), "sculk", 30, "deepslate", 18, "cobbled_deepslate", 16, "tuff", 10,
                "deepslate_tiles", 8, "black_concrete", 4, "moss_block", 0);
        set(x, h, z, top);
        if (d > dome - 1.5) return;
        double dh = hash(x, h + 1, z, 254);
        String deco = null;
        if (dh < 0.07) deco = "cobweb";
        else if (dh < 0.10) deco = "glow_lichen[down=true]";
        else if (dh < 0.108) deco = "skeleton_skull[rotation=" + (int) (hash(x, 1, z, 255) * 16) + "]";
        else if (dh < 0.118) deco = "bone_block[axis=" + (hash(x, 2, z, 256) < 0.5 ? "x" : "z") + "]";
        else if (dh < 0.124) deco = "soul_lantern";
        if (deco != null) set(x, h + 1, z, deco);
    }

    private void lights() {
        for (int x = -r; x <= r; x += 6) {
            for (int z = -r; z <= r; z += 6) {
                if (x * x + z * z <= (r - 1) * (r - 1)) set(x, 4, z, "light[level=12]");
            }
        }
        for (int x = -dome; x <= dome; x += 7) {
            for (int z = -dome; z <= dome; z += 7) {
                double d = Math.sqrt(x * x + z * z);
                if (d > abyss + 1 && d < dome - 4) set(x, rim(x, z) + 3, z, "light[level=10]");
            }
        }
        // lueur qui monte du fond du gouffre
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            double d = (r + 1.3 + abyss) / 2;
            set((int) Math.round(Math.cos(a) * d), PIT + 2, (int) Math.round(Math.sin(a) * d), "light[level=9]");
        }
        // poteaux du muret, coiffés d'une lanterne d'âme
        int n = Math.max(12, (int) Math.round(2 * Math.PI * r / 6.5));
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + 0.2;
            int x = (int) Math.round(Math.cos(a) * (r + 0.8)), z = (int) Math.round(Math.sin(a) * (r + 0.8));
            if (Math.abs(x) <= 3 && z < 0) continue;
            set(x, 1, z, "polished_basalt[axis=y]");
            set(x, 2, z, "polished_basalt[axis=y]");
            set(x, 3, z, "soul_lantern");
        }
    }

    /** Stalagmites (sol) et stalactites (plafond), en cônes d'ardoise. */
    private void stalagmites() {
        for (int i = 0; i < 20; i++) {
            double a = hash(i, 1, 0, 261) * Math.PI * 2;
            double d = abyss + 3 + hash(i, 2, 0, 261) * (dome - abyss - 8);
            int bx = (int) Math.round(Math.cos(a) * d), bz = (int) Math.round(Math.sin(a) * d);
            if (bz < 0 && Math.abs(bx) < 15) continue; // devant la tête
            double rad = 1.4 + hash(i, 3, 0, 261) * 1.4;
            int hgt = 5 + (int) (hash(i, 4, 0, 261) * 8);
            int base = rim(bx, bz);
            cone(bx, base, bz, rad, hgt, 1);
        }
        for (int i = 0; i < 34; i++) {
            double a = hash(i, 1, 0, 262) * Math.PI * 2;
            double d = 8 + hash(i, 2, 0, 262) * (dome - 14);
            int bx = (int) Math.round(Math.cos(a) * d), bz = (int) Math.round(Math.sin(a) * d);
            int top = domeH + 2;
            while (top > 5 && inBubble(d, top)) top++;
            while (top > 5 && !inBubble(d, top - 1)) top--;
            double rad = 1.0 + hash(i, 3, 0, 262) * 1.3;
            int len = 4 + (int) (hash(i, 4, 0, 262) * 7);
            cone(bx, top, bz, rad, len, -1);
        }
    }

    private void cone(int bx, int by, int bz, double rad, int len, int dir) {
        for (int v = 0; v <= len; v++) {
            double rr = rad * (1 - (double) v / (len + 1));
            int ir = (int) Math.ceil(rr);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    if (dx * dx + dz * dz > rr * rr + 0.3) continue;
                    int y = by + v * dir;
                    String m = v >= len - 1 ? "polished_basalt[axis=y]"
                            : pick(hash(bx + dx, y, bz + dz, 263), "deepslate", 5, "cobbled_deepslate", 3, "tuff", 2, "sculk", 1);
                    set(bx + dx, y, bz + dz, m);
                }
            }
        }
    }

    /** Grappes de cocons d'œufs sur les bords, enveloppés de toiles. */
    private void eggSacs() {
        for (int i = 0; i < 7; i++) {
            double a = hash(i, 1, 0, 271) * Math.PI * 2;
            double d = abyss + 3 + hash(i, 2, 0, 271) * 9;
            int cx = (int) Math.round(Math.cos(a) * d), cz = (int) Math.round(Math.sin(a) * d);
            if (cz < 0 && Math.abs(cx) < 15) continue;
            int n = 2 + (int) (hash(i, 3, 0, 271) * 3);
            for (int j = 0; j < n; j++) {
                int ox = cx + (int) Math.round((hash(i, j, 4, 271) - 0.5) * 4);
                int oz = cz + (int) Math.round((hash(i, j, 5, 271) - 0.5) * 4);
                double rad = 1.1 + hash(i, j, 6, 271) * 0.8;
                int oy = rim(ox, oz) + 1 + (int) Math.round(rad);
                blob(ox, oy, oz, rad, rad * 1.25, "white_wool", "white_wool");
            }
            for (int k = 0; k < 14; k++) {
                int wx = cx + (int) Math.round((hash(i, k, 7, 271) - 0.5) * 7);
                int wz = cz + (int) Math.round((hash(i, k, 8, 271) - 0.5) * 7);
                int wy = rim(wx, wz) + 1 + (int) (hash(i, k, 9, 271) * 3);
                if (isAir(wx, wy, wz)) set(wx, wy, wz, "cobweb");
            }
        }
    }

    private void blob(int cx, int cy, int cz, double rh, double rv, String outer, String inner) {
        int ih = (int) Math.ceil(rh), iv = (int) Math.ceil(rv);
        for (int x = -ih; x <= ih; x++) {
            for (int y = -iv; y <= iv; y++) {
                for (int z = -ih; z <= ih; z++) {
                    double e = sq(x / rh) + sq(y / rv) + sq(z / rh);
                    if (e > 1) continue;
                    set(cx + x, cy + y, cz + z, e < 0.35 ? inner : outer);
                }
            }
        }
    }

    /** Pont de soie au-dessus du gouffre, jusqu'à la gueule de la tête. */
    private void bridge() {
        for (int z = front - 2; z <= -(r - 1); z++) {
            for (int x = -3; x <= 3; x++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= r - 0.5) continue;
                if (Math.abs(x) == 3) {
                    if (d > r + 1.3 && d <= abyss + 0.5) {
                        if (hash(x, 0, z, 281) < 0.55) set(x, 0, z, "cobweb");
                        if (hash(x, 1, z, 281) < 0.35) set(x, 1, z, "cobweb");
                    }
                    continue;
                }
                set(x, 0, z, (z & 1) == 0 && Math.abs(x) == 2 ? "light_gray_wool" : "white_wool");
                for (int y = 1; y <= 5; y++) set(x, y, z, "air");
                if (d > r + 1.3 && d <= abyss + 0.5) {
                    if (Math.abs(x) == 2 || hash(x, -1, z, 282) < 0.3) set(x, -1, z, "cobweb");
                    if (Math.abs(x) == 2 && hash(x, -2, z, 283) < 0.4) set(x, -2, z, "cobweb");
                }
            }
        }
        int ez = -(int) Math.round(Math.sqrt(Math.max(0, (r + 0.8) * (r + 0.8) - 16)));
        for (int sx : new int[]{-4, 4}) {
            set(sx, 0, ez, "polished_deepslate");
            set(sx, 1, ez, "polished_basalt[axis=y]");
            set(sx, 2, ez, "polished_basalt[axis=y]");
            set(sx, 3, ez, "polished_basalt[axis=y]");
            set(sx, 4, ez, "soul_lantern");
        }
    }

    // ================================================================= tête d'araignée (entrée / sortie)

    private static final double HS = 1.35; // taille de la tête

    private int headZ() {
        return front - 9;
    }

    /** Repère local : u = x, v = y, w = z - centre (positif = vers l'arène). */
    private boolean headSolid(double u, double v, double w) {
        if (v < 1.5) return false;
        double main = sq(u / 8.5) + sq((v - 9) / 7) + sq(w / 7.5);
        double back = sq(u / 7) + sq((v - 10) / 6) + sq((w + 7) / 6);  // le céphalothorax derrière
        return main <= 1 || back <= 1;
    }

    private static final double[][] EYES = {
            {-2.3, 10.2, 1.25}, {2.3, 10.2, 1.25},   // grands yeux
            {-5.0, 11.6, 1.05}, {5.0, 11.6, 1.05},
            {-1.3, 13.1, 0.9}, {1.3, 13.1, 0.9},
            {-6.3, 9.0, 0.9}, {6.3, 9.0, 0.9}};

    private double[] eyePos(double[] e) {
        double inside = 1 - sq(e[0] / 8.5) - sq((e[1] - 9) / 7);
        double w = 7.5 * Math.sqrt(Math.max(0, inside));
        return new double[]{e[0], e[1], w};
    }

    private void head() {
        int hz = headZ();
        int pz = -(dome - 2);
        for (int u = -15; u <= 15; u++) {
            for (int v = 1; v <= 24; v++) {
                for (int w = -24; w <= 11; w++) {
                    double U = u / HS, V = v / HS, Wd = w / HS;
                    if (!headSolid(U, V, Wd)) continue;
                    int z = hz + w;
                    if (!inBubble(Math.sqrt(u * u + z * z), v)) continue;
                    String m = chitin(u, v, z);
                    for (double[] e : EYES) {
                        double[] p = eyePos(e);
                        double dd = Math.sqrt(sq(U - p[0]) + sq(V - p[1]) + sq(Wd - p[2]));
                        if (dd < e[2]) m = "sea_lantern";
                    }
                    if (u == 0 && V >= 14 && Wd < 3) m = "light_blue_concrete";
                    set(u, v, z, m);
                }
            }
        }
        // poils : quelques pointes noires sur le dessus et les côtés
        for (int i = 0; i < 60; i++) {
            double a = hash(i, 0, 0, 311) * Math.PI * 2, b = hash(i, 1, 0, 311) * 1.2;
            int u = (int) Math.round(Math.cos(a) * 9 * HS * Math.cos(b)), v = (int) Math.round((9 + Math.sin(b) * 7.3) * HS);
            int w = (int) Math.round(Math.sin(a) * 7.5 * HS * Math.cos(b)) - 2;
            if (!headSolid(u / HS, v / HS, w / HS) && isAir(u, v, hz + w) && v > 8 && !isAir(u, v - 1, hz + w)) set(u, v, hz + w, "black_candle");
        }
        // crochets (chélicères) de chaque côté de la gueule, qui se courbent vers le bas et vers l'arène
        for (int s = -1; s <= 1; s += 2) {
            for (double t = 0; t <= 1.0001; t += 0.008) {
                double cu = s * (4.3 - 1.2 * t) * HS;
                double cv = (5.2 - 4.4 * t + 0.8 * t * t) * HS;
                double cw = (5.2 + 3.2 * t - 1.0 * t * t) * HS;
                double rad = (1.55 * (1 - t) + 0.45) * HS;
                for (int x = (int) Math.floor(cu - rad); x <= Math.ceil(cu + rad); x++) {
                    for (int y = (int) Math.floor(cv - rad); y <= Math.ceil(cv + rad); y++) {
                        for (int w = (int) Math.floor(cw - rad); w <= Math.ceil(cw + rad); w++) {
                            if (sq(x - cu) + sq(y - cv) + sq(w - cw) > rad * rad || y < 1) continue;
                            if (Math.abs(x) <= 2) continue; // la gueule reste libre
                            set(x, y, hz + w, t > 0.6 ? "bone_block[axis=y]" : t > 0.48 ? "light_blue_concrete" : chitin(x, y, w));
                        }
                    }
                }
            }
        }
        // tunnel de la gueule jusqu'au portail
        for (int z = pz + 1; z <= hz + 12; z++) {
            for (int u = -2; u <= 2; u++) {
                set(u, 0, z, (z & 1) == 0 && Math.abs(u) == 2 ? "light_gray_wool" : "white_wool");
                set(u, -1, z, "deepslate");
                for (int v = 1; v <= 5; v++) set(u, v, z, Math.abs(u) == 2 && v == 5 ? "cobweb" : "air");
            }
            if (z > hz + 8) continue;
            for (int u : new int[]{-3, 3}) for (int v = 0; v <= 6; v++) if (isAir(u, v, z)) set(u, v, z, chitin(u, v, z));
            for (int u = -3; u <= 3; u++) if (isAir(u, 6, z)) set(u, 6, z, chitin(u, 6, z));
        }
        set(0, 4, hz + 5, "light[level=13]");
        set(0, 4, pz + 3, "light[level=13]");
        // portail de sortie au fond (cadre d'obsidienne pleureuse)
        for (int u = -2; u <= 2; u++) {
            for (int v = 0; v <= 6; v++) {
                boolean inside = Math.abs(u) <= 1 && v >= 1 && v <= 4;
                set(u, v, pz, inside ? "air" : "obsidian");
            }
            for (int v = 0; v <= 6; v++) set(u, v, pz - 1, chitin(u, v, pz - 1));
        }
    }

    // ================================================================= bulle

    private void bubble() {
        int box = dome + 3;
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                double d = Math.sqrt(x * x + z * z);
                for (int y = PIT - 1; y <= domeH + 3; y++) {
                    if (inBubble(d, y)) continue;
                    if (domeF(d, y, dome + 2.3, domeH + 2.3) >= 1) continue;
                    boolean inner = domeF(d, y, dome + 1.1, domeH + 1.1) < 1;
                    String m;
                    if (!inner) m = rock(x, y, z, 291);
                    else m = pick(hash(x, y, z, 292), "deepslate", 30, "sculk", 22, "cobbled_deepslate", 16, "tuff", 10,
                            "black_concrete", 8, "sea_lantern", 0.4);
                    String cur = get(x, y, z);
                    if (cur != null && !cur.equals("air") && y <= 0) continue; // le terrain reste
                    set(x, y, z, m);
                }
            }
        }
        // toiles qui pendent du plafond
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                if (hash(x, 0, z, 293) > 0.035) continue;
                double d = Math.sqrt(x * x + z * z);
                int y = domeH + 2;
                while (y > 6 && !inBubble(d, y)) y--;
                if (y <= 6) continue;
                int len = 1 + (int) (hash(x, 1, z, 293) * 5);
                for (int i = 0; i < len; i++) if (isAir(x, y - i, z)) set(x, y - i, z, "cobweb");
            }
        }
    }

    // ================================================================= l'araignée géante du plafond

    private static final int LEGS = 8;

    /** Point d'une patte k, paramètre t (0 = hanche au plafond, 1 = genou, 2 = pied), en (R, y). */
    private double[] legRY(double t) {
        double hipR = 7, hipY = domeH - 7;
        double kneeR = r + 9.5, kneeY = domeH * 0.78;
        double footR = r + 11.5, footY = 0;
        if (t <= 1) {
            double cR = (hipR + kneeR) * 0.45, cY = domeH - 2.5;
            double a = 1 - t;
            return new double[]{a * a * hipR + 2 * a * t * cR + t * t * kneeR, a * a * hipY + 2 * a * t * cY + t * t * kneeY};
        }
        double s = t - 1, a = 1 - s;
        double cR = kneeR + 3, cY = (kneeY + footY) * 0.55;
        return new double[]{a * a * kneeR + 2 * a * s * cR + s * s * footR, a * a * kneeY + 2 * a * s * cY + s * s * footY};
    }

    private double legAngle(int k) {
        return Math.toRadians(22.5 + 45 * k);
    }

    private int[] legPoint(int k, double t) {
        double[] p = legRY(t);
        double a = legAngle(k);
        double y = p[1];
        if (t > 1.95) {
            int fx = (int) Math.round(Math.cos(a) * p[0]), fz = (int) Math.round(Math.sin(a) * p[0]);
            y = Math.min(y, rim(fx, fz));
        }
        return new int[]{(int) Math.round(Math.cos(a) * p[0]), (int) Math.round(y), (int) Math.round(Math.sin(a) * p[0])};
    }

    private void legsAndWeb() {
        // ventre (abdomen) accroché au plafond, sablier bleu lumineux dessous
        int by = domeH - 4;
        double rx = 10, ry = 6.5, rz = 12.5;
        for (int x = -11; x <= 11; x++) {
            for (int y = -7; y <= 7; y++) {
                for (int z = -13; z <= 13; z++) {
                    double e = sq(x / rx) + sq(y / ry) + sq(z / rz);
                    if (e > 1) continue;
                    boolean bottom = sq(x / rx) + sq((y - 1) / ry) + sq(z / rz) > 1;
                    String m = chitin(x, by + y, z);
                    if (bottom) {
                        double hz = Math.abs(z);
                        double half = 0.6 + 0.42 * hz;
                        if (hz <= 7 && Math.abs(x) <= half) m = Math.abs(x) <= half - 1.1 ? "sea_lantern" : "light_blue_concrete";
                        else if (hash(x, by + y, z, 321) < 0.05) m = "light_blue_concrete";
                    }
                    set(x, by + y, z, m);
                }
            }
        }
        // les 8 pattes
        for (int k = 0; k < LEGS; k++) {
            double a = legAngle(k);
            for (double t = 0; t <= 2.0001; t += 0.005) {
                double[] p = legRY(t);
                double cx = Math.cos(a) * p[0], cz = Math.sin(a) * p[0], cy = p[1];
                double rad = t <= 1 ? 2.0 - 0.7 * t : 1.3 - 0.75 * (t - 1);
                if (t > 1.9) {
                    int fx = (int) Math.round(cx), fz = (int) Math.round(cz);
                    cy = Math.min(cy, rim(fx, fz) + 0.5);
                }
                boolean joint = Math.abs(t - 1) < 0.05;
                if (joint) rad += 0.6;
                boolean band = false;
                for (double bt : new double[]{0.3, 0.6, 0.85, 1.25, 1.5, 1.72}) if (Math.abs(t - bt) < 0.018) band = true;
                int ir = (int) Math.ceil(rad);
                for (int x = (int) Math.floor(cx - ir); x <= Math.ceil(cx + ir); x++) {
                    for (int y = (int) Math.floor(cy - ir); y <= Math.ceil(cy + ir); y++) {
                        for (int z = (int) Math.floor(cz - ir); z <= Math.ceil(cz + ir); z++) {
                            if (sq(x - cx) + sq(y - cy) + sq(z - cz) > rad * rad) continue;
                            String m;
                            if (joint && Math.abs(y - cy) < 0.8) m = "sea_lantern";
                            else if (band) m = "light_blue_concrete";
                            else if (t > 1.85) m = "polished_basalt[axis=y]";
                            else m = chitin(x, y, z);
                            set(x, y, z, m);
                        }
                    }
                }
            }
        }
        // toile tendue entre les pattes, qui pend un peu
        for (double s : new double[]{0.3, 0.55, 0.8, 1.0}) {
            for (int k = 0; k < LEGS; k++) {
                int[] p = legPoint(k, s), q = legPoint((k + 1) % LEGS, s);
                double sag = 2 + s * 4;
                strand(p, q, sag, k * 10 + (int) (s * 100));
            }
        }
        // fils qui descendent des genoux vers les murs
        for (int k = 0; k < LEGS; k++) {
            int[] kn = legPoint(k, 1.0);
            double a = legAngle(k) + Math.toRadians(22.5);
            double d = abyss + 6;
            int tx = (int) Math.round(Math.cos(a) * d), tz = (int) Math.round(Math.sin(a) * d);
            if (tz < 0 && Math.abs(tx) < 15) continue;
            strand(kn, new int[]{tx, rim(tx, tz) + 1, tz}, 1.5, 500 + k);
        }
    }

    /** Fil de toile entre deux points, qui pend ; parfois une victime emmaillotée qui pend dessous. */
    private void strand(int[] p, int[] q, double sag, int seed) {
        double len = Math.sqrt(sq(q[0] - p[0]) + sq(q[1] - p[1]) + sq(q[2] - p[2]));
        int n = (int) Math.ceil(len * 2);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            int x = (int) Math.round(p[0] + (q[0] - p[0]) * t);
            int y = (int) Math.round(p[1] + (q[1] - p[1]) * t - sag * 4 * t * (1 - t));
            int z = (int) Math.round(p[2] + (q[2] - p[2]) * t);
            if (isAir(x, y, z)) set(x, y, z, "cobweb");
        }
        // victime emmaillotée
        if (hash(seed, 0, 0, 331) < 0.33 && sag > 3) {
            int x = (p[0] + q[0]) / 2, z = (p[2] + q[2]) / 2;
            int y = (int) Math.round((p[1] + q[1]) / 2.0 - sag);
            if (Math.sqrt(x * x + z * z) < 6) return;
            int drop = 2 + (int) (hash(seed, 1, 0, 331) * 4);
            for (int i = 1; i <= drop; i++) if (isAir(x, y - i, z)) set(x, y - i, z, "cobweb");
            int cy = y - drop - 2;
            if (cy < 12) return;
            for (int yy = 0; yy < 3; yy++) {
                set(x, cy - yy, z, "white_wool");
                if (yy == 1) {
                    if (isAir(x + 1, cy - yy, z)) set(x + 1, cy - yy, z, "white_wool");
                    if (isAir(x, cy - yy, z + 1)) set(x, cy - yy, z + 1, "light_gray_wool");
                }
            }
        }
    }

}
