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
import org.bukkit.util.Transformation;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.BlockDisplay;

import org.joml.Quaternionf;
import org.joml.Vector3f;
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

    /** La Lance céleste inversée de Toji : l'Infini est brisé un moment. */
    void breakInfinity(Player p, int ticks) {
        if (!infinity.remove(p.getUniqueId())) return;
        releaseProjectiles(p.getUniqueId());
        Location c = p.getLocation().add(0, 1, 0);
        Draw.sphere(c, 1.4, Draw.LIGHT_BLUE, 1f, 60);
        Draw.fx(c, Particle.END_ROD, 30, 0.8, 0.2);
        SmpGojo.sound(c, "block.glass.break", 3f, 0.6f);
        SmpGojo.sound(c, "block.beacon.deactivate", 2f, 1.6f);
        p.sendActionBar(SmpGojo.mm("<red><b>∞ Infini brisé"));
        pl.later(ticks, () -> {
            if (p.isOnline() && infinity.add(p.getUniqueId())) {
                p.sendActionBar(SmpGojo.mm("<gradient:#ffffff:#9ad7ff><b>∞ Infini rétabli"));
                SmpGojo.sound(p.getLocation(), "block.beacon.activate", 1.2f, 1.8f);
            }
        });
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
                if (i % 2 == 0) pl.ruin.carve(orb, 2.2 + i * 0.025, 0, 0.6, null, orb, 300);
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
                pl.ruin.crater(orb, pl.cfg("bleu.crater", 4.5), 0.35, 5, false);
                pl.ruin.shockwave(orb, 12, 10, 0.6);
                pl.ruin.smoke(orb, 3, 60);
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
        double r = pl.cfg("rouge.radius", 6);
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
            pl.ruin.crater(at, r, 0.3, 7, false);
            pl.ruin.shockwave(at, r * 3.5, 14, 0.7);
            pl.ruin.smoke(at, r * 0.6, 80);
            pl.cine.shake(vs, 4, 2);
            for (LivingEntity le : victims) {
                pl.hit(p, le, dmg, "rouge");
                Vector push = le.getLocation().toVector().subtract(at.toVector()).add(dir.clone().multiply(1.5));
                if (push.lengthSquared() < 0.01) push = dir.clone();
                le.setVelocity(push.normalize().multiply(3.6).setY(1));
                pl.smash(le, 12, 1.6);
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
        double r = pl.cfg("violet.radius", 4.5), dmg = pl.cfg("damage.violet", 35);
        int steps = pl.cfgi("violet.ticks", 70);
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
            if (i % 6 == 0) pl.ruin.shatter(orb, r * 2.5);
            if (i % 4 == 0) SmpGojo.sound(orb, "entity.ender_dragon.flap", 3f, 0.5f);
            if (i % 10 == 0) pl.cine.shake(pl.viewers(orb, 20), 1, 1);
        }, () -> {
            Draw.sphere(orb, r * 1.6, Draw.PURPLE, 3f, 120);
            Draw.fx(orb, Particle.EXPLOSION_EMITTER, 2, 1, 0);
            pl.ruin.crater(orb, r * 1.8, 0.2, 6, false);
            pl.ruin.shockwave(orb, r * 4, 12, 0.6);
            SmpGojo.sound(orb, "entity.generic.explode", 3f, 0.5f);
        });
    }

    // ================================================================= Violet creux 200 %

    /**
     * Violet creux 200 %, comme l'animation : la nuit tombe, éclairs violets, l'orbe rouge et l'orbe bleu
     * fusionnent, la boule violette se condense dans la main de Gojo, il la lance ; elle file vers la cible
     * en laissant une traînée, puis une colonne de lumière jaillit du sol jusqu'au ciel. Tout devient blanc.
     * Il reste un trou parfaitement rond relié à la ligne de la boule.
     */
    void nuke(Player p) {
        double range = pl.cfg("nuke.range", 300);
        Location target = pl.aimPoint(p, range);
        World w = p.getWorld();
        Block g = Ruin.surface(w, target.getBlockX(), target.getBlockY(), target.getBlockZ());
        if (g == null) g = w.getHighestBlockAt(target.getBlockX(), target.getBlockZ());
        Location ground = g.getLocation().add(0.5, 1, 0.5);
        double R = pl.cfg("nuke.radius", 55);
        preload(w, p.getLocation(), ground, R);
        List<Player> vs = pl.viewers(p.getLocation(), 450);
        for (Player v : pl.viewers(ground, 450)) if (!vs.contains(v)) vs.add(v);

        // la nuit tombe pour tous les spectateurs
        Runnable[] day = {null};
        day[0] = () -> {
            for (Player v : vs) if (v.isOnline()) v.resetPlayerTime();
            pl.pending.remove(day[0]);
        };
        pl.pending.add(day[0]);
        for (Player v : vs) v.setPlayerTime(18000, false);

        List<String> lines = List.of("<gray>« <white>Neuf cordes.</white> »", "<gray>« <white>Lumière polarisée.</white> »",
                "<gray>« <white>Corbeau et déclaration.</white> »", "<gray>« <white>Entre l'avant et l'arrière.</white> »",
                "<gradient:#9628ff:#ff4dff><b>« Violet creux : 200 % »");
        int per = 16, chant = lines.size() * per, merge = 18, hold = 24;
        pl.cine.chant(p, 450, lines, per, "block.beacon.ambient", () -> {
        });
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, chant + merge + hold, 5, false, false, false));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // 1) éclairs violets et les deux orbes géants qui se rapprochent
        pl.repeat(1, chant + merge, k -> {
            Location eye = p.getEyeLocation();
            Vector d = eye.getDirection().normalize();
            Vector side = Draw.perpendicular(d, 0);
            Location front = eye.clone().add(d.clone().multiply(7)).add(0, 2, 0);
            double off = k < chant ? 7 : 7 * (1 - (k - chant) / (double) merge);
            double r = 2.2 + Math.min(1, k / (double) chant) * 1.6;
            Location red = front.clone().add(side.clone().multiply(-off)), blue = front.clone().add(side.clone().multiply(off));
            Draw.sphere(red, r, Draw.RED, 3.2f, 70);
            Draw.ball(red, r * 0.8, org.bukkit.Color.fromRGB(255, 110, 110), 1.6f, 40);
            Draw.orbit(red, r * 1.15, k * 0.25, 0.4, Draw.WHITE, 1f, 18);
            Draw.sphere(blue, r, Draw.BLUE, 3.2f, 70);
            Draw.ball(blue, r * 0.8, Draw.LIGHT_BLUE, 1.6f, 40);
            Draw.orbit(blue, r * 1.15, -k * 0.25, -0.4, Draw.WHITE, 1f, 18);
            // éclairs dans le ciel
            if (k % 9 == 0) {
                Location sky = p.getLocation().add(rnd.nextDouble(-25, 25), 25 + rnd.nextDouble(10), rnd.nextDouble(-25, 25));
                Draw.bolt(sky, new Vector(rnd.nextDouble(-0.3, 0.3), -1, rnd.nextDouble(-0.3, 0.3)), 18 + rnd.nextDouble(10), Draw.PINK, Draw.PURPLE, 1.8f);
                SmpGojo.sound(sky, "entity.lightning_bolt.thunder", 6f, 0.6f + rnd.nextFloat() * 0.3f);
            }
            if (k % 30 == 15) {
                Location strike = p.getLocation().add(rnd.nextDouble(-30, 30), 0, rnd.nextDouble(-30, 30));
                w.strikeLightningEffect(w.getHighestBlockAt(strike).getLocation().add(0, 1, 0));
            }
            if (k % 8 == 0) SmpGojo.sound(front, "block.beacon.ambient", 4f, 0.5f + k / 140f);
            if (k % 20 == 0) pl.cine.shake(vs, 1, 1);
        }, () -> {
            // 2) la fusion : noir, puis la boule violette se condense dans sa main
            pl.cine.impact(vs, null, new Cine.Frame[]{Cine.f(255, Draw.BLACK, 3), Cine.f(150, Draw.PURPLE, 2)});
            SmpGojo.sound(p.getLocation(), "entity.warden.sonic_boom", 6f, 0.5f);
            SmpGojo.sound(p.getLocation(), "block.end_portal.spawn", 4f, 0.8f);
            pl.repeat(1, hold, k -> {
                Location h = SmpGojo.hand(p).add(0, 0.6, 0);
                double gr = 0.3 + k / (double) hold;
                Draw.ball(h, 0.35 * gr, Draw.WHITE, 1.5f, 14);
                Draw.sphere(h, 0.6 * gr, Draw.PINK, 1.6f, 22);
                Draw.sphere(h, 0.9 * gr, Draw.PURPLE, 1.4f, 26);
                // halo d'étincelles autour de la boule
                Draw.sphere(h, 2.6, Draw.LIGHT_BLUE, 0.6f, 30);
                Draw.orbit(h, 2.2, k * 0.35, 1.2, Draw.WHITE, 0.6f, 24);
                Draw.converge(h, 3, Draw.PURPLE, 0.8f, 8);
                if (k % 4 == 0) SmpGojo.sound(h, "block.amethyst_block.resonate", 2f, 0.6f + k * 0.05f);
            }, () -> launch200(p, ground, R, vs, day[0]));
        });
    }

    /** Charge les chunks du trajet et de la zone en arrière-plan pendant l'incantation. */
    private void preload(World w, Location from, Location to, double R) {
        Set<Long> seen = new HashSet<>();
        List<int[]> todo = new ArrayList<>();
        Vector line = to.toVector().subtract(from.toVector());
        double len = line.length();
        for (double t = 0; t <= len; t += 8) {
            Location l = from.clone().add(line.clone().multiply(len < 1 ? 0 : t / len));
            int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
            if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) todo.add(new int[]{cx, cz});
        }
        for (double x = -R - 16; x <= R + 16; x += 16) {
            for (double z = -R - 16; z <= R + 16; z += 16) {
                int cx = (to.getBlockX() + (int) x) >> 4, cz = (to.getBlockZ() + (int) z) >> 4;
                if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) todo.add(new int[]{cx, cz});
            }
        }
        List<int[]> ticketed = new ArrayList<>();
        for (int[] c : todo) {
            if (!w.isChunkGenerated(c[0], c[1])) continue;
            w.getChunkAtAsync(c[0], c[1], false).whenComplete((ch, err) -> {
                if (ch != null && w.addPluginChunkTicket(c[0], c[1], pl)) ticketed.add(c);
            });
        }
        pl.later(20L * 120, () -> {
            for (int[] c : ticketed) w.removePluginChunkTicket(c[0], c[1], pl);
        });
    }

    /** 3) Le lancer : la boule file vers la cible en creusant une traînée. */
    private void launch200(Player p, Location ground, double R, List<Player> vs, Runnable day) {
        Location start = SmpGojo.hand(p).add(0, 0.6, 0);
        Location end = ground.clone().add(0, 1, 0);
        Vector path = end.toVector().subtract(start.toVector());
        double len = Math.max(1, path.length());
        Vector d = path.clone().normalize();
        double speed = pl.cfg("nuke.ball-speed", 5), tunnel = pl.cfg("nuke.trail-radius", 4);
        pl.cine.impact(vs, null, new Cine.Frame[]{Cine.f(230, Draw.WHITE, 1), Cine.f(200, Draw.PURPLE, 2), Cine.f(100, Draw.PINK, 2)});
        pl.cine.shake(vs, 3, 2);
        SmpGojo.sound(start, "entity.warden.sonic_boom", 8f, 0.6f);
        SmpGojo.sound(start, "item.trident.riptide_3", 6f, 0.5f);
        Draw.fx(start, Particle.EXPLOSION_EMITTER, 2, 0.5, 0);
        Location pos = start.clone();
        double[] done = {0};
        double dmg = pl.cfg("damage.nuke", 200);
        Set<UUID> hit = new HashSet<>();
        pl.repeat(1, (int) Math.ceil(len / speed) + 1, i -> {
            for (double s = 0; s < speed && done[0] < len; s += 1) {
                pos.add(d.clone().multiply(Math.min(1, len - done[0])));
                done[0] += 1;
                if (((int) done[0]) % 2 == 0 && done[0] > 6) pl.ruin.carve(pos, tunnel, 0, 0.05, d.clone().multiply(0.5), null, 30);
                for (LivingEntity le : pl.targets(p, pos, tunnel + 1)) {
                    if (hit.add(le.getUniqueId())) {
                        pl.hit(p, le, dmg * 0.5, "nuke");
                        le.setVelocity(d.clone().multiply(2).setY(0.5));
                    }
                }
            }
            Draw.ball(pos, 1.2, Draw.WHITE, 2.5f, 25);
            Draw.sphere(pos, 2.2, Draw.PURPLE, 3f, 40);
            Draw.fx(pos, Particle.END_ROD, 12, 1, 0.1);
            Draw.segment(pos.clone().subtract(d.clone().multiply(Math.min(done[0], 12))), pos, Draw.PINK, 2f, 0.5);
            if (i % 3 == 0) SmpGojo.sound(pos, "entity.ender_dragon.flap", 6f, 0.6f);
        }, () -> eruption(p, ground, R, vs, day, hit));
    }

    /** 4) Un point de lumière, puis la colonne jaillit du sol jusqu'au ciel et efface tout. */
    private void eruption(Player p, Location ground, double R, List<Player> vs, Runnable day, Set<UUID> hit) {
        World w = ground.getWorld();
        double dmg = pl.cfg("damage.nuke", 200);
        int depth = pl.cfgi("nuke.depth", 40);
        double skyY = Math.min(w.getMaxHeight() - 2, ground.getY() + pl.cfg("nuke.column-height", 220));
        double total = skyY - (ground.getY() - depth);
        // le petit point de lumière avant l'éruption
        pl.repeat(1, 12, k -> {
            Draw.ball(ground.clone().add(0, 1, 0), 0.6 + k * 0.08, Draw.WHITE, 2f, 20);
            Draw.ring(ground.clone().add(0, 0.3, 0), 1.5 + k * 0.4, Draw.PURPLE, 2f, 30);
            Draw.fx(ground.clone().add(0, 1, 0), Particle.END_ROD, 6, 0.6, 0.15);
            if (k % 4 == 0) SmpGojo.sound(ground, "block.respawn_anchor.charge", 8f, 0.6f + k * 0.08f);
        }, () -> {
            List<Player> all = new ArrayList<>(vs);
            for (Player v : pl.viewers(ground, R + 400)) if (!all.contains(v)) all.add(v);
            Beam beam = new Beam(pl, ground, R);
            Runnable[] clean = {null};
            clean[0] = () -> {
                beam.remove();
                pl.pending.remove(clean[0]);
                day.run();
            };
            pl.pending.add(clean[0]);
            // tout le cylindre disparaît sous la colonne (budget de temps : pas de lag)
            MegaHole hole = pl.ruin.mega(w, ground.getX(), ground.getZ(), R, skyY, ground.getBlockY() - depth, pl.cfgi("nuke.restore-seconds", 360));
            if (hole != null) hole.trigger();
            // l'écran passe au blanc puis revient
            pl.cine.impact(all, null, new Cine.Frame[]{Cine.f(200, Draw.PINK, 2), Cine.f(255, Draw.WHITE, 8), Cine.f(235, Draw.WHITE, 4),
                    Cine.f(200, Draw.PINK, 4), Cine.f(150, Draw.WHITE, 4), Cine.f(100, Draw.PINK, 4), Cine.f(50, Draw.WHITE, 4)});
            pl.cine.shake(all, 30, 2);
            for (int k = 0; k < 4; k++) {
                int kk = k;
                pl.later(k * 6L, () -> {
                    SmpGojo.sound(ground, "entity.generic.explode", 10f, 0.4f + kk * 0.05f);
                    SmpGojo.sound(ground, "entity.lightning_bolt.thunder", 10f, 0.5f);
                });
            }
            SmpGojo.sound(ground, "block.beacon.power_select", 10f, 0.5f);
            SmpGojo.sound(ground, "entity.wither.spawn", 8f, 0.6f);
            int rise = 14, minHold = pl.cfgi("nuke.hold-ticks", 80), maxHold = pl.cfgi("nuke.max-hold-ticks", 600);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int[] tick = {0};
            org.bukkit.scheduler.BukkitTask[] loop = new org.bukkit.scheduler.BukkitTask[1];
            loop[0] = Bukkit.getScheduler().runTaskTimer(pl, SmpGojo.guard(pl, loop, "violet 200 %", () -> {
                int i = tick[0]++;
                if (!pl.pending.contains(clean[0])) {
                    loop[0].cancel();
                    return;
                }
                if (i < rise) {
                    // la colonne monte depuis le sol
                    double prog = Math.pow((i + 1) / (double) rise, 0.7);
                    double h = total * prog;
                    beam.setTop(ground.clone().add(0, h - depth, 0));
                    beam.shape(R * (0.35 + 0.65 * prog), h, 1);
                } else if (i % 10 == 0) {
                    beam.setTop(new Location(w, ground.getX(), skyY, ground.getZ()));
                    beam.shape(R * (1 + 0.03 * Math.sin(i * 0.3)), total, 10);
                }
                // traînées de lumière qui montent dans la colonne
                for (int k = 0; k < 30; k++) {
                    double a = rnd.nextDouble() * Math.PI * 2, rr = Math.sqrt(rnd.nextDouble()) * R;
                    Location l = ground.clone().add(Math.cos(a) * rr, rnd.nextDouble(0, skyY - ground.getY()), Math.sin(a) * rr);
                    w.spawnParticle(Particle.END_ROD, l, 0, 0, 1, 0, 2.5, null, true);
                }
                if (i % 4 == 0) {
                    Draw.ring(ground.clone().add(0, 0.5, 0), R + 0.6, Draw.PINK, 4f, (int) Math.min(300, R * 4));
                    Draw.ring(ground.clone().add(0, 2, 0), R + 1.5, Draw.PURPLE, 3f, (int) Math.min(200, R * 2));
                }
                if (i % 5 == 0) {
                    for (LivingEntity le : pl.targets(p, ground, R + 1)) {
                        if (le.getLocation().getY() < ground.getY() - depth - 2) continue;
                        if (hit.add(le.getUniqueId())) pl.hit(p, le, dmg, "nuke");
                        else pl.hit(p, le, dmg * 0.25, "nuke");
                        le.setVelocity(new Vector(0, 1.2, 0));
                    }
                    for (Entity e : w.getNearbyEntities(ground, R, 200, R)) if (e instanceof Item) e.remove();
                }
                if (i % 10 == 0) {
                    for (LivingEntity le : pl.targets(p, ground, Math.min(R + 40, 160))) {
                        Vector out = le.getLocation().toVector().subtract(ground.toVector()).setY(0);
                        if (out.lengthSquared() > 0.01) le.setVelocity(le.getVelocity().add(out.normalize().multiply(0.5).setY(0.1)));
                    }
                    SmpGojo.sound(ground, "block.beacon.ambient", 10f, 0.5f);
                }
                if (i % 30 == 0) SmpGojo.sound(ground, "entity.generic.explode", 10f, 0.4f);
                boolean erasing = hole != null && hole.erasing();
                if ((i >= minHold && !erasing) || i >= maxHold) {
                    loop[0].cancel();
                    // la colonne s'amincit et s'éteint : il reste le trou rond et la ligne de la boule
                    beam.shape(0.01, total, 25);
                    SmpGojo.sound(ground, "block.beacon.deactivate", 10f, 0.5f);
                    pl.ruin.shockwave(ground, Math.min(R + 40, 120), 24, 1.0);
                    pl.ruin.smoke(ground.clone().add(0, -depth * 0.5, 0), Math.min(R * 0.5, 30), 200);
                    pl.later(30, () -> {
                        if (pl.pending.contains(clean[0])) clean[0].run();
                    });
                }
            }), 1L, 1L);
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
