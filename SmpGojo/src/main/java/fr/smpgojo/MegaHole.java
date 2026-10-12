package fr.smpgojo;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Un tunnel géant (Violet creux 200 %) : tout ce qui est dans une "capsule"
 * (une boule de rayon r qui avance sur une distance donnée) est effacé, liquides compris.
 * Travail découpé avec un budget de temps par tick (pas de lag), gardé en mémoire de façon compacte
 * (12 octets par bloc), sauvegardé sur disque et reconstruit tout seul plus tard.
 */
final class MegaHole {

    private static final BlockData AIR = Material.AIR.createBlockData();

    private final SmpGojo pl;
    final World w;
    private final double ax, ay, az, dx, dy, dz, len, r;
    private final int minY, maxY;
    private final List<int[]> chunks = new ArrayList<>();
    private final Map<int[], Double> along;
    private int chunkIdx, col;
    private long[] keys = new long[1 << 16];
    private int[] pal = new int[1 << 16];
    private int size;
    private final List<BlockData> palette = new ArrayList<>();
    private final Map<BlockData, Integer> palIndex = new HashMap<>();
    private int restoreAt = -1, restoreIdx = -1;
    private final File file;
    private final Set<Long> loading = new HashSet<>();
    private final Set<Long> tickets = new HashSet<>();
    boolean done;
    /** false = on ne creuse que sous la surface (invisible) ; true = on enlève tout. */
    private boolean full;
    private static final int SHELL = 2;
    /** la phase cachée ne monte jamais au-dessus de ce niveau (le sol au point d'impact) */
    int hiddenCeil = Integer.MAX_VALUE;

    /** Déclenche l'effacement de la surface (ce qui se voit). */
    void trigger() {
        if (full) return;
        full = true;
        chunkIdx = 0;
        col = 0;
    }

    MegaHole(SmpGojo pl, Location from, Vector dir, double length, double r, File file) {
        this.pl = pl;
        this.w = from.getWorld();
        Vector d = dir.clone().normalize();
        this.ax = from.getX();
        this.ay = from.getY();
        this.az = from.getZ();
        this.dx = d.getX();
        this.dy = d.getY();
        this.dz = d.getZ();
        this.len = length;
        this.r = r;
        this.file = file;
        double bx = ax + dx * len, by = ay + dy * len, bz = az + dz * len;
        this.minY = (int) Math.max(w.getMinHeight() + 1, Math.floor(Math.min(ay, by) - r));
        this.maxY = (int) Math.min(w.getMaxHeight() - 1, Math.ceil(Math.max(ay, by) + r));
        int c0x = (int) Math.floor((Math.min(ax, bx) - r) / 16), c1x = (int) Math.floor((Math.max(ax, bx) + r) / 16);
        int c0z = (int) Math.floor((Math.min(az, bz) - r) / 16), c1z = (int) Math.floor((Math.max(az, bz) + r) / 16);
        along = new HashMap<>();
        for (int x = c0x; x <= c1x; x++) {
            for (int z = c0z; z <= c1z; z++) {
                double cx = x * 16 + 8, cz = z * 16 + 8;
                if (flatDist2(cx, cz) > (r + 12) * (r + 12)) continue;
                int[] c = {x, z};
                chunks.add(c);
                along.put(c, (cx - ax) * dx + (cz - az) * dz);
            }
        }
        // dans l'ordre du trajet de la boule
        chunks.sort((a, b) -> Double.compare(along.get(a), along.get(b)));
    }

    /** Distance² horizontale entre une colonne et le trajet (pour trier / filtrer les chunks). */
    private double flatDist2(double x, double z) {
        double hx = dx, hz = dz, hl = Math.sqrt(hx * hx + hz * hz);
        double t;
        if (hl < 1e-6) t = 0;
        else t = Math.max(0, Math.min(len * hl, ((x - ax) * hx + (z - az) * hz) / hl));
        double px = ax + (hl < 1e-6 ? 0 : hx / hl * t), pz = az + (hl < 1e-6 ? 0 : hz / hl * t);
        return (x - px) * (x - px) + (z - pz) * (z - pz);
    }

