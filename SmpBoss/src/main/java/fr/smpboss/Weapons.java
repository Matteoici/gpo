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
import org.bukkit.entity.ItemDisplay;
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
            case TRANCHE -> 12;
            case BRISE -> 20;
            default -> 16;
        };
    }

    private static double defCooldown(String id) {
        return switch (id) {
            case SONIC -> 10;
            case TRANCHE -> 8;
            case BRISE -> 6;
            default -> 2.5;
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
        Bukkit.getScheduler().runTaskTimer(plugin, this::aura, 40L, 3L);
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
                    .replace("<radius>", fmt(cfg(path(id) + ".radius", 10)))));
        }
        m.lore(lore);
        if (BRISE.equals(id)) {
            // hache PRESTIGE : 18 de dégâts (netherite = 10)
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "brise_damage"), cfg("brise-terre.attack-damage", 22) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "brise_speed"), cfg("brise-terre.attack-speed", 1.1) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (TRANCHE.equals(id)) {
            // plus fort qu'une épée en netherite Tranchant V (11) : 13 de base, et on peut encore l'enchanter
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "tranche_damage"), cfg("tranche-mace.attack-damage", 16) - 1,
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
        if (!ready(p, SONIC, cfg("sonic-bow.cooldown-seconds", 10))) return;
        sonic(p);
    }

    /** Nouveau style : une onde sonique violette qui file à toute vitesse, anneaux d'onde le long du rayon. */
    private void sonic(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        double range = cfg("sonic-bow.range", 96);
        RayTraceResult wall = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double len = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
        double dmg = cfg("sonic-bow.damage", 10);
        PumpkinKing k = plugin.king();
        w.playSound(eye, org.bukkit.Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.8f);
        w.playSound(eye, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 1.2f);
        w.playSound(eye, org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 2f);
        Set<UUID> hit = new HashSet<>();
        Particle.DustOptions violet = new Particle.DustOptions(Color.fromRGB(170, 90, 255), 1.5f);
        Particle.DustOptions pale = new Particle.DustOptions(Color.fromRGB(235, 215, 255), 1.0f);
        // repère perpendiculaire au rayon (pour dessiner les anneaux)
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
        side.normalize();
        Vector up = side.clone().crossProduct(dir).normalize();
        Vector sd = side, ud = up;
        // l'onde avance de 12 blocs par tick (on la voit partir)
        double[] d0 = {1};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double to = Math.min(len, d0[0] + 12);
            for (double d = d0[0]; d <= to; d += 0.5) {
                Location at = eye.clone().add(dir.clone().multiply(d));
                w.spawnParticle(Particle.END_ROD, at, 1, 0, 0, 0, 0, null, true);
                w.spawnParticle(Particle.DUST, at, 1, 0.06, 0.06, 0.06, 0, violet, true);
                // anneau d'onde tous les 3 blocs, de plus en plus large
                if (Math.abs(d % 3) < 0.25) {
                    double rr = 0.45 + Math.min(1.2, d / 60.0);
                    for (int n = 0; n < 14; n++) {
                        double a = Math.PI * 2 * n / 14;
                        Location r = at.clone().add(sd.clone().multiply(Math.cos(a) * rr)).add(ud.clone().multiply(Math.sin(a) * rr));
                        w.spawnParticle(Particle.DUST, r, 1, 0, 0, 0, 0, n % 2 == 0 ? violet : pale, true);
                    }
                }
                if (Math.abs(d % 8) < 0.25) w.spawnParticle(Particle.SONIC_BOOM, at, 1, 0, 0, 0, 0, null, true);
                for (Entity en : w.getNearbyEntities(at, 0.9, 0.9, 0.9)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) continue;
                    if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                    le.setNoDamageTicks(0);
                    le.damage(dmg, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.SONIC_BOOM)
                            .withCausingEntity(p).withDirectEntity(p).build());
                    if (k == null || !k.isBoss(en)) le.setVelocity(le.getVelocity().add(dir.clone().multiply(0.9).setY(0.3)));
                    w.spawnParticle(Particle.END_ROD, le.getLocation().add(0, 1, 0), 25, 0.2, 0.4, 0.2, 0.2, null, true);
                }
            }
            d0[0] = to + 0.5;
            if (d0[0] > len) {
                task[0].cancel();
                Location end = eye.clone().add(dir.clone().multiply(len));
                w.spawnParticle(Particle.END_ROD, end, 40, 0.1, 0.1, 0.1, 0.25, null, true);
                w.spawnParticle(Particle.DUST, end, 30, 0.6, 0.6, 0.6, 0, violet, true);
                if (wall != null) w.playSound(end, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.6f);
            }
        }, 0L, 1L);
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
        double dmg = cfg("tranche-mace.damage", 12);
        int lev = (int) (cfg("tranche-mace.levitation-seconds", 5) * 20);
        PumpkinKing k = plugin.king();
        Set<UUID> hit = new HashSet<>();
        // nouveau style : lame rouge sang au tranchant blanc, bords noirs, traînée de cendres
        Particle.DustOptions blood = new Particle.DustOptions(Color.fromRGB(200, 10, 30), 1.6f);
        Particle.DustOptions edge = new Particle.DustOptions(Color.fromRGB(255, 235, 235), 1.0f);
        Particle.DustOptions black = new Particle.DustOptions(Color.fromRGB(25, 0, 5), 1.8f);
        w.playSound(start, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, 0.5f);
        w.playSound(start, org.bukkit.Sound.ITEM_TRIDENT_RIPTIDE_3, 1f, 1.2f);
        w.playSound(start, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 0.6f, 0.6f);
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
            for (double t = -1; t <= 1.0001; t += 0.08) {
                Location pt = c.clone().add(axis.clone().multiply(t * 2.2)).add(fwd.clone().multiply(-0.8 * t * t));
                w.spawnParticle(Particle.DUST, pt, 1, 0.02, 0.02, 0.02, 0, Math.abs(t) > 0.85 ? black : blood, true);
                // tranchant blanc à l'avant de la lame
                w.spawnParticle(Particle.DUST, pt.clone().add(fwd.clone().multiply(0.25)), 1, 0, 0, 0, 0, edge, true);
                if (i[0] % 2 == 0 && Math.abs(t) < 0.05) w.spawnParticle(Particle.SWEEP_ATTACK, pt, 1, 0, 0, 0, 0, null, true);
            }
            // traînée de cendres derrière la lame
            Location back = c.clone().add(fwd.clone().multiply(-0.9));
            w.spawnParticle(Particle.CRIMSON_SPORE, back, 6, 0.2, 1.0, 0.2, 0, null, true);
            if (i[0] % 2 == 0) w.spawnParticle(Particle.LARGE_SMOKE, back, 2, 0.1, 0.8, 0.1, 0.01, null, true);
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
        w.spawnParticle(Particle.DUST, le.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0, new Particle.DustOptions(Color.fromRGB(200, 10, 30), 1.5f), true);
        w.spawnParticle(Particle.CRIMSON_SPORE, le.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0, null, true);
        w.spawnParticle(Particle.CLOUD, le.getLocation(), 10, 0.3, 0.1, 0.3, 0.05, null, true);
        w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_BREEZE_JUMP, 1f, 1.0f);
        w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.7f);
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
        if (slamming.contains(p.getUniqueId()) || !ready(p, BRISE, cfg("brise-terre.cooldown-seconds", 6))) return;
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
        double r = cfg("brise-terre.radius", 10), dmg = cfg("brise-terre.damage", 20);
        p.setFallDistance(0);
        PumpkinKing k = plugin.king();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        w.playSound(c, org.bukkit.Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2f, 0.5f);
        w.playSound(c, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.6f);
        w.playSound(c, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 0.5f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0, null, true);
        w.spawnParticle(Particle.FLASH, c.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, Particle.FLASH.getDataType() == Color.class ? Color.ORANGE : null, true);
        org.bukkit.block.data.BlockData ground = c.clone().add(0, -1, 0).getBlock().getBlockData();
        if (ground.getMaterial().isAir()) ground = Material.DIRT.createBlockData();
        org.bukkit.block.data.BlockData gd = ground;
        // fissures qui partent du centre
        Particle.DustOptions lava = new Particle.DustOptions(Color.fromRGB(255, 90, 0), 1.6f);
        Particle.DustOptions gold = new Particle.DustOptions(Color.fromRGB(255, 210, 60), 1.2f);
        for (int n = 0; n < 10; n++) {
            double a = Math.PI * 2 * n / 10 + rnd.nextDouble(0.3);
            for (double d = 0.5; d < r; d += 0.35) {
                double wob = Math.sin(d * 1.7 + n) * 0.25;
                Location l = c.clone().add(Math.cos(a) * d - Math.sin(a) * wob, 0.08, Math.sin(a) * d + Math.cos(a) * wob);
                w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, d < r * 0.6 ? lava : gold, true);
                if (rnd.nextDouble() < 0.12) w.spawnParticle(Particle.LAVA, l, 1, 0, 0, 0, 0, null, true);
            }
        }
        // trois ondes de choc qui s'élargissent
        for (int wave = 0; wave < 3; wave++) {
            int ww = wave;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int[] i = {0};
                org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
                task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    double rr = 1 + (r - 1) * (i[0] + 1) / 10.0;
                    int pts = (int) (rr * 9);
                    for (int n = 0; n < pts; n++) {
                        double a = Math.PI * 2 * n / pts;
                        Location l = c.clone().add(Math.cos(a) * rr, 0.2 + ww * 0.35, Math.sin(a) * rr);
                        if (ww == 0) w.spawnParticle(Particle.BLOCK, l, 2, 0.1, 0.15, 0.1, 0, gd, true);
                        else if (ww == 1) w.spawnParticle(Particle.FLAME, l, 1, 0, 0.05, 0, 0.01, null, true);
                        else w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, gold, true);
                    }
                    if (++i[0] >= 10) task[0].cancel();
                }, 0L, 1L);
            }, wave * 3L);
        }
        // morceaux du sol arrachés et projetés en l'air
        for (int n = 0; n < 14; n++) {
            double a = rnd.nextDouble(Math.PI * 2), d = rnd.nextDouble(1, r * 0.7);
            Location from = c.clone().add(Math.cos(a) * d, -0.2, Math.sin(a) * d);
            float sz = (float) rnd.nextDouble(0.5, 1.1);
            org.bukkit.entity.BlockDisplay chunk = w.spawn(from, org.bukkit.entity.BlockDisplay.class, e -> {
                e.setPersistent(false);
                e.setBlock(gd);
                e.setTeleportDuration(1);
                e.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-sz / 2, 0, -sz / 2),
                        new org.joml.Quaternionf().rotateXYZ(rnd.nextFloat() * 3, rnd.nextFloat() * 3, rnd.nextFloat() * 3),
                        new org.joml.Vector3f(sz, sz, sz), new org.joml.Quaternionf()));
            });
            Vector vel = new Vector(Math.cos(a) * 0.15, rnd.nextDouble(0.45, 0.8), Math.sin(a) * 0.15);
            Location[] at = {from.clone()};
            int[] life = {0};
            org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
            task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (++life[0] > 26 || !chunk.isValid()) {
                    if (chunk.isValid()) w.spawnParticle(Particle.BLOCK, chunk.getLocation(), 12, 0.2, 0.2, 0.2, 0, gd, true);
                    chunk.remove();
                    task[0].cancel();
                    return;
                }
                vel.setY(vel.getY() - 0.06);
                at[0].add(vel);
                at[0].setYaw(life[0] * 18f);
                chunk.teleport(at[0]);
            }, 1L, 1L);
        }
        // la caméra tremble pour ceux autour
        for (Player o : w.getPlayers()) if (o.getLocation().distanceSquared(c) < r * r * 4) o.playHurtAnimation((float) rnd.nextDouble(360));
        for (Entity en : w.getNearbyEntities(c, r, 4, r)) {
            if (!(en instanceof LivingEntity le) || en.equals(p) || en.getLocation().distanceSquared(c) > r * r) continue;
            if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
            double near = 1 - Math.min(1, en.getLocation().distance(c) / r) * 0.5; // plus près = plus fort
            le.setNoDamageTicks(0);
            le.damage(dmg * near, p);
            if (k != null && k.isBoss(en)) continue;
            Vector kb = en.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
            le.setVelocity(kb.normalize().multiply(1.3).setY(1.0));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 70, 2, false, true, true));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 70, 1, false, true, true));
            le.setFireTicks(60);
        }
        // la fureur de la terre : le porteur est renforcé quelques secondes
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 1, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 0, false, true, true));
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
        if (!ready(p, SPECTRAL, cfg("arbalete-spectrale.cooldown-seconds", 2.5))) return; // elle reste chargée
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
        int n = (int) cfg("arbalete-spectrale.skulls", 5);
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
        double dmg = cfg("arbalete-spectrale.damage", 16), range = cfg("arbalete-spectrale.range", 36);
        PumpkinKing k = plugin.king();
        w.playSound(pos, org.bukkit.Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.5f);
        w.playSound(pos, org.bukkit.Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.7f);
        // le crâne lui-même : une tête de squelette wither qui tourne, entourée d'âmes
        ItemDisplay skull = w.spawn(pos, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(new ItemStack(Material.WITHER_SKELETON_SKULL));
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(60, 240, 220));
            d.setTeleportDuration(1);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.7f, 0.7f, 0.7f), new org.joml.Quaternionf()));
        });
        LivingEntity[] target = {null};
        int[] t = {0};
        Particle.DustOptions teal = new Particle.DustOptions(Color.fromRGB(70, 235, 220), 1.4f);
        Particle.DustOptions dark = new Particle.DustOptions(Color.fromRGB(20, 40, 45), 1.8f);
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++t[0] > 70 || !p.isOnline()) {
                w.spawnParticle(Particle.SOUL, pos, 15, 0.3, 0.3, 0.3, 0.03, null, true);
                skull.remove();
                task[0].cancel();
                return;
            }
            if (target[0] == null || !target[0].isValid() || target[0].isDead()) {
                target[0] = null;
                double best = range * range;
                for (Entity en : w.getNearbyEntities(pos, range, range, range)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p) || en instanceof org.bukkit.entity.ArmorStand) continue;
                    if (en instanceof Player pl && (pl.getGameMode() == org.bukkit.GameMode.SPECTATOR || pl.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                    Vector to = le.getEyeLocation().toVector().subtract(pos.toVector());
                    double d2 = to.lengthSquared();
                    if (d2 < best && to.normalize().dot(dir) > 0.35 && !taken.contains(en.getUniqueId())) {
                        best = d2;
                        target[0] = le;
                    }
                }
            }
            if (target[0] != null) {
                taken.add(target[0].getUniqueId());
                Vector to = target[0].getLocation().add(0, target[0].getHeight() * 0.6, 0).toVector().subtract(pos.toVector()).normalize();
                dir.multiply(0.65).add(to.multiply(0.35)).normalize();
            }
            for (int s = 0; s < 3; s++) {
                pos.add(dir.clone().multiply(0.5));
                if (pos.getBlock().getType().isSolid()) {
                    w.spawnParticle(Particle.SCULK_SOUL, pos, 20, 0.3, 0.3, 0.3, 0.06, null, true);
                    w.playSound(pos, org.bukkit.Sound.BLOCK_SCULK_BREAK, 1f, 0.6f);
                    skull.remove();
                    task[0].cancel();
                    return;
                }
                for (Entity en : w.getNearbyEntities(pos, 0.8, 0.8, 0.8)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p)) continue;
                    le.setNoDamageTicks(0);
                    le.damage(dmg, p);
                    if (k == null || !k.isBoss(en)) {
                        le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, (int) (cfg("arbalete-spectrale.wither-seconds", 5) * 20), 2, false, true, true));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) (cfg("arbalete-spectrale.glow-seconds", 10) * 20), 0, false, false, true));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2, false, true, true));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, true));
                    }
                    // explosion d'âmes
                    Location hit = le.getLocation().add(0, 1, 0);
                    w.spawnParticle(Particle.SOUL, hit, 40, 0.4, 0.6, 0.4, 0.08, null, true);
                    w.spawnParticle(Particle.SCULK_SOUL, hit, 25, 0.3, 0.5, 0.3, 0.08, null, true);
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, hit, 30, 0.2, 0.2, 0.2, 0.15, null, true);
                    for (int n = 0; n < 24; n++) {
                        double a = Math.PI * 2 * n / 24;
                        w.spawnParticle(Particle.DUST, hit.clone().add(Math.cos(a) * 1.3, 0, Math.sin(a) * 1.3), 1, 0, 0, 0, 0, teal, true);
                    }
                    w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_WITHER_HURT, 0.9f, 1.5f);
                    w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_VEX_CHARGE, 1f, 0.6f);
                    skull.remove();
                    task[0].cancel();
                    return;
                }
            }
            Location look = pos.clone();
            look.setDirection(dir);
            skull.teleport(look);
            // traînée : spirale d'âmes autour du crâne
            for (int n = 0; n < 2; n++) {
                double a = t[0] * 0.9 + Math.PI * n;
                Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
                if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
                side.normalize();
                Vector up = side.clone().crossProduct(dir).normalize();
                Location sp = pos.clone().add(side.multiply(Math.cos(a) * 0.35)).add(up.multiply(Math.sin(a) * 0.35));
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, sp, 1, 0, 0, 0, 0, null, true);
            }
            w.spawnParticle(Particle.DUST, pos, 3, 0.12, 0.12, 0.12, 0, t[0] % 2 == 0 ? teal : dark, true);
            if (t[0] % 3 == 0) w.spawnParticle(Particle.SCULK_SOUL, pos, 1, 0.05, 0.05, 0.05, 0.01, null, true);
        }, 0L, 1L);
    }

    // ================================================================= aura des armes prestige

    /** Toutes les 3 ticks : une aura autour de l'arme prestige tenue en main (et un bonus passif). */
    void aura() {
        auraTick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String id = id(p.getInventory().getItemInMainHand());
            if (id == null) continue;
            World w = p.getWorld();
            double yaw = Math.toRadians(p.getLocation().getYaw());
            // position de la main droite
            Location hand = p.getLocation().add(-Math.cos(yaw) * 0.38 - Math.sin(yaw) * 0.25, 0.95, -Math.sin(yaw) * 0.38 + Math.cos(yaw) * 0.25);
            if (SONIC.equals(id)) {
                if (auraTick % 2 == 0) w.spawnParticle(Particle.DUST, hand, 1, 0.1, 0.18, 0.1, 0, new Particle.DustOptions(Color.fromRGB(170, 90, 255), 0.8f));
                if (auraTick % 6 == 0) w.spawnParticle(Particle.END_ROD, hand, 1, 0.08, 0.12, 0.08, 0.005);
                continue;
            }
            if (TRANCHE.equals(id)) {
                if (auraTick % 2 == 0) w.spawnParticle(Particle.DUST, hand, 1, 0.1, 0.2, 0.1, 0, new Particle.DustOptions(Color.fromRGB(200, 10, 30), 0.9f));
                if (auraTick % 3 == 0) w.spawnParticle(Particle.CRIMSON_SPORE, hand, 2, 0.15, 0.25, 0.15, 0);
                continue;
            }
            if (BRISE.equals(id)) {
                w.spawnParticle(Particle.DUST, hand, 2, 0.12, 0.2, 0.12, 0, new Particle.DustOptions(Color.fromRGB(255, 110 + (auraTick * 13) % 80, 10), 0.9f));
                if (auraTick % 3 == 0) w.spawnParticle(Particle.FALLING_LAVA, hand, 1, 0.1, 0.1, 0.1, 0);
                if (auraTick % 5 == 0) w.spawnParticle(Particle.WAX_OFF, hand.clone().add(0, 0.3, 0), 1, 0.15, 0.15, 0.15, 0);
                // braises autour des pieds
                double a = auraTick * 0.35;
                w.spawnParticle(Particle.FLAME, p.getLocation().add(Math.cos(a) * 0.7, 0.1, Math.sin(a) * 0.7), 1, 0, 0, 0, 0);
            } else {
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, hand, 1, 0.08, 0.15, 0.08, 0.005);
                w.spawnParticle(Particle.DUST, hand, 2, 0.12, 0.2, 0.12, 0, new Particle.DustOptions(Color.fromRGB(70, 235, 220), 0.9f));
                double a = auraTick * 0.3;
                for (int n = 0; n < 2; n++) {
                    double b = a + Math.PI * n;
                    w.spawnParticle(Particle.SOUL, p.getLocation().add(Math.cos(b) * 0.8, 1.0 + Math.sin(auraTick * 0.1) * 0.6, Math.sin(b) * 0.8), 1, 0, 0, 0, 0);
                }
                // passif : on se déplace plus vite, arbalète en main
                if (auraTick % 10 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 0, false, false, true));
            }
        }
    }

    private int auraTick;

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
