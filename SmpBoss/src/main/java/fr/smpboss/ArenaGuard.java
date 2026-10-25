package fr.smpboss;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;

import java.util.Collection;
import java.util.Locale;

import static fr.smpboss.SmpBoss.parse;

/**
 * Protection de l'arène : pas de casse ni de pose, pas de PvP, pas de monstres (sauf le boss et ses serviteurs),
 * pas de feu / explosions / coulées, et la lave ou le magma du décor ne font pas de dégâts (sauvetage).
 * Les admins (smpboss.arena.bypass) peuvent construire.
 */
final class ArenaGuard implements Listener {

    private final SmpBoss plugin;
    private final Arena arena;
    private final String ns;

    ArenaGuard(SmpBoss plugin, Arena arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.ns = plugin.getName().toLowerCase(Locale.ROOT);
    }

    private boolean on(String key) {
        return plugin.getConfig().getBoolean("arena.protection.enabled", true) && plugin.getConfig().getBoolean("arena.protection." + key, true);
    }

    private boolean ours(Entity e) {
        for (NamespacedKey k : e.getPersistentDataContainer().getKeys()) if (k.getNamespace().equals(ns)) return true;
        return false;
    }

    private boolean builder(Player p) {
        return p != null && p.hasPermission("smpboss.arena.bypass");
    }

    private void deny(Player p) {
        p.sendActionBar(parse("<red>Arène du boss : zone protégée."));
    }

    // ================================================================= blocs

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (on("blocks") && !builder(e.getPlayer()) && arena.contains(e.getBlock())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (on("blocks") && !builder(e.getPlayer()) && arena.contains(e.getBlock())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (on("blocks") && !builder(e.getPlayer()) && arena.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (on("blocks") && !builder(e.getPlayer()) && arena.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !on("blocks") || builder(e.getPlayer()) || !arena.contains(b)) return;
        if (plugin.isAltar(b)) return; // l'autel du boss reste utilisable
        if (e.getAction() == Action.PHYSICAL) {
            e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // pas de briquet, pelle (feu de camp), hache (écorcer), houe, poudre d'os, oeufs... ; manger / boire reste possible
        e.setUseInteractedBlock(Event.Result.DENY);
        Material m = e.getItem() == null ? Material.AIR : e.getItem().getType();
        String n = m.name();
        if (m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE || m == Material.BONE_MEAL || n.endsWith("_SHOVEL")
                || n.endsWith("_AXE") || n.endsWith("_HOE") || n.endsWith("_SPAWN_EGG") || n.endsWith("BUCKET")
                || m == Material.END_CRYSTAL || m == Material.ARMOR_STAND || n.endsWith("_BOAT") || n.endsWith("MINECART")
                || m == Material.ITEM_FRAME || m == Material.GLOW_ITEM_FRAME || m == Material.PAINTING) {
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (on("blocks") && !builder(e.getPlayer()) && arena.contains(e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (on("blocks") && !builder(e.getPlayer()) && arena.contains(e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = e.getRemover() instanceof Player pl ? pl : null;
        if (on("blocks") && !builder(p) && arena.contains(e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (on("blocks")) e.blockList().removeIf(arena::contains);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (on("blocks")) e.blockList().removeIf(arena::contains);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) {
        if (!on("blocks") || !arena.contains(e.getBlock())) return;
        if (e.getEntity() instanceof Player p && builder(p)) return;
        if (!ours(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!on("blocks") || !arena.contains(e.getBlock())) return;
        if (e.getPlayer() != null && builder(e.getPlayer())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (on("blocks") && arena.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (on("blocks") && arena.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (on("blocks") && (arena.contains(e.getToBlock()) || arena.contains(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (on("blocks") && arena.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (on("blocks") && arena.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        if (on("blocks") && !builder(e.getPlayer()) && arena.contains(e.getBlock())) e.setCancelled(true);
    }

    // ================================================================= PvP

    /** Le joueur derrière un coup (flèche, loup, TNT, nuage de potion...). */
    private static Player attacker(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (d instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        if (d instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !on("pvp")) return;
        Player by = attacker(e.getDamager());
        boolean inside = arena.contains(victim.getLocation()) || by != null && arena.contains(by.getLocation());
        if (!inside) return;
        if (by != null && !by.equals(victim)) {
            e.setCancelled(true);
            return;
        }
        // cristaux / lits / ancres posés par un joueur : pas dans l'arène
        if (e.getDamager() instanceof EnderCrystal) e.setCancelled(true);
    }

    private static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect pe : effects) if (pe.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!on("pvp") || !(e.getPotion().getShooter() instanceof Player thrower)) return;
        if (!harmful(e.getPotion().getEffects())) return;
        for (LivingEntity le : e.getAffectedEntities()) {
            if (le instanceof Player p && !p.equals(thrower) && (arena.contains(p.getLocation()) || arena.contains(thrower.getLocation()))) {
                e.setIntensity(le, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent e) {
        AreaEffectCloud c = e.getEntity();
        if (!on("pvp") || !(c.getSource() instanceof Player thrower)) return;
        boolean bad = harmful(c.getCustomEffects()) || c.getBasePotionType() != null && harmful(c.getBasePotionType().getPotionEffects());
        if (!bad) return;
        e.getAffectedEntities().removeIf(le -> le instanceof Player p && !p.equals(thrower) && arena.contains(p.getLocation()));
    }

    // ================================================================= lave / magma du décor

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !arena.contains(p.getLocation())) return;
        var cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.HOT_FLOOR && on("decor-safe")) {
            e.setCancelled(true);
            return;
        }
        if (cause == EntityDamageEvent.DamageCause.LAVA && on("lava-rescue")) {
            e.setCancelled(true);
            Location to = arena.safeSpot(p.getLocation());
            if (to == null) return;
            Arena.allowTeleport(p);
            p.teleport(to);
            p.setFallDistance(0);
            p.setFireTicks(0);
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> p.setFireTicks(0), 1L);
            p.sendActionBar(parse("<gold>La lave te recrache dans l'arène…"));
        }
    }

    // ================================================================= monstres

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!on("mobs") || ours(e.getEntity()) || !arena.contains(e.getLocation())) return;
        var r = e.getSpawnReason();
        // les apparitions par plugin / commande passent (boss, cosmétiques...) ; tout le reste est bloqué
        if (r == CreatureSpawnEvent.SpawnReason.CUSTOM || r == CreatureSpawnEvent.SpawnReason.COMMAND) return;
        e.setCancelled(true);
    }

    /** Toutes les 5 s : les monstres qui sont entrés à pied disparaissent. */
    void sweep() {
        if (!on("mobs") || !arena.defined()) return;
        World w = arena.world();
        Location c = arena.center();
        if (w == null || c == null) return;
        if (w.getPlayers().stream().noneMatch(p -> arena.contains(p.getLocation()))) return;
        for (Entity en : w.getNearbyEntities(c, plugin.getConfig().getInt("arena.sweep-radius", arena.floorRadius() + 24), 40,
                plugin.getConfig().getInt("arena.sweep-radius", arena.floorRadius() + 24))) {
            if (!(en instanceof Enemy) || en instanceof Player || ours(en) || en.customName() != null) continue;
            if (en instanceof LivingEntity le && le.isLeashed()) continue;
            if (!arena.contains(en.getLocation())) continue;
            en.remove();
        }
    }
}
