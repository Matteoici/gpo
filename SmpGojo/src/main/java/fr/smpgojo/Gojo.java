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

    void nuke(Player p) {
        preload200(p);
        List<Player> vs = pl.viewers(p.getLocation(), 400);
        List<String> lines = List.of("<gray>« <white>Neuf cordes.</white> »", "<gray>« <white>Lumière polarisée.</white> »",
                "<gray>« <white>Corbeau et déclaration.</white> »", "<gray>« <white>Entre l'avant et l'arrière.</white> »",
                "<gradient:#9628ff:#ff4dff><b>« Violet creux : 200 % »");
        int per = 12, chant = lines.size() * per, merge = 16;
        pl.cine.chant(p, 400, lines, per, "block.beacon.ambient", () -> {
        });
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, chant + merge, 4, false, false, false));
        pl.repeat(1, chant + merge, k -> {
            Location eye = p.getEyeLocation();
            Vector d = flat(eye.getDirection());
            Vector side = Draw.perpendicular(d, 0);
            Location front = eye.clone().add(d.clone().multiply(5));
            double grow = Math.min(1, k / (double) chant);
            double off = k < chant ? 4.5 : 4.5 * (1 - (k - chant) / (double) merge);
            Location b = front.clone().add(side.clone().multiply(-off));
            Location r = front.clone().add(side.clone().multiply(off));
            Draw.ball(b, 0.4 + grow * 1.6, Draw.BLUE, 2.2f, 26);
            Draw.orbit(b, 1 + grow * 1.8, k * 0.3, 0.6, Draw.LIGHT_BLUE, 1f, 16);
            Draw.ball(r, 0.4 + grow * 1.6, Draw.RED, 2.2f, 26);
            Draw.orbit(r, 1 + grow * 1.8, -k * 0.3, -0.6, Draw.WHITE, 1f, 16);
            Draw.converge(front, 9, Draw.PURPLE, 0.8f, 8);
            if (k % 12 == 0) pl.ruin.shockwave(p.getLocation(), 4 + k / 8.0, 8, 0.4);
            if (k % 6 == 0) SmpGojo.sound(front, "block.beacon.ambient", 4f, 0.5f + k / 120f);
            if (k % 20 == 0) pl.cine.shake(vs, 1, 1);
        }, () -> launch200(p));
    }

    /**
     * Pendant l'incantation : charge en arrière-plan tous les chunks du trajet et de l'explosion,
     * pour que la boule n'ait jamais à attendre.
     */
    private void preload200(Player p) {
        World w = p.getWorld();
        double bR = pl.cfg("nuke.ball-radius", 22), R = pl.cfg("nuke.radius", 55), length = pl.cfg("nuke.length", 200);
        Vector d = flat(p.getEyeLocation().getDirection());
        Vector side = Draw.perpendicular(d, 0);
        Location from = p.getEyeLocation().add(d.clone().multiply(bR + 3));
        Set<Long> seen = new HashSet<>();
        List<int[]> todo = new ArrayList<>();
        for (double t = -8; t <= length + R + 8; t += 8) {
            double half = t > length - R ? R + 8 : bR + 8;
            for (double s = -half; s <= half; s += 8) {
                Location l = from.clone().add(d.clone().multiply(t)).add(side.clone().multiply(s));
                int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
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
        // on relâche les chunks une fois le 200 % largement terminé
        pl.later(20L * 90, () -> {
            for (int[] c : ticketed) w.removePluginChunkTicket(c[0], c[1], pl);
        });
    }

    private static Vector flat(Vector v) {
        Vector f = v.clone().setY(0);
        return f.lengthSquared() < 0.001 ? new Vector(1, 0, 0) : f.normalize();
    }

    /**
     * Le 200 % : une boule violette part droit devant, à l'horizontale, sans rien casser.
     * Au premier contact (un bloc ou quelqu'un), elle explose : une sphère géante qui grandit
     * au rythme où le serveur efface (budget de temps par tick : pas de lag). Tout disparaît dedans.
     */
    private void launch200(Player p) {
        double R = pl.cfg("nuke.radius", 55);
        double bR = pl.cfg("nuke.ball-radius", 22), speed = pl.cfg("nuke.ball-speed", 3);
        double dmg = pl.cfg("damage.nuke", 200);
        Location eye = p.getEyeLocation();
        Vector d = flat(eye.getDirection());
        World w = eye.getWorld();
        Location pos = eye.clone().add(d.clone().multiply(bR + 3));

        List<BlockDisplay> orb = new ArrayList<>();
        Material[] mats = {Material.PURPLE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS, Material.PINK_STAINED_GLASS, Material.WHITE_CONCRETE};
        double[] scales = {1.75, 1.6, 1.4, 0.8};
        for (Material m : mats) {
            orb.add(w.spawn(pos, BlockDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "prop");
                e.setBlock(m.createBlockData());
                e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                e.setViewRange(16f);
                e.setTeleportDuration(1);
            }));
        }
        Runnable[] clean = new Runnable[1];
        clean[0] = () -> {
            for (BlockDisplay o : orb) o.remove();
            pl.pending.remove(clean[0]);
        };
        pl.pending.add(clean[0]);
        Quaternionf[] spin = {new Quaternionf(), new Quaternionf().rotateXYZ(0.6f, 0.8f, 0.3f), new Quaternionf().rotateXYZ(-0.5f, 0.3f, 0.9f),
                new Quaternionf().rotateXYZ(0.2f, -0.7f, 0.4f)};
        java.util.function.BiConsumer<Double, Integer> shapeOrb = (rad, interp) -> {
            for (int k = 0; k < orb.size(); k++) {
                float s = (float) (rad * scales[k]);
                Quaternionf q = spin[k];
                Vector3f t = q.transform(new Vector3f(-s / 2, -s / 2, -s / 2));
                BlockDisplay o = orb.get(k);
                o.setInterpolationDelay(0);
                o.setInterpolationDuration(interp);
                o.setTransformation(new Transformation(t, new Quaternionf(q), new Vector3f(s, s, s), new Quaternionf()));
            }
        };
        shapeOrb.accept(0.5, 0);
        pl.later(1, () -> shapeOrb.accept(bR, 6));
        List<Player> vs = pl.viewers(pos, 450);
        pl.cine.shake(vs, 3, 2);
        SmpGojo.sound(pos, "entity.warden.sonic_boom", 8f, 0.5f);
        SmpGojo.sound(pos, "block.end_portal.spawn", 6f, 0.7f);

        // le tunnel : tout ce que la boule traverse disparaît au moment où elle passe
        double length = pl.cfg("nuke.length", 200);
        Location from = pos.clone();
        MegaHole tunnel = pl.ruin.mega(from, d, length, bR, pl.cfgi("nuke.restore-seconds", 360));
        if (tunnel != null) tunnel.trigger();
        double minSpeed = pl.cfg("nuke.min-speed", 0.6);
        Set<UUID> hit = new HashSet<>();
        double[] at = {0};
        int[] tick = {0};
        org.bukkit.scheduler.BukkitTask[] loop = new org.bukkit.scheduler.BukkitTask[1];
        loop[0] = Bukkit.getScheduler().runTaskTimer(pl, () -> {
            int i = tick[0]++;
            if (!pl.pending.contains(clean[0])) {
                loop[0].cancel();
                return;
            }
            // la boule suit le néant : elle ne va jamais plus vite que l'effacement
            boolean digging = tunnel != null && tunnel.erasing();
            double target = digging ? tunnel.progress() : length;
            double next = Math.min(length, Math.min(at[0] + speed, Math.max(target, at[0] + (digging ? 0 : speed))));
            if (digging && next < at[0] + minSpeed && target >= at[0] + minSpeed) next = at[0] + minSpeed;
            at[0] = Math.max(at[0], next);
            pos.setX(from.getX() + d.getX() * at[0]);
            pos.setY(from.getY());
            pos.setZ(from.getZ() + d.getZ() * at[0]);
            for (BlockDisplay o : orb) o.teleport(pos);
            if (i % 2 == 0) {
                for (int k = 0; k < spin.length; k++) spin[k].rotateY(0.4f + k * 0.1f).rotateX(0.2f);
                shapeOrb.accept(bR, 2);
            }
            Draw.sphere(pos, bR * 1.05, Draw.PURPLE, 3.5f, (int) Math.min(120, 30 + bR * 3));
            Draw.fx(pos, Particle.REVERSE_PORTAL, 30, bR * 0.4, 0.15);
            Draw.fx(pos.clone().subtract(d.clone().multiply(bR)), Particle.END_ROD, 15, bR * 0.3, 0.05);
            if (i % 4 == 0) SmpGojo.sound(pos, "entity.ender_dragon.flap", 8f, 0.5f);
            if (i % 20 == 0) pl.cine.shake(pl.viewers(pos, bR + 60), 1, 1);
            if (i % 4 == 0) capsuleZap(p, from, d, at[0], bR + 1, dmg, hit);
            // au bout du trajet : l'explosion
            if ((at[0] >= length && !digging) || i >= pl.cfgi("nuke.max-ticks", 1200)) {
                loop[0].cancel();
                explode200(p, pos.clone(), d, R, dmg, orb, spin, shapeOrb, clean);
            }
        }, 1L, 1L);
    }

    /** L'explosion : une sphère géante qui grandit en effaçant tout. */
    private void explode200(Player p, Location c, Vector d, double R, double dmg, List<BlockDisplay> orb, Quaternionf[] spin,
                            java.util.function.BiConsumer<Double, Integer> shapeOrb, Runnable[] clean) {
        List<Player> all = pl.viewers(c, R + 300);
        pl.cine.impact(all, null, new Cine.Frame[]{Cine.f(220, Draw.PURPLE, 2), Cine.f(150, Draw.PINK, 2), Cine.f(70, Draw.PURPLE, 2)});
        pl.cine.shake(all, 30, 2);
        SmpGojo.sound(c, "entity.generic.explode", 10f, 0.4f);
        SmpGojo.sound(c, "entity.warden.sonic_boom", 10f, 0.4f);
        SmpGojo.sound(c, "entity.lightning_bolt.thunder", 10f, 0.5f);
        SmpGojo.sound(c, "entity.wither.death", 8f, 0.5f);
        Draw.fx(c, Particle.EXPLOSION_EMITTER, 6, 3, 0);
        // la boule éclate : elle disparaît et vole en morceaux de verre violet
        double bR0 = pl.cfg("nuke.ball-radius", 22);
        shapeOrb.accept(0.01, 3);
        pl.later(4, () -> {
            for (BlockDisplay o : orb) o.remove();
        });
        Material[] shards = {Material.PURPLE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS, Material.PINK_STAINED_GLASS,
                Material.WHITE_STAINED_GLASS, Material.AMETHYST_BLOCK};
        ThreadLocalRandom rr = ThreadLocalRandom.current();
        for (int k = 0; k < 120; k++) {
            Vector dir = Draw.randomDir();
            Location at = c.clone().add(dir.clone().multiply(bR0 * rr.nextDouble(0.3, 1)));
            pl.ruin.spawnDebris(shards[rr.nextInt(shards.length)].createBlockData(), at, dir.multiply(1.2 + rr.nextDouble() * 1.6), null, 50);
        }
        Draw.ball(c, bR0, Draw.WHITE, 4f, 200);
        Draw.sphere(c, bR0, Draw.PINK, 4f, 200);
        SmpGojo.sound(c, "block.glass.break", 10f, 0.5f);
        SmpGojo.sound(c, "block.amethyst_block.break", 10f, 0.5f);
        MegaHole hole = pl.ruin.mega(c, d, 0.01, R, pl.cfgi("nuke.restore-seconds", 360));
        if (hole != null) hole.trigger();
        Set<UUID> hit = new HashSet<>();
        double[] cur = {Math.min(R, pl.cfg("nuke.ball-radius", 22))};
        int maxTicks = pl.cfgi("nuke.max-ticks", 1200);
        int[] tick = {0};
        org.bukkit.scheduler.BukkitTask[] loop = new org.bukkit.scheduler.BukkitTask[1];
        loop[0] = Bukkit.getScheduler().runTaskTimer(pl, () -> {
            int i = tick[0]++;
            if (!pl.pending.contains(clean[0])) {
                loop[0].cancel();
                return;
            }
            // la sphère suit le néant : elle grandit au rythme où tout disparaît
            double target = hole == null ? R : hole.radiusProgress();
            if (hole == null || !hole.erasing()) target = R;
            cur[0] = Math.min(R, Math.max(cur[0] + 0.15, Math.min(target, cur[0] + 3)));
            // l'onde d'énergie : coquille violette, rose dedans, cœur blanc, éclats qui partent
            double e0 = cur[0];
            Draw.sphere(c, e0, Draw.PURPLE, 4.5f, (int) Math.min(320, 60 + e0 * 5));
            Draw.sphere(c, e0 * 0.9, Draw.PINK, 3.5f, (int) Math.min(160, 30 + e0 * 2));
            Draw.ball(c, Math.max(2, e0 * 0.35), Draw.WHITE, 3.5f, 50);
            Draw.fx(c, Particle.END_ROD, 40, e0 * 0.5, 0.35);
            Draw.fx(c, Particle.REVERSE_PORTAL, 40, e0 * 0.4, 0.3);
            if (i % 3 == 0) Draw.orbit(c, e0 * 1.05, i * 0.2, 0.3, Draw.LIGHT_BLUE, 2.5f, (int) Math.min(120, e0 * 3));
            if (i % 5 == 0) {
                for (LivingEntity le : pl.targets(p, c, cur[0] + 1)) {
                    if (hit.add(le.getUniqueId())) {
                        pl.hit(p, le, dmg, "nuke");
                        Draw.fx(le.getLocation().add(0, 1, 0), Particle.REVERSE_PORTAL, 60, 0.5, 0.5);
                    } else {
                        pl.hit(p, le, dmg * 0.25, "nuke");
                    }
                }
                for (Entity e : c.getWorld().getNearbyEntities(c, cur[0], cur[0], cur[0])) {
                    if (e instanceof Item && e.getLocation().distanceSquared(c) <= cur[0] * cur[0]) e.remove();
                }
            }
            if (i % 8 == 0) SmpGojo.sound(c, "entity.generic.explode", 10f, 0.4f + (i % 24) * 0.01f);
            if (i % 10 == 0) SmpGojo.sound(c, "block.beacon.ambient", 10f, 0.5f);
            if (i % 20 == 0) pl.cine.shake(pl.viewers(c, R + 100), 1, 1);
            boolean finished = cur[0] >= R && (hole == null || !hole.erasing());
            if (finished || i >= maxTicks) {
                loop[0].cancel();
                // la sphère se résorbe
                pl.repeat(1, 14, k -> {
                    double rr2 = R * (1 - k / 14.0);
                    if (rr2 > 1) Draw.sphere(c, rr2, Draw.PURPLE, 4f, (int) Math.min(250, 40 + rr2 * 4));
                }, null);
                SmpGojo.sound(c, "block.beacon.deactivate", 10f, 0.5f);
                pl.ruin.shockwave(c.clone().add(0, -R * 0.6, 0), Math.min(90, R * 1.6), 22, 0.9);
                pl.ruin.smoke(c.clone().add(0, -R * 0.5, 0), Math.min(30, R * 0.5), 200);
                pl.later(26, () -> {
                    if (pl.pending.contains(clean[0])) clean[0].run();
                });
            }
        }, 1L, 1L);
    }

    /** Tout ce qui est dans la boule le long de son trajet (jusqu'à "upTo") est touché ; les objets disparaissent. */
    private void capsuleZap(Player p, Location from, Vector d, double upTo, double r, double dmg, Set<UUID> hit) {
        Location mid = from.clone().add(d.clone().multiply(upTo / 2));
        double half = upTo / 2 + r;
        for (Entity e : from.getWorld().getNearbyEntities(mid, half, r, half)) {
            Vector rel = e.getLocation().toVector().subtract(from.toVector());
            double t = Math.max(0, Math.min(upTo, rel.dot(d)));
            if (rel.subtract(d.clone().multiply(t)).lengthSquared() > r * r) continue;
            if (e instanceof Item) {
                e.remove();
                continue;
            }
            if (!(e instanceof LivingEntity le) || !pl.valid(p, le)) continue;
            if (hit.add(le.getUniqueId())) {
                pl.hit(p, le, dmg, "nuke");
                Draw.fx(le.getLocation().add(0, 1, 0), Particle.REVERSE_PORTAL, 60, 0.5, 0.5);
            } else {
                pl.hit(p, le, dmg * 0.25, "nuke");
            }
            le.setVelocity(d.clone().multiply(1.5));
        }
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
