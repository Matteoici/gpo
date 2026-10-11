package fr.smpevents.koth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Zone de roi de la colline : un cylindre (rayon + hauteur) autour d'un point. */
public final class KothZone {

    public final String name;
    public final String world;
    public final double x, y, z;
    public final double radius;

    public KothZone(String name, String world, double x, double y, double z, double radius) {
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
    }

    public Location center() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    public boolean contains(Location l, double height) {
        if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        double dx = l.getX() - x, dz = l.getZ() - z;
        return dx * dx + dz * dz <= radius * radius && l.getY() >= y - 1.5 && l.getY() <= y + height;
    }
}
