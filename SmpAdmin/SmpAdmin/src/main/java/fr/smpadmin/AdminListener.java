package fr.smpadmin;

import fr.smpadmin.gui.Menu;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AdminListener implements Listener {

    private final SmpAdmin plugin;
    /** Membres du staff qui regardent un inventaire en lecture seule. */
    private final Set<UUID> readOnly = ConcurrentHashMap.newKeySet();

    AdminListener(SmpAdmin plugin) {
        this.plugin = plugin;
    }

    void markReadOnly(Player viewer) {
        readOnly.add(viewer.getUniqueId());
    }

    // ================================================================= connexion

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Punishment ban = plugin.sanctions().activeBan(e.getUniqueId());
        if (ban != null) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.actions().banScreen(ban));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.vanish().onJoin(p);
        if (plugin.vanish().is(p)) {
            e.joinMessage(null);
            plugin.texts().send(p, "vanish.on");
        }
        StaffState.Suspect sus = plugin.state().suspect(p.getUniqueId());
        if (sus != null) plugin.actions().suspectJoined(p, sus);
        if (plugin.actions().isFrozen(p.getUniqueId())) {
            plugin.actions().showFreeze(p);
            plugin.texts().send(p, "freeze.chat");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (plugin.vanish().is(p)) e.quitMessage(null);
        plugin.spectate().onQuit(p);
        plugin.input().forget(p.getUniqueId());
        plugin.state().staffChat.remove(p.getUniqueId());
        readOnly.remove(p.getUniqueId());
        if (plugin.actions().isFrozen(p.getUniqueId())) plugin.actions().frozenQuit(p);
    }

    // ================================================================= chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (plugin.input().handle(p, text)) {
            e.setCancelled(true);
            return;
        }
        if (p.hasPermission("smpadmin.staffchat")) {
            String sc = plugin.settings().staffChatShortcut;
            boolean shortcut = sc != null && !sc.isEmpty() && text.startsWith(sc) && text.length() > sc.length();
            if (shortcut || plugin.state().staffChat.contains(p.getUniqueId())) {
                e.setCancelled(true);
                plugin.actions().staffChat(p.getName(), shortcut ? text.substring(sc.length()).trim() : text);
                return;
            }
        }
        Punishment mute = plugin.sanctions().activeMute(p.getUniqueId());
        if (mute != null) {
            e.setCancelled(true);
            plugin.texts().send(p, "mute.blocked", plugin.actions().resolvers(mute));
            return;
        }
        if (plugin.state().chatLocked && !p.hasPermission("smpadmin.chat.bypass")) {
            e.setCancelled(true);
            plugin.texts().send(p, "chat.locked-msg");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (plugin.actions().isFrozen(p.getUniqueId()) && !plugin.settings().freezeAllowed.contains(label)) {
            e.setCancelled(true);
            plugin.texts().send(p, "freeze.blocked");
            return;
        }
        if (plugin.settings().muteBlocked.contains(label)) {
            Punishment mute = plugin.sanctions().activeMute(p.getUniqueId());
            if (mute != null) {
                e.setCancelled(true);
                plugin.texts().send(p, "mute.blocked", plugin.actions().resolvers(mute));
            }
        }
    }

    // ================================================================= freeze

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.actions().isFrozen(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        e.setTo(back);
    }

    private boolean frozen(Player p) {
        if (!plugin.actions().isFrozen(p.getUniqueId())) return false;
        plugin.texts().send(p, "freeze.blocked");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (plugin.actions().isFrozen(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        // en vanish : ne pas déclencher les plaques de pression / fils
        if (e.getAction() == Action.PHYSICAL && plugin.vanish().is(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player victim) {
            if (plugin.actions().isFrozen(victim.getUniqueId())) {
                e.setCancelled(true);
                return;
            }
            if (plugin.settings().vanishGod && plugin.vanish().is(victim)) {
                e.setCancelled(true);
                return;
            }
        }
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = null;
            if (ed.getDamager() instanceof Player p) attacker = p;
            else if (ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
            if (attacker != null && plugin.actions().isFrozen(attacker.getUniqueId())) e.setCancelled(true);
        }
    }

    // ================================================================= vanish

    @EventHandler(priority = EventPriority.HIGH)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && plugin.vanish().is(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && plugin.settings().noPickup && plugin.vanish().is(p)) e.setCancelled(true);
    }

    // ================================================================= menus et invsee

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof Menu menu) {
            e.setCancelled(true);
            if (e.getClickedInventory() == top) menu.click(e.getSlot(), e.getClick());
            return;
        }
        if (readOnly.contains(e.getWhoClicked().getUniqueId())) {
            if (e.getClickedInventory() == top || e.isShiftClick()) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu || readOnly.contains(e.getWhoClicked().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        readOnly.remove(e.getPlayer().getUniqueId());
    }
}
