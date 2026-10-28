package fr.smpduel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitTask;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Une arène de duel : un cercle de dalles noires fissurées de magma, un muret de briques du Nether avec des piliers
 * à citrouilles et flammes d'âmes, une rivière de lave autour, deux plots de départ (rouge / bleu) face à face.
 * Dans le style de l'arène du Roi Citrouille, en plus petit.
 */
final class DuelArena {

    final String name;
    final String world;
    final int cx, cy, cz, r;
    Location spawn1, spawn2;
    /** occupée par un duel */
    transient Match match;

    DuelArena(String name, String world, int cx, int cy, int cz, int r) {
        this.name = name;
        this.world = world;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.r = r;
    }

    World w() {
        return Bukkit.getWorld(world);
    }

    boolean ready() {
        return w() != null && spawn1 != null && spawn2 != null;
    }

    /** Spawns par défaut : face à face sur l'axe nord-sud, à 3 blocs du muret. */
    void defaultSpawns() {
        World w = w();
        if (w == null) return;
        spawn1 = new Location(w, cx + 0.5, cy + 1, cz - (r - 3) + 0.5, 0, 0);
        spawn2 = new Location(w, cx + 0.5, cy + 1, cz + (r - 3) + 0.5, 180, 0);
    }

    /** Dans le cercle de combat (au-dessus du sol). */
    boolean inside(Location l) {
        if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        return dx * dx + dz * dz <= (r + 0.6) * (r + 0.6) && l.getY() >= cy - 1.5 && l.getY() <= cy + 30;
    }

    /** Toute la zone protégée (muret, lave, bord). */
    boolean region(Location l) {
        if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        return dx * dx + dz * dz <= (r + 8) * (r + 8) && l.getY() >= cy - 5 && l.getY() <= cy + 32;
    }

    /** Bloc de la structure (sol, muret, piliers...) : jamais cassé ni détruit par une explosion. */
    boolean structure(Block b) {
        if (!b.getWorld().getName().equals(world)) return false;
        double dx = b.getX() - cx, dz = b.getZ() - cz;
        double d2 = dx * dx + dz * dz;
        if (d2 > (r + 8) * (r + 8) || b.getY() < cy - 5 || b.getY() > cy + 32) return false;
        return b.getY() <= cy || d2 > (r + 0.5) * (r + 0.5);
    }

    // ================================================================= sauvegarde

