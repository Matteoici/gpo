package fr.smpparkour;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Construit un parcours devant le joueur : des sauts faisables (vérifiés : place pour la tête, rien sur la trajectoire),
 * qui tournent et montent, avec un départ, des checkpoints réguliers et une arrivée, aux couleurs d'un thème.
 */
final class Generator {

    record Theme(List<Material> blocks, Material special, Material plate, Material finish) {
    }

    static final Map<String, Theme> THEMES = Map.of(
            "neon", new Theme(List.of(Material.LIME_CONCRETE, Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE,
                    Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE, Material.PINK_CONCRETE),
                    Material.SEA_LANTERN, Material.LIGHT_WEIGHTED_PRESSURE_PLATE, Material.EMERALD_BLOCK),
            "quartz", new Theme(List.of(Material.QUARTZ_BLOCK, Material.SMOOTH_QUARTZ, Material.QUARTZ_PILLAR, Material.CHISELED_QUARTZ_BLOCK),
                    Material.GOLD_BLOCK, Material.LIGHT_WEIGHTED_PRESSURE_PLATE, Material.EMERALD_BLOCK),
            "nether", new Theme(List.of(Material.POLISHED_BLACKSTONE, Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM,
                    Material.NETHER_BRICKS, Material.MAGMA_BLOCK),
                    Material.SHROOMLIGHT, Material.POLISHED_BLACKSTONE_PRESSURE_PLATE, Material.CRYING_OBSIDIAN),
            "end", new Theme(List.of(Material.PURPUR_BLOCK, Material.END_STONE_BRICKS, Material.PURPUR_PILLAR, Material.OBSIDIAN),
                    Material.END_ROD, Material.HEAVY_WEIGHTED_PRESSURE_PLATE, Material.BEACON),
            "foret", new Theme(List.of(Material.MOSS_BLOCK, Material.OAK_LOG, Material.MOSSY_COBBLESTONE, Material.MUD_BRICKS,
                    Material.SPRUCE_PLANKS), Material.GLOWSTONE, Material.OAK_PRESSURE_PLATE, Material.EMERALD_BLOCK),
            "glace", new Theme(List.of(Material.SNOW_BLOCK, Material.WHITE_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.CALCITE),
                    Material.SEA_LANTERN, Material.LIGHT_WEIGHTED_PRESSURE_PLATE, Material.DIAMOND_BLOCK));

    private final SmpParkour pl;
    private final Random rng = new Random();

    Generator(SmpParkour pl) {
        this.pl = pl;
    }

    /** Écart (blocs vides) et montée possibles selon la difficulté. */
    private int[] gapRange(String diff) {
        return switch (diff) {
            case "facile" -> new int[]{1, 2};
            case "dur" -> new int[]{2, 3};
            default -> new int[]{1, 3};
        };
    }

