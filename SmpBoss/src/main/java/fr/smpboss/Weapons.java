package fr.smpboss;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Armes du Roi Citrouille (butin rare, gardées à la mort) :
 * - Sonic Bow : un tir chargé AU MAXIMUM envoie un rayon sonique de Warden (traverse l'armure) au lieu d'une flèche.
 * - Tranche Mace : clic droit = une longue lame verticale qui file droit devant ; ceux qu'elle touche lévitent
 *   (le joueur à la masse ne peut plus tomber sur toi).
 */
final class Weapons implements Listener {

    static final String SONIC = "sonic_bow", TRANCHE = "tranche_mace", BRISE = "brise_terre", SPECTRAL = "arbalete_spectrale";
    static final String[] ALL = {SONIC, TRANCHE, BRISE, SPECTRAL};

    /** Section du config / des textes de chaque arme. */
    static String path(String id) {
        return switch (id) {
            case SONIC -> "sonic-bow";
            case TRANCHE -> "tranche-mace";
            case BRISE -> "brise-terre";
            default -> "arbalete-spectrale";
        };
    }

    private static Material material(String id) {
        return switch (id) {
            case SONIC -> Material.BOW;
            case TRANCHE -> Material.NETHERITE_SWORD;
            case BRISE -> Material.NETHERITE_AXE;
            default -> Material.CROSSBOW;
        };
    }

    /** Brise-Terre et Arbalète Spectrale : PRESTIGE (0,001 %). */
    static Rarity rarity(String id) {
        return BRISE.equals(id) || SPECTRAL.equals(id) ? Rarity.PRESTIGE : Rarity.SECRET;
    }

    private static double defDamage(String id) {
        return switch (id) {
            case SONIC -> 10;
            case TRANCHE -> 8;
            case BRISE -> 14;
            default -> 14;
        };
    }

    private static double defCooldown(String id) {
        return switch (id) {
            case SONIC -> 3;
            case TRANCHE -> 8;
            case BRISE -> 8;
            default -> 3;
        };
    }

    private final SmpBoss plugin;
    final NamespacedKey key;
    private final Map<UUID, Long> drawStart = new HashMap<>();
    private final Map<String, Long> cooldown = new HashMap<>();
    /** armes gardées à la mort, rendues à la réapparition */
    private final Map<UUID, List<ItemStack>> kept = new HashMap<>();

    Weapons(SmpBoss plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "weapon");
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("weapons." + path, def);
    }

    // ================================================================= objets

    ItemStack create(String id) {
        boolean bow = SONIC.equals(id);
        ItemStack it = new ItemStack(material(id));
        ItemMeta m = it.getItemMeta();
        String base = "weapons." + path(id) + ".";
        m.displayName(parse(rarity(id).name(plugin.msg("weapons." + path(id) + ".name"), 0)));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(rarity(id).tag));
        lore.add(Component.empty());
        for (String l : plugin.getConfig().getStringList("messages." + base + "lore")) {
            lore.add(parse(l.replace("<damage>", fmt(cfg(path(id) + ".damage", defDamage(id)) / 2))
                    .replace("<cooldown>", fmt(cfg(path(id) + ".cooldown-seconds", defCooldown(id))))
                    .replace("<radius>", fmt(cfg(path(id) + ".radius", 8)))));
        }
        m.lore(lore);
        if (BRISE.equals(id)) {
            // hache PRESTIGE : 18 de dégâts (netherite = 10)
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "brise_damage"), cfg("brise-terre.attack-damage", 18) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "brise_speed"), cfg("brise-terre.attack-speed", 1.1) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (TRANCHE.equals(id)) {
            // plus fort qu'une épée en netherite Tranchant V (11) : 13 de base, et on peut encore l'enchanter
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "tranche_damage"), cfg("tranche-mace.attack-damage", 13) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "tranche_speed"), cfg("tranche-mace.attack-speed", 1.6) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        m.setUnbreakable(true);
        m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        if (bow) m.addEnchant(Enchantment.INFINITY, 1, true);
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private boolean ready(Player p, String id, double seconds) {
        String k = p.getUniqueId() + ":" + id;
        long now = System.currentTimeMillis();
        Long until = cooldown.get(k);
        if (until != null && until > now) {
            p.sendActionBar(parse(plugin.msg("weapons.cooldown").replace("<seconds>", fmt(Math.ceil((until - now) / 100.0) / 10.0))));
            return false;
        }
        cooldown.put(k, now + (long) (seconds * 1000));
        return true;
    }

    // ================================================================= Sonic Bow

    /** Met à jour une arme déjà donnée (nouveaux dégâts, nouvelle description), en gardant ses enchantements. */
    private void refresh(org.bukkit.inventory.Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            String id = id(it);
            if (id == null) continue;
            ItemStack fresh = create(id);
            ItemMeta fm = fresh.getItemMeta();
            for (Map.Entry<Enchantment, Integer> en : it.getEnchantments().entrySet()) fm.addEnchant(en.getKey(), en.getValue(), true);
            if (!fm.equals(it.getItemMeta())) {
                fresh.setItemMeta(fm);
                inv.setItem(i, fresh);
            }
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) refresh(p.getInventory());
        }, 20L);
    }

    @EventHandler
    public void onHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        ItemStack it = e.getPlayer().getInventory().getItem(e.getNewSlot());
        if (id(it) != null) Bukkit.getScheduler().runTask(plugin, () -> refresh(e.getPlayer().getInventory()));
    }

    @EventHandler
    public void onDraw(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (SONIC.equals(id(e.getItem()))) drawStart.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !SONIC.equals(id(e.getBow()))) return;
        e.setCancelled(true); // pas de flèche : le rayon la remplace
        Bukkit.getScheduler().runTask(plugin, p::updateInventory);
        Long start = drawStart.remove(p.getUniqueId());
        long held = start == null ? 0 : System.currentTimeMillis() - start;
        // l'arc doit être tendu au maximum (1 s)
        if (held < cfg("sonic-bow.full-charge-ms", 950) || e.getForce() < 0.95f) {
            p.sendActionBar(parse(plugin.msg("weapons.sonic-bow.not-charged")));
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_SCULK_SENSOR_CLICKING_STOP, 1f, 0.6f);
            return;
        }
        if (!ready(p, SONIC, cfg("sonic-bow.cooldown-seconds", 6))) return;
        sonic(p);
    }

    private void sonic(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        double range = cfg("sonic-bow.range", 32);
        RayTraceResult wall = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double len = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
        double dmg = cfg("sonic-bow.damage", 10);
        PumpkinKing k = plugin.king();
        w.playSound(eye, org.bukkit.Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.6f);
        w.playSound(eye, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 2.5f, 1f);
        Set<UUID> hit = new HashSet<>();
        Particle.DustOptions teal = new Particle.DustOptions(Color.fromRGB(40, 220, 210), 1.3f);
        for (double d = 1; d <= len; d += 0.5) {
            Location at = eye.clone().add(dir.clone().multiply(d));
            if (((int) (d * 2)) % 3 == 0) w.spawnParticle(Particle.SONIC_BOOM, at, 1, 0, 0, 0, 0, null, true);
            w.spawnParticle(Particle.DUST, at, 2, 0.08, 0.08, 0.08, 0, teal, true);
            for (Entity en : w.getNearbyEntities(at, 0.9, 0.9, 0.9)) {
                if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) continue;
                if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                le.setNoDamageTicks(0);
                le.damage(dmg, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.SONIC_BOOM)
                        .withCausingEntity(p).withDirectEntity(p).build());
                if (k == null || !k.isBoss(en)) le.setVelocity(le.getVelocity().add(dir.clone().multiply(0.7).setY(0.25)));
            }
        }
        if (wall != null) w.spawnParticle(Particle.EXPLOSION, wall.getHitPosition().toLocation(w), 1, 0, 0, 0, 0, null, true);
    }

    // ================================================================= Tranche Mace

    @EventHandler(priority = EventPriority.HIGH)
    public void onSlash(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !TRANCHE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return; // ouvrir un coffre, une porte...
        Player p = e.getPlayer();
        if (!ready(p, TRANCHE, cfg("tranche-mace.cooldown-seconds", 8))) return;
        e.setCancelled(true);
        slash(p);
    }

    private void slash(Player p) {
        World w = p.getWorld();
        // la lame part exactement là où on vise (vers le sol, vers le ciel...)
        Location start = p.getEyeLocation().add(0, -0.3, 0);
        Vector fwd = start.getDirection().normalize();
        // axe de la lame : la "verticale" perpendiculaire à la visée
        Vector up = new Vector(0, 1, 0).subtract(fwd.clone().multiply(fwd.getY()));
        if (up.lengthSquared() < 0.01) {
            // visée droit vers le haut / le bas : la lame se met dans l'axe du regard horizontal
            double yaw = Math.toRadians(p.getLocation().getYaw());
            up = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        Vector axis = up.normalize();
        int steps = (int) cfg("tranche-mace.length", 18);
        double dmg = cfg("tranche-mace.damage", 6);
        int lev = (int) (cfg("tranche-mace.levitation-seconds", 1.5) * 20);
        PumpkinKing k = plugin.king();
        Set<UUID> hit = new HashSet<>();
        Particle.DustOptions white = new Particle.DustOptions(Color.fromRGB(235, 250, 255), 1.4f);
        Particle.DustOptions cyan = new Particle.DustOptions(Color.fromRGB(110, 210, 255), 1.0f);
        w.playSound(start, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, 0.6f);
        w.playSound(start, org.bukkit.Sound.ITEM_TRIDENT_RIPTIDE_1, 1f, 1.4f);
        int[] i = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (i[0] >= steps || !p.isOnline()) {
                task[0].cancel();
                return;
            }
            Location c = start.clone().add(fwd.clone().multiply(1.5 + i[0] * 1.1));
            if (c.getBlock().getType().isSolid()) {
                // elle s'écrase sur le sol / le mur : fissure + éclats, et touche ce qui est juste là
                w.spawnParticle(Particle.BLOCK, c, 40, 0.6, 0.3, 0.6, 0, c.getBlock().getBlockData(), true);
                w.spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(0, 0.5, 0), 3, 0.6, 0.2, 0.6, 0, null, true);
                w.playSound(c, org.bukkit.Sound.ITEM_MACE_SMASH_GROUND, 1f, 1.3f);
                for (Entity en : w.getNearbyEntities(c, 1.6, 1.6, 1.6)) touch(p, en, hit, dmg, lev, k);
                task[0].cancel();
                return;
            }
            // la lame : un croissant vertical de 4 blocs de haut
            for (double t = -1; t <= 1.0001; t += 0.1) {
                Location pt = c.clone().add(axis.clone().multiply(t * 2.0)).add(fwd.clone().multiply(-0.7 * t * t));
                w.spawnParticle(Particle.DUST, pt, 1, 0.02, 0.02, 0.02, 0, Math.abs(t) > 0.8 ? cyan : white, true);
                if (i[0] % 2 == 0 && Math.abs(t) < 0.05) w.spawnParticle(Particle.SWEEP_ATTACK, pt, 1, 0, 0, 0, 0, null, true);
            }
            if (i[0] % 3 == 0) w.spawnParticle(Particle.END_ROD, c, 4, 0.3, 0.3, 0.3, 0.01, null, true);
            // la lame touche tout ce qui est sur sa longueur (4 blocs)
            for (double t = -1; t <= 1.0001; t += 0.5) {
                Location pt = c.clone().add(axis.clone().multiply(t * 2.0));
                for (Entity en : w.getNearbyEntities(pt, 0.9, 0.9, 0.9)) touch(p, en, hit, dmg, lev, k);
            }
            i[0]++;
        }, 0L, 1L);
    }

    private void touch(Player p, Entity en, Set<UUID> hit, double dmg, int lev, PumpkinKing k) {
        if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) return;
        if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) return;
        le.setNoDamageTicks(0);
        le.damage(dmg, p);
        if (k != null && k.isBoss(en)) return;
        le.setFallDistance(0); // un joueur qui tombait à la masse perd son élan
        le.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, lev, (int) cfg("tranche-mace.levitation-level", 1) - 1, false, true, true));
        World w = le.getWorld();
        w.spawnParticle(Particle.CLOUD, le.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05, null, true);
        w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_BREEZE_JUMP, 1f, 1.2f);
    }

    // ================================================================= Brise-Terre (hache)

    private final java.util.Set<UUID> slamming = new HashSet<>();

    /** Clic droit : bond en l'air puis on s'écrase au sol, onde de choc tout autour. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onQuake(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !BRISE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        Player p = e.getPlayer();
        e.setCancelled(true); // pas d'écorçage de bûche
        if (slamming.contains(p.getUniqueId()) || !ready(p, BRISE, cfg("brise-terre.cooldown-seconds", 8))) return;
        slamming.add(p.getUniqueId());
        World w = p.getWorld();
        boolean onGround = ((org.bukkit.entity.Entity) p).isOnGround();
        if (onGround) {
            p.setVelocity(p.getLocation().getDirection().setY(0).normalize().multiply(0.4).setY(1.05));
            w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 0.7f);
            w.spawnParticle(Particle.CLOUD, p.getLocation(), 20, 0.4, 0.1, 0.4, 0.05, null, true);
        }
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            if (!p.isOnline() || p.isDead() || t[0] > 80) {
                slamming.remove(p.getUniqueId());
                task[0].cancel();
                return;
            }
            // au sommet du bond (ou déjà en l'air) : on pique vers le sol
            if (t[0] == (onGround ? 9 : 1)) {
                p.setVelocity(new Vector(0, -1.6, 0));
                w.playSound(p.getLocation(), org.bukkit.Sound.ITEM_MACE_SMASH_AIR, 1f, 0.6f);
            }
            if (t[0] > 3) w.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 4, 0.3, 0.5, 0.3, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 120, 30), 1.4f), true);
            if (t[0] > (onGround ? 10 : 2) && ((org.bukkit.entity.Entity) p).isOnGround()) {
                task[0].cancel();
                quake(p);
                Bukkit.getScheduler().runTaskLater(plugin, () -> slamming.remove(p.getUniqueId()), 5L);
            }
        }, 1L, 1L);
    }

    private void quake(Player p) {
        Location c = p.getLocation();
        World w = c.getWorld();
        double r = cfg("brise-terre.radius", 8), dmg = cfg("brise-terre.damage", 14);
        p.setFallDistance(0);
        PumpkinKing k = plugin.king();
        w.playSound(c, org.bukkit.Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.6f, 0.6f);
        w.playSound(c, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.7f);
        w.spawnParticle(Particle.EXPLOSION, c, 3, 0.6, 0.1, 0.6, 0, null, true);
        org.bukkit.block.data.BlockData ground = c.clone().add(0, -1, 0).getBlock().getBlockData();
        // onde de choc qui s'élargit, morceaux de sol qui volent
        int[] i = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double rr = 1 + (r - 1) * (i[0] + 1) / 8.0;
            int pts = (int) (rr * 10);
            for (int n = 0; n < pts; n++) {
                double a = Math.PI * 2 * n / pts;
                Location l = c.clone().add(Math.cos(a) * rr, 0.15, Math.sin(a) * rr);
                w.spawnParticle(Particle.BLOCK, l, 2, 0.1, 0.1, 0.1, 0, ground, true);
                if (n % 3 == 0) w.spawnParticle(Particle.DUST, l.add(0, 0.2, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 140, 40), 1.6f), true);
            }
            if (++i[0] >= 8) task[0].cancel();
        }, 0L, 1L);
        for (Entity en : w.getNearbyEntities(c, r, 3, r)) {
            if (!(en instanceof LivingEntity le) || en.equals(p) || en.getLocation().distanceSquared(c) > r * r) continue;
            if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
            le.setNoDamageTicks(0);
            le.damage(dmg, p);
            if (k != null && k.isBoss(en)) continue;
            Vector kb = en.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
            le.setVelocity(kb.normalize().multiply(1.1).setY(0.9));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, true, true));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, false, true, true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSlamFall(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL && slamming.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    // ================================================================= Arbalète Spectrale

    /** Tir : un crâne d'âmes qui CHERCHE l'ennemi le plus proche devant toi ; il flétrit et fait briller sa cible. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrossbow(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !SPECTRAL.equals(id(e.getBow()))) return;
        e.setCancelled(true);
        if (!ready(p, SPECTRAL, cfg("arbalete-spectrale.cooldown-seconds", 3))) return; // elle reste chargée
        ItemStack bow = e.getBow();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
                if (SPECTRAL.equals(id(it)) && it.getItemMeta() instanceof org.bukkit.inventory.meta.CrossbowMeta cm) {
                    cm.setChargedProjectiles(new ArrayList<>());
                    it.setItemMeta(cm);
                }
            }
            p.updateInventory();
        });
        if (bow == null) return;
        // trois crânes en éventail : chacun cherche sa cible
        int n = (int) cfg("arbalete-spectrale.skulls", 3);
        Set<UUID> taken = new HashSet<>();
        for (int i = 0; i < n; i++) {
            double yaw = Math.toRadians((i - (n - 1) / 2.0) * 12);
            Vector d = p.getEyeLocation().getDirection().normalize().rotateAroundY(yaw);
            int delay = i * 2;
            Bukkit.getScheduler().runTaskLater(plugin, () -> spectral(p, d, taken), delay);
        }
    }

    private void spectral(Player p, Vector start, Set<UUID> taken) {
        World w = p.getWorld();
        Location pos = p.getEyeLocation().add(start.clone().multiply(0.8));
        Vector dir = start.clone().normalize();
        double dmg = cfg("arbalete-spectrale.damage", 14), range = cfg("arbalete-spectrale.range", 32);
        PumpkinKing k = plugin.king();
        w.playSound(pos, org.bukkit.Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.6f);
        w.playSound(pos, org.bukkit.Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.8f);
        LivingEntity[] target = {null};
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++t[0] > 60 || !p.isOnline()) {
                w.spawnParticle(Particle.SOUL, pos, 10, 0.2, 0.2, 0.2, 0.02, null, true);
                task[0].cancel();
                return;
            }
            // cible : l'ennemi vivant le plus proche devant le tir
            if (target[0] == null || !target[0].isValid() || target[0].isDead()) {
                target[0] = null;
                double best = range * range;
                for (Entity en : w.getNearbyEntities(pos, range, range, range)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p) || en instanceof org.bukkit.entity.ArmorStand) continue;
                    if (en instanceof Player pl && (pl.getGameMode() == org.bukkit.GameMode.SPECTATOR || pl.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                    Vector to = le.getEyeLocation().toVector().subtract(pos.toVector());
                    double d2 = to.lengthSquared();
                    if (d2 < best && to.normalize().dot(dir) > 0.45 && !taken.contains(en.getUniqueId())) {
                        best = d2;
                        target[0] = le;
                    }
                }
            }
            if (target[0] != null) {
                taken.add(target[0].getUniqueId());
                Vector to = target[0].getLocation().add(0, target[0].getHeight() * 0.6, 0).toVector().subtract(pos.toVector()).normalize();
                dir.multiply(0.7).add(to.multiply(0.3)).normalize();
            }
            for (int s = 0; s < 3; s++) {
                pos.add(dir.clone().multiply(0.45));
                if (pos.getBlock().getType().isSolid()) {
                    w.spawnParticle(Particle.SCULK_SOUL, pos, 12, 0.2, 0.2, 0.2, 0.05, null, true);
                    w.playSound(pos, org.bukkit.Sound.BLOCK_SCULK_BREAK, 1f, 0.6f);
                    task[0].cancel();
                    return;
                }
                for (Entity en : w.getNearbyEntities(pos, 0.7, 0.7, 0.7)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p)) continue;
                    le.setNoDamageTicks(0);
                    le.damage(dmg, p);
                    if (k == null || !k.isBoss(en)) {
                        le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, (int) (cfg("arbalete-spectrale.wither-seconds", 4) * 20), 2, false, true, true));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) (cfg("arbalete-spectrale.glow-seconds", 8) * 20), 0, false, false, true));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true, true));
                    }
                    w.spawnParticle(Particle.SOUL, le.getLocation().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.05, null, true);
                    w.spawnParticle(Particle.SCULK_SOUL, le.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.05, null, true);
                    w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_WITHER_HURT, 0.8f, 1.6f);
                    task[0].cancel();
                    return;
                }
            }
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, pos, 3, 0.05, 0.05, 0.05, 0.01, null, true);
            w.spawnParticle(Particle.DUST, pos, 2, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(Color.fromRGB(90, 230, 220), 1.4f), true);
            if (t[0] % 3 == 0) w.spawnParticle(Particle.SCULK_SOUL, pos, 1, 0, 0, 0, 0.01, null, true);
        }, 0L, 1L);
    }

    // ================================================================= gardées à la mort

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        List<ItemStack> mine = new ArrayList<>();
        for (Iterator<ItemStack> it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (id(s) != null) {
                mine.add(s.clone());
                it.remove();
            }
        }
        if (!mine.isEmpty()) kept.computeIfAbsent(e.getEntity().getUniqueId(), k -> new ArrayList<>()).addAll(mine);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        List<ItemStack> back = kept.remove(p.getUniqueId());
        if (back == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (ItemStack it : back) plugin.loot().give(p, it);
            p.sendMessage(parse(plugin.msg("weapons.kept")));
        }, 2L);
    }

    /** Arrêt du serveur : les armes pas encore rendues vont dans le coffre de butin. */
    void saveKept() {
        for (Map.Entry<UUID, List<ItemStack>> e : kept.entrySet()) plugin.store().of(e.getKey()).stash.addAll(e.getValue());
        if (!kept.isEmpty()) plugin.store().dirty();
        kept.clear();
    }

    // ================================================================= butin

    /** Tirage des armes après une victoire (chacune a sa chance). */
    void roll(Player p) {
        for (String id : ALL) {
            double chance = cfg(path(id) + ".chance", rarity(id) == Rarity.PRESTIGE ? 0.001 : 0.5);
            if (ThreadLocalRandom.current().nextDouble() * 100 >= chance) continue;
            ItemStack it = create(id);
            boolean inv = plugin.loot().give(p, it.clone());
            Rarity rar = rarity(id);
            String name = rar.name(plugin.msg("weapons." + path(id) + ".name"), 0);
            Reveal.show(plugin, p, it, rar, name, plugin.msg(inv ? "loot.where-inventory" : "loot.where-stash"));
            if (rar == Rarity.PRESTIGE) {
                Bukkit.broadcast(parse(plugin.msg("loot.broadcast-prestige").replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", name)
                        .replace("1 chance sur 10 000", "1 chance sur 100 000")));
                for (Player o : Bukkit.getOnlinePlayers()) {
                    o.showTitle(net.kyori.adventure.title.Title.title(parse(rar.tag), parse(plugin.msg("loot.prestige-subtitle")
                            .replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", name))));
                    o.playSound(o.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_DEATH, 0.6f, 1.1f);
                }
                p.getWorld().strikeLightningEffect(p.getLocation());
            } else {
                Bukkit.broadcast(parse(plugin.msg("weapons.broadcast").replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", name)));
            }
            for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f, 1.4f);
        }
    }
}
