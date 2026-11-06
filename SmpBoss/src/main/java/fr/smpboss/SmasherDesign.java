package fr.smpboss;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static fr.smpboss.ArenaDesign.hash;
import static fr.smpboss.ArenaDesign.key;
import static fr.smpboss.ArenaDesign.pick;

/**
 * L'arène d'Adam Smasher : une île flottante, un bout de Night City sous un dôme de verre fermé.
 * Au centre, un carrefour (lignes de néon cyan et magenta dans le bitume) ; tout autour, des tours sombres aux fenêtres
 * allumées, des panneaux de pub en néon, une rambarde ; au nord, la porte de l'ascenseur (sortie).
 * Dessous : un cône de béton et de câbles qui pendent dans le vide.
 */
final class SmasherDesign implements ArenaPlan {

    final int r, dome, domeH, island, protect;
    final Map<Long, String> blocks = new LinkedHashMap<>();
    final Set<Long> soft = new HashSet<>();
    final List<int[]> banners = new ArrayList<>();

    @Override public Map<Long, String> blocks() { return blocks; }
    @Override public Set<Long> soft() { return soft; }
    @Override public List<int[]> banners() { return banners; }
    @Override public int radius() { return r; }
    @Override public int protect() { return protect; }

    SmasherDesign(int r) {
        this.r = r;
        this.dome = r + 14;
        this.domeH = r + 16;
        this.island = dome + 3;
        this.protect = island + 4;
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

    /** < 1 dans le dôme (demi-ellipsoïde) */
    private double domeF(double d, double y, double a, double b) {
        return (d * d) / (a * a) + (y * y) / (b * b);
    }

    private boolean inDome(double d, int y) {
        return y >= 1 && domeF(d, y, dome, domeH) < 1;
    }

    private void build() {
        for (int x = -island; x <= island; x++) {
            for (int z = -island; z <= island; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > island + 0.3) continue;
                // l'air du dôme
                for (int y = 1; y <= domeH + 1; y++) if (inDome(d, y)) set(x, y, z, "air");
                ground(x, z, d);
                underside(x, z, d);
            }
        }
        towers();
        railing();
        lamps();
        elevator();
        shell();
    }

    // ================================================================= le sol

    private void ground(int x, int z, double d) {
        if (x == 0 && z == 0) {
            set(0, 0, 0, "crying_obsidian");
            set(0, -1, 0, "obsidian");
            return;
        }
        if (d <= 3.2) {
            set(x, 0, z, d <= 1.6 ? "chiseled_polished_blackstone" : "polished_blackstone");
            set(x, -1, z, "polished_blackstone");
            return;
        }
        if (d <= r + 0.3) {
            // lignes de néon : un quadrillage tous les 6 blocs, cyan dans un sens, magenta dans l'autre ; un anneau rose au bord
            boolean ringNeon = Math.abs(d - (r - 1.5)) < 0.55;
            boolean lineX = Math.floorMod(x, 6) == 0, lineZ = Math.floorMod(z, 6) == 0;
            if (ringNeon) {
                set(x, 0, z, "pink_stained_glass");
                set(x, -1, z, "sea_lantern");
            } else if (lineX && !lineZ) {
                set(x, 0, z, "cyan_stained_glass");
                set(x, -1, z, "sea_lantern");
            } else if (lineZ && !lineX) {
                set(x, 0, z, "magenta_stained_glass");
                set(x, -1, z, "sea_lantern");
            } else if (Math.abs(x) <= 1 && Math.floorMod(z, 4) < 2 || Math.abs(z) <= 1 && Math.floorMod(x, 4) < 2) {
                set(x, 0, z, "yellow_concrete"); // marquage au sol du carrefour
                set(x, -1, z, "gray_concrete");
            } else {
                set(x, 0, z, pick(hash(x, 0, z, 401), "black_concrete", 40, "gray_concrete", 22, "polished_blackstone", 14,
                        "polished_deepslate", 12, "cracked_polished_blackstone_bricks", 6, "black_terracotta", 6));
                set(x, -1, z, "gray_concrete");
            }
            return;
        }
        // trottoir et socle des tours
        set(x, 0, z, d <= r + 1.4 ? "smooth_stone" : pick(hash(x, 0, z, 402), "gray_concrete", 40, "polished_deepslate", 30, "black_concrete", 20, "polished_andesite", 10));
        set(x, -1, z, "gray_concrete");
    }

