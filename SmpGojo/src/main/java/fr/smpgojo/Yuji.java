package fr.smpgojo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Yuji Itadori : Poing divergent, Black Flash, Coup de pied Manji, Rafale, Sang perforant. */
final class Yuji {

    private final SmpGojo pl;
    /** "dans la zone" après un Black Flash : jusqu'à quel tick */
    private final Map<UUID, Integer> zone = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();
    private final Map<UUID, Integer> lastFlash = new HashMap<>();
    private int now;
    private boolean inside;

    Yuji(SmpGojo pl) {
        this.pl = pl;
    }

    private boolean inZone(Player p) {
        return zone.getOrDefault(p.getUniqueId(), 0) > now;
    }

    void tick() {
        now++;
        if (now % 2 != 0) return;
        for (var e : zone.entrySet()) {
            if (e.getValue() <= now) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Location c = p.getLocation().add(0, 1, 0);
            Draw.dust(c.clone().add(Draw.randomDir().multiply(0.7)), ThreadLocalRandom.current().nextBoolean() ? Draw.BLACK : Draw.RED, 0.8f);
            Draw.fx(SmpGojo.hand(p), Particle.SOUL_FIRE_FLAME, 1, 0.05, 0.01);
        }
    }

    // ================================================================= coups normaux

    /** Les coups de poing avec le Poing divergent en main : second impact, et parfois un Black Flash. */
    void onMelee(EntityDamageByEntityEvent e) {
        if (inside || !(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || pl.technique != null) return;
        if (!"poing".equals(pl.abilityOf(p.getInventory().getItemInMainHand())) || !p.hasPermission("smpgojo.use")) return;
        double chance = pl.cfg(inZone(p) ? "yuji.black-flash-chance-zone" : "yuji.black-flash-chance", inZone(p) ? 0.3 : 0.1);
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            e.setCancelled(true);
            pl.later(1, () -> blackFlash(p, victim));
            return;
        }
        divergent(p, victim, Math.max(4, e.getDamage()));
    }

