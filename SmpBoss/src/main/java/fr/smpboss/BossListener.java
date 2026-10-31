package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.event.player.PlayerItemDamageEvent;

import static fr.smpboss.SmpBoss.parse;

final class BossListener implements Listener {
    private final java.util.Map<java.util.UUID, Long> cheeseWarn = new java.util.HashMap<>();


    private final SmpBoss plugin;
    private final NamespacedKey spawnerKey;
    private final Map<UUID, Long> sickleCooldown = new HashMap<>();

    BossListener(SmpBoss plugin) {
        this.plugin = plugin;
        this.spawnerKey = new NamespacedKey(plugin, "from_spawner");
    }

    private boolean ours(Entity e) {
        for (NamespacedKey k : e.getPersistentDataContainer().getKeys()) if (k.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // ================================================================= apparitions (protections du spawn)

    /** WorldGuard / limiteurs de mobs : le boss, ses serviteurs et nos affichages passent toujours. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(EntitySpawnEvent e) {
        if (e.isCancelled() && ours(e.getEntity())) e.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreature(CreatureSpawnEvent e) {
        var r = e.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.SPAWNER || r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            e.getEntity().getPersistentDataContainer().set(spawnerKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    // ================================================================= usure de l'armure

    /** Pendant le combat, l'armure s'use beaucoup moins (les attaques du boss touchent souvent). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWear(PlayerItemDamageEvent e) {
        Boss k = plugin.king();
        Player p = e.getPlayer();
        if (k == null || !k.active() || !k.inFight(p)) return;
        boolean armor = false;
        for (ItemStack a : p.getInventory().getArmorContents()) if (a != null && a.equals(e.getItem())) armor = true;
        if (!armor) return;
        double v = e.getDamage() * Math.max(0, plugin.getConfig().getDouble("boss.armor-wear", 0.25));
        int out = (int) v + (ThreadLocalRandom.current().nextDouble() < v - (int) v ? 1 : 0);
        if (out <= 0) e.setCancelled(true);
        else e.setDamage(out);
    }

    // ================================================================= dégâts au boss

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        Boss k = plugin.king();
        Entity ent = e.getEntity();
        if (ent instanceof Player p) plugin.relics().hurt(p);
        // cocons de la Toile Abyssale (Reine Araignée)
        if (k instanceof SpiderQueen q && q.isAnchor(ent)) {
            e.setCancelled(true);
            Player hb = null;
            if (e instanceof EntityDamageByEntityEvent ed) {
                if (ed.getDamager() instanceof Player pp) hb = pp;
                else if (ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pp) hb = pp;
            }
            if (hb != null) q.hitAnchor(ent, hb);
            return;
        }
        if (k == null || !k.isBoss(ent)) return;
        Player by = null;
        if (e instanceof EntityDamageByEntityEvent ed) {
            Entity d = ed.getDamager();
            if (d instanceof Player p) by = p;
            else if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) {
                // perles, boules de neige, œufs, charges de vent : ne touchent pas le Roi
                if (pr instanceof org.bukkit.entity.EnderPearl || pr instanceof org.bukkit.entity.Snowball || pr instanceof org.bukkit.entity.Egg
                        || pr instanceof org.bukkit.entity.AbstractWindCharge) {
                    e.setCancelled(true);
                    return;
                }
                // tirée depuis l'extérieur de la piste : ne compte pas, même si le tireur est descendu depuis
                if (pr.getPersistentDataContainer().has(offFloorKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
                    e.setCancelled(true);
                    SmpBoss.hud(p, SmpBoss.parse("<red>☠ Tir depuis l'extérieur de l'arène : le boss ne sent rien."));
                    return;
                }
                by = p;
            }
        }
        if (by == null || e.getDamage() <= 0) {
            e.setCancelled(true); // feu, chute, étouffement, lave, coups à 0... : rien (et ne compte pas comme combattant)
            return;
        }
        // un même joueur ne touche le boss qu'une fois par tick (la Reine a plusieurs zones de coups)
        int nowTick = org.bukkit.Bukkit.getCurrentTick();
        Integer lastTick = hitTick.put(by.getUniqueId(), nowTick);
        if (lastTick != null && lastTick == nowTick) {
            e.setCancelled(true);
            return;
        }
        // anti-cheese : on ne frappe le Roi que depuis la piste (pas du pont du portail, du muret, de dehors...)
        Arena ar = k.bossArena();
        if (ar != null && ar.defined() && plugin.getConfig().getBoolean("arena.protection.floor-only-damage", true)
                && ar.contains(e.getEntity().getLocation()) && !ar.onFightFloor(by.getLocation())) {
            e.setCancelled(true);
            Long last = cheeseWarn.get(by.getUniqueId());
            if (last == null || System.currentTimeMillis() - last > 2000) {
                cheeseWarn.put(by.getUniqueId(), System.currentTimeMillis());
                SmpBoss.hud(by, SmpBoss.parse("<red>☠ Le boss ne sent rien d'ici... <gold>descends dans l'arène</gold> pour le frapper."));
            }
            return;
        }
        if (ar != null) ar.unprotect(by);
        // dégâts bruts (épée + Tranchant + coup critique), sans l'armure du boss qui les réduisait d'un quart ;
        // bonus pour le corps à corps (plus risqué que l'arc)
        boolean melee = e instanceof EntityDamageByEntityEvent ed2 && ed2.getDamager() instanceof Player;
        boolean arrow = e instanceof EntityDamageByEntityEvent ed3 && ed3.getDamager() instanceof org.bukkit.entity.AbstractArrow
                && !(ed3.getDamager() instanceof org.bukkit.entity.Trident);
        double amount = e.getDamage() * (melee ? plugin.getConfig().getDouble("boss.melee-bonus", 1.3)
                : arrow ? plugin.getConfig().getDouble("boss.bow-bonus", 1.25) : 1.0);
        if (!k.hurt(by, amount)) {
            e.setCancelled(true);
            return;
        }
        e.setDamage(0);
    }

    private final java.util.Map<java.util.UUID, Integer> hitTick = new java.util.HashMap<>();

    private final org.bukkit.NamespacedKey offFloorKey = new org.bukkit.NamespacedKey("smpboss", "off_floor");

    /** Projectile tiré depuis l'extérieur de la piste (pont, muret, dehors...) : marqué, il ne touchera pas le Roi. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Arena ar = plugin.fightArena();
        if (ar == null || !ar.defined() || !plugin.getConfig().getBoolean("arena.protection.floor-only-damage", true)) return;
        Boss k = plugin.king();
        if (k == null || !k.active()) return;
        if (!ar.onFightFloor(p.getLocation())) {
            e.getEntity().getPersistentDataContainer().set(offFloorKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        Boss k = plugin.king();
        if (k != null && (k.isBoss(e.getEntity()) || k.isMinion(e.getEntity()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Boss k = plugin.king();
        if (k != null && (k.isBoss(dead) || k.isMinion(dead))) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        var c = plugin.getConfig();
        // yeux maudits (Reine Araignée)
        Player eyeKiller = dead.getKiller();
        if (eyeKiller != null && !(dead instanceof Player) && c.getBoolean("spider.eyes.enabled", true)
                && c.getStringList("spider.eyes.mobs").contains(dead.getType().name())
                && !(dead.getPersistentDataContainer().has(spawnerKey, PersistentDataType.BYTE) && !c.getBoolean("spider.eyes.from-spawners", false))
                && ThreadLocalRandom.current().nextDouble() * 100 < c.getDouble("spider.eyes.chance", 6)) {
            e.getDrops().add(plugin.items().cursedEye(1));
            dead.getWorld().spawnParticle(Particle.SOUL, dead.getLocation().add(0, 0.6, 0), 10, 0.3, 0.3, 0.3, 0.02);
        }
        // graines maudites
        if (!c.getBoolean("seeds.enabled", true)) return;
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player) return;
        if (dead.getPersistentDataContainer().has(spawnerKey, PersistentDataType.BYTE) && !c.getBoolean("seeds.from-spawners", false)) return;
        List<String> mobs = c.getStringList("seeds.mobs");
        if (!mobs.contains(dead.getType().name())) return;
        if (ThreadLocalRandom.current().nextDouble() * 100 >= c.getDouble("seeds.chance", 4)) return;
        e.getDrops().add(plugin.items().seed(1));
        dead.getWorld().spawnParticle(Particle.SOUL, dead.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.02);
    }

    // ================================================================= faucille : vol de vie

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity)) return;
        if (!plugin.items().isSickle(p.getInventory().getItemInMainHand())) return;
        double ratio = plugin.getConfig().getDouble(e.getEntity() instanceof Player ? "sickle.lifesteal-players" : "sickle.lifesteal-mobs",
                e.getEntity() instanceof Player ? 4 : 10) / 100.0;
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) p.setHealth(Math.min(max.getValue(), p.getHealth() + e.getFinalDamage() * ratio));
    }

    // ================================================================= clics droits

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        // autel
        if (a == Action.RIGHT_CLICK_BLOCK && plugin.isAltar(e.getClickedBlock()) && plugin.items().isSacrifice(hand)) {
            e.setCancelled(true);
            plugin.trySummon(p);
            return;
        }
        // autel de la Reine Araignée
        if (plugin.items().isCocoon(hand)) {
            e.setCancelled(true); // le cocon ne se pose jamais comme une toile
            if (a == Action.RIGHT_CLICK_BLOCK && plugin.isSpiderAltar(e.getClickedBlock())) plugin.trySummonSpider(p);
            else if (a == Action.RIGHT_CLICK_BLOCK) SmpBoss.hud(p, parse(plugin.msg("spider.cocoon-hint")));
            return;
        }
        // relique : l'ajouter à sa collection
        String relicId = plugin.items().relicId(hand);
        if (relicId != null && !relicId.equals("backpack-display")) {
            e.setCancelled(true);
            Relic r = plugin.relics().get(relicId);
            if (r == null) return;
            if (!plugin.relics().claim(p, r)) {
                p.sendMessage(parse(plugin.msg("relics.already-owned")));
                return;
            }
            hand.setAmount(hand.getAmount() - 1);
            p.sendMessage(parse(plugin.msg("relics.claimed").replace("<relic>", r.rarity().name(r.name(), 0))));
            RelicMenu.inspect(plugin, p, r);
            return;
        }
        // faucille : Moisson
        if (plugin.items().isSickle(hand)) {
            e.setCancelled(true);
            harvest(p);
        }
    }

    private void harvest(Player p) {
        var c = plugin.getConfig();
        long cd = (long) (c.getDouble("sickle.cooldown-seconds", 20) * 1000);
        long now = System.currentTimeMillis();
        Long last = sickleCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            SmpBoss.hud(p, parse(plugin.msg("sickle.cooldown").replace("<s>", String.valueOf((cd - (now - last)) / 1000 + 1))));
            return;
        }
        sickleCooldown.put(p.getUniqueId(), now);
        double range = c.getDouble("sickle.harvest-range", 4.5);
        Vector dir = p.getLocation().getDirection().setY(0).normalize();
        var w = p.getWorld();
        // arc de particules noir et blanc
        for (int i = -6; i <= 6; i++) {
            double ang = Math.toRadians(i * 10);
            Vector v = dir.clone().rotateAroundY(ang).multiply(range * 0.8);
            var at = p.getLocation().add(0, 1.1, 0).add(v);
            w.spawnParticle(Particle.DUST, at, 3, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(i % 2 == 0 ? org.bukkit.Color.WHITE : org.bukkit.Color.BLACK, 1.6f), true);
            w.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0, 0, 0, 0, null, true);
        }
        w.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("minecraft", "entity.player.attack.sweep"),
                net.kyori.adventure.sound.Sound.Source.PLAYER, 1.5f, 0.6f), p.getX(), p.getY(), p.getZ());
        for (Entity t : p.getNearbyEntities(range, 2.5, range)) {
            if (!(t instanceof LivingEntity le) || t == p || t.isInvulnerable()) continue;
            Vector to = t.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (to.lengthSquared() < 0.01 || to.normalize().dot(dir) < 0.5) continue; // devant soi (arc de 120°)
            double dmg = c.getDouble(t instanceof Player ? "sickle.harvest-damage-players" : "sickle.harvest-damage", t instanceof Player ? 5 : 9);
            le.damage(dmg, p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        if (plugin.items().isSacrifice(it) || plugin.items().isSeed(it) || plugin.items().relicId(it) != null) e.setCancelled(true);
    }

    // ================================================================= menus

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        var top = e.getView().getTopInventory();
        if (top.getHolder(false) instanceof RelicMenu m) {
            e.setCancelled(true);
            if (e.getClickedInventory() == top) m.click(e.getSlot(), e.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof RelicMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        var h = e.getInventory().getHolder(false);
        if (h instanceof Backpacks.Holder b) plugin.backpacks().close(b);
        if (h instanceof RelicMenu m) plugin.openMenus().remove(m);
    }

    // ================================================================= mort, connexion

    /** Une perle lancée sur le Roi le traverse (pas de téléportation sur lui). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        Boss k = plugin.king();
        if (k == null || e.getHitEntity() == null || !k.isBoss(e.getHitEntity())) return;
        if (e.getEntity() instanceof org.bukkit.entity.EnderPearl || e.getEntity() instanceof org.bukkit.entity.Snowball
                || e.getEntity() instanceof org.bukkit.entity.Egg) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        // mort dans l'arène : on garde tout, sac compris
        if (plugin.arenaAt(p.getLocation()) != null && plugin.getConfig().getBoolean("arena.keep-inventory", true)) {
            plugin.relics().updateBackpack(p);
            return;
        }
        List<ItemStack> lost = plugin.backpacks().dropOnDeath(p);
        if (!lost.isEmpty()) {
            if (e.getKeepInventory()) plugin.backpacks().dropAt(p.getLocation(), lost);
            else e.getDrops().addAll(lost);
            p.sendMessage(parse(plugin.msg("backpack.lost").replace("<n>", String.valueOf(lost.size()))));
        }
        plugin.relics().updateBackpack(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.relics().apply(p);
        }, 2L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.relics().apply(p);
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.relics().forget(e.getPlayer());
        sickleCooldown.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        plugin.relics().updateBackpack(e.getPlayer());
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.relics().updateBackpack(p));
    }
}
