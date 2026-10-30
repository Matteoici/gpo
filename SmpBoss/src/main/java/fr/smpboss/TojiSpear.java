package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
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
 * Lance du Ciel Inversé (prestige, l'arme de Toji).
 * <p>
 * RESTRICTION CÉLESTE : tant que tu la tiens en main, ton corps est poussé au maximum (vie, vitesse, dégâts, saut, recul,
 * chute, pas de faim) mais tu n'as plus droit à rien d'autre : pas de totem, pas de potion, pas de pomme, pas de perle,
 * pas d'élytres, rien dans l'autre main, pas d'arc, et aucune relique (ni bonus, ni pouvoir : Black Flash, Capricorne...).
 * <p>
 * La lance annule les techniques : chaque coup coupe le Black Flash chargé de la cible, traverse le Fantôme du Net,
 * et bloque ses pouvoirs quelques secondes.
 * <p>
 * PERCÉE DU CIEL INVERSÉ (accroupi + clic droit) : tu te jettes sur ta cible, la lance la traverse, cinq entailles
 * sous tous les angles, puis le coup final.
 */
final class TojiSpear implements Listener {

    static final String ID = "lance_ciel";
    private static final Color STEEL = Color.fromRGB(225, 232, 245), BLOOD = Color.fromRGB(170, 8, 20), DARK = Color.fromRGB(40, 0, 6);

    private static TojiSpear instance;

    private final SmpBoss plugin;
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, Long> specialCd = new HashMap<>();
    /** techniques coupées par la lance (jusqu'à quand) */
    private final Map<UUID, Long> nullified = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final NamespacedKey hp, speed, dmg, kb, fall, jump, step, atkSpeed, setSpeed, setJump, setFall, setDmg;
    /** Tenue de Toji complète (3 pièces) : Corps Céleste */
    private final Set<UUID> fullSet = new HashSet<>();

    TojiSpear(SmpBoss plugin) {
        this.plugin = plugin;
        instance = this;
        hp = new NamespacedKey(plugin, "toji_hp");
        speed = new NamespacedKey(plugin, "toji_speed");
        dmg = new NamespacedKey(plugin, "toji_dmg");
        kb = new NamespacedKey(plugin, "toji_kb");
        fall = new NamespacedKey(plugin, "toji_fall");
        jump = new NamespacedKey(plugin, "toji_jump");
        step = new NamespacedKey(plugin, "toji_step");
        atkSpeed = new NamespacedKey(plugin, "toji_atkspeed");
        setSpeed = new NamespacedKey(plugin, "tojiset_speed");
        setJump = new NamespacedKey(plugin, "tojiset_jump");
        setFall = new NamespacedKey(plugin, "tojiset_fall");
        setDmg = new NamespacedKey(plugin, "tojiset_dmg");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    private double cfg2(String path, double def) {
        return plugin.getConfig().getDouble("weapons.tenue-toji." + path, def);
    }

    /** Corps Céleste : ni poison, ni faiblesse, ni faim. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bodyImmune(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || !fullSet.contains(p.getUniqueId()) || e.getNewEffect() == null) return;
        PotionEffectType t = e.getNewEffect().getType();
        if (t == PotionEffectType.POISON || t == PotionEffectType.WEAKNESS || t == PotionEffectType.HUNGER) e.setCancelled(true);
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("weapons.lance-ciel." + path, def);
    }

    private Ultimates ult() {
        return plugin.weapons().ultimates;
    }

    private WeaponFx fx() {
        return ult().weaponFx;
    }

    /** Restriction Céleste en cours (la lance en main) : plus aucun pouvoir ni objet. */
    static boolean restricted(Player p) {
        return instance != null && instance.active.contains(p.getUniqueId());
    }

    /** Techniques bloquées : sous Restriction Céleste, ou coupées par un coup de la lance. */
    static boolean blocked(Player p) {
        if (instance == null) return false;
        if (instance.active.contains(p.getUniqueId())) return true;
        Long u = instance.nullified.get(p.getUniqueId());
        return u != null && u > System.currentTimeMillis();
    }

    /** Message quand un pouvoir est refusé. */
    static void deny(Player p) {
        if (instance == null) return;
        p.sendActionBar(parse(instance.plugin.msg(instance.active.contains(p.getUniqueId()) ? "lance-ciel.no-power" : "lance-ciel.nullified")));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f);
    }

    private boolean holds(Player p) {
        return ID.equals(plugin.weapons().id(p.getInventory().getItemInMainHand()));
    }

    // ================================================================= Restriction Céleste

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean on = holds(p) && !p.isDead() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR;
            // Tenue de Toji : les 3 pièces = Corps Céleste
            boolean set = plugin.weapons().wearsOutfit(p, Weapons.TENUE) && !p.isDead();
            if (set && fullSet.add(id)) {
                set(p, Attribute.MOVEMENT_SPEED, setSpeed, cfg2("set.speed", 0.15), AttributeModifier.Operation.ADD_SCALAR);
                set(p, Attribute.JUMP_STRENGTH, setJump, cfg2("set.jump", 0.1), AttributeModifier.Operation.ADD_NUMBER);
                set(p, Attribute.SAFE_FALL_DISTANCE, setFall, cfg2("set.safe-fall", 6), AttributeModifier.Operation.ADD_NUMBER);
                set(p, Attribute.ATTACK_DAMAGE, setDmg, cfg2("set.damage", 0.15), AttributeModifier.Operation.ADD_SCALAR);
                p.sendActionBar(parse(plugin.msg("lance-ciel.set-on")));
                p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.6f);
            } else if (!set && fullSet.remove(id)) {
                for (Attribute a : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.SAFE_FALL_DISTANCE, Attribute.ATTACK_DAMAGE}) {
                    AttributeInstance inst = p.getAttribute(a);
                    if (inst != null) for (NamespacedKey k : new NamespacedKey[]{setSpeed, setJump, setFall, setDmg}) inst.removeModifier(k);
                }
            }
            if (on && !active.contains(id)) enter(p);
            else if (!on && active.contains(id)) leave(p);
            if (on) {
                // pas de faim, et le corps récupère tout seul
                if (p.getFoodLevel() < 20) p.setFoodLevel(20);
                p.setSaturation(Math.max(p.getSaturation(), 5f));
                if (Bukkit.getCurrentTick() % 40 == 0) {
                    var max = p.getAttribute(Attribute.MAX_HEALTH);
                    if (max != null && p.getHealth() < max.getValue()) p.setHealth(Math.min(max.getValue(), p.getHealth() + cfg("restriction.regen", 1)));
                }
            }
        }
        active.removeIf(u -> Bukkit.getPlayer(u) == null);
        fullSet.removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    private void enter(Player p) {
        active.add(p.getUniqueId());
        set(p, Attribute.MAX_HEALTH, hp, cfg("restriction.health", 20), AttributeModifier.Operation.ADD_NUMBER);
        set(p, Attribute.MOVEMENT_SPEED, speed, cfg("restriction.speed", 0.45), AttributeModifier.Operation.ADD_SCALAR);
        set(p, Attribute.ATTACK_DAMAGE, dmg, cfg("restriction.damage", 0.6), AttributeModifier.Operation.ADD_SCALAR);
        set(p, Attribute.ATTACK_SPEED, atkSpeed, cfg("restriction.attack-speed", 0.6), AttributeModifier.Operation.ADD_NUMBER);
        set(p, Attribute.KNOCKBACK_RESISTANCE, kb, cfg("restriction.knockback-resistance", 0.6), AttributeModifier.Operation.ADD_NUMBER);
        set(p, Attribute.SAFE_FALL_DISTANCE, fall, cfg("restriction.safe-fall", 10), AttributeModifier.Operation.ADD_NUMBER);
        set(p, Attribute.JUMP_STRENGTH, jump, cfg("restriction.jump", 0.18), AttributeModifier.Operation.ADD_NUMBER);
        set(p, Attribute.STEP_HEIGHT, step, cfg("restriction.step", 0.5), AttributeModifier.Operation.ADD_NUMBER);
        // plus aucune relique (bonus retirés)
        plugin.relics().apply(p);
        // plus de bonus de potions (sauf ceux des balises)
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe.getType().getCategory() == PotionEffectTypeCategory.BENEFICIAL && !pe.isAmbient() && !pe.isInfinite()) p.removePotionEffect(pe.getType());
        }
        p.sendActionBar(parse(plugin.msg("lance-ciel.restriction-on")));
        p.playSound(p, "limitless:toji.equip", SoundCategory.PLAYERS, 1f, 1f);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.8f, 0.6f);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0, new Particle.DustOptions(DARK, 1.2f), true);
    }

    private void leave(Player p) {
        active.remove(p.getUniqueId());
        for (Attribute a : new Attribute[]{Attribute.MAX_HEALTH, Attribute.MOVEMENT_SPEED, Attribute.ATTACK_DAMAGE, Attribute.ATTACK_SPEED,
                Attribute.KNOCKBACK_RESISTANCE, Attribute.SAFE_FALL_DISTANCE, Attribute.JUMP_STRENGTH, Attribute.STEP_HEIGHT}) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) continue;
            for (NamespacedKey k : new NamespacedKey[]{hp, speed, dmg, atkSpeed, kb, fall, jump, step}) inst.removeModifier(k);
        }
        plugin.relics().apply(p);
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && p.getHealth() > max.getValue()) p.setHealth(max.getValue());
        if (p.isOnline()) p.sendActionBar(parse(plugin.msg("lance-ciel.restriction-off")));
    }

    private static void set(Player p, Attribute a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        inst.removeModifier(key);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (active.contains(e.getPlayer().getUniqueId())) leave(e.getPlayer());
    }

    // ---- ce qui est interdit sous Restriction Céleste

    @EventHandler(priority = EventPriority.HIGHEST)
    public void noTotem(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p && restricted(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void noConsume(PlayerItemConsumeEvent e) {
        if (restricted(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(parse(plugin.msg("lance-ciel.no-item")));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void noHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && restricted(p) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void noUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!restricted(p)) return;
        if (e.getHand() == EquipmentSlot.OFF_HAND && e.getItem() != null) {
            // rien dans l'autre main : bouclier, nourriture, perles, fusées...
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            if (e.getAction() == Action.RIGHT_CLICK_AIR) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void noProjectile(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && restricted(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void noGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && restricted(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void noPotions(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || !restricted(p) || e.getNewEffect() == null) return;
        if (e.getNewEffect().getType().getCategory() != PotionEffectTypeCategory.BENEFICIAL) return;
        switch (e.getCause()) {
            case POTION_DRINK, POTION_SPLASH, AREA_EFFECT_CLOUD, TOTEM, FOOD, ARROW -> e.setCancelled(true);
            default -> {
            }
        }
    }

    // ================================================================= la lance annule les techniques

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !holds(p) || !(e.getEntity() instanceof Player t)) return;
        nullified.put(t.getUniqueId(), System.currentTimeMillis() + (long) (cfg("nullify-seconds", 4) * 1000));
        BlackFlash.disarm(t);
        t.getWorld().spawnParticle(Particle.DUST, t.getLocation().add(0, 1.1, 0), 8, 0.25, 0.4, 0.25, 0, new Particle.DustOptions(BLOOD, 1.1f), true);
    }

    /** Vrai si celui qui frappe tient la lance (le Fantôme du Net ne marche pas contre elle). */
    static boolean piercing(Entity damager) {
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity sh) damager = sh;
        return damager instanceof Player p && instance != null && instance.holds(p);
    }

    // ================================================================= Percée du Ciel Inversé

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpecial(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || !holds(p)) return;
        e.setCancelled(true);
        if (busy.contains(p.getUniqueId())) return;
        long now = System.currentTimeMillis();
        Long until = specialCd.get(p.getUniqueId());
        if (until != null && until > now) {
            p.sendActionBar(parse(plugin.msg("weapons.cooldown").replace("<seconds>", String.valueOf((until - now) / 1000 + 1))));
            return;
        }
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        RayTraceResult hit = w.rayTrace(eye, eye.getDirection(), cfg("special.range", 18), FluidCollisionMode.NEVER, true, 1.0,
                en -> en instanceof LivingEntity && !en.equals(p) && !(en instanceof Player o && o.getGameMode() == org.bukkit.GameMode.SPECTATOR));
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity target0)) {
            p.sendActionBar(parse(plugin.msg("lance-ciel.no-target")));
            return;
        }
        LivingEntity target = target0;
        Boss b = plugin.king();
        if (b != null && b.isBoss(target0) && b.mainEntity() != null) target = b.mainEntity();
        boolean duels = plugin.getConfig().getBoolean("weapons.lance-ciel.in-duels", true);
        if (!isBoss(target) && !ult().targets(p, target.getLocation(), 0.5, duels).contains(target)) {
            p.sendActionBar(parse(plugin.msg("lance-ciel.no-target")));
            return;
        }
        if (!isBoss(target) && !allowed(p, target)) return;
        specialCd.put(p.getUniqueId(), now + (long) (cfg("special.cooldown-seconds", 90) * 1000));
        special(p, target);
    }

    private void special(Player p, LivingEntity t) {
        UUID pid = p.getUniqueId();
        busy.add(pid);
        boolean boss = isBoss(t);
        World w = p.getWorld();
        Location from = p.getLocation();
        // la ruée : traînée sombre jusqu'à la cible
        Location front = around(t, Math.atan2(from.getZ() - t.getLocation().getZ(), from.getX() - t.getLocation().getX()), boss ? 3.2 : 1.6);
        Vector path = front.toVector().subtract(from.toVector());
        for (double d = 0; d < path.length(); d += 0.6) {
            Location at = from.clone().add(path.clone().normalize().multiply(d)).add(0, 1, 0);
            w.spawnParticle(Particle.DUST, at, 2, 0.15, 0.4, 0.15, 0, new Particle.DustOptions(DARK, 1.4f), true);
        }
        w.spawnParticle(Particle.SWEEP_ATTACK, from.clone().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0, null, true);
        Arena.allowTeleport(p);
        p.teleport(front);
        ult().root(p);
        Location c0 = t.getLocation().add(0, t.getHeight() * 0.55, 0);
        sound(c0, "toji.special", 48);
        PackFx.fallbackSound(c0, Sound.ITEM_TRIDENT_THROW, 1.5f, 0.6f);
        int total = 50;
        if (t instanceof Player tp) {
            BlackFlash.stun(tp, total + 4);
            tp.sendActionBar(parse(plugin.msg("lance-ciel.stunned")));
        }
        if (!boss) ult().freezeFor(t, total + 4);
        p.sendActionBar(parse(plugin.msg("lance-ciel.special")));
        double max = maxHealth(t);
        double pct = cfg("special.percent", 70) / 100.0;
        // part de chaque coup : percée 20 %, 5 entailles 10 % chacune, coup final 30 %
        double[] share = {0.2, 0.1, 0.1, 0.1, 0.1, 0.1, 0.3};
        int[] at = {4, 10, 15, 20, 25, 30, 44};
        int[] n = {0};
        int[] tick = {0};
        double[] ang = {Math.atan2(front.getZ() - t.getLocation().getZ(), front.getX() - t.getLocation().getX())};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = tick[0]++;
            boolean broken = !p.isOnline() || p.isDead() || !t.isValid() || t.isDead() || p.getWorld() != t.getWorld();
            if (broken || k > total + 2) {
                task[0].cancel();
                busy.remove(pid);
                if (p.isOnline()) ult().unroot(p, false);
                return;
            }
            if (n[0] < at.length && k == at[n[0]]) {
                int i = n[0]++;
                Location c = t.getLocation().add(0, t.getHeight() * 0.55, 0);
                if (i >= 1 && i <= 5) {
                    // une entaille sous un nouvel angle
                    ang[0] += ThreadLocalRandom.current().nextDouble(1.2, 2.4);
                    Location pos = around(t, ang[0], boss ? 3.2 : 1.6);
                    Arena.allowTeleport(p);
                    p.teleport(pos);
                    ult().root(p);
                }
                if (i == 6) {
                    // coup final : il plonge d'en haut
                    Location pos = around(t, ang[0] + Math.PI, boss ? 3.2 : 1.5);
                    pos.setPitch(35);
                    Arena.allowTeleport(p);
                    p.teleport(pos);
                    ult().root(p);
                }
                strikeFx(p, t, c, i);
                if (boss) {
                    Boss bb = plugin.king();
                    if (bb != null) bb.hurt(p, cfg("special.boss-damage", 700) * share[i]);
                } else if (t instanceof Player) {
                    applyDamage(p, t, max * pct * share[i]);
                } else {
                    applyDamage(p, t, cfg("special.damage", 160) * share[i]);
                }
            }
        }, 1L, 1L);
    }

    private void strikeFx(Player p, LivingEntity t, Location c, int i) {
        WeaponFx fx = fx();
        World w = c.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean big = i == 0 || i == 6;
        Vector dir = c.toVector().subtract(p.getEyeLocation().toVector());
        if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
        dir.normalize();
        if (big) {
            // la lame traverse : longue pointe d'acier à travers la cible
            fx.tpop(c.clone().add(dir.clone().multiply(1.2)), "w_spike_x", STEEL, 0.6f, 4.5f, 0.6f, 1, 3, 3, Display.Billboard.FIXED, WeaponFx.alongY(dir));
            fx.tpop(c, "w_glow", BLOOD, 4, 4, 4, 1, 1, 4, Display.Billboard.CENTER, null);
            impact(c, p, t);
        }
        // entaille blanche bordée de rouge
        Quaternionf q = new Quaternionf().rotateY((float) r.nextDouble(6.28)).rotateX((float) r.nextDouble(-0.8, 0.8));
        fx.tpop(c, "w_slash", STEEL, big ? 5 : 3.6f, big ? 5 : 3.6f, 1, 1, 1, 3, Display.Billboard.FIXED, q);
        fx.tpop(c, "w_slash", BLOOD, big ? 5.6f : 4, big ? 5.6f : 4, 1, 1, 2, 4, Display.Billboard.FIXED, new Quaternionf(q).rotateZ(0.12f));
        // sang
        w.spawnParticle(Particle.DUST, c, big ? 70 : 30, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(BLOOD, big ? 1.8f : 1.4f), true);
        w.spawnParticle(Particle.BLOCK, c, big ? 60 : 25, 0.4, 0.4, 0.4, 0.3, Material.REDSTONE_BLOCK.createBlockData(), true);
        w.spawnParticle(Particle.SWEEP_ATTACK, c, 1, 0, 0, 0, 0, null, true);
        if (big) w.spawnParticle(Particle.CRIT, c, 40, 0.4, 0.4, 0.4, 0.6, null, true);
        PackFx.fallbackSound(c, big ? Sound.ENTITY_PLAYER_ATTACK_CRIT : Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, big ? 0.6f : 1.1f);
        t.playHurtAnimation((float) r.nextDouble(360));
    }

    /** Images d'impact plein écran (noir, puis rouge) pour la cible, le lanceur et ceux tout près. */
    private void impact(Location c, Player p, LivingEntity t) {
        char[] seq = {'', ''};
        for (int i = 0; i < seq.length; i++) {
            char g = seq[i];
            boolean last = i == seq.length - 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                var txt = net.kyori.adventure.text.Component.text(String.valueOf(g)).font(net.kyori.adventure.key.Key.key(PackFx.NS, "fx"))
                        .color(net.kyori.adventure.text.format.NamedTextColor.WHITE).shadowColor(net.kyori.adventure.text.format.ShadowColor.shadowColor(0));
                var title = net.kyori.adventure.title.Title.title(txt, net.kyori.adventure.text.Component.empty(),
                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(last ? 60 : 90), java.time.Duration.ofMillis(last ? 140 : 0)));
                for (Player o : c.getWorld().getPlayers()) {
                    if (!PackStatus.has(o)) continue;
                    if (o.equals(p) || o.equals(t) || o.getLocation().distanceSquared(c) <= 100) o.showTitle(title);
                }
            }, 1L + i * 2L);
        }
    }

    private static Location around(LivingEntity t, double a, double dist) {
        Location c = t.getLocation();
        Location p = c.clone().add(Math.cos(a) * dist, 0, Math.sin(a) * dist);
        Vector look = c.toVector().subtract(p.toVector());
        p.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));
        p.setPitch(10);
        return p;
    }

    private void sound(Location c, String key, double radius) {
        for (Player o : c.getWorld().getPlayers()) {
            if (!PackStatus.has(o) || o.getLocation().distanceSquared(c) > radius * radius) continue;
            double d = o.getLocation().distance(c);
            o.playSound(o, PackFx.NS + ":" + key, SoundCategory.PLAYERS, (float) Math.max(0.35, 1 - d / radius), 1f);
        }
    }

    // ================================================================= Tribâton (légendaire) : le Nuage Joueur

    private final Map<UUID, Long> tribCd = new HashMap<>();
    private static final Color RED_LACQ = Color.fromRGB(190, 30, 30), GOLD = Color.fromRGB(240, 190, 70);

    /** Clic droit : trois tourbillons du bâton à trois sections autour de toi ; le dernier envoie tout en l'air. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onTribaton(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !Weapons.TRIBATON.equals(plugin.weapons().id(e.getItem()))) return;
        org.bukkit.block.Block bl = e.getClickedBlock();
        if (bl != null && bl.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        e.setCancelled(true); // pas de chemin de terre avec la pelle
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long until = tribCd.get(p.getUniqueId());
        if (until != null && until > now) {
            p.sendActionBar(parse(plugin.msg("weapons.cooldown").replace("<seconds>", String.valueOf((until - now) / 1000 + 1))));
            return;
        }
        tribCd.put(p.getUniqueId(), now + (long) (plugin.getConfig().getDouble("weapons.tribaton.cooldown-seconds", 10) * 1000));
        double r = plugin.getConfig().getDouble("weapons.tribaton.radius", 4.5), dmg = plugin.getConfig().getDouble("weapons.tribaton.damage", 7);
        boolean duels = plugin.getConfig().getBoolean("weapons.tribaton.in-duels", true);
        for (int spin = 0; spin < 3; spin++) {
            int sp = spin;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || p.isDead()) return;
                Location c = p.getLocation().add(0, 1, 0);
                World w = p.getWorld();
                boolean last = sp == 2;
                // le bâton tourne : anneau d'entailles rouges et dorées
                Quaternionf q = new Quaternionf().rotateY((float) (sp * 1.1));
                fx().tpop(c.clone().add(0, -0.3, 0), "w_slash_flat", sp % 2 == 0 ? RED_LACQ : GOLD, (float) r * 2.1f, 1, (float) r * 2.1f, 1, 2, 3,
                        Display.Billboard.FIXED, q);
                fx().wave(c.clone().add(0, -0.9, 0), "w_ring_flat", RED_LACQ, 1, (float) r * 2.2f, 6, null, Display.Billboard.FIXED);
                for (int i = 0; i < 16; i++) {
                    double a = Math.PI * 2 * i / 16 + sp;
                    Location l = c.clone().add(Math.cos(a) * r * 0.8, -0.2, Math.sin(a) * r * 0.8);
                    w.spawnParticle(Particle.DUST, l, 2, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(i % 4 == 0 ? GOLD : RED_LACQ, 1.2f), true);
                }
                w.spawnParticle(Particle.SWEEP_ATTACK, c, 3, r * 0.4, 0.2, r * 0.4, 0, null, true);
                w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.7f + sp * 0.15f);
                w.playSound(c, Sound.BLOCK_CHAIN_HIT, 1.2f, 0.8f + sp * 0.1f);
                sound(c, "toji.slash", 24);
                for (LivingEntity le : ult().targets(p, c, r, duels)) {
                    if (!isBoss(le) && !allowed(p, le)) continue;
                    le.setNoDamageTicks(0);
                    le.damage(last ? dmg * 1.5 : dmg, p);
                    if (isBoss(le)) continue;
                    Vector out = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                    if (out.lengthSquared() < 0.01) out = new Vector(1, 0, 0);
                    le.setVelocity(out.normalize().multiply(last ? 1.3 : 0.6).setY(last ? 0.9 : 0.25));
                    if (last) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true));
                }
            }, spin * 5L);
        }
    }

    /** Au corps à corps, le Tribâton repousse fort. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTribHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity le) || isBoss(le)) return;
        if (!Weapons.TRIBATON.equals(plugin.weapons().id(p.getInventory().getItemInMainHand()))) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!le.isValid()) return;
            Vector out = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (out.lengthSquared() < 0.01) return;
            le.setVelocity(le.getVelocity().add(out.normalize().multiply(plugin.getConfig().getDouble("weapons.tribaton.knockback", 0.7)).setY(0.2)));
        });
    }

    // ================================================================= dégâts

    private boolean isBoss(Entity e) {
        Boss b = plugin.king();
        return b != null && b.isBoss(e);
    }

    private static double maxHealth(LivingEntity t) {
        var a = t.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private boolean allowed(Player caster, LivingEntity t) {
        var ev = new EntityDamageByEntityEvent(caster, t, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build(), 1.0);
        Bukkit.getPluginManager().callEvent(ev);
        return !ev.isCancelled();
    }

    private static void applyDamage(Player caster, LivingEntity t, double amount) {
        if (amount <= 0 || t.isDead()) return;
        double h = t.getHealth() + t.getAbsorptionAmount();
        if (h - amount <= 0.01) {
            t.setNoDamageTicks(0);
            t.damage(amount * 50, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build());
            return;
        }
        double abs = t.getAbsorptionAmount();
        double fromAbs = Math.min(abs, amount);
        t.setAbsorptionAmount(abs - fromAbs);
        t.setHealth(Math.max(0.5, t.getHealth() - (amount - fromAbs)));
        t.getWorld().playSound(t.getLocation(), t instanceof Player ? Sound.ENTITY_PLAYER_HURT : Sound.ENTITY_GENERIC_HURT, 1f, 0.8f);
    }
}
