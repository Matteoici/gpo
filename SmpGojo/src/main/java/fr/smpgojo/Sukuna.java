package fr.smpgojo;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Ryomen Sukuna : Démantèlement, Clivage, Fuga, Taille du monde, Sanctuaire Démoniaque. */
final class Sukuna {

    private final SmpGojo pl;

    Sukuna(SmpGojo pl) {
        this.pl = pl;
    }

    // ================================================================= Démantèlement

    void dismantle(Player p) {
        double dmg = pl.cfg("damage.demantelement", 8);
        SmpGojo.sound(p.getEyeLocation(), "entity.player.attack.sweep", 2f, 0.5f);
        for (int k = 0; k < 3; k++) {
            double tilt = (k - 1) * 0.7 + Draw.rnd().nextDouble(-0.25, 0.25);
            pl.later(k * 3L, () -> {
                Location eye = p.getEyeLocation();
                Vector d = eye.getDirection().normalize();
                Vector u = Draw.perpendicular(d, tilt);
                Location c = eye.clone().add(d.clone().multiply(1.2));
                Set<UUID> hit = new HashSet<>();
                SmpGojo.sound(c, "item.trident.throw", 2f, 1.9f);
                SmpGojo.sound(c, "entity.player.attack.sweep", 2f, 1.4f);
                pl.repeat(1, 14, i -> {
                    for (int s = 0; s < 2; s++) {
                        c.add(d.clone().multiply(1.3));
                        double half = 1.4 + i * 0.12;
                        Draw.slash(c, u, half, Draw.WHITE, Draw.BLOOD);
                        for (LivingEntity le : pl.targets(p, c, half)) {
                            Vector rel = le.getLocation().add(0, le.getHeight() / 2, 0).toVector().subtract(c.toVector());
                            if (Math.abs(rel.dot(d)) > 1.5 || !hit.add(le.getUniqueId())) continue;
                            pl.hit(p, le, dmg, "demantelement");
                            Draw.blood(le, 30);
                            pl.cine.hitStop(List.of(le), 2);
                            SmpGojo.sound(le.getLocation(), "entity.player.attack.crit", 1.5f, 0.7f);
                        }
                        pl.ruin.gash(c, u, half * 0.8, 0.55);
                    }
                }, null);
            });
        }
    }

    // ================================================================= Clivage

    void cleave(Player p) {
        LivingEntity target = pl.aim(p, 12);
        if (target == null) {
            p.sendActionBar(SmpGojo.mm("<red>Aucune cible en vue"));
            pl.resetCooldown(p, "clivage");
            return;
        }
        var max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double total = pl.cfg("damage.clivage-base", 8) + pl.cfg("damage.clivage-percent", 0.35) * (max == null ? 20 : max.getValue());
        List<Player> vs = pl.viewers(target.getLocation(), 40);
        pl.cine.hitStop(List.of(target), 10);
        pl.cine.impact(vs, List.of(target), Cine.RED);
        SmpGojo.sound(target.getLocation(), "entity.player.attack.strong", 2f, 0.5f);
        SmpGojo.sound(target.getLocation(), "block.anvil.land", 1.5f, 1.8f);
        pl.repeat(1, 10, i -> {
            if (!target.isValid()) return;
            Location c = target.getLocation().add(0, target.getHeight() / 2, 0);
            for (int s = 0; s < 4; s++) Draw.slash(c, Draw.randomDir(), 1.1 + target.getWidth(), Draw.BLOOD, Draw.BLACK);
            Draw.blood(target, 12);
            SmpGojo.sound(c, "entity.player.attack.sweep", 1.5f, 1.2f + i * 0.08f);
        }, () -> {
            if (!target.isValid()) return;
            pl.hit(p, target, total, "clivage");
            Draw.blood(target, 120);
            Location c = target.getLocation().add(0, target.getHeight() / 2, 0);
            Draw.fx(c, Particle.SWEEP_ATTACK, 8, 0.8, 0);
            SmpGojo.sound(c, "entity.generic.explode", 1.2f, 1.6f);
            Vector away = target.getLocation().toVector().subtract(p.getLocation().toVector());
            if (away.lengthSquared() < 0.01) away = p.getLocation().getDirection();
            target.setVelocity(away.normalize().multiply(1.4).setY(0.5));
            Location g = target.getLocation();
            for (int s = 0; s < 3; s++) pl.ruin.gash(g, Draw.perpendicular(new Vector(0, 1, 0).add(Draw.randomDir().multiply(0.1)), s), 2.5, 0.5);
        });
    }

