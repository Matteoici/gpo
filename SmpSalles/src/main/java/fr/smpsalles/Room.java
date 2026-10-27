package fr.smpsalles;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Une salle : une boîte (coins inclus), un code, une ou plusieurs portes. */
final class Room {
    final String name;
    String world;
    int[] box; // minX, minY, minZ, maxX, maxY, maxZ
    String code = "";
    final List<int[]> doors = new ArrayList<>(); // bas de chaque porte
    Location pos1, pos2;
    /** Groupe vocal (Simple Voice Chat) de la salle. */
    UUID groupId;

    Room(String name) {
        this.name = name;
    }

    boolean ready() {
        return world != null && box != null;
    }

    boolean contains(Location l) {
        if (!ready() || l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
    }

    boolean isDoor(Block b) {
        if (b == null || world == null || !b.getWorld().getName().equals(world)) return false;
        for (int[] d : doors) if (d[0] == b.getX() && d[1] == b.getY() && d[2] == b.getZ()) return true;
        return false;
    }

    void setBox(Location a, Location b) {
        world = a.getWorld().getName();
        box = new int[]{Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ())};
    }
}
