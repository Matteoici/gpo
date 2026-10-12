package fr.smpgojo;

import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Techniques de Sukuna : Démantèlement, Clivage, Fuga, Taille du monde,
 * Extension du territoire : Sanctuaire Démoniaque.
 */
final class Sukuna {

    private static final Color BLOOD = Color.fromRGB(170, 0, 0), WHITE = Color.fromRGB(255, 255, 255),
            BLACK = Color.fromRGB(15, 15, 15);

    private final SmpGojo plugin;

    Sukuna(SmpGojo plugin) {
        this.plugin = plugin;
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble(path, def);
    }

    // ================================================================= Démantèlement (Kai)

    /** Trois lames invisibles qui partent en éventail et tranchent tout sur leur passage. */
    void dismantle(Player p) {
        double dmg = cfg("damage.demantelement", 6);
        SmpGojo.sound(p.getEyeLocation(), "entity.player.attack.sweep", 2f, 0.6f);
        for (int k = 0; k < 3; k++) {
            int delay = k * 3;
            double tilt = (k - 1) * 0.6 + ThreadLocalRandom.current().nextDouble(-0.2, 0.2);
            plugin.later(delay, () -> {
                Location eye = p.getEyeLocation();
                Vector d = eye.getDirection().normalize();
                Vector u = perpendicular(d, tilt);
                Location c = eye.clone().add(d.clone().multiply(1.2));
                Set<UUID> hit = new HashSet<>();
                SmpGojo.sound(c, "item.trident.throw", 1.5f, 1.8f);
                plugin.repeat(1, 12, i -> {
                    for (int s = 0; s < 2; s++) {
                        c.add(d.clone().multiply(1.2));
                        line(c, u, 1.8, BLOOD, WHITE);
                        for (LivingEntity le : c.getWorld().getNearbyLivingEntities(c, 1.8)) {
                            if (le == p || !hit.add(le.getUniqueId())) continue;
                            le.damage(dmg, p);
                            bleed(le);
                        }
                        if (plugin.getConfig().getBoolean("destroy-blocks", false)) plugin.erase(c, 0.9);
                    }
                }, null);
            });
        }
    }

    // ================================================================= Clivage (Hachi)

