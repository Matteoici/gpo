package fr.smpshop;

import fr.smpshop.gui.Menu;
import fr.smpshop.gui.SellMenu;
import org.bukkit.Bukkit;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

import static fr.smpshop.Texts.ph;

final class ShopListener implements Listener {

    private final SmpShop plugin;

    ShopListener(SmpShop plugin) {
        this.plugin = plugin;
    }

    // ================================================================= menus

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder(false);
        if (h instanceof Menu menu) {
            e.setCancelled(true);
            if (e.getClickedInventory() == top) menu.click(e.getSlot(), e.getClick());
            return;
        }
        if (h instanceof SellMenu sell) {
            boolean inTop = e.getClickedInventory() == top;
            if (inTop && e.getSlot() >= SellMenu.AREA) {
                e.setCancelled(true);
                sell.clickBottom(e.getSlot());
                return;
            }
            // double-clic qui ramasserait les boutons, ou touche numérique vers les boutons
            if (e.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, sell::refresh);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder(false);
        if (h instanceof Menu) {
            e.setCancelled(true);
            return;
        }
        if (h instanceof SellMenu sell) {
            for (int raw : e.getRawSlots()) {
                if (raw >= SellMenu.AREA && raw < 54) {
                    e.setCancelled(true);
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, sell::refresh);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof SellMenu sell) sell.close();
    }

    // ================================================================= réparation des objets (ancien plugin de vente)

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            int n = plugin.cleaner().clean(p.getInventory()) + plugin.cleaner().clean(p.getEnderChest());
            if (n > 0) plugin.getLogger().info("Réparé " + n + " pile(s) d'objets de " + p.getName());
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        Inventory top = e.getInventory();
        InventoryHolder h = top.getHolder(false);
        // seulement les vrais coffres / shulkers / entités, jamais les menus d'autres plugins
        if (h instanceof Container || h instanceof DoubleChest || h instanceof Entity) plugin.cleaner().clean(top);
        if (e.getPlayer() instanceof Player p) plugin.cleaner().clean(p.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        if (plugin.cleaner().clean(it)) e.getItem().setItemStack(it);
    }

    // ================================================================= créatif : jamais de ligne de prix

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, p::updateInventory);
    }

    // ================================================================= jetons sur les monstres (événement)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        if (!plugin.tokenDrops()) return;
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player) return;
        var drop = plugin.tokenDrop(dead.getType());
        if (drop == null) return;
        if (ThreadLocalRandom.current().nextDouble() * 100 >= drop[0]) return;
        long amount = (long) drop[1];
        plugin.tokens().add(killer.getUniqueId(), amount);
        killer.sendActionBar(plugin.texts().get("tokens.drop", ph("amount", amount),
                ph("tokens", plugin.settings().tokens(plugin.tokens().get(killer.getUniqueId())))));
    }
}
