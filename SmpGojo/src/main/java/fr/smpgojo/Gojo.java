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
        Location target = pl.aimPoint(p, pl.cfg("nuke.range", 300));
        List<Player> vs = pl.viewers(p.getLocation(), 700);
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
        }, () -> skyBeam(p, target, vs, land));
    }

    /**
     * Le vrai 200 % : la sphère monte dans le ciel, un vortex violet géant se forme au-dessus de la cible,
     * puis une colonne de lumière de plusieurs centaines de blocs tombe et réduit tout à néant.
     * Il ne reste qu'un trou parfaitement rond, qui se reconstruit tout seul plus tard.
     */
    private void skyBeam(Player p, Location aimed, List<Player> vs0, Runnable land) {
        World w = aimed.getWorld();
        Block g = Ruin.surface(w, aimed.getBlockX(), aimed.getBlockY(), aimed.getBlockZ());
        Location ground = g != null ? g.getLocation().add(0.5, 1, 0.5) : aimed.clone();
        double R = pl.cfg("nuke.radius", 55);
        int depth = pl.cfgi("nuke.depth", 40);
        int skyH = Math.max(40, Math.min(pl.cfgi("nuke.sky-height", 200), w.getMaxHeight() - 4 - ground.getBlockY()));
        double dmg = pl.cfg("damage.nuke", 200);
        Location sky = ground.clone().add(0, skyH, 0);
        List<Player> vs = pl.viewers(ground, R + 400);
        for (Player v : vs0) if (!vs.contains(v) && v.isOnline()) vs.add(v);

        // le sous-sol est creusé en cachette dès maintenant (la surface reste en place)
        MegaHole hole = pl.ruin.mega(w, ground.getX(), ground.getZ(), R, sky.getY(), ground.getBlockY() - depth,
                pl.cfgi("nuke.restore-seconds", 180));
        if (hole != null) hole.hiddenCeil = ground.getBlockY() - 1;

        // 1) la sphère violette part dans le ciel
        Location from = p.getLocation().add(0, 4.5, 0);
        pl.cine.impact(vs, null, Cine.PURPLE);
        pl.cine.shake(vs, 3, 2);
        SmpGojo.sound(from, "entity.warden.sonic_boom", 5f, 0.4f);
        SmpGojo.sound(from, "entity.wither.spawn", 3f, 1.4f);
        Location orb = from.clone();
        int travel = Math.max(16, (int) (from.distance(sky) / 7));
        Vector step = sky.toVector().subtract(orb.toVector()).multiply(1.0 / travel);
        pl.repeat(1, travel, i -> {
            orb.add(step);
            Draw.sphere(orb, 4, Draw.PURPLE, 4f, 80);
            Draw.ball(orb, 2, Draw.WHITE, 3f, 30);
            Draw.fx(orb, Particle.REVERSE_PORTAL, 40, 2, 0.1);
            if (i % 3 == 0) SmpGojo.sound(orb, "entity.ender_dragon.flap", 6f, 0.4f);
        }, () -> vortex(p, ground, sky, R, skyH, depth, dmg, vs, land, hole));
    }

    /** 2) Le vortex dans les nuages : il s'étend sur des centaines de blocs. */
    private void vortex(Player p, Location ground, Location sky, double R, int skyH, int depth, double dmg, List<Player> vs, Runnable land,
                        MegaHole hole) {
        SmpGojo.sound(sky, "block.end_portal.spawn", 10f, 0.5f);
        SmpGojo.sound(ground, "entity.lightning_bolt.thunder", 10f, 0.5f);
        int ticks = pl.cfgi("nuke.vortex-ticks", 60);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double maxOuter = Math.min(R * 1.8, 360);
        pl.repeat(1, ticks, i -> {
            double grow = (i + 1) / (double) ticks;
            double outer = Math.max(20, maxOuter * (0.3 + 0.7 * grow));
            double stepT = Math.max(0.02, 4.0 / outer);
            for (int arm = 0; arm < 5; arm++) {
                for (double t = 0.04; t <= 1; t += stepT) {
                    double a = arm * Math.PI * 2 / 6 + i * 0.09 + t * 4;
                    double rr = outer * t;
                    Location l = sky.clone().add(Math.cos(a) * rr, -t * outer * 0.04 + rnd.nextDouble(-1, 1), Math.sin(a) * rr);
                    Draw.dust(l, t < 0.25 ? Draw.WHITE : t < 0.55 ? Draw.PINK : Draw.PURPLE, (float) (4 - t * 1.5));
                    if (rnd.nextInt(8) == 0) Draw.fx(l, Particle.CLOUD, 1, 1, 0.02);
                }
            }
            Draw.ball(sky, 2 + grow * Math.min(40, R * 0.18), Draw.WHITE, 4f, 60);
            Draw.sphere(sky, 3 + grow * Math.min(55, R * 0.25), Draw.PINK, 4f, 60);
            Draw.fx(sky, Particle.END_ROD, 40, Math.min(40, R * 0.2) * grow, 0.2);
            // la zone visée s'éclaire, le sol tremble
            if (i % 4 == 0) Draw.ring(ground.clone().add(0, 0.5, 0), R, Draw.PURPLE, 4f, (int) Math.min(250, R * 3));
            if (i % 8 == 0) {
                SmpGojo.sound(sky, "entity.lightning_bolt.thunder", 10f, 0.4f + rnd.nextFloat() * 0.3f);
                pl.cine.shake(vs, 1, 1);
                for (int k = 0; k < 4; k++) {
                    double a = rnd.nextDouble() * Math.PI * 2, rr = rnd.nextDouble() * R;
                    pl.ruin.pop(ground.clone().add(Math.cos(a) * rr, 0, Math.sin(a) * rr), 3, 8, 0.5);
                }
            }
            if (i % 12 == 0 && i > 0) {
                double a = rnd.nextDouble() * Math.PI * 2, rr = rnd.nextDouble() * R;
                ground.getWorld().strikeLightningEffect(ground.clone().add(Math.cos(a) * rr, 0, Math.sin(a) * rr));
            }
            if (i == ticks / 2) pl.cine.impact(vs, null, Cine.FLASH);
        }, () -> fall(p, ground, sky, R, skyH, depth, dmg, vs, land, hole));
    }

    /** 3) La colonne tombe, 4) écran blanc, 5) tout est effacé, 6) elle s'éteint : un trou parfait. */
    private void fall(Player p, Location ground, Location sky, double R, int skyH, int depth, double dmg, List<Player> vs, Runnable land,
                      MegaHole hole) {
        World w = ground.getWorld();
        Beam beam = new Beam(pl, sky, R);
        Runnable[] clean = new Runnable[1];
        clean[0] = () -> {
            beam.remove();
            pl.pending.remove(clean[0]);
            land.run();
            pl.pending.remove(land);
        };
        pl.pending.add(clean[0]);
        Set<UUID> hit = new HashSet<>();
        int bottomY = ground.getBlockY() - depth;
        double total = sky.getY() - bottomY;
        int descend = 20;
        SmpGojo.sound(sky, "entity.warden.sonic_boom", 10f, 0.4f);
        SmpGojo.sound(sky, "item.trident.thunder", 10f, 0.5f);
        pl.repeat(1, descend, i -> {
            double prog = Math.pow((i + 1) / (double) descend, 1.5);
            double h = total * prog;
            double r = R * (0.35 + 0.65 * prog);
            beam.shape(r, h, 1);
            if (i % 5 == 0) zap(p, ground, r, sky.getY() - h, sky.getY(), dmg, hit);
            Draw.fx(sky.clone().add(0, -h, 0), Particle.END_ROD, 25, Math.min(40, r * 0.4), 0.4);
        }, () -> {
            // impact : tout devient blanc, très loin
            List<Player> all = pl.viewers(ground, R + 500);
            for (Player v : vs) if (!all.contains(v) && v.isOnline()) all.add(v);
            for (Player v : all) pl.cine.tint(v, Cine.argb(255, Draw.WHITE), 3);
            pl.cine.shake(all, 40, 2);
            for (int k = 0; k < 4; k++) {
                int kk = k;
                pl.later(k * 6L, () -> {
                    SmpGojo.sound(ground, "entity.generic.explode", 10f, 0.4f + kk * 0.05f);
                    SmpGojo.sound(ground, "entity.lightning_bolt.thunder", 10f, 0.5f);
                });
            }
            SmpGojo.sound(ground, "entity.wither.death", 10f, 0.5f);
            SmpGojo.sound(ground, "block.beacon.power_select", 10f, 0.5f);
            beam.shape(R, total, 4);
            // le néant : le cylindre entier est effacé, chunk par chunk depuis le centre
            // le néant : tout le cylindre est effacé pendant que l'écran est blanc (budget de temps : pas de lag)
            if (hole != null) hole.trigger();
            int maxWhite = pl.cfgi("nuke.max-white-ticks", 200);
            boolean[] revealed = {false};
            int hold = pl.cfgi("nuke.hold-ticks", 80), maxHold = pl.cfgi("nuke.max-hold-ticks", 600);
            pl.ruin.shockwave(ground, Math.min(R + 40, 120), 24, 1.0);
            ThreadLocalRandom r0 = ThreadLocalRandom.current();
            boolean[] cracked = {false};
            // fissures géantes autour du trou et bord arraché, une fois le trou creusé
            Runnable edges = () -> {
                if (cracked[0]) return;
                cracked[0] = true;
                int cracks = pl.cfgi("nuke.cracks", 16);
                for (int k = 0; k < cracks; k++) {
                    double a = k * Math.PI * 2 / cracks + r0.nextDouble(-0.15, 0.15);
                    pl.later(k * 3L, () -> pl.ruin.crack(ground, a, R, Math.min(90, R * (0.3 + r0.nextDouble() * 0.4)), false));
                }
                int bites = (int) Math.min(16, 6 + R / 20);
                for (int k = 0; k < bites; k++) {
                    double a = r0.nextDouble() * Math.PI * 2;
                    double br = Math.max(3, Math.min(14, R * (0.04 + r0.nextDouble() * 0.04)));
                    Location edge = ground.clone().add(Math.cos(a) * R, -1, Math.sin(a) * R);
                    pl.later(10L + k * 6L, () -> pl.ruin.crater(edge, br, 0.08, 2, false));
                }
                if (R <= 30) pl.ruin.shatter(ground, R + 12);
            };
            pl.ruin.smoke(ground.clone().add(0, 4, 0), Math.min(R * 0.5, 40), 300);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int streaks = (int) Math.min(40, 10 + R / 6);
            int ringPts = (int) Math.min(300, R * 3);
            int[] tick = {0};
            org.bukkit.scheduler.BukkitTask[] loop = new org.bukkit.scheduler.BukkitTask[1];
            Runnable fade = () -> {
                beam.shape(0.01, total, 25);
                SmpGojo.sound(ground, "block.beacon.deactivate", 10f, 0.5f);
                pl.later(30, () -> {
                    if (pl.pending.contains(clean[0])) clean[0].run();
                });
                if (!cracked[0]) {
                    // le trou finit de se creuser après la colonne : on attend avant les fissures
                    pl.repeat(20, 120, k -> {
                        if (!cracked[0] && (hole == null || !hole.erasing())) edges.run();
                    }, null);
                }
            };
            loop[0] = Bukkit.getScheduler().runTaskTimer(pl, () -> {
                int i = tick[0]++;
                boolean erasing = hole != null && hole.erasing();
                boolean surface = hole != null && hole.surfacePending();
                // écran blanc tant que le trou n'est pas fini : quand il s'estompe, tout a disparu d'un coup
                if (!revealed[0]) {
                    if ((surface && i < maxWhite) || i < 12) {
                        for (Player v : all) if (v.isOnline()) pl.cine.tint(v, Cine.argb(255, Draw.WHITE), 2);
                    } else {
                        revealed[0] = true;
                        pl.cine.impact(all, null, new Cine.Frame[]{Cine.f(240, Draw.WHITE, 3), Cine.f(215, Draw.PINK, 3),
                                Cine.f(190, Draw.WHITE, 3), Cine.f(150, Draw.WHITE, 3), Cine.f(110, Draw.PINK, 3), Cine.f(70, Draw.WHITE, 3),
                                Cine.f(35, Draw.WHITE, 3)});
                        SmpGojo.sound(ground, "entity.lightning_bolt.thunder", 10f, 0.5f);
                    }
                }
                if (!erasing && !cracked[0]) edges.run();
                if ((i >= hold && !erasing) || i >= maxHold || !pl.pending.contains(clean[0])) {
                    loop[0].cancel();
                    fade.run();
                    return;
                }
                if (i % 10 == 0) beam.shape(R * (1 + 0.03 * Math.sin(i * 0.3)), total, 10);
                if (i % 10 == 0) zap(p, ground, R + 0.5, bottomY, sky.getY(), dmg, hit);
                // traînées de lumière qui tombent dans la colonne
                for (int k = 0; k < streaks; k++) {
                    double a = rnd.nextDouble() * Math.PI * 2, rr = Math.sqrt(rnd.nextDouble()) * R;
                    Location l = ground.clone().add(Math.cos(a) * rr, rnd.nextDouble(0, skyH), Math.sin(a) * rr);
                    w.spawnParticle(Particle.END_ROD, l, 0, 0, -1, 0, 2.5, null, true);
                }
                // anneau lumineux au sol
                if (i % 4 == 0) {
                    Draw.ring(ground.clone().add(0, 0.5, 0), R + 0.6, Draw.PINK, 4f, ringPts);
                    Draw.ring(ground.clone().add(0, 2, 0), R + 1.5, Draw.PURPLE, 3f, ringPts / 2);
                }
                // le souffle repousse tout autour
                if (i % 10 == 0) {
                    for (LivingEntity le : pl.targets(p, ground, Math.min(R + 40, 160))) {
                        Vector out = le.getLocation().toVector().subtract(ground.toVector()).setY(0);
                        if (out.lengthSquared() > 0.01) le.setVelocity(le.getVelocity().add(out.normalize().multiply(0.6).setY(0.1)));
                    }
                }
                if (i % 10 == 0) SmpGojo.sound(ground, "block.beacon.ambient", 10f, 0.5f);
                if (i % 30 == 0) SmpGojo.sound(ground, "entity.generic.explode", 10f, 0.4f);
                if (i % 20 == 0) pl.cine.shake(all, 1, 1);
            }, 1L, 1L);
        });
    }

    /** Tout ce qui est dans la colonne est touché ; les objets au sol disparaissent. */
    private void zap(Player p, Location ground, double r, double bottomY, double topY, double dmg, Set<UUID> hit) {
        double cy = (bottomY + topY) / 2, half = (topY - bottomY) / 2 + 2;
        Location c = new Location(ground.getWorld(), ground.getX(), cy, ground.getZ());
        for (Entity e : ground.getWorld().getNearbyEntities(c, r, half, r)) {
            double dx = e.getLocation().getX() - ground.getX(), dz = e.getLocation().getZ() - ground.getZ();
            if (dx * dx + dz * dz > r * r) continue;
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
            le.setVelocity(new Vector(0, -0.6, 0));
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