    /** Dessous de l'île : un cône de béton, de roche et de câbles, avec quelques lumières. */
    private void underside(int x, int z, double d) {
        int depth = (int) Math.round(4 + (island - d) * 0.75 + hash(x, 0, z, 411) * 3);
        for (int y = -2; y >= -depth; y--) {
            String m = y > -4 ? "gray_concrete" : pick(hash(x, y, z, 412), "blackstone", 30, "deepslate", 30, "polished_blackstone", 15,
                    "gray_concrete", 12, "iron_block", 1, "sea_lantern", 1.2, "magenta_concrete", 0.8);
            setSoft(x, y, z, m);
        }
        // câbles qui pendent
        if (d > island - 2.2 && hash(x, 1, z, 413) < 0.12) {
            int len = 4 + (int) (hash(x, 2, z, 414) * 9);
            for (int y = -depth - 1; y >= -depth - len; y--) setSoft(x, y, z, "chain");
            setSoft(x, -depth - len - 1, z, hash(x, 3, z, 415) < 0.5 ? "redstone_lamp[lit=true]" : "sea_lantern");
        }
    }

    // ================================================================= la ville

    /** Tours autour du carrefour, collées au dôme : fenêtres allumées, néons verticaux, panneaux de pub. */
    private void towers() {
        int n = Math.max(10, (int) Math.round(2 * Math.PI * (r + 8) / 7.5));
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + 0.13;
            // la porte de l'ascenseur au nord : pas de tour devant
            if (Math.abs(ArenaDesign.angDiff(a, -Math.PI / 2)) < 0.32) continue;
            double rad = r + 5 + hash(i, 0, 0, 421) * Math.max(1, dome - r - 9);
            int cx = (int) Math.round(Math.cos(a) * rad), cz = (int) Math.round(Math.sin(a) * rad);
            int half = 1 + (int) (hash(i, 1, 0, 422) * 2.2);
            int want = 10 + (int) (hash(i, 2, 0, 423) * (domeH * 0.75));
            String wall = pick(hash(i, 3, 0, 424), "black_concrete", 40, "gray_concrete", 25, "polished_blackstone_bricks", 20, "deepslate_tiles", 15);
            String win = pick(hash(i, 4, 0, 425), "yellow_stained_glass", 30, "light_blue_stained_glass", 30, "black_stained_glass", 20, "orange_stained_glass", 20);
            String neon = pick(hash(i, 5, 0, 426), "magenta_concrete", 30, "cyan_concrete", 30, "pink_concrete", 20, "yellow_concrete", 10, "red_concrete", 10);
            for (int x = cx - half; x <= cx + half; x++) {
                for (int z = cz - half; z <= cz + half; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d < r + 2.5) continue;
                    boolean edge = x == cx - half || x == cx + half || z == cz - half || z == cz + half;
                    boolean corner = (x == cx - half || x == cx + half) && (z == cz - half || z == cz + half);
                    for (int y = 1; y <= want; y++) {
                        if (!inDome(d + 0.8, y + 1)) break; // la tour s'arrête sous le verre
                        String id;
                        if (!edge) id = y % 4 == 0 ? wall : "air";
                        else if (corner) id = (y % 6 == 3 && hash(i, y, 0, 427) < 0.5) ? neon : wall;
                        else id = (y % 3 != 0 && hash(x, y, z, 428) < 0.62) ? win : wall;
                        set(x, y, z, id);
                        if (!edge && y % 4 == 2 && hash(x, y, z, 429) < 0.4) set(x, y, z, "light[level=11]");
                    }
                }
            }
            // panneau de pub en néon sur la face qui regarde le centre
            int fx = cx - (int) Math.signum(Math.round(Math.cos(a) * 1.2)) * (half + 1);
            int fz = cz - (int) Math.signum(Math.round(Math.sin(a) * 1.2)) * (half + 1);
            if (hash(i, 6, 0, 430) < 0.75) {
                int by = 5 + (int) (hash(i, 7, 0, 431) * 6);
                String[] pal = {"magenta_concrete", "cyan_concrete", "pink_concrete", "light_blue_concrete", "yellow_concrete"};
                String c1 = pal[(int) (hash(i, 8, 0, 432) * pal.length)], c2 = pal[(int) (hash(i, 9, 0, 433) * pal.length)];
                boolean alongX = Math.abs(Math.cos(a)) < Math.abs(Math.sin(a));
                for (int u = -2; u <= 2; u++) {
                    for (int v = 0; v < 3; v++) {
                        int x = alongX ? fx + u : fx, z = alongX ? fz : fz + u;
                        double d = Math.sqrt(x * x + z * z);
                        if (d < r + 2 || !inDome(d, by + v + 1)) continue;
                        set(x, by + v, z, (u + v) % 2 == 0 ? c1 : c2);
                    }
                }
            }
        }
    }

    /** Rambarde au bord de la piste (ouverte au nord, vers l'ascenseur). */
    private void railing() {
        int n = (int) Math.round(2 * Math.PI * (r + 1) * 1.6);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            int x = (int) Math.round(Math.cos(a) * (r + 1)), z = (int) Math.round(Math.sin(a) * (r + 1));
            if (Math.abs(x) <= 2 && z < 0) continue;
            set(x, 0, z, "polished_blackstone");
            set(x, 1, z, i % 7 == 0 ? "polished_blackstone_wall" : "iron_bars");
        }
    }

    /** Lampadaires (barres de fer + bâton de l'End) et lumière douce sur la piste. */
    private void lamps() {
        for (int x = -r; x <= r; x += 7) {
            for (int z = -r; z <= r; z += 7) {
                if (x * x + z * z <= (r - 2) * (r - 2) && x * x + z * z > 16) set(x, 5, z, "light[level=11]");
            }
        }
        int n = Math.max(8, (int) Math.round(2 * Math.PI * (r + 2) / 9));
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + 0.2;
            int x = (int) Math.round(Math.cos(a) * (r + 2)), z = (int) Math.round(Math.sin(a) * (r + 2));
            if (Math.abs(x) <= 3 && z < 0) continue;
            for (int y = 1; y <= 4; y++) set(x, y, z, "iron_bars");
            set(x, 5, z, "end_rod[facing=up]");
            set(x, 6, z, "light[level=13]");
        }
    }

    /** La porte de l'ascenseur au nord (la sortie) : un cadre de métal et de néon. */
    private void elevator() {
        int z0 = -(r + 3);
        for (int x = -3; x <= 3; x++) {
            for (int y = 0; y <= 7; y++) {
                for (int z = z0 - 2; z <= z0; z++) {
                    boolean frame = Math.abs(x) == 3 || y == 7 || y == 0;
                    if (z == z0 - 2) set(x, y, z, frame ? "polished_blackstone_bricks" : "black_stained_glass");
                    else if (frame) set(x, y, z, Math.abs(x) == 3 && y % 2 == 1 ? "sea_lantern" : "iron_block");
                    else set(x, y, z, "air");
                }
            }
        }
        for (int x = -2; x <= 2; x++) set(x, 7, z0 + 1, "magenta_concrete"); // enseigne au-dessus
        set(0, 3, z0 - 1, "light[level=15]");
    }

    /** Le dôme fermé : verre teinté, nervures en métal (méridiens et anneaux), néon cyan au pied. */
    private void shell() {
        int box = dome + 2;
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                double d = Math.sqrt(x * x + z * z);
                for (int y = 1; y <= domeH + 2; y++) {
                    double f = domeF(d, y, dome, domeH);
                    if (f < 1 || domeF(d, y, dome + 1.15, domeH + 1.15) >= 1) continue;
                    double ang = Math.atan2(z, x) + Math.PI, seg = Math.PI * 2 / 12, mm = ang % seg;
                    boolean meridian = d > 3 && Math.min(mm, seg - mm) * d < 0.6;
                    boolean ring = y % 9 == 0;
                    String m;
                    if (y <= 2) m = "cyan_stained_glass";
                    else if (meridian || ring) m = "gray_concrete";
                    else m = hash(x, y, z, 441) < 0.06 ? "purple_stained_glass" : "black_stained_glass";
                    set(x, y, z, m);
                }
            }
        }
        // le dôme est bien fermé : on bouche les trous éventuels au pied
        for (int i = 0; i < 360; i++) {
            double a = Math.toRadians(i);
            int x = (int) Math.round(Math.cos(a) * dome), z = (int) Math.round(Math.sin(a) * dome);
            for (int y = 1; y <= 2; y++) if (!blocks.containsKey(key(x, y, z)) || "air".equals(blocks.get(key(x, y, z)))) set(x, y, z, "cyan_stained_glass");
        }
    }
}
