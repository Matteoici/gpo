package fr.smpguard;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** Tout ce que l'anticheat sait d'un joueur connecté. */
public final class Profile {

    public static final int HISTORY = 80; // 4 secondes de positions (1 par tick)

    public final UUID id;
    public String name;
    public final double[] vl = new double[Check.values().length];
    public final long[] lastAlert = new long[Check.values().length];
    public final double[] peak = new double[Check.values().length];
    public int alerts;
    public boolean susSent;
    public final Deque<String> recent = new ArrayDeque<>(); // dernières alertes (pour le rapport)

    // ---------------------------------------------------------------- lag
    private final int[] pings = new int[30];
    private int pingIdx, pingCount;
    public long graceUntil;

    // ---------------------------------------------------------------- historique des positions (yeux)
    private final double[] hx = new double[HISTORY], hy = new double[HISTORY], hz = new double[HISTORY];
    private final double[] heye = new double[HISTORY];
    private int hIdx, hCount;

    // ---------------------------------------------------------------- esp-aim : une poursuite par cible
    public static final class Track {
        public int samples;
        public double sx, sz;      // position de la cible au début
        public double startYaw;    // direction (degrés) vers la cible au début
        public long lastSample;
    }

    public final Map<UUID, Track> tracks = new HashMap<>();

    // ---------------------------------------------------------------- freecam
    public double inputStillSeconds;

    // ---------------------------------------------------------------- xray
    public record Mined(long time, boolean nether) {
    }

    public record Vein(long time, boolean nether, double weight, boolean hidden, int x, int y, int z, String type) {
    }

    /** Chaque minerai rare cassé (pour regrouper les blocs d'un même filon). */
    public record Ore(int x, int y, int z, long time, String type) {
    }

    public final Deque<Ore> ores = new ArrayDeque<>();
    public final Deque<Mined> stone = new ArrayDeque<>();
    public final Deque<Vein> veins = new ArrayDeque<>();
    /** Positions des blocs cassés récemment par le joueur (pour savoir si un trou est "naturel"). */
    public final LinkedHashSet<Long> broken = new LinkedHashSet<>();

    public Profile(Player p) {
        this.id = p.getUniqueId();
        this.name = p.getName();
    }

    // ================================================================= ping

    public void samplePing(int ping) {
        pings[pingIdx] = Math.max(0, ping);
        pingIdx = (pingIdx + 1) % pings.length;
        pingCount = Math.min(pingCount + 1, pings.length);
    }

    public int avgPing() {
        if (pingCount == 0) return 0;
        long s = 0;
        for (int i = 0; i < pingCount; i++) s += pings[i];
        return (int) (s / pingCount);
    }

    /** Variation du ping (écart-type) sur les 30 dernières secondes. */
    public double jitter() {
        if (pingCount < 3) return 0;
        double avg = avgPing();
        double s = 0;
        for (int i = 0; i < pingCount; i++) s += (pings[i] - avg) * (pings[i] - avg);
        return Math.sqrt(s / pingCount);
    }

    // ================================================================= positions

    public void record(Player p) {
        Location l = p.getLocation();
        hx[hIdx] = l.getX();
        hy[hIdx] = l.getY();
        hz[hIdx] = l.getZ();
        heye[hIdx] = p.getEyeHeight();
        hIdx = (hIdx + 1) % HISTORY;
        hCount = Math.min(hCount + 1, HISTORY);
    }

    public void clearHistory() {
        hCount = 0;
    }

    /** Position (pieds) il y a "ticks" ticks. null si on ne remonte pas si loin. */
    public Vector at(int ticks) {
        if (hCount == 0) return null;
        ticks = Math.max(0, Math.min(ticks, hCount - 1));
        int i = Math.floorMod(hIdx - 1 - ticks, HISTORY);
        return new Vector(hx[i], hy[i], hz[i]);
    }

    public Vector eyeAt(int ticks) {
        Vector v = at(ticks);
        if (v == null) return null;
        int i = Math.floorMod(hIdx - 1 - Math.max(0, Math.min(ticks, hCount - 1)), HISTORY);
        return v.add(new Vector(0, heye[i], 0));
    }

    public int historySize() {
        return hCount;
    }

    /** Distance horizontale parcourue sur les "ticks" derniers ticks. */
    public double moved(int ticks) {
        Vector a = at(0), b = at(ticks);
        if (a == null || b == null) return 0;
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ================================================================= xray

    public void rememberBroken(int x, int y, int z) {
        broken.add(pack(x, y, z));
        if (broken.size() > 800) {
            var it = broken.iterator();
            it.next();
            it.remove();
        }
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ================================================================= score

    public double maxVl() {
        double m = 0;
        for (double v : vl) m = Math.max(m, v);
        return m;
    }

    public void note(String line) {
        recent.addFirst(line);
        while (recent.size() > 10) recent.removeLast();
    }
}
