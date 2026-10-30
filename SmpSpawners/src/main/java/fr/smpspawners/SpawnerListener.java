package fr.smpspawners;

import com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent;
import fr.smpspawners.Defs.Mob;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

final class SpawnerListener implements Listener {

    private final SmpSpawners plugin;

    SpawnerListener(SmpSpawners plugin) {
        this.plugin = plugin;
    }

    private boolean canUse(Player p, VSpawner s) {
        return !plugin.ownerOnly || p.hasPermission("smpspawners.admin") || (s.owner != null && s.owner.equals(p.getUniqueId()));
    }

    // ================================================================= pose

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (b.getType() != Material.SPAWNER) return;
        ItemStack it = e.getItemInHand();
        String id = plugin.mobOf(it);
        if (id == null) {
            if (!plugin.convertVanilla) return;
            if (!(it.getItemMeta() instanceof BlockStateMeta bsm) || !bsm.hasBlockState()
                    || !(bsm.getBlockState() instanceof CreatureSpawner cs) || plugin.mob(cs.getSpawnedType()) == null) return;
            id = cs.getSpawnedType().name();
        }
        Player p = e.getPlayer();
        if (plugin.maxPerChunk > 0 && plugin.countInChunk(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4) >= plugin.maxPerChunk) {
            e.setCancelled(true);
            plugin.msg(p, "<red>Trop de spawners dans ce chunk (max " + plugin.maxPerChunk + "). <gray>Empile-les plutôt : clic droit sur un spawner du même mob.");
            return;
        }
        boolean random = SmpSpawners.RANDOM.equals(id);
        Mob m;
        if (random) {
            m = plugin.roll();
        } else {
            try {
                m = plugin.mob(EntityType.valueOf(id));
            } catch (IllegalArgumentException ex) {
                m = null;
            }
        }
        if (m == null) {
            e.setCancelled(true);
            plugin.msg(p, "<red>Ce spawner contient un mob qui n'existe plus dans la config.");
            return;
        }
        VSpawner s = new VSpawner(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), m.type(), 1, p.getUniqueId(), p.getName());
        plugin.add(s);
        // un tick plus tard (et encore un peu après, au cas où un autre plugin réécrit le spawner)
        Bukkit.getScheduler().runTask(plugin, () -> plugin.applyBlock(s));
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.applyBlock(s), 3L);
        if (random) plugin.rollAnimation(p, m);
        else plugin.msg(p, "<gray>Spawner à " + m.rarity().color() + m.name() + " <gray>posé. <yellow>Clic droit</yellow> dessus pour récupérer l'XP et le butin.");
    }

    // ================================================================= clic droit

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        VSpawner s = plugin.at(b);
        if (s == null) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = plugin.mobOf(hand);
        boolean sameMob = id != null && id.equals(s.type.name());
        // accroupi avec un bloc en main : on laisse poser à côté
        if (!sameMob && p.isSneaking() && hand.getType().isBlock() && hand.getType() != Material.AIR) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!canUse(p, s)) {
            plugin.msg(p, "<red>Ce spawner appartient à <white>" + s.ownerName + "<red>.");
            return;
        }
        if (sameMob) {
            int room = plugin.maxStack - s.stack;
            if (room <= 0) {
                plugin.msg(p, "<red>Ce spawner est déjà au maximum (" + plugin.maxStack + ").");
                return;
            }
            int add = Math.min(room, p.isSneaking() ? hand.getAmount() : 1);
            hand.setAmount(hand.getAmount() - add);
            s.stack += add;
            plugin.markDirty();
            p.playSound(b.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.3f);
            plugin.msg(p, "<green>+" + add + " <gray>→ <white>" + s.stack + " <gray>spawners empilés (production ×" + s.stack + ").");
            return;
        }
        if (id != null) plugin.msg(p, "<gray>Ce n'est pas le même mob : accroupis-toi pour poser ton spawner à côté.");
        new SpawnerMenu(plugin, s, p).open();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof SpawnerMenu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == e.getView().getTopInventory() && e.getWhoClicked() instanceof Player) menu.click(e.getSlot(), e.getClick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof SpawnerMenu) e.setCancelled(true);
    }

    // ================================================================= casse

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        VSpawner s = plugin.at(b);
        if (s == null) {
            // spawner naturel (donjon...) cassé avec Toucher de soie
            if (!plugin.naturalSilk || p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
            ItemStack tool = p.getInventory().getItemInMainHand();
            if (!tool.containsEnchantment(Enchantment.SILK_TOUCH)) return;
            if (!(b.getState() instanceof CreatureSpawner cs) || plugin.mob(cs.getSpawnedType()) == null) return;
            e.setExpToDrop(0);
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), plugin.item(cs.getSpawnedType().name(), 1));
            return;
        }
        if (!canUse(p, s)) {
            e.setCancelled(true);
            plugin.msg(p, "<red>Ce spawner appartient à <white>" + s.ownerName + "<red>.");
            return;
        }
        e.setDropItems(false);
        e.setExpToDrop(0);
        // tout est rendu : le spawner (avec ses empilés), le butin et l'XP
        plugin.pickup(p, s);
        plugin.msg(p, "<gray>Spawner repris avec tout son contenu.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER && plugin.at(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER && plugin.at(b) != null);
    }

    // ================================================================= aucun mob

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreSpawn(PreSpawnerSpawnEvent e) {
        if (plugin.at(e.getSpawnerLocation().getBlock()) != null) {
            e.setCancelled(true);
            e.setShouldAbortSpawn(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpawn(SpawnerSpawnEvent e) {
        if (e.getSpawner() != null && plugin.at(e.getSpawner().getBlock()) != null) e.setCancelled(true);
    }
}
