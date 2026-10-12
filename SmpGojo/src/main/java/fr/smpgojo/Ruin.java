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
    private int now;
    private boolean dirty;

    Ruin(SmpGojo pl) {
        this.pl = pl;
    }

    boolean enabled() {
        return pl.getConfig().getBoolean("destroy-blocks", true);
    }

    private int restoreTicks() {
        return Math.max(5, pl.getConfig().getInt("restore-seconds", 45)) * 20;
    }

    private boolean allowedWorld(World w, Location at) {
        if (pl.getConfig().getStringList("no-destroy-worlds").contains(w.getName())) return false;
        int spawnR = pl.getConfig().getInt("no-destroy-spawn-radius", 0);
        return spawnR <= 0 || w.getSpawnLocation().distanceSquared(at) > (double) spawnR * spawnR;
    }

    private static boolean breakable(Block b) {
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
        if (debris.size() >= pl.getConfig().getInt("max-debris", 350)) return;
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

    void tick() {
        now++;
        tickDebris();
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
    }

    boolean isSaved(Location blockLoc) {
        return saved.containsKey(blockLoc);
    }

    /** Remet tout de suite tout le terrain. */
    int restoreAll() {
        int n = 0;
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
