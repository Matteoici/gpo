package fr.smpgojo;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Un trou géant (Violet creux 200 %) : un cylindre effacé chunk par chunk depuis le centre,
 * gardé en mémoire de façon compacte (12 octets par bloc) puis reconstruit petit à petit.
 * Sauvegardé sur disque : même après un crash, tout est remis au démarrage.
 */
final class MegaHole {

    private static final BlockData AIR = Material.AIR.createBlockData();

    private final SmpGojo pl;
    final World w;
    final double cx, cz, r;
    final int bottom;
    private final List<int[]> chunks = new ArrayList<>();
    private int chunkIdx;
    private long[] keys = new long[1 << 16];
    private int[] pal = new int[1 << 16];
    private int size;
    private final List<BlockData> palette = new ArrayList<>();
    private final Map<BlockData, Integer> palIndex = new HashMap<>();
    private int restoreAt = -1, restoreIdx = -1;
    private final File file;
    boolean done;

    MegaHole(SmpGojo pl, World w, double cx, double cz, double r, int bottom, File file) {
        this.pl = pl;
        this.w = w;
        this.cx = cx;
        this.cz = cz;
        this.r = r;
        this.bottom = Math.max(w.getMinHeight() + 1, bottom);
        this.file = file;
        int c0x = (int) Math.floor((cx - r) / 16), c1x = (int) Math.floor((cx + r) / 16);
        int c0z = (int) Math.floor((cz - r) / 16), c1z = (int) Math.floor((cz + r) / 16);
        for (int x = c0x; x <= c1x; x++) {
            for (int z = c0z; z <= c1z; z++) {
                double nx = Math.max(x * 16, Math.min(cx, x * 16 + 15)), nz = Math.max(z * 16, Math.min(cz, z * 16 + 15));
                if ((nx - cx) * (nx - cx) + (nz - cz) * (nz - cz) <= r * r) chunks.add(new int[]{x, z});
            }
        }
        // du centre vers l'extérieur
        chunks.sort((a, b) -> Double.compare(d2(a), d2(b)));
    }

    private double d2(int[] c) {
        double x = c[0] * 16 + 8 - cx, z = c[1] * 16 + 8 - cz;
        return x * x + z * z;
    }

    int chunkCount() {
        return chunks.size();
    }

    boolean erasing() {
        return chunkIdx < chunks.size();
    }

