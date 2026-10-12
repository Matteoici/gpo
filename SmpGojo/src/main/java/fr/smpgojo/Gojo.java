package fr.smpgojo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Satoru Gojo : Infini, Bleu, Rouge, Violet creux, Violet creux 200 %, Sphère de l'infini. */
final class Gojo {

    private final SmpGojo pl;
    private final Set<UUID> infinity = new HashSet<>();
    /** projectiles arrêtés par l'Infini -> joueur */
    private final Map<UUID, UUID> stopped = new HashMap<>();
    private int t;

    Gojo(SmpGojo pl) {
        this.pl = pl;
    }

    // ================================================================= Infini

    boolean hasInfinity(Player p) {
        return infinity.contains(p.getUniqueId());
    }

    void toggleInfinity(Player p) {
        if (infinity.remove(p.getUniqueId())) {
            p.sendActionBar(SmpGojo.mm("<gray>∞ Infini désactivé"));
            SmpGojo.sound(p.getLocation(), "block.beacon.deactivate", 1f, 1.5f);
            releaseProjectiles(p.getUniqueId());
            return;
        }
        infinity.add(p.getUniqueId());
        p.sendActionBar(SmpGojo.mm("<gradient:#ffffff:#9ad7ff><b>∞ Infini activé"));
        SmpGojo.sound(p.getLocation(), "block.beacon.activate", 1.2f, 1.8f);
        SmpGojo.sound(p.getLocation(), "block.amethyst_block.chime", 2f, 0.6f);
        Location c = p.getLocation().add(0, 1, 0);
        pl.repeat(1, 8, i -> {
            Draw.sphere(c, 0.4 + i * 0.3, Draw.LIGHT_BLUE, 0.7f, 30);
            Draw.ring(p.getLocation().add(0, 0.1, 0), 0.5 + i * 0.4, Draw.WHITE, 0.8f, 24);
        }, null);
    }

    void shimmer(Player p, Entity from) {
        Location c = p.getLocation().add(0, 1, 0);
        Vector to = from.getLocation().add(0, from.getHeight() / 2, 0).toVector().subtract(c.toVector());
        if (to.lengthSquared() < 0.01) to = p.getLocation().getDirection();
        Location contact = c.clone().add(to.normalize().multiply(1.1));
        Draw.sphere(contact, 0.35, Draw.LIGHT_BLUE, 0.8f, 14);
        Draw.fx(contact, Particle.END_ROD, 3, 0.1, 0.02);
        SmpGojo.sound(contact, "block.amethyst_block.hit", 1f, 1.9f);
    }

