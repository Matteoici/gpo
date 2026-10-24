package fr.smpmarket;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * Spawners avec un mob (zombie, blaze...) : Minecraft n'applique le mob contenu dans l'objet que si celui qui
 * le pose est op. Un joueur normal (qui l'a acheté au marché, au shop, en échange...) obtenait un spawner vide.
 * On recopie nous-mêmes le mob et ses réglages juste après la pose.
 */
final class SpawnerFix implements Listener {

    private final SmpMarket plugin;

    SpawnerFix(SmpMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block block = e.getBlockPlaced();
        if (block.getType() != Material.SPAWNER) return;
        ItemStack it = e.getItemInHand();
        if (!it.hasItemMeta() || !(it.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return;
        if (!(meta.getBlockState() instanceof CreatureSpawner from)) return;
        EntityType type = from.getSpawnedType();
        if (type == null) return;
        int minDelay = from.getMinSpawnDelay(), maxDelay = from.getMaxSpawnDelay(), count = from.getSpawnCount();
        int nearby = from.getMaxNearbyEntities(), range = from.getRequiredPlayerRange(), spawnRange = from.getSpawnRange();
        // un tick plus tard : après que le jeu a (ou n'a pas) appliqué les données de l'objet
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() != Material.SPAWNER || !(block.getState() instanceof CreatureSpawner to)) return;
            to.setSpawnedType(type);
            try {
                to.setMaxSpawnDelay(Math.max(maxDelay, 1));
                to.setMinSpawnDelay(Math.min(minDelay, Math.max(maxDelay, 1)));
                to.setSpawnCount(count);
                to.setMaxNearbyEntities(nearby);
                to.setRequiredPlayerRange(range);
                to.setSpawnRange(spawnRange);
            } catch (IllegalArgumentException ignored) {
                // réglages incohérents dans l'objet : on garde ceux du jeu, le mob suffit
            }
            to.update(true, false);
        });
    }
}