    /**
     * Construit le parcours. Retourne le nombre de sauts réellement posés (le terrain peut arrêter avant).
     */
    int build(Course c, Location origin, int jumps, String diff, String themeName) {
        Theme theme = THEMES.getOrDefault(themeName, THEMES.get("neon"));
        World w = origin.getWorld();
        Vector dir = origin.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        // on s'aligne sur l'axe le plus proche (des sauts propres, faciles à lire)
        dir = Math.abs(dir.getX()) > Math.abs(dir.getZ()) ? new Vector(Math.signum(dir.getX()), 0, 0) : new Vector(0, 0, Math.signum(dir.getZ()));

        Block base = origin.getBlock().getRelative(BlockFace.DOWN);
        // départ : plateforme 3x3 + plaque
        platform(c, base, theme.special(), theme.blocks().get(0));
        Block startPlate = base.getRelative(BlockFace.UP);
        set(c, startPlate, theme.plate());
        c.start = center(startPlate, dir);
        label(c, startPlate.getLocation().add(0.5, 1.6, 0.5), "<gradient:#7cff6b:#00d4ff><b>DÉPART</b></gradient>\n<gray>" + c.name);

        int[] gaps = gapRange(diff);
        double climb = pl.getConfig().getDouble("generator.climb", 0.45);
        int cpEvery = Math.max(3, pl.getConfig().getInt("generator.checkpoint-every", 10));
        Block cur = base;
        int done = 0;
        c.checkpoints.clear();
        for (int i = 1; i <= jumps; i++) {
            boolean checkpoint = i % cpEvery == 0 && i < jumps;
            boolean last = i == jumps;
            Block next = null;
            Vector usedDir = dir;
            for (int attempt = 0; attempt < 30 && next == null; attempt++) {
                Vector d = dir;
                // virage de temps en temps (jamais demi-tour)
                if (rng.nextDouble() < 0.18 || attempt > 10) d = rng.nextBoolean() ? new Vector(-dir.getZ(), 0, dir.getX()) : new Vector(dir.getZ(), 0, -dir.getX());
                int gap = gaps[0] + rng.nextInt(gaps[1] - gaps[0] + 1);
                int dy = rng.nextDouble() < climb ? 1 : (rng.nextDouble() < 0.25 ? -1 : 0);
                if (dy == 1) gap = Math.min(gap, 2);          // monter + sauter loin : impossible au-delà
                if (diff.equals("dur") && dy == 0 && rng.nextDouble() < 0.3) gap = 3; // sauts de 4 blocs
                if (diff.equals("facile") && dy == 1) gap = 1;
                int side = !diff.equals("facile") && rng.nextDouble() < 0.25 ? (rng.nextBoolean() ? 1 : -1) : 0; // décalé (diagonale)
                Vector lat = new Vector(-d.getZ(), 0, d.getX()).multiply(side);
                if (side != 0 && gap > 2) gap = 2;
                Block cand = w.getBlockAt(cur.getX() + (int) (d.getX() * (gap + 1) + lat.getX()), cur.getY() + dy,
                        cur.getZ() + (int) (d.getZ() * (gap + 1) + lat.getZ()));
                if (cand.getY() < w.getMinHeight() + 2 || cand.getY() > w.getMaxHeight() - 6) continue;
                if (!clear(cur, cand, checkpoint || last)) continue;
                next = cand;
                usedDir = d;
            }
            if (next == null) break; // plus de place : le parcours s'arrête là
            dir = usedDir;
            if (checkpoint) {
                platform(c, next, theme.special(), pick(theme, i));
                Block plate = next.getRelative(BlockFace.UP);
                set(c, plate, theme.plate());
                c.checkpoints.add(center(plate, dir));
                label(c, plate.getLocation().add(0.5, 1.6, 0.5), "<aqua><b>CHECKPOINT " + c.checkpoints.size() + "</b>");
            } else if (last) {
                platform(c, next, theme.finish(), theme.finish());
                Block plate = next.getRelative(BlockFace.UP);
                set(c, plate, Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
                c.finish = center(plate, dir);
                label(c, plate.getLocation().add(0.5, 1.6, 0.5), "<gradient:#ffd36b:#ff7a00><b>ARRIVÉE</b></gradient>");
            } else {
                set(c, next, pick(theme, i));
            }
            cur = next;
            done = i;
        }
        // le terrain a coupé avant la fin : le dernier bloc devient l'arrivée
        if (c.finish == null && cur != base) {
            platform(c, cur, theme.finish(), theme.finish());
            Block plate = cur.getRelative(BlockFace.UP);
            set(c, plate, Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
            c.finish = center(plate, dir);
            label(c, plate.getLocation().add(0.5, 1.6, 0.5), "<gradient:#ffd36b:#ff7a00><b>ARRIVÉE</b></gradient>");
        }
        return done;
    }

    private Material pick(Theme t, int i) {
        return t.blocks().get((i / 3) % t.blocks().size());
    }

    /** Place pour le joueur sur la cible (3 blocs d'air) et rien de solide sur la trajectoire du saut. */
    private boolean clear(Block from, Block to, boolean wide) {
        int r = wide ? 1 : 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!to.getRelative(dx, 0, dz).getType().isAir()) return false;
                for (int h = 1; h <= 3; h++) if (!to.getRelative(dx, h, dz).getType().isAir()) return false;
            }
        }
        int steps = 8;
        for (int s = 1; s < steps; s++) {
            double t = s / (double) steps;
            double x = from.getX() + 0.5 + (to.getX() - from.getX()) * t;
            double z = from.getZ() + 0.5 + (to.getZ() - from.getZ()) * t;
            double y = from.getY() + 1 + Math.max(0, to.getY() - from.getY()) * t;
            for (int h = 0; h <= 2; h++) {
                if (!from.getWorld().getBlockAt((int) Math.floor(x), (int) Math.floor(y) + h, (int) Math.floor(z)).getType().isAir()) return false;
            }
        }
        return true;
    }

    private void platform(Course c, Block center, Material middle, Material ring) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block b = center.getRelative(dx, 0, dz);
                if (dx == 0 && dz == 0) set(c, b, middle);
                else if (b.getType().isAir() || c.placed.containsKey(Course.key(b))) set(c, b, ring);
            }
        }
    }

    /** Pose un bloc en notant l'ancien (pour pouvoir démonter le parcours). */
    void set(Course c, Block b, Material m) {
        c.placed.putIfAbsent(Course.key(b), b.getBlockData().getAsString());
        b.setType(m, false);
    }

    private static Location center(Block plate, Vector dir) {
        Location l = plate.getLocation().add(0.5, 0, 0.5);
        l.setDirection(dir);
        l.setPitch(0);
        return l;
    }

    /** Étiquette flottante (Départ / Checkpoint / Arrivée), recréée à chaque démarrage. */
    void label(Course c, Location at, String mini) {
        c.labels.add(new String[]{Course.loc(at), mini});
        spawnLabel(at, mini);
    }

    TextDisplay spawnLabel(Location at, String mini) {
        TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.text(pl.mm(mini));
        });
        pl.labels.add(d);
        return d;
    }

    static String themes() {
        return String.join(", ", THEMES.keySet().stream().sorted().toList());
    }

    static boolean isTheme(String s) {
        return THEMES.containsKey(s.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unused")
    private static Component none() {
        return Component.empty();
    }

    static List<String> difficulties() {
        return new ArrayList<>(List.of("facile", "moyen", "dur"));
    }
}
