package fr.smpmarket;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Zone rectangulaire d'un stand (coins inclus). */
public record Cuboid(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Cuboid of(Location a, Location b) {
        return new Cuboid(a.getWorld().getName(),
                Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));
    }

    public boolean contains(Location l) {
        return l != null && l.getWorld() != null && l.getWorld().getName().equals(world)
                && contains(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public boolean contains(Block b) {
        return b != null && b.getWorld().getName().equals(world) && contains(b.getX(), b.getY(), b.getZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(Cuboid o) {
        return world.equals(o.world) && minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public String size() {
        return sizeX() + "x" + sizeZ();
    }

    /** Vrai si le chunk (cx, cz) touche cette zone. */
    public boolean touchesChunk(String w, int cx, int cz) {
        return world.equals(w) && (minX >> 4) <= cx && (maxX >> 4) >= cx && (minZ >> 4) <= cz && (maxZ >> 4) >= cz;
    }
}
