package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Les implants d'Adam Smasher (reliques de Night City).
 * <ul>
 *   <li>Sandevistan de Martinez (prestige) / Sandevistan prototype (mythique) : <b>accroupi en sprintant</b>. Le temps ralentit
 *   pour tout le monde autour, sauf toi : tu vas très vite, tu frappes plus fort, et tu laisses des images vertes et roses derrière toi.</li>
 *   <li>Châssis de Smasher, Lames Mantis, Bras Gorilla, Second Cœur, Kerenzikov, Optiques Kiroshi, Tendons renforcés,
 *   Armure subdermique, Pompe sanguine, Synapses boostées, Peau de titane : des passifs.</li>
 * </ul>
 * L'IEM d'Adam Smasher coupe tous les implants quelques secondes. La Restriction Céleste (Toji) les coupe aussi, et Toji n'est jamais ralenti.
 */
final class Cyberware implements Listener {

    static final String SANDE = "sandevistan_martinez", PROTO = "sandevistan_prototype", CHASSIS = "chassis_smasher", MANTIS = "lames_mantis",
            GORILLA = "bras_gorilla", HEART = "second_coeur", KEREN = "kerenzikov", KIROSHI = "optiques_kiroshi", TENDONS = "tendons_renforces",
            SUBDERM = "armure_subdermique", PUMP = "pompe_sanguine", SYNAPSE = "synapses_boost", TITAN = "peau_titane";

    static final Color GREEN = Color.fromRGB(60, 255, 120), PINK = Color.fromRGB(255, 42, 109), CYAN = Color.fromRGB(5, 217, 232),
            YELLOW = Color.fromRGB(252, 238, 10);

    static Cyberware INSTANCE;

    private final SmpBoss plugin;
    /** implants coupés (IEM) jusqu'à */
    private final Map<UUID, Long> empUntil = new HashMap<>();
    private final Map<UUID, Long> sandeCd = new HashMap<>(), heartCd = new HashMap<>(), kerenCd = new HashMap<>();
    /** Sandevistan en cours : joueur -> fin (tick serveur) */
    private final Map<UUID, Integer> running = new HashMap<>();
    private final Map<UUID, Boolean> runningPrestige = new HashMap<>();
    private final Map<UUID, Integer> gorillaHits = new HashMap<>();
    private final Set<UUID> slowedProjectiles = new HashSet<>();
    private final SandeFx fx;

    Cyberware(SmpBoss plugin) {
        INSTANCE = this;
        this.plugin = plugin;
        this.fx = new SandeFx(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::passives, 20L, 40L);
    }

    void shutdown() {
        fx.stopAll();
    }

    void resetCooldowns(UUID id) {
        sandeCd.remove(id);
        heartCd.remove(id);
        kerenCd.remove(id);
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("cyberware." + path, def);
    }

    private boolean wears(Player p, String id) {
        return plugin.store().of(p.getUniqueId()).equipped.contains(id);
    }

    /** Les implants marchent-ils en ce moment ? (pas d'IEM, pas de Restriction Céleste) */
    private boolean online(Player p) {
        if (TojiSpear.restricted(p)) return false;
        Long u = empUntil.get(p.getUniqueId());
        return u == null || u < System.currentTimeMillis();
    }

