package fr.smpguard;

import fr.smpguard.gui.Menu;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

final class GuardListener implements Listener {

    private final SmpGuard plugin;

    GuardListener(SmpGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.join(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.quit(e.getPlayer());
    }

    // ---------------------------------------------------------------- pauses (les positions "sautent")

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        plugin.grace(e.getPlayer(), plugin.settings().graceTeleport);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        plugin.grace(e.getPlayer(), plugin.settings().graceRespawn);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        plugin.grace(e.getPlayer(), plugin.settings().graceWorld);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        plugin.grace(e.getPlayer(), plugin.settings().graceTeleport);
    }

    // ---------------------------------------------------------------- détections

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        plugin.xray().onBreak(e.getPlayer(), e.getBlock());
    }

    /** Combat : les deux joueurs se voient tout de suite (anti-esp), et l'appât ne prend jamais de dégâts. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker != null && e.getEntity() instanceof Player victim) plugin.culling().reveal(attacker, victim);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBaitDamage(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(plugin.key(), PersistentDataType.BYTE)) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- menus

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == top) menu.click(e.getSlot(), e.getClick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
