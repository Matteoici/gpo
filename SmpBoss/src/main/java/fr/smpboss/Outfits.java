package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Bonus des tenues complètes (la Tenue de Toji est gérée par TojiSpear) :
 * - Tenue de Gojo : L'INFINI — les projectiles s'arrêtent avant de te toucher, les coups au corps à corps sont freinés,
 *   presque pas de recul ; les Six Yeux : vision nocturne. (La Lance du Ciel Inversé traverse l'Infini.)
 * - Tenue de Sukuna : ROI DES FLÉAUX — plus de dégâts, chaque coup te soigne un peu, un coup sur trois déclenche
 *   Démantèlement (une entaille invisible frappe juste après) ; insensible au feu.
 */
final class Outfits implements Listener {

    private static final Color BLUE = Color.fromRGB(90, 170, 255), ICE = Color.fromRGB(210, 235, 255), CRIMSON = Color.fromRGB(170, 10, 30),
            DARK = Color.fromRGB(30, 0, 8);

    private final SmpBoss plugin;
    private final Set<UUID> gojo = new HashSet<>(), sukuna = new HashSet<>();
    private final Map<String, Integer> combo = new HashMap<>();
    private final NamespacedKey gKb, sDmg;

    Outfits(SmpBoss plugin) {
        this.plugin = plugin;
        gKb = new NamespacedKey(plugin, "gojoset_kb");
        sDmg = new NamespacedKey(plugin, "sukunaset_dmg");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 30L, 10L);
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("weapons." + path, def);
    }

    private void tick() {
        Weapons w = plugin.weapons();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean g = !p.isDead() && w.wearsOutfit(p, Weapons.TENUE_GOJO);
            boolean s = !p.isDead() && w.wearsOutfit(p, Weapons.TENUE_SUKUNA);
            if (g && gojo.add(id)) {
                set(p, Attribute.KNOCKBACK_RESISTANCE, gKb, cfg("tenue-gojo.set.knockback-resistance", 0.6));
                SmpBoss.hud(p, parse(plugin.msg("outfits.gojo-on")));
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.8f);
            } else if (!g && gojo.remove(id)) {
                unset(p, Attribute.KNOCKBACK_RESISTANCE, gKb);
                PotionEffect nv = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
                if (nv != null && nv.getDuration() <= 400) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
            if (g && plugin.getConfig().getBoolean("weapons.tenue-gojo.set.night-vision", true)) {
                // les Six Yeux
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, false));
            }
            if (s && sukuna.add(id)) {
                set(p, Attribute.ATTACK_DAMAGE, sDmg, cfg("tenue-sukuna.set.damage", 0.2));
                SmpBoss.hud(p, parse(plugin.msg("outfits.sukuna-on")));
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.5f, 0.6f);
            } else if (!s && sukuna.remove(id)) {
                unset(p, Attribute.ATTACK_DAMAGE, sDmg);
            }
        }
        gojo.removeIf(u -> Bukkit.getPlayer(u) == null);
        sukuna.removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    private static void set(Player p, Attribute a, NamespacedKey k, double v) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        inst.removeModifier(k);
        AttributeModifier.Operation op = a == Attribute.ATTACK_DAMAGE ? AttributeModifier.Operation.ADD_SCALAR : AttributeModifier.Operation.ADD_NUMBER;
        inst.addTransientModifier(new AttributeModifier(k, v, op, EquipmentSlotGroup.ANY));
    }

    private static void unset(Player p, Attribute a, NamespacedKey k) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst != null) inst.removeModifier(k);
    }

    // ================================================================= Gojo : l'Infini

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void infinity(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !gojo.contains(p.getUniqueId())) return;
        Entity d = e.getDamager();
        if (TojiSpear.piercing(d)) return; // la Lance du Ciel Inversé traverse l'Infini
        Location c = p.getLocation().add(0, 1, 0);
        if (d instanceof Projectile pr) {
            // le projectile s'arrête net devant toi
            e.setCancelled(true);
            Vector back = pr.getVelocity().multiply(-0.15);
            pr.setVelocity(back);
            ripple(c, pr.getLocation());
            p.getWorld().playSound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.6f);
            return;
        }
        e.setDamage(e.getDamage() * (1 - cfg("tenue-gojo.set.melee-reduction", 0.3)));
        ripple(c, d.getLocation().add(0, 1, 0));
    }

    private void ripple(Location c, Location from) {
        Vector dir = from.toVector().subtract(c.toVector());
        if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
        Location at = c.clone().add(dir.normalize().multiply(0.8));
        c.getWorld().spawnParticle(Particle.DUST, at, 14, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(BLUE, 1.1f), true);
        c.getWorld().spawnParticle(Particle.DUST, at, 6, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(ICE, 0.9f), true);
        c.getWorld().spawnParticle(Particle.END_ROD, at, 3, 0.1, 0.1, 0.1, 0.02, null, true);
        plugin.weapons().fx().wave(at, "w_ring", BLUE, 0.3f, 1.8f, 6, WeaponFx.alongY(dir).rotateX((float) Math.PI / 2), Display.Billboard.FIXED);
    }

    // ================================================================= Sukuna : Roi des Fléaux

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kingOfCurses(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !sukuna.contains(p.getUniqueId()) || !(e.getEntity() instanceof LivingEntity t)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        // vol de vie
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) p.setHealth(Math.min(max.getValue(), p.getHealth() + e.getFinalDamage() * cfg("tenue-sukuna.set.lifesteal", 0.15)));
        // un coup sur trois : Démantèlement
        String k = p.getUniqueId() + ":" + t.getUniqueId();
        int n = combo.merge(k, 1, Integer::sum);
        if (n < 3) return;
        combo.remove(k);
        if (combo.size() > 500) combo.clear();
        Bukkit.getScheduler().runTaskLater(plugin, () -> dismantle(p, t), 4L);
    }

    private void dismantle(Player p, LivingEntity t) {
        if (!p.isOnline() || !t.isValid() || t.isDead()) return;
        Location c = t.getLocation().add(0, t.getHeight() * 0.55, 0);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        WeaponFx fx = plugin.weapons().fx();
        for (int i = 0; i < 2; i++) {
            Quaternionf q = new Quaternionf().rotateY((float) r.nextDouble(6.28)).rotateX((float) r.nextDouble(-0.9, 0.9));
            fx.tpop(c, "w_slash", i == 0 ? CRIMSON : DARK, 3.2f, 3.2f, 1, 1, 1, 3, Display.Billboard.FIXED, q);
        }
        c.getWorld().spawnParticle(Particle.DUST, c, 30, 0.4, 0.5, 0.4, 0, new Particle.DustOptions(CRIMSON, 1.4f), true);
        c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, c, 2, 0.3, 0.3, 0.3, 0, null, true);
        c.getWorld().playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.3f, 0.5f);
        c.getWorld().playSound(c, Sound.ITEM_TRIDENT_HIT, 1f, 0.6f);
        double dmg = cfg("tenue-sukuna.set.dismantle-damage", 5);
        Boss b = plugin.king();
        if (b != null && b.isBoss(t)) {
            b.hurt(p, dmg * 2);
            return;
        }
        t.setNoDamageTicks(0);
        t.damage(dmg, DamageSource.builder(DamageType.MAGIC).withCausingEntity(p).withDirectEntity(p).build());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void noFire(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !sukuna.contains(p.getUniqueId())) return;
        switch (e.getCause()) {
            case FIRE, FIRE_TICK, HOT_FLOOR -> {
                e.setCancelled(true);
                p.setFireTicks(0);
            }
            default -> {
            }
        }
    }
}
