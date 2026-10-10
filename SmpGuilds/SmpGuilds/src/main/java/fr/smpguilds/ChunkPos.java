package fr.smpguilds;

import org.bukkit.Location;

/** Position d'un chunk (monde + coordonnées de chunk). */
public record ChunkPos(String world, int x, int z) {

    public static ChunkPos of(Location l) {
        return new ChunkPos(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    public String serialize() {
        return world + ";" + x + ";" + z;
    }

    public static ChunkPos parse(String s) {
        String[] p = s.split(";");
        return new ChunkPos(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    public boolean adjacent(ChunkPos o) {
        return world.equals(o.world) && Math.abs(x - o.x) + Math.abs(z - o.z) == 1;
    }

    public ChunkPos offset(int dx, int dz) {
        return new ChunkPos(world, x + dx, z + dz);
    }
}