    /** Sur la cible visée : une rafale de coupures, plus forte sur les cibles robustes. */
    void cleave(Player p) {
        RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 10, 0.6,
                e -> e != p && e instanceof LivingEntity);
        if (r == null || !(r.getHitEntity() instanceof LivingEntity target)) {
            p.sendActionBar(SmpGojo.mm("<red>Aucune cible en vue"));
            plugin.resetCooldown(p, "clivage");
            return;
        }
        var max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double total = cfg("damage.clivage-base", 6) + cfg("damage.clivage-percent", 0.3) * (max == null ? 20 : max.getValue());
        int hits = 6;
        SmpGojo.sound(target.getLocation(), "entity.player.attack.strong", 2f, 0.5f);
        plugin.repeat(2, hits, i -> {
            if (!target.isValid() || target.isDead()) return;
            Location c = target.getLocation().add(0, target.getHeight() / 2, 0);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int s = 0; s < 3; s++) {
                Vector dir = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1));
                if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
                line(c, dir.normalize(), 1.3, BLOOD, BLACK);
            }
            target.setNoDamageTicks(0);
            target.damage(total / hits, p);
            bleed(target);
            SmpGojo.sound(c, "entity.player.attack.sweep", 1.2f, 1.2f + i * 0.1f);
        }, null);
    }

    // ================================================================= Fuga

    /** « Ouverture » : une flèche de feu qui embrase toute la zone. */
    void fuga(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 3, false, false, false));
        p.sendActionBar(SmpGojo.mm("<gradient:#ff3c00:#ffb000><b>⟢ Ouverture ⟣"));
        SmpGojo.sound(p.getLocation(), "item.firecharge.use", 2f, 0.5f);
        plugin.repeat(1, 30, i -> {
            Location eye = p.getEyeLocation();
            Vector d = eye.getDirection().normalize();
            Vector side = d.clone().crossProduct(new Vector(0, 1, 0));
            if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
            side.normalize();
            // l'arc et la flèche se forment entre ses mains
            Location hand = eye.clone().add(d.clone().multiply(1.2)).add(0, -0.3, 0);
            double len = 0.3 + i * 0.05;
            for (double t = -len; t <= len; t += 0.15) {
                p.getWorld().spawnParticle(Particle.FLAME, hand.clone().add(d.clone().multiply(t)), 1, 0, 0, 0, 0);
            }
            double a = i * 0.5;
            p.getWorld().spawnParticle(Particle.FLAME, hand.clone().add(side.clone().multiply(Math.cos(a) * 0.6)).add(0, Math.sin(a) * 0.6, 0),
                    2, 0, 0, 0, 0.01);
            if (i % 6 == 0) SmpGojo.sound(hand, "block.fire.ambient", 2f, 0.6f);
        }, () -> {
            Location eye = p.getEyeLocation();
            Vector d = eye.getDirection().normalize();
            Location arrow = eye.clone().add(d.clone().multiply(1.5));
            SmpGojo.sound(arrow, "entity.blaze.shoot", 3f, 0.4f);
            SmpGojo.sound(arrow, "item.crossbow.shoot", 3f, 0.5f);
            boolean[] done = {false};
            plugin.repeat(1, 30, i -> {
                if (done[0]) return;
                for (int s = 0; s < 3; s++) {
                    arrow.add(d.clone().multiply(0.9));
                    arrow.getWorld().spawnParticle(Particle.FLAME, arrow, 6, 0.08, 0.08, 0.08, 0.02, null, true);
                    arrow.getWorld().spawnParticle(Particle.LAVA, arrow, 1, 0, 0, 0, 0, null, true);
                    if (!arrow.getBlock().isPassable() || !arrow.getWorld().getNearbyLivingEntities(arrow, 1.2, e -> e != p).isEmpty()) {
                        done[0] = true;
                        fugaBlast(p, arrow);
                        return;
                    }
                }
            }, () -> {
                if (!done[0]) fugaBlast(p, arrow);
            });
        });
    }

    private void fugaBlast(Player p, Location at) {
        double dmg = cfg("damage.fuga", 25);
        double r = cfg("fuga.radius", 7);
        World w = at.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 2, 0.5, 0.5, 0.5, 0, null, true);
        SmpGojo.sound(at, "entity.generic.explode", 4f, 0.6f);
        SmpGojo.sound(at, "item.firecharge.use", 4f, 0.4f);
        Set<UUID> hit = new HashSet<>();
        plugin.repeat(2, 30, i -> {
            double cur = Math.min(r, r * (i + 1) / 8.0);
            // colonne et vague de feu
            w.spawnParticle(Particle.FLAME, at, 60, cur * 0.5, 1 + i * 0.25, cur * 0.5, 0.08, null, true);
            w.spawnParticle(Particle.LAVA, at, 8, cur * 0.4, 1, cur * 0.4, 0, null, true);
            w.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, i * 0.4, 0), 10, cur * 0.3, 0.5, cur * 0.3, 0.02, null, true);
            for (double a = 0; a < Math.PI * 2; a += Math.PI / 16) {
                w.spawnParticle(Particle.FLAME, at.clone().add(Math.cos(a) * cur, 0.2, Math.sin(a) * cur), 1, 0, 0.1, 0, 0.01, null, true);
            }
            for (LivingEntity le : w.getNearbyLivingEntities(at, cur)) {
                if (le == p) continue;
                le.setFireTicks(Math.max(le.getFireTicks(), 160));
                if (!hit.add(le.getUniqueId())) continue;
                le.damage(dmg, p);
                Vector push = le.getLocation().toVector().subtract(at.toVector());
                if (push.lengthSquared() < 0.01) push = new Vector(0, 1, 0);
                le.setVelocity(push.normalize().multiply(1.6).setY(0.8));
            }
            if (i % 5 == 0) SmpGojo.sound(at, "block.fire.ambient", 3f, 0.5f);
        }, null);
    }

    // ================================================================= Taille du monde

    /** L'incantation, puis une entaille géante qui coupe l'espace lui-même (traverse l'Infini). */
    void worldSlash(Player p) {
        String[] chant = {"Écaille de dragon", "Recul", "Météores jumeaux"};
        for (int k = 0; k < chant.length; k++) {
            String line = chant[k];
            boolean last = k == chant.length - 1;
            plugin.later(k * 12, () -> {
                for (Player o : p.getWorld().getPlayers()) {
                    if (o.getLocation().distanceSquared(p.getLocation()) > 80 * 80) continue;
                    o.showTitle(Title.title(SmpGojo.mm(""), SmpGojo.mm("<dark_red>« <white>" + line + "</white> »"),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(last ? 900 : 500), Duration.ofMillis(200))));
                }
                SmpGojo.sound(p.getLocation(), "block.chain.place", 2f, 0.5f + 0.2f * line.length() / 10f);
            });
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 3, false, false, false));
        plugin.later(38, () -> {
            Location eye = p.getEyeLocation();
            Vector d = eye.getDirection().normalize();
            Vector u = perpendicular(d, 0.5);
            double length = cfg("taille.length", 40), half = cfg("taille.width", 14);
            double dmg = cfg("damage.taille", 60);
            Set<UUID> hit = new HashSet<>();
            SmpGojo.sound(eye, "entity.warden.sonic_boom", 4f, 1.4f);
            SmpGojo.sound(eye, "item.trident.riptide_3", 4f, 0.5f);
            Location origin = eye.clone();
            int steps = 10;
            plugin.repeat(1, steps, i -> {
                for (int s = 0; s < 2; s++) {
                    double dist = (i * 2 + s + 1) * length / (steps * 2);
                    Location c = origin.clone().add(d.clone().multiply(dist));
                    line(c, u, half, WHITE, BLACK);
                    c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, c, 6, u.getX() * half * 0.4, u.getY() * half * 0.4,
                            u.getZ() * half * 0.4, 0, null, true);
                    for (LivingEntity le : c.getWorld().getNearbyLivingEntities(c, half)) {
                        if (le == p || hit.contains(le.getUniqueId())) continue;
                        Vector rel = le.getLocation().add(0, le.getHeight() / 2, 0).toVector().subtract(c.toVector());
                        Vector n = d.clone().crossProduct(u);
                        if (Math.abs(rel.dot(d)) > 1.6 || Math.abs(rel.dot(n)) > 1.4 || Math.abs(rel.dot(u)) > half) continue;
                        hit.add(le.getUniqueId());
                        plugin.pierce(() -> le.damage(dmg, p));
                        bleed(le);
                        le.getWorld().spawnParticle(Particle.EXPLOSION, le.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
                    }
                    if (plugin.getConfig().getBoolean("destroy-blocks", false)) {
                        for (double t = -half; t <= half; t += 1.5) plugin.erase(c.clone().add(u.clone().multiply(t)), 0.8);
                    }
                }
                if (i % 3 == 0) SmpGojo.sound(origin.clone().add(d.clone().multiply(i * length / steps)), "entity.player.attack.sweep", 3f, 0.5f);
            }, null);
        });
    }

    // ================================================================= Sanctuaire Démoniaque

    /** Pas de barrière : le sanctuaire apparaît, et tout ce qui est dans la zone est tranché sans arrêt. */
    void shrine(Player p) {
        int r = plugin.getConfig().getInt("sanctuaire.radius", 20);
        int seconds = plugin.getConfig().getInt("sanctuaire.seconds", 10);
        double dmg = cfg("damage.sanctuaire", 2);
        Location c = p.getLocation().getBlock().getLocation();
        World w = c.getWorld();

        // le sanctuaire derrière lui (seulement dans l'affichage, le monde n'est pas modifié)
        Vector back = p.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 0.01) back = new Vector(0, 0, 1);
        back.normalize().multiply(-5);
        Location base = c.clone().add(Math.round(back.getX()), 0, Math.round(back.getZ()));
        List<BlockState> fake = new ArrayList<>();
        List<Location> touched = new ArrayList<>();
        buildShrine(base, r, fake, touched);
        List<Player> viewers = new ArrayList<>();
        for (Player o : w.getPlayers()) if (o.getLocation().distanceSquared(c) < 100 * 100) viewers.add(o);
        for (Player o : viewers) {
            o.sendBlockChanges(fake);
            o.showTitle(Title.title(SmpGojo.mm("<gradient:#8b0000:#ff2a2a:#8b0000><b>EXTENSION DU TERRITOIRE"),
                    SmpGojo.mm("<dark_red>Sanctuaire Démoniaque"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
        }
        SmpGojo.sound(c, "entity.wither.spawn", 3f, 0.5f);
        SmpGojo.sound(c, "block.bell.resonate", 3f, 0.5f);
        w.spawnParticle(Particle.SCULK_SOUL, c, 60, r * 0.4, 1, r * 0.4, 0.02, null, true);

        boolean cut = plugin.getConfig().getBoolean("destroy-blocks", false);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        plugin.repeat(5, seconds * 4, i -> {
            // le ciel se couvre de coupures
            for (int s = 0; s < 6; s++) {
                Location at = c.clone().add(rnd.nextDouble(-r, r), rnd.nextDouble(0, 6), rnd.nextDouble(-r, r));
                if (at.distanceSquared(c) > r * r) continue;
                line(at, randomDir(rnd), 2.2, WHITE, BLOOD);
            }
            for (LivingEntity le : w.getNearbyLivingEntities(c, r)) {
                if (le == p) continue;
                Location l = le.getLocation().add(0, le.getHeight() / 2, 0);
                for (int s = 0; s < 3; s++) line(l, randomDir(rnd), 1.2, BLOOD, BLACK);
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                bleed(le);
                if (le instanceof Player v && i % 4 == 0) v.sendActionBar(SmpGojo.mm("<dark_red><b>⚔ Tranché ⚔"));
            }
            if (cut && i % 2 == 0) {
                Location at = c.clone().add(rnd.nextDouble(-r, r), rnd.nextDouble(-1, 4), rnd.nextDouble(-r, r));
                if (at.distanceSquared(c) <= r * r && at.distanceSquared(base) > 36) plugin.erase(at, 1.2);
            }
            if (i % 2 == 0) SmpGojo.sound(c.clone().add(rnd.nextDouble(-r, r), 1, rnd.nextDouble(-r, r)),
                    "entity.player.attack.sweep", 2f, 0.6f + rnd.nextFloat() * 0.8f);
        }, () -> {
            List<BlockState> real = new ArrayList<>();
            for (Location l : touched) real.add(l.getBlock().getState());
            for (Player o : viewers) if (o.isOnline()) o.sendBlockChanges(real);
            SmpGojo.sound(c, "block.bell.use", 3f, 0.4f);
        });
    }

    /** Un petit temple sombre aux piliers rouges, posé sur un tas de crânes. */
    private void buildShrine(Location base, int r, List<BlockState> fake, List<Location> touched) {
        World w = base.getWorld();
        BlockData floor = Material.POLISHED_BLACKSTONE_BRICKS.createBlockData();
        BlockData pillar = Material.CRIMSON_HYPHAE.createBlockData();
        BlockData roof = Material.RED_NETHER_BRICKS.createBlockData();
        BlockData roofTop = Material.NETHER_WART_BLOCK.createBlockData();
        BlockData bone = Material.BONE_BLOCK.createBlockData();
        BlockData skull = Material.SKELETON_SKULL.createBlockData();
        BlockData wither = Material.WITHER_SKELETON_SKULL.createBlockData();
        BlockData blood = Material.REDSTONE_BLOCK.createBlockData();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        java.util.function.BiConsumer<int[], BlockData> put = (xyz, data) -> {
            Block b = w.getBlockAt(base.getBlockX() + xyz[0], base.getBlockY() + xyz[1], base.getBlockZ() + xyz[2]);
            BlockState st = b.getState();
            st.setBlockData(data);
            fake.add(st);
            touched.add(b.getLocation());
        };
        // tas de crânes autour
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                int d2 = x * x + z * z;
                if (d2 > 25 || d2 <= 9) continue;
                put.accept(new int[]{x, -1, z}, rnd.nextDouble() < 0.3 ? blood : bone);
                if (rnd.nextDouble() < 0.45) put.accept(new int[]{x, 0, z}, rnd.nextDouble() < 0.3 ? wither : skull);
            }
        }
        // sol du temple
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) put.accept(new int[]{x, 0, z}, floor);
        // piliers
        for (int[] corner : new int[][]{{-3, -3}, {-3, 3}, {3, -3}, {3, 3}}) {
            for (int y = 1; y <= 5; y++) put.accept(new int[]{corner[0], y, corner[1]}, pillar);
        }
        // toit en pagode
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) put.accept(new int[]{x, 6, z}, roof);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) put.accept(new int[]{x, 7, z}, roofTop);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) put.accept(new int[]{x, 8, z}, roof);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) put.accept(new int[]{x, 9, z}, roofTop);
        put.accept(new int[]{0, 10, 0}, pillar);
        // la gueule : des crânes de wither au fond du temple
        for (int x = -1; x <= 1; x++) put.accept(new int[]{x, 1, 0}, wither);
    }

    // ================================================================= outils

    /** Un axe perpendiculaire au regard, incliné de "tilt" (radians) autour de lui. */
    private static Vector perpendicular(Vector d, double tilt) {
        Vector side = d.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
        side.normalize();
        Vector up = side.clone().crossProduct(d).normalize();
        return side.multiply(Math.cos(tilt)).add(up.multiply(Math.sin(tilt))).normalize();
    }

    private static Vector randomDir(ThreadLocalRandom rnd) {
        Vector v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1));
        return v.lengthSquared() < 0.01 ? new Vector(1, 0, 0) : v.normalize();
    }

    /** Une coupure : un trait fin dans une couleur, bordé d'une autre. */
    private static void line(Location c, Vector u, double half, Color core, Color edge) {
        Particle.DustOptions a = new Particle.DustOptions(core, 1.1f), b = new Particle.DustOptions(edge, 0.7f);
        World w = c.getWorld();
        double step = half > 4 ? 0.5 : 0.18;
        for (double t = -half; t <= half; t += step) {
            Location l = c.clone().add(u.clone().multiply(t));
            w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, a, true);
            if (Math.abs(t) < half * 0.7) w.spawnParticle(Particle.DUST, l.add(0, 0.06, 0), 1, 0.02, 0.02, 0.02, 0, b, true);
        }
    }

    private static void bleed(Entity e) {
        e.getWorld().spawnParticle(Particle.BLOCK, e.getLocation().add(0, 1, 0), 15, 0.3, 0.4, 0.3, 0,
                Material.REDSTONE_BLOCK.createBlockData());
    }
}
