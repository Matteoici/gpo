package fr.smpboss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan de l'arène infernale (sans Bukkit : uniquement des identifiants de blocs, pour pouvoir le tester à part).
 * Coordonnées relatives au centre : (0,0,0) = l'autel, au niveau du sol. Nord = z négatif.
 *
 *  - sol de dalles hexagonales sombres, fissures de magma rougeoyantes, fissures en étoile depuis l'autel
 *  - muret + piliers à citrouilles, puis une rivière de lave tout autour
 *  - bord rocheux qui remonte en cratère, 3 coulées de lave, obélisques penchés fissurés, arbres morts, bannières
 *  - au nord : un énorme crâne de démon à cornes, ses yeux brûlent ; on entre par sa gueule puis un pont sur la lave
 */
final class ArenaDesign implements ArenaPlan {

    final int r;            // rayon du sol de combat
    final int outer;        // rayon du bord rocheux (jusqu'à la paroi de la bulle)
    final int dome, domeH;  // bulle fermée : rayon et hauteur intérieurs
    final int protect;      // rayon de la zone protégée
    final int front;        // z de la face avant du crâne (négatif)
    final Map<Long, String> blocks = new LinkedHashMap<>();
    final Set<Long> soft = new HashSet<>();       // posés seulement s'il n'y a rien (air / liquide)
    final List<int[]> banners = new ArrayList<>(); // x, y, z des bannières à décorer
    int[] portal;                                   // bas du portail de sortie
    private final Map<Long, Integer> rimHeight = new HashMap<>();

    @Override public Map<Long, String> blocks() { return blocks; }
    @Override public Set<Long> soft() { return soft; }
    @Override public List<int[]> banners() { return banners; }
    @Override public int radius() { return r; }
    @Override public int protect() { return protect; }

    ArenaDesign(int r) {
        this.r = r;
        this.dome = r + 24;
        this.domeH = r + 22;
        this.outer = dome;
        this.front = -(r + 6);
        this.protect = dome + 4;
        build();
    }

    // ================================================================= clés

    static long key(int x, int y, int z) {
        return (((long) (x + 4096)) << 40) | (((long) (z + 4096)) << 20) | (long) (y + 4096);
    }

    static int kx(long k) {
        return (int) ((k >>> 40) & 0xFFFFF) - 4096;
    }

    static int kz(long k) {
        return (int) ((k >>> 20) & 0xFFFFF) - 4096;
    }

