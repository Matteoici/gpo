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

    // ------------------------------------------------------------ objets ordinaires déjà reçus avec l'étiquette "Largage aérien"

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinClean(PlayerJoinEvent e) {
        clean(e.getPlayer().getInventory());
        clean(e.getPlayer().getEnderChest());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenClean(org.bukkit.event.inventory.InventoryOpenEvent e) {
        clean(e.getInventory());
        clean(e.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupClean(org.bukkit.event.entity.EntityPickupItemEvent e) {
        org.bukkit.inventory.ItemStack it = e.getItem().getItemStack();
        if (strip(it)) e.getItem().setItemStack(it);
    }

    private static void clean(org.bukkit.inventory.Inventory inv) {
        if (inv == null) return;
        org.bukkit.inventory.ItemStack[] content = inv.getContents();
        for (int i = 0; i < content.length; i++) {
            org.bukkit.inventory.ItemStack it = content[i];
            if (it != null && strip(it)) inv.setItem(i, it);
        }
    }

    /**
     * Retire l'étiquette d'un objet ORDINAIRE (pas de nom, pas d'enchantement, une seule ligne = l'étiquette),
     * pour qu'il s'empile de nouveau avec les objets normaux. Les objets spéciaux du largage la gardent.
     */
    static boolean strip(org.bukkit.inventory.ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta.hasDisplayName() || meta.hasEnchants()) return false;
        java.util.List<net.kyori.adventure.text.Component> lore = meta.lore();
        if (lore == null || lore.size() != 1) return false;
        String line = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(lore.get(0));
        if (!line.contains("Largage aérien")) return false;
        meta.lore(null);
        it.setItemMeta(meta);
        return true;
    }
}
