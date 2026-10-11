package fr.gpotrade;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Tous les événements : clics dans la fenêtre, chat privé, connexion, pack, accroupi + clic droit. */
final class TradeListener implements Listener {

    private final TradePlugin plugin;

    TradeListener(TradePlugin plugin) {
        this.plugin = plugin;
    }

    // =============================================================== fenêtre d'échange

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof TradeHolder h)) return;
        TradeSession s = h.session();
        int me = h.index();
        InventoryAction action = e.getAction();

        // le double-clic rassemble les items de toute la fenêtre, y compris ceux du partenaire
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        if (e.getClickedInventory() == null) return;

        if (e.getClickedInventory() == top) {
            int slot = e.getRawSlot();
            if (TradeSession.isAccept(slot)) {
                e.setCancelled(true);
                s.accept(me);
            } else if (TradeSession.isDecline(slot)) {
                e.setCancelled(true);
                s.cancel(s.player(me).getName() + " a refusé l'échange.");
            } else if (slot == TradeSession.CHAT) {
                e.setCancelled(true);
                s.openChat(me);
            } else if (slot == TradeSession.MONEY_OWN) {
                e.setCancelled(true);
                if (!plugin.moneyEnabled()) return;
                long step = plugin.moneyStep();
                switch (e.getClick()) {
                    case LEFT -> s.adjustMoney(me, step, false);
                    case RIGHT -> s.adjustMoney(me, -step, false);
                    case SHIFT_LEFT -> s.adjustMoney(me, step * 10, false);
                    case SHIFT_RIGHT -> s.adjustMoney(me, -step * 10, false);
                    case DROP, CONTROL_DROP -> s.adjustMoney(me, 0, true);
                    case MIDDLE, SWAP_OFFHAND -> s.openMoneyInput(me);
                    default -> { }
                }
            } else if (TradeSession.isOwn(slot)) {
                s.touch(me); // modification autorisée de sa propre grille
            } else {
                e.setCancelled(true); // décor, grille du partenaire...
            }
        } else if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            // shift-clic depuis son inventaire : on range l'item nous-mêmes, uniquement dans SA grille
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType().isAir()) return;
            int left = s.addToOffer(me, it);
            if (left != it.getAmount()) {
                ItemStack rest = it.clone();
                rest.setAmount(left);
                e.setCurrentItem(left <= 0 ? null : rest);
                s.touch(me);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof TradeHolder h)) return;
        boolean touchesTop = false;
        for (int raw : e.getRawSlots()) {
            if (raw >= top.getSize()) continue;
            if (!TradeSession.isOwn(raw)) {
                e.setCancelled(true);
                return;
            }
            touchesTop = true;
        }
        if (touchesTop) h.session().touch(h.index());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof TradeHolder h) {
            h.session().onClosed(h.index());
        }
    }

    /** Pendant un échange, ce qu'un joueur écrit ne va qu'à son partenaire. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        TradeSession s = plugin.session(p);
        if (s == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> s.chat(p, text));
    }

    // =============================================================== joueurs

    /** Accroupi + clic droit sur un joueur = demande d'échange. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // l'événement arrive une fois par main
        if (!(e.getRightClicked() instanceof Player target)) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || !plugin.sneakClickRequest()) return;
        if (target.hasMetadata("NPC") || !p.hasPermission("gpotrade.use")) return;
        plugin.request(p, target);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.pack().send(p);
        }, 20L);
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> plugin.pack().markLoaded(e.getPlayer(), true);
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                    plugin.pack().markLoaded(e.getPlayer(), false);
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        TradeSession s = plugin.session(p);
        if (s != null) s.cancel("Échange annulé : " + p.getName() + " s'est déconnecté.");
        plugin.forgetRequests(p);
        plugin.pack().forget(p);
    }
}
