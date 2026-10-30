package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fûga avec le resource pack du serveur (modèles, textures et sons "limitless:").
 * Chronologie calquée sur la vidéo de référence :
 * 0 s nuit + aura · 2,6 s explosion de feu aux pieds · cercle magique et reflets · 6,2 s la flèche part
 * · impact en flammes style anime + fumée noire · 3,2 s plus tard flash blanc, le jour revient, colonne de feu géante.
 */
final class FugaFx extends PackFx {

    private static final String SND_CHARGE = NS + ":fuga.charge", SND_SHOT = NS + ":fuga.shot", SND_IMPACT = NS + ":fuga.impact";

    FugaFx(SmpBoss plugin, Ultimates ult) {
        super(plugin, ult);
    }

    // ================================================================= 1. incantation (6,2 s)

    void cast(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        double R = ult.cfg("fuga.radius", 200);
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.fuga.chant");
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        sound(base, SND_CHARGE, 250);
        // le ciel passe à la nuit pour tous ceux qui sont autour
        Set<UUID> dark = new HashSet<>();
        for (Player o : w.getPlayers()) {
            if (o.getLocation().distanceSquared(base) > R * R) continue;
            o.setPlayerTime(18000, false);
            ult.night.add(o.getUniqueId());
            dark.add(o.getUniqueId());
        }
        flash(base, 60, WHITE, 0, 1, 8);
        fallbackSound(base, org.bukkit.Sound.ENTITY_BLAZE_AMBIENT, 2f, 0.5f);
        int release = 124;
        ult.root(p);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            if (!p.isOnline() || p.isDead() || p.getWorld() != w) {
                task[0].cancel();
                ult.casting.remove(p.getUniqueId());
                ult.unroot(p, false);
                stopSound(base, SND_CHARGE, 260);
                dayAgain(dark);
                return;
            }
            Location feet = p.getLocation();
            if (!lines.isEmpty() && k % 36 == 0 && k / 36 < lines.size()) ult.chat(base, p, lines.get(k / 36));
            // seulement des flammes : elles tournent autour de toi en montant, de plus en plus fort
            double power = Math.min(1, k / 90.0);
            int arms = 3;
            for (int a = 0; a < arms; a++) {
                double ang = k * 0.32 + Math.PI * 2 * a / arms;
                double y = (k * 0.09 + a * 0.7) % 2.6;
                double rr = 1.1 + 0.3 * Math.sin(k * 0.2 + a);
                Location at = feet.clone().add(Math.cos(ang) * rr, y + 0.1, Math.sin(ang) * rr);
                fxParticle(w, Particle.FLAME, at, 1 + (int) (power * 2), 0.05, 0.05, 0.05, 0.01);
            }
            if (k % 2 == 0) fxParticle(w, Particle.SMALL_FLAME, feet.clone().add(0, 1, 0), 2 + (int) (power * 4), 0.6, 0.8, 0.6, 0.02);
            // les mains en feu
            double yaw = Math.toRadians(feet.getYaw());
            for (int side = -1; side <= 1; side += 2) {
                Location hand = feet.clone().add(-Math.cos(yaw) * 0.42 * side - Math.sin(yaw) * 0.2, 0.9, -Math.sin(yaw) * 0.42 * side + Math.cos(yaw) * 0.2);
                fxParticle(w, Particle.FLAME, hand, 2, 0.06, 0.1, 0.06, 0.015);
            }
            // de petites flammes dessinées qui jaillissent près des mains
            if (k > 40 && k < release && k % 7 == 0) {
                double a = rnd.nextDouble(Math.PI * 2);
                flip(feet.clone().add(Math.cos(a) * 0.7, rnd.nextDouble(0.6, 1.6), Math.sin(a) * 0.7), "burst_", 16, 1, 0.5f, 1.5f, Display.Billboard.CENTER, 0.4f);
            }
            // juste avant le tir : boule de feu devant toi, l'écran du lanceur devient orange
            if (k == 116) {
                Vector dir = p.getEyeLocation().getDirection();
                flip(p.getEyeLocation().add(dir.multiply(1.6)), "burst_", 16, 1, 2, 6, Display.Billboard.CENTER, 0);
                flash(base, 8, ORANGE, 2, 3, 6);
            }
            if (k >= release) {
                task[0].cancel();
                ult.casting.remove(p.getUniqueId());
                later(10, () -> ult.unroot(p, false)); // libre une demi-seconde après le tir
                // « Fûga ! » au moment où la flèche part
                ult.chat(base, p, plugin.msg("weapons.fuga.final"));
                fallback(p.getEyeLocation(), Particle.FLAME, 80, 0.4, 0.4, 0.4, 0.25, null);
                fallbackSound(base, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection().normalize();
                fly(p, eye.clone().add(dir.clone().multiply(2)), dir, dark);
            }
        }, 0L, 1L);
    }

    // ================================================================= 2. la flèche de feu

    private void fly(Player p, Location start, Vector dir, Set<UUID> dark) {
        World w = start.getWorld();
        double range = ult.cfg("fuga.range", 140), speed = ult.cfg("fuga.speed", 3.0);
        sound(start, SND_SHOT, 250);
        Vector rt = right(dir), up = rt.clone().crossProduct(dir).normalize();
        Location pos = start.clone();
        ItemDisplay halo = sprite(pos, "glow", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay core = sprite(pos, "core", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay hot = sprite(pos, "glow_white", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        later(1, () -> {
            tween(halo, new Vector3f(), null, new Vector3f(7, 7, 7), 3);
            tween(core, new Vector3f(), null, new Vector3f(3, 3, 3), 3);
            tween(hot, new Vector3f(), null, new Vector3f(2.6f, 2.6f, 2.6f), 3);
        });
        Location[] prev = new Location[3];
        int[] t = {0};
        double[] dist = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            RayTraceResult rtb = w.rayTraceBlocks(pos, dir, speed, FluidCollisionMode.NEVER, true);
            double step = rtb == null ? speed : rtb.getHitPosition().distance(pos.toVector());
            boolean stop = rtb != null;
            Location hitAt = null;
            for (double s = 0; s <= step && hitAt == null; s += 0.5) {
                Location at = pos.clone().add(dir.clone().multiply(s));
                if (!ult.targets(p, at, 1.8).isEmpty()) hitAt = at;
            }
            if (hitAt != null) {
                pos.set(hitAt.getX(), hitAt.getY(), hitAt.getZ());
                stop = true;
            } else pos.add(dir.clone().multiply(step));
            dist[0] += step;
            halo.teleport(pos);
            core.teleport(pos);
            hot.teleport(pos);
            if (k % 3 == 0) tween(core, new Vector3f(), new Quaternionf().rotateZ(k * 0.3f), new Vector3f(3, 3, 3), 3);
            // les rubans de feu qui s'enroulent derrière
            double rad = 0.7 + Math.min(0.5, k * 0.02);
            for (int i = 0; i < 3; i++) {
                double a = k * 0.42 + Math.PI * 2 * i / 3;
                Location pt = pos.clone().add(rt.clone().multiply(Math.cos(a) * rad)).add(up.clone().multiply(Math.sin(a) * rad));
                if (prev[i] != null && prev[i].getWorld() == pt.getWorld()) ribbon(prev[i], pt, i == 2 ? "ribbon_red" : "ribbon");
                prev[i] = pt;
            }
            // traînée de flammes, étincelles, fumée
            if (k % 2 == 0) flip(pos.clone().subtract(dir.clone().multiply(1.2)), "burst_", 16, 1, 1.6f, 3.8f, Display.Billboard.CENTER, 0);
            if (k % 4 == 0) pop(pos.clone(), "star", 1.4f, 1.4f, 1.4f, 1, 0, 3, Display.Billboard.CENTER, null);
            if (k % 3 == 0) flip(pos.clone().add(rnd(0.3), rnd(0.3), rnd(0.3)), "dust_", 12, 2, 0.8f, 2.2f, Display.Billboard.CENTER, 0.4f);
            w.spawnParticle(Particle.FLAME, pos, 2, 0.3, 0.3, 0.3, 0.03, null, true);
            fallback(pos, Particle.FLAME, 14, 0.45, 0.45, 0.45, 0.04, null);
            fallback(pos, Particle.DUST, 10, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 140, 20), 2.2f));
            if (dist[0] >= range) stop = true;
            if (!p.isOnline() && k > 200) stop = true;
            if (stop) {
                task[0].cancel();
                for (ItemDisplay d : List.of(halo, core, hot)) {
                    d.remove();
                    live.remove(d);
                }
                Location c = rtb != null && hitAt == null ? pos.clone().subtract(dir.clone().multiply(0.6)) : pos.clone();
                stopSound(c, SND_SHOT, 300);
                impact(p, c, dark);
            }
        }, 1L, 1L);
    }

    // ================================================================= 3. impact, fumée noire, puis la colonne

    private void impact(Player p, Location c, Set<UUID> dark) {
        World w = c.getWorld();
        double R = ult.cfg("fuga.radius", 200), H = ult.cfg("fuga.column-height", 170);
        int burn = (int) (ult.cfg("fuga.burn-seconds", 10) * 20);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        sound(c, SND_IMPACT, 300);
        List<LivingEntity> list = ult.targets(p, c, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) dist.put(le.getUniqueId(), le.getLocation().distance(c));
        // le sol sous l'explosion (même si la flèche a explosé en l'air) : la colonne part toujours du sol
        Location below = groundBelow(c, ult.cfg("fuga.ground-search", 80));
        Location gc = below == null ? c.clone() : below;
        // l'explosion en flammes style anime, les éclaboussures au sol, les ondes
        flip(gc.clone().add(0, 6, 0), "boom_", 16, 2, 14, 30, Display.Billboard.CENTER, 0);
        for (int j = 0; j < 3; j++) {
            Location off = gc.clone().add(rnd.nextDouble(-7, 7), rnd.nextDouble(2, 5), rnd.nextDouble(-7, 7));
            later(2 + j * 3, () -> flip(off, "burst_", 16, 1, 6, 15, Display.Billboard.CENTER, 0));
        }
        flip(gc.clone().add(0, 0.15, 0), "splash_", 12, 2, 6, 36, Display.Billboard.FIXED, 0);
        pop(gc.clone().add(0, 0.3, 0), "shock", 46, 1, 46, 10, 0, 5, Display.Billboard.FIXED, null);
        pop(gc.clone().add(0, 4, 0), "shock_up", 30, 30, 30, 8, 0, 4, Display.Billboard.CENTER, null);
        pop(gc.clone().add(0, 4, 0), "glow_white", 26, 26, 26, 1, 2, 8, Display.Billboard.CENTER, null);
        later(8, () -> pop(gc.clone().add(0, 0.4, 0), "ring", 30, 1, 30, 10, 0, 6, Display.Billboard.FIXED, null));
        w.spawnParticle(Particle.BLOCK, gc.clone().add(0, 2, 0), 220, 4, 3, 4, 0.6, Material.BLACKSTONE.createBlockData(), true);
        w.spawnParticle(Particle.LAVA, gc, 60, 6, 1, 6, 0, null, true);
        fallback(gc.clone().add(0, 3, 0), Particle.EXPLOSION_EMITTER, 4, 3, 2, 3, 0, null);
        fallback(gc.clone().add(0, 3, 0), Particle.FLAME, 400, 4, 3, 4, 0.5, null);
        fallback(gc, Particle.LAVA, 80, 6, 1, 6, 0, null);
        fallbackSound(gc, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
        ult.shards(gc, 40, 20, 170);
        ult.crater(p, gc, "fuga", new Material[]{Material.MAGMA_BLOCK, Material.BLACKSTONE, Material.NETHERRACK}, true);
        ult.shake(c, R + 30, 9, 3);
        ult.quake(gc, R + 30, 34, 9f);
        Set<UUID> done = new HashSet<>();
        List<ItemDisplay> column = new ArrayList<>();
        int c0 = 64, cEnd = c0 + 86;
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            // ---- l'onde de feu frappe chacun (dégâts selon la distance)
            if (k <= 46) {
                double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    ult.zoneHit(p, le, d, DamageType.MAGIC, "fuga", burn);
                    w.spawnParticle(Particle.FLAME, le.getLocation().add(0, 1, 0), 30, 0.4, 0.7, 0.4, 0.1);
                }
            }
            // ---- fumée noire qui monte, braises au sol
            if (k >= 6 && k < c0 && k % 2 == 0) {
                Location s = gc.clone().add(rnd.nextDouble(-8, 8), rnd.nextDouble(1, 6), rnd.nextDouble(-8, 8));
                float big = (float) rnd.nextDouble(8, 14);
                flip(s, "smoke_", 12, 3, big * 0.4f, big, Display.Billboard.CENTER, 6.5f);
            }
            if (k < c0 + 6 && k % 3 == 0) w.spawnParticle(Particle.FLAME, gc, 6, 6, 0.3, 6, 0.02, null, true);
            if (k >= 6 && k < c0 && k % 4 == 0) fallback(gc.clone().add(0, 4, 0), Particle.LARGE_SMOKE, 40, 6, 3, 6, 0.03, null);
            if (k > c0 && k < cEnd && k % 2 == 0) {
                for (double y = 0; y < H; y += 3) fallback(gc.clone().add(0, y, 0), Particle.FLAME, 3, 2.5, 1.2, 2.5, 0.02, null);
                if (k % 10 == 0) fallbackSound(gc, org.bukkit.Sound.ITEM_FIRECHARGE_USE, 3f, 0.5f);
            }
            // ---- reflets rouges qui montent du cratère juste avant la colonne
            if (k >= 44 && k < c0 && k % 2 == 0) {
                double y = (k - 44) * 2.2;
                pop(gc.clone().add(rnd.nextDouble(-1, 1), y + 2, rnd.nextDouble(-1, 1)), rnd.nextInt(3) == 0 ? "star_dark" : "star_red", 2.4f, 2.4f, 2.4f, 1, 1, 3, Display.Billboard.CENTER, null);
                pop(gc.clone().add(0, y + 1, 0), "flare_red", (float) rnd.nextDouble(10, 18), 0.6f, 1, 1, 2, 4, Display.Billboard.CENTER, null);
            }
            // ---- flash blanc : le jour revient, la colonne jaillit
            if (k == c0) {
                flash(gc, 150, WHITE, 1, 2, 12);
                dayAgain(dark);
                pop(gc.clone().add(0, 10, 0), "glow_white", 60, 60, 60, 1, 2, 10, Display.Billboard.CENTER, null);
                pop(gc.clone().add(0, 0.3, 0), "shock", 40, 1, 40, 8, 0, 5, Display.Billboard.FIXED, null);
                int seg = 12;
                float h = (float) (H / seg);
                for (int i = 0; i < seg; i++) {
                    Location mid = gc.clone().add(0, -4 + h * i + h / 2, 0);
                    ItemDisplay col = sprite(mid, "column", 0.3f, h, 0.3f, Display.Billboard.FIXED, null);
                    ItemDisplay core = sprite(mid, "column_core", 0.2f, h, 0.2f, Display.Billboard.FIXED, null);
                    column.add(col);
                    column.add(core);
                    later(1, () -> {
                        tween(col, new Vector3f(), null, new Vector3f(10, h, 10), 4);
                        tween(core, new Vector3f(), null, new Vector3f(2.4f, h, 2.4f), 4);
                    });
                }
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    ItemDisplay f = sprite(gc.clone().add(Math.cos(a) * 5.5, 2.2, Math.sin(a) * 5.5), "basefire", 0.3f, 0.3f, 0.3f, Display.Billboard.VERTICAL, null);
                    column.add(f);
                    later(2, () -> tween(f, new Vector3f(), null, new Vector3f(5.5f, 5.5f, 5.5f), 5));
                }
                ult.shake(gc, R, 3, 4);
                ult.quake(gc, R, 26, 6f);
            }
            if (k == c0 + 26) ult.quake(gc, R * 0.6, cEnd - c0 - 26, 1.4f); // la colonne gronde
            if (k > c0 + 3 && k < cEnd) {
                // traits de lumière horizontaux qui traversent la colonne, étincelles
                if (k % 2 == 0) {
                    Location fl = gc.clone().add(0, rnd.nextDouble(4, H * 0.75), 0);
                    pop(fl, rnd.nextInt(3) == 0 ? "flare_red" : "flare", (float) rnd.nextDouble(20, 40), (float) rnd.nextDouble(1.2, 2.0), 1, 1, 2, 5, Display.Billboard.CENTER, null);
                }
                if (k % 3 == 0) {
                    Location st = gc.clone().add(rnd.nextDouble(-5, 5), rnd.nextDouble(3, H * 0.6), rnd.nextDouble(-5, 5));
                    pop(st, rnd.nextInt(3) == 0 ? "star_dark" : "star", 3, 3, 3, 1, 1, 4, Display.Billboard.CENTER, null);
                }
                if (k % 2 == 0) w.spawnParticle(Particle.FLAME, gc.clone().add(0, 1, 0), 10, 6, 0.6, 6, 0.06, null, true);
            }
            if (k == cEnd) {
                for (ItemDisplay d : column) {
                    Vector3f sc = d.getTransformation().getScale();
                    tween(d, new Vector3f(), null, new Vector3f(0.05f, sc.y, 0.05f), 10);
                    kill(d, 12);
                }
            }
            if (k >= cEnd + 14) {
                task[0].cancel();
                dayAgain(dark);
                ult.chat(c, p, plugin.msg("weapons.fuga.summary").replace("<n>", String.valueOf(done.size())));
            }
        }, 0L, 1L);
    }
}