    void tick() {
        t++;
        for (UUID id : infinity) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Location c = p.getLocation().add(0, 1, 0);
            if (t % 2 == 0) {
                Vector v = Draw.randomDir().multiply(1.25);
                Draw.dust(c.clone().add(v), Draw.LIGHT_BLUE, 0.45f);
            }
            for (Entity e : p.getNearbyEntities(4, 4, 4)) {
                if (e instanceof Projectile pr) {
                    if (pr.getShooter() == p || stopped.containsKey(pr.getUniqueId())) continue;
                    if (pr.getLocation().distanceSquared(c) > 9) continue;
                    stopped.put(pr.getUniqueId(), id);
                    pr.setGravity(false);
                    pr.setVelocity(new Vector());
                    Draw.fx(pr.getLocation(), Particle.ENCHANT, 12, 0.2, 0.5);
                    SmpGojo.sound(pr.getLocation(), "block.amethyst_block.step", 1f, 1.8f);
                } else if (e instanceof LivingEntity && pl.valid(p, e) && !pl.megumi.adaptedToInfinity(e)) {
                    Vector away = e.getLocation().toVector().subtract(c.toVector());
                    double d = away.length();
                    if (d < 2.2 && d > 0.01) e.setVelocity(e.getVelocity().add(away.normalize().multiply(0.28).setY(0.04)));
                }
            }
        }
        for (Iterator<Map.Entry<UUID, UUID>> it = stopped.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Entity pr = Bukkit.getEntity(e.getKey());
            Player owner = Bukkit.getPlayer(e.getValue());
            if (pr == null || !pr.isValid()) {
                it.remove();
                continue;
            }
            if (owner == null || !infinity.contains(owner.getUniqueId()) || pr.getWorld() != owner.getWorld()
                    || pr.getLocation().distanceSquared(owner.getLocation()) > 30) {
                pr.setGravity(true);
                it.remove();
                continue;
            }
            pr.setVelocity(new Vector());
            if (t % 5 == 0) Draw.fx(pr.getLocation(), Particle.ENCHANT, 2, 0.1, 0.2);
        }
    }

    private void releaseProjectiles(UUID owner) {
        stopped.entrySet().removeIf(e -> {
            if (!e.getValue().equals(owner)) return false;
            Entity pr = Bukkit.getEntity(e.getKey());
            if (pr != null) pr.setGravity(true);
            return true;
        });
    }

    // ================================================================= Bleu

    void blue(Player p) {
        SmpGojo.sound(p.getEyeLocation(), "entity.illusioner.cast_spell", 2f, 1.6f);
        pl.cine.title(pl.viewers(p.getLocation(), 30), "", "<gradient:#2878ff:#9ad7ff>Amplification de sort : Bleu", 25);
        pl.repeat(1, 10, i -> Draw.converge(SmpGojo.hand(p), 1.4 - i * 0.12, Draw.BLUE, 1f, 10), () -> {
            Vector dir = p.getEyeLocation().getDirection().normalize();
            Location orb = p.getEyeLocation().add(dir.clone().multiply(4));
            double dmg = pl.cfg("damage.bleu", 5);
            int life = pl.cfgi("bleu.ticks", 80);
            SmpGojo.sound(orb, "entity.warden.sonic_charge", 2f, 1.5f);
            pl.repeat(1, life, i -> {
                orb.add(dir.clone().multiply(0.16));
                double r = 0.5 + Math.min(1.0, i / 15.0) * 0.8;
                Draw.ball(orb, r * 0.55, Draw.WHITE, 1.2f, 10);
                Draw.sphere(orb, r, Draw.BLUE, 2.2f, 32);
                Draw.orbit(orb, r * 2.3, i * 0.35, 0.5, Draw.LIGHT_BLUE, 0.9f, 14);
                Draw.orbit(orb, r * 1.8, -i * 0.45, -0.9, Draw.BLUE, 0.9f, 12);
                Draw.converge(orb, 6, Draw.LIGHT_BLUE, 0.6f, 6);
                Draw.fx(orb, Particle.SOUL_FIRE_FLAME, 3, 0.25, 0.02);
                double pull = pl.cfg("bleu.pull-radius", 11);
                for (Entity e : orb.getWorld().getNearbyEntities(orb, pull, pull, pull)) {
                    if (!(e instanceof Item) && !pl.valid(p, e)) continue;
                    Vector v = orb.toVector().subtract(e.getLocation().add(0, e.getHeight() / 2, 0).toVector());
                    double d = v.length();
                    if (d < 0.4) {
                        e.setVelocity(new Vector());
                        continue;
                    }
                    e.setVelocity(v.normalize().multiply(Math.min(1.1, 0.25 + 3.2 / d)));
                    if (d < 2 && i % 6 == 0 && e instanceof LivingEntity le) {
                        pl.hit(p, le, dmg, "bleu");
                        Draw.fx(le.getLocation().add(0, 1, 0), Particle.ENCHANTED_HIT, 10, 0.3, 0.3);
                    }
                }
                if (i % 3 == 0) pl.ruin.carve(orb, 1.6 + i * 0.015, 0, 0.55, null, orb, 300);
                if (i % 6 == 0) SmpGojo.sound(orb, "block.respawn_anchor.charge", 1.6f, 0.6f + i / (float) life);
                if (i % 20 == 0) pl.cine.shake(pl.viewers(orb, 14), 1, 1);
            }, () -> {
                // implosion puis tout est recraché
                pl.ruin.release(orb, 1.3);
                Draw.sphere(orb, 3, Draw.BLUE, 3f, 120);
                Draw.fx(orb, Particle.EXPLOSION, 6, 1, 0);
                Draw.fx(orb, Particle.GUST_EMITTER_LARGE, 1, 0, 0);
                SmpGojo.sound(orb, "entity.generic.explode", 3f, 1.3f);
                SmpGojo.sound(orb, "entity.breeze.wind_burst", 3f, 0.6f);
                pl.cine.impact(pl.viewers(orb, 16), null, Cine.FLASH);
                pl.ruin.carve(orb, 2.8, 0, 0.4, null, null, 50);
                for (LivingEntity le : pl.targets(p, orb, 6)) {
                    pl.hit(p, le, dmg * 2, "bleu");
                    Vector out = le.getLocation().toVector().subtract(orb.toVector());
                    if (out.lengthSquared() < 0.01) out = new Vector(0, 1, 0);
                    le.setVelocity(out.normalize().multiply(1.6).setY(0.7));
                }
            });
        });
    }

    // ================================================================= Rouge

    void red(Player p) {
        List<Player> vs = pl.viewers(p.getLocation(), 40);
        pl.cine.title(vs, "", "<gradient:#ff1e1e:#ff9a9a>Inversion de sort : Rouge", 30);
        SmpGojo.sound(p.getEyeLocation(), "block.beacon.power_select", 2f, 1.2f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 18, 2, false, false, false));
        pl.repeat(1, 16, i -> {
            Location tip = SmpGojo.hand(p);
            Draw.converge(tip, 1.6 - i * 0.09, Draw.RED, 1.1f, 12);
            Draw.ball(tip, 0.08 + i * 0.012, Draw.RED, 1.4f, 6);
            Draw.orbit(tip, 0.35, i * 0.6, 1.2, Draw.WHITE, 0.6f, 8);
            if (i % 4 == 0) SmpGojo.sound(tip, "block.respawn_anchor.charge", 1.5f, 0.8f + i * 0.07f);
        }, () -> {
            Location orb = SmpGojo.hand(p);
            Vector dir = p.getEyeLocation().getDirection().normalize();
            SmpGojo.sound(orb, "entity.warden.sonic_boom", 2.5f, 1.6f);
            SmpGojo.sound(orb, "item.trident.riptide_3", 2f, 1.4f);
            boolean[] done = {false};
            pl.repeat(1, 28, i -> {
                if (done[0]) return;
                for (int s = 0; s < 3; s++) {
                    orb.add(dir.clone().multiply(0.85));
                    Draw.ball(orb, 0.35, Draw.RED, 1.8f, 10);
                    Draw.dust(orb.clone().subtract(dir.clone().multiply(0.5)), Draw.WHITE, 1f);
                    if (!orb.getBlock().isPassable() || !pl.targets(p, orb, 1.4).isEmpty()) {
                        done[0] = true;
                        redBurst(p, orb.clone(), dir);
                        return;
                    }
                }
                Draw.fx(orb, Particle.FLAME, 2, 0.1, 0.01);
            }, () -> {
                if (!done[0]) redBurst(p, orb.clone(), dir);
            });
        });
    }

    private void redBurst(Player p, Location at, Vector dir) {
        double dmg = pl.cfg("damage.rouge", 14);
        double r = pl.cfg("rouge.radius", 4.5);
        List<LivingEntity> victims = pl.targets(p, at, r + 3);
        List<Player> vs = pl.viewers(at, 60);
        pl.cine.hitStop(victims, 4);
        pl.cine.impact(vs, victims, Cine.RED);
        Draw.ball(at, 1.2, Draw.RED, 3f, 60);
        SmpGojo.sound(at, "entity.lightning_bolt.thunder", 2f, 1.8f);
        pl.later(4, () -> {
            Draw.fx(at, Particle.EXPLOSION_EMITTER, 2, 0.5, 0);
            Draw.sphere(at, r, Draw.RED, 3f, 140);
            Draw.sphere(at, r * 0.6, Draw.WHITE, 2f, 60);
            SmpGojo.sound(at, "entity.generic.explode", 4f, 0.6f);
            SmpGojo.sound(at, "item.mace.smash_ground_heavy", 3f, 0.7f);
            pl.ruin.carve(at, r, 0, 0.3, dir.clone().multiply(0.6), null, 60);
            pl.ruin.shockwave(at, r * 3, 12, 0.6);
            pl.cine.shake(vs, 4, 2);
            for (LivingEntity le : victims) {
                pl.hit(p, le, dmg, "rouge");
                Vector push = le.getLocation().toVector().subtract(at.toVector()).add(dir.clone().multiply(1.5));
                if (push.lengthSquared() < 0.01) push = dir.clone();
                le.setVelocity(push.normalize().multiply(3.4).setY(1));
            }
        });
    }

    // ================================================================= Violet creux

    void purple(Player p) {
        List<String> lines = List.of("<gray>« <white>Neuf cordes.</white> »", "<gray>« <white>Lumière polarisée.</white> »",
                "<gray>« <white>Corbeau et déclaration.</white> »", "<gray>« <white>Entre l'avant et l'arrière.</white> »");
        int per = 14, chant = lines.size() * per, merge = 14;
        pl.cine.chant(p, 60, lines, per, "block.beacon.ambient", () -> {
        });
        pl.repeat(1, chant + merge, i -> {
            Location eye = p.getEyeLocation();
            Vector d = eye.getDirection().normalize();
            Vector side = Draw.perpendicular(d, 0);
            Location base = eye.clone().add(d.clone().multiply(1.8));
            double off = i < chant ? 2.2 : 2.2 * (1 - (i - chant) / (double) merge);
            double grow = Math.min(1, i / (double) chant);
            Location b = base.clone().add(side.clone().multiply(-off)).add(0, Math.sin(i * 0.2) * 0.2, 0);
            Location r = base.clone().add(side.clone().multiply(off)).add(0, -Math.sin(i * 0.2) * 0.2, 0);
            Draw.ball(b, 0.2 + grow * 0.35, Draw.BLUE, 1.4f, 10);
            Draw.orbit(b, 0.5 + grow * 0.4, i * 0.4, 0.8, Draw.LIGHT_BLUE, 0.7f, 8);
            Draw.ball(r, 0.2 + grow * 0.35, Draw.RED, 1.4f, 10);
            Draw.orbit(r, 0.5 + grow * 0.4, -i * 0.4, -0.8, Draw.WHITE, 0.7f, 8);
            if (i % per == 0 && i < chant) pl.ruin.pop(p.getLocation(), 2.5 + i / 14.0, 10, 0.35);
            if (i % 5 == 0) SmpGojo.sound(base, "block.amethyst_block.resonate", 1.5f, 0.5f + i / 80f);
        }, () -> launchPurple(p));
    }

    private void launchPurple(Player p) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection().normalize();
        Location orb = eye.clone().add(d.clone().multiply(2.5));
        List<Player> vs = pl.viewers(orb, 80);
        pl.cine.impact(vs, null, Cine.PURPLE);
        pl.cine.shake(vs, 3, 2);
        pl.cine.title(vs, "<gradient:#9628ff:#e0a8ff><b>VIOLET CREUX", "", 25);
        SmpGojo.sound(orb, "entity.warden.sonic_boom", 4f, 0.6f);
        SmpGojo.sound(orb, "block.beacon.power_select", 3f, 0.5f);
        double r = pl.cfg("violet.radius", 3.6), dmg = pl.cfg("damage.violet", 35);
        int steps = pl.cfgi("violet.ticks", 50);
        Set<UUID> hit = new HashSet<>();
        pl.repeat(1, steps, i -> {
            orb.add(d.clone().multiply(1.5));
            Draw.sphere(orb, r, Draw.PURPLE, 3.2f, 70);
            Draw.sphere(orb, r * 0.8, Draw.PINK, 2f, 25);
            Draw.ball(orb, r * 0.4, Draw.WHITE, 2f, 20);
            Draw.fx(orb, Particle.REVERSE_PORTAL, 30, r * 0.4, 0.05);
            Draw.fx(orb, Particle.END_ROD, 6, r * 0.3, 0.05);
            for (LivingEntity le : pl.targets(p, orb, r + 0.6)) {
                if (!hit.add(le.getUniqueId())) continue;
                pl.hit(p, le, dmg, "violet");
                le.setVelocity(d.clone().multiply(1.8).setY(0.5));
                Draw.fx(le.getLocation().add(0, 1, 0), Particle.REVERSE_PORTAL, 40, 0.4, 0.4);
            }
            pl.ruin.carve(orb, r, 0, 0.06, null, orb, 12);
            if (i % 4 == 0) SmpGojo.sound(orb, "entity.ender_dragon.flap", 3f, 0.5f);
            if (i % 10 == 0) pl.cine.shake(pl.viewers(orb, 20), 1, 1);
        }, () -> {
            Draw.sphere(orb, r * 1.6, Draw.PURPLE, 3f, 120);
            Draw.fx(orb, Particle.EXPLOSION_EMITTER, 2, 1, 0);
            SmpGojo.sound(orb, "entity.generic.explode", 3f, 0.5f);
        });
    }

    // ================================================================= Violet creux 200 %

    void nuke(Player p) {
        Location target = pl.aimPoint(p, pl.cfg("nuke.range", 140));
        List<Player> vs = pl.viewers(p.getLocation(), 160);
        UUID id = p.getUniqueId();
        pl.noFall.add(id);
        boolean couldFly = p.getAllowFlight();
        p.setAllowFlight(true);
        p.setVelocity(new Vector(0, 1.25, 0));
        SmpGojo.sound(p.getLocation(), "entity.breeze.wind_burst", 3f, 0.6f);
        pl.ruin.shockwave(p.getLocation(), 6, 8, 0.6);
        pl.later(12, () -> {
            if (!p.isOnline()) return;
            p.setGravity(false);
            p.setVelocity(new Vector());
        });
        Runnable land = () -> {
            p.setGravity(true);
            if (!couldFly && p.getGameMode() != org.bukkit.GameMode.CREATIVE && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
            pl.later(100, () -> pl.noFall.remove(id));
        };
        pl.pending.add(land);
        List<String> lines = List.of("<gray>« <white>Neuf cordes.</white> »", "<gray>« <white>Lumière polarisée.</white> »",
                "<gray>« <white>Corbeau et déclaration.</white> »", "<gray>« <white>Entre l'avant et l'arrière.</white> »",
                "<gradient:#9628ff:#ff4dff><b>« Violet creux : 200 % »");
        int per = 12, chant = lines.size() * per, merge = 16;
        pl.later(10, () -> pl.cine.chant(p, 160, lines, per, "block.beacon.ambient", () -> {
        }));
        double maxR = pl.cfg("nuke.radius", 20);
        pl.repeat(1, 10 + chant + merge, i -> {
            if (i < 10) return;
            int k = i - 10;
            Location above = p.getLocation().add(0, 4.5, 0);
            Vector side = SmpGojo.right(p);
            double grow = Math.min(1, k / (double) chant);
            double off = k < chant ? 6 : 6 * (1 - (k - chant) / (double) merge);
            Location b = above.clone().add(side.clone().multiply(-off));
            Location r = above.clone().add(side.clone().multiply(off));
            Draw.ball(b, 0.6 + grow * 1.9, Draw.BLUE, 2.4f, 30);
            Draw.orbit(b, 1.4 + grow * 2.2, k * 0.25, 0.6, Draw.LIGHT_BLUE, 1.2f, 20);
            Draw.ball(r, 0.6 + grow * 1.9, Draw.RED, 2.4f, 30);
            Draw.orbit(r, 1.4 + grow * 2.2, -k * 0.25, -0.6, Draw.WHITE, 1.2f, 20);
            Draw.converge(above, 10, Draw.PURPLE, 0.8f, 8);
            if (k % 12 == 0) {
                Location ground = p.getLocation().add(0, -8, 0);
                Block g = Ruin.surface(p.getWorld(), ground.getBlockX(), p.getLocation().getBlockY() - 6, ground.getBlockZ());
                if (g != null) pl.ruin.shockwave(g.getLocation().add(0.5, 1, 0.5), 8 + k / 6.0, 10, 0.5);
            }
            if (k % 6 == 0) SmpGojo.sound(above, "block.beacon.ambient", 4f, 0.5f + k / 120f);
            if (k % 15 == 0) pl.cine.shake(vs, 1, 1);
        }, () -> {
            Location from = p.getLocation().add(0, 4.5, 0);
            pl.cine.impact(vs, null, Cine.PURPLE);
            pl.cine.shake(vs, 4, 2);
            SmpGojo.sound(from, "entity.warden.sonic_boom", 5f, 0.4f);
            SmpGojo.sound(from, "entity.wither.spawn", 3f, 1.4f);
            Location orb = from.clone();
            Vector to = target.toVector().subtract(orb.toVector());
            int steps = Math.max(10, (int) (to.length() / 2.2));
            Vector step = to.multiply(1.0 / steps);
            double dmg = pl.cfg("damage.nuke", 50);
            Set<UUID> hit = new HashSet<>();
            pl.repeat(1, steps, i -> {
                orb.add(step);
                Draw.sphere(orb, 4.5, Draw.PURPLE, 4f, 90);
                Draw.ball(orb, 2, Draw.WHITE, 2.5f, 30);
                Draw.fx(orb, Particle.REVERSE_PORTAL, 40, 2, 0.1);
                for (LivingEntity le : pl.targets(p, orb, 5)) {
                    if (hit.add(le.getUniqueId())) pl.hit(p, le, dmg, "nuke");
                }
                pl.ruin.carve(orb, 4.5, 0, 0.03, null, orb, 10);
                if (i % 3 == 0) SmpGojo.sound(orb, "entity.ender_dragon.flap", 4f, 0.4f);
            }, () -> detonate(p, target, maxR, hit, vs, land));
        });
    }

    private void detonate(Player p, Location at, double maxR, Set<UUID> hit, List<Player> vs0, Runnable land) {
        List<Player> vs = pl.viewers(at, 200);
        for (Player v : vs0) if (!vs.contains(v) && v.isOnline()) vs.add(v);
        List<LivingEntity> victims = pl.targets(p, at, maxR + 4);
        double dmg = pl.cfg("damage.nuke", 50);
        pl.cine.hitStop(victims, 8);
        pl.cine.impact(vs, victims, Cine.NUKE);
        pl.cine.letterbox(vs, 70);
        Draw.ball(at, 3, Draw.WHITE, 4f, 120);
        SmpGojo.sound(at, "entity.lightning_bolt.thunder", 5f, 0.5f);
        SmpGojo.sound(at, "block.end_portal.spawn", 5f, 0.6f);
        int grow = 28;
        double[] prev = {0};
        pl.later(8, () -> {
            SmpGojo.sound(at, "entity.generic.explode", 6f, 0.4f);
            SmpGojo.sound(at, "entity.wither.death", 4f, 0.6f);
            pl.cine.shake(vs, 14, 3);
            pl.repeat(1, grow + 24, i -> {
                if (i < grow) {
                    double r = maxR * Math.sqrt((i + 1) / (double) grow);
                    Draw.sphere(at, r, Draw.PURPLE, 4.5f, (int) Math.min(300, 50 + r * 10));
                    Draw.sphere(at, r * 0.9, Draw.PINK, 3f, (int) Math.min(120, 20 + r * 4));
                    Draw.ball(at, r * 0.5, Draw.WHITE, 3f, 40);
                    Draw.fx(at, Particle.END_ROD, 40, r * 0.5, 0.25);
                    pl.ruin.carve(at, r, prev[0], 0.004, null, null, 70);
                    prev[0] = Math.max(0, r - 0.5);
                    for (LivingEntity le : pl.targets(p, at, r)) {
                        if (!hit.add(le.getUniqueId())) continue;
                        pl.hit(p, le, dmg, "nuke");
                        Vector push = le.getLocation().toVector().subtract(at.toVector());
                        if (push.lengthSquared() < 0.01) push = new Vector(0, 1, 0);
                        le.setVelocity(push.normalize().multiply(3).setY(1.4));
                    }
                    if (i % 5 == 0) SmpGojo.sound(at, "entity.generic.explode", 5f, 0.4f + i / 50f);
                } else {
                    int k = i - grow;
                    double r = maxR * (1 - k / 24.0);
                    if (r > 1) Draw.sphere(at, r, Draw.PURPLE, 3f, (int) Math.min(200, 30 + r * 6));
                    Draw.fx(at, Particle.CAMPFIRE_COSIGNAL_SMOKE, 8, maxR * 0.4, 0.05);
                }
            }, () -> {
                pl.ruin.shockwave(at, maxR + 14, 16, 0.8);
                land.run();
                pl.pending.remove(land);
            });
        });
    }

    // ================================================================= Sphère de l'infini

    void domain(Player p) {
        int r = pl.cfgi("domain.radius", 13);
        int seconds = pl.cfgi("domain.seconds", 10);
        Location c = p.getLocation().getBlock().getLocation();
        World w = c.getWorld();
        List<Player> vs = pl.viewers(c, 100);
        pl.cine.letterbox(vs, 30);
        pl.cine.title(vs, "", "<white>« Extension du territoire... »", 22);
        SmpGojo.sound(c, "block.beacon.power_select", 3f, 0.5f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 22, 4, false, false, false));
        pl.repeat(1, 22, i -> {
            Draw.orbit(p.getLocation().add(0, 1, 0), 1.2, i * 0.4, 0.3, i % 2 == 0 ? Draw.WHITE : Draw.BLACK, 1f, 10);
            Draw.converge(p.getLocation().add(0, 1, 0), 4, Draw.WHITE, 0.7f, 6);
        }, () -> openDomain(p, c, r, seconds, w));
    }

    private void openDomain(Player p, Location c, int r, int seconds, World w) {
        List<Player> vs = pl.viewers(c, 100);
        pl.cine.impact(vs, null, new Cine.Frame[]{Cine.f(255, Draw.BLACK, 3), Cine.f(255, Draw.WHITE, 1), Cine.f(220, Draw.BLACK, 2)});
        pl.cine.title(vs, "<gradient:#ffffff:#6a6a6a:#ffffff><b>SPHÈRE DE L'INFINI", "<gray>Extension du territoire", 50);
        SmpGojo.sound(c, "block.end_portal.spawn", 4f, 0.7f);
        SmpGojo.sound(c, "entity.wither.spawn", 2f, 1.6f);
        // le dôme (seulement dans l'affichage)
        List<BlockState> shell = new ArrayList<>();
        BlockData black = Material.BLACK_CONCRETE.createBlockData(), star = Material.WHITE_CONCRETE.createBlockData(),
                glow = Material.SEA_LANTERN.createBlockData(), blue = Material.LIGHT_BLUE_CONCRETE.createBlockData();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int x = -r; x <= r; x++) {
            for (int y = -1; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    boolean isShell = d <= r && d > r - 1.1;
                    boolean floor = y == -1 && d <= r;
                    if (!isShell && !floor) continue;
                    Block b = w.getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
                    BlockState st = b.getState();
                    double roll = rnd.nextDouble();
                    st.setBlockData(floor ? (roll < 0.05 ? glow : roll < 0.08 ? star : black)
                            : roll < 0.07 ? star : roll < 0.1 ? glow : roll < 0.12 ? blue : black);
                    shell.add(st);
                }
            }
        }
        shell.sort(Comparator.comparingInt(BlockState::getY));
        int waves = 8;
        int per = (shell.size() + waves - 1) / waves;
        for (int k = 0; k < waves; k++) {
            List<BlockState> part = shell.subList(Math.min(shell.size(), k * per), Math.min(shell.size(), (k + 1) * per));
            List<BlockState> copy = new ArrayList<>(part);
            pl.later(k, () -> {
                for (Player v : vs) if (v.isOnline()) v.sendBlockChanges(copy);
            });
        }
        // victimes figées, submergées d'informations
        List<LivingEntity> victims = pl.targets(p, c, r - 1);
        int ticks = seconds * 20;
        for (LivingEntity le : victims) {
            pl.cine.hold(le, ticks);
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 255, false, false, false));
            le.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, ticks, 255, false, false, false));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 255, false, false, false));
            AttributeInstance j = le.getAttribute(Attribute.JUMP_STRENGTH);
            if (j != null) {
                j.removeModifier(pl.jumpKey);
                j.addTransientModifier(new AttributeModifier(pl.jumpKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            }
            if (le instanceof Player v) v.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, ticks, 1, false, false, false));
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 2, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 1, false, false, false));
        Runnable[] close = new Runnable[1];
        close[0] = () -> {
            pl.pending.remove(close[0]);
            List<BlockState> real = new ArrayList<>();
            for (BlockState s : shell) real.add(s.getBlock().getState());
            real.sort(Comparator.comparingInt(BlockState::getY).reversed());
            int n = (real.size() + 5) / 6;
            for (int k = 0; k < 6; k++) {
                List<BlockState> part = new ArrayList<>(real.subList(Math.min(real.size(), k * n), Math.min(real.size(), (k + 1) * n)));
                pl.later(k, () -> {
                    for (Player v : vs) if (v.isOnline()) v.sendBlockChanges(part);
                });
            }
            for (int k = 0; k < 120 && !shell.isEmpty(); k++) {
                BlockState s = shell.get(rnd.nextInt(shell.size()));
                Location at = s.getLocation().add(0.5, 0.5, 0.5);
                Vector out = at.toVector().subtract(c.toVector());
                if (out.lengthSquared() < 0.01) out = new Vector(0, 1, 0);
                pl.ruin.spawnDebris(s.getBlockData(), at, out.normalize().multiply(0.4).setY(0.3), null, 40);
            }
            if (pl.isEnabled()) {
                pl.cine.impact(pl.viewers(c, 100), null, Cine.FLASH);
                SmpGojo.sound(c, "block.glass.break", 4f, 0.5f);
                SmpGojo.sound(c, "block.beacon.deactivate", 3f, 0.6f);
            }
            for (LivingEntity le : victims) {
                if (!le.isValid()) continue;
                pl.cine.release(le);
                AttributeInstance j = le.getAttribute(Attribute.JUMP_STRENGTH);
                if (j != null) j.removeModifier(pl.jumpKey);
            }
        };
        pl.pending.add(close[0]);
        pl.repeat(2, seconds * 10, i -> {
            if (!pl.pending.contains(close[0])) return;
            Location top = c.clone().add(0.5, r * 0.55, 0.5);
            Draw.orbit(top, r * 0.5, i * 0.12, 0.25, Draw.WHITE, 1.2f, 30);
            Draw.orbit(top, r * 0.35, -i * 0.18, 0.4, Draw.LIGHT_BLUE, 1f, 20);
            Draw.fx(top, Particle.END_ROD, 10, r * 0.4, 0.02);
            for (LivingEntity le : victims) {
                if (!le.isValid()) continue;
                Location head = le.getEyeLocation();
                Vector from = Draw.randomDir().multiply(r * 0.8);
                Draw.segment(c.clone().add(0.5, r * 0.4, 0.5).add(from), head, Draw.WHITE, 0.5f, 0.6);
                Draw.fx(head, Particle.ENCHANT, 25, 0.4, 1);
                if (le instanceof Player v) {
                    v.sendActionBar(SmpGojo.mm("<obf>||||||||||||||||</obf> <white>∞</white> <obf>||||||||||||||||"));
                    if (rnd.nextInt(4) == 0) pl.cine.tint(v, Cine.argb(rnd.nextBoolean() ? 140 : 190,
                            rnd.nextBoolean() ? Draw.WHITE : Draw.BLACK), 1);
                }
            }
            if (i % 10 == 0) SmpGojo.sound(c, "block.beacon.ambient", 3f, 0.5f + rnd.nextFloat() * 0.5f);
        }, () -> {
            if (pl.pending.contains(close[0])) close[0].run();
        });
    }

    // ================================================================= nettoyage

    void quit(Player p) {
        infinity.remove(p.getUniqueId());
        releaseProjectiles(p.getUniqueId());
        p.setGravity(true);
    }

    void cleanup() {
        for (UUID id : new ArrayList<>(stopped.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.setGravity(true);
        }
        stopped.clear();
    }
}
