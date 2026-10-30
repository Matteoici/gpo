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
    private static final int RED_AT = 58, ORBIT_END = 270, MERGE = 322, BOOM = 332, WHITE_END = 460, WHITE_START = 20;

    HollowFx(SmpBoss plugin, Ultimates ult) {
        super(plugin, ult);
    }

    private static Location orbitPoint(Location center, int k) {
        double a = k * 0.11;
        return center.clone().add(Math.cos(a) * 7.5, 0.9 + Math.sin(a * 1.5) * 0.9, Math.sin(a) * 7.5);
    }

    /** Devant le lanceur (à 3 blocs), un peu au-dessus ; "side" décale à gauche (-) ou à droite (+). */
    private static Location front(Location c, Vector fwd, Vector rt, double side) {
        return c.clone().add(fwd.clone().multiply(3)).add(rt.clone().multiply(side)).add(0, 2.4, 0);
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

    /** Image d'impact plein écran (police du pack), très courte. */
    private void impactFrame(Location c, double radius, char glyph, boolean last) {
        Component t = Component.text(String.valueOf(glyph)).font(Key.key(NS, "fx")).color(NamedTextColor.WHITE).shadowColor(ShadowColor.shadowColor(0));
        Title title = Title.title(t, Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(last ? 50 : 120), Duration.ofMillis(last ? 150 : 0)));
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
        // le lanceur ne bouge plus, et s'élève doucement dans les airs pendant la charge (comme sur la vidéo)
        ult.root(p);
        double liftTotal = Ultimates.headroom(p, ult.cfg("hollow-purple.lift", 8));
        // la boule bleue
        Location blueStart = orbitPoint(base, 0);
        ItemDisplay bGlow = sprite(blueStart, "glow_blue", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay bOrb = sprite(blueStart, "orb_blue", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        ItemDisplay bHot = sprite(blueStart, "glow_white", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
        later(1, () -> {
            tween(bGlow, new Vector3f(), null, new Vector3f(6.5f, 6.5f, 6.5f), 4);
            tween(bOrb, new Vector3f(), null, new Vector3f(3, 3, 3), 4);
            tween(bHot, new Vector3f(), null, new Vector3f(1.6f, 1.6f, 1.6f), 4);
        });
        pop(blueStart, "star", 3, 3, 3, 1, 1, 5, Display.Billboard.CENTER, null);
        ItemDisplay[] red = new ItemDisplay[3];
        Location[] prev = new Location[4], prevR = new Location[2];
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
                ult.unroot(p, true);
                return;
            }
            if (k >= 90 && k < 250) ult.lift(p, liftTotal / 160.0);
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
                Location side = front(c, fwd, rt, -3.2), mid = front(c, fwd, rt, 0);
                bl = k < 300 ? lerp(fromB[0], side, (k - ORBIT_END) / 16.0) : lerp(side, mid, (k - 300) / (double) (MERGE - 300));
            }
            if (k < MERGE) {
                bGlow.teleport(bl);
                bOrb.teleport(bl);
                bHot.teleport(bl);
                if (k % 3 == 0) {
                    float pulse = (float) (3 + Math.sin(k * 0.35) * 0.25);
                    tween(bOrb, new Vector3f(), new Quaternionf().rotateZ(k * 0.4f), new Vector3f(pulse, pulse, pulse), 3);
                }
                // grosses traînées bleues en coups de pinceau (bleu clair dedans, bleu nuit dehors)
                Location from = bluePos[0];
                if (from != null && from.getWorld() == bl.getWorld() && from.distanceSquared(bl) > 1e-4) {
                    Vector dir = bl.toVector().subtract(from.toVector()).normalize();
                    Vector side = right(Math.abs(dir.getY()) > 0.95 ? new Vector(1, 0, 0) : dir);
                    Vector upv = side.clone().crossProduct(dir).normalize();
                    float[] wid = {2.4f, 1.6f, 1.9f, 1.1f};
                    for (int i = 0; i < 4; i++) {
                        double a = k * 0.42 + Math.PI * 2 * i / 4;
                        double rr = i % 2 == 0 ? 0.85 : 0.5;
                        Location pt = bl.clone().add(side.clone().multiply(Math.cos(a) * rr)).add(upv.clone().multiply(Math.sin(a) * rr * 0.6));
                        if (prev[i] != null && prev[i].getWorld() == pt.getWorld() && prev[i].distanceSquared(pt) < 9)
                            ribbon(prev[i], pt, i % 2 == 0 ? "ribbon_navy" : "ribbon_blue", wid[i], k < ORBIT_END ? 26 : 12);
                        prev[i] = pt;
                    }
                }
                if (k % 5 == 0) pop(bl.clone().add(rnd(0.8), rnd(0.8), rnd(0.8)), "star", 1.6f, 1.6f, 1.6f, 1, 0, 3, Display.Billboard.CENTER, null);
                fallback(bl, Particle.DUST, 8, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(60, 150, 255), 2.2f));
            }
            bluePos[0] = bl;
            // ---- 2. rayon rouge, puis la boule rouge au-dessus du lanceur
            if (k == RED_AT) {
                Location beam = c.clone().add(0, 13, 0);
                pop(beam, "beam_red", 5, 34, 1, 2, 7, 8, Display.Billboard.VERTICAL, null);
                pop(c.clone().add(0, 1.6, 0), "star_red", 7, 7, 7, 1, 3, 6, Display.Billboard.CENTER, null);
                pop(c.clone().add(0, 0.2, 0), "shock_purple", 14, 1, 14, 6, 0, 4, Display.Billboard.FIXED, null);
                for (int i = 0; i < 8; i++) pop(c.clone().add(rnd(1.5), rnd.nextDouble(1, 10), rnd(1.5)), "star_red", 2.5f, 2.5f, 2.5f, 1, 1, 4, Display.Billboard.CENTER, null);
                w.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 2f, 0.6f);
                ult.ultQuake(c, 30, 8, 1.2f);
                Location rp = c.clone().add(rt.clone().multiply(1.6)).add(0, 3.6, 0);
                red[0] = sprite(rp, "glow_red", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
                red[1] = sprite(rp, "orb_red", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
                red[2] = sprite(rp, "glow_white", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
                later(1, () -> {
                    tween(red[0], new Vector3f(), null, new Vector3f(6, 6, 6), 4);
                    tween(red[1], new Vector3f(), null, new Vector3f(2.8f, 2.8f, 2.8f), 4);
                    tween(red[2], new Vector3f(), null, new Vector3f(1.5f, 1.5f, 1.5f), 4);
                });
                fallback(beam, Particle.DUST, 120, 0.2, 5, 0.2, 0, new Particle.DustOptions(Color.fromRGB(255, 40, 80), 2f));
                fallbackSound(c, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 1.6f);
            }
            if (k > RED_AT && k < MERGE && red[1] != null) {
                Location rp;
                Location home = c.clone().add(rt.clone().multiply(1.6)).add(0, 3.6, 0);
                if (k < ORBIT_END) rp = home;
                else {
                    if (fromR[0] == null) fromR[0] = redPos[0] == null ? home : redPos[0].clone();
                    Location side = front(c, fwd, rt, 3.2), mid = front(c, fwd, rt, 0);
                    rp = k < 300 ? lerp(fromR[0], side, (k - ORBIT_END) / 16.0) : lerp(side, mid, (k - 300) / (double) (MERGE - 300));
                }
                // traînée rouge quand elle part se placer
                if (k >= ORBIT_END && redPos[0] != null && redPos[0].distanceSquared(rp) > 1e-4) {
                    for (int i = 0; i < 2; i++) {
                        Location pt = rp.clone().add(0, i == 0 ? 0.35 : -0.35, 0);
                        if (prevR[i] != null && prevR[i].distanceSquared(pt) < 9) ribbon(prevR[i], pt, "ribbon_red", 1.4f, 12);
                        prevR[i] = pt;
                    }
                }
                redPos[0] = rp;
                for (ItemDisplay d : red) d.teleport(rp);
                if (k % 6 == 0) {
                    float s = (float) (2.8 + Math.sin(k * 0.5) * 0.3);
                    tween(red[1], new Vector3f(), new Quaternionf().rotateZ(-k * 0.3f), new Vector3f(s, s, s), 6);
                }
                fallback(rp, Particle.DUST, 6, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(255, 40, 70), 2f));
            }
            // ---- 3. juste avant la fusion : étincelles roses et éclairs entre les deux boules
            if (k >= 312 && k < MERGE) {
                Location mid = front(c, fwd, rt, 0);
                pop(mid.clone().add(rnd(1.5), rnd(1.2), rnd(1.5)), "star_pink", 1.8f, 1.8f, 1.8f, 1, 0, 3, Display.Billboard.CENTER, null);
                if (k % 2 == 0) pop(mid.clone().add(rnd(1), rnd(0.6), rnd(1)), "bolt_" + rnd.nextInt(4), 2.5f, 2.5f, 2.5f, 1, 1, 2, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) rnd.nextDouble(6.28)));
            }
            if (k == MERGE - 26) sound(front(c, fwd, rt, 0), SND_BURST, 300);
            // ---- 4. fusion : la boule violette, qui pulse jusqu'à l'explosion
            if (k == MERGE) {
                for (ItemDisplay d : mine) kill(d, 1);
                Location mid = front(c, fwd, rt, 0);
                fallbackSound(mid, Sound.ENTITY_WARDEN_SONIC_CHARGE, 3f, 1.2f);
                pop(mid, "glow_purple", 8, 8, 8, 2, 6, 2, Display.Billboard.CENTER, null);
                pop(mid, "orb_purple", 3.6f, 3.6f, 3.6f, 2, 6, 2, Display.Billboard.CENTER, null);
                for (int i = 0; i < 6; i++) pop(mid.clone().add(rnd(2), rnd(1.5), rnd(2)), i % 2 == 0 ? "star_pink" : "bolt_" + rnd.nextInt(4), 3, 3, 3, 1, 3, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) rnd.nextDouble(6.28)));
                fallback(mid, Particle.DUST, 80, 1, 1, 1, 0, new Particle.DustOptions(Color.fromRGB(200, 40, 240), 3f));
                ult.ultQuake(mid, 60, 10, 1.5f);
            }
            if (k > MERGE && k < BOOM) {
                Location mid = front(c, fwd, rt, 0);
                pop(mid.clone().add(rnd(2.5), rnd(2), rnd(2.5)), k % 2 == 0 ? "star_pink" : "spike_purple", 3, k % 2 == 0 ? 3 : 7, 3, 1, 0, 2, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) rnd.nextDouble(6.28)));
            }
            // ---- 5. l'explosion : écran blanc, tout est effacé autour
            if (k == BOOM) {
                task[0].cancel();
                ult.casting.remove(p.getUniqueId());
                ult.chat(base, p, plugin.msg("weapons.hollow-purple.final"));
                explode(p, front(c, fwd, rt, 0));
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
        // ---- le BOOM (visible pendant 1 s avant l'écran blanc, comme sur la vidéo)
        pop(m, "glow_white", 34, 34, 34, 1, 2, 6, Display.Billboard.CENTER, null);
        ItemDisplay core = sprite(m, "orb_purple", 3.6f, 3.6f, 3.6f, Display.Billboard.CENTER, null);
        later(1, () -> tween(core, new Vector3f(), null, new Vector3f(11, 11, 11), 3));
        later(6, () -> tween(core, new Vector3f(), null, new Vector3f(7, 7, 7), 6));
        later(70, () -> tween(core, new Vector3f(), null, new Vector3f(0.1f, 0.1f, 0.1f), 10));
        kill(core, 81);
        ItemDisplay glow = sprite(m, "glow_purple", 8, 8, 8, Display.Billboard.CENTER, null);
        later(1, () -> tween(glow, new Vector3f(), null, new Vector3f(30, 30, 30), 4));
        later(66, () -> tween(glow, new Vector3f(), null, new Vector3f(0.1f, 0.1f, 0.1f), 12));
        kill(glow, 79);
        flip(m, "pburst_", 16, 2, 14, 70, Display.Billboard.CENTER, 0);
        later(2, () -> pop(m, "ring_pink", 50, 50, 50, 6, 0, 5, Display.Billboard.CENTER, null));
        later(5, () -> pop(m, "ring_pink", 80, 80, 80, 8, 0, 6, Display.Billboard.CENTER, null));
        pop(gc.clone().add(0, 0.3, 0), "shock_purple", 110, 1, 110, 14, 0, 6, Display.Billboard.FIXED, null);
        pop(m, "shock_purple_up", 70, 70, 70, 10, 0, 6, Display.Billboard.CENTER, null);
        // traits de lumière qui partent du centre
        for (int i = 0; i < 18; i++) {
            float th = (float) (i * Math.PI * 2 / 18 + rnd.nextDouble(-0.15, 0.15));
            float len = (float) rnd.nextDouble(22, 42), wid = (float) rnd.nextDouble(1.6, 3.2);
            Quaternionf q = new Quaternionf().rotateZ(th);
            ItemDisplay sp = sprite(m, "spike_purple", wid, 0.1f, wid, Display.Billboard.CENTER, q);
            int delay = 1 + rnd.nextInt(3);
            later(delay, () -> tween(sp, new Vector3f(0, len / 2, 0).rotate(q), q, new Vector3f(wid, len, wid), 3));
            later(delay + 6, () -> tween(sp, new Vector3f(0, len * 0.9f, 0).rotate(q), q, new Vector3f(0.05f, len * 0.2f, 0.05f), 6));
            kill(sp, delay + 13);
        }
        // encre violette sombre qui gicle dans tous les sens
        for (int i = 0; i < 22; i++) {
            double a = rnd.nextDouble(Math.PI * 2), el = rnd.nextDouble(-0.5, 0.9);
            Vector dir = new Vector(Math.cos(a) * Math.cos(el), Math.sin(el), Math.sin(a) * Math.cos(el));
            float s0 = (float) rnd.nextDouble(3, 6), s1 = (float) rnd.nextDouble(10, 20);
            Quaternionf q = new Quaternionf().rotateZ((float) rnd.nextDouble(6.28));
            Location from = m.clone().add(dir.clone().multiply(3));
            ItemDisplay ink = sprite(from, "ink_" + rnd.nextInt(4), s0, s0, s0, Display.Billboard.CENTER, q);
            Vector3f to = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()).mul((float) rnd.nextDouble(14, 30));
            int delay = 3 + rnd.nextInt(4);
            later(delay, () -> tween(ink, to, q.rotateZ((float) rnd.nextDouble(-0.5, 0.5), new Quaternionf()), new Vector3f(s1, s1, s1), 10));
            later(delay + 12, () -> tween(ink, new Vector3f(to).mul(1.3f), q, new Vector3f(0.05f, 0.05f, 0.05f), 6));
            kill(ink, delay + 19);
        }
        for (int i = 0; i < 10; i++) {
            int dl = rnd.nextInt(14);
            later(1 + dl, () -> pop(m.clone().add(rnd(9), rnd(6), rnd(9)), "bolt_" + ThreadLocalRandom.current().nextInt(4), 7, 7, 7, 1, 1, 2, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) ThreadLocalRandom.current().nextDouble(6.28))));
        }
        for (int i = 0; i < 14; i++) {
            double a = rnd.nextDouble(Math.PI * 2), el = rnd.nextDouble(-0.4, 0.9);
            Vector dir = new Vector(Math.cos(a) * Math.cos(el), Math.sin(el), Math.sin(a) * Math.cos(el));
            float s = (float) rnd.nextDouble(4, 9);
            ItemDisplay sh = sprite(m, "shard_" + rnd.nextInt(4), s, s, s, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) Math.atan2(dir.getY(), Math.hypot(dir.getX(), dir.getZ()))));
            Vector3f to = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()).mul((float) rnd.nextDouble(25, 45));
            later(1, () -> tween(sh, to, null, new Vector3f(s * 0.3f, s * 0.3f, s * 0.3f), 18));
            kill(sh, 20);
        }
        // pour tout le monde (avec ou sans pack) : la vraie explosion, et un vrai bruit
        w.spawnParticle(Particle.EXPLOSION_EMITTER, m, 6, 3, 2, 3, 0, null, true);
        w.spawnParticle(Particle.SONIC_BOOM, m, 30, 6, 4, 6, 0, null, true);
        w.playSound(m, Sound.ENTITY_GENERIC_EXPLODE, 6f, 0.5f);
        sound(m, NS + ":weapons.impact", 300);
        w.playSound(m, Sound.ENTITY_WARDEN_SONIC_BOOM, 6f, 0.6f);
        w.playSound(m, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 6f, 0.6f);
        fallback(m, Particle.DUST, 400, 10, 8, 10, 0, new Particle.DustOptions(Color.fromRGB(190, 60, 255), 4f));
        fallback(m, Particle.END_ROD, 300, 1, 1, 1, 1.5, null);
        ult.shards(gc, 44, 26, 180);
        ult.crater(p, gc, "hollow-purple", new Material[]{Material.CRYING_OBSIDIAN, Material.AMETHYST_BLOCK, Material.OBSIDIAN}, false);
        ult.ultShake(m, R + 30, 8, 3);
        ult.ultQuake(m, R + 30, 46, 14f);
        // ---- le choc : énorme dôme violet qui s'étend, souffle qui projette tout le monde, explosions en anneaux
        ItemDisplay dome = sprite(m, "orb_purple", 3.6f, 3.6f, 3.6f, Display.Billboard.CENTER, null);
        later(1, () -> tween(dome, new Vector3f(), null, new Vector3f(70, 70, 70), 7));
        later(9, () -> tween(dome, new Vector3f(), null, new Vector3f(90, 90, 90), 10));
        later(19, () -> tween(dome, new Vector3f(), null, new Vector3f(0.5f, 0.5f, 0.5f), 6));
        kill(dome, 26);
        double blast = ult.cfg("hollow-purple.blast-radius", 70), power = ult.cfg("hollow-purple.blast-power", 3.2);
        later(2, () -> {
            for (LivingEntity le : ult.targets(p, m, blast)) {
                if (le instanceof Player pl && (pl.getGameMode() == org.bukkit.GameMode.CREATIVE || pl.getGameMode() == org.bukkit.GameMode.SPECTATOR)) continue;
                Boss king = plugin.king();
                if (king != null && king.isBoss(le)) continue;
                Vector push = le.getLocation().toVector().subtract(m.toVector());
                double d = push.length();
                if (d < 0.1) push = new Vector(0, 1, 0);
                double f = power * Math.max(0.15, 1 - d / blast);
                le.setVelocity(push.normalize().multiply(f).setY(Math.min(1.6, 0.6 + f * 0.35)));
            }
        });
        for (int ring = 1; ring <= 4; ring++) {
            int rr = ring;
            later(ring * 2, () -> {
                double rad = rr * 9;
                for (int i = 0; i < 6 + rr * 3; i++) {
                    double a = Math.PI * 2 * i / (6 + rr * 3);
                    Location at = gc.clone().add(Math.cos(a) * rad, 1, Math.sin(a) * rad);
                    w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0, null, true);
                }
                w.playSound(m, Sound.ENTITY_GENERIC_EXPLODE, 5f, (float) (0.4 + rr * 0.08));
            });
        }
        w.playSound(m, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 6f, 0.5f);
        sound(m, NS + ":boss.boom", 300);
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
            // images d'impact plein écran (noir / blanc / violet, comme dans les animes), juste au moment du choc
            if (k <= 8 && k % 2 == 0) impactFrame(m, ult.cfg("hollow-purple.whiteout-radius", 120), new char[]{'\ue020', '\ue021', '\ue023', '\ue022', '\ue021'}[k / 2], k == 8);
            // écran blanc (avec éclats violets) pour ceux qui sont dans la zone, après 1 s d'explosion visible
            if (k >= WHITE_START && k < len && k % 2 == 0) whiteout(m, ult.cfg("hollow-purple.whiteout-radius", 120), (k - WHITE_START) / 2, k + 2 >= len);
            // ceux qui sont plus loin voient l'explosion continuer (ondes, lueur, éclats)
            if (k >= WHITE_START && k < 80 && k % 10 == 0) {
                float g = 40 + (k - WHITE_START) * 0.8f;
                pop(m, "glow_purple", g, g, g, 3, 2, 8, Display.Billboard.CENTER, null);
                pop(m, "shock_purple_up", g * 1.2f, g * 1.2f, g * 1.2f, 8, 0, 4, Display.Billboard.CENTER, null);
                pop(gc.clone().add(0, 0.3, 0), "shock_purple", g * 1.8f, 1, g * 1.8f, 10, 0, 5, Display.Billboard.FIXED, null);
                for (int i = 0; i < 4; i++) {
                    double a = rnd.nextDouble(Math.PI * 2), el = rnd.nextDouble(-0.3, 0.9);
                    Vector dir = new Vector(Math.cos(a) * Math.cos(el), Math.sin(el), Math.sin(a) * Math.cos(el));
                    Quaternionf q = new Quaternionf().rotateZ((float) rnd.nextDouble(6.28));
                    ItemDisplay ink = sprite(m.clone().add(dir.clone().multiply(6)), "ink_" + rnd.nextInt(4), 4, 4, 4, Display.Billboard.CENTER, q);
                    Vector3f to = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()).mul((float) rnd.nextDouble(16, 30));
                    later(1, () -> tween(ink, to, q, new Vector3f(14, 14, 14), 12));
                    later(14, () -> tween(ink, new Vector3f(to).mul(1.2f), q, new Vector3f(0.05f, 0.05f, 0.05f), 6));
                    kill(ink, 21);
                }
                m.getWorld().spawnParticle(Particle.EXPLOSION, m, 4, 6, 4, 6, 0, null, true);
            }
            if (k == WHITE_START) ult.ultQuake(m, R * 0.7, len - WHITE_START, 2.2f); // ça tremble encore pendant l'écran blanc
            // éclats violets et éclairs qui retombent autour
            if (k > 10 && k < len && k % 4 == 0) {
                Location at = m.clone().add(rnd(18), rnd.nextDouble(-2, 10), rnd(18));
                pop(at, rnd.nextBoolean() ? "star_pink" : "bolt_" + rnd.nextInt(4), 4, 4, 4, 1, 1, 4, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) rnd.nextDouble(6.28)));
            }
            if (k == WHITE_START + 40) ult.unroot(p, true); // redescend doucement pendant l'écran blanc
            if (k >= len + 4) {
                task[0].cancel();
                ult.chat(m, p, plugin.msg("weapons.hollow-purple.summary").replace("<n>", String.valueOf(done.size())));
            }
        }, 0L, 1L);
    }
}
