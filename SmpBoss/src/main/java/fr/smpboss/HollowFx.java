package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
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
 * Violet Imaginaire avec le resource pack, calqué sur la vidéo de référence :
 * la boule BLEUE tourne autour du lanceur en laissant des traînées · 2,9 s : rayon rouge, la boule ROUGE reste au-dessus
 * · 13,5 s : silence, les deux boules se placent de chaque côté puis se rejoignent · 16,1 s : fusion VIOLETTE
 * · 16,5 s : écran blanc (éclats violets) et explosion qui efface tout autour.
 */
final class HollowFx extends PackFx {

    private static final String SND_CHARGE = NS + ":hollow.charge", SND_BURST = NS + ":hollow.burst";
    private static final int RED_AT = 58, ORBIT_END = 270, MERGE = 322, BOOM = 330, WHITE_END = 478;

    HollowFx(SmpBoss plugin, Ultimates ult) {
        super(plugin, ult);
    }

    private static Location orbitPoint(Location center, int k) {
        double a = k * 0.11;
        return center.clone().add(Math.cos(a) * 6.5, 1.4 + Math.sin(a * 1.5) * 1.2, Math.sin(a) * 6.5);
    }

    private static Location lerp(Location a, Location b, double u) {
        u = Math.max(0, Math.min(1, u));
        double e = u * u * (3 - 2 * u);
        return a.clone().add(b.toVector().subtract(a.toVector()).multiply(e));
    }

