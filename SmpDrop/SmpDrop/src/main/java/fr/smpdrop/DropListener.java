package fr.smpdrop;

import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

final class DropListener implements Listener {

    private final SmpDrop plugin;

    DropListener(SmpDrop plugin) {
        this.plugin = plugin;
    }

    private DropEvent ev() {
        return plugin.manager().current();
    }

    // ------------------------------------------------------------ clic sur la caisse

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        DropEvent ev = ev();
        if (ev == null || !ev.isCrate(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        ev.tryOpen(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractBlock(PlayerInteractEvent e) {
        DropEvent ev = ev();
        if (ev == null || !ev.isCrateBlock(e.getClickedBlock())) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        ev.tryOpen(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        DropEvent ev = ev();
        if (ev != null && ev.isCrateBlock(e.getBlock())) e.setCancelled(true);
    }

    // ------------------------------------------------------------ coups : interrompent l'ouverture

    /** Les feux d'artifice décoratifs ne blessent personne. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFirework(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework fw
                && fw.getPersistentDataContainer().has(plugin.entityKey(), PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        DropEvent ev = ev();
        if (ev == null) return;
        Settings s = plugin.settings();
        if (e.getEntity() instanceof Player victim && ev.isOpener(victim)) {
            if (e instanceof EntityDamageByEntityEvent) {
                ev.interrupt("hit");
                return;
            }
            if (s.anyDamage) {
                ev.interrupt("hurt");
                return;
            }
        }
        if (s.attackInterrupts && e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = null;
            if (ed.getDamager() instanceof Player p) attacker = p;
            else if (ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
            if (attacker != null && attacker != e.getEntity() && ev.isOpener(attacker)) ev.interrupt("attack");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        DropEvent ev = ev();
        if (ev != null && ev.isOpener(e.getEntity())) ev.interrupt("died");
    }

    // ------------------------------------------------------------ joueurs

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        DropEvent ev = ev();
        if (ev != null) ev.onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        DropEvent ev = ev();
        if (ev != null) ev.onQuit(e.getPlayer());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        DropEvent ev = ev();
        if (ev != null && ev.isLoot(e.getInventory().getHolder())) ev.checkEmpty();
    }
}