    /** Le second impact de l'énergie occulte, juste après le coup. */
    private void divergent(Player p, LivingEntity victim, double base) {
        Location c = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        Draw.fx(c, Particle.CRIT, 10, 0.3, 0.3);
        pl.later(4, () -> {
            if (!victim.isValid()) return;
            Location l = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
            Draw.sphere(l, 0.9, Draw.CURSED, 1.3f, 30);
            Draw.fx(l, Particle.SOUL_FIRE_FLAME, 15, 0.3, 0.15);
            Draw.fx(l, Particle.SONIC_BOOM, 1, 0, 0);
            SmpGojo.sound(l, "entity.player.attack.knockback", 1.5f, 0.6f);
            SmpGojo.sound(l, "block.respawn_anchor.deplete", 1f, 1.6f);
            strike(p, victim, base * pl.cfg("yuji.divergent-ratio", 0.8), "poing");
            Vector push = victim.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 0.01) victim.setVelocity(push.normalize().multiply(0.9).setY(0.35));
        });
    }

    private void strike(Player p, LivingEntity t, double dmg, String tech) {
        inside = true;
        try {
            pl.hit(p, t, dmg, tech);
        } finally {
            inside = false;
        }
    }

    // ================================================================= Poing divergent (clic droit)

    void dashPunch(Player p) {
        dash(p, "poing", t -> {
            strike(p, t, pl.cfg("damage.poing", 8), "poing");
            divergent(p, t, pl.cfg("damage.poing", 8));
            SmpGojo.sound(t.getLocation(), "entity.player.attack.strong", 2f, 0.8f);
        });
    }

    /** Fonce sur la cible visée, puis frappe quand il l'atteint. */
    private void dash(Player p, String id, java.util.function.Consumer<LivingEntity> onHit) {
        LivingEntity target = pl.aim(p, pl.cfg("yuji.dash-range", 14));
        if (target == null) {
            p.sendActionBar(SmpGojo.mm("<red>Aucune cible en vue"));
            pl.resetCooldown(p, id);
            return;
        }
        SmpGojo.sound(p.getLocation(), "entity.breeze.wind_burst", 1.5f, 1.4f);
        pl.ruin.pop(p.getLocation(), 1.2, 6, 0.3);
        boolean[] done = {false};
        pl.repeat(1, 12, i -> {
            if (done[0] || !target.isValid()) return;
            Vector to = target.getLocation().add(0, target.getHeight() / 2, 0).toVector().subtract(p.getEyeLocation().toVector());
            double d = to.length();
            Draw.fx(p.getLocation().add(0, 1, 0), Particle.CLOUD, 3, 0.2, 0.01);
            if (d < 2.4) {
                done[0] = true;
                p.setVelocity(new Vector());
                onHit.accept(target);
                return;
            }
            p.setVelocity(to.normalize().multiply(Math.min(1.6, 0.6 + d * 0.15)));
        }, null);
    }

    // ================================================================= Black Flash

    void blackFlashDash(Player p) {
        dash(p, "blackflash", t -> blackFlash(p, t));
    }

    /** 黒閃 : distorsion de l'espace à l'impact, éclairs noirs, arrêt sur image, puis la cible traverse tout. */
    void blackFlash(Player p, LivingEntity t) {
        if (!t.isValid()) return;
        Location at = t.getLocation().add(0, t.getHeight() * 0.55, 0);
        List<Player> vs = pl.viewers(at, 50);
        int stop = 8;
        pl.cine.hitStop(List.of(t, p), stop);
        pl.cine.impact(vs, List.of(t, p), Cine.BLACK_FLASH);
        pl.cine.title(List.of(p), "<black><b>黒閃", "<dark_red><b>BLACK FLASH", 25);
        if (t instanceof Player tp) pl.cine.title(List.of(tp), "<black><b>黒閃", "<dark_red><b>BLACK FLASH", 25);
        SmpGojo.sound(at, "entity.lightning_bolt.thunder", 2.5f, 1.7f);
        SmpGojo.sound(at, "block.anvil.land", 2f, 0.5f);
        SmpGojo.sound(at, "entity.player.attack.crit", 3f, 0.5f);
        SmpGojo.sound(at, "block.respawn_anchor.deplete", 2f, 0.5f);
        pl.repeat(1, stop, i -> {
            for (int k = 0; k < 7; k++) {
                Draw.bolt(at, Draw.randomDir(), 2 + ThreadLocalRandom.current().nextDouble() * 3.5, Draw.BLACK, Draw.RED, 1.5f);
            }
            Draw.sphere(at, 0.6 + i * 0.12, Draw.BLACK, 1.8f, 30);
            Draw.fx(at, Particle.SQUID_INK, 8, 0.3, 0.1);
        }, () -> {
            if (!t.isValid()) return;
            Vector dir = t.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() < 0.01) dir = p.getLocation().getDirection().setY(0);
            dir.normalize();
            strike(p, t, pl.cfg("damage.blackflash", 30), "blackflash");
            Draw.fx(at, Particle.EXPLOSION_EMITTER, 1, 0, 0);
            Draw.fx(at, Particle.SONIC_BOOM, 2, 0.3, 0);
            SmpGojo.sound(at, "entity.generic.explode", 3f, 0.7f);
            SmpGojo.sound(at, "item.mace.smash_ground_heavy", 3f, 0.6f);
            pl.cine.shake(vs, 4, 2);
            pl.ruin.shockwave(t.getLocation(), 6, 6, 0.5);
            Vector launch = dir.clone().multiply(pl.cfg("yuji.black-flash-knockback", 3.2)).setY(0.45);
            t.setVelocity(launch);
            // la cible traverse les murs
            pl.repeat(1, 14, i -> {
                if (!t.isValid()) return;
                Location l = t.getLocation().add(0, t.getHeight() / 2, 0);
                Draw.dust(l, Draw.BLACK, 2f);
                Draw.dust(l.clone().add(Draw.randomDir().multiply(0.4)), Draw.RED, 1.2f);
                pl.ruin.carve(l.clone().add(dir.clone().multiply(0.8)), 1.3, 0, 0.35, dir.clone().multiply(0.4), null, 40);
                if (i < 8) t.setVelocity(launch);
            }, () -> {
                if (t.isValid()) {
                    pl.ruin.carve(t.getLocation(), 2, 0, 0.4, null, null, 40);
                    pl.ruin.shockwave(t.getLocation(), 4, 5, 0.4);
                }
            });
            // la zone
            zone.put(p.getUniqueId(), now + pl.cfgi("yuji.zone-seconds", 15) * 20);
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, pl.cfgi("yuji.zone-seconds", 15) * 20, 1, false, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, pl.cfgi("yuji.zone-seconds", 15) * 20, 1, false, false, true));
            int last = lastFlash.getOrDefault(p.getUniqueId(), -10000);
            int n = now - last < 20 * 20 ? streak.merge(p.getUniqueId(), 1, Integer::sum) : 1;
            streak.put(p.getUniqueId(), n);
            lastFlash.put(p.getUniqueId(), now);
            if (n >= 2) {
                for (Player v : vs) v.sendMessage(SmpGojo.mm("<black>■</black> <dark_red><b>BLACK FLASH ×" + n + "</b></dark_red> <gray>"
                        + p.getName() + (n >= 4 ? " <gold>- égale le record d'Itadori !" : "")));
            }
        });
    }

    // ================================================================= Coup de pied Manji

    void manji(Player p) {
        LivingEntity target = pl.aim(p, 22);
        Location aim = target != null ? target.getLocation() : pl.aimPoint(p, 22);
        UUID id = p.getUniqueId();
        pl.noFall.add(id);
        Vector flat = aim.toVector().subtract(p.getLocation().toVector()).setY(0);
        double dist = flat.length();
        p.setVelocity((dist > 0.1 ? flat.normalize().multiply(Math.min(1.4, dist * 0.08)) : new Vector()).setY(1.25));
        SmpGojo.sound(p.getLocation(), "entity.breeze.jump", 2f, 0.8f);
        pl.ruin.pop(p.getLocation(), 1.5, 10, 0.4);
        boolean[] slam = {false}, done = {false};
        pl.repeat(1, 45, i -> {
            if (done[0] || !p.isOnline()) return;
            Location l = p.getLocation();
            if (!slam[0]) {
                Draw.orbit(l.clone().add(0, 0.6, 0), 1.2, i * 0.9, 0.2, Draw.WHITE, 1f, 10);
                Draw.fx(l.clone().add(0, 0.5, 0), Particle.SWEEP_ATTACK, 1, 0.5, 0);
                if (i >= 9 || p.getVelocity().getY() < 0) {
                    slam[0] = true;
                    Location to = target != null && target.isValid() ? target.getLocation() : aim;
                    Vector down = to.toVector().subtract(l.toVector());
                    p.setVelocity(down.normalize().multiply(2.8).setY(-2.8));
                    SmpGojo.sound(l, "item.trident.riptide_2", 2f, 0.6f);
                }
                return;
            }
            Draw.fx(l.clone().add(0, 1, 0), Particle.CLOUD, 4, 0.2, 0.05);
            if (p.isOnGround() || i > 40) {
                done[0] = true;
                manjiImpact(p, l);
            }
        }, () -> pl.later(40, () -> pl.noFall.remove(id)));
    }

    private void manjiImpact(Player p, Location at) {
        List<LivingEntity> hit = pl.targets(p, at, 4.5);
        List<Player> vs = pl.viewers(at, 40);
        pl.cine.hitStop(hit, 3);
        pl.cine.impact(vs, hit, Cine.WHITE_BLACK);
        pl.cine.shake(vs, 4, 2);
        SmpGojo.sound(at, "item.mace.smash_ground_heavy", 3f, 0.6f);
        SmpGojo.sound(at, "entity.generic.explode", 2.5f, 1f);
        pl.ruin.carve(at.clone().add(0, -0.6, 0), 3, 0, 0.45, new Vector(0, 0.5, 0), null, 50);
        pl.ruin.shockwave(at, 10, 10, 0.6);
        Draw.ring(at.clone().add(0, 0.2, 0), 3, Draw.CURSED, 1.5f, 40);
        double dmg = pl.cfg("damage.manji", 16);
        pl.later(3, () -> {
            for (LivingEntity le : hit) {
                if (!le.isValid()) continue;
                strike(p, le, dmg, "manji");
                le.setVelocity(new Vector(0, 1.2, 0));
            }
        });
    }

    // ================================================================= Rafale

    void barrage(Player p) {
        LivingEntity target = pl.aim(p, 6);
        if (target == null) {
            p.sendActionBar(SmpGojo.mm("<red>Aucune cible au contact"));
            pl.resetCooldown(p, "rafale");
            return;
        }
        int hits = pl.cfgi("rafale.hits", 14);
        double each = pl.cfg("damage.rafale", 1.5);
        pl.cine.hold(target, hits * 2 + 2);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, hits * 2, 3, false, false, false));
        pl.repeat(2, hits, i -> {
            if (!target.isValid()) return;
            Location c = target.getLocation().add(0, target.getHeight() * 0.6, 0);
            Location from = p.getEyeLocation().add(0, -0.3, 0);
            Vector side = SmpGojo.right(p).multiply((i % 2 == 0 ? 0.45 : -0.45));
            Location fist = c.clone().add(side).add(Draw.randomDir().multiply(0.25));
            Draw.segment(from.clone().add(side), fist, Draw.WHITE, 0.6f, 0.3);
            Draw.fx(fist, Particle.CRIT, 8, 0.15, 0.3);
            Draw.fx(fist, Particle.SWEEP_ATTACK, 1, 0, 0);
            if (i % 3 == 0) Draw.sphere(fist, 0.5, Draw.CURSED, 1f, 12);
            SmpGojo.sound(c, i % 2 == 0 ? "entity.player.attack.weak" : "entity.player.attack.strong", 1.5f, 0.8f + i * 0.05f);
            strike(p, target, each, "rafale");
        }, () -> {
            if (!target.isValid()) return;
            pl.cine.release(target);
            List<Player> vs = pl.viewers(target.getLocation(), 30);
            pl.cine.impact(vs, List.of(target), Cine.RED);
            Location c = target.getLocation().add(0, target.getHeight() * 0.6, 0);
            Draw.fx(c, Particle.EXPLOSION, 3, 0.3, 0);
            Draw.sphere(c, 1.2, Draw.CURSED, 1.8f, 50);
            SmpGojo.sound(c, "entity.player.attack.knockback", 3f, 0.5f);
            SmpGojo.sound(c, "entity.generic.explode", 2f, 1.4f);
            strike(p, target, pl.cfg("damage.uppercut", 10), "rafale");
            pl.later(1, () -> target.setVelocity(new Vector(0, 1.7, 0)));
        });
    }

    // ================================================================= Sang perforant

    void piercingBlood(Player p) {
        pl.cine.title(pl.viewers(p.getLocation(), 30), "", "<dark_red>« <red>Convergence...</red> »", 30);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 32, 3, false, false, false));
        SmpGojo.sound(p.getLocation(), "block.respawn_anchor.charge", 2f, 0.6f);
        pl.repeat(1, 30, i -> {
            Location h = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.7)).add(0, -0.35, 0);
            double r = 1.2 - i * 0.035;
            Draw.converge(h, r + 0.6, Draw.BLOOD, 1.1f, 10);
            Draw.ball(h, Math.max(0.08, r * 0.25), Draw.RED, 1.5f, 10);
            Draw.orbit(h, Math.max(0.15, r * 0.5), i * 0.7, 1.3, Draw.BLOOD, 0.8f, 10);
            if (i % 5 == 0) SmpGojo.sound(h, "block.respawn_anchor.charge", 1.5f, 0.6f + i * 0.04f);
        }, () -> fireBlood(p));
    }

    private void fireBlood(Player p) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection().normalize();
        double range = pl.cfg("sang.range", 60);
        RayTraceResult end = p.getWorld().rayTraceBlocks(eye, d, range);
        double len = range;
        if (end != null && !pl.ruin.enabled()) len = end.getHitPosition().distance(eye.toVector());
        double finalLen = len;
        List<Player> vs = pl.viewers(eye, 60);
        pl.cine.impact(vs, null, new Cine.Frame[]{Cine.f(200, Draw.RED, 1), Cine.f(120, Draw.WHITE, 1)});
        pl.cine.title(vs, "", "<dark_red><b>« Sang perforant »", 20);
        SmpGojo.sound(eye, "entity.warden.sonic_boom", 3f, 1.5f);
        SmpGojo.sound(eye, "item.trident.riptide_3", 2f, 1.6f);
        double dmg = pl.cfg("damage.sang", 24);
        java.util.Set<UUID> hit = new java.util.HashSet<>();
        for (double t = 1; t <= finalLen; t += 0.8) {
            Location l = eye.clone().add(d.clone().multiply(t));
            for (LivingEntity le : pl.targets(p, l, 1.1)) {
                if (!hit.add(le.getUniqueId())) continue;
                strike(p, le, dmg, "sang");
                Draw.blood(le, 50);
                le.setVelocity(d.clone().multiply(0.8).setY(0.2));
            }
            if (((int) t) % 2 == 0) pl.ruin.carve(l, 0.75, 0, 0.12, d.clone().multiply(0.4), null, 30);
        }
        pl.repeat(1, 10, i -> {
            for (double t = 1; t <= finalLen; t += 0.35) {
                Location l = eye.clone().add(d.clone().multiply(t));
                Draw.dust(l, i < 5 ? Draw.RED : Draw.BLOOD, i < 5 ? 1.3f : 0.8f);
                if (i < 3 && ThreadLocalRandom.current().nextInt(4) == 0) Draw.dust(l, Draw.WHITE, 0.6f);
            }
        }, null);
        Location tip = eye.clone().add(d.clone().multiply(finalLen));
        Draw.fx(tip, Particle.EXPLOSION, 2, 0.3, 0);
        SmpGojo.sound(tip, "entity.generic.explode", 2f, 1.3f);
    }

    // ================================================================= nettoyage

    void quit(Player p) {
        zone.remove(p.getUniqueId());
        streak.remove(p.getUniqueId());
        lastFlash.remove(p.getUniqueId());
    }

    void cleanup() {
        zone.clear();
    }
}
