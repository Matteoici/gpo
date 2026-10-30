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

    // ================================================================= élytres / charges de vent

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGlide(org.bukkit.event.entity.EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && on("no-elytra") && arena.contains(p.getLocation())) {
            e.setCancelled(true);
            p.sendActionBar(parse("<red>Pas d'élytres dans l'arène."));
        }
    }

    private boolean windCharge(org.bukkit.inventory.ItemStack it) {
        return it != null && it.getType() == Material.WIND_CHARGE;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onWindUse(PlayerInteractEvent e) {
        if (!on("no-wind-charge") || !windCharge(e.getItem()) || !arena.contains(e.getPlayer().getLocation())) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.getPlayer().sendActionBar(parse("<red>Pas de charges de vent dans l'arène."));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.AbstractWindCharge && e.getEntity().getShooter() instanceof Player p
                && on("no-wind-charge") && arena.contains(p.getLocation())) {
            e.setCancelled(true);
        }
    }

    // ================================================================= lances (spear)

    private static boolean spear(org.bukkit.inventory.ItemStack it) {
        return it != null && it.getType().name().endsWith("_SPEAR");
    }

    private void spearDenied(Player p) {
        p.sendActionBar(parse("<red>Pas de lances dans l'arène."));
    }

    /** Si le joueur tient une lance, on passe sur un autre emplacement de sa barre. */
    private void dropSpearFromHand(Player p) {
        if (!on("no-spear") || !spear(p.getInventory().getItemInMainHand())) return;
        var inv = p.getInventory();
        int cur = inv.getHeldItemSlot();
        for (int i = 1; i < 9; i++) {
            int s = (cur + i) % 9;
            if (!spear(inv.getItem(s))) {
                inv.setHeldItemSlot(s);
                spearDenied(p);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpearHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!on("no-spear") || !arena.contains(p.getLocation()) || !spear(p.getInventory().getItem(e.getNewSlot()))) return;
        e.setCancelled(true);
        spearDenied(p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpearSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (!on("no-spear") || !arena.contains(e.getPlayer().getLocation())) return;
        if (spear(e.getMainHandItem())) {
            e.setCancelled(true);
            spearDenied(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSpearUse(PlayerInteractEvent e) {
        if (!on("no-spear") || !spear(e.getItem()) || !arena.contains(e.getPlayer().getLocation())) return;
        e.setCancelled(true);
        e.setUseItemInHand(Event.Result.DENY);
        dropSpearFromHand(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpearHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !on("no-spear") || !arena.contains(p.getLocation())) return;
        if (!spear(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        dropSpearFromHand(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpearClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !on("no-spear") || !arena.contains(p.getLocation())) return;
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && arena.contains(p.getLocation())) dropSpearFromHand(p);
        });
    }

    // ================================================================= ender pearls

    @EventHandler(priority = EventPriority.LOW)
    public void onPearlUse(PlayerInteractEvent e) {
        if (!on("no-ender-pearl") || e.getItem() == null || e.getItem().getType() != Material.ENDER_PEARL
                || !arena.contains(e.getPlayer().getLocation())) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.getPlayer().sendActionBar(parse("<red>Pas d'ender pearls dans l'arène."));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPearlLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.EnderPearl && e.getEntity().getShooter() instanceof Player p
                && on("no-ender-pearl") && arena.contains(p.getLocation())) {
            e.setCancelled(true);
        }
    }

    /** Une perle lancée de dehors ne fait pas entrer (ni sortir) de l'arène. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPearlTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (e.getCause() != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL || !on("no-ender-pearl")) return;
        if (arena.contains(e.getTo()) || arena.contains(e.getFrom())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(parse("<red>Pas d'ender pearls dans l'arène."));
        }
    }

    // ================================================================= potions de saut (combos masse)

    private boolean blockedEffect(org.bukkit.potion.PotionEffectType t) {
        if (t == null || !on("no-jump-effects")) return false;
        String key = t.getKey().getKey();
        for (String s : plugin.getConfig().getStringList("arena.protection.blocked-effects")) {
            if (s.equalsIgnoreCase(key) || s.equalsIgnoreCase("minecraft:" + key)) return true;
        }
        return false;
    }

    /** Retire les effets interdits (saut...) d'un joueur dans l'arène. */
    private void stripEffects(Player p) {
        boolean any = false;
        for (org.bukkit.potion.PotionEffect pe : p.getActivePotionEffects()) {
            if (blockedEffect(pe.getType())) {
                p.removePotionEffect(pe.getType());
                any = true;
            }
        }
        if (any) p.sendActionBar(parse("<red>Les potions de saut ne marchent pas dans l'arène."));
    }

    /** Pas de lévitation venue de flèches / potions dans l'arène (ni sur les joueurs, ni sur le boss, ni sur ses monstres). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLevitation(org.bukkit.event.entity.EntityPotionEffectEvent e) {
        if (e.getModifiedType() != org.bukkit.potion.PotionEffectType.LEVITATION || e.getNewEffect() == null) return;
        var c = e.getCause();
        if (c != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ARROW && c != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.POTION_SPLASH
                && c != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD && c != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.POTION_DRINK) return;
        if (!arena.contains(e.getEntity().getLocation())) return;
        e.setCancelled(true);
    }

    /** Les flèches de lévitation tirées dans l'arène perdent leur effet. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArrow(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Arrow a) || !arena.contains(a.getLocation())) return;
        stripLevitation(a);
    }

    static void stripLevitation(org.bukkit.entity.Arrow a) {
        try {
            org.bukkit.potion.PotionType base = a.getBasePotionType();
            if (base != null) {
                for (PotionEffect pe : base.getPotionEffects()) {
                    if (pe.getType() == org.bukkit.potion.PotionEffectType.LEVITATION) {
                        a.setBasePotionType(null);
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        a.removeCustomEffect(org.bukkit.potion.PotionEffectType.LEVITATION);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(org.bukkit.event.entity.EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getNewEffect() == null || !blockedEffect(e.getModifiedType())) return;
        if (e.getAction() != org.bukkit.event.entity.EntityPotionEffectEvent.Action.ADDED
                && e.getAction() != org.bukkit.event.entity.EntityPotionEffectEvent.Action.CHANGED) return;
        if (!arena.contains(p.getLocation())) return;
        e.setCancelled(true);
        p.sendActionBar(parse("<red>Les potions de saut ne marchent pas dans l'arène."));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportIn(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (!arena.contains(e.getTo())) return;
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getPlayer().isOnline() && arena.contains(e.getPlayer().getLocation())) {
                stripEffects(e.getPlayer());
                dropSpearFromHand(e.getPlayer());
            }
        });
    }

    // ================================================================= portail du spawn (entrée)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        Location t = e.getTo();
        if (e.getFrom().getBlockX() == t.getBlockX() && e.getFrom().getBlockY() == t.getBlockY() && e.getFrom().getBlockZ() == t.getBlockZ()) return;
        arena.checkPortal(e.getPlayer(), t);
        if (arena.contains(t)) {
            stripEffects(e.getPlayer());
            dropSpearFromHand(e.getPlayer());
        }
        // gouffre du nid de la Reine : les toiles te remontent
        if (arena.inAbyss(t)) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (e.getPlayer().isOnline()) arena.lavaRescue(e.getPlayer());
            });
        }
        // lave dans l'arène : direct au spawn de l'arène (même avec résistance au feu ou protection d'arrivée)
        if (on("lava-rescue") && arena.contains(t) && !arena.inPortal(t)
                && (t.getBlock().getType() == org.bukkit.Material.LAVA || t.clone().add(0, 0.6, 0).getBlock().getType() == org.bukkit.Material.LAVA)) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (e.getPlayer().isOnline()) arena.lavaRescue(e.getPlayer());
            });
        }
    }

    // ================================================================= portail de sortie

    @EventHandler(priority = EventPriority.LOW)
    public void onPortalTouch(org.bukkit.event.entity.EntityPortalEnterEvent e) {
        if (!arena.contains(e.getLocation())) return;
        if (e.getEntity() instanceof Player p) arena.exit(p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortal(org.bukkit.event.player.PlayerPortalEvent e) {
        if (arena.contains(e.getFrom())) e.setCancelled(true); // on sort par /spawn, pas vers le Nether
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPortal(org.bukkit.event.entity.EntityPortalEvent e) {
        if (arena.contains(e.getFrom())) e.setCancelled(true);
    }

    // ================================================================= lave / magma du décor

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        var cause = e.getCause();
        boolean burn = cause == EntityDamageEvent.DamageCause.LAVA || cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK;
        // lave dans la zone portail : on passe dans l'arène sans brûler
        if (burn && arena.inPortal(p.getLocation())) {
            e.setCancelled(true);
            p.setFireTicks(0);
            arena.checkPortal(p, p.getLocation());
            return;
        }
        if (!arena.contains(p.getLocation())) return;
        if (cause == EntityDamageEvent.DamageCause.LAVA && on("lava-rescue")) {
            e.setCancelled(true);
            arena.lavaRescue(p);
            return;
        }
        if (arena.isProtected(p)) {
            e.setCancelled(true); // protection d'arrivée : rien ne le touche (gardes, explosions, chute...)
            return;
        }
        if (cause == EntityDamageEvent.DamageCause.HOT_FLOOR && on("decor-safe")) {
            e.setCancelled(true);
        }
    }

    // ================================================================= mort dans l'arène

    /** Mourir dans l'arène ne fait rien perdre (inventaire et niveaux gardés). */
    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!arena.contains(p.getLocation()) || !plugin.getConfig().getBoolean("arena.keep-inventory", true)) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
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
