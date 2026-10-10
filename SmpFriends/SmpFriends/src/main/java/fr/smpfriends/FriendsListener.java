package fr.smpfriends;

import fr.smpfriends.menu.Menu;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/** Clics dans les menus, réponses aux questions dans le chat, connexions, AFK. */
final class FriendsListener implements Listener {

    private final FriendsPlugin plugin;

    FriendsListener(FriendsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) return;
        e.setCancelled(true); // rien ne bouge dans nos menus
        if (e.getClickedInventory() == top) menu.click(e.getRawSlot(), e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    /** Priorité la plus basse : la réponse à une question n'apparaît pas dans le chat public. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (plugin.prompts().handle(e.getPlayer(), text)) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.activity().moved(p);
        plugin.friends().onJoin(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.friends().onQuit(p);
        plugin.activity().forget(p);
        plugin.prompts().forget(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        plugin.activity().moved(e.getPlayer());
    }
}