    static int ky(long k) {
        return (int) (k & 0xFFFFF) - 4096;
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

    // ================================================================= bruit

    static double hash(int x, int y, int z, int s) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ s * 2654435761L;
        h ^= h >>> 13;
        h *= 0x5bd1e995L;
        h ^= h >>> 15;
        h *= 0x27d4eb2dL;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    static double smooth(double x, double z) {
        return (Math.sin(x * 0.31 + 1.7) * 0.5 + Math.cos(z * 0.27 - 0.6) * 0.5 + Math.sin((x + z) * 0.19) * 0.4
                + Math.cos((x - z) * 0.41) * 0.3) / 1.7;
    }

    /** Choix pondéré : pick(h, "a", 3, "b", 1...) */
    static String pick(double h, Object... p) {
        double total = 0;
        for (int i = 1; i < p.length; i += 2) total += ((Number) p[i]).doubleValue();
        double t = h * total;
        for (int i = 0; i < p.length; i += 2) {
            t -= ((Number) p[i + 1]).doubleValue();
            if (t < 0) return (String) p[i];
        }
        return (String) p[p.length - 2];
    }

    static double angDiff(double a, double b) {
        double d = (a - b) % (Math.PI * 2);
        if (d > Math.PI) d -= Math.PI * 2;
        if (d < -Math.PI) d += Math.PI * 2;
        return d;
    }

    private static String facing(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? "east" : "west";
        return dz >= 0 ? "south" : "north";
    }

    private static int[] faceVec(String f) {
        return switch (f) {
            case "east" -> new int[]{1, 0};
            case "west" -> new int[]{-1, 0};
            case "south" -> new int[]{0, 1};
            default -> new int[]{0, -1};
        };
    }

    /** Face vers le centre depuis (x,z). */
    private static String toCenter(int x, int z) {
        return facing(-x, -z);
    }

    // ================================================================= zones

    private boolean footprint(int x, int z) {
        return x * x + z * z <= (dome + 3) * (dome + 3);
    }

    /** < 1 = dans la bulle (forme de dôme un peu "carré" pour garder de la hauteur sur les bords). */
    private static double domeF(double d, int y, double a, double b) {
        return Math.pow(d / a, 3) + Math.pow(Math.max(0, y) / b, 3);
    }

    private int rim(int x, int z) {
        Integer h = rimHeight.get(key(x, 0, z));
        return h == null ? 0 : h;
    }

    // ================================================================= construction

    private void build() {
        int box = dome + 3;
        // 1) on vide l'intérieur de la bulle
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                if (!footprint(x, z)) continue;
                double d = Math.sqrt(x * x + z * z);
                for (int y = 1; y <= domeH + 3; y++) {
                    if (domeF(d, y, dome, domeH) < 1) set(x, y, z, "air");
                }
            }
        }
        // 2) fondations + sol de chaque colonne
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                if (!footprint(x, z)) continue;
                column(x, z);
            }
        }
        lights();
        channels();
        pillars();
        int[] obeliskAngles = {0, 45, 90, 135, 180, 225, 315};
        for (int i = 0; i < obeliskAngles.length; i++) obelisk(Math.toRadians(obeliskAngles[i]), i);
        int[] treeAngles = {67, 157, 232, 338};
        for (int i = 0; i < treeAngles.length; i++) tree(Math.toRadians(treeAngles[i]), i);
        bridge();
        skull();
        bubble();
        connect();
    }

    private void column(int x, int z) {
        double d = Math.sqrt(x * x + z * z);
        // dessous : cône de roche (seulement là où il n'y a rien, pour ne pas abîmer le terrain)
        double depth = 4 + Math.max(0, outer - d) * 0.65 + hash(x, 0, z, 7) * 2.5;
        for (int y = -3; y >= -Math.min(24, (int) depth); y--) {
            double h = hash(x, y, z, 3);
            setSoft(x, y, z, pick(h, "blackstone", 4, "netherrack", 4, "basalt", 2, "magma_block", 0.6));
        }
        set(x, -2, z, "blackstone");
        if (d <= r + 0.2) {
            floor(x, z, d);
            set(x, -1, z, "blackstone");
        } else if (d <= r + 1.3) {
            set(x, 0, z, "polished_blackstone_bricks");
            set(x, -1, z, "blackstone");
            set(x, 1, z, "polished_blackstone_brick_wall");
        } else if (d <= r + 4.6) {
            set(x, 0, z, "lava");
            set(x, -1, z, "lava");
            set(x, -2, z, "basalt");
        } else {
            terrain(x, z, d);
        }
    }

    /** Dalles hexagonales, fissures de magma, cercle de l'autel. */
    private void floor(int x, int z, double d) {
        if (x == 0 && z == 0) {
            set(0, 0, 0, "crying_obsidian");
            return;
        }
        if (d <= 2.6) {
            set(x, 0, z, "chiseled_polished_blackstone");
            return;
        }
        if (d <= 3.5) {
            set(x, 0, z, "magma_block");
            return;
        }
        if ((Math.abs(x) == 5 && z == 0) || (Math.abs(z) == 5 && x == 0)) {
            set(x, 0, z, "gilded_blackstone");
            return;
        }
        // fissures en étoile qui partent de l'autel
        double a = Math.atan2(z, x);
        for (int k = 0; k < 6; k++) {
            double ca = Math.toRadians(k * 60 + 17) + 0.22 * Math.sin(d * 0.55 + k * 1.3);
            if (d > 4 && d < r - 1.5 && Math.abs(angDiff(a, ca)) * d < 0.55) {
                set(x, 0, z, "magma_block");
                return;
            }
        }
        long cell = hex(x, z);
        if (cell != hex(x + 1, z) || cell != hex(x, z + 1)) {
            set(x, 0, z, "magma_block");
            return;
        }
        int cq = (int) (cell >> 32), cr = (int) cell;
        String base = pick(hash(cq, 0, cr, 11), "polished_blackstone_bricks", 30, "cracked_polished_blackstone_bricks", 20,
                "polished_blackstone", 14, "deepslate_tiles", 16, "cracked_deepslate_tiles", 10, "blackstone", 10);
        double h = hash(x, 0, z, 12);
        if (h < 0.10) base = base.contains("deepslate") ? "cracked_deepslate_tiles" : "cracked_polished_blackstone_bricks";
        else if (h < 0.115) base = "magma_block";
        set(x, 0, z, base);
    }

    /** Case hexagonale (q, r) qui contient le bloc. */
    private static long hex(int x, int z) {
        double s = 4.8;
        double q = (2.0 / 3 * x) / s;
        double rr = (-1.0 / 3 * x + Math.sqrt(3) / 3 * z) / s;
        double cx = q, cz = rr, cy = -q - rr;
        long rx = Math.round(cx), ry = Math.round(cy), rz = Math.round(cz);
        double dx = Math.abs(rx - cx), dy = Math.abs(ry - cy), dz = Math.abs(rz - cz);
        if (dx > dy && dx > dz) rx = -ry - rz;
        else if (dy <= dz) rz = -rx - ry;
        return (rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Bord rocheux qui remonte en cratère. */
    private void terrain(int x, int z, double d) {
        // le bord remonte en cratère jusqu'à la paroi de la bulle
        double t = Math.max(0, Math.min(1, (d - (r + 4.6)) / (dome - (r + 4.6))));
        int h = (int) Math.round(0.3 + t * t * 9 + t * 2 + smooth(x, z) * 1.2);
        h = Math.max(0, Math.min(12, h));
        rimHeight.put(key(x, 0, z), h);
        for (int y = -1; y < h; y++) {
            set(x, y, z, pick(hash(x, y, z, 21), "blackstone", 5, "netherrack", 4, "basalt", 1));
        }
        String top;
        if (d < r + 5.8) top = "basalt";
        else top = pick(hash(x, h, z, 22), "blackstone", 30, "basalt", 14, "netherrack", 24, "crimson_nylium", 18,
                "magma_block", 6, "cracked_polished_blackstone_bricks", 4, "soul_soil", 3);
        set(x, h, z, top);
        double dh = hash(x, h + 1, z, 23);
        String deco = null;
        if (top.equals("crimson_nylium")) {
            if (dh < 0.22) deco = "crimson_roots";
            else if (dh < 0.30) deco = "crimson_fungus";
        } else if (dh < 0.012) {
            deco = "skeleton_skull[rotation=" + (int) (hash(x, 1, z, 24) * 16) + "]";
        } else if (dh < 0.018) {
            deco = "wither_skeleton_skull[rotation=" + (int) (hash(x, 1, z, 24) * 16) + "]";
        } else if (dh < 0.026) {
            deco = "jack_o_lantern[facing=" + toCenter(x, z) + "]";
        } else if (dh < 0.034) {
            deco = "red_candle[candles=" + (1 + (int) (hash(x, 2, z, 25) * 4)) + ",lit=true]";
        }
        if (deco != null && d < dome - 1.5) set(x, h + 1, z, deco);
    }

    /** Lumières invisibles au-dessus du sol (le combat se fait la nuit). */
    private void lights() {
        for (int x = -r; x <= r; x += 6) {
            for (int z = -r; z <= r; z += 6) {
                if (x * x + z * z <= (r - 1) * (r - 1)) set(x, 3, z, "light[level=13]");
            }
        }
        // le cratère autour, un peu plus sombre
        for (int x = -dome; x <= dome; x += 8) {
            for (int z = -dome; z <= dome; z += 8) {
                double d = Math.sqrt(x * x + z * z);
                if (d > r + 6 && d < dome - 4) set(x, rim(x, z) + 4, z, "light[level=11]");
            }
        }
    }

    /** Coulées de lave qui descendent du bord dans la rivière. */
    private void channels() {
        for (int deg : new int[]{22, 112, 202}) {
            double ca = Math.toRadians(deg);
            for (int x = -outer; x <= outer; x++) {
                for (int z = -outer; z <= outer; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d <= r + 4 || d > r + 11) continue;
                    double a = Math.atan2(z, x) + 0.12 * Math.sin(d * 0.5);
                    if (Math.abs(angDiff(a, ca)) * d > 1.1) continue;
                    for (int y = 1; y <= 7; y++) set(x, y, z, "air");
                    set(x, 0, z, "lava");
                    set(x, -1, z, "lava");
                    set(x, -2, z, "basalt");
                    rimHeight.put(key(x, 0, z), -1);
                }
            }
        }
        // les bords des coulées : de la roche au niveau de la lave pour qu'elle ne coule pas
        for (int x = -outer; x <= outer; x++) {
            for (int z = -outer; z <= outer; z++) {
                if (!"lava".equals(get(x, 0, z))) continue;
                for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    String s = get(x + n[0], 0, z + n[1]);
                    if (s == null || s.equals("air")) set(x + n[0], 0, z + n[1], "basalt");
                }
            }
        }
    }

    /** Piliers du muret, coiffés de citrouilles qui regardent l'autel. */
    private void pillars() {
        int n = Math.max(12, (int) Math.round(2 * Math.PI * r / 7));
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + 0.13;
            int x = (int) Math.round(Math.cos(a) * (r + 0.8)), z = (int) Math.round(Math.sin(a) * (r + 0.8));
            if (Math.abs(x) <= 4 && z < 0) continue; // passage du pont
            pillar(x, z);
        }
    }

    private void pillar(int x, int z) {
        set(x, 0, z, "polished_blackstone_bricks");
        set(x, 1, z, "polished_blackstone");
        set(x, 2, z, "chiseled_polished_blackstone");
        set(x, 3, z, "polished_blackstone");
        set(x, 4, z, "jack_o_lantern[facing=" + toCenter(x, z) + "]");
    }

    /** Obélisque penché, fissure rougeoyante, bannière tournée vers l'arène. */
    private void obelisk(double angle, int idx) {
        double dist = r + 8.5;
        int bx = (int) Math.round(Math.cos(angle) * dist), bz = (int) Math.round(Math.sin(angle) * dist);
        int base = Math.max(0, rim(bx, bz));
        int height = 12 + (int) (hash(idx, 1, 0, 31) * 7) + r / 10;
        double lean = 0.10 + hash(idx, 2, 0, 31) * 0.12;
        double phi = angle + (hash(idx, 3, 0, 31) - 0.5) * 1.6;
        String face = toCenter(bx, bz);
        int[] fv = faceVec(face);
        // gravats au pied
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx + dz * dz > 10 || (Math.abs(dx) <= 1 && Math.abs(dz) <= 1)) continue;
                int gx = bx + dx, gz = bz + dz;
                int h = rim(gx, gz);
                if (h < 0 || hash(gx, 5, gz, 32) > 0.45) continue;
                set(gx, h + 1, gz, pick(hash(gx, 6, gz, 32), "blackstone", 3, "polished_blackstone_bricks", 2, "magma_block", 1));
            }
        }
        for (int v = -1; v <= height; v++) {
            int ox = (int) Math.round(Math.max(0, v) * lean * Math.cos(phi));
            int oz = (int) Math.round(Math.max(0, v) * lean * Math.sin(phi));
            int lo, hi;
            if (v < height * 0.55) { lo = -1; hi = 1; }
            else if (v < height * 0.85) { lo = 0; hi = 1; }
            else { lo = 0; hi = 0; }
            int crack = (int) Math.round(Math.sin(v * 0.9 + idx) * (hi - lo) / 2.0 + (hi + lo) / 2.0);
            for (int i = lo; i <= hi; i++) {
                for (int j = lo; j <= hi; j++) {
                    int x = bx + ox + i, z = bz + oz + j, y = base + v;
                    int along = fv[0] != 0 ? i : j;          // vers le centre
                    int across = fv[0] != 0 ? j : i;         // le long de la face
                    boolean onFace = along == (fv[0] + fv[1] > 0 ? hi : lo);
                    String m;
                    if (onFace && across == crack) m = "magma_block";
                    else m = pick(hash(x, y, z, 33), "polished_blackstone_bricks", 5, "blackstone", 3, "polished_blackstone", 2,
                            "cracked_polished_blackstone_bricks", 2);
                    set(x, y, z, m);
                }
            }
            if (v == height) set(bx + ox, base + v + 1, bz + oz, "magma_block");
            if (v == 7) {
                int x = bx + ox + fv[0] * 2, z = bz + oz + fv[1] * 2, y = base + v;
                set(x, y, z, "red_wall_banner[facing=" + face + "]");
                banners.add(new int[]{x, y, z});
                set(x, y - 1, z, "air");
            }
        }
    }

    /** Arbre mort aux branches tordues, une lanterne pendue à une branche. */
    private void tree(double angle, int idx) {
        double dist = r + 6.5 + hash(idx, 0, 1, 41) * 2;
        int tx = (int) Math.round(Math.cos(angle) * dist), tz = (int) Math.round(Math.sin(angle) * dist);
        int base = Math.max(0, rim(tx, tz)) + 1;
        int h = 5 + (int) (hash(idx, 1, 1, 41) * 3);
        for (int v = 0; v < h; v++) set(tx, base + v, tz, "dark_oak_log[axis=y]");
        int[][] roots = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < 4; i++) {
            if (hash(idx, i, 2, 41) < 0.55) set(tx + roots[i][0], base, tz + roots[i][1], "dark_oak_wood[axis=x]");
        }
        for (int b = 0; b < 3; b++) {
            double a = hash(idx, b, 3, 41) * Math.PI * 2;
            int len = 3 + (int) (hash(idx, b, 4, 41) * 2);
            int sy = base + h - 3 + b;
            int lx = tx, ly = sy, lz = tz;
            for (int s = 1; s <= len; s++) {
                lx = tx + (int) Math.round(Math.cos(a) * s);
                lz = tz + (int) Math.round(Math.sin(a) * s);
                ly = sy + (int) Math.round(s * 0.6);
                set(lx, ly, lz, "dark_oak_wood[axis=" + (Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? "x" : "z") + "]");
            }
            set(lx, ly + 1, lz, "dark_oak_fence");
            if (b == 1) {
                set(lx, ly - 1, lz, "iron_chain|chain");
                set(lx, ly - 2, lz, "lantern[hanging=true]");
            }
        }
    }

    /** Pont au-dessus de la lave, du sol jusqu'à la gueule du crâne. */
    private void bridge() {
        for (int z = front + 1; z <= -(r - 1); z++) {
            for (int x = -3; x <= 3; x++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= r - 1.5) continue;
                if (Math.abs(x) == 3) {
                    if (d > r + 1.3 && d <= r + 4.6) {
                        set(x, 0, z, "polished_blackstone_bricks");
                        set(x, -1, z, "blackstone");
                        set(x, 1, z, "polished_blackstone_brick_wall");
                    }
                    continue;
                }
                set(x, 0, z, "polished_blackstone_bricks");
                set(x, -1, z, "blackstone");
                for (int y = 1; y <= 6; y++) set(x, y, z, "air");
            }
        }
        // piliers à l'entrée du pont
        int ez = -(int) Math.round(Math.sqrt(Math.max(0, (r + 0.8) * (r + 0.8) - 16)));
        pillar(-4, ez);
        pillar(4, ez);
    }

    // ================================================================= crâne de démon

    private boolean skullSolid(double u, double v, double w) {
        double cr = sq(u / 8.5) + sq((v - 12) / 8) + sq((w - 5) / 5.5);
        double jaw = v >= 0 ? sq(u / 6.5) + sq((v - 4.5) / 4.8) + sq((w - 4) / 4.5) : 9;
        double cheekL = sq(u + 6) + sq(v - 8.5) + sq(w - 2);
        double cheekR = sq(u - 6) + sq(v - 8.5) + sq(w - 2);
        boolean brow = Math.abs(u) <= 7.2 && v >= 14 && v <= 15 && w >= -1 && w <= 2 && sq(u / 7.6) + sq((v - 14.5) / 2.2) <= 1;
        return cr <= 1 || jaw <= 1 || cheekL <= 6.8 || cheekR <= 6.8 || brow;
    }

    private static double sq(double a) {
        return a * a;
    }

    /** Orbites en amande, coin intérieur plus bas : un regard furieux. */
    private boolean eyeHole(double u, double v, double w) {
        for (int side = -1; side <= 1; side += 2) {
            double cu = side * 3.6, inner = (u - cu) * -side; // > 0 vers le nez
            double e = sq((u - cu) / 2.9) + sq((v - 11.3) / 2.1) + sq((w + 0.3) / 2.6);
            if (e < 1 && v - 11.3 <= 1.4 - 0.55 * inner) return true;
        }
        return false;
    }

    private static double eyeDist(double u, double v, double w) {
        double l = Math.sqrt(sq(u + 3.6) + sq(v - 11.3) + sq(w + 0.3));
        double r = Math.sqrt(sq(u - 3.6) + sq(v - 11.3) + sq(w + 0.3));
        return Math.min(l, r);
    }

    private boolean noseHole(double u, double v, double w) {
        return v >= 7 && v <= 9.5 && Math.abs(u) <= (v - 6.6) * 0.7 && w <= 1.5;
    }

    private boolean mouthHole(double u, double v) {
        return Math.abs(u) <= 2 && v >= 1 && v <= 6 && (v <= 5 || Math.abs(u) <= 1);
    }

    private void skull() {
        for (int u = -14; u <= 14; u++) {
            for (int v = 0; v <= 22; v++) {
                for (int w = -2; w <= 12; w++) {
                    if (!skullSolid(u, v, w)) continue;
                    int x = u, y = v, z = front - w;
                    if (mouthHole(u, v) && w <= 5) {
                        set(x, y, z, "air");
                        continue;
                    }
                    if (eyeHole(u, v, w)) {
                        set(x, y, z, Math.abs(u) == 4 && v == 11 && w == 0 ? "light[level=15]" : "air");
                        continue;
                    }
                    if (noseHole(u, v, w)) {
                        set(x, y, z, "air");
                        continue;
                    }
                    String m;
                    double ed = eyeDist(u, v, w);
                    boolean surface = !skullSolid(u + 1, v, w) || !skullSolid(u - 1, v, w) || !skullSolid(u, v + 1, w)
                            || !skullSolid(u, v - 1, w) || !skullSolid(u, v, w + 1) || !skullSolid(u, v, w - 1);
                    double pupil = Math.min(Math.sqrt(sq(u + 3.6) + sq(v - 11.3) + sq(w - 2)),
                            Math.sqrt(sq(u - 3.6) + sq(v - 11.3) + sq(w - 2)));
                    if (ed < 3.6 && w >= 1 && pupil < 2.3) {
                        // fond des orbites : pupille éclatante entourée de braise
                        m = pupil < 1.3 ? "shroomlight" : "magma_block";
                    } else if (ed < 3.6 && w >= 0) {
                        m = "blackstone";
                    } else if (noseHole(u, v, w - 1)) {
                        m = "magma_block";
                    } else if (Math.abs(u) == 3 && v >= 1 && v <= 6 && w <= 1) {
                        m = "crying_obsidian";
                    } else if ((v == 7 && Math.abs(u) <= 2 || v == 6 && Math.abs(u) == 2) && w <= 1) {
                        m = "crying_obsidian";
                    } else if (v >= 14 && v <= 15 && w <= 1 && Math.abs(u) <= 7) {
                        m = "polished_blackstone_bricks";
                    } else if (surface && ed > 4.2 && Math.abs(Math.sin(u * 0.9 + v * 0.35) + Math.sin(v * 0.6 - w * 0.8 + u * 0.2)) < 0.09) {
                        m = "magma_block";
                    } else {
                        m = pick(hash(x, y, z, 51), "nether_bricks", 40, "red_nether_bricks", 20, "blackstone", 18,
                                "cracked_nether_bricks", 12, "polished_blackstone_bricks", 10);
                    }
                    set(x, y, z, m);
                }
            }
        }
        // sol du tunnel de la gueule + dents
        for (int w = -2; w <= 5; w++) {
            for (int u = -2; u <= 2; u++) {
                set(u, 0, front - w, "polished_blackstone_bricks");
                set(u, -1, front - w, "blackstone");
            }
        }
        // au fond de la gueule : le portail de sortie (cadre en obsidienne pour qu'il tienne)
        int pz = front - 6;
        for (int u = -2; u <= 2; u++) {
            for (int v = 0; v <= 6; v++) {
                boolean inside = Math.abs(u) <= 1 && v >= 1 && v <= 4;
                set(u, v, pz, inside ? "nether_portal[axis=x]" : "obsidian");
            }
        }
        portal = new int[]{0, 1, pz};
        int w0 = firstSolidW(1, 6);
        if (w0 != Integer.MIN_VALUE) {
            set(-1, 6, front - w0, "bone_block");
            set(1, 6, front - w0, "bone_block");
        }
        int wl = firstSolidW(2, 1);
        if (wl != Integer.MIN_VALUE) {
            set(-2, 1, front - wl, "bone_block");
            set(2, 1, front - wl, "bone_block");
        }
        // cornes
        for (int s = -1; s <= 1; s += 2) {
            for (double t = 0; t <= 1.0001; t += 0.01) {
                double cx = s * (7 + 9 * t - 4 * t * t);
                double cy = 15 + 3 * t + 10 * t * t;
                double cw = 4 - 2 * t - 2 * t * t;
                double rad = 2.4 * (1 - t) + 0.55;
                for (int x = (int) Math.floor(cx - rad); x <= Math.ceil(cx + rad); x++) {
                    for (int y = (int) Math.floor(cy - rad); y <= Math.ceil(cy + rad); y++) {
                        for (int w = (int) Math.floor(cw - rad); w <= Math.ceil(cw + rad); w++) {
                            if (sq(x - cx) + sq(y - cy) + sq(w - cw) > rad * rad) continue;
                            String m;
                            if (t > 0.86) m = "bone_block";
                            else if (Math.abs(t - 0.32) < 0.025 || Math.abs(t - 0.6) < 0.025) m = "magma_block";
                            else m = pick(hash(x, y, w, 52), "blackstone", 6, "polished_blackstone", 3, "basalt", 1);
                            set(x, y, front - w, m);
                        }
                    }
                }
            }
        }
        // bannières de chaque côté de la gueule
        for (int u : new int[]{-5, 5}) {
            int w = firstSolidW(u, 4);
            if (w == Integer.MIN_VALUE) continue;
            int z = front - w + 1;
            set(u, 4, z, "red_wall_banner[facing=south]");
            banners.add(new int[]{u, 4, z});
        }
    }

    /** Premier bloc plein du crâne en partant de l'avant (w croissant) sur la ligne (u, v). */
    private int firstSolidW(int u, int v) {
        for (int w = -2; w <= 12; w++) {
            String s = get(u, v, front - w);
            if (s != null && !s.equals("air") && !s.startsWith("light")) return w;
        }
        return Integer.MIN_VALUE;
    }

    // ================================================================= bulle

    /**
     * Dôme fermé : on ne voit plus l'extérieur. Paroi intérieure rouge sang (verrues du Nether, briques rouges,
     * points de champilampe), coque extérieure en roche noire, lianes pleureuses qui pendent du plafond.
     */
    private void bubble() {
        int box = dome + 3;
        List<int[]> ceiling = new ArrayList<>();
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                double d = Math.sqrt(x * x + z * z);
                for (int y = -8; y <= domeH + 3; y++) {
                    if (domeF(d, y, dome, domeH) < 1) continue;
                    if (domeF(d, y, dome + 2.3, domeH + 2.3) >= 1) continue;
                    boolean inner = domeF(d, y, dome + 1.1, domeH + 1.1) < 1;
                    String m;
                    if (!inner) m = pick(hash(x, y, z, 61), "blackstone", 3, "basalt", 1);
                    else if (y < 7) m = pick(hash(x, y, z, 62), "blackstone", 4, "netherrack", 3, "magma_block", 1);
                    else m = pick(hash(x, y, z, 63), "nether_wart_block", 62, "red_nether_bricks", 14, "crimson_hyphae[axis=y]", 12,
                            "shroomlight", 5, "magma_block", 7);
                    set(x, y, z, m);
                    if (inner && y > domeH * 0.4 && domeF(d, y - 1, dome, domeH) < 1) ceiling.add(new int[]{x, y, z});
                }
            }
        }
        for (int[] c : ceiling) {
            if (hash(c[0], c[1], c[2], 64) > 0.06) continue;
            int len = 2 + (int) (hash(c[0], c[1], c[2], 65) * 7);
            for (int i = 1; i <= len; i++) {
                String below = get(c[0], c[1] - i, c[2]);
                if (below != null && !below.equals("air")) break;
                set(c[0], c[1] - i, c[2], i == len ? "weeping_vines[age=25]" : "weeping_vines_plant");
            }
        }
    }

    // ================================================================= murets / barrières reliés

    static boolean full(String id) {
        if (id == null || id.equals("air") || id.startsWith("light") || id.equals("lava")) return false;
        return !(id.contains("wall") || id.contains("fence") || id.contains("banner") || id.contains("skull")
                || id.contains("roots") || id.contains("fungus") || id.contains("candle") || id.contains("chain")
                || id.startsWith("lantern"));
    }

    private void connect() {
        connectWalls(blocks);
    }

    /** Murets / barrières : relie chaque bloc à ses voisins. */
    static void connectWalls(Map<Long, String> blocks) {
        List<Long> keys = new ArrayList<>(blocks.keySet());
        for (long k : keys) {
            String id = blocks.get(k);
            boolean wall = id.endsWith("_wall"), fence = id.endsWith("_fence");
            if (!wall && !fence) continue;
            int x = kx(k), y = ky(k), z = kz(k);
            String[] names = {"north", "east", "south", "west"};
            int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
            boolean[] c = new boolean[4];
            for (int i = 0; i < 4; i++) {
                String n = blocks.get(key(x + dirs[i][0], y, z + dirs[i][1]));
                c[i] = n != null && (n.startsWith(id) || full(n));
            }
            StringBuilder sb = new StringBuilder(id).append('[');
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append(',');
                sb.append(names[i]).append('=').append(wall ? (c[i] ? "low" : "none") : String.valueOf(c[i]));
            }
            if (wall) {
                boolean straight = (c[0] && c[2] && !c[1] && !c[3]) || (c[1] && c[3] && !c[0] && !c[2]);
                sb.append(",up=").append(!straight);
            }
            sb.append(']');
            blocks.put(k, sb.toString());
        }
    }
}
