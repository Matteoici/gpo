package fr.smpevents.island;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan de l'Île des Classements (sans Bukkit, testable à part). (0,0,0) = centre de la place, au niveau du sol
 * (on marche à y = 1). Nord = z négatif. On arrive au sud, devant le portail de retour, et on remonte vers le podium.
 *
 *  - île flottante aux bords irréguliers, dessous en roche qui finit en pointe, cristaux et mousse qui pendent
 *  - au centre : un podium 1-2-3 (or / fer / cuivre), une balise sur la 1re marche : faisceau doré visible de loin
 *  - autour : 10 socles en quartz, chacun avec un classement en hologramme au-dessus
 *  - allées, cerisiers, fleurs, lanternes, haie d'azalée au bord pour ne pas tomber
 *  - au sud : l'arche du portail qui ramène au spawn
 */
public final class IslandDesign {

    public final int R;
    public final Map<Long, String> blocks = new LinkedHashMap<>();
    public final Set<Long> soft = new HashSet<>();
    /** Emplacements des hologrammes de classement (x, y, z) : un par socle. */
    public final List<double[]> boards = new ArrayList<>();
    /** Zone du portail de retour : minX, minY, minZ, maxX, maxY, maxZ (relatif). */
    public int[] portal;
    /** Point d'arrivée (x, y, z), regard vers le nord. */
    public double[] arrival;
    /** Hauteur la plus basse du dessous (pour vérifier le bas du monde). */
    public int bottom;
    private final Map<Long, Integer> top = new LinkedHashMap<>();

    public IslandDesign(int R) {
        this.R = R;
        build();
    }

    // ================================================================= clés

    public static long key(int x, int y, int z) {
        return (((long) (x + 4096)) << 40) | (((long) (z + 4096)) << 20) | (long) (y + 4096);
    }

    public static int kx(long k) {
        return (int) ((k >>> 40) & 0xFFFFF) - 4096;
    }

    public static int kz(long k) {
        return (int) ((k >>> 20) & 0xFFFFF) - 4096;
    }

    public static int ky(long k) {
        return (int) (k & 0xFFFFF) - 4096;
    }

    private void set(int x, int y, int z, String id) {
        long k = key(x, y, z);
        blocks.put(k, id);
        soft.remove(k);
    }