    /** IEM d'Adam Smasher : implants coupés pendant `ticks`, le Sandevistan s'arrête net. */
    static void emp(Player p, int ticks) {
        Cyberware c = INSTANCE;
        if (c == null) return;
        c.empUntil.put(p.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        if (c.running.containsKey(p.getUniqueId())) c.stop(p, true);
    }

    static boolean sandevistan(Player p) {
        return INSTANCE != null && INSTANCE.running.containsKey(p.getUniqueId());
    }

    private Boss king() {
        return plugin.king();
    }

    private boolean isBoss(Entity e) {
        Boss b = king();
        return b != null && b.isBoss(e);
    }

    // ================================================================= Sandevistan

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        if (!p.isSprinting()) return;
        boolean prestige = wears(p, SANDE), proto = !prestige && wears(p, PROTO);
        if (!prestige && !proto) return;
        if (running.containsKey(p.getUniqueId())) return;
        if (TojiSpear.blocked(p)) {
            TojiSpear.deny(p);
            return;
        }
        if (!online(p)) {
            SmpBoss.hud(p, parse(plugin.msg("cyberware.offline")));
            return;
        }
        long now = System.currentTimeMillis();
        Long until = sandeCd.get(p.getUniqueId());
        if (until != null && until > now) {
            SmpBoss.hud(p, parse(plugin.msg("weapons.cooldown").replace("<seconds>", String.valueOf((until - now) / 1000 + 1))));
            return;
        }
        String k = prestige ? "sandevistan." : "prototype.";
        int secs = (int) cfg(k + "seconds", prestige ? 10 : 6);
        sandeCd.put(p.getUniqueId(), now + (long) ((cfg(k + "cooldown-seconds", prestige ? 50 : 90) + secs) * 1000));
        running.put(p.getUniqueId(), Bukkit.getCurrentTick() + secs * 20);
        runningPrestige.put(p.getUniqueId(), prestige);
        int amp = prestige ? 2 : 1;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, secs * 20, amp + 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, secs * 20, amp, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, secs * 20, 1, false, false, true));
        SmpBoss.hud(p, parse(plugin.msg(prestige ? "cyberware.sandevistan-on" : "cyberware.prototype-on")));
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.5f, 2f);
        w.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.2f, 1.8f);
        fx.start(p, prestige, secs);
        // battement grave pour ceux qui sont ralentis
        double r = cfg(k + "radius", prestige ? 24 : 16);
        for (Player o : w.getPlayers()) {
            if (o != p && o.getLocation().distanceSquared(p.getLocation()) <= r * r) o.playSound(o.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.5f);
        }
    }

    /** Chaque tick : le temps ralentit autour des Sandevistan actifs. */
    private void tick() {
        if (running.isEmpty()) return;
        int now = Bukkit.getCurrentTick();
        for (UUID id : new ArrayList<>(running.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead() || !p.isValid()) {
                running.remove(id);
                runningPrestige.remove(id);
                continue;
            }
            if (now >= running.get(id) || !online(p)) {
                stop(p, false);
                continue;
            }
            boolean prestige = runningPrestige.getOrDefault(id, false);
            String k = prestige ? "sandevistan." : "prototype.";
            double r = cfg(k + "radius", prestige ? 24 : 16);
            int slow = (int) cfg(k + "slowness", prestige ? 6 : 3);
            Location c = p.getLocation();
            World w = p.getWorld();
            fx.tick(p, prestige, running.get(id) - now);
            // images rémanentes en particules (en plus des copies)
            if (now % 2 == 0) {
                Vector back = c.getDirection().setY(0);
                if (back.lengthSquared() > 0.01) back.normalize().multiply(-0.25);
                Color[] cols = prestige ? new Color[]{GREEN, PINK, CYAN} : new Color[]{GREEN, PINK};
                for (int i = 0; i < cols.length; i++) {
                    Location g = c.clone().add(back.clone().multiply(i + 1));
                    Particle.DustOptions d = new Particle.DustOptions(cols[i], 1.0f);
                    for (double y = 0.1; y < 1.9; y += 0.3) w.spawnParticle(Particle.DUST, g.clone().add(0, y, 0), 2, 0.17, 0.05, 0.17, 0, d, true);
                }
            }
            if (now % 10 == 0) p.playSound(c, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.6f, 2f);
            // le monde autour ralentit
            if (now % 5 == 0) {
                for (Entity en : w.getNearbyEntities(c, r, r * 0.6, r)) {
                    if (en == p) continue;
                    if (en instanceof Projectile pr) {
                        if (pr.getShooter() == p || !slowedProjectiles.add(pr.getUniqueId())) continue;
                        pr.setVelocity(pr.getVelocity().multiply(prestige ? 0.15 : 0.35));
                        continue;
                    }
                    if (!(en instanceof LivingEntity le) || le.isDead()) continue;
                    if (isBoss(le) || TojiSpear.ccImmune(le) || le instanceof Player o && (sandevistan(o) || o.getGameMode() == org.bukkit.GameMode.SPECTATOR
                            || o.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                    if (le instanceof Player o && !allowed(p, o)) continue;
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 12, slow, false, false, false));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 12, prestige ? 2 : 1, false, false, false));
                    if (prestige) le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 12, 0, false, false, false));
                    Vector v = le.getVelocity();
                    if (v.getY() > 0.05) le.setVelocity(v.setY(v.getY() * 0.5));
                    if (now % 10 == 0) w.spawnParticle(Particle.DUST, le.getLocation().add(0, le.getHeight() * 0.6, 0), 2, 0.2, 0.3, 0.2, 0,
                            new Particle.DustOptions(Color.fromRGB(120, 255, 170), 0.8f), true);
                }
            }
        }
        if (slowedProjectiles.size() > 500) slowedProjectiles.clear();
    }

    private void stop(Player p, boolean emp) {
        UUID id = p.getUniqueId();
        fx.stop(p);
        running.remove(id);
        boolean prestige = Boolean.TRUE.equals(runningPrestige.remove(id));
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.HASTE);
        p.removePotionEffect(PotionEffectType.JUMP_BOOST);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.2f, 1.6f);
        SmpBoss.hud(p, parse(plugin.msg(emp ? "cyberware.emp-cut" : "cyberware.sandevistan-off")));
        // le prototype fait payer : ton corps lâche
        if (!prestige && !TojiSpear.ccImmune(p)) {
            int s = (int) cfg("prototype.backlash-seconds", 5);
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, s * 20, 0, false, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, s * 10, 0, false, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, false, false, false));
        }
    }

    // ================================================================= coups donnés

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (probing || !(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity t)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!online(p)) return;
        double mult = 1;
        // Sandevistan : plus fort
        if (running.containsKey(p.getUniqueId())) {
            boolean prestige = runningPrestige.getOrDefault(p.getUniqueId(), false);
            mult *= prestige ? cfg("sandevistan.damage", 1.5) : cfg("prototype.damage", 1.25);
            t.getWorld().spawnParticle(Particle.DUST, t.getLocation().add(0, t.getHeight() * 0.6, 0), 8, 0.3, 0.3, 0.3, 0,
                    new Particle.DustOptions(prestige ? GREEN : PINK, 1.2f), true);
        }
        // Lames Mantis : entailles qui saignent
        if (wears(p, MANTIS)) {
            if (ThreadLocalRandom.current().nextDouble() < cfg("mantis.chance", 0.3)) {
                mult *= cfg("mantis.damage", 1.35);
                bleed(p, t, (int) cfg("mantis.bleed-seconds", 3), cfg("mantis.bleed-damage", 1.5));
                t.getWorld().spawnParticle(Particle.SWEEP_ATTACK, t.getLocation().add(0, t.getHeight() * 0.6, 0), 1, 0, 0, 0, 0, null, true);
                t.getWorld().playSound(t.getLocation(), Sound.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1f, 1.4f);
            }
            if (p.getFallDistance() > 0.5f) mult *= 1.2;
        }
        // Bras Gorilla : tout le monde vole, et un uppercut tous les 5 coups
        if (wears(p, GORILLA) && !isBoss(t)) {
            int n = gorillaHits.merge(p.getUniqueId(), 1, Integer::sum);
            Vector dir = t.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() < 0.01) dir = p.getLocation().getDirection().setY(0);
            Vector d = dir.normalize();
            boolean upper = n % (int) cfg("gorilla.uppercut-every", 5) == 0;
            if (upper) mult *= cfg("gorilla.uppercut-damage", 1.4);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!t.isValid() || TojiSpear.ccImmune(t)) return;
                if (upper) {
                    t.setVelocity(d.clone().multiply(0.5).setY(1.1));
                    if (t instanceof Player tp) CcGuard.mark(tp, 30);
                    t.getWorld().playSound(t.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.4f, 0.8f);
                    t.getWorld().spawnParticle(Particle.EXPLOSION, t.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0, null, true);
                } else t.setVelocity(t.getVelocity().add(d.clone().multiply(cfg("gorilla.knockback", 0.8)).setY(0.25)));
            });
        }
        // Optiques Kiroshi : la cible est marquée (elle brille)
        if (wears(p, KIROSHI)) t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) cfg("kiroshi.mark-seconds", 4) * 20, 0, false, false, false));
        if (mult != 1) e.setDamage(e.getDamage() * mult);
    }

    private void bleed(Player by, LivingEntity t, int secs, double dmg) {
        int[] i = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (i[0]++ >= secs || !t.isValid() || t.isDead()) {
                task[0].cancel();
                return;
            }
            t.getWorld().spawnParticle(Particle.DUST, t.getLocation().add(0, t.getHeight() * 0.5, 0), 6, 0.2, 0.3, 0.2, 0,
                    new Particle.DustOptions(Color.fromRGB(170, 8, 20), 1f), true);
            if (isBoss(t)) return; // le boss a ses propres PV : le saignement n'y compte pas
            t.setNoDamageTicks(0);
            t.damage(dmg, DamageSource.builder(DamageType.MAGIC).withCausingEntity(by).build());
        }, 20L, 20L);
    }

    // ================================================================= coups reçus

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (probing || !(e.getEntity() instanceof Player p) || !online(p)) return;
        double mult = 1;
        EntityDamageEvent.DamageCause cause = e.getCause();
        boolean attack = e instanceof EntityDamageByEntityEvent;
        boolean projectile = cause == EntityDamageEvent.DamageCause.PROJECTILE;
        // Kerenzikov : esquive (en sprint ou accroupi), le monde ralentit une fraction de seconde
        if (wears(p, KEREN) && attack && (p.isSprinting() || p.isSneaking())) {
            long now = System.currentTimeMillis();
            Long cd = kerenCd.get(p.getUniqueId());
            if ((cd == null || cd < now) && ThreadLocalRandom.current().nextDouble() < cfg("kerenzikov.chance", 0.25)) {
                kerenCd.put(p.getUniqueId(), now + (long) (cfg("kerenzikov.cooldown-seconds", 6) * 1000));
                e.setCancelled(true);
                Vector side = p.getLocation().getDirection().setY(0);
                if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
                side = new Vector(-side.getZ(), 0, side.getX()).normalize().multiply(ThreadLocalRandom.current().nextBoolean() ? 0.9 : -0.9);
                p.setVelocity(side.setY(0.2));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 30, 2, false, false, false));
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1f, 1.6f);
                p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0, new Particle.DustOptions(CYAN, 1f), true);
                SmpBoss.hud(p, parse(plugin.msg("cyberware.dodge")));
                return;
            }
        }
        if (running.containsKey(p.getUniqueId()) && runningPrestige.getOrDefault(p.getUniqueId(), false)) mult *= cfg("sandevistan.damage-taken", 0.75);
        if (wears(p, CHASSIS) && attack) mult *= cfg("chassis.damage-taken", 0.85);
        if (wears(p, CHASSIS) && (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION))
            mult *= 0.5;
        if (wears(p, SUBDERM) && projectile) mult *= cfg("subdermique.projectile-taken", 0.8);
        if (wears(p, TENDONS) && cause == EntityDamageEvent.DamageCause.FALL) mult *= cfg("tendons.fall-taken", 0.4);
        if (wears(p, TITAN) && (cause == EntityDamageEvent.DamageCause.FIRE || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA || cause == EntityDamageEvent.DamageCause.HOT_FLOOR)) mult *= cfg("titane.fire-taken", 0.6);
        if (mult != 1) e.setDamage(e.getDamage() * mult);
    }

    /** Châssis de Smasher : presque aucun recul. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent e) {
        if (!(e.getEntity() instanceof Player p) || !wears(p, CHASSIS) || !online(p)) return;
        e.setKnockback(e.getKnockback().multiply(cfg("chassis.knockback", 0.3)));
    }

    /** Second Cœur : un coup mortel ne te tue pas (une fois toutes les 10 min). Passe après le totem. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        if (probing || !(e.getEntity() instanceof Player p) || !wears(p, HEART) || !online(p)) return;
        switch (e.getCause()) {
            case VOID, KILL, SUICIDE, WORLD_BORDER -> {
                return;
            }
            default -> {
            }
        }
        if (p.getHealth() + p.getAbsorptionAmount() - e.getFinalDamage() > 0) return;
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            ItemStack it = p.getInventory().getItem(s);
            if (it != null && it.getType() == org.bukkit.Material.TOTEM_OF_UNDYING) return;
        }
        long now = System.currentTimeMillis();
        Long cd = heartCd.get(p.getUniqueId());
        if (cd != null && cd > now) return;
        heartCd.put(p.getUniqueId(), now + (long) (cfg("second-coeur.cooldown-minutes", 10) * 60_000));
        e.setCancelled(true);
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double m = max == null ? 20 : max.getValue();
        p.setHealth(Math.max(1, m * cfg("second-coeur.health", 0.4)));
        p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), 8));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 2, false, true, true));
        p.setNoDamageTicks(40);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2f, 1.4f);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 1.2f, 1.5f);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 40, 0.4, 0.7, 0.4, 0, new Particle.DustOptions(PINK, 1.4f), true);
        SmpBoss.hud(p, parse(plugin.msg("cyberware.second-heart")));
    }

    /** Pompe sanguine : chaque kill rend de la vie. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || !wears(k, PUMP) || !online(k)) return;
        var max = k.getAttribute(Attribute.MAX_HEALTH);
        k.setHealth(Math.min(max == null ? 20 : max.getValue(), k.getHealth() + cfg("pompe.heal", 3)));
        k.getWorld().spawnParticle(Particle.HEART, k.getLocation().add(0, 2, 0), 2, 0.3, 0.1, 0.3, 0, null, true);
    }

    /** Toutes les 2 s : vision de nuit (Kiroshi), saut (Tendons), célérité (Synapses). */
    private void passives() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!online(p)) continue;
            var eq = plugin.store().of(p.getUniqueId()).equipped;
            if (eq.contains(KIROSHI)) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0, false, false, false));
            if (eq.contains(TENDONS)) p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 60, 1, false, false, false));
            if (eq.contains(SYNAPSE)) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, 0, false, false, false));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (running.containsKey(id)) stop(e.getPlayer(), false);
        gorillaHits.remove(id);
    }

    /** Les protections (spawn, claims, duels...) acceptent-elles qu'on gêne ce joueur ? */
    private final Map<UUID, Long> allowCache = new HashMap<>();
    /** un faux coup est en cours de test : nos propres effets ne doivent pas s'y appliquer */
    private boolean probing;

    private boolean allowed(Player caster, Player t) {
        UUID key = new UUID(caster.getUniqueId().getMostSignificantBits() ^ t.getUniqueId().getMostSignificantBits(),
                caster.getUniqueId().getLeastSignificantBits() ^ t.getUniqueId().getLeastSignificantBits());
        Long c = allowCache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && Math.abs(c) > now) return c > 0;
        var ev = new EntityDamageByEntityEvent(caster, t, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build(), 1.0);
        probing = true;
        try {
            Bukkit.getPluginManager().callEvent(ev);
        } finally {
            probing = false;
        }
        boolean ok = !ev.isCancelled();
        if (allowCache.size() > 2000) allowCache.clear();
        allowCache.put(key, ok ? now + 2000 : -(now + 2000));
        return ok;
    }
}
