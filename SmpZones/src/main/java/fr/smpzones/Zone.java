package fr.smpzones;

import org.bukkit.Location;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Une zone : une boîte dans un monde (ou tout le monde si global), une priorité, des permissions, des membres. */
final class Zone {
    final String name;
    String world;
    boolean global;
    int[] box; // minX, minY, minZ, maxX, maxY, maxZ
    int priority;
    final Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
    final Map<UUID, String> members = new LinkedHashMap<>();
    String enterMsg, leaveMsg;

    Zone(String name) {
        this.name = name;
    }

    boolean contains(Location l) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        if (global) return true;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
    }

    long volume() {
        return global ? Long.MAX_VALUE : (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
    }

    String size() {
        return global ? "tout le monde" : (box[3] - box[0] + 1) + "×" + (box[4] - box[1] + 1) + "×" + (box[5] - box[2] + 1);
    }
}
