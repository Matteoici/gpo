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

    static final String SONIC = "sonic_bow", TRANCHE = "tranche_mace";

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
        ItemStack it = new ItemStack(bow ? Material.BOW : Material.NETHERITE_SWORD);
        ItemMeta m = it.getItemMeta();
        String base = "weapons." + (bow ? "sonic-bow" : "tranche-mace") + ".";
        m.displayName(parse(Rarity.SECRET.name(plugin.msg("weapons." + (bow ? "sonic-bow" : "tranche-mace") + ".name"), 0)));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(Rarity.SECRET.tag));
        lore.add(Component.empty());
        for (String l : plugin.getConfig().getStringList("messages." + base + "lore")) {
            lore.add(parse(l.replace("<damage>", fmt(cfg((bow ? "sonic-bow" : "tranche-mace") + ".damage", bow ? 10 : 6) / 2))
                    .replace("<cooldown>", fmt(cfg((bow ? "sonic-bow" : "tranche-mace") + ".cooldown-seconds", bow ? 6 : 8)))));
        }
        m.lore(lore);
        if (!bow) {
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
        for (String id : new String[]{SONIC, TRANCHE}) {
            double chance = cfg((id.equals(SONIC) ? "sonic-bow" : "tranche-mace") + ".chance", 0.5);
            if (ThreadLocalRandom.current().nextDouble() * 100 >= chance) continue;
            ItemStack it = create(id);
            boolean inv = plugin.loot().give(p, it.clone());
            String name = Rarity.SECRET.name(plugin.msg("weapons." + (id.equals(SONIC) ? "sonic-bow" : "tranche-mace") + ".name"), 0);
            Reveal.show(plugin, p, it, Rarity.SECRET, name, plugin.msg(inv ? "loot.where-inventory" : "loot.where-stash"));
            Bukkit.broadcast(parse(plugin.msg("weapons.broadcast").replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", name)));
            for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f, 1.4f);
        }
    }
}
