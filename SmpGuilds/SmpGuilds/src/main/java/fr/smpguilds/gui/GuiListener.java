package fr.smpguilds.gui;

import fr.smpguilds.GuildVaults;
import fr.smpguilds.SmpGuilds;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/** Clics dans les menus (aucun objet ne peut être pris) + fermeture du coffre de guilde. */
public final class GuiListener implements Listener {

    private final SmpGuilds plugin;

    public GuiListener(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (!(h instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.equals(menu.viewer)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        var type = e.getClick();
        // tick suivant : ouvrir un autre menu pendant l'événement de clic n'est pas fiable
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory().getTopInventory().getHolder() == menu) menu.click(slot, type);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof GuildVaults.Holder h) plugin.vaults().onClose(h);
    }
}