    void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("x", cx);
        s.set("y", cy);
        s.set("z", cz);
        s.set("radius", r);
        if (spawn1 != null) s.set("spawn1", loc(spawn1));
        if (spawn2 != null) s.set("spawn2", loc(spawn2));
    }

    static DuelArena load(String name, ConfigurationSection s) {
        DuelArena a = new DuelArena(name, s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getInt("radius", 14));
        a.spawn1 = loc(a.w(), s.getString("spawn1"));
        a.spawn2 = loc(a.w(), s.getString("spawn2"));
        if (a.spawn1 == null || a.spawn2 == null) a.defaultSpawns();
        return a;
    }

    static String loc(Location l) {
        return l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    static Location loc(World w, String s) {
        if (w == null || s == null) return null;
        String[] p = s.split(";");
        if (p.length < 5) return null;
        try {
            return new Location(w, Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Float.parseFloat(p[3]), Float.parseFloat(p[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ================================================================= après un duel

    /** Vide l'intérieur (blocs posés, lave, eau, toiles...) et les objets au sol. */
    void cleanup() {
        World w = w();
        if (w == null) return;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > (r + 0.5) * (r + 0.5)) continue;
                for (int y = cy + 1; y <= cy + 24; y++) {
                    Block b = w.getBlockAt(cx + x, y, cz + z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
        // le sol abîmé (explosions) : on rebouche
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > (r + 0.5) * (r + 0.5)) continue;
                Block b = w.getBlockAt(cx + x, cy, cz + z);
                if (b.getType().isAir() || b.isLiquid()) b.setBlockData(floor(x, z), false);
            }
        }
        Location c = new Location(w, cx + 0.5, cy + 1, cz + 0.5);
        for (Item it : w.getNearbyEntitiesByType(Item.class, c, r + 2, 20, r + 2)) it.remove();
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(c, r + 2, 20, r + 2)) {
            if (e instanceof org.bukkit.entity.ExperienceOrb || e instanceof org.bukkit.entity.AbstractArrow
                    || e instanceof org.bukkit.entity.EnderCrystal || e instanceof org.bukkit.entity.TNTPrimed) e.remove();
        }
    }

    // ================================================================= plan

    private static long key(int x, int y, int z) {
        return (((long) (x + 1024)) << 42) | (((long) (z + 1024)) << 21) | (long) (y + 1024);
    }

    private static int h(int x, int z, int salt) {
        int v = x * 73856093 ^ z * 19349663 ^ salt * 83492791;
        v ^= v >>> 13;
        v *= 0x5bd1e995;
        v ^= v >>> 15;
        return v & 0x7fffffff;
    }

    private static BlockData bd(Material m) {
        return m.createBlockData();
    }

    /** Dalles du sol : sombres, fissures de magma en étoile, cercle doré au centre. */
    BlockData floor(int x, int z) {
        double d = Math.sqrt(x * x + z * z);
        double ang = Math.atan2(z, x);
        if (d < 1.5) return bd(Material.CHISELED_POLISHED_BLACKSTONE);
        if (d < 2.6) return bd(Material.GILDED_BLACKSTONE);
        // fissures en étoile (8 branches)
        double sector = Math.PI / 4;
        double off = Math.abs(((ang % sector) + sector) % sector - sector / 2);
        if (d > 3 && d < r - 1 && off * d > sector / 2 * d - 0.55 && h(x, z, 3) % 5 != 0) return bd(Material.MAGMA_BLOCK);
        // anneau intérieur
        if (Math.abs(d - r * 0.55) < 0.5) return bd(Material.POLISHED_BLACKSTONE);
        int v = h(x, z, 1) % 100;
        if (v < 55) return bd(Material.POLISHED_BLACKSTONE_BRICKS);
        if (v < 75) return bd(Material.CRACKED_POLISHED_BLACKSTONE_BRICKS);
        if (v < 88) return bd(Material.BLACKSTONE);
        if (v < 96) return bd(Material.BASALT);
        return bd(Material.MAGMA_BLOCK);
    }

    /** Le plan complet (relatif au centre). */
    Map<Long, BlockData> design() {
        Map<Long, BlockData> m = new LinkedHashMap<>();
        int outer = r + 7;
        for (int x = -outer; x <= outer; x++) {
            for (int z = -outer; z <= outer; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > outer + 0.5) continue;
                // on fait de la place au-dessus
                for (int y = 1; y <= 22; y++) m.put(key(x, y, z), bd(Material.AIR));
                // fondations
                for (int y = -4; y <= -1; y++) m.put(key(x, y, z), bd(y == -1 && d <= r ? Material.POLISHED_BLACKSTONE : Material.BLACKSTONE));
                if (d <= r + 0.5) {
                    m.put(key(x, 0, z), floor(x, z));
                } else if (d <= r + 1.5) {
                    // muret
                    m.put(key(x, 0, z), bd(Material.NETHER_BRICKS));
                    m.put(key(x, 1, z), bd(Material.RED_NETHER_BRICKS));
                    m.put(key(x, 2, z), bd(Material.NETHER_BRICKS));
                    Slab s = (Slab) bd(Material.NETHER_BRICK_SLAB);
                    s.setType(Slab.Type.BOTTOM);
                    m.put(key(x, 3, z), s);
                } else if (d <= r + 4.5) {
                    // rivière de lave
                    m.put(key(x, -1, z), bd(Material.MAGMA_BLOCK));
                    m.put(key(x, 0, z), bd(Material.LAVA));
                } else {
                    // bord rocheux qui remonte
                    int top = (int) Math.min(3, Math.max(1, (d - r - 4) + h(x, z, 7) % 2));
                    for (int y = 0; y < top; y++) {
                        int v = h(x, z, 11 + y) % 10;
                        m.put(key(x, y, z), bd(v < 5 ? Material.BLACKSTONE : v < 8 ? Material.BASALT : Material.NETHERRACK));
                    }
                }
            }
        }
        // piliers à citrouilles sur le muret
        for (int k = 0; k < 8; k++) {
            double a = Math.PI * 2 * k / 8 + Math.PI / 8;
            int x = (int) Math.round(Math.cos(a) * (r + 1)), z = (int) Math.round(Math.sin(a) * (r + 1));
            for (int y = 1; y <= 4; y++) m.put(key(x, y, z), bd(y == 1 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
            Directional pump = (Directional) bd(Material.JACK_O_LANTERN);
            pump.setFacing(facingTo(-x, -z));
            m.put(key(x, 5, z), pump);
            m.put(key(x, 6, z), bd(Material.SOUL_SOIL));
            m.put(key(x, 7, z), bd(Material.SOUL_FIRE));
        }
        // lumières dans le sol
        for (int k = 0; k < 6; k++) {
            double a = Math.PI * 2 * k / 6;
            m.put(key((int) Math.round(Math.cos(a) * r * 0.55), 0, (int) Math.round(Math.sin(a) * r * 0.55)), bd(Material.SHROOMLIGHT));
        }
        // plots de départ : rouge au nord, bleu au sud
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean mid = dx == 0 && dz == 0;
                m.put(key(dx, 0, -(r - 3) + dz), bd(mid ? Material.SHROOMLIGHT : Material.NETHER_WART_BLOCK));
                m.put(key(dx, 0, (r - 3) + dz), bd(mid ? Material.SEA_LANTERN : Material.WARPED_WART_BLOCK));
            }
        }
        return m;
    }

    private static BlockFace facingTo(int x, int z) {
        if (Math.abs(x) > Math.abs(z)) return x > 0 ? BlockFace.EAST : BlockFace.WEST;
        return z > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    // ================================================================= construction (étalée sur plusieurs ticks)

    /** Construit l'arène et garde une copie du terrain d'avant (pour /duel arene annuler). */
    BukkitTask build(SmpDuel plugin, File backup, Consumer<Integer> done) {
        World w = w();
        Map<Long, BlockData> plan = design();
        List<long[]> order = new ArrayList<>();
        for (long k : plan.keySet()) order.add(new long[]{k});
        // l'air d'abord, puis du bas vers le haut (la lave posée en dernier sur son lit)
        order.sort((a, b) -> {
            BlockData da = plan.get(a[0]), db = plan.get(b[0]);
            int ra = da.getMaterial().isAir() ? 0 : da.getMaterial() == Material.LAVA || da.getMaterial() == Material.SOUL_FIRE ? 2 : 1;
            int rb = db.getMaterial().isAir() ? 0 : db.getMaterial() == Material.LAVA || db.getMaterial() == Material.SOUL_FIRE ? 2 : 1;
            if (ra != rb) return ra - rb;
            return Integer.compare((int) (a[0] & 0x1FFFFF), (int) (b[0] & 0x1FFFFF));
        });
        List<int[]> pos = new ArrayList<>();
        List<String> old = new ArrayList<>();
        int[] i = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int n = 0;
            while (i[0] < order.size() && n < 4000) {
                long k = order.get(i[0]++)[0];
                int x = (int) ((k >>> 42) & 0x1FFFFF) - 1024, z = (int) ((k >>> 21) & 0x1FFFFF) - 1024, y = (int) (k & 0x1FFFFF) - 1024;
                Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                BlockData want = plan.get(k);
                if (b.getBlockData().matches(want)) continue;
                pos.add(new int[]{b.getX(), b.getY(), b.getZ()});
                old.add(b.getBlockData().getAsString());
                b.setBlockData(want, false);
                n++;
            }
            if (i[0] >= order.size()) {
                task[0].cancel();
                writeBackup(plugin, backup, pos, old);
                done.accept(pos.size());
            }
        }, 1L, 1L);
        return task[0];
    }

    private void writeBackup(SmpDuel plugin, File f, List<int[]> pos, List<String> old) {
        f.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)))) {
            Map<String, Integer> pal = new HashMap<>();
            List<String> palette = new ArrayList<>();
            for (String s : old) pal.computeIfAbsent(s, k -> {
                palette.add(k);
                return palette.size() - 1;
            });
            out.writeUTF(world);
            out.writeInt(palette.size());
            for (String s : palette) out.writeUTF(s);
            out.writeInt(pos.size());
            for (int j = 0; j < pos.size(); j++) {
                out.writeInt(pos.get(j)[0]);
                out.writeInt(pos.get(j)[1]);
                out.writeInt(pos.get(j)[2]);
                out.writeInt(pal.get(old.get(j)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Sauvegarde du terrain impossible : " + e.getMessage());
        }
    }

    /** Remet le terrain comme avant la construction. Renvoie le nombre de blocs remis, -1 si pas de sauvegarde. */
    static int restore(SmpDuel plugin, File f) {
        if (!f.exists()) return -1;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            World w = Bukkit.getWorld(in.readUTF());
            if (w == null) return -1;
            int pn = in.readInt();
            List<BlockData> palette = new ArrayList<>();
            for (int j = 0; j < pn; j++) palette.add(Bukkit.createBlockData(in.readUTF()));
            int n = in.readInt();
            for (int j = 0; j < n; j++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt(), p = in.readInt();
                w.getBlockAt(x, y, z).setBlockData(palette.get(p), false);
            }
            f.delete();
            return n;
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("Restauration impossible : " + e.getMessage());
            return -1;
        }
    }
}
