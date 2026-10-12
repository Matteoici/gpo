package fr.smpgojo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Destruction du terrain : les blocs sont enlevés (sans rien lâcher), des débris volent,
 * puis tout se reconstruit tout seul après "restore-seconds".
 * Jamais : bedrock, blocs incassables, coffres / fours / panneaux (tout ce qui a un contenu), liquides.
 * En cas de crash, la liste est gardée dans pending-restore.txt et remise au prochain démarrage.
 */
final class Ruin {

    private record Saved(BlockData original, Material ours) {
    }

    private record Batch(int due, Deque<Location> blocks) {
    }

    private static final class Debris {
        BlockDisplay d;
        Location pos;
        Vector vel;
        Vector3f axis;
        float angle, spin, scale;
        int life, maxLife;
        Location attract;
        boolean resting;
    }

    private final SmpGojo pl;
    private final Map<Location, Saved> saved = new HashMap<>();
    private final List<Batch> batches = new ArrayList<>();
    private final List<Debris> debris = new ArrayList<>();
    private final List<MegaHole> megas = new ArrayList<>();
    private int megaSeq;
    private int now;
    private boolean dirty;

    Ruin(SmpGojo pl) {
        this.pl = pl;
    }

    boolean enabled() {
        return pl.getConfig().getBoolean("destroy-blocks", true);
    }

    private int restoreTicks() {
        return Math.max(5, pl.getConfig().getInt("restore-seconds", 60)) * 20;
    }

    private boolean allowedWorld(World w, Location at) {
        if (pl.getConfig().getStringList("no-destroy-worlds").contains(w.getName())) return false;
        int spawnR = pl.getConfig().getInt("no-destroy-spawn-radius", 0);
        return spawnR <= 0 || w.getSpawnLocation().distanceSquared(at) > (double) spawnR * spawnR;
    }

