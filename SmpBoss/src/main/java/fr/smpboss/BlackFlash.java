package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Reliques "Black Flash" (Reine Araignée) : accroupi + touche main secondaire (F) = un éclair noir et rouge part devant toi.
 * - Black Flash (légendaire) : la cible touchée perd 50 % de sa vie.
 * - Black Flash Consécutifs (prestige) : la cible est figée et encaisse une rafale de black flashs calée sur le son de la
 *   vidéo de référence (37 coups en 13 s), 95 % de sa vie au total.
 */
final class BlackFlash implements Listener {

    static final String SINGLE = "black_flash", CONSEC = "black_flash_consecutif";
    static final Color RED = Color.fromRGB(255, 30, 45), DARK = Color.fromRGB(90, 0, 10), BLACK = Color.fromRGB(14, 0, 4);
    private static final char IMPACT_BLACK = '', IMPACT_RED = '';
    /** coups de la rafale (en ticks depuis le début du son "consecutive"), calés sur les impacts du son */
    private static final int[] HITS = {3, 9, 16, 21, 26, 32, 40, 45, 52, 57, 66, 74, 87, 93, 101, 107, 113, 119, 127, 133, 141, 147, 155,
            161, 167, 172, 178, 184, 192, 204, 209, 216, 223, 229, 234, 241, 257};
    /** 2 = gros impact (images plein écran, grosse explosion) */
    private static final int[] BIG = {2, 1, 1, 1, 2, 1, 2, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 2, 1, 2, 1, 1, 2, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    private final SmpBoss plugin;
    private final Map<String, Long> cooldown = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();

    BlackFlash(SmpBoss plugin) {
        this.plugin = plugin;
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("black-flash." + path, def);
    }

    private Ultimates ult() {
        return plugin.weapons().ultimates;
    }

    private WeaponFx fx() {
        return ult().weaponFx;
    }

    // ================================================================= déclenchement

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        PlayerData d = plugin.store().of(p.getUniqueId());
        boolean consec = d.equipped.contains(CONSEC), single = d.equipped.contains(SINGLE);
        if (!consec && !single) return;
        e.setCancelled(true);
        cast(p, consec);
    }

