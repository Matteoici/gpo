package fr.smpstart;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Clics dans le guide : rien ne peut être pris, les boutons s'exécutent au tick suivant. */
public final class GuiListener implements Listener {

    private final SmpStart plugin;

    GuiListener(SmpStart plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GuideMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.equals(menu.viewer())) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        var type = e.getClick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory().getTopInventory().getHolder() == menu) menu.click(slot, type);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof GuideMenu) e.setCancelled(true);
    }
}
