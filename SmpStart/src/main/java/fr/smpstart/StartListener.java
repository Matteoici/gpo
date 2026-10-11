package fr.smpstart;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/** Tous les événements du jeu qui font avancer le guide + protection débutant + objet « guide ». */
public final class StartListener implements Listener {

    private final SmpStart plugin;

    StartListener(SmpStart plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ connexion

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.welcome().onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.bars().forget(p.getUniqueId());
        plugin.welcome().forget(p.getUniqueId());
        plugin.data().saveIfDirty();
    }

    // ------------------------------------------------------------ étapes

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        plugin.progress().add(p, StepType.BREAK, e.getBlock().getType().name(), false, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        plugin.progress().add(p, StepType.PLACE, e.getBlockPlaced().getType().name(), false, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        plugin.progress().add(p, StepType.CRAFT, result.getType().name(), false, Math.max(1, result.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        boolean hostile = e.getEntity() instanceof Monster;
        plugin.progress().add(killer, StepType.KILL, e.getEntity().getType().name(), hostile, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String line = e.getMessage();
        if (line.startsWith("/")) line = line.substring(1);
        line = line.trim();
        if (line.isEmpty()) return;
        // retire un éventuel "plugin:" devant la commande (ex. essentials:sethome)
        int space = line.indexOf(' ');
        String label = space < 0 ? line : line.substring(0, space);
        int colon = label.indexOf(':');
        if (colon >= 0) line = label.substring(colon + 1) + (space < 0 ? "" : line.substring(space));
        plugin.progress().add(e.getPlayer(), StepType.COMMAND, line.toLowerCase(Locale.ROOT), false, 1);
    }

    // ------------------------------------------------------------ objet « guide »

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        if (!it.getItemMeta().getPersistentDataContainer().has(plugin.guideKey(), PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        new GuideMenu(plugin, e.getPlayer()).open();
    }

    // ------------------------------------------------------------ protection débutant

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        if (protectedPlayer(victim)) {
            e.setCancelled(true);
            attacker.sendActionBar(plugin.texts().get("protection.victim", Texts.ph("player", victim.getName())));
        } else if (protectedPlayer(attacker)) {
            e.setCancelled(true);
            attacker.sendActionBar(plugin.texts().get("protection.attacker"));
        }
    }

    private boolean protectedPlayer(Player p) {
        PlayerData d = plugin.data().get(p.getUniqueId());
        return d != null && d.protectionSec > 0;
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    // ------------------------------------------------------------ chat (accueil des nouveaux)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        plugin.welcome().onChat(e.getPlayer(), PlainTextComponentSerializer.plainText().serialize(e.message()));
    }
}
