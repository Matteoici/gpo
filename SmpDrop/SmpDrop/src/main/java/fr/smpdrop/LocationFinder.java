package fr.smpdrop;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cherche un endroit de chute au hasard. Les chunks sont chargés en asynchrone
 * (getChunkAtAsync) pour ne jamais bloquer le serveur.
 */
final class LocationFinder {

    private final SmpDrop plugin;
    private final World world;
    private final Settings s;
    private final CompletableFuture<Location> result = new CompletableFuture<>();

    private LocationFinder(SmpDrop plugin, World world, Settings s) {
        this.plugin = plugin;
        this.world = world;
        this.s = s;
    }

    /** Renvoie le bloc (vide) où la caisse se posera, ou null si rien n'a été trouvé. */
    static CompletableFuture<Location> find(SmpDrop plugin, World world, Settings s) {
        LocationFinder f = new LocationFinder(plugin, world, s);
        f.attempt(s.maxAttempts);
        return f.result;
    }

    private void attempt(int left) {
        if (!plugin.isEnabled()) {
            result.complete(null);
            return;
        }
        if (left <= 0) {
            result.complete(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2;
        // répartition uniforme sur l'anneau min-radius / max-radius
        double radius = Math.sqrt(r.nextDouble() * (s.maxRadius * s.maxRadius - s.minRadius * s.minRadius)
                + s.minRadius * s.minRadius);
        int x = (int) Math.floor(s.centerX + Math.cos(angle) * radius);
        int z = (int) Math.floor(s.centerZ + Math.sin(angle) * radius);
        if (!world.getWorldBorder().isInside(new Location(world, x + 0.5, 64, z + 0.5))) {
            attempt(left - 1);
            return;
        }
        world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Location ok = (error == null && chunk != null) ? check(x, z) : null;
                    if (ok != null) result.complete(ok);
                    else attempt(left - 1);
                }));
    }

    private Location check(int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 20) return null;
        Block top = world.getBlockAt(x, y, z);
        if (top.isLiquid() || s.avoidBlocks.contains(top.getType()) || !top.getType().isSolid()) return null;
        Block place = top.getRelative(0, 1, 0);
        if (place.isLiquid() || !(place.isEmpty() || place.isPassable())) return null;
        if (place.getRelative(0, 1, 0).isLiquid()) return null;
        Location loc = place.getLocation();
        if (s.avoidClaims && Hooks.isClaimed(loc)) return null;
        return loc;
    }
}