    private String get(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    private boolean free(int x, int y, int z) {
        String s = get(x, y, z);
        return s == null || s.equals("air");
    }

    static double hash(int x, int y, int z, int s) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ s * 2654435761L;
        h ^= h >>> 13;
        h *= 0x5bd1e995L;
        h ^= h >>> 15;
        h *= 0x27d4eb2dL;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

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

    private static double sq(double a) {
        return a * a;
    }

    /** Rayon du bord dans la direction a (bord irrégulier). */
    private double edge(double a) {
        return R * (1 + 0.09 * Math.sin(3 * a + 1.1) + 0.06 * Math.sin(5 * a + 2.3) + 0.04 * Math.sin(8 * a + 0.4));
    }

    private static String facing(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? "east" : "west";
        return dz >= 0 ? "south" : "north";
    }

    // ================================================================= construction

    private int ringR() {
        return Math.round(R * 0.58f);
    }

    private int portalZ() {
        return (int) Math.round(edge(Math.PI / 2) - 5);
    }

    private void build() {
        int box = (int) Math.ceil(R * 1.25) + 2;
        // vider l'air au-dessus (arbres / monuments existants dans le ciel : rien normalement)
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                double d = Math.sqrt(x * x + z * z), a = Math.atan2(z, x);
                double e = edge(a);
                if (d > e + 0.3) continue;
                column(x, z, d, e);
            }
        }
        plaza();
        paths();
        podium();
        pedestals();
        returnPortal();
        trees();
        hedge();
        flowers();
        connect();
    }

    private void column(int x, int z, double d, double e) {
        double t = d / e;                     // 0 au centre, 1 au bord
        // dessus : quasi plat, léger relief vers le bord
        int h = 0;
        if (t > 0.78) h = (int) Math.round(Math.sin((t - 0.78) / 0.22 * Math.PI) * (0.6 + hash(x, 0, z, 2) * 0.8));
        top.put(key(x, 0, z), h);
        set(x, h, z, "grass_block");
        for (int y = 1; y <= 10; y++) set(x, h + y, z, "air");
        int dirt = 2 + (int) (hash(x, 1, z, 3) * 2);
        for (int y = h - 1; y >= h - dirt; y--) set(x, y, z, "dirt");
        // dessous en pointe
        double depth = Math.pow(Math.max(0, 1 - t * t), 0.75) * R * 0.95 + hash(x, 2, z, 4) * 3;
        // quelques pointes plus longues
        if (hash(x / 3, 3, z / 3, 5) < 0.08) depth += 4 + hash(x, 4, z, 6) * 6;
        int bot = -(int) Math.round(depth) - 1;
        for (int y = h - dirt - 1; y >= bot; y--) {
            String m;
            double hh = hash(x, y, z, 7);
            boolean side = y < bot + 2 || t > 0.86;
            if (y > -6 && t > 0.8 && hh < 0.35) m = "moss_block";
            else if (side && hh < 0.04) m = "amethyst_block";
            else m = pick(hh, "stone", 40, "andesite", 20, "cobblestone", 12, "tuff", 10, "calcite", 4, "mossy_cobblestone", 8,
                    "gold_ore", 1.2, "diorite", 5);
            set(x, y, z, m);
            soft.add(key(x, y, z));
        }
        bottom = Math.min(bottom, bot);
    }

    private int h(int x, int z) {
        Integer v = top.get(key(x, 0, z));
        return v == null ? 0 : v;
    }

    /** Place centrale : quartz, anneau d'or, étoile à 8 branches. */
    private void plaza() {
        double pr = R * 0.38;
        for (int x = -(int) pr - 1; x <= pr + 1; x++) {
            for (int z = -(int) pr - 1; z <= pr + 1; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > pr + 0.4) continue;
                String m;
                double a = Math.atan2(z, x);
                double star = Math.abs(Math.sin(4 * a)) * d;
                if (d > pr - 0.9) m = "polished_andesite";
                else if (d > pr - 1.8) m = "gold_block";
                else if (d > pr - 2.6) m = "smooth_quartz";
                else if (star < 0.6 && d > 4.5) m = "chiseled_quartz_block";
                else m = (Math.floorMod(x + z, 2) == 0) ? "smooth_quartz" : "quartz_bricks";
                set(x, 0, z, m);
                top.put(key(x, 0, z), 0);
            }
        }
    }

    /** Allées de terre battue vers chaque socle et vers le portail. */
    private void paths() {
        List<Double> angles = new ArrayList<>(pedestalAngles());
        angles.add(Math.PI / 2);
        double pr = R * 0.38;
        for (double a : angles) {
            double to = a == Math.PI / 2 ? portalZ() - 1 : ringR() - 1.5;
            for (double s = pr; s <= to; s += 0.3) {
                for (double w = -1.1; w <= 1.1; w += 0.5) {
                    int x = (int) Math.round(Math.cos(a) * s - Math.sin(a) * w), z = (int) Math.round(Math.sin(a) * s + Math.cos(a) * w);
                    if (h(x, z) != 0) continue;
                    set(x, 0, z, hash(x, 0, z, 21) < 0.15 ? "coarse_dirt" : "dirt_path");
                }
            }
        }
    }

    private List<Double> pedestalAngles() {
        List<Double> out = new ArrayList<>();
        // 10 socles sur 304°, en laissant le sud (l'arrivée) libre
        double from = Math.toRadians(90 + 28), span = Math.toRadians(360 - 56);
        for (int i = 0; i < 10; i++) out.add(from + span * i / 9.0);
        return out;
    }

    /** Podium 1-2-3 face au sud, balise sur la 1re marche. */
    private void podium() {
        for (int x = -5; x <= 5; x++) {
            for (int z = -2; z <= 2; z++) {
                set(x, 0, z, "polished_andesite");
            }
        }
        // 1re place : 3×3, 3 de haut, dessus en or (base de la balise)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                set(x, 1, z, "quartz_bricks");
                set(x, 2, z, "quartz_bricks");
                set(x, 3, z, "gold_block");
            }
        }
        set(0, 4, 0, "beacon");
        set(0, 5, 0, "yellow_stained_glass");
        // 2e place (ouest) : 2 de haut, fer
        for (int x = -4; x <= -2; x++) {
            for (int z = -1; z <= 1; z++) {
                set(x, 1, z, "quartz_bricks");
                set(x, 2, z, "iron_block");
            }
        }
        // 3e place (est) : 1 de haut, cuivre ciré
        for (int x = 2; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) set(x, 1, z, "waxed_copper_block");
        }
        // marches devant le podium
        for (int x = -1; x <= 1; x++) set(x, 1, 2, "smooth_quartz_stairs[facing=north]");
        for (int x = -1; x <= 1; x++) set(x, 2, 2, "air");
        // lanternes aux coins
        for (int x : new int[]{-5, 5}) {
            for (int z : new int[]{-2, 2}) {
                set(x, 1, z, "quartz_pillar[axis=y]");
                set(x, 2, z, "lantern");
            }
        }
    }

    /** 10 socles en anneau ; hologramme au-dessus de chacun. */
    private void pedestals() {
        int rr = ringR();
        for (double a : pedestalAngles()) {
            int px = (int) Math.round(Math.cos(a) * rr), pz = (int) Math.round(Math.sin(a) * rr);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    double dd = Math.sqrt(dx * dx + dz * dz);
                    if (dd > 2.6) continue;
                    set(px + dx, 0, pz + dz, dd > 1.8 ? "stone_bricks" : "polished_andesite");
                    top.put(key(px + dx, 0, pz + dz), 0);
                    for (int y = 1; y <= 8; y++) set(px + dx, y, pz + dz, "air");
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    set(px + dx, 1, pz + dz, (dx == 0 || dz == 0) ? "smooth_quartz" : "quartz_slab[type=bottom]");
                }
            }
            set(px, 2, pz, "quartz_pillar[axis=y]");
            set(px, 3, pz, "chiseled_quartz_block");
            // lanternes de chaque côté (vers les voisins)
            double tx = -Math.sin(a), tz = Math.cos(a);
            for (int s = -1; s <= 1; s += 2) {
                int lx = (int) Math.round(px + tx * 2.6 * s), lz = (int) Math.round(pz + tz * 2.6 * s);
                set(lx, 1, lz, "stone_brick_wall");
                set(lx, 2, lz, "lantern");
            }
            boards.add(new double[]{px + 0.5, 4.15, pz + 0.5});
        }
    }

    /** Arche du portail de retour au spawn, au sud ; on arrive juste devant. */
    private void returnPortal() {
        int pz = portalZ();
        for (int x = -4; x <= 4; x++) {
            for (int z = pz - 4; z <= pz + 1; z++) {
                set(x, 0, z, Math.abs(x) <= 1 ? "dirt_path" : "stone_bricks");
                top.put(key(x, 0, z), 0);
                for (int y = 1; y <= 8; y++) set(x, y, z, "air");
            }
        }
        for (int x = -2; x <= 2; x++) {
            for (int y = 0; y <= 4; y++) {
                boolean inside = Math.abs(x) <= 1 && y >= 1 && y <= 3;
                set(x, y, pz, inside ? "nether_portal[axis=x]" : "obsidian");
            }
        }
        for (int s = -1; s <= 1; s += 2) {
            for (int y = 0; y <= 5; y++) set(3 * s, y, pz, y == 5 ? "chiseled_quartz_block" : "quartz_pillar[axis=y]");
            set(3 * s, 6, pz, "lantern");
            set(2 * s, 5, pz, "smooth_quartz");
        }
        for (int x = -1; x <= 1; x++) set(x, 5, pz, x == 0 ? "gold_block" : "smooth_quartz");
        set(0, 6, pz, "chiseled_quartz_block");
        // derrière le portail : un mur de quartz (on ne le traverse pas par l'arrière)
        for (int x = -3; x <= 3; x++) for (int y = 0; y <= 5; y++) if (free(x, y, pz + 1)) set(x, y, pz + 1, "quartz_bricks");
        portal = new int[]{-1, 1, pz, 1, 3, pz};
        arrival = new double[]{0.5, 1, pz - 3 + 0.5};
    }

    /** Cerisiers entre les socles, près du bord. */
    private void trees() {
        List<Double> ped = pedestalAngles();
        for (int i = 0; i + 1 < ped.size(); i++) {
            if (i % 2 == 1) continue;
            double a = (ped.get(i) + ped.get(i + 1)) / 2;
            double d = ringR() + (edge(a) - ringR()) * 0.55;
            int tx = (int) Math.round(Math.cos(a) * d), tz = (int) Math.round(Math.sin(a) * d);
            tree(tx, h(tx, tz) + 1, tz, i);
        }
        // deux arbres qui encadrent l'arrivée
        for (int s = -1; s <= 1; s += 2) {
            int tx = 7 * s, tz = portalZ() - 4;
            tree(tx, h(tx, tz) + 1, tz, 20 + s);
        }
    }

    private void tree(int x, int y, int z, int seed) {
        int hgt = 4 + (int) (hash(seed, 0, 0, 31) * 2);
        for (int v = 0; v < hgt; v++) set(x, y + v, z, "cherry_log[axis=y]");
        int cy = y + hgt;
        double rad = 3.2 + hash(seed, 1, 0, 31) * 0.8;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    double e = sq(dx / rad) + sq(dy / 2.2) + sq(dz / rad);
                    if (e > 1 || hash(x + dx, cy + dy, z + dz, 32) < 0.12 * e) continue;
                    if (!free(x + dx, cy + dy, z + dz)) continue;
                    set(x + dx, cy + dy, z + dz, "cherry_leaves[persistent=true]");
                }
            }
        }
        // pétales au pied
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (hash(x + dx, 0, z + dz, 33) > 0.35 || (dx == 0 && dz == 0)) continue;
                int hy = h(x + dx, z + dz);
                if ("grass_block".equals(get(x + dx, hy, z + dz)) && free(x + dx, hy + 1, z + dz))
                    set(x + dx, hy + 1, z + dz, "pink_petals[flower_amount=" + (1 + (int) (hash(x + dx, 1, z + dz, 34) * 4)) + ",facing=north]");
            }
        }
    }

    /** Haie d'azalée tout autour du bord (on ne tombe pas par accident). */
    private void hedge() {
        for (Map.Entry<Long, Integer> en : new ArrayList<>(top.entrySet())) {
            int x = kx(en.getKey()), z = kz(en.getKey());
            double d = Math.sqrt(x * x + z * z), e = edge(Math.atan2(z, x));
            if (d < e - 1.6 || d > e + 0.3) continue;
            int y = en.getValue() + 1;
            if (!free(x, y, z)) continue;
            set(x, y, z, hash(x, y, z, 41) < 0.25 ? "flowering_azalea_leaves[persistent=true]" : "azalea_leaves[persistent=true]");
        }
    }

    private void flowers() {
        for (Map.Entry<Long, Integer> en : new ArrayList<>(top.entrySet())) {
            int x = kx(en.getKey()), z = kz(en.getKey());
            int y = en.getValue();
            if (!"grass_block".equals(get(x, y, z)) || !free(x, y + 1, z)) continue;
            double hh = hash(x, y, z, 51);
            String f = null;
            if (hh < 0.16) f = "short_grass";
            else if (hh < 0.19) f = "poppy";
            else if (hh < 0.22) f = "dandelion";
            else if (hh < 0.245) f = "cornflower";
            else if (hh < 0.265) f = "azure_bluet";
            else if (hh < 0.275) f = "lily_of_the_valley";
            if (f != null) set(x, y + 1, z, f);
        }
        // lampadaires le long de la place
        double pr = R * 0.38 + 1.5;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(22.5 + 45 * i);
            if (Math.abs(a - Math.PI / 2) < 0.5) continue;
            int x = (int) Math.round(Math.cos(a) * pr), z = (int) Math.round(Math.sin(a) * pr);
            if (h(x, z) != 0) continue;
            set(x, 1, z, "stone_brick_wall");
            set(x, 2, z, "stone_brick_wall");
            set(x, 3, z, "lantern");
        }
    }

    // ================================================================= murets reliés

    /** Les murets et barrières : on les relie à leurs voisins pleins (appelé à la fin). */
    public void connect() {
        for (long k : new ArrayList<>(blocks.keySet())) {
            String id = blocks.get(k);
            if (!id.endsWith("_wall")) continue;
            int x = kx(k), y = ky(k), z = kz(k);
            String[] names = {"north", "east", "south", "west"};
            int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
            StringBuilder sb = new StringBuilder(id).append('[');
            boolean[] c = new boolean[4];
            for (int i = 0; i < 4; i++) {
                String n = blocks.get(key(x + dirs[i][0], y, z + dirs[i][1]));
                c[i] = n != null && n.startsWith(id);
                if (i > 0) sb.append(',');
                sb.append(names[i]).append('=').append(c[i] ? "low" : "none");
            }
            sb.append(",up=true]");
            blocks.put(k, sb.toString());
        }
    }

    // ================================================================= porte du spawn

    /**
     * Petite arche dorée posée au spawn : portail 3×3, piliers de quartz, clé d'or.
     * Repère : (0,0,0) = bloc sous les pieds au centre de l'arche, la face avant regarde vers +w.
     * Renvoie les blocs en (u = côté, y, w = profondeur) ; l'appelant les tourne selon la direction.
     */
    public static Map<int[], String> gate() {
        Map<int[], String> out = new LinkedHashMap<>();
        for (int u = -2; u <= 2; u++) {
            for (int y = 0; y <= 4; y++) {
                boolean inside = Math.abs(u) <= 1 && y >= 1 && y <= 3;
                out.put(new int[]{u, y, 0}, inside ? "nether_portal" : "obsidian");
            }
        }
        for (int s = -1; s <= 1; s += 2) {
            for (int y = 0; y <= 5; y++) out.put(new int[]{3 * s, y, 0}, y == 5 ? "chiseled_quartz_block" : "quartz_pillar[axis=y]");
            out.put(new int[]{3 * s, 6, 0}, "lantern");
            out.put(new int[]{2 * s, 5, 0}, "smooth_quartz");
            out.put(new int[]{3 * s, 0, 1}, "gold_block");
            out.put(new int[]{3 * s, 0, -1}, "gold_block");
        }
        for (int u = -1; u <= 1; u++) out.put(new int[]{u, 5, 0}, u == 0 ? "gold_block" : "smooth_quartz");
        out.put(new int[]{0, 6, 0}, "chiseled_quartz_block");
        return out;
    }
}
