package fr.smpboss;

import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Les deux attaques finales PRESTIGE (clic droit, 30 min de recharge, rien à l'écran : tout passe par le chat des
 * joueurs autour).
 * <p>
 * - Violet Imaginaire (200 %) : incantation, Bleu et Rouge fusionnent, la sphère violette part droit devant puis
 *   implose et explose sur 200 blocs. Tout ce qui est dedans est figé (temps arrêté, ciel de nuit, givre), puis le
 *   mur violet passe sur chacun.
 * - Fûga : un arc de feu se forme, la flèche part, et à l'impact un dôme de flammes s'étend sur 200 blocs ; ceux
 *   qui sont dedans sont figés puis brûlés quand le feu les atteint.
 * <p>
 * Anti-lag : aucun bloc touché, une seule recherche d'entités par explosion, le mur de particules n'est dessiné
 * qu'auprès de chaque joueur (et envoyé à lui seul), les grandes gerbes sont des paquets uniques (count > 1).
 * Seuls les joueurs et les monstres sont touchés (pas les villageois, animaux, familiers).
 */
final class Ultimates {

    private static final Color PURPLE = Color.fromRGB(150, 60, 255), MAGENTA = Color.fromRGB(205, 95, 255),
            LILAC = Color.fromRGB(235, 215, 255), BLUE = Color.fromRGB(60, 130, 255), RED = Color.fromRGB(255, 35, 45),
            FIRE = Color.fromRGB(255, 120, 0), EMBER = Color.fromRGB(255, 50, 0), GOLD = Color.fromRGB(255, 205, 60);

    private final SmpBoss plugin;
    private final Set<UUID> casting = new HashSet<>();
    private final NamespacedKey jumpKey, gravKey;

    /** entités figées */
    private record Frozen(LivingEntity e, Location at, int end, boolean frost, boolean ai) {}
    private final Map<UUID, Frozen> frozen = new HashMap<>();
    private BukkitTask freezeTask;
    /** ciel / teinte posés pendant une attaque, à rendre ensuite */
    private final Map<UUID, WorldBorder> borders = new HashMap<>();
    private final Set<UUID> night = new HashSet<>();

    Ultimates(SmpBoss plugin) {
        this.plugin = plugin;
        this.jumpKey = new NamespacedKey(plugin, "ult_freeze_jump");
        this.gravKey = new NamespacedKey(plugin, "ult_freeze_gravity");
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("weapons." + path, def);
    }

    private static double sq(double v) {
        return v * v;
    }

    // ================================================================= lancement