    /** Écran blanc avec des éclats violets qui passent (images du pack), pour ceux qui sont près. */
    private void whiteout(Location c, double radius, int frame, boolean last) {
        Component t = Component.text(String.valueOf((char) (0xe010 + (frame % 8)))).font(Key.key(NS, "fx")).color(NamedTextColor.WHITE).shadowColor(ShadowColor.shadowColor(0));
        Title title = Title.title(t, Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(last ? 100 : 250), Duration.ofMillis(last ? 900 : 0)));
        for (Player o : c.getWorld().getPlayers()) if (PackStatus.has(o) && o.getLocation().distanceSquared(c) <= radius * radius) o.showTitle(title);
    }

    void cast(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        double R = ult.cfg("hollow-purple.radius", 200);
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.hollow-purple.chant");
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        sound(base, SND_CHARGE, 250);
        fallbackSound(base, Sound.BLOCK_BEACON_ACTIVATE, 2f, 1.4f);
        // la boule bleue
        Location blueStart = orbitPoint(base, 0);
        ItemDisplay bGlow = sprite(blueStart, "glow_blue", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay bOrb = sprite(blueStart, "orb_blue", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay bHot = sprite(blueStart, "glow_white", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        later(1, () -> {
            tween(bGlow, new Vector3f(), null, new Vector3f(5, 5, 5), 4);
            tween(bOrb, new Vector3f(), null, new Vector3f(2.4f, 2.4f, 2.4f), 4);
            tween(bHot, new Vector3f(), null, new Vector3f(1.3f, 1.3f, 1.3f), 4);
        });
        pop(blueStart, "star", 3, 3, 3, 1, 1, 5, Display.Billboard.CENTER, null);
        ItemDisplay[] red = new ItemDisplay[3];
        Location[] prev = new Location[3];
        Location[] bluePos = {blueStart.clone()}, redPos = {null};
        Location[] fromB = new Location[1], fromR = new Location[1];
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            List<ItemDisplay> mine = new ArrayList<>(List.of(bGlow, bOrb, bHot));
            for (ItemDisplay d : red) if (d != null) mine.add(d);
            if (!p.isOnline() || p.isDead() || p.getWorld() != w) {
                task[0].cancel();
                ult.casting.remove(p.getUniqueId());
                for (ItemDisplay d : mine) kill(d, 1);
                stopSound(base, SND_CHARGE, 260);
                return;
            }
            Location c = p.getLocation();
            float yaw = c.getYaw();
            Vector fwd = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            Vector rt = right(fwd);
            if (!lines.isEmpty() && k % 45 == 0 && k / 45 < lines.size()) ult.chat(base, p, lines.get(k / 45));
            // ---- 1. la boule bleue tourne autour du lanceur (traînées bleues et bleu nuit)
            Location bl;
            if (k < ORBIT_END) bl = orbitPoint(c, k);
            else {
                if (fromB[0] == null) fromB[0] = bluePos[0].clone();
                Location side = c.clone().add(rt.clone().multiply(-2.4)).add(0, 2.8, 0), mid = c.clone().add(0, 2.8, 0);
                bl = k < 300 ? lerp(fromB[0], side, (k - ORBIT_END) / 16.0) : lerp(side, mid, (k - 300) / (double) (MERGE - 300));
            }
            bluePos[0] = bl;
            if (k < MERGE) {
                bGlow.teleport(bl);
                bOrb.teleport(bl);
                bHot.teleport(bl);
                if (k % 3 == 0) tween(bOrb, new Vector3f(), new Quaternionf().rotateZ(k * 0.4f), new Vector3f(2.4f, 2.4f, 2.4f), 3);
                if (k < ORBIT_END + 10) {
                    Vector dir = orbitPoint(c, k + 1).toVector().subtract(orbitPoint(c, k).toVector());
                    Vector side = right(dir.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : dir.clone().normalize());
                    Vector upv = side.clone().crossProduct(dir).normalize();
                    for (int i = 0; i < 3; i++) {
                        double a = k * 0.5 + Math.PI * 2 * i / 3;
                        Location pt = bl.clone().add(side.clone().multiply(Math.cos(a) * 0.45)).add(upv.clone().multiply(Math.sin(a) * 0.45));
                        if (prev[i] != null && prev[i].getWorld() == pt.getWorld() && prev[i].distanceSquared(pt) < 9)
                            ribbon(prev[i], pt, i == 2 ? "ribbon_navy" : "ribbon_blue", i == 2 ? 1.1f : 0.9f, 18);
                        prev[i] = pt;
                    }
                }
                fallback(bl, Particle.DUST, 8, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(60, 150, 255), 2.2f));
            }
            // ---- 2. rayon rouge, puis la boule rouge au-dessus du lanceur
            if (k == RED_AT) {
                Location beam = c.clone().add(0, 5, 0);
                pop(beam, "beam_red", 2.6f, 16, 1, 2, 5, 7, Display.Billboard.VERTICAL, null);
                pop(c.clone().add(0, 1.6, 0), "star_red", 4, 4, 4, 1, 2, 5, Display.Billboard.CENTER, null);
                Location rp = c.clone().add(rt.clone().multiply(1.4)).add(0, 3.3, 0);
                red[0] = sprite(rp, "glow_red", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
                red[1] = sprite(rp, "orb_red", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
                red[2] = sprite(rp, "glow_white", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
                later(1, () -> {
                    tween(red[0], new Vector3f(), null, new Vector3f(4.5f, 4.5f, 4.5f), 4);
                    tween(red[1], new Vector3f(), null, new Vector3f(2, 2, 2), 4);
                    tween(red[2], new Vector3f(), null, new Vector3f(1.1f, 1.1f, 1.1f), 4);
                });
                fallback(beam, Particle.DUST, 120, 0.2, 5, 0.2, 0, new Particle.DustOptions(Color.fromRGB(255, 40, 80), 2f));
                fallbackSound(c, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 1.6f);
            }
            if (k > RED_AT && k < MERGE && red[1] != null) {
                Location rp;
                Location home = c.clone().add(rt.clone().multiply(1.4)).add(0, 3.3, 0);
                if (k < ORBIT_END) rp = home;
                else {
                    if (fromR[0] == null) fromR[0] = redPos[0] == null ? home : redPos[0].clone();
                    Location side = c.clone().add(rt.clone().multiply(2.4)).add(0, 2.8, 0), mid = c.clone().add(0, 2.8, 0);
                    rp = k < 300 ? lerp(fromR[0], side, (k - ORBIT_END) / 16.0) : lerp(side, mid, (k - 300) / (double) (MERGE - 300));
                }
                redPos[0] = rp;
                for (ItemDisplay d : red) d.teleport(rp);
                if (k % 6 == 0) {
                    float s = (float) (2 + Math.sin(k * 0.5) * 0.25);
                    tween(red[1], new Vector3f(), new Quaternionf().rotateZ(-k * 0.3f), new Vector3f(s, s, s), 6);
                }
                fallback(rp, Particle.DUST, 6, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(255, 40, 70), 2f));
            }
            // ---- 3. juste avant la fusion : étincelles roses et éclairs entre les deux boules
            if (k >= 312 && k < MERGE) {
                Location mid = c.clone().add(0, 2.8, 0);
                pop(mid.clone().add(rnd(1.5), rnd(1.2), rnd(1.5)), "star_pink", 1.8f, 1.8f, 1.8f, 1, 0, 3, Display.Billboard.CENTER, null);
                if (k % 2 == 0) pop(mid.clone().add(rnd(1), rnd(0.6), rnd(1)), "bolt_" + rnd.nextInt(4), 2.5f, 2.5f, 2.5f, 1, 1, 2, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) rnd.nextDouble(6.28)));
            }
            // ---- 4. fusion : la boule violette
            if (k == MERGE) {
                for (ItemDisplay d : mine) kill(d, 1);
                Location mid = c.clone().add(0, 2.8, 0);
                sound(mid, SND_BURST, 300);
                fallbackSound(mid, Sound.ENTITY_WARDEN_SONIC_CHARGE, 3f, 1.2f);
                pop(mid, "glow_purple", 8, 8, 8, 2, 6, 2, Display.Billboard.CENTER, null);
                pop(mid, "orb_purple", 3.6f, 3.6f, 3.6f, 2, 6, 2, Display.Billboard.CENTER, null);
                for (int i = 0; i < 6; i++) pop(mid.clone().add(rnd(2), rnd(1.5), rnd(2)), i % 2 == 0 ? "star_pink" : "bolt_" + rnd.nextInt(4), 3, 3, 3, 1, 3, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) rnd.nextDouble(6.28)));
                fallback(mid, Particle.DUST, 80, 1, 1, 1, 0, new Particle.DustOptions(Color.fromRGB(200, 40, 240), 3f));
            }
            // ---- 5. l'explosion : écran blanc, tout est effacé autour
            if (k == BOOM) {
                task[0].cancel();
                ult.casting.remove(p.getUniqueId());
                ult.chat(base, p, plugin.msg("weapons.hollow-purple.final"));
                explode(p, c.clone().add(0, 2.8, 0));
            }
        }, 0L, 1L);
    }

    private void explode(Player p, Location m) {
        World w = m.getWorld();
        double R = ult.cfg("hollow-purple.radius", 200);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<LivingEntity> list = ult.targets(p, m, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) dist.put(le.getUniqueId(), le.getLocation().distance(m));
        Location below = groundBelow(m, 40);
        Location gc = below == null ? m.clone() : below;
        // ce que les joueurs loin voient (les proches ont l'écran blanc)
        flip(m, "pburst_", 16, 2, 10, 60, Display.Billboard.CENTER, 0);
        pop(m, "glow_white", 70, 70, 70, 1, 4, 14, Display.Billboard.CENTER, null);
        pop(gc.clone().add(0, 0.3, 0), "shock_purple", 90, 1, 90, 12, 0, 6, Display.Billboard.FIXED, null);
        pop(m, "shock_purple_up", 60, 60, 60, 10, 0, 6, Display.Billboard.CENTER, null);
        for (int i = 0; i < 14; i++) {
            double a = rnd.nextDouble(Math.PI * 2), el = rnd.nextDouble(-0.4, 0.9);
            Vector dir = new Vector(Math.cos(a) * Math.cos(el), Math.sin(el), Math.sin(a) * Math.cos(el));
            float s = (float) rnd.nextDouble(4, 9);
            ItemDisplay sh = sprite(m, "shard_" + rnd.nextInt(4), s, s, s, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) Math.atan2(dir.getY(), Math.hypot(dir.getX(), dir.getZ()))));
            Vector3f to = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()).mul((float) rnd.nextDouble(25, 45));
            later(1, () -> tween(sh, to, null, new Vector3f(s * 0.3f, s * 0.3f, s * 0.3f), 18));
            kill(sh, 20);
        }
        fallback(m, Particle.EXPLOSION_EMITTER, 8, 5, 3, 5, 0, null);
        fallback(m, Particle.DUST, 400, 10, 8, 10, 0, new Particle.DustOptions(Color.fromRGB(190, 60, 255), 4f));
        fallback(m, Particle.END_ROD, 300, 1, 1, 1, 1.5, null);
        fallbackSound(m, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
        fallbackSound(m, Sound.ENTITY_WARDEN_SONIC_BOOM, 4f, 0.5f);
        ult.shards(gc, 44, 26, 180);
        ult.crater(p, gc, "hollow-purple", new Material[]{Material.CRYING_OBSIDIAN, Material.AMETHYST_BLOCK, Material.OBSIDIAN}, false);
        ult.shake(m, R + 30, 8, 3);
        Set<UUID> done = new HashSet<>();
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            // l'onde frappe chacun (dégâts selon la distance)
            if (k <= 46) {
                double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    ult.zoneHit(p, le, d, DamageType.SONIC_BOOM, "hollow-purple", 0);
                }
            }
            // écran blanc avec éclats violets pour ceux qui sont près (environ 7 s)
            int len = WHITE_END - BOOM;
            if (k < len && k % 2 == 0) whiteout(m, ult.cfg("hollow-purple.whiteout-radius", 120), k / 2, k + 2 >= len);
            // éclats violets et éclairs qui retombent autour
            if (k > 10 && k < len && k % 4 == 0) {
                Location at = m.clone().add(rnd(18), rnd.nextDouble(-2, 10), rnd(18));
                pop(at, rnd.nextBoolean() ? "star_pink" : "bolt_" + rnd.nextInt(4), 4, 4, 4, 1, 1, 4, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) rnd.nextDouble(6.28)));
            }
            if (k >= len + 4) {
                task[0].cancel();
                ult.chat(m, p, plugin.msg("weapons.hollow-purple.summary").replace("<n>", String.valueOf(done.size())));
            }
        }, 0L, 1L);
    }
}