    /** Pendant la rafale : celui qui frappe ne prend pas de dégâts de chute / ne se fait pas pousser. */
    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && busy.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    void cast(Player p, boolean consec) {
        if (busy.contains(p.getUniqueId())) return;
        String key = p.getUniqueId() + (consec ? ":c" : ":s");
        long now = System.currentTimeMillis();
        Long until = cooldown.get(key);
        if (until != null && until > now) {
            p.sendActionBar(parse(plugin.msg("weapons.cooldown").replace("<seconds>", String.valueOf((until - now) / 1000 + 1))));
            return;
        }
        cooldown.put(key, now + (long) (cfg(consec ? "consecutive.cooldown-seconds" : "single.cooldown-seconds", consec ? 120 : 45) * 1000));
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double range = cfg("range", 24);
        RayTraceResult wall = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double len = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
        w.playSound(eye, Sound.ENTITY_BREEZE_WIND_BURST, 1.4f, 0.6f);
        w.playSound(eye, Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.8f, 2f);
        // le poing se charge d'éclairs
        Location fist = eye.clone().add(dir.clone().multiply(0.8)).add(0, -0.3, 0);
        WeaponFx fx = fx();
        fx.pop(fist, "bf_star", 1.6f, 1.6f, 1.6f, 1, 1, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ(0.4f));
        for (int i = 0; i < 3; i++) fx.pop(fist, "bf_bolt_" + i, 0.5f, 2f, 0.5f, 1, 0, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) (i * 2.1)));
        // l'éclair part devant (3 blocs par tick) ; la première cible touchée encaisse
        double speed = 3;
        int steps = (int) Math.ceil(len / speed);
        Location[] prev = {eye.clone().add(dir.clone().multiply(1))};
        int[] k = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int s = k[0]++;
            double dist = Math.min(len, 1 + (s + 1) * speed);
            Location now2 = eye.clone().add(dir.clone().multiply(dist));
            Vector seg = now2.toVector().subtract(prev[0].toVector());
            if (seg.lengthSquared() > 0.01) {
                Location mid = prev[0].clone().add(seg.clone().multiply(0.5));
                fx.pop(mid, "bf_bolt_" + (s % 4) + "_x", 1.1f, (float) seg.length() * 1.2f, 1.1f, 1, 1, 4, Display.Billboard.FIXED, WeaponFx.alongY(seg));
                w.spawnParticle(Particle.DUST, mid, 12, seg.length() * 0.3, 0.15, seg.length() * 0.3, 0, new Particle.DustOptions(RED, 1.6f), true);
                w.spawnParticle(Particle.DUST, mid, 6, seg.length() * 0.3, 0.15, seg.length() * 0.3, 0, new Particle.DustOptions(BLACK, 1.4f), true);
            }
            // cible sur ce bout de trajet
            LivingEntity hit = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity le : ult().targets(p, prev[0].clone().add(seg.clone().multiply(0.5)), seg.length() / 2 + 1.6)) {
                double d = le.getLocation().add(0, le.getHeight() / 2, 0).distanceSquared(prev[0]);
                if (d < best) {
                    best = d;
                    hit = le;
                }
            }
            prev[0] = now2;
            if (hit != null) {
                task[0].cancel();
                if (consec) consecutive(p, hit);
                else single(p, hit);
                return;
            }
            if (dist >= len || s > steps + 1) {
                task[0].cancel();
                flash(now2, false, null);
            }
        }, 1L, 1L);
    }

    // ================================================================= Black Flash simple (50 %)

    private void single(Player caster, LivingEntity t) {
        Location c = t.getLocation().add(0, t.getHeight() * 0.55, 0);
        packSound(c, "blackflash.single", 48);
        fallbackSound(c);
        flash(c, true, t);
        impactFrames(c, 14, caster, t, 2);
        if (!allowed(caster, t)) return;
        hitPercent(caster, t, cfg("single.percent", 50) / 100.0, cfg("single.boss-damage", 120));
        Vector kb = t.getLocation().toVector().subtract(caster.getLocation().toVector()).setY(0);
        if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
        if (!isBoss(t)) t.setVelocity(kb.normalize().multiply(1.6).setY(0.55));
    }

    // ================================================================= Black Flash Consécutifs (95 %)

    private void consecutive(Player caster, LivingEntity t) {
        if (!allowed(caster, t)) {
            flash(t.getLocation().add(0, 1, 0), true, t);
            return;
        }
        boolean boss = isBoss(t);
        busy.add(caster.getUniqueId());
        int total = (int) cfg("consecutive.duration-ticks", 270);
        if (!boss) ult().freezeFor(t, total);
        // celui qui frappe se met contre sa cible (il ne peut plus bouger pendant la rafale)
        Location start = around(t, 0);
        Arena.allowTeleport(caster);
        caster.teleport(start);
        ult().root(caster);
        Location c0 = t.getLocation().add(0, t.getHeight() * 0.55, 0);
        packSound(c0, "blackflash.consecutive", 64);
        for (Player o : c0.getWorld().getPlayers()) {
            if (o.getLocation().distanceSquared(c0) > 64 * 64 || PackStatus.has(o)) continue;
            o.playSound(c0, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 0.6f);
        }
        if (t instanceof Player tp) tp.sendActionBar(parse(plugin.msg("black-flash.stunned")));
        caster.sendActionBar(parse(plugin.msg("black-flash.combo")));
        double max = maxHealth(t);
        double perHit = max * cfg("consecutive.percent", 95) / 100.0 / HITS.length;
        double bossHit = cfg("consecutive.boss-damage", 400) / HITS.length;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int[] n = {0};
        double[] angle = {0};
        BukkitTask[] task = new BukkitTask[1];
        int[] tick = {0};
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = tick[0]++;
            boolean broken = !caster.isOnline() || caster.isDead() || !t.isValid() || t.isDead() || caster.getWorld() != t.getWorld();
            if (broken || k > total) {
                task[0].cancel();
                busy.remove(caster.getUniqueId());
                if (caster.isOnline()) ult().unroot(caster, false);
                if (!broken) {
                    // dernier coup : tout explose, la cible est projetée
                    Location c = t.getLocation().add(0, t.getHeight() * 0.55, 0);
                    flash(c, true, t);
                    fx().boom(c, RED, 9);
                    if (!isBoss(t)) {
                        Vector kb = t.getLocation().toVector().subtract(caster.getLocation().toVector()).setY(0);
                        if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
                        t.setVelocity(kb.normalize().multiply(2.2).setY(0.8));
                    }
                }
                return;
            }
            // ambiance continue : sol rouge, éclairs qui crépitent autour
            Location c = t.getLocation().add(0, t.getHeight() * 0.55, 0);
            if (k % 3 == 0) {
                c.getWorld().spawnParticle(Particle.DUST, c, 10, 1.4, 1, 1.4, 0, new Particle.DustOptions(RED, 1.5f), true);
                c.getWorld().spawnParticle(Particle.DUST, c, 5, 1.2, 0.9, 1.2, 0, new Particle.DustOptions(BLACK, 1.3f), true);
            }
            if (k % 10 == 0) {
                Location g = t.getLocation().add(0, 0.1, 0);
                fx().tpop(g, "w_ring_flat", RED, 7, 1, 7, 2, 8, 3, Display.Billboard.FIXED, null);
            }
            if (n[0] < HITS.length && k == HITS[n[0]]) {
                int i = n[0]++;
                boolean big = BIG[i] == 2 || i == HITS.length - 1;
                // il frappe sous un nouvel angle à chaque fois
                angle[0] += r.nextDouble(1.0, 2.4);
                Location pos = around(t, angle[0]);
                Arena.allowTeleport(caster);
                caster.teleport(pos);
                ult().root(caster);
                flash(c, big, t);
                if (big) impactFrames(c, 10, caster, t, 1);
                if (boss) {
                    Boss b = plugin.king();
                    if (b != null) b.hurt(caster, bossHit);
                } else {
                    applyDamage(caster, t, perHit);
                }
                if (t instanceof Player tp && i % 4 == 0) tp.sendActionBar(parse(plugin.msg("black-flash.counter").replace("<n>", String.valueOf(i + 1))));
                caster.sendActionBar(parse(plugin.msg("black-flash.counter").replace("<n>", String.valueOf(i + 1))));
            }
        }, 1L, 1L);
    }

    /** Position autour de la cible, tournée vers elle. */
    private static Location around(LivingEntity t, double a) {
        Location c = t.getLocation();
        Location p = c.clone().add(Math.cos(a) * 1.9, 0, Math.sin(a) * 1.9);
        Vector look = c.toVector().subtract(p.toVector());
        p.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));
        p.setPitch(10);
        return p;
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

    /** Les protections (zones sans PvP, amis, guilde...) acceptent-elles ce coup ? */
    private boolean allowed(Player caster, LivingEntity t) {
        if (isBoss(t)) return true;
        var ev = new org.bukkit.event.entity.EntityDamageByEntityEvent(caster, t, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build(), 1.0);
        Bukkit.getPluginManager().callEvent(ev);
        return !ev.isCancelled();
    }

    private void hitPercent(Player caster, LivingEntity t, double frac, double bossDamage) {
        if (isBoss(t)) {
            Boss b = plugin.king();
            if (b != null) b.hurt(caster, bossDamage);
            return;
        }
        applyDamage(caster, t, maxHealth(t) * frac);
    }

    /** Retire exactement "amount" PV (l'armure ne compte pas) ; si ça tue, c'est un vrai coup de celui qui frappe. */
    private static void applyDamage(Player caster, LivingEntity t, double amount) {
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
        t.playHurtAnimation((float) ThreadLocalRandom.current().nextDouble(360));
        t.getWorld().playSound(t.getLocation(), t instanceof Player ? Sound.ENTITY_PLAYER_HURT : Sound.ENTITY_GENERIC_HURT, 1f, 0.8f);
    }

    // ================================================================= effets

    /** Un black flash : étoile rouge à cœur noir, éclairs rouges/noirs qui jaillissent, pointes blanches, ondes, sol rouge. */
    void flash(Location c, boolean big, LivingEntity t) {
        WeaponFx fx = fx();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float s = big ? 1f : 0.6f;
        World w = c.getWorld();
        fx.pop(c, "bf_star", 7 * s, 7 * s, 7 * s, 1, 1, 4, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) r.nextDouble(-0.4, 0.4)));
        if (big) fx.pop(c, "bf_spikes", 9, 9, 9, 1, 1, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) r.nextDouble(6.28)));
        // éclairs en 3D qui partent du centre
        int bolts = big ? 12 : 6;
        for (int i = 0; i < bolts; i++) {
            Vector d = new Vector(r.nextDouble(-1, 1), r.nextDouble(-0.5, 1), r.nextDouble(-1, 1));
            if (d.lengthSquared() < 0.05) d = new Vector(0, 1, 0);
            d.normalize();
            double L = r.nextDouble(2.5, big ? 8 : 5);
            Location mid = c.clone().add(d.clone().multiply(L / 2));
            fx.pop(mid, "bf_bolt_" + r.nextInt(4) + "_x", (float) (0.9 * s + 0.4), (float) L, (float) (0.9 * s + 0.4), 1, r.nextInt(3), 3,
                    Display.Billboard.FIXED, WeaponFx.alongY(d));
        }
        // éclairs dessinés face caméra
        for (int i = 0; i < (big ? 6 : 3); i++) {
            fx.pop(c.clone().add(r.nextDouble(-1, 1), r.nextDouble(-0.6, 1), r.nextDouble(-1, 1)), "bf_bolt_" + r.nextInt(4), 1.4f * s, 6 * s, 1.4f * s,
                    1, 1, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) r.nextDouble(6.28)));
        }
        fx.wave(c, "w_shock", Color.WHITE, 0.8f, big ? 11 : 6, 6, null, Display.Billboard.CENTER);
        fx.tpop(c, "w_glow", RED, 6 * s, 6 * s, 6 * s, 1, 1, 5, Display.Billboard.CENTER, null);
        for (int i = 0; i < (big ? 6 : 3); i++) {
            fx.tpop(c.clone().add(r.nextDouble(-1.5, 1.5), r.nextDouble(-1, 1.5), r.nextDouble(-1.5, 1.5)), "w_spark", i % 2 == 0 ? BLACK : RED,
                    1.6f * s, 1.6f * s, 1.6f * s, 1, 1, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) r.nextDouble(6.28)));
        }
        // au sol : anneau, fissures et lueur rouges
        Location g = (t != null ? t.getLocation() : c.clone().add(0, -1, 0)).add(0, 0.08, 0);
        fx.wave(g, "w_shock_flat", RED, 1, big ? 14 : 8, 8, null, Display.Billboard.FIXED);
        for (int i = 0; i < (big ? 3 : 1); i++) {
            Quaternionf q = new Quaternionf().rotateY((float) r.nextDouble(6.28));
            fx.tpop(g, "w_crack_flat", RED, 7 * s, 1, 7 * s, 1, 10, 6, Display.Billboard.FIXED, q);
        }
        if (big) fx.puff(c, DARK, 4.5f);
        // pour tout le monde (avec ou sans pack)
        w.spawnParticle(Particle.DUST, c, big ? 60 : 25, 1.2, 1, 1.2, 0, new Particle.DustOptions(RED, 2f), true);
        w.spawnParticle(Particle.DUST, c, big ? 30 : 12, 1, 0.8, 1, 0, new Particle.DustOptions(BLACK, 1.8f), true);
        w.spawnParticle(Particle.ELECTRIC_SPARK, c, big ? 30 : 10, 0.8, 0.8, 0.8, 0.3, null, true);
        if (big) w.spawnParticle(Particle.EXPLOSION, c, 1, 0, 0, 0, 0, null, true);
        if (big) ult().quake(c, 16, 8, 2.8f);
    }

    /** Images plein écran noires / rouges (pack), pour la cible, celui qui frappe et ceux tout près. */
    private void impactFrames(Location c, double radius, Player caster, LivingEntity t, int steps) {
        char[] seq = steps >= 2 ? new char[]{IMPACT_BLACK, IMPACT_RED} : new char[]{ThreadLocalRandom.current().nextBoolean() ? IMPACT_BLACK : IMPACT_RED};
        for (int i = 0; i < seq.length; i++) {
            char g = seq[i];
            boolean last = i == seq.length - 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Component txt = Component.text(String.valueOf(g)).font(Key.key(PackFx.NS, "fx")).color(NamedTextColor.WHITE).shadowColor(ShadowColor.shadowColor(0));
                Title title = Title.title(txt, Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(last ? 60 : 100), Duration.ofMillis(last ? 120 : 0)));
                for (Player o : c.getWorld().getPlayers()) {
                    if (!PackStatus.has(o)) continue;
                    if (o.equals(caster) || o.equals(t) || o.getLocation().distanceSquared(c) <= radius * radius) o.showTitle(title);
                }
            }, 1L + i * 2L);
        }
    }

    private static void packSound(Location c, String key, double radius) {
        for (Player o : c.getWorld().getPlayers()) {
            if (!PackStatus.has(o) || o.getLocation().distanceSquared(c) > radius * radius) continue;
            double d = o.getLocation().distance(c);
            o.playSound(o, PackFx.NS + ":" + key, SoundCategory.PLAYERS, (float) Math.max(0.35, 1 - d / radius), 1f);
        }
    }

    private static void fallbackSound(Location c) {
        for (Player o : c.getWorld().getPlayers()) {
            if (PackStatus.has(o) || o.getLocation().distanceSquared(c) > 48 * 48) continue;
            o.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.6f);
            o.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.5f, 0.7f);
        }
    }

    @SuppressWarnings("unused")
    private static List<Player> none() {
        return List.of();
    }
}
