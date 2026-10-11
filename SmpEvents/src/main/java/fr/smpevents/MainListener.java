package fr.smpevents;

import fr.smpevents.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;

/** Menus, connexions, chargement des chunks (hologrammes). */
final class MainListener implements Listener {

    private final SmpEvents plugin;

    MainListener(SmpEvents plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == top) menu.click(e.getSlot(), e.getClick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data().of(p);
        plugin.quests().ensure(d);
        plugin.relations().sample(p);
        plugin.boards().update(p);
        plugin.koth().onJoin(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !plugin.quests().enabled) return;
            int left = 0;
            for (var q : d.daily) if (!q.done) left++;
            if (left > 0) plugin.texts().send(p, "quests.join", Texts.ph("count", left));
            var b = plugin.bounties().of(p.getUniqueId());
            if (b != null) plugin.texts().send(p, "bounty.on-you", Texts.ph("total", plugin.settings().money(b.total())));
        }, 80L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.boards().update(e.getPlayer());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        String w = e.getWorld().getName();
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.holograms().onChunkLoad(w, cx, cz));
    }
}