    boolean contains(Location l) {
        if (l.getWorld() != w || l.getY() < bottom - 1) return false;
        double dx = l.getX() - cx, dz = l.getZ() - cz;
        return dx * dx + dz * dz <= (r + 1) * (r + 1);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    private static int ux(long k) {
        return (int) (k >> 38);
    }

    private static int uz(long k) {
        return (int) ((k << 26) >> 38);
    }

    private static int uy(long k) {
        return (int) (k & 0xFFF) - 2048;
    }

    private void record(int x, int y, int z, BlockData d) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            pal = Arrays.copyOf(pal, size * 2);
        }
        Integer idx = palIndex.get(d);
        if (idx == null) {
            idx = palette.size();
            palette.add(d);
            palIndex.put(d, idx);
        }
        keys[size] = pack(x, y, z);
        pal[size] = idx;
        size++;
    }

    // ================================================================= effacement

    private int col;
    private final java.util.Set<Long> loading = new java.util.HashSet<>();
    private final java.util.Set<Long> tickets = new java.util.HashSet<>();

    private void releaseTickets() {
        for (long k : tickets) w.removePluginChunkTicket((int) (k >> 32), (int) k, pl);
        tickets.clear();
    }

    private boolean ready(int x, int z) {
        if (w.isChunkLoaded(x, z)) return true;
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        if (loading.add(key)) {
            w.getChunkAtAsync(x, z, false).whenComplete((c, err) -> {
                loading.remove(key);
                if (c != null && !done && tickets.add(key)) w.addPluginChunkTicket(x, z, pl);
            });
        }
        return false;
    }

    /**
     * Efface pendant au plus "budgetNanos" ce tick, colonne par colonne, puis s'arrête :
     * le serveur ne lag jamais, un gros trou prend juste plus de temps.
     */
    int eraseTick(long budgetNanos) {
        long start = System.nanoTime();
        int count = 0;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int skipped = 0;
        while (chunkIdx < chunks.size() && System.nanoTime() - start < budgetNanos) {
            int[] c = chunks.get(chunkIdx);
            if (!w.isChunkGenerated(c[0], c[1])) {
                chunkIdx++;
                col = 0;
                continue;
            }
            if (!ready(c[0], c[1])) {
                // pas encore chargé : on le remet un peu plus loin dans la file et on continue
                if (++skipped > 8) break;
                chunks.remove(chunkIdx);
                chunks.add(Math.min(chunks.size(), chunkIdx + 6), c);
                continue;
            }
            Chunk ch = w.getChunkAt(c[0], c[1]);
            while (col < 256 && System.nanoTime() - start < budgetNanos) {
                int lx = col & 15, lz = col >> 4;
                col++;
                int x = c[0] * 16 + lx, z = c[1] * 16 + lz;
                double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
                if (dx * dx + dz * dz > r * r) continue;
                int top = w.getHighestBlockYAt(x, z);
                for (int y = top; y >= bottom; y--) {
                    Block b = ch.getBlock(lx, y, lz);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (!b.isLiquid() && !Ruin.breakable(b)) continue;
                    BlockData d = b.getBlockData();
                    record(x, y, z, d);
                    b.setBlockData(AIR, false);
                    count++;
                    if (y == top && rnd.nextInt(2500) == 0) {
                        pl.ruin.spawnDebris(d, new Location(w, x + 0.5, y + 0.5, z + 0.5),
                                new Vector(dx, 0, dz).normalize().multiply(0.3).setY(1.2 + rnd.nextDouble()), null, 40);
                    }
                }
            }
            if (col >= 256) {
                chunkIdx++;
                col = 0;
                if (rnd.nextInt(6) == 0) {
                    Location at = new Location(w, c[0] * 16 + 8, w.getHighestBlockYAt(c[0] * 16 + 8, c[1] * 16 + 8) + 1, c[1] * 16 + 8);
                    w.spawnParticle(Particle.END_ROD, at, 4, 6, 2, 6, 0.3, null, true);
                }
            }
        }
        if (!erasing()) {
            releaseTickets();
            save();
        }
        return count;
    }

    // ================================================================= reconstruction

    void scheduleRestore(int now, int delayTicks) {
        restoreAt = now + delayTicks;
    }

    /** true quand tout est remis. Même budget de temps que l'effacement. */
    boolean restoreTick(int now, long budgetNanos) {
        if (erasing() || restoreAt < 0 || now < restoreAt) return false;
        if (restoreIdx < 0) restoreIdx = size - 1;
        long start = System.nanoTime();
        int n = 0;
        while (restoreIdx >= 0) {
            if ((++n & 63) == 0 && System.nanoTime() - start > budgetNanos) break;
            long k = keys[restoreIdx];
            if (!ready(ux(k) >> 4, uz(k) >> 4)) break;
            restoreOne(restoreIdx--);
        }
        if (restoreIdx < 0) {
            finish();
            return true;
        }
        return false;
    }

    private void restoreOne(int i) {
        long k = keys[i];
        Block b = w.getBlockAt(ux(k), uy(k), uz(k));
        Material cur = b.getType();
        if (cur.isAir() || b.isLiquid() || cur == Material.FIRE) b.setBlockData(palette.get(pal[i]), false);
    }

    /** Tout remettre tout de suite. */
    void restoreAll() {
        chunkIdx = chunks.size();
        for (int i = size - 1; i >= 0; i--) restoreOne(i);
        finish();
    }

    private void finish() {
        releaseTickets();
        size = 0;
        done = true;
        keys = new long[0];
        pal = new int[0];
        try {
            java.nio.file.Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }

    // ================================================================= sauvegarde anti-crash

    private void save() {
        long[] k = Arrays.copyOf(keys, size);
        int[] p = Arrays.copyOf(pal, size);
        List<String> pals = new ArrayList<>();
        for (BlockData d : palette) pals.add(d.getAsString());
        String world = w.getName();
        Runnable write = () -> {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
                out.writeUTF(world);
                out.writeInt(pals.size());
                for (String s : pals) out.writeUTF(s);
                out.writeInt(k.length);
                for (int i = 0; i < k.length; i++) {
                    out.writeLong(k[i]);
                    out.writeInt(p[i]);
                }
            } catch (IOException e) {
                pl.getLogger().warning("Sauvegarde du trou impossible : " + e.getMessage());
            }
        };
        file.getParentFile().mkdirs();
        if (pl.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(pl, write);
        else write.run();
    }

    /** Au démarrage : remet les trous qui n'ont pas été reconstruits (crash). */
    static int recover(SmpGojo pl) {
        File[] files = pl.getDataFolder().listFiles((d, n) -> n.startsWith("mega-") && n.endsWith(".bin"));
        if (files == null) return 0;
        int total = 0;
        for (File f : files) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                World w = Bukkit.getWorld(in.readUTF());
                int np = in.readInt();
                List<BlockData> pals = new ArrayList<>(np);
                for (int i = 0; i < np; i++) {
                    String s = in.readUTF();
                    BlockData d;
                    try {
                        d = Bukkit.createBlockData(s);
                    } catch (IllegalArgumentException e) {
                        d = Material.STONE.createBlockData();
                    }
                    pals.add(d);
                }
                int n = in.readInt();
                long[] k = new long[n];
                int[] p = new int[n];
                for (int i = 0; i < n; i++) {
                    k[i] = in.readLong();
                    p[i] = in.readInt();
                }
                if (w != null) {
                    for (int i = n - 1; i >= 0; i--) {
                        Block b = w.getBlockAt(ux(k[i]), uy(k[i]), uz(k[i]));
                        if (b.getType().isAir() || b.isLiquid()) {
                            b.setBlockData(pals.get(p[i]), false);
                            total++;
                        }
                    }
                }
            } catch (IOException e) {
                pl.getLogger().warning("Lecture de " + f.getName() + " impossible : " + e.getMessage());
            }
            f.delete();
        }
        return total;
    }
}
