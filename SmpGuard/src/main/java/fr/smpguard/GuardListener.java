package fr.smpguard;

import fr.smpguard.gui.Menu;
import fr.smpguard.check.AutoTotemCheck;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

final class GuardListener implements Listener {

    private final SmpGuard plugin;

    GuardListener(SmpGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.join(e.getPlayer());
        plugin.client().onJoin(e.getPlayer());
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
        plugin.oreBait().onBreak(e.getPlayer(), e.getBlock());
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

    // ---------------------------------------------------------------- auto-totem

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p) plugin.autoTotem().onPop(p);
    }

    @EventHandler
    public void onInput(PlayerInputEvent e) {
        plugin.autoTotem().onInputChange(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        ItemStack toOffhand = e.getOffHandItem();
        boolean totem = toOffhand != null && toOffhand.getType() == org.bukkit.Material.TOTEM_OF_UNDYING;
        plugin.autoTotem().onTotemAction(e.getPlayer(), e, totem, false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTotemClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof Menu) return;
        ItemStack hotbar = null;
        if (e.getHotbarButton() >= 0) hotbar = p.getInventory().getItem(e.getHotbarButton());
        boolean totem = AutoTotemCheck.involvesTotem(e.getCurrentItem(), e.getCursor(), hotbar);
        plugin.autoTotem().onTotemAction(p, e, totem, AutoTotemCheck.isOwnInventory(e.getView().getTopInventory().getType()));
    }

    // ---------------------------------------------------------------- mods / clients interdits

    @EventHandler
    public void onChannel(org.bukkit.event.player.PlayerRegisterChannelEvent e) {
        plugin.client().onChannel(e.getPlayer(), e.getChannel());
    }

    // ---------------------------------------------------------------- à travers les murs (freecam)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(org.bukkit.event.player.PlayerInteractEvent e) {
        plugin.wall().onInteract(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakCheck(BlockBreakEvent e) {
        plugin.wall().onBreak(e);
        if (!e.isCancelled()) plugin.breaks().onBreak(e);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(org.bukkit.event.block.BlockDamageEvent e) {
        plugin.breaks().onDamage(e);
    }

    // ---------------------------------------------------------------- combat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        plugin.combat().onAttack(e);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (e.getAnimationType() == org.bukkit.event.player.PlayerAnimationType.ARM_SWING) plugin.combat().onSwing(e.getPlayer());
    }

    // ---------------------------------------------------------------- déplacements : poussées légitimes

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(org.bukkit.event.player.PlayerVelocityEvent e) {
        plugin.move().onPush(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) plugin.move().onPush(p);
    }

    /** Destination demandée par le client, avant que les autres plugins y touchent. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMoveFirst(org.bukkit.event.player.PlayerMoveEvent e) {
        Profile prof = plugin.profile(e.getPlayer());
        if (prof != null) prof.intendedTo = e.getTo().clone();
    }

    /** Un plugin a annulé ou changé le déplacement (gel, cinématique, zone interdite...) : ce n'est pas un freecam. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMoveLast(org.bukkit.event.player.PlayerMoveEvent e) {
        Profile prof = plugin.profile(e.getPlayer());
        if (prof == null || prof.intendedTo == null) return;
        org.bukkit.Location want = prof.intendedTo;
        prof.intendedTo = null;
        if (e.isCancelled() || want.getWorld() != e.getTo().getWorld() || want.distanceSquared(e.getTo()) > 1.0E-4) {
            prof.moveBlockedAt = System.currentTimeMillis();
        }
    }

    /** Charge de vent (seule ou combo masse) : projette sans dégâts ni événement de vitesse. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWindCharge(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.AbstractWindCharge)) return;
        for (Player p : e.getEntity().getLocation().getNearbyPlayers(6)) plugin.move().onPush(p);
    }

    /** Explosions (TNT, creeper, charge de vent...) : le souffle pousse aussi ceux qu'il ne blesse pas. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        for (Player p : e.getLocation().getNearbyPlayers(10)) plugin.move().onPush(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        for (Player p : e.getBlock().getLocation().getNearbyPlayers(10)) plugin.move().onPush(p);
    }

    /** Coup de masse : le tapeur rebondit et l'onde de choc projette les joueurs autour de la cible. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMace(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || p.getInventory().getItemInMainHand().getType() != org.bukkit.Material.MACE) return;
        plugin.move().onPush(p);
        for (Player near : e.getEntity().getLocation().getNearbyPlayers(5)) plugin.move().onPush(near);
    }

    /** Fin de vol en élytres : il garde son élan quelques instants. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(org.bukkit.event.entity.EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) plugin.move().onPush(p);
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
