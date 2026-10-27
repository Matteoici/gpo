package fr.smpzones;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applique les permissions des zones. Priorité HIGH : les plugins qui passent en HIGHEST
 * (stands du marché, arène du boss...) peuvent encore autoriser leurs propres joueurs.
 */
final class ZoneListener implements Listener {

    private final SmpZones plugin;
    private final Map<UUID, Long> warned = new HashMap<>();

    ZoneListener(SmpZones plugin) {
        this.plugin = plugin;
    }

    private boolean deny(Player p, Location l, Flag f) {
        if (plugin.allowed(p, l, f)) return false;
        if (p != null) warn(p, f);
        return true;
    }

    private void warn(Player p, Flag f) {
        long now = System.currentTimeMillis();
        Long last = warned.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        warned.put(p.getUniqueId(), now);
        p.sendActionBar(SmpZones.parse(plugin.msg("denied." + f.key, plugin.msg("denied.default", "<red>✖ Interdit ici."))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        warned.remove(e.getPlayer().getUniqueId());
        plugin.forgetPlayer(e.getPlayer());
    }

    // ================================================================= blocs

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation(), Flag.CASSER)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation(), Flag.POSER)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Location at = e.getBlockClicked().getRelative(e.getBlockFace()).getLocation();
        if (deny(e.getPlayer(), at, Flag.SEAUX)) {
            e.setCancelled(true);
            return;
        }
        // anti-flood : aucun seau près du spawn (sauf si une zone autorise les seaux explicitement)
        if (plugin.antiFlood(e.getPlayer(), at)) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(SmpZones.parse(plugin.msg("anti-flood", "<red>Pas de seaux près du spawn.")
                    .replace("<radius>", String.valueOf(plugin.floodRadius()))));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (deny(e.getPlayer(), e.getBlockClicked().getLocation(), Flag.SEAUX)) e.setCancelled(true);
    }

    /** Distributeur qui vide un seau près du spawn : même règle anti-flood. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        Material m = e.getItem().getType();
        if (!m.name().endsWith("_BUCKET") || m == Material.BUCKET || m == Material.MILK_BUCKET) return;
        if (plugin.antiFlood(null, e.getBlock().getLocation())) e.setCancelled(true);
        else if (!plugin.allowed(null, e.getBlock().getLocation(), Flag.LIQUIDES)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        if (e.getAction() == Action.PHYSICAL) {
            if (b.getBlockData() instanceof Farmland) {
                if (deny(null, b.getLocation(), Flag.PIETINEMENT) && !plugin.bypass(p)) e.setCancelled(true);
            } else if (deny(p, b.getLocation(), Flag.INTERAGIR)) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Location l = b.getLocation();
        // allumer un feu
        if (e.getItem() != null && (e.getItem().getType() == Material.FLINT_AND_STEEL || e.getItem().getType() == Material.FIRE_CHARGE)
                && deny(p, l, Flag.FEU)) {
            e.setUseItemInHand(Event.Result.DENY);
            e.setCancelled(true);
            return;
        }
        if (b.getState(false) instanceof InventoryHolder) {
            if (deny(p, l, Flag.COFFRES)) e.setUseInteractedBlock(Event.Result.DENY);
        } else if (b.getType().isInteractable()) {
            if (deny(p, l, Flag.INTERAGIR)) e.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Location to = e.getToBlock().getLocation();
        if (!plugin.allowed(null, to, Flag.LIQUIDES)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null) {
            if (deny(e.getPlayer(), e.getBlock().getLocation(), Flag.FEU)) e.setCancelled(true);
        } else if (!plugin.allowed(null, e.getBlock().getLocation(), Flag.FEU)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!plugin.allowed(null, e.getBlock().getLocation(), Flag.FEU)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getSource().getType() == Material.FIRE || e.getSource().getType() == Material.SOUL_FIRE) {
            if (!plugin.allowed(null, e.getBlock().getLocation(), Flag.FEU)) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        if (!plugin.allowed(null, e.getBlock().getLocation(), Flag.FEUILLES)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        Material m = e.getNewState().getType();
        if ((m == Material.SNOW || m == Material.ICE || m == Material.FROSTED_ICE) && !plugin.allowed(null, e.getBlock().getLocation(), Flag.NEIGE_GLACE)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        Material m = e.getBlock().getType();
        if ((m == Material.SNOW || m == Material.ICE || m == Material.SNOW_BLOCK) && !plugin.allowed(null, e.getBlock().getLocation(), Flag.NEIGE_GLACE)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player) return;
        Location l = e.getBlock().getLocation();
        if (e.getBlock().getType() == Material.FARMLAND) {
            if (!plugin.allowed(null, l, Flag.PIETINEMENT)) e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof org.bukkit.entity.FallingBlock) return; // sable / gravier qui tombent
        if (!plugin.allowed(null, l, Flag.MOBS_CASSENT)) e.setCancelled(true);
    }

    /** Le paysage près du spawn : les explosions (TNT, creepers, machines...) n'y cassent aucun bloc. */
    private boolean keepLandscape(Location l) {
        if (!plugin.getConfig().getBoolean("anti-flood.no-explosion-damage", true) || !plugin.inSpawnRadius(l)) return false;
        return !plugin.allowedExplicit(l, Flag.EXPLOSIONS);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> !plugin.allowed(null, b.getLocation(), Flag.EXPLOSIONS) || keepLandscape(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> !plugin.allowed(null, b.getLocation(), Flag.EXPLOSIONS) || keepLandscape(b.getLocation()));
    }

    /** Machines volantes / mangeuses de monde : près du spawn, un piston ne déplace pas de slime ni de miel. */
    private boolean flyingMachine(Block piston, List<Block> moved) {
        if (!plugin.getConfig().getBoolean("anti-flood.no-flying-machines", true) || !plugin.inSpawnRadius(piston.getLocation())) return false;
        for (Block b : moved) {
            Material m = b.getType();
            if (m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonOut(org.bukkit.event.block.BlockPistonExtendEvent e) {
        if (flyingMachine(e.getBlock(), e.getBlocks())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonIn(org.bukkit.event.block.BlockPistonRetractEvent e) {
        if (flyingMachine(e.getBlock(), e.getBlocks())) e.setCancelled(true);
    }

    // ================================================================= entités

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null && deny(e.getPlayer(), e.getEntity().getLocation(), Flag.POSER)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        if (p != null) {
            if (deny(p, e.getEntity().getLocation(), Flag.ENTITES)) e.setCancelled(true);
        } else if (e.getCause() == org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.EXPLOSION
                && !plugin.allowed(null, e.getEntity().getLocation(), Flag.EXPLOSIONS)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() != null && deny(e.getPlayer(), e.getEntity().getLocation(), Flag.POSER)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof Hanging) && !(t instanceof ArmorStand)) return;
        if (deny(e.getPlayer(), t.getLocation(), Flag.ENTITES)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (deny(e.getPlayer(), e.getRightClicked().getLocation(), Flag.ENTITES)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicle(VehicleDestroyEvent e) {
        Player p = attacker(e.getAttacker());
        if (p != null && deny(p, e.getVehicle().getLocation(), Flag.VEHICULES)) e.setCancelled(true);
    }

    private static Player attacker(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    // ================================================================= dégâts

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        Location l = victim.getLocation();
        var cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return;
        Player by = e instanceof EntityDamageByEntityEvent ed ? attacker(ed.getDamager()) : null;
        boolean explosion = cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if (victim instanceof Player pv) {
            if (!plugin.allowed(null, l, Flag.DEGATS)) {
                e.setCancelled(true);
                return;
            }
            if (cause == EntityDamageEvent.DamageCause.FALL && !plugin.allowed(null, l, Flag.CHUTE)) {
                e.setCancelled(true);
                return;
            }
            if (explosion && !plugin.allowed(null, l, Flag.EXPLOSIONS)) {
                e.setCancelled(true);
                return;
            }
            if (by != null && !by.equals(pv)) {
                // il faut que le PvP soit permis là où est la victime ET là où est l'attaquant
                if (!plugin.allowed(null, l, Flag.PVP) || !plugin.allowed(null, by.getLocation(), Flag.PVP)) {
                    e.setCancelled(true);
                    warn(by, Flag.PVP);
                }
                return;
            }
            if (e instanceof EntityDamageByEntityEvent ed && (ed.getDamager() instanceof Enemy
                    || ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Enemy)) {
                if (!plugin.allowed(null, l, Flag.ATTAQUE_MONSTRES)) e.setCancelled(true);
            }
            return;
        }
        // cadres, porte-armures, animaux : protégés des joueurs
        if (by != null && (victim instanceof Hanging || victim instanceof ArmorStand || victim instanceof Animals
                || victim instanceof org.bukkit.entity.Villager)) {
            if (deny(by, l, Flag.ENTITES)) e.setCancelled(true);
            return;
        }
        if (explosion && (victim instanceof Hanging || victim instanceof ArmorStand) && !plugin.allowed(null, l, Flag.EXPLOSIONS)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getFoodLevel() >= e.getEntity().getFoodLevel()) return; // manger : toujours permis
        if (!plugin.allowed(null, e.getEntity().getLocation(), Flag.FAIM)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        var r = e.getSpawnReason();
        boolean natural = switch (r) {
            case NATURAL, SPAWNER, REINFORCEMENTS, PATROL, RAID, VILLAGE_INVASION, JOCKEY, MOUNT, TRAP, DEFAULT -> true;
            default -> false;
        };
        if (!natural) return;
        Location l = e.getLocation();
        if (e.getEntity() instanceof Enemy) {
            if (!plugin.allowed(null, l, Flag.MONSTRES)) e.setCancelled(true);
        } else if (e.getEntity() instanceof Animals) {
            if (!plugin.allowed(null, l, Flag.ANIMAUX)) e.setCancelled(true);
        }
    }

    // ================================================================= objets, perles, élytres

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (deny(e.getPlayer(), e.getPlayer().getLocation(), Flag.JETER)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !plugin.allowed(p, e.getItem().getLocation(), Flag.RAMASSER)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl && e.getEntity().getShooter() instanceof Player p
                && deny(p, p.getLocation(), Flag.PERLES)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        String c = e.getCause().name();
        Player p = e.getPlayer();
        if (c.equals("ENDER_PEARL")) {
            if (deny(p, e.getTo(), Flag.PERLES) || deny(p, e.getFrom(), Flag.PERLES)) e.setCancelled(true);
        } else if (c.equals("CHORUS_FRUIT") || c.equals("CONSUMABLE_EFFECT")) {
            if (deny(p, e.getTo(), Flag.CHORUS) || deny(p, e.getFrom(), Flag.CHORUS)) e.setCancelled(true);
        }
        if (!e.isCancelled() && !plugin.bypass(p) && !plugin.allowed(p, e.getTo(), Flag.ENTRER)
                && !List.of("COMMAND", "PLUGIN", "UNKNOWN").contains(c)) {
            e.setCancelled(true);
            warn(p, Flag.ENTRER);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && deny(p, p.getLocation(), Flag.ELYTRES)) e.setCancelled(true);
    }

    // ================================================================= entrée / messages

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Player p = e.getPlayer();
        if (!plugin.bypass(p) && !plugin.allowed(p, to, Flag.ENTRER) && plugin.allowed(p, from, Flag.ENTRER)) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
            Vector push = from.toVector().subtract(to.toVector()).setY(0);
            if (push.lengthSquared() > 1e-4) p.setVelocity(push.normalize().multiply(0.4).setY(0.1));
            warn(p, Flag.ENTRER);
            return;
        }
        plugin.moved(p, to);
    }
}
