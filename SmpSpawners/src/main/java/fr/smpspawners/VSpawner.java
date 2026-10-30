package fr.smpspawners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Un spawner posé : son mob, combien sont empilés, ce qu'il a stocké. */
final class VSpawner {

    final String world;
    final int x, y, z;
    EntityType type;
    int stack;
    UUID owner;
    String ownerName;
    long xp;
    final Map<Material, Long> items = new EnumMap<>(Material.class);
    double progress;          // secondes accumulées vers le prochain "kill"
    transient TextDisplay holo;

    VSpawner(String world, int x, int y, int z, EntityType type, int stack, UUID owner, String ownerName) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.stack = stack;
        this.owner = owner;
        this.ownerName = ownerName;
    }

    static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    static String key(Location l) {
        return key(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    String key() {
        return key(world, x, y, z);
    }

    long chunkKey() {
        return ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
    }

    Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    long totalItems() {
        long t = 0;
        for (long v : items.values()) t += v;
        return t;
    }

    void take(Material m, long n) {
        long left = items.getOrDefault(m, 0L) - n;
        if (left <= 0) items.remove(m);
        else items.put(m, left);
    }
}