    void cast(Player p, String id) {
        if (!casting.add(p.getUniqueId())) return;
        String path = Weapons.path(id);
        NamespacedKey cd = new NamespacedKey(plugin, "ult_" + id);
        long now = System.currentTimeMillis();
        Long until = p.getPersistentDataContainer().get(cd, PersistentDataType.LONG);
        if (until != null && until > now) {
            casting.remove(p.getUniqueId());
            long s = (until - now) / 1000;
            p.sendMessage(parse(plugin.msg("weapons.ult-cooldown").replace("<item>", plugin.msg("weapons." + path + ".name"))
                    .replace("<time>", (s / 60) + " min " + (s % 60) + " s")));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f);
            return;
        }
        p.getPersistentDataContainer().set(cd, PersistentDataType.LONG, now + (long) (cfg(path + ".cooldown-minutes", 30) * 60_000));
        if (Weapons.HOLLOW.equals(id)) hollow(p);
        else fuga(p);
    }

    /** Admin : remet à zéro la recharge des attaques finales d'un joueur. */
    void resetCooldown(Player p) {
        for (String id : new String[]{Weapons.HOLLOW, Weapons.FUGA}) p.getPersistentDataContainer().remove(new NamespacedKey(plugin, "ult_" + id));
    }

    /** Les lignes d'incantation, dans le chat des joueurs autour seulement. */
    private void chat(Location c, Player caster, String line) {
        double r2 = sq(cfg("ultimate-chat-radius", 250));
        var msg = parse(line.replace("<player>", SmpBoss.MM.escapeTags(caster.getName())));
        for (Player o : c.getWorld().getPlayers()) if (o.getLocation().distanceSquared(c) <= r2) o.sendMessage(msg);
    }

    /** Un son entendu de loin : joué près de chaque joueur, dans la direction de l'attaque. */
    private void boom(Location c, double r, Sound s, float vol, float pitch) {
        for (Player o : c.getWorld().getPlayers()) {
            double d2 = o.getLocation().distanceSquared(c);
            if (d2 > r * r) continue;
            if (d2 < 256) o.playSound(c, s, vol, pitch);
            else {
                Location eye = o.getEyeLocation();
                o.playSound(eye.clone().add(c.toVector().subtract(eye.toVector()).normalize().multiply(6)), s, vol, pitch);
            }
        }
    }

    /** Sphère de particules (répartition de Fibonacci). */
    private static void orb(World w, Location c, double r, int n, Particle.DustOptions d, double spin, boolean force) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i + 0.5) * 2.0 / n, rad = Math.sqrt(1 - y * y), th = golden * i + spin;
            w.spawnParticle(Particle.DUST, c.getX() + Math.cos(th) * rad * r, c.getY() + y * r, c.getZ() + Math.sin(th) * rad * r, 1, 0, 0, 0, 0, d, force);
        }
    }

    /** Joueurs et monstres dans la zone (une seule recherche). */
    private List<LivingEntity> targets(Player caster, Location c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        PumpkinKing k = plugin.king();
        for (Entity en : c.getWorld().getNearbyEntities(c, r, r, r)) {
            if (!(en instanceof LivingEntity le) || en.equals(caster) || le.isDead()) continue;
            if (en.getLocation().distanceSquared(c) > r * r) continue;
            if (en instanceof Player t) {
                if (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE || vanished(t)) continue;
            } else if (!(en instanceof Enemy) && (k == null || !k.isBoss(en))) continue;
            out.add(le);
        }
        return out;
    }

    private static Vector right(Vector dir) {
        Vector r = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (r.lengthSquared() < 1e-4) r = new Vector(1, 0, 0);
        return r.normalize();
    }

    // ================================================================= figer

    private void freeze(LivingEntity e, int ticks, boolean frost) {
        PumpkinKing k = plugin.king();
        if (k != null && k.isBoss(e)) return; // le Roi ne se fige pas
        Frozen old = frozen.get(e.getUniqueId());
        boolean ai = old != null ? old.ai() : e.hasAI();
        if (!(e instanceof Player)) e.setAI(false);
        frozen.put(e.getUniqueId(), new Frozen(e, old != null ? old.at() : e.getLocation(), Bukkit.getCurrentTick() + ticks, frost, ai));
        e.setVelocity(new Vector());
        e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks + 5, 9, false, false, false));
        e.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, ticks + 5, 4, false, false, false));
        e.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks + 5, 9, false, false, false));
        mod(e, Attribute.JUMP_STRENGTH, jumpKey, true);
        mod(e, Attribute.GRAVITY, gravKey, true);
        if (freezeTask == null) freezeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::freezeTick, 1L, 2L);
    }

    private static void mod(LivingEntity e, Attribute a, NamespacedKey key, boolean on) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst == null) return;
        inst.removeModifier(key);
        if (on) inst.addTransientModifier(new AttributeModifier(key, -1, AttributeModifier.Operation.ADD_SCALAR));
    }

    private void release(Frozen f) {
        LivingEntity e = f.e();
        mod(e, Attribute.JUMP_STRENGTH, jumpKey, false);
        mod(e, Attribute.GRAVITY, gravKey, false);
        if (!(e instanceof Player) && f.ai() && e.isValid()) e.setAI(true);
        e.removePotionEffect(PotionEffectType.SLOWNESS);
        e.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        e.removePotionEffect(PotionEffectType.WEAKNESS);
    }

    private void freezeTick() {
        int now = Bukkit.getCurrentTick();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Particle.DustOptions violet = new Particle.DustOptions(PURPLE, 1.1f);
        for (var it = frozen.values().iterator(); it.hasNext(); ) {
            Frozen f = it.next();
            LivingEntity e = f.e();
            if (!e.isValid() || e.isDead() || now >= f.end() || (e instanceof Player p && !p.isOnline())) {
                release(f);
                it.remove();
                continue;
            }
            // retenu sur place (garde où il regarde)
            Location l = e.getLocation();
            if (l.getWorld() != f.at().getWorld() || l.distanceSquared(f.at()) > 0.04) {
                Location back = f.at().clone();
                back.setYaw(l.getYaw());
                back.setPitch(l.getPitch());
                if (e instanceof Player p) Arena.allowTeleport(p);
                e.teleport(back);
            }
            e.setVelocity(new Vector());
            e.setFallDistance(0);
            if (f.frost()) e.setFreezeTicks(e.getMaxFreezeTicks());
            // petit cercle autour de la victime
            Location c = l.clone().add(0, e.getHeight() * 0.55, 0);
            double a = now * 0.25;
            for (int n = 0; n < 3; n++) {
                double b = a + Math.PI * 2 * n / 3;
                Location at = c.clone().add(Math.cos(b) * 0.8, Math.sin(b * 1.5) * 0.4, Math.sin(b) * 0.8);
                if (f.frost()) e.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, violet);
                else e.getWorld().spawnParticle(Particle.FLAME, at, 1, 0, 0, 0, 0);
            }
            if (rnd.nextInt(4) == 0) e.getWorld().spawnParticle(f.frost() ? Particle.SNOWFLAKE : Particle.SMALL_FLAME, c, 2, 0.3, 0.5, 0.3, 0.01);
        }
        if (frozen.isEmpty() && freezeTask != null) {
            freezeTask.cancel();
            freezeTask = null;
        }
    }

    /** Arrêt du serveur : on libère tout le monde. */
    void shutdown() {
        for (Frozen f : frozen.values()) release(f);
        frozen.clear();
        for (UUID id : night) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.resetPlayerTime();
        }
        night.clear();
        for (Map.Entry<UUID, WorldBorder> en : borders.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && p.getWorldBorder() == en.getValue()) p.setWorldBorder(null);
        }
        borders.clear();
    }

    // ================================================================= mur d'explosion (dessiné près de chaque joueur)

    /**
     * L'onde (une sphère de rayon r) dessinée seulement là où se trouve chaque joueur : un pan de mur de ~44 blocs
     * de large, envoyé à ce joueur seul. Rien n'est calculé là où personne ne regarde.
     */
    private void wall(Location c, double r, double maxR, ParticleBuilder a, ParticleBuilder b, Particle spark) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player o : w.getPlayers()) {
            Location pl = o.getLocation();
            double d = pl.distance(c);
            if (d > maxR + 30 || Math.abs(d - r) > 38) continue;
            double theta = Math.atan2(pl.getZ() - c.getZ(), pl.getX() - c.getX());
            int i = 0;
            for (double y = pl.getY() - 6; y <= pl.getY() + 20; y += 2.2) {
                double h = y - c.getY();
                if (Math.abs(h) >= r) continue;
                double rr = Math.sqrt(r * r - h * h);
                double half = Math.min(Math.PI, 22 / Math.max(rr, 1)), st = 2.2 / Math.max(rr, 1);
                for (double ang = theta - half; ang <= theta + half; ang += st) {
                    double x = c.getX() + Math.cos(ang) * rr, z = c.getZ() + Math.sin(ang) * rr;
                    ParticleBuilder pb = (i++ % 2 == 0) ? a : b;
                    pb.receivers(o).location(w, x, y + rnd.nextDouble(-0.6, 0.6), z).spawn();
                    if (spark != null && i % 9 == 0) new ParticleBuilder(spark).receivers(o).location(w, x, y, z).count(1).extra(0.02).force(true).spawn();
                }
            }
        }
    }

    private static ParticleBuilder dust(Color c, float size) {
        return new ParticleBuilder(Particle.DUST).count(1).offset(0.25, 0.35, 0.25).extra(0).data(new Particle.DustOptions(c, size)).force(true);
    }

    // ================================================================= effets communs

    /** Vanish (EssentialsX, SuperVanish, PremiumVanish...) : jamais touché. */
    static boolean vanished(Player p) {
        for (org.bukkit.metadata.MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return true;
        return false;
    }

    private static Vector look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), pt = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(pt), -Math.sin(pt), Math.cos(y) * Math.cos(pt));
    }

    private final Map<UUID, float[]> shakeOff = new HashMap<>();

    /**
     * Tremblement d'écran (seulement aux impacts, jamais pendant l'incantation) : petits à-coups de la vue qui
     * s'estompent + secousse "coup reçu". Plus fort près du centre.
     */
    private void shake(Location c, double radius, int ticks, double power) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            boolean last = k >= ticks;
            double fade = Math.pow(1 - (double) k / ticks, 1.6);
            for (Player o : w.getPlayers()) {
                if (o.getLocation().distanceSquared(c) > radius * radius) continue;
                double d = o.getLocation().distance(c);
                float[] off = shakeOff.get(o.getUniqueId());
                double amp = last ? 0 : power * fade * (1 - 0.65 * d / radius);
                if (off == null && amp < 0.2) continue;
                float by = o.getYaw() - (off == null ? 0 : off[0]), bp = o.getPitch() - (off == null ? 0 : off[1]);
                float oy = amp < 0.2 ? 0 : (float) rnd.nextDouble(-amp, amp), op = amp < 0.2 ? 0 : (float) rnd.nextDouble(-amp * 0.75, amp * 0.75);
                float np = Math.max(-89, Math.min(89, bp + op));
                double ry = Math.toRadians(by + oy), rp = Math.toRadians(np);
                Location eye = o.getEyeLocation();
                o.lookAt(eye.getX() - Math.sin(ry) * Math.cos(rp) * 8, eye.getY() - Math.sin(rp) * 8, eye.getZ() + Math.cos(ry) * Math.cos(rp) * 8,
                        io.papermc.paper.entity.LookAnchor.EYES);
                if (amp < 0.2) shakeOff.remove(o.getUniqueId());
                else shakeOff.put(o.getUniqueId(), new float[]{oy, np - bp});
                if (amp > 1.6 && k % 4 == 0) o.playHurtAnimation((float) rnd.nextDouble(360));
            }
            if (last) task[0].cancel();
        }, 0L, 1L);
    }

    /** Onde au sol qui s'étend (anneau), visible de loin. */
    private void groundWave(Location c, double maxR, int ticks, Particle p, Particle.DustOptions d) {
        World w = c.getWorld();
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            if (k > ticks) {
                task[0].cancel();
                return;
            }
            double u = (double) k / ticks, r = maxR * (1 - Math.pow(1 - u, 2));
            int n = (int) Math.min(220, 24 + r * 5);
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                Location at = c.clone().add(Math.cos(a) * r, 0.25, Math.sin(a) * r);
                if (p != null && i % 2 == 0) w.spawnParticle(p, at, 1, 0.1, 0.15, 0.1, 0.02, null, true);
                if (d != null) w.spawnParticle(Particle.DUST, at.clone().add(0, 0.3, 0), 1, 0.15, 0.2, 0.15, 0, d, true);
            }
            if (k % 3 == 0) w.spawnParticle(Particle.CLOUD, c, 30, r * 0.7, 0.2, r * 0.7, 0.05, null, true);
        }, 0L, 1L);
    }

    /** Volutes colorées sur les bords de l'écran (gauche / droite) pendant l'écran blanc. */
    private static void edges(Player o, Color a, Color b, int k) {
        Location eye = o.getEyeLocation();
        Vector f = eye.getDirection(), rt = right(f), up = rt.clone().crossProduct(f).normalize();
        Location c = eye.clone().add(f.clone().multiply(1.1));
        ParticleBuilder pa = new ParticleBuilder(Particle.DUST).receivers(o).count(1).extra(0).force(true).data(new Particle.DustOptions(a, 2.6f));
        ParticleBuilder pb = new ParticleBuilder(Particle.DUST).receivers(o).count(1).extra(0).force(true).data(new Particle.DustOptions(b, 2.2f));
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 9; i++) {
                double th = k * 0.15 + i * 0.35 + (side > 0 ? Math.PI : 0);
                Location at = c.clone().add(rt.clone().multiply(side * (1.35 + 0.15 * Math.sin(th * 2)))).add(up.clone().multiply(Math.sin(th) * 0.6));
                (i % 2 == 0 ? pa : pb).location(at).spawn();
            }
        }
    }

    /** Écran envahi (blanc, feu...) : un nuage de particules juste devant les yeux, pour ce joueur seulement. */
    private static void screen(Player o, Color a, Color b, int n, float size) {
        Location at = o.getEyeLocation().add(o.getEyeLocation().getDirection().multiply(1.1));
        new ParticleBuilder(Particle.DUST).receivers(o).location(at).count(n).offset(0.9, 0.55, 0.9).extra(0)
                .data(new Particle.DustOptions(a, size)).force(true).spawn();
        if (b != null) new ParticleBuilder(Particle.DUST).receivers(o).location(at).count(Math.max(1, n / 3)).offset(0.9, 0.55, 0.9).extra(0)
                .data(new Particle.DustOptions(b, size)).force(true).spawn();
    }

    /** Éclair violet / noir en zigzag devant les yeux (flash du Violet Imaginaire). */
    private static void streak(Player o, Color c, float size) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location eye = o.getEyeLocation();
        Vector f = eye.getDirection(), rt = right(f), up = rt.clone().crossProduct(f).normalize();
        Location p0 = eye.clone().add(f.clone().multiply(1.3)).add(rt.clone().multiply(rnd.nextDouble(-1.2, 1.2))).add(up.clone().multiply(rnd.nextDouble(-0.7, 0.7)));
        Vector dir = rt.clone().multiply(rnd.nextDouble(-1, 1)).add(up.clone().multiply(rnd.nextDouble(-1, 1))).normalize();
        ParticleBuilder pb = new ParticleBuilder(Particle.DUST).receivers(o).count(1).offset(0, 0, 0).extra(0).data(new Particle.DustOptions(c, size)).force(true);
        Location at = p0.clone();
        for (int i = 0; i < 18; i++) {
            at.add(dir.clone().multiply(0.09)).add(rt.clone().multiply(rnd.nextDouble(-0.05, 0.05))).add(up.clone().multiply(rnd.nextDouble(-0.05, 0.05)));
            pb.location(at).spawn();
            if (i % 6 == 5) dir.add(rt.clone().multiply(rnd.nextDouble(-0.8, 0.8))).normalize();
        }
    }

    /** Éclat de lumière (lens flare) : une longue ligne horizontale + une courte verticale. */
    private static void flare(World w, Location at, Vector side, double len, Particle.DustOptions d, Particle.DustOptions core) {
        Vector s = side.clone().normalize();
        for (double x = -len; x <= len; x += 0.25) {
            double f = 1 - Math.abs(x) / len;
            w.spawnParticle(Particle.DUST, at.clone().add(s.clone().multiply(x)), 1, 0, 0, 0, 0, f > 0.7 ? core : d, true);
        }
        for (double y = -len * 0.3; y <= len * 0.3; y += 0.2) w.spawnParticle(Particle.DUST, at.clone().add(0, y, 0), 1, 0, 0, 0, 0, d, true);
        w.spawnParticle(Particle.END_ROD, at, 3, 0.05, 0.05, 0.05, 0.02, null, true);
    }

    private static void ring(World w, Location c, double r, int n, Vector a, Vector b, Particle.DustOptions d, double spin, boolean force) {
        for (int i = 0; i < n; i++) {
            double t = Math.PI * 2 * i / n + spin;
            w.spawnParticle(Particle.DUST, c.clone().add(a.clone().multiply(Math.cos(t) * r)).add(b.clone().multiply(Math.sin(t) * r)), 1, 0, 0, 0, 0, d, force);
        }
    }

    /** Le sol sous (ou un peu au-dessus de) ce point. */
    private static Location ground(Location l) {
        Location c = l.clone();
        c.setY(Math.floor(c.getY()) + 4);
        for (int i = 0; i < 16; i++) {
            if (c.getBlock().getType().isSolid()) return c.add(0, 1, 0);
            c.add(0, -1, 0);
        }
        return null;
    }

    private static Transformation centered(Quaternionf q, float sx, float sy, float sz, boolean centerY) {
        Vector3f c = new Vector3f(sx / 2, centerY ? sy / 2 : 0, sz / 2).rotate(q);
        return new Transformation(c.negate(), q, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    private BlockDisplay block(Location at, BlockData data, Transformation tr, float view, float box) {
        return at.getWorld().spawn(at, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setBlock(data);
            d.setTransformation(tr);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(view);
            d.setDisplayWidth(box);
            d.setDisplayHeight(box);
        });
    }

    private static void animate(BlockDisplay d, Transformation tr, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(tr);
    }

    /**
     * Colonne géante (blocs translucides agrandis : une seule entité par couche, aucun lag) qui monte, tourne,
     * puis s'amincit et disparaît. Visible de très loin.
     */
    private void pillar(Location c, Material[] mats, float[] widths, float height, int grow, int hold, int fade) {
        List<BlockDisplay> ds = new ArrayList<>();
        for (int i = 0; i < mats.length; i++) {
            Quaternionf q = new Quaternionf().rotateY((float) (i * Math.PI / 4));
            ds.add(block(c, mats[i].createBlockData(), centered(q, widths[i], 0.01f, widths[i], false), 8f, height));
        }
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            for (int i = 0; i < ds.size(); i++) {
                BlockDisplay d = ds.get(i);
                float rot = (float) (i * Math.PI / 4 + (i % 2 == 0 ? 1 : -1) * k * 0.06);
                Quaternionf q = new Quaternionf().rotateY(rot);
                if (k == 1) animate(d, centered(q, widths[i], height, widths[i], false), grow);
                else if (k > grow && k < grow + hold && k % 10 == 0) animate(d, centered(q, widths[i], height, widths[i], false), 10);
                else if (k == grow + hold) animate(d, centered(q, 0.01f, height * 1.1f, 0.01f, false), fade);
            }
            if (k >= grow + hold + fade + 1) {
                for (BlockDisplay d : ds) d.remove();
                task[0].cancel();
            }
        }, 1L, 1L);
    }

    /** Éclats de roche sombres qui sortent du sol autour du cratère (terrain "ravagé", rien n'est cassé). */
    private void shards(Location c, int n, double radius, int hold) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Material[] mats = {Material.BLACKSTONE, Material.BASALT, Material.COBBLED_DEEPSLATE, Material.POLISHED_BLACKSTONE, Material.MAGMA_BLOCK};
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble(Math.PI * 2), d = rnd.nextDouble(3, radius);
            Location g = ground(c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            if (g == null) continue;
            float sx = (float) rnd.nextDouble(1.2, 3.2), sy = (float) rnd.nextDouble(1, 3.5), sz = (float) rnd.nextDouble(1.2, 3);
            Quaternionf q = new Quaternionf().rotateY((float) rnd.nextDouble(Math.PI * 2)).rotateX((float) rnd.nextDouble(-0.6, 0.6)).rotateZ((float) rnd.nextDouble(-0.6, 0.6));
            Location below = g.clone().add(0, -sy, 0);
            BlockDisplay sh = block(below, mats[rnd.nextInt(mats.length)].createBlockData(), centered(q, sx, sy, sz, false), 4f, 4f);
            sh.setBrightness(null);
            int delay = rnd.nextInt(8);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!sh.isValid()) return;
                sh.setTeleportDuration(4);
                sh.teleport(g.clone().add(0, -sy * 0.45, 0));
                g.getWorld().spawnParticle(Particle.BLOCK, g, 15, 0.6, 0.2, 0.6, 0, sh.getBlock(), true);
            }, 2L + delay);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!sh.isValid()) return;
                sh.setTeleportDuration(20);
                sh.teleport(g.clone().add(0, -sy - 0.5, 0));
            }, 2L + delay + hold);
            Bukkit.getScheduler().runTaskLater(plugin, sh::remove, 25L + delay + hold);
        }
    }


    // ================================================================= VIOLET IMAGINAIRE (comme la vidéo)

    /*
     * 1. Le Bleu se forme dans la main gauche : nuage bleu sombre qui tourbillonne, lueur violette au sol.
     * 2. Le Bleu part et tourne tout autour du lanceur, au ras du sol : longue traînée bleue avec des traits noirs.
     * 3. Un faisceau rouge et blanc jaillit du lanceur (étincelles), le Rouge apparaît au-dessus de lui.
     * 4. Le lanceur saute, le Bleu revient : Bleu à gauche, Rouge à droite, au-dessus de sa tête.
     * 5. Ils se rapprochent et fusionnent : cœur magenta, pics de lumière blancs, éclats violets et noirs projetés.
     * 6. L'explosion part du lanceur : TOUT DEVIENT BLANC pour tous ceux dans les 200 blocs (brume rose/violette),
     *    ils sont figés, l'onde les frappe, puis le blanc s'efface.
     * Rien devant les yeux du lanceur pendant l'incantation : tout se passe autour de lui.
     */
    private void hollow(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.hollow-purple.chant");
        int merge = 118;
        Particle.DustOptions blue = new Particle.DustOptions(BLUE, 1.9f), deep = new Particle.DustOptions(Color.fromRGB(25, 45, 190), 2.6f),
                ink = new Particle.DustOptions(Color.fromRGB(8, 4, 20), 1.8f), red = new Particle.DustOptions(RED, 1.9f),
                redHot = new Particle.DustOptions(Color.fromRGB(255, 140, 150), 1.3f), purple = new Particle.DustOptions(PURPLE, 2.2f),
                magenta = new Particle.DustOptions(Color.fromRGB(240, 40, 220), 2.4f), glowP = new Particle.DustOptions(Color.fromRGB(110, 60, 220), 1.4f),
                white = new Particle.DustOptions(Color.WHITE, 1.8f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Location> trail = new ArrayList<>();
        Location[] last = new Location[1];
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            if (!p.isOnline() || p.isDead() || p.getWorld() != w) {
                casting.remove(p.getUniqueId());
                task[0].cancel();
                return;
            }
            Location feet = p.getLocation(), eye = p.getEyeLocation();
            Vector fwd = feet.getDirection().setY(0);
            if (fwd.lengthSquared() < 1e-4) fwd = new Vector(0, 0, 1);
            fwd.normalize();
            Vector rt = right(fwd);
            if (!lines.isEmpty() && k % 18 == 0 && k / 18 < lines.size()) {
                chat(base, p, lines.get(k / 18));
                boom(base, 64, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.6f, 0.5f + 0.1f * (k / 18));
            }
            // ---- positions
            Location head = feet.clone().add(0, 3.3, 0);
            Location bluePos;
            if (k < 22) bluePos = feet.clone().add(rt.clone().multiply(-0.7)).add(fwd.clone().multiply(0.3)).add(0, 1.0, 0);
            else if (k < 84) {
                double a = (k - 22) * 0.2 + Math.atan2(-rt.getZ(), -rt.getX()), rad = Math.min(11, 1 + (k - 22) * 0.6);
                bluePos = base.clone().add(Math.cos(a) * rad, 0.7 + 0.8 * Math.sin(k * 0.13), Math.sin(a) * rad);
                last[0] = bluePos.clone();
            } else {
                double s = Math.min(1, (k - 84) / 16.0);
                s = s * s * (3 - 2 * s);
                double spread = k < 100 ? 1.7 : 1.7 * (1 - Math.min(1, (k - 100) / (double) (merge - 100)));
                Location target = head.clone().add(rt.clone().multiply(-spread));
                bluePos = last[0].clone().add(target.toVector().subtract(last[0].toVector()).multiply(s));
            }
            double rSpread = k < 100 ? 1.7 : 1.7 * (1 - Math.min(1, (k - 100) / (double) (merge - 100)));
            Location redPos = k < 84 ? head.clone().add(rt.clone().multiply(0.6)) : head.clone().add(rt.clone().multiply(rSpread));
            // ---- 1. le Bleu dans la main : nuage bleu qui tourbillonne, lueur violette au sol
            if (k < 22) {
                double g = Math.min(1, k / 14.0);
                orb(w, bluePos, 0.2 + g * 0.35, 20, blue, k * 0.5, false);
                for (int n = 0; n < 6; n++) {
                    double a = k * 0.5 + n * Math.PI / 3, rr = 0.5 + g * 0.6;
                    w.spawnParticle(Particle.DUST, bluePos.clone().add(Math.cos(a) * rr, Math.sin(a * 1.7) * 0.4, Math.sin(a) * rr), 1, 0.05, 0.05, 0.05, 0, n % 2 == 0 ? deep : ink);
                }
                ring(w, feet.clone().add(0, 0.06, 0), 1.2 + g, 26, new Vector(1, 0, 0), new Vector(0, 0, 1), glowP, k * 0.1, false);
                w.spawnParticle(Particle.DUST, feet.clone().add(0, 0.1, 0), 4, 1.2 * g, 0, 1.2 * g, 0, glowP);
                if (k == 0) {
                    boom(base, 96, Sound.BLOCK_CONDUIT_ACTIVATE, 2f, 0.6f);
                    boom(base, 96, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 1.8f);
                }
            }
            // ---- 2. le Bleu fait le tour au ras du sol : traînée bleue + traits noirs
            if (k >= 22 && k < merge) {
                double rb = k < 84 ? 0.6 : 0.75;
                orb(w, bluePos, rb, 30, blue, k * 0.6, true);
                orb(w, bluePos, rb * 0.45, 8, white, -k * 0.6, true);
                w.spawnParticle(Particle.GLOW, bluePos, 2, rb, rb, rb, 0.02, null, true);
                trail.add(0, bluePos.clone());
                while (trail.size() > (k < 84 ? 46 : 18)) trail.remove(trail.size() - 1);
                for (int i = 1; i < trail.size(); i++) {
                    Location l = trail.get(i);
                    float f = 1 - (float) i / trail.size();
                    w.spawnParticle(Particle.DUST, l, 2, 0.25 + f * 0.35, 0.15 + f * 0.2, 0.25 + f * 0.35, 0, i < 10 ? blue : deep, true);
                    if (i % 2 == 0) {
                        Location s = l.clone().add(rnd.nextDouble(-0.9, 0.9) * f, rnd.nextDouble(-0.4, 0.4), rnd.nextDouble(-0.9, 0.9) * f);
                        w.spawnParticle(Particle.DUST, s, 1, 0, 0, 0, 0, ink, true);
                    }
                }
                if (k < 84) {
                    Location g = ground(bluePos);
                    if (g != null && bluePos.getY() - g.getY() < 2.5) w.spawnParticle(Particle.CLOUD, g, 2, 0.4, 0.02, 0.4, 0.03, null, true);
                    if (k % 8 == 0) w.playSound(bluePos, Sound.ENTITY_PHANTOM_SWOOP, 1.6f, 0.6f);
                    if (k % 12 == 0) w.playSound(bluePos, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, 1.4f, 0.7f);
                }
            }
            // ---- 3. faisceau rouge et blanc, le Rouge apparaît au-dessus du lanceur
            if (k == 38) {
                pillar(feet, new Material[]{Material.RED_STAINED_GLASS, Material.PINK_STAINED_GLASS, Material.WHITE_STAINED_GLASS},
                        new float[]{2.6f, 1.6f, 0.5f}, 40, 3, 22, 8);
                w.spawnParticle(Particle.END_ROD, feet.clone().add(0, 2, 0), 80, 0.6, 2.5, 0.6, 0.25, null, true);
                w.spawnParticle(Particle.DUST, feet.clone().add(0, 3, 0), 120, 1.2, 4, 1.2, 0, red, true);
                boom(base, 160, Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.7f);
                boom(base, 160, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2f, 0.6f);
                boom(base, 160, Sound.ITEM_TRIDENT_THUNDER, 1.4f, 1.4f);
            }
            if (k >= 38 && k < 62 && k % 2 == 0) {
                for (int n = 0; n < 6; n++) {
                    Vector v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(0, 1.5), rnd.nextDouble(-1, 1)).normalize();
                    w.spawnParticle(Particle.END_ROD, feet.clone().add(0, rnd.nextDouble(0.5, 6), 0), 0, v.getX(), v.getY(), v.getZ(), 0.3, null, true);
                }
                w.spawnParticle(Particle.DUST, feet.clone().add(0, 4, 0), 10, 0.8, 3, 0.8, 0, redHot, true);
            }
            if (k >= 40 && k < merge) {
                double rr = Math.min(0.75, 0.2 + (k - 40) * 0.03);
                orb(w, redPos, rr, 30, red, -k * 0.5, true);
                orb(w, redPos, rr * 0.45, 8, redHot, k * 0.6, true);
                if (k % 7 == 0) ring(w, redPos, rr + 0.4, 18, rt, new Vector(0, 1, 0), red, 0, true);
            }
            // ---- 4. le lanceur saute, le Bleu revient
            if (k == 80) {
                p.setVelocity(new Vector(0, 1.05, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, false, false, false));
                boom(base, 96, Sound.ENTITY_BREEZE_JUMP, 1.6f, 0.7f);
            }
            if (k >= 92 && k < merge && k % 2 == 0) {
                // crépitements entre les deux
                Vector seg = redPos.toVector().subtract(bluePos.toVector());
                double len = seg.length();
                for (double s = 0; s <= len; s += 0.25) {
                    Location at = bluePos.clone().add(seg.clone().multiply(s / Math.max(len, 0.01))).add(rnd.nextDouble(-0.2, 0.2), rnd.nextDouble(-0.2, 0.2), rnd.nextDouble(-0.2, 0.2));
                    w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, rnd.nextBoolean() ? purple : white, true);
                }
                if (k == 100) boom(base, 160, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 0.5f);
            }
            // ---- 5. fusion : cœur magenta, pics blancs, éclats violets et noirs
            if (k >= merge) {
                Location m = head.clone();
                int e = k - merge;
                orb(w, m, 0.6 + e * 0.25, 40, magenta, k * 0.4, true);
                orb(w, m, 0.4 + e * 0.1, 14, white, -k * 0.5, true);
                for (int ray = 0; ray < 14; ray++) {
                    double a = ray * 2.39996 + e * 0.05, b = Math.acos(1 - 2 * ((ray + 0.5) / 14));
                    Vector v = new Vector(Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a));
                    double len = 3 + e * 1.4 + (ray % 3) * 1.5;
                    for (double s = 0.8; s < len; s += 0.35) w.spawnParticle(Particle.DUST, m.clone().add(v.clone().multiply(s)), 1, 0, 0, 0, 0, s < len * 0.6 ? white : glowP, true);
                }
                for (int sh = 0; sh < 16; sh++) {
                    double a = sh * 2.39996, b = Math.acos(1 - 2 * ((sh + 0.5) / 16));
                    Vector v = new Vector(Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a));
                    double d0 = 2 + e * 2.2;
                    Location s0 = m.clone().add(v.clone().multiply(d0));
                    Vector side = right(v).multiply(0.35);
                    for (int j = 0; j < 7; j++) {
                        Location at = s0.clone().add(v.clone().multiply(j * 0.35)).add(side.clone().multiply(j % 2 == 0 ? 1 : -1));
                        w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, sh % 3 == 0 ? ink : purple, true);
                    }
                }
                // anneaux de lumière qui s'ouvrent autour du point de fusion
                ring(w, m, 2 + e * 3.2, 60, new Vector(1, 0, 0), new Vector(0, 0, 1), white, 0, true);
                ring(w, m, 1.5 + e * 2.4, 50, rt, new Vector(0, 1, 0), magenta, e * 0.3, true);
                w.spawnParticle(Particle.END_ROD, m, 40, 0.3, 0.3, 0.3, 0.5 + e * 0.1, null, true);
                w.spawnParticle(Particle.ELECTRIC_SPARK, m, 30, 2 + e, 2 + e, 2 + e, 0.3, null, true);
                if (e == 0) {
                    boom(base, 260, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 0.6f);
                    boom(base, 260, Sound.BLOCK_END_PORTAL_SPAWN, 2f, 1.2f);
                    boom(base, 260, Sound.ITEM_TRIDENT_THUNDER, 2f, 0.7f);
                    boom(base, 260, Sound.BLOCK_BEACON_POWER_SELECT, 2f, 0.5f);
                    chat(base, p, plugin.msg("weapons.hollow-purple.final"));
                    shake(m, 60, 12, 3.5);
                }
                if (e >= 6) {
                    task[0].cancel();
                    casting.remove(p.getUniqueId());
                    hollowBurst(p, m);
                }
            }
        }, 0L, 1L);
    }

    /** 6. L'explosion part du lanceur : tout devient blanc, l'onde balaie 200 blocs. */
    private void hollowBurst(Player p, Location c) {
        World w = c.getWorld();
        double R = cfg("hollow-purple.radius", 200), dmg = cfg("hollow-purple.damage", 60), core = cfg("hollow-purple.core-radius", 30);
        List<LivingEntity> list = targets(p, c, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) {
            dist.put(le.getUniqueId(), le.getLocation().distance(c));
            freeze(le, 120, true);
        }
        Set<UUID> done = new HashSet<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boom(c, R + 60, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_WARDEN_SONIC_BOOM, 4f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_WITHER_DEATH, 1.2f, 0.7f);
        boom(c, R + 60, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.5f);
        w.spawnParticle(Particle.END_ROD, c, 700, 0.5, 0.5, 0.5, 2.2, null, true);
        w.spawnParticle(Particle.DUST, c, 500, 12, 10, 12, 0, new Particle.DustOptions(Color.WHITE, 4f), true);
        w.spawnParticle(Particle.DUST, c, 200, 12, 10, 12, 0, new Particle.DustOptions(MAGENTA, 4f), true);
        Color haze = Color.fromRGB(250, 228, 252), pink = Color.fromRGB(230, 170, 245), lav = Color.fromRGB(200, 180, 255);
        Particle.DustOptions purpleD = new Particle.DustOptions(PURPLE, 3.2f), lilacD = new Particle.DustOptions(LILAC, 2.4f),
                inkD = new Particle.DustOptions(Color.fromRGB(12, 0, 28), 2.4f);
        shake(c, R + 30, 70, 8.5);
        groundWave(ground(c) == null ? c : ground(c), 60, 22, Particle.END_ROD, new Particle.DustOptions(MAGENTA, 3f));
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
            int white = 120;
            // grondements en cascade
            if (k == 3 || k == 9 || k == 16 || k == 26 || k == 40) {
                boom(c, R + 60, k % 2 == 0 ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 3f, 0.45f + k * 0.004f);
                boom(c, R + 60, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.5f, 0.5f + k * 0.005f);
                if (k == 9) boom(c, R + 60, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.6f);
                if (k == 26) shake(c, R, 30, 4.5);
            }
            for (Player o : w.getPlayers()) {
                double d = o.getLocation().distance(c);
                if (d > R) continue;
                if (k < white && d <= r + 12) {
                    // écran blanc, éclairs violets et noirs, volutes roses / violettes sur les bords
                    int n = k < 12 ? 90 : k < white - 25 ? 65 : Math.max(4, 65 - (k - (white - 25)) * 2);
                    screen(o, Color.WHITE, k % 3 == 0 ? pink : (k % 3 == 1 ? haze : lav), n, 4f);
                    if (k < 40 && k % 2 == 0) streak(o, rnd.nextInt(3) == 0 ? Color.fromRGB(10, 0, 25) : PURPLE, 1.8f);
                    if (k < 40 && k % 3 == 1) streak(o, MAGENTA, 1.4f);
                    if (k > 6 && k < white - 10) edges(o, k % 2 == 0 ? PURPLE : Color.fromRGB(230, 120, 245), pink, k);
                    if (k == 0 || (k < 6 && d <= r)) o.playSound(o.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
                } else if (k >= white && k < white + 60 && k % 3 == 0) {
                    // l'écran revient : brume et éclats violets qui retombent autour de chacun
                    new ParticleBuilder(Particle.REVERSE_PORTAL).receivers(o).location(o.getLocation().add(0, 2, 0)).count(30).offset(8, 3, 8).extra(0.04).spawn();
                    new ParticleBuilder(Particle.DUST).receivers(o).location(o.getLocation().add(0, 3, 0)).count(12).offset(8, 3, 8).extra(0).data(lilacD).spawn();
                    new ParticleBuilder(Particle.END_ROD).receivers(o).location(o.getLocation().add(0, 3, 0)).count(5).offset(8, 3, 8).extra(0.01).spawn();
                }
            }
            // cratère violet au centre, une fois le blanc parti
            if (k >= white - 20 && k < white + 70 && k % 2 == 0) {
                Location g = ground(c);
                Location gc = g == null ? c : g;
                for (int i = 0; i < 3; i++) ring(w, gc.clone().add(0, 0.15, 0), 6 + i * 6, 40 + i * 20, new Vector(1, 0, 0), new Vector(0, 0, 1), i == 1 ? lilacD : purpleD, k * 0.02 * (i % 2 == 0 ? 1 : -1), true);
                w.spawnParticle(Particle.DUST, gc.clone().add(0, 1.5, 0), 25, 10, 1.5, 10, 0, inkD, true);
                w.spawnParticle(Particle.ELECTRIC_SPARK, gc.clone().add(0, 1, 0), 15, 12, 1, 12, 0.2, null, true);
                w.spawnParticle(Particle.REVERSE_PORTAL, gc.clone().add(0, 1, 0), 30, 14, 2, 14, 0.05, null, true);
            }
            // l'onde frappe chacun quand elle l'atteint
            if (k <= 46) {
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    double amount = d <= core ? dmg : dmg * (1 - 0.7 * (d - core) / Math.max(1, R - core));
                    le.setNoDamageTicks(0);
                    le.damage(amount, DamageSource.builder(DamageType.SONIC_BOOM).withCausingEntity(p).withDirectEntity(p).build());
                    le.getWorld().playSound(le.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.6f);
                }
            }
            if (k >= 190) {
                task[0].cancel();
                chat(c, p, plugin.msg("weapons.hollow-purple.summary").replace("<n>", String.valueOf(done.size())));
            }
        }, 0L, 1L);
    }

    // ================================================================= FÛGA (comme la vidéo)

    /*
     * 1. Un cercle de feu en spirale s'ouvre au sol autour du lanceur, ses mains s'embrasent.
     * 2. Des étoiles de lumière orange (longues lignes horizontales) et des croix sombres apparaissent autour de lui.
     * 3. Tir : une traînée de feu énorme, rubans jaunes / orange qui ondulent, tête de feu avec un anneau.
     * 4. Impact : l'écran est envahi par le feu, puis ciel noir de cendres, braises rouges, terrain ravagé.
     * 5. Une IMMENSE COLONNE DE FEU monte du point d'impact (visible de très loin), traversée d'éclats de lumière.
     * Rien devant les yeux du lanceur pendant l'incantation.
     */
    private void fuga(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.fuga.chant");
        int chant = 84;
        Particle.DustOptions ember = new Particle.DustOptions(EMBER, 1.5f), fire = new Particle.DustOptions(FIRE, 1.7f),
                gold = new Particle.DustOptions(GOLD, 1.3f), flareD = new Particle.DustOptions(Color.fromRGB(255, 150, 30), 1.1f),
                flareC = new Particle.DustOptions(Color.fromRGB(255, 250, 220), 1.6f), dark = new Particle.DustOptions(Color.fromRGB(20, 10, 10), 1.3f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            if (!p.isOnline() || p.isDead() || p.getWorld() != w) {
                casting.remove(p.getUniqueId());
                task[0].cancel();
                return;
            }
            if (!lines.isEmpty() && k % 36 == 0 && k / 36 < lines.size()) {
                chat(base, p, lines.get(k / 36));
                boom(base, 64, Sound.BLOCK_FIRE_AMBIENT, 2f, 0.5f);
            }
            if (k == 0) {
                boom(base, 128, Sound.ITEM_FIRECHARGE_USE, 2f, 0.5f);
                boom(base, 128, Sound.ENTITY_BLAZE_AMBIENT, 1.5f, 0.5f);
            }
            if (k % 10 == 5) boom(base, 48, Sound.BLOCK_FIRE_AMBIENT, 1.6f, 0.6f);
            if (k == 40 || k == 62) boom(base, 96, Sound.ENTITY_BLAZE_SHOOT, 1.4f, 0.5f);
            Location feet = p.getLocation(), eye = p.getEyeLocation();
            Vector fwd = feet.getDirection().setY(0);
            if (fwd.lengthSquared() < 1e-4) fwd = new Vector(0, 0, 1);
            fwd.normalize();
            Vector rt = right(fwd);
            double grow = Math.min(1, k / 22.0);
            // ---- 1. cercle de feu en spirale au sol
            for (int i = 0; i < 5; i++) {
                double rr = (0.9 + i * 0.8) * grow;
                int n = 16 + i * 8;
                for (int j = 0; j < n; j++) {
                    if ((j + k) % 2 == 1) continue;
                    double a = Math.PI * 2 * j / n + (i % 2 == 0 ? 1 : -1) * k * (0.1 - i * 0.012);
                    double wob = 0.22 * Math.sin(a * 4 + k * 0.35 + i);
                    Location at = feet.clone().add(Math.cos(a) * (rr + wob), 0.06, Math.sin(a) * (rr + wob));
                    if (j % 3 == 0) w.spawnParticle(Particle.FLAME, at, 1, 0, 0, 0, 0.003);
                    else w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, j % 3 == 1 ? fire : gold);
                }
            }
            w.spawnParticle(Particle.SMALL_FLAME, feet.clone().add(0, 0.3, 0), 3, 2.5 * grow, 0.2, 2.5 * grow, 0.02);
            // langues de feu qui montent du cercle + étincelles orange
            for (int n = 0; n < 5; n++) {
                double a = rnd.nextDouble(Math.PI * 2), rr = rnd.nextDouble(0.8, 4.2) * grow;
                w.spawnParticle(Particle.FLAME, feet.clone().add(Math.cos(a) * rr, 0.1, Math.sin(a) * rr), 0, 0, 1, 0, rnd.nextDouble(0.08, 0.2));
            }
            if (k % 2 == 0) w.spawnParticle(Particle.WAX_ON, feet.clone().add(0, 1.2, 0), 4, 2.2 * grow, 1.2, 2.2 * grow, 0.5);
            if (k % 2 == 0) w.spawnParticle(Particle.LAVA, feet, 1, 2 * grow, 0.05, 2 * grow, 0);
            // ---- les mains en feu (plus fort sur la main droite à la fin)
            double yaw = Math.toRadians(feet.getYaw());
            for (int side = -1; side <= 1; side += 2) {
                Location hand = feet.clone().add(-Math.cos(yaw) * 0.42 * side - Math.sin(yaw) * 0.2, 0.85, -Math.sin(yaw) * 0.42 * side + Math.cos(yaw) * 0.2);
                boolean big = side == -1 && k > 60;
                w.spawnParticle(Particle.FLAME, hand, big ? 9 : 4, 0.12, 0.18, 0.12, 0.02);
                w.spawnParticle(Particle.DUST, hand, big ? 4 : 2, 0.14, 0.18, 0.14, 0, k % 2 == 0 ? fire : ember);
                if (k % 4 == 0) w.spawnParticle(Particle.LAVA, hand, 1, 0.05, 0.05, 0.05, 0);
            }
            // ---- 2. étoiles de lumière et croix sombres autour (jamais devant les yeux)
            if (k > 16 && k % 6 == 0) {
                double a = rnd.nextDouble(Math.PI * 2);
                Location at = feet.clone().add(Math.cos(a) * rnd.nextDouble(1.6, 3.2), rnd.nextDouble(0.3, 3.4), Math.sin(a) * rnd.nextDouble(1.6, 3.2));
                if (at.distanceSquared(eye.clone().add(eye.getDirection().multiply(1.5))) > 2.5) flare(w, at, rt, rnd.nextDouble(0.8, 2.4), flareD, flareC);
            }
            if (k == 46 || k == 66) flare(w, feet.clone().add(0, 0.9, 0).add(fwd.clone().multiply(-0.6)), rt, 8, flareD, flareC);
            if (k > 26 && k % 9 == 0) {
                double a = rnd.nextDouble(Math.PI * 2);
                Location at = feet.clone().add(Math.cos(a) * rnd.nextDouble(1.8, 3), rnd.nextDouble(0.8, 3.5), Math.sin(a) * rnd.nextDouble(1.8, 3));
                for (double y = -1.4; y <= 1.4; y += 0.12) w.spawnParticle(Particle.DUST, at.clone().add(0, y, 0), 1, 0, 0, 0, 0, dark);
                for (double x = -0.5; x <= 0.5; x += 0.12) w.spawnParticle(Particle.DUST, at.clone().add(rt.clone().multiply(x)), 1, 0, 0, 0, 0, dark);
            }
            if (k >= chant) {
                task[0].cancel();
                casting.remove(p.getUniqueId());
                chat(base, p, plugin.msg("weapons.fuga.final"));
                boom(base, 260, Sound.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
                boom(base, 260, Sound.ENTITY_GHAST_SHOOT, 2f, 0.6f);
                boom(base, 260, Sound.ITEM_TRIDENT_RIPTIDE_3, 2f, 0.6f);
                boom(base, 260, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.3f);
                shake(base, 45, 12, 3);
                groundWave(feet, 14, 10, Particle.FLAME, fire);
                Vector dir = eye.getDirection().normalize();
                fugaFly(p, eye.clone().add(dir.clone().multiply(3.5)), dir);
            }
        }, 0L, 1L);
    }

    private void fugaFly(Player p, Location start, Vector dir) {
        World w = start.getWorld();
        double range = cfg("fuga.range", 140), speed = 3.4;
        Particle.DustOptions fire = new Particle.DustOptions(FIRE, 2.6f), gold = new Particle.DustOptions(GOLD, 2.4f),
                yellow = new Particle.DustOptions(Color.fromRGB(255, 235, 90), 2.8f), ember = new Particle.DustOptions(EMBER, 2.2f),
                hot = new Particle.DustOptions(Color.fromRGB(255, 250, 225), 2.2f), flareD = new Particle.DustOptions(Color.fromRGB(255, 160, 40), 1.2f);
        Vector rt = right(dir), up = rt.clone().crossProduct(dir).normalize();
        Location pos = start.clone();
        List<Location> tail = new ArrayList<>();
        int[] t = {0};
        double[] dist = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            Location from = pos.clone();
            RayTraceResult rtb = w.rayTraceBlocks(pos, dir, speed, FluidCollisionMode.NEVER, true);
            double step = rtb == null ? speed : rtb.getHitPosition().distance(pos.toVector());
            boolean stop = rtb != null;
            Location hitAt = null;
            for (double s = 0; s <= step; s += 0.35) {
                Location at = from.clone().add(dir.clone().multiply(s));
                double d = dist[0] + s;
                for (int j = 0; j < 3; j++) {
                    double ph = d * 0.32 + j * 2.1;
                    double amp = 0.6 + Math.min(1.2, d * 0.05);
                    Vector o = rt.clone().multiply(Math.sin(ph) * amp).add(up.clone().multiply(Math.cos(ph * 0.8) * amp * 0.6));
                    w.spawnParticle(Particle.DUST, at.clone().add(o), 1, 0.06, 0.06, 0.06, 0, j == 0 ? yellow : j == 1 ? fire : gold, true);
                }
                w.spawnParticle(Particle.DUST, at, 1, 0.1, 0.1, 0.1, 0, hot, true);
                if (hitAt == null && !targets(p, at, 1.8).isEmpty()) hitAt = at;
                tail.add(0, at.clone());
            }
            while (tail.size() > 80) tail.remove(tail.size() - 1);
            for (int i = 6; i < tail.size(); i += 2) {
                Location l = tail.get(i);
                w.spawnParticle(Particle.DUST, l, 1, 0.6, 0.4, 0.6, 0, i % 4 == 0 ? fire : ember, true);
            }
            if (hitAt != null) {
                pos.set(hitAt.getX(), hitAt.getY(), hitAt.getZ());
                stop = true;
            } else pos.add(dir.clone().multiply(step));
            dist[0] += step;
            // la tête de feu et son anneau
            orb(w, pos, 0.7, 20, gold, k * 0.4, true);
            ring(w, pos, 1.3, 24, rt, up, fire, k * 0.5, true);
            w.spawnParticle(Particle.FLAME, pos, 12, 0.35, 0.35, 0.35, 0.04, null, true);
            if (k % 3 == 0) flare(w, pos, rt, 3, flareD, hot);
            if (k % 3 == 0) w.playSound(pos, Sound.ENTITY_BLAZE_SHOOT, 3f, 0.6f);
            if (k % 4 == 0) shake(pos, 14, 6, 1.6);
            if (dist[0] >= range) stop = true;
            if (stop) {
                task[0].cancel();
                Location c = rtb != null && hitAt == null ? pos.clone().subtract(dir.clone().multiply(0.6)) : pos.clone();
                fugaBurst(p, c);
            }
        }, 0L, 1L);
    }

    private void fugaBurst(Player p, Location c) {
        World w = c.getWorld();
        double R = cfg("fuga.radius", 200), dmg = cfg("fuga.damage", 50), core = cfg("fuga.core-radius", 30);
        int burn = (int) (cfg("fuga.burn-seconds", 10) * 20);
        List<LivingEntity> list = targets(p, c, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) {
            dist.put(le.getUniqueId(), le.getLocation().distance(c));
            freeze(le, 90, false);
        }
        Location g0 = ground(c.clone().add(0, 1, 0));
        Location gc = g0 == null ? c.clone() : g0;
        Particle.DustOptions fire = new Particle.DustOptions(FIRE, 3.4f), gold = new Particle.DustOptions(GOLD, 2.6f),
                ember = new Particle.DustOptions(EMBER, 3.0f), ash = new Particle.DustOptions(Color.fromRGB(28, 24, 24), 4f),
                red = new Particle.DustOptions(Color.fromRGB(255, 30, 0), 1.2f), stripe = new Particle.DustOptions(Color.fromRGB(150, 10, 0), 3f),
                flareD = new Particle.DustOptions(Color.fromRGB(255, 110, 30), 1.4f), flareC = new Particle.DustOptions(Color.fromRGB(255, 240, 200), 2f);
        Set<UUID> done = new HashSet<>();
        Map<UUID, Integer> passedAt = new HashMap<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boom(c, R + 60, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 4f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_BLAZE_DEATH, 2f, 0.5f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 10, 4, 3, 4, 0, null, true);
        w.spawnParticle(Particle.FLAME, c, 1200, 0.5, 0.5, 0.5, 1.1, null, true);
        w.spawnParticle(Particle.LAVA, c, 200, 5, 2, 5, 0, null, true);
        shards(gc, 40, 20, 170);
        shake(c, R + 30, 80, 9);
        groundWave(gc, 55, 20, Particle.FLAME, new Particle.DustOptions(FIRE, 3.2f));
        w.spawnParticle(Particle.LAVA, gc, 250, 8, 2, 8, 0, null, true);
        w.spawnParticle(Particle.LARGE_SMOKE, gc, 250, 3, 2, 3, 0.6, null, true);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
            // ---- l'immense colonne de feu
            if (k == 14) {
                pillar(gc, new Material[]{Material.ORANGE_STAINED_GLASS, Material.MAGMA_BLOCK, Material.RED_STAINED_GLASS},
                        new float[]{11, 6.5f, 8.5f}, 220, 8, 130, 24);
                boom(c, R + 60, Sound.ENTITY_BLAZE_SHOOT, 4f, 0.4f);
                boom(c, R + 60, Sound.ITEM_TRIDENT_THUNDER, 3f, 0.5f);
                boom(c, R + 60, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
            }
            // grondements en cascade, la colonne fait trembler le sol tant qu'elle brûle
            if (k == 4 || k == 10 || k == 20 || k == 32) {
                boom(c, R + 60, k % 4 == 0 ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 3f, 0.45f + k * 0.005f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, gc.clone().add(rnd.nextDouble(-12, 12), rnd.nextDouble(0, 6), rnd.nextDouble(-12, 12)), 3, 4, 2, 4, 0, null, true);
            }
            if (k >= 20 && k < 150 && k % 20 == 0) {
                shake(gc, 70, 12, 1.4);
                boom(gc, R + 40, Sound.BLOCK_FIRE_AMBIENT, 3f, 0.5f);
                boom(gc, R + 40, Sound.ENTITY_BLAZE_BURN, 2f, 0.5f);
            }
            // geysers de feu autour de l'impact
            if (k < 60 && k % 2 == 0) {
                for (int n = 0; n < 3; n++) {
                    double a = rnd.nextDouble(Math.PI * 2), d0 = rnd.nextDouble(6, 30);
                    Location g = ground(gc.clone().add(Math.cos(a) * d0, 2, Math.sin(a) * d0));
                    if (g != null) w.spawnParticle(Particle.FLAME, g.clone().add(0, 2.5, 0), 45, 0.3, 2.5, 0.3, 0.03, null, true);
                }
            }
            if (k >= 14 && k < 170) {
                if (k % 2 == 0) {
                    double top = Math.min(220, (k - 14) * 28.0);
                    for (double y = 0; y < top; y += 3.5) {
                        double a = y * 0.1 + k * 0.3;
                        w.spawnParticle(Particle.FLAME, gc.getX() + Math.cos(a) * 5.8, gc.getY() + y, gc.getZ() + Math.sin(a) * 5.8, 2, 0.3, 1.2, 0.3, 0.02, null, true);
                        w.spawnParticle(Particle.DUST, gc.getX() - Math.cos(a) * 5.8, gc.getY() + y, gc.getZ() - Math.sin(a) * 5.8, 1, 0.2, 1.4, 0.2, 0, y % 10 < 3.5 ? stripe : (y % 7 < 3.5 ? gold : fire), true);
                    }
                    // flammes à la base
                    w.spawnParticle(Particle.FLAME, gc.clone().add(0, 1, 0), 50, 7, 1.2, 7, 0.07, null, true);
                    w.spawnParticle(Particle.LAVA, gc, 6, 6, 0.3, 6, 0, null, true);
                }
                // éclats de lumière horizontaux qui traversent la colonne
                if (k % 7 == 0) flare(w, gc.clone().add(0, rnd.nextDouble(8, 140), 0), new Vector(1, 0, rnd.nextDouble(-0.4, 0.4)), rnd.nextDouble(14, 30), flareD, flareC);
            }
            // ---- l'onde de feu frappe chacun
            if (k <= 46) {
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    double amount = d <= core ? dmg : dmg * (1 - 0.7 * (d - core) / Math.max(1, R - core));
                    le.setNoDamageTicks(0);
                    le.damage(amount, DamageSource.builder(DamageType.MAGIC).withCausingEntity(p).withDirectEntity(p).build());
                    le.setFireTicks(burn);
                    Location at = le.getLocation().add(0, 1, 0);
                    w.spawnParticle(Particle.FLAME, at, 50, 0.4, 0.7, 0.4, 0.15);
                    w.playSound(at, Sound.ENTITY_GENERIC_BURN, 1.5f, 0.6f);
                }
            }
            // ---- l'écran envahi par le feu, puis ciel de cendres et braises rouges
            for (Player o : w.getPlayers()) {
                double d = o.getLocation().distance(c);
                if (d > R) continue;
                if (d <= r && !passedAt.containsKey(o.getUniqueId())) {
                    passedAt.put(o.getUniqueId(), k);
                    o.playSound(o.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
                    o.playSound(o.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.5f);
                }
                Integer at = passedAt.get(o.getUniqueId());
                if (at == null) continue;
                int since = k - at;
                if (since < 22) {
                    int n = 90 - since * 3;
                    screen(o, since % 2 == 0 ? FIRE : GOLD, since % 3 == 0 ? Color.fromRGB(255, 25, 0) : Color.fromRGB(255, 240, 120), n, 4f);
                } else if (since < 150 && k % 3 == 0) {
                    Location l = o.getLocation();
                    new ParticleBuilder(Particle.ASH).receivers(o).location(l.clone().add(0, 4, 0)).count(50).offset(10, 3, 10).extra(0).spawn();
                    new ParticleBuilder(Particle.DUST).receivers(o).location(l.clone().add(0, 2, 0)).count(8).offset(9, 3, 9).extra(0).data(red).spawn();
                    new ParticleBuilder(Particle.LARGE_SMOKE).receivers(o).location(l.clone().add(0, 9, 0)).count(10).offset(14, 2, 14).extra(0.01).spawn();
                }
            }
            // ---- nuage noir et terrain brûlé autour de l'impact
            if (k > 8 && k < 180 && k % 3 == 0) {
                w.spawnParticle(Particle.DUST, gc.clone().add(0, 14, 0), 40, 26, 8, 26, 0, ash, true);
                w.spawnParticle(Particle.LARGE_SMOKE, gc.clone().add(0, 6, 0), 20, 18, 4, 18, 0.02, null, true);
                w.spawnParticle(Particle.FLAME, gc, 25, 14, 0.4, 14, 0.02, null, true);
                w.spawnParticle(Particle.DUST, gc.clone().add(0, 3, 0), 20, 16, 4, 16, 0, ember, true);
            }
            if (k >= 180) {
                task[0].cancel();
                chat(c, p, plugin.msg("weapons.fuga.summary").replace("<n>", String.valueOf(done.size())));
            }
        }, 0L, 1L);
    }
}