    // ================================================================= Fuga

    void fuga(Player p) {
        List<Player> vs = pl.viewers(p.getLocation(), 60);
        pl.cine.letterbox(vs, 40);
        pl.cine.title(vs, "", "<gradient:#ff3c00:#ffb000><b>« ⟢ Ouverture ⟣ »", 30);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 32, 3, false, false, false));
        SmpGojo.sound(p.getLocation(), "item.firecharge.use", 2f, 0.5f);
        SmpGojo.sound(p.getLocation(), "entity.blaze.ambient", 2f, 0.5f);
        pl.repeat(1, 32, i -> {
            Location eye = p.getEyeLocation();
            Vector d = eye.getDirection().normalize();
            Vector side = SmpGojo.right(p);
            Location hand = eye.clone().add(d.clone().multiply(1.2)).add(0, -0.25, 0);
            double len = 0.3 + i * 0.05;
            // la flèche
            for (double t = -len; t <= len; t += 0.12) Draw.fx(hand.clone().add(d.clone().multiply(t)), Particle.FLAME, 1, 0, 0);
            // l'arc
            for (double a = -1.1; a <= 1.1; a += 0.15) {
                Location arc = hand.clone().add(d.clone().multiply(-0.35 + Math.cos(a) * 0.5 - 0.5))
                        .add(side.clone().multiply(Math.sin(a) * 0.9 * Math.min(1, i / 12.0)));
                Draw.fx(arc, Particle.FLAME, 1, 0, 0);
            }
            Draw.fx(p.getLocation().add(0, 1, 0), Particle.FLAME, 6, 0.8, 0.03);
            Draw.fx(p.getLocation(), Particle.LAVA, 1, 0.5, 0);
            if (i % 6 == 0) SmpGojo.sound(hand, "block.fire.ambient", 2f, 0.6f);
        }, () -> {
            Location eye = p.getEyeLocation();
            Vector d = eye.getDirection().normalize();
            Location arrow = eye.clone().add(d.clone().multiply(1.5));
            SmpGojo.sound(arrow, "entity.blaze.shoot", 3f, 0.4f);
            SmpGojo.sound(arrow, "item.crossbow.shoot", 3f, 0.5f);
            SmpGojo.sound(arrow, "entity.warden.sonic_boom", 2f, 1.6f);
            boolean[] done = {false};
            pl.repeat(1, 30, i -> {
                if (done[0]) return;
                for (int s = 0; s < 3; s++) {
                    arrow.add(d.clone().multiply(0.95));
                    Draw.fx(arrow, Particle.FLAME, 8, 0.1, 0.03);
                    Draw.orbit(arrow, 0.6, i * 0.9 + s, 1.4, Draw.ORANGE, 1f, 6);
                    if (!arrow.getBlock().isPassable() || !pl.targets(p, arrow, 1.3).isEmpty()) {
                        done[0] = true;
                        fugaBlast(p, arrow.clone());
                        return;
                    }
                }
                Draw.fx(arrow, Particle.LAVA, 1, 0, 0);
            }, () -> {
                if (!done[0]) fugaBlast(p, arrow.clone());
            });
        });
    }

    private void fugaBlast(Player p, Location at) {
        double dmg = pl.cfg("damage.fuga", 30), r = pl.cfg("fuga.radius", 9);
        List<LivingEntity> victims = pl.targets(p, at, r + 3);
        List<Player> vs = pl.viewers(at, 70);
        pl.cine.hitStop(victims, 4);
        pl.cine.impact(vs, victims, Cine.ORANGE);
        SmpGojo.sound(at, "entity.lightning_bolt.thunder", 2f, 1.5f);
        pl.later(4, () -> firestorm(p, at, r, dmg, vs, new HashSet<>()));
    }

    /** Explosion de feu : cratère, sol calciné, colonne de flammes, flammes qui restent. */
    private void firestorm(Player p, Location at, double r, double dmg, List<Player> vs, Set<UUID> hit) {
        World w = at.getWorld();
        Draw.fx(at, Particle.EXPLOSION_EMITTER, 3, 1, 0);
        SmpGojo.sound(at, "entity.generic.explode", 5f, 0.6f);
        SmpGojo.sound(at, "item.firecharge.use", 5f, 0.4f);
        SmpGojo.sound(at, "entity.blaze.death", 3f, 0.5f);
        pl.cine.shake(vs, 6, 2);
        pl.ruin.carve(at, Math.max(2.5, r * 0.4), 0, 0.35, null, null, 60);
        pl.ruin.scorch(at, r * 1.4, 0.75);
        pl.ruin.shockwave(at, r * 1.8, 12, 0.6);
        pl.repeat(2, 40, i -> {
            double cur = Math.min(r, r * (i + 1) / 6.0);
            w.spawnParticle(Particle.FLAME, at, 90, cur * 0.5, 1 + i * 0.3, cur * 0.5, 0.1, null, true);
            w.spawnParticle(Particle.LAVA, at, 10, cur * 0.4, 1, cur * 0.4, 0, null, true);
            w.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, i * 0.5, 0), 14, cur * 0.3, 0.5, cur * 0.3, 0.02, null, true);
            if (i < 12) {
                Draw.ring(at.clone().add(0, 0.3, 0), cur * 1.3, Particle.FLAME, (int) (20 + cur * 6));
                Draw.ring(at.clone().add(0, 0.6, 0), cur * 1.1, Draw.ORANGE, 1.6f, (int) (10 + cur * 3));
            }
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int k = 0; k < 10; k++) {
                Location f = at.clone().add(rnd.nextDouble(-r, r), 0.2, rnd.nextDouble(-r, r));
                Draw.fx(f, Particle.FLAME, 3, 0.2, 0.02);
            }
            for (LivingEntity le : pl.targets(p, at, cur)) {
                le.setFireTicks(Math.max(le.getFireTicks(), 160));
                if (hit.add(le.getUniqueId())) {
                    pl.hit(p, le, dmg, "fuga");
                    Vector push = le.getLocation().toVector().subtract(at.toVector());
                    if (push.lengthSquared() < 0.01) push = new Vector(0, 1, 0);
                    le.setVelocity(push.normalize().multiply(1.8).setY(0.9));
                } else if (i % 10 == 0) {
                    pl.hit(p, le, 2, "fuga");
                }
            }
            if (i % 6 == 0) SmpGojo.sound(at, "block.fire.ambient", 4f, 0.5f);
        }, null);
    }

    // ================================================================= Taille du monde

    void worldSlash(Player p) {
        List<String> lines = List.of("<dark_red>« <white>Écaille de dragon.</white> »", "<dark_red>« <white>Recul.</white> »",
                "<dark_red>« <white>Météores jumeaux.</white> »");
        pl.cine.chant(p, 90, lines, 13, "block.chain.place", () -> cutWorld(p));
        pl.repeat(2, 20, i -> {
            Location h = SmpGojo.hand(p);
            Draw.slash(h, Draw.perpendicular(p.getEyeLocation().getDirection(), i * 0.5), 0.5, Draw.WHITE, Draw.BLACK);
            Draw.fx(p.getLocation().add(0, 1, 0), Particle.SQUID_INK, 2, 0.6, 0.01);
        }, null);
    }

    private void cutWorld(Player p) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection().normalize();
        Vector u = Draw.perpendicular(d, 0.45);
        Vector n = d.clone().crossProduct(u).normalize();
        double length = pl.cfg("taille.length", 50), half = pl.cfg("taille.width", 16);
        double dmg = pl.cfg("damage.taille", 70);
        List<Player> vs = pl.viewers(eye, 120);
        pl.cine.impact(vs, null, Cine.WHITE_BLACK);
        pl.cine.shake(vs, 6, 2);
        pl.cine.title(vs, "", "<white><b>⟋", 10);
        SmpGojo.sound(eye, "entity.warden.sonic_boom", 5f, 1.5f);
        SmpGojo.sound(eye, "item.trident.riptide_3", 5f, 0.5f);
        SmpGojo.sound(eye, "block.end_portal.spawn", 3f, 2f);
        Location origin = eye.clone();
        Set<UUID> hit = new HashSet<>();
        int steps = 10;
        pl.repeat(1, steps, i -> {
            for (int s = 0; s < 2; s++) {
                double dist = (i * 2 + s + 1) * length / (steps * 2);
                Location c = origin.clone().add(d.clone().multiply(dist));
                Draw.slash(c, u, half, Draw.WHITE, Draw.BLACK);
                Draw.fx(c, Particle.SWEEP_ATTACK, 4, 0.4, 0);
                for (LivingEntity le : pl.targets(p, c, half + 1)) {
                    if (hit.contains(le.getUniqueId())) continue;
                    Vector rel = le.getLocation().add(0, le.getHeight() / 2, 0).toVector().subtract(c.toVector());
                    if (Math.abs(rel.dot(d)) > 1.8 || Math.abs(rel.dot(n)) > 1.6 || Math.abs(rel.dot(u)) > half) continue;
                    hit.add(le.getUniqueId());
                    pl.cine.hitStop(List.of(le), 4);
                    pl.pierceHit(p, le, dmg, "taille");
                    Draw.blood(le, 80);
                    pl.later(4, () -> le.setVelocity(n.clone().multiply(0.6).setY(0.3)));
                }
                // le plan coupe le terrain
                for (double t = -half; t <= half; t += 0.8) {
                    pl.ruin.carve(c.clone().add(u.clone().multiply(t)), 0.6, 0, 0.03, n.clone().multiply(0.3), null, 50);
                }
            }
            if (i % 3 == 0) SmpGojo.sound(origin.clone().add(d.clone().multiply(i * length / steps)), "entity.player.attack.sweep", 4f, 0.5f);
        }, () -> {
            // la fissure dans l'espace reste un instant
            pl.repeat(2, 15, i -> {
                for (double dist = 2; dist < length; dist += 2.5) {
                    Location c = origin.clone().add(d.clone().multiply(dist));
                    Draw.slash(c, u, half * 0.9, Draw.BLACK, Draw.WHITE);
                }
            }, null);
        });
    }

    // ================================================================= Sanctuaire Démoniaque

    void shrine(Player p) {
        List<Player> vs = pl.viewers(p.getLocation(), 100);
        pl.cine.letterbox(vs, 34);
        pl.cine.title(vs, "", "<dark_red>« <red>Extension du territoire...</red> »", 22);
        SmpGojo.sound(p.getLocation(), "block.bell.resonate", 3f, 0.5f);
        SmpGojo.sound(p.getLocation(), "entity.warden.heartbeat", 3f, 0.6f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 24, 4, false, false, false));
        pl.repeat(1, 24, i -> {
            Draw.converge(p.getLocation().add(0, 1, 0), 5, Draw.BLOOD, 1f, 10);
            Draw.fx(p.getLocation(), Particle.SCULK_SOUL, 2, 1.5, 0.02);
        }, () -> openShrine(p));
    }

    private void openShrine(Player p) {
        int r = pl.cfgi("sanctuaire.radius", 22);
        int seconds = pl.cfgi("sanctuaire.seconds", 10);
        double dmg = pl.cfg("damage.sanctuaire", 2);
        Location c = p.getLocation().getBlock().getLocation();
        World w = c.getWorld();
        List<Player> vs = pl.viewers(c, 110);
        pl.cine.impact(vs, null, Cine.RED);
        pl.cine.shake(vs, 3, 2);
        pl.cine.title(vs, "<gradient:#8b0000:#ff2a2a:#8b0000><b>SANCTUAIRE DÉMONIAQUE", "<dark_red>Extension du territoire", 50);
        SmpGojo.sound(c, "entity.wither.spawn", 3f, 0.5f);
        SmpGojo.sound(c, "block.bell.use", 4f, 0.5f);

        Vector back = p.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 0.01) back = new Vector(0, 0, 1);
        back.normalize().multiply(-6);
        Location base = c.clone().add(Math.round(back.getX()), 0, Math.round(back.getZ()));
        List<BlockState> fake = buildShrine(base);
        fake.sort(Comparator.comparingInt(BlockState::getY));
        int waves = 10, per = (fake.size() + waves - 1) / waves;
        for (int k = 0; k < waves; k++) {
            List<BlockState> part = new ArrayList<>(fake.subList(Math.min(fake.size(), k * per), Math.min(fake.size(), (k + 1) * per)));
            pl.later(k * 2L, () -> {
                for (Player v : vs) if (v.isOnline()) v.sendBlockChanges(part);
                SmpGojo.sound(base, "block.deepslate_bricks.place", 2f, 0.5f);
                pl.ruin.pop(base, 4, 8, 0.4);
            });
        }
        Runnable[] close = new Runnable[1];
        close[0] = () -> {
            pl.pending.remove(close[0]);
            List<BlockState> real = new ArrayList<>();
            for (BlockState s : fake) real.add(s.getBlock().getState());
            real.sort(Comparator.comparingInt(BlockState::getY).reversed());
            int n = (real.size() + 7) / 8;
            for (int k = 0; k < 8; k++) {
                List<BlockState> part = new ArrayList<>(real.subList(Math.min(real.size(), k * n), Math.min(real.size(), (k + 1) * n)));
                pl.later(k * 2L, () -> {
                    for (Player v : vs) if (v.isOnline()) v.sendBlockChanges(part);
                });
            }
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int k = 0; k < 80 && !fake.isEmpty(); k++) {
                BlockState s = fake.get(rnd.nextInt(fake.size()));
                pl.ruin.spawnDebris(s.getBlockData(), s.getLocation().add(0.5, 0.5, 0.5), Draw.randomDir().multiply(0.25).setY(0.2), null, 40);
            }
        };
        pl.pending.add(close[0]);
        boolean finish = pl.getConfig().getBoolean("sanctuaire.fuga-finish", true);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        pl.repeat(2, seconds * 10, i -> {
            if (!pl.pending.contains(close[0])) return;
            for (int s = 0; s < 8; s++) {
                Location at = c.clone().add(rnd.nextDouble(-r, r), rnd.nextDouble(0, 7), rnd.nextDouble(-r, r));
                if (at.distanceSquared(c) > r * r) continue;
                Draw.slash(at, Draw.randomDir(), 2.5, Draw.WHITE, Draw.BLOOD);
            }
            // le terrain est haché
            for (int s = 0; s < 3; s++) {
                double a = rnd.nextDouble() * Math.PI * 2, rr = rnd.nextDouble() * r;
                int x = (int) Math.floor(c.getX() + Math.cos(a) * rr), z = (int) Math.floor(c.getZ() + Math.sin(a) * rr);
                Block top = Ruin.surface(w, x, c.getBlockY(), z);
                if (top == null || top.getLocation().distanceSquared(base) < 49) continue;
                Location at = top.getLocation().add(0.5, 0.5, 0.5);
                Vector u = Draw.perpendicular(new Vector(rnd.nextDouble(-1, 1), 0.2, rnd.nextDouble(-1, 1)).normalize(), rnd.nextDouble(3));
                Draw.slash(at, u, 1.6, Draw.WHITE, Draw.BLOOD);
                pl.ruin.gash(at, u, 1.6, 0.6 + rnd.nextDouble() * 0.6);
            }
            for (LivingEntity le : pl.targets(p, c, r)) {
                Location l = le.getLocation().add(0, le.getHeight() / 2, 0);
                for (int s = 0; s < 3; s++) Draw.slash(l, Draw.randomDir(), 1 + le.getWidth(), Draw.BLOOD, Draw.BLACK);
                if (i % 2 == 0) {
                    pl.hit(p, le, dmg, "sanctuaire");
                    Draw.blood(le, 10);
                }
                if (le instanceof Player v && i % 4 == 0) v.sendActionBar(SmpGojo.mm("<dark_red><b>⚔ Tranché ⚔"));
            }
            if (i % 2 == 0) SmpGojo.sound(c.clone().add(rnd.nextDouble(-r, r), 1, rnd.nextDouble(-r, r)),
                    "entity.player.attack.sweep", 2.5f, 0.6f + rnd.nextFloat() * 0.8f);
            if (i % 20 == 0) SmpGojo.sound(c, "block.bell.resonate", 2f, 0.5f);
        }, () -> {
            if (!pl.pending.contains(close[0])) return;
            if (finish && p.isOnline()) {
                pl.cine.letterbox(vs, 30);
                pl.cine.title(vs, "", "<gradient:#ff3c00:#ffb000><b>« ⟢ Fuga ⟣ »", 25);
                SmpGojo.sound(c, "item.firecharge.use", 4f, 0.5f);
                pl.later(15, () -> {
                    pl.cine.impact(vs, null, Cine.ORANGE);
                    firestorm(p, c.clone().add(0.5, 0.5, 0.5), r * 0.75, pl.cfg("damage.fuga", 30), vs, new HashSet<>());
                    pl.later(30, close[0]);
                });
            } else {
                close[0].run();
            }
        });
    }

    /** Un temple aux piliers rouges, toit en pagode, posé sur une montagne de crânes. */
    private List<BlockState> buildShrine(Location base) {
        World w = base.getWorld();
        List<BlockState> out = new ArrayList<>();
        BlockData floor = Material.POLISHED_BLACKSTONE_BRICKS.createBlockData();
        BlockData pillar = Material.CRIMSON_HYPHAE.createBlockData();
        BlockData roof = Material.RED_NETHER_BRICKS.createBlockData();
        BlockData roofTop = Material.NETHER_WART_BLOCK.createBlockData();
        BlockData bone = Material.BONE_BLOCK.createBlockData();
        BlockData skull = Material.SKELETON_SKULL.createBlockData();
        BlockData wither = Material.WITHER_SKELETON_SKULL.createBlockData();
        BlockData blood = Material.REDSTONE_BLOCK.createBlockData();
        BlockData mouth = Material.BLACK_CONCRETE.createBlockData();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        java.util.function.BiConsumer<int[], BlockData> put = (xyz, data) -> {
            Block b = w.getBlockAt(base.getBlockX() + xyz[0], base.getBlockY() + xyz[1], base.getBlockZ() + xyz[2]);
            BlockState st = b.getState();
            st.setBlockData(data);
            out.add(st);
        };
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                int d2 = x * x + z * z;
                if (d2 > 49 || d2 <= 16) continue;
                int h = d2 < 30 ? 1 : 0;
                for (int y = -1; y < h; y++) put.accept(new int[]{x, y, z}, rnd.nextDouble() < 0.3 ? blood : bone);
                if (rnd.nextDouble() < 0.5) put.accept(new int[]{x, h, z}, rnd.nextDouble() < 0.3 ? wither : skull);
            }
        }
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) put.accept(new int[]{x, 0, z}, floor);
        for (int[] corner : new int[][]{{-4, -4}, {-4, 4}, {4, -4}, {4, 4}, {0, -4}, {0, 4}, {-4, 0}, {4, 0}}) {
            for (int y = 1; y <= 6; y++) put.accept(new int[]{corner[0], y, corner[1]}, pillar);
        }
        int[][] roofs = {{7, 5}, {6, 6}, {5, 5}, {4, 6}, {3, 5}, {2, 6}, {1, 5}};
        int y = 7;
        for (int[] layer : roofs) {
            int s = layer[0];
            BlockData mat = layer[1] == 5 ? roof : roofTop;
            for (int x = -s; x <= s; x++) for (int z = -s; z <= s; z++) {
                if (Math.abs(x) == s || Math.abs(z) == s || s <= 2) put.accept(new int[]{x, y, z}, mat);
            }
            y++;
        }
        put.accept(new int[]{0, y, 0}, pillar);
        put.accept(new int[]{0, y + 1, 0}, pillar);
        // la gueule au fond du temple
        for (int x = -2; x <= 2; x++) for (int yy = 1; yy <= 4; yy++) put.accept(new int[]{x, yy, 3}, mouth);
        for (int x = -2; x <= 2; x += 2) put.accept(new int[]{x, 1, 2}, wither);
        for (int x = -1; x <= 1; x++) put.accept(new int[]{x, 4, 2}, bone);
        return out;
    }
}