    static boolean breakable(Block b) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || m.getHardness() < 0) return false;
        switch (m) {
            case BEDROCK, BARRIER, END_PORTAL_FRAME, END_PORTAL, NETHER_PORTAL, END_GATEWAY, REINFORCED_DEEPSLATE,
                 COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK, STRUCTURE_BLOCK, JIGSAW, LIGHT, SPAWNER,
                 FIRE, SOUL_FIRE -> {
                return false;
            }
            default -> {
            }
        }
        return !(b.getState(false) instanceof TileState);
    }

    // ================================================================= destruction

    /**
     * Creuse une boule (ou une coquille si inner > 0).
     *
     * @param debrisChance part des blocs qui deviennent des débris volants
     * @param push         poussée ajoutée aux débris (null = vers l'extérieur)
     * @param attract      si non nul, les débris tournent autour de ce point (qui peut bouger)
     */
    int carve(Location c, double r, double inner, double debrisChance, Vector push, Location attract, int debrisLife) {
        if (!enabled() || !allowedWorld(c.getWorld(), c)) return 0;
        World w = c.getWorld();
        int ri = (int) Math.ceil(r);
        double r2 = r * r, in2 = inner * inner;
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        int minY = w.getMinHeight(), maxY = w.getMaxHeight() - 1;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Deque<Location> list = new ArrayDeque<>();
        int max = pl.getConfig().getInt("max-blocks-per-hit", 6000);
        for (int x = -ri; x <= ri && list.size() < max; x++) {
            for (int y = -ri; y <= ri; y++) {
                int by = cy + y;
                if (by < minY || by > maxY) continue;
                for (int z = -ri; z <= ri; z++) {
                    double d2 = (x + 0.5 - (c.getX() - cx)) * (x + 0.5 - (c.getX() - cx))
                            + (y + 0.5 - (c.getY() - cy)) * (y + 0.5 - (c.getY() - cy))
                            + (z + 0.5 - (c.getZ() - cz)) * (z + 0.5 - (c.getZ() - cz));
                    if (d2 > r2 || d2 < in2) continue;
                    Block b = w.getBlockAt(cx + x, by, cz + z);
                    if (!breakable(b)) continue;
                    Location key = b.getLocation();
                    BlockData data = b.getBlockData();
                    saved.putIfAbsent(key, new Saved(data, Material.AIR));
                    b.setType(Material.AIR, false);
                    list.add(key);
                    if (rnd.nextDouble() < debrisChance) {
                        Location at = key.clone().add(0.5, 0.5, 0.5);
                        Vector v;
                        if (attract != null) {
                            v = Draw.randomDir().multiply(0.3).setY(0.4);
                        } else {
                            Vector out = at.toVector().subtract(c.toVector());
                            if (out.lengthSquared() < 0.01) out = new Vector(0, 1, 0);
                            v = out.normalize().multiply(0.35 + rnd.nextDouble() * 0.6).add(new Vector(0, 0.35 + rnd.nextDouble() * 0.5, 0));
                            if (push != null) v.add(push.clone().multiply(0.5 + rnd.nextDouble()));
                        }
                        spawnDebris(data, at, v, attract, debrisLife);
                    }
                }
            }
        }
        if (list.isEmpty()) return 0;
        Saved first = saved.get(list.peekFirst());
        if (first != null && rnd.nextDouble() < 0.5) {
            w.spawnParticle(Particle.BLOCK, c, Math.min(60, list.size()), r * 0.5, r * 0.5, r * 0.5, 0, first.original(), true);
        }
        schedule(list);
        return list.size();
    }

    int carve(Location c, double r, double debrisChance) {
        return carve(c, r, 0, debrisChance, null, null, 60);
    }

    /**
     * Efface une tranche horizontale d'un cylindre (tout, liquides compris : "réduit à néant").
     * Les coffres et blocs à contenu restent (rien n'est perdu).
     */
    int carveLayer(World w, double cx, double cz, int y, double r, double debrisChance) {
        if (!enabled() || y < w.getMinHeight() || y >= w.getMaxHeight()) return 0;
        if (!allowedWorld(w, new Location(w, cx, y, cz))) return 0;
        int ri = (int) Math.ceil(r);
        int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Deque<Location> list = new ArrayDeque<>();
        double r2 = r * r;
        for (int x = -ri; x <= ri; x++) {
            for (int z = -ri; z <= ri; z++) {
                double dx = bx + x + 0.5 - cx, dz = bz + z + 0.5 - cz;
                if (dx * dx + dz * dz > r2) continue;
                Block b = w.getBlockAt(bx + x, y, bz + z);
                Material m = b.getType();
                if (m.isAir()) continue;
                if (!b.isLiquid() && !breakable(b)) continue;
                Location key = b.getLocation();
                BlockData data = b.getBlockData();
                saved.putIfAbsent(key, new Saved(data, Material.AIR));
                b.setType(Material.AIR, false);
                list.add(key);
                if (!b.isLiquid() && rnd.nextDouble() < debrisChance) {
                    Vector out = new Vector(dx, 0, dz);
                    if (out.lengthSquared() < 0.01) out = new Vector(1, 0, 0);
                    spawnDebris(data, key.clone().add(0.5, 0.5, 0.5), out.normalize().multiply(0.6).setY(0.8 + rnd.nextDouble()), null, 50);
                }
            }
        }
        schedule(list);
        return list.size();
    }

    // ================================================================= cratères réalistes

    /** Bruit doux selon la direction, pour des bords irréguliers. */
    private static double noise(double a, double b, double seed) {
        return Math.sin(a * 3 + seed) * 0.45 + Math.sin(a * 7 + b * 2 + seed * 1.7) * 0.3 + Math.sin(b * 5 + seed * 2.3) * 0.25;
    }

    /**
     * Un vrai cratère : cuvette aux bords irréguliers, débris projetés, rebord de gravats,
     * fissures qui partent du centre, fond en fusion si "molten", vitres et feuilles soufflées autour.
     * Les grands cratères sont creusés tranche par tranche pour ne pas faire laguer.
     */
    void crater(Location c, double r, double debrisChance, int cracks, boolean molten) {
        if (!enabled() || !allowedWorld(c.getWorld(), c)) return;
        World w = c.getWorld();
        double seed = ThreadLocalRandom.current().nextDouble(100);
        int ri = (int) Math.ceil(r * 1.3);
        int cy = c.getBlockY();
        int top = Math.min(w.getMaxHeight() - 1, cy + ri), bottom = Math.max(w.getMinHeight(), cy - ri);
        int perTick = Math.max(1, (int) (40000 / Math.max(1, (2 * ri + 1) * (2 * ri + 1))));
        int layers = top - bottom + 1;
        Runnable after = () -> {
            if (molten) scorch(c.clone().add(0, -r * 0.5, 0), r * 0.9, 0.9);
            rim(c, r, seed);
            for (int k = 0; k < cracks; k++) crack(c, ThreadLocalRandom.current().nextDouble(Math.PI * 2), r * 0.8, r * (1.6 + ThreadLocalRandom.current().nextDouble()), molten);
            shatter(c, Math.min(30, r * 2.6 + 6));
        };
        pl.repeat(1, (layers + perTick - 1) / perTick, i -> {
            for (int y = top - i * perTick; y > top - (i + 1) * perTick && y >= bottom; y--) craterLayer(w, c, r, y, seed, debrisChance);
        }, after);
    }

    private void craterLayer(World w, Location c, double r, int y, double seed, double debrisChance) {
        int ri = (int) Math.ceil(r * 1.3);
        double dy = y + 0.5 - c.getY();
        double vy = dy < 0 ? dy / 0.7 : dy * 1.1;
        Deque<Location> list = new ArrayDeque<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int x = -ri; x <= ri; x++) {
            for (int z = -ri; z <= ri; z++) {
                double dx = c.getBlockX() + x + 0.5 - c.getX(), dz = c.getBlockZ() + z + 0.5 - c.getZ();
                double d = Math.sqrt(dx * dx + vy * vy + dz * dz);
                if (d > r * 1.3) continue;
                double rr = r * (1 + 0.22 * noise(Math.atan2(dz, dx), Math.atan2(vy, Math.hypot(dx, dz)), seed));
                if (d > rr) continue;
                Block b = w.getBlockAt(c.getBlockX() + x, y, c.getBlockZ() + z);
                if (!breakable(b)) continue;
                Location key = b.getLocation();
                BlockData data = b.getBlockData();
                saved.putIfAbsent(key, new Saved(data, Material.AIR));
                b.setType(Material.AIR, false);
                list.add(key);
                if (rnd.nextDouble() < debrisChance) {
                    Vector out = new Vector(dx, Math.max(0.3, vy), dz);
                    if (out.lengthSquared() < 0.01) out = new Vector(0, 1, 0);
                    spawnDebris(data, key.clone().add(0.5, 0.5, 0.5),
                            out.normalize().multiply(0.5 + rnd.nextDouble() * 0.7).add(new Vector(0, 0.5 + rnd.nextDouble() * 0.6, 0)), null, 70);
                }
            }
        }
        schedule(list);
    }

    /** Rebord de gravats autour d'un cratère. */
    private void rim(Location c, double r, double seed) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Material[] rubble = {Material.COBBLESTONE, Material.GRAVEL, Material.COARSE_DIRT, Material.ANDESITE, Material.COBBLESTONE,
                Material.TUFF, Material.COBBLED_DEEPSLATE};
        Deque<Location> list = new ArrayDeque<>();
        int n = (int) (r * 14);
        for (int k = 0; k < n; k++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double rr = r * (1 + 0.22 * noise(a, 0, seed)) + 0.5 + rnd.nextDouble() * 2.5;
            int x = (int) Math.floor(c.getX() + Math.cos(a) * rr), z = (int) Math.floor(c.getZ() + Math.sin(a) * rr);
            Block g = surface(w, x, c.getBlockY(), z);
            if (g == null) continue;
            int h = rnd.nextDouble() < 0.35 ? 2 : 1;
            for (int yy = 1; yy <= h; yy++) {
                Block b = g.getRelative(0, yy, 0);
                if (!b.getType().isAir() || saved.containsKey(b.getLocation())) break;
                Material m = rubble[rnd.nextInt(rubble.length)];
                saved.put(b.getLocation(), new Saved(b.getBlockData(), m));
                b.setType(m, false);
                list.add(b.getLocation());
            }
        }
        schedule(list);
    }

    /** Une fissure qui part du cratère en zigzag (avec du magma au fond si "molten"). */
    void crack(Location c, double angle, double from, double length, boolean molten) {
        if (!enabled()) return;
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double a = angle, x = c.getX() + Math.cos(a) * from, z = c.getZ() + Math.sin(a) * from;
        Deque<Location> placed = new ArrayDeque<>();
        for (double t = 0; t < length; t += 0.8) {
            a += rnd.nextDouble(-0.35, 0.35);
            x += Math.cos(a) * 0.8;
            z += Math.sin(a) * 0.8;
            Block g = surface(w, (int) Math.floor(x), c.getBlockY(), (int) Math.floor(z));
            if (g == null) continue;
            double depth = Math.max(1, 3.5 * (1 - t / length));
            double width = t / length < 0.5 ? 1.2 : 0.7;
            carve(g.getLocation().add(0.5, 0.5 - depth * 0.4, 0.5), width, 0, 0.06, new Vector(0, 0.4, 0), null, 30);
            if (molten && rnd.nextDouble() < 0.6) {
                Block bot = g.getRelative(0, -(int) Math.ceil(depth), 0);
                if (breakable(bot) && !saved.containsKey(bot.getLocation())) {
                    saved.put(bot.getLocation(), new Saved(bot.getBlockData(), Material.MAGMA_BLOCK));
                    bot.setType(Material.MAGMA_BLOCK, false);
                    placed.add(bot.getLocation());
                }
            }
        }
        schedule(placed);
    }

    private static boolean fragile(Material m) {
        String n = m.name();
        return n.endsWith("GLASS") || n.endsWith("GLASS_PANE") || n.endsWith("LEAVES") || n.equals("GLASS_PANE")
                || n.endsWith("_CARPET") || n.equals("SHORT_GRASS") || n.equals("TALL_GRASS") || n.equals("FERN") || n.equals("LARGE_FERN");
    }

    /** Le souffle : vitres, feuillages et herbes volent en éclats dans un grand rayon. */
    void shatter(Location c, double r) {
        if (!enabled() || !allowedWorld(c.getWorld(), c)) return;
        World w = c.getWorld();
        int ri = (int) Math.ceil(r);
        int cy = c.getBlockY();
        int minY = Math.max(w.getMinHeight(), cy - ri / 2), maxY = Math.min(w.getMaxHeight() - 1, cy + ri);
        int[] y = {maxY};
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        pl.repeat(1, Math.max(1, (maxY - minY + 1 + 5) / 6), i -> {
            Deque<Location> list = new ArrayDeque<>();
            for (int k = 0; k < 6 && y[0] >= minY; k++, y[0]--) {
                for (int x = -ri; x <= ri; x++) {
                    for (int z = -ri; z <= ri; z++) {
                        if (x * x + z * z + (y[0] - cy) * (y[0] - cy) > r * r) continue;
                        Block b = w.getBlockAt(c.getBlockX() + x, y[0], c.getBlockZ() + z);
                        Material m = b.getType();
                        if (!fragile(m) || !breakable(b)) continue;
                        BlockData data = b.getBlockData();
                        saved.putIfAbsent(b.getLocation(), new Saved(data, Material.AIR));
                        b.setType(Material.AIR, false);
                        list.add(b.getLocation());
                        if (rnd.nextInt(6) == 0) w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0, data, true);
                        if (rnd.nextInt(40) == 0) {
                            Vector out = b.getLocation().toVector().subtract(c.toVector());
                            if (out.lengthSquared() > 0.01) spawnDebris(data, b.getLocation().add(0.5, 0.5, 0.5),
                                    out.normalize().multiply(0.6).setY(0.3), null, 30);
                        }
                    }
                }
            }
            if (!list.isEmpty()) {
                SmpGojo.sound(c, "block.glass.break", 3f, 0.5f + rnd.nextFloat() * 0.5f);
                schedule(list);
            }
        }, null);
    }

    /** Colonne de fumée et de poussière qui reste après un gros impact. */
    void smoke(Location c, double r, int ticks) {
        pl.repeat(3, Math.max(1, ticks / 3), i -> {
            c.getWorld().spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, c, (int) (4 + r), r * 0.4, 0.5, r * 0.4, 0.04, null, true);
            c.getWorld().spawnParticle(Particle.LARGE_SMOKE, c, (int) (6 + r * 2), r * 0.6, 0.6, r * 0.6, 0.03, null, true);
            c.getWorld().spawnParticle(Particle.WHITE_ASH, c, 20, r, 2, r, 0, null, true);
        }, null);
    }

    /** Une entaille fine le long d'un segment (coupures de Sukuna). */
    void gash(Location c, Vector u, double half, double width) {
        if (!enabled()) return;
        for (double t = -half; t <= half; t += Math.max(0.5, width)) {
            carve(c.clone().add(u.clone().multiply(t)), width, 0, 0.12, null, null, 40);
        }
    }

    /** Sol brûlé : la surface devient noire / magma un moment. */
    void scorch(Location c, double r, double chance) {
        if (!enabled() || !allowedWorld(c.getWorld(), c)) return;
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Material[] burnt = {Material.BLACKSTONE, Material.BLACKSTONE, Material.BASALT, Material.MAGMA_BLOCK, Material.COAL_BLOCK,
                Material.POLISHED_BLACKSTONE, Material.NETHERRACK};
        Deque<Location> list = new ArrayDeque<>();
        int ri = (int) r;
        for (int x = -ri; x <= ri; x++) {
            for (int z = -ri; z <= ri; z++) {
                if (x * x + z * z > r * r || rnd.nextDouble() > chance) continue;
                Block b = surface(w, c.getBlockX() + x, c.getBlockY(), c.getBlockZ() + z);
                if (b == null || !breakable(b) || saved.containsKey(b.getLocation())) continue;
                Material m = burnt[rnd.nextInt(burnt.length)];
                saved.put(b.getLocation(), new Saved(b.getBlockData(), m));
                b.setType(m, false);
                list.add(b.getLocation());
            }
        }
        schedule(list);
    }

    /** Le bloc solide le plus haut près de cette hauteur (la surface du sol). */
    static Block surface(World w, int x, int y, int z) {
        for (int dy = 4; dy >= -6; dy--) {
            Block b = w.getBlockAt(x, y + dy, z);
            if (b.getType().isSolid() && !b.getRelative(0, 1, 0).getType().isSolid()) return b;
        }
        return null;
    }

    // ================================================================= débris (affichage seulement)

    void spawnDebris(BlockData data, Location at, Vector vel, Location attract, int life) {
        if (debris.size() >= pl.getConfig().getInt("max-debris", 250)) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Debris d = new Debris();
        d.pos = at.clone();
        d.vel = vel.clone();
        d.axis = new Vector3f((float) rnd.nextDouble(-1, 1), (float) rnd.nextDouble(-1, 1), (float) rnd.nextDouble(-1, 1));
        if (d.axis.lengthSquared() < 0.01f) d.axis.set(0, 1, 0);
        d.axis.normalize();
        d.spin = (float) rnd.nextDouble(0.15, 0.6);
        d.scale = (float) rnd.nextDouble(0.3, 0.75);
        d.life = d.maxLife = life + rnd.nextInt(15);
        d.attract = attract;
        float s = d.scale;
        d.d = at.getWorld().spawn(at, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "debris");
            e.setBlock(data);
            e.setTeleportDuration(1);
            e.setInterpolationDuration(1);
            e.setTransformation(new Transformation(new Vector3f(-s / 2, -s / 2, -s / 2), new Quaternionf(), new Vector3f(s, s, s),
                    new Quaternionf()));
        });
        debris.add(d);
    }

    /** Fait voler une copie des blocs du sol sur un cercle (onde de choc). */
    void pop(Location c, double r, int points, double up) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < points; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double rr = r * (0.85 + rnd.nextDouble() * 0.3);
            int x = (int) Math.floor(c.getX() + Math.cos(a) * rr), z = (int) Math.floor(c.getZ() + Math.sin(a) * rr);
            Block b = surface(w, x, c.getBlockY(), z);
            if (b == null) continue;
            Location at = b.getLocation().add(0.5, 1.1, 0.5);
            Vector out = new Vector(Math.cos(a), 0, Math.sin(a)).multiply(0.12 + rnd.nextDouble() * 0.15);
            spawnDebris(b.getBlockData(), at, out.setY(up * (0.7 + rnd.nextDouble() * 0.6)), null, 22);
            w.spawnParticle(Particle.BLOCK, at, 6, 0.3, 0.2, 0.3, 0, b.getBlockData(), true);
        }
    }

    /** Onde de choc au sol qui s'agrandit. */
    void shockwave(Location c, double maxR, int ticks, double up) {
        pl.repeat(1, ticks, i -> {
            double r = maxR * (i + 1) / ticks;
            pop(c, r, (int) Math.min(40, 6 + r * 2.5), up);
            Draw.ring(c.clone().add(0, 0.3, 0), r, Particle.CLOUD, (int) Math.min(80, 10 + r * 5));
            if (i % 3 == 0) Draw.ring(c.clone().add(0, 0.2, 0), r, Particle.GUST, (int) Math.min(20, 4 + r));
        }, null);
    }

    /** Libère les débris qui tournaient autour d'un point et les projette. */
    void release(Location attract, double strength) {
        for (Debris d : debris) {
            if (d.attract != attract) continue;
            d.attract = null;
            Vector out = d.pos.toVector().subtract(attract.toVector());
            if (out.lengthSquared() < 0.01) out = Draw.randomDir();
            d.vel = out.normalize().multiply(strength * (0.6 + Math.random() * 0.8)).add(new Vector(0, 0.3, 0));
            d.life = Math.max(d.life, 30);
        }
    }

    // ================================================================= tick

    /** Commence un trou géant (cylindre). Il s'efface chunk par chunk, puis se reconstruit après "restoreSeconds". */
    MegaHole mega(World w, double cx, double cz, double r, int bottom, int restoreSeconds) {
        if (!enabled() || !allowedWorld(w, new Location(w, cx, bottom, cz))) return null;
        MegaHole h = new MegaHole(pl, w, cx, cz, r, bottom, new java.io.File(pl.getDataFolder(), "mega-" + System.currentTimeMillis() + "-" + (megaSeq++) + ".bin"));
        h.scheduleRestore(now, Math.max(5, restoreSeconds) * 20);
        megas.add(h);
        return h;
    }

    void tick() {
        now++;
        tickDebris();
        long budget = (long) (pl.getConfig().getDouble("nuke.max-ms-per-tick", 6) * 1_000_000L);
        for (java.util.Iterator<MegaHole> it = megas.iterator(); it.hasNext(); ) {
            MegaHole h = it.next();
            if (h.erasing()) h.eraseTick(budget);
            else h.restoreTick(now, budget);
            if (h.done) it.remove();
            break; // un seul trou travaille par tick
        }
        int budget = pl.getConfig().getInt("restore-blocks-per-tick", 500);
        for (Iterator<Batch> it = batches.iterator(); it.hasNext() && budget > 0; ) {
            Batch b = it.next();
            if (now < b.due()) continue;
            List<Location> retry = new ArrayList<>();
            while (budget > 0 && !b.blocks().isEmpty()) {
                Location l = b.blocks().pollFirst();
                int r = restoreOne(l);
                if (r < 0) retry.add(l);
                budget--;
            }
            if (!retry.isEmpty()) {
                Deque<Location> again = new ArrayDeque<>(retry);
                batches.add(new Batch(now + 40, again));
                break; // on a modifié la liste
            }
            if (b.blocks().isEmpty()) it.remove();
        }
        if (dirty && now % 100 == 0) persist();
    }

    /** 1 = remis, 0 = ignoré, -1 = un joueur est dedans (on réessaie plus tard). */
    private int restoreOne(Location l) {
        Saved s = saved.get(l);
        if (s == null) return 0;
        Block b = l.getBlock();
        Material cur = b.getType();
        if (!(cur.isAir() || b.isLiquid() || cur == s.ours() || cur == Material.FIRE)) {
            saved.remove(l);
            dirty = true;
            return 0;
        }
        for (Player p : l.getWorld().getPlayers()) {
            Location pp = p.getLocation();
            if (Math.abs(l.getX() + 0.5 - pp.getX()) < 0.85 && Math.abs(l.getZ() + 0.5 - pp.getZ()) < 0.85
                    && l.getY() + 1 > pp.getY() && l.getY() < pp.getY() + 1.9) return -1;
        }
        saved.remove(l);
        b.setBlockData(s.original(), false);
        dirty = true;
        if (ThreadLocalRandom.current().nextInt(12) == 0) {
            l.getWorld().spawnParticle(Particle.WAX_OFF, l.clone().add(0.5, 0.5, 0.5), 2, 0.3, 0.3, 0.3, 0, null, false);
        }
        return 1;
    }

    private void schedule(Deque<Location> list) {
        if (list.isEmpty()) return;
        List<Location> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingDouble(Location::getY));
        batches.add(new Batch(now + restoreTicks(), new ArrayDeque<>(sorted)));
        dirty = true;
    }

    private void tickDebris() {
        for (Iterator<Debris> it = debris.iterator(); it.hasNext(); ) {
            Debris d = it.next();
            if (--d.life <= 0 || d.d == null || !d.d.isValid()) {
                if (d.d != null) d.d.remove();
                it.remove();
                continue;
            }
            if (d.attract != null && d.attract.getWorld() == d.pos.getWorld()) {
                Vector to = d.attract.toVector().subtract(d.pos.toVector());
                double dist = to.length();
                Vector tangent = to.clone().crossProduct(new Vector(0, 1, 0));
                if (tangent.lengthSquared() > 0.001) tangent.normalize().multiply(0.25);
                d.vel.add(to.multiply(0.09)).add(tangent).multiply(dist < 1.2 ? 0.6 : 0.82);
            } else if (!d.resting) {
                d.vel.setY(d.vel.getY() - 0.045);
                d.vel.multiply(0.985);
            }
            if (!d.resting) {
                Location next = d.pos.clone().add(d.vel);
                if (d.attract == null && d.vel.getY() < 0 && next.getBlock().getType().isSolid()) {
                    d.resting = true;
                    d.vel.zero();
                    d.pos.setY(Math.floor(next.getY()) + 1 + d.scale * 0.3);
                } else {
                    d.pos = next;
                }
                d.angle += d.spin;
            }
            float s = d.scale * (d.life < 10 ? d.life / 10f : 1f);
            d.d.setInterpolationDelay(0);
            d.d.setTransformation(new Transformation(new Vector3f(-s / 2, -s / 2, -s / 2),
                    new Quaternionf().rotateAxis(d.angle, d.axis), new Vector3f(s, s, s), new Quaternionf()));
            d.d.teleport(d.pos);
        }
    }

    // ================================================================= sécurité

    /** Un cadre / tableau dont le bloc a été effacé ne tombe pas. */
    void onHanging(HangingBreakEvent e) {
        if (e.getCause() != HangingBreakEvent.RemoveCause.PHYSICS) return;
        Hanging h = e.getEntity();
        Block behind = h.getLocation().getBlock().getRelative(h.getAttachedFace());
        if (saved.containsKey(behind.getLocation()) || saved.containsKey(h.getLocation().getBlock().getLocation())) e.setCancelled(true);
        for (MegaHole m : megas) if (m.contains(h.getLocation())) e.setCancelled(true);
    }

    boolean isSaved(Location blockLoc) {
        return saved.containsKey(blockLoc);
    }

    /** Remet tout de suite tout le terrain. */
    int restoreAll() {
        int n = 0;
        for (MegaHole m : megas) m.restoreAll();
        megas.clear();
        List<Location> keys = new ArrayList<>(saved.keySet());
        keys.sort(Comparator.comparingDouble(Location::getY));
        for (Location l : keys) {
            Saved s = saved.get(l);
            if (s == null) continue;
            Block b = l.getBlock();
            Material cur = b.getType();
            if (cur.isAir() || b.isLiquid() || cur == s.ours() || cur == Material.FIRE) {
                b.setBlockData(s.original(), false);
                n++;
            }
        }
        saved.clear();
        batches.clear();
        for (Debris d : debris) if (d.d != null) d.d.remove();
        debris.clear();
        persist();
        return n;
    }

    // ================================================================= sauvegarde anti-crash

    private File file() {
        return new File(pl.getDataFolder(), "pending-restore.txt");
    }

    private void persist() {
        dirty = false;
        List<String> lines = new ArrayList<>(saved.size());
        for (var e : saved.entrySet()) {
            Location l = e.getKey();
            lines.add(l.getWorld().getName() + "\t" + l.getBlockX() + "\t" + l.getBlockY() + "\t" + l.getBlockZ() + "\t"
                    + e.getValue().ours().name() + "\t" + e.getValue().original().getAsString());
        }
        File f = file();
        Runnable write = () -> {
            try {
                if (lines.isEmpty()) {
                    Files.deleteIfExists(f.toPath());
                } else {
                    f.getParentFile().mkdirs();
                    Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
                }
            } catch (IOException ex) {
                pl.getLogger().warning("pending-restore.txt : " + ex.getMessage());
            }
        };
        if (pl.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(pl, write);
        else write.run();
    }

    /** Au démarrage : remet ce qui n'a pas pu l'être (crash). */
    void recover() {
        int mega = MegaHole.recover(pl);
        if (mega > 0) pl.getLogger().info(mega + " blocs d'un trou géant remis en place après un arrêt brutal.");
        File f = file();
        if (!f.exists()) return;
        int n = 0;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 6);
                if (p.length < 6) continue;
                World w = Bukkit.getWorld(p[0]);
                if (w == null) continue;
                Block b = w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                Material ours = Material.matchMaterial(p[4]);
                Material cur = b.getType();
                if (cur.isAir() || b.isLiquid() || cur == ours) {
                    try {
                        b.setBlockData(Bukkit.createBlockData(p[5]), false);
                        n++;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            Files.deleteIfExists(f.toPath());
        } catch (IOException | NumberFormatException ex) {
            pl.getLogger().warning("Reconstruction impossible : " + ex.getMessage());
        }
        if (n > 0) pl.getLogger().info(n + " blocs remis en place après un arrêt brutal.");
    }
}
