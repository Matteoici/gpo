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

import static fr.smpboss.SmpBoss.parse;

final class BossListener implements Listener {

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

    // ================================================================= dégâts au boss

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        PumpkinKing k = plugin.king();
        Entity ent = e.getEntity();
        if (ent instanceof Player p) plugin.relics().hurt(p);
        if (k == null || !k.isBoss(ent)) return;
        Player by = null;
        if (e instanceof EntityDamageByEntityEvent ed) {
            Entity d = ed.getDamager();
            if (d instanceof Player p) by = p;
            else if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) by = p;
        }
        if (by == null) {
            e.setCancelled(true); // feu, chute, étouffement, lave... : rien
            return;
        }
        double amount = e.getFinalDamage();
        if (!k.hurt(by, amount)) {
            e.setCancelled(true);
            return;
        }
        e.setDamage(0);
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        PumpkinKing k = plugin.king();
        if (k != null && (k.isBoss(e.getEntity()) || k.isMinion(e.getEntity()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        PumpkinKing k = plugin.king();
        if (k != null && (k.isBoss(dead) || k.isMinion(dead))) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        // graines maudites
        var c = plugin.getConfig();
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
            p.sendActionBar(parse(plugin.msg("sickle.cooldown").replace("<s>", String.valueOf((cd - (now - last)) / 1000 + 1))));
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
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