    private boolean inside(double x, double y, double z) {
        double t = (x - ax) * dx + (y - ay) * dy + (z - az) * dz;
        if (t < 0) t = 0;
        else if (t > len) t = len;
        double px = ax + dx * t - x, py = ay + dy * t - y, pz = az + dz * t - z;
        return px * px + py * py + pz * pz <= r * r;
    }

    /** Jusqu'où (en blocs le long du trajet) le néant est arrivé. */
    double progress() {
        if (!full) return 0;
        if (chunkIdx >= chunks.size()) return len;
        Double a = along.get(chunks.get(chunkIdx));
        double hl = Math.sqrt(dx * dx + dz * dz);
        double v = a == null ? 0 : a / Math.max(0.2, hl) - 12;
        return Math.max(0, Math.min(len, v));
    }

    double length() {
        return len;
    }

    boolean erasing() {
        return !full || chunkIdx < chunks.size();
    }

    /** La partie visible (surface, bâtiments) est-elle encore là ? */
    boolean surfacePending() {
        return full && chunkIdx < chunks.size();
    }

    boolean contains(Location l) {
        return l.getWorld() == w && inside(l.getX(), l.getY(), l.getZ());
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

    private void releaseTickets() {
        for (long k : tickets) w.removePluginChunkTicket((int) (k >> 32), (int) k, pl);
        tickets.clear();
    }

    // ================================================================= effacement

    /** Efface pendant au plus "budgetNanos" ce tick, colonne par colonne. */
    int eraseTick(long budgetNanos) {
        long start = System.nanoTime();
        int count = 0, skipped = 0;
        if (!full) budgetNanos /= 2;
        while (chunkIdx < chunks.size() && System.nanoTime() - start < budgetNanos) {
            int[] c = chunks.get(chunkIdx);
            if (!w.isChunkGenerated(c[0], c[1])) {
                chunkIdx++;
                col = 0;
                continue;
            }
            if (!ready(c[0], c[1])) {
                if (++skipped > 8) break;
                chunks.remove(chunkIdx);
                chunks.add(Math.min(chunks.size(), chunkIdx + 4), c);
                continue;
            }
            Chunk ch = w.getChunkAt(c[0], c[1]);
            while (col < 256 && System.nanoTime() - start < budgetNanos) {
                int lx = col & 15, lz = col >> 4;
                col++;
                int x = c[0] * 16 + lx, z = c[1] * 16 + lz;
                if (flatDist2(x + 0.5, z + 0.5) > r * r) continue;
                int high = w.getHighestBlockYAt(x, z);
                // phase cachée : seulement sous la croûte de surface (arbres et bâtiments ignorés)
                int top = full ? Math.min(maxY, high)
                        : Math.min(maxY, Math.min(w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES), hiddenCeil) - SHELL - 1);
                for (int y = top; y >= minY; y--) {
                    if (!inside(x + 0.5, y + 0.5, z + 0.5)) continue;
                    Block b = ch.getBlock(lx, y, lz);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (!b.isLiquid() && !Ruin.breakable(b)) continue;
                    record(x, y, z, b.getBlockData());
                    b.setBlockData(AIR, false);
                    count++;
                }
            }
            if (col >= 256) {
                chunkIdx++;
                col = 0;
            }
        }
        if (full && chunkIdx >= chunks.size()) {
            releaseTickets();
            save();
        }
        return count;
    }

    // ================================================================= reconstruction

    boolean restoreDue(int now) {
        return !erasing() && restoreAt >= 0 && now >= restoreAt;
    }

    void scheduleRestore(int now, int delayTicks) {
        restoreAt = now + delayTicks;
    }

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

    void restoreAll() {
        full = true;
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
