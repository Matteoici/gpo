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

    /** Tremblement de caméra : la vue donne des à-coups (plus fort près du centre) + secousse "coup reçu". */
    private void shake(Location c, double radius, int ticks, double power) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            boolean last = k >= ticks;
            double fade = 1 - (double) k / ticks;
            for (Player o : w.getPlayers()) {
                if (o.getLocation().distanceSquared(c) > radius * radius) continue;
                double d = o.getLocation().distance(c);
                float[] off = shakeOff.get(o.getUniqueId());
                double amp = last ? 0 : power * fade * (1 - 0.7 * d / radius);
                if (off == null && amp < 0.15) continue;
                float by = o.getYaw() - (off == null ? 0 : off[0]), bp = o.getPitch() - (off == null ? 0 : off[1]);
                float oy = amp < 0.15 ? 0 : (float) rnd.nextDouble(-amp, amp), op = amp < 0.15 ? 0 : (float) rnd.nextDouble(-amp * 0.7, amp * 0.7);
                float np = Math.max(-89, Math.min(89, bp + op));
                Vector v = look(by + oy, np);
                Location eye = o.getEyeLocation();
                o.lookAt(eye.getX() + v.getX() * 8, eye.getY() + v.getY() * 8, eye.getZ() + v.getZ() * 8, io.papermc.paper.entity.LookAnchor.EYES);
                if (amp < 0.15) shakeOff.remove(o.getUniqueId());
                else shakeOff.put(o.getUniqueId(), new float[]{oy, np - bp});
                if (amp > 1.5 && k % 5 == 0) o.playHurtAnimation((float) rnd.nextDouble(360));
            }
            if (last) task[0].cancel();
        }, 0L, 1L);
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

    /** Éclairs (sans feu ni dégâts) tout autour. */
    private void lightning(Location c, double radius, int count, int overTicks) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                double a = rnd.nextDouble(Math.PI * 2), d = Math.sqrt(rnd.nextDouble()) * radius;
                Location at = ground(c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
                c.getWorld().strikeLightningEffect(at == null ? c : at);
            }, rnd.nextInt(Math.max(1, overTicks)));
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

    /** Boule d'énergie géante (3 blocs translucides tournés les uns dans les autres) qui gonfle puis s'efface. */
    private void blast(Location c, Material[] mats, float size, int grow, int hold, int fade) {
        Quaternionf[] qs = {new Quaternionf(), new Quaternionf().rotateY((float) (Math.PI / 4)).rotateX((float) (Math.PI / 4)),
                new Quaternionf().rotateZ((float) (Math.PI / 4)).rotateY((float) (Math.PI / 6))};
        List<BlockDisplay> ds = new ArrayList<>();
        for (int i = 0; i < mats.length; i++) ds.add(block(c, mats[i].createBlockData(), centered(qs[i % 3], 0.1f, 0.1f, 0.1f, true), 8f, size * 1.6f));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < ds.size(); i++) {
                float s = size * (1 - i * 0.12f);
                animate(ds.get(i), centered(qs[i % 3], s, s, s, true), grow);
            }
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < ds.size(); i++) animate(ds.get(i), centered(qs[i % 3], 0.05f, 0.05f, 0.05f, true), fade);
        }, 1L + grow + hold);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (BlockDisplay d : ds) d.remove();
        }, 2L + grow + hold + fade);
    }

    /** Débris : copies des blocs du sol projetées en l'air puis qui retombent (rien n'est cassé). */
    private void debris(Location c, int n, double power, Particle trail, Material fallback) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<BlockDisplay> ds = new ArrayList<>();
        List<Vector> vel = new ArrayList<>();
        List<float[]> spin = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble(Math.PI * 2), d = rnd.nextDouble(1, 9);
            Location g = ground(c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            BlockData bd = g == null ? null : g.clone().add(0, -1, 0).getBlock().getBlockData();
            if (bd == null || !bd.getMaterial().isSolid() || !bd.getMaterial().isOccluding()) bd = fallback.createBlockData();
            float s = (float) rnd.nextDouble(0.5, 1.4);
            Location at = (g == null ? c.clone() : g).add(0, 0.3, 0);
            BlockDisplay bdisp = block(at, bd, centered(new Quaternionf(), s, s, s, true), 3f, 2f);
            bdisp.setBrightness(null);
            bdisp.setTeleportDuration(2);
            ds.add(bdisp);
            vel.add(new Vector(Math.cos(a), 0, Math.sin(a)).multiply(rnd.nextDouble(0.3, 0.9) * power).setY(rnd.nextDouble(0.7, 1.5) * power));
            spin.add(new float[]{(float) rnd.nextDouble(-25, 25), (float) rnd.nextDouble(-25, 25)});
        }
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0] += 2;
            for (int i = 0; i < ds.size(); i++) {
                BlockDisplay d = ds.get(i);
                if (!d.isValid()) continue;
                Vector v = vel.get(i);
                Location l = d.getLocation().add(v.clone().multiply(2));
                v.setY(v.getY() - 0.16);
                l.setYaw(l.getYaw() + spin.get(i)[0]);
                l.setPitch(Math.max(-90, Math.min(90, l.getPitch() + spin.get(i)[1])));
                if (t[0] > 6 && l.getBlock().getType().isSolid()) {
                    w.spawnParticle(Particle.BLOCK, d.getLocation(), 12, 0.3, 0.2, 0.3, 0, d.getBlock(), true);
                    d.remove();
                    continue;
                }
                d.teleport(l);
                if (trail != null) w.spawnParticle(trail, l, 1, 0.1, 0.1, 0.1, 0.01, null, true);
            }
            if (t[0] >= 60) {
                for (BlockDisplay d : ds) d.remove();
                task[0].cancel();
            }
        }, 2L, 2L);
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

    // ================================================================= VIOLET IMAGINAIRE

    /*
     * 1. Un faisceau rouge monte du lanceur, le Rouge apparaît au-dessus de son épaule.
     * 2. Le Bleu file en orbite autour de lui en balayant le sol, traînée bleue et vrilles noires.
     * 3. Bleu et Rouge se rejoignent devant lui, tournent l'un autour de l'autre et fusionnent : éclair blanc.
     * 4. La sphère violette géante part droit devant (tranchée de particules au sol, éclairs, aspiration).
     * 5. Implosion, puis explosion : tout devient blanc (éclairs violets/noirs devant les yeux), boule géante,
     *    colonne de lumière, débris, éclairs, tremblements ; le mur violet balaie 200 blocs et frappe chacun.
     */
    private void hollow(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.hollow-purple.chant");
        int chant = 104;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, chant, 4, false, false, false));
        Particle.DustOptions blue = new Particle.DustOptions(BLUE, 1.8f), deep = new Particle.DustOptions(Color.fromRGB(25, 50, 200), 2.4f),
                ink = new Particle.DustOptions(Color.fromRGB(10, 5, 25), 1.6f), red = new Particle.DustOptions(RED, 1.8f),
                redGlow = new Particle.DustOptions(Color.fromRGB(255, 120, 130), 1.2f), purple = new Particle.DustOptions(PURPLE, 2.0f),
                lilac = new Particle.DustOptions(LILAC, 1.3f), ring = new Particle.DustOptions(MAGENTA, 1.0f),
                white = new Particle.DustOptions(Color.WHITE, 1.5f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Location> trail = new ArrayList<>();
        Location[] orbitEnd = new Location[1];
        // le faisceau rouge
        pillar(base, new Material[]{Material.RED_STAINED_GLASS, Material.PINK_STAINED_GLASS}, new float[]{1.4f, 0.7f}, 45, 4, 26, 10);
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
            Vector dir = eye.getDirection().normalize(), rt = right(dir);
            Location front = eye.clone().add(dir.clone().multiply(1.8));
            // incantation
            if (!lines.isEmpty() && k % 15 == 0 && k / 15 < lines.size()) {
                chat(base, p, lines.get(k / 15));
                boom(base, 64, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.6f, 0.5f + 0.1f * (k / 15));
            }
            // sons
            if (k == 0) {
                boom(base, 128, Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.5f);
                boom(base, 128, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2f, 0.5f);
                boom(base, 128, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.6f);
                shake(base, 30, 20, 1.2);
            }
            if (k == 16) boom(base, 96, Sound.BLOCK_CONDUIT_ACTIVATE, 2f, 0.6f);
            if (k >= 16 && k < 76 && k % 9 == 0) boom(base, 64, Sound.ENTITY_PHANTOM_SWOOP, 1.4f, 0.6f);
            if (k == 60) shake(base, 40, 44, 1.6);
            if (k == 76) boom(base, 128, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, 2f, 0.5f);
            if (k == 84) boom(base, 160, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 0.5f);
            if (k == 96) boom(base, 160, Sound.BLOCK_END_PORTAL_FRAME_FILL, 2f, 0.5f);
            // cercle magique au sol + runes
            if (k % 2 == 0) {
                ring(w, base.clone().add(0, 0.08, 0), 3.2, 44, new Vector(1, 0, 0), new Vector(0, 0, 1), ring, k * 0.04, false);
                ring(w, base.clone().add(0, 0.08, 0), 4.6, 52, new Vector(1, 0, 0), new Vector(0, 0, 1), lilac, -k * 0.03, false);
                for (int n = 0; n < 8; n++) {
                    double a = Math.PI * 2 * n / 8 - k * 0.03;
                    for (double s = 3.2; s <= 4.6; s += 0.35) w.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * s, 0.08, Math.sin(a) * s), 1, 0, 0, 0, 0, ring);
                    w.spawnParticle(Particle.ENCHANT, base.clone().add(Math.cos(a) * 6, 0.6, Math.sin(a) * 6), 3, 0.2, 0.3, 0.2, 0.5);
                }
                // vent aspiré vers le lanceur
                for (int n = 0; n < 4; n++) {
                    double a = rnd.nextDouble(Math.PI * 2);
                    Vector v = new Vector(Math.cos(a), rnd.nextDouble(-0.1, 0.3), Math.sin(a)).multiply(9);
                    w.spawnParticle(Particle.CLOUD, feet.clone().add(v).add(0, 0.5, 0), 0, -v.getX(), -v.getY(), -v.getZ(), 0.07);
                }
            }
            // ---- positions du Bleu et du Rouge
            Location redPos, bluePos;
            double conv = k < 86 ? 0 : Math.min(1, (k - 86) / 18.0);
            if (k < 86) {
                redPos = eye.clone().add(rt.clone().multiply(0.9)).add(0, 0.9, 0);
                if (k < 70) {
                    double a = k * 0.21, rad = 7 + 2 * Math.sin(k * 0.07), h = 0.9 + 1.4 * Math.sin(k * 0.11);
                    bluePos = feet.clone().add(Math.cos(a) * rad, h, Math.sin(a) * rad);
                    orbitEnd[0] = bluePos.clone();
                } else {
                    double s = (k - 70) / 16.0;
                    s = s * s * (3 - 2 * s);
                    Location left = eye.clone().add(rt.clone().multiply(-0.9)).add(0, 0.9, 0);
                    bluePos = orbitEnd[0].clone().add(left.toVector().subtract(orbitEnd[0].toVector()).multiply(s));
                }
            } else {
                double a = (k - 86) * 0.55, spread = 1.3 * (1 - conv);
                Vector up2 = rt.clone().crossProduct(dir).normalize();
                Vector off = rt.clone().multiply(Math.cos(a) * spread).add(up2.multiply(Math.sin(a) * spread));
                bluePos = front.clone().subtract(off);
                redPos = front.clone().add(off);
            }
            // ---- le Rouge : sphère rouge, anneaux qui repoussent
            if (conv < 1) {
                double rr = Math.min(0.6, 0.15 + k * 0.012);
                orb(w, redPos, rr, 26, red, -k * 0.4, true);
                orb(w, redPos, rr * 0.5, 8, redGlow, k * 0.6, true);
                if (k % 6 == 0) ring(w, redPos, rr + 0.25 + (k % 18) * 0.06, 18, rt, new Vector(0, 1, 0), red, 0, true);
                for (int n = 0; n < 2; n++) {
                    Vector v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1)).normalize();
                    w.spawnParticle(Particle.FLAME, redPos, 0, v.getX(), v.getY(), v.getZ(), 0.08);
                }
            }
            // ---- le Bleu : comète qui tourne, traînée bleue et vrilles noires
            if (k >= 14 && conv < 1) {
                double rb = Math.min(0.6, 0.2 + (k - 14) * 0.02);
                orb(w, bluePos, rb, 26, blue, k * 0.5, true);
                w.spawnParticle(Particle.GLOW, bluePos, 3, rb, rb, rb, 0.02, null, true);
                for (int n = 0; n < 3; n++) {
                    Vector v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1)).normalize().multiply(2.2);
                    w.spawnParticle(Particle.END_ROD, bluePos.clone().add(v), 0, -v.getX(), -v.getY(), -v.getZ(), 0.11, null, true);
                }
                trail.add(0, bluePos.clone());
                while (trail.size() > 28) trail.remove(trail.size() - 1);
                for (int i = 0; i < trail.size(); i++) {
                    Location l = trail.get(i);
                    float f = 1 - i / 28f;
                    w.spawnParticle(Particle.DUST, l, 2, 0.15 * f + 0.05, 0.12, 0.15 * f + 0.05, 0, i < 8 ? blue : deep, true);
                    if (i % 3 == 1) w.spawnParticle(Particle.DUST, l.clone().add(rnd.nextDouble(-0.4, 0.4), rnd.nextDouble(-0.3, 0.3), rnd.nextDouble(-0.4, 0.4)), 1, 0, 0, 0, 0, ink, true);
                }
                // il balaie le sol
                if (k < 70) {
                    Location g = ground(bluePos);
                    if (g != null && bluePos.getY() - g.getY() < 2.5) {
                        w.spawnParticle(Particle.CLOUD, g, 3, 0.4, 0.05, 0.4, 0.04, null, true);
                        w.spawnParticle(Particle.DUST, g.clone().add(0, 0.05, 0), 2, 0.3, 0, 0.3, 0, deep, true);
                    }
                }
            }
            // ---- arcs électriques entre les deux quand ils sont proches
            if (k >= 70 && conv < 1 && k % 2 == 0) {
                Vector seg = redPos.toVector().subtract(bluePos.toVector());
                double len = seg.length();
                for (double s = 0; s <= len; s += 0.2) {
                    Location at = bluePos.clone().add(seg.clone().multiply(s / Math.max(len, 0.01))).add(rnd.nextDouble(-0.15, 0.15), rnd.nextDouble(-0.15, 0.15), rnd.nextDouble(-0.15, 0.15));
                    w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, rnd.nextBoolean() ? purple : white, true);
                }
                w.spawnParticle(Particle.ELECTRIC_SPARK, bluePos, 3, 0.2, 0.2, 0.2, 0.1, null, true);
                w.spawnParticle(Particle.ELECTRIC_SPARK, redPos, 3, 0.2, 0.2, 0.2, 0.1, null, true);
            }
            // ---- la sphère violette naît
            if (conv > 0) {
                orb(w, front, 0.2 + conv * 1.5, 46, purple, k * 0.3, true);
                orb(w, front, 0.1 + conv * 0.6, 14, lilac, -k * 0.5, true);
                w.spawnParticle(Particle.DUST, front, 4, 0.8 * conv, 0.8 * conv, 0.8 * conv, 0, ink, true);
                w.spawnParticle(Particle.ELECTRIC_SPARK, front, 5, 0.7 * conv, 0.7 * conv, 0.7 * conv, 0.08, null, true);
                w.spawnParticle(Particle.REVERSE_PORTAL, front, 10, 1, 1, 1, 0.1, null, true);
                ring(w, front, 1.4 + conv * 0.8, 30, rt, new Vector(0, 1, 0), ring, k * 0.2, true);
            }
            // ---- fusion : éclair blanc
            if (k == chant - 4) {
                for (Player o : w.getPlayers()) if (o.getLocation().distanceSquared(front) < 40 * 40) screen(o, Color.WHITE, LILAC, 40, 4f);
                w.spawnParticle(Particle.END_ROD, front, 150, 0.2, 0.2, 0.2, 0.6, null, true);
                ring(w, feet.clone().add(0, 0.2, 0), 5, 60, new Vector(1, 0, 0), new Vector(0, 0, 1), white, 0, true);
                lightning(feet, 10, 3, 6);
            }
            if (k >= chant) {
                task[0].cancel();
                casting.remove(p.getUniqueId());
                chat(base, p, plugin.msg("weapons.hollow-purple.final"));
                boom(base, 260, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 0.6f);
                boom(base, 260, Sound.ENTITY_WITHER_SHOOT, 2f, 0.5f);
                boom(base, 260, Sound.ITEM_TRIDENT_THUNDER, 2f, 0.7f);
                boom(base, 260, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.4f);
                shake(base, 70, 14, 4.5);
                w.spawnParticle(Particle.END_ROD, front, 120, 0.2, 0.2, 0.2, 0.5, null, true);
                hollowFly(p, front, dir);
            }
        }, 0L, 1L);
    }

    private void hollowFly(Player p, Location start, Vector dir) {
        World w = start.getWorld();
        double range = cfg("hollow-purple.range", 120), speed = 2.6, pathDmg = cfg("hollow-purple.path-damage", 30);
        Particle.DustOptions purple = new Particle.DustOptions(PURPLE, 3.0f), core = new Particle.DustOptions(LILAC, 2.0f),
                mag = new Particle.DustOptions(MAGENTA, 1.8f), ink = new Particle.DustOptions(Color.fromRGB(15, 0, 30), 2.2f),
                scar = new Particle.DustOptions(Color.fromRGB(70, 10, 110), 2.6f);
        Vector rt = right(dir), up = rt.clone().crossProduct(dir).normalize();
        Location pos = start.clone();
        Set<UUID> hit = new HashSet<>();
        List<Location> scars = new ArrayList<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] t = {0};
        double[] dist = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            double rs = Math.min(6, 1.5 + k * 0.5);
            RayTraceResult rtb = w.rayTraceBlocks(pos, dir, speed, FluidCollisionMode.NEVER, true);
            boolean stop = false;
            if (rtb != null) {
                pos.set(rtb.getHitPosition().getX(), rtb.getHitPosition().getY(), rtb.getHitPosition().getZ());
                pos.subtract(dir.clone().multiply(1.0));
                stop = true;
            } else pos.add(dir.clone().multiply(speed));
            dist[0] += speed;
            if (dist[0] >= range) stop = true;
            // la sphère : coque violette, cœur clair, vrilles noires, étincelles
            orb(w, pos, rs, 110, purple, k * 0.3, true);
            orb(w, pos, rs * 0.45, 26, core, -k * 0.5, true);
            w.spawnParticle(Particle.END_ROD, pos, 8, 0.4, 0.4, 0.4, 0.03, null, true);
            w.spawnParticle(Particle.DUST, pos, 8, rs * 0.6, rs * 0.6, rs * 0.6, 0, ink, true);
            w.spawnParticle(Particle.ELECTRIC_SPARK, pos, 10, rs * 0.7, rs * 0.7, rs * 0.7, 0.15, null, true);
            // anneaux en orbite
            for (int i = 0; i < 3; i++) {
                double tilt = i * Math.PI / 3 + k * 0.08;
                Vector a = rt.clone().multiply(Math.cos(tilt)).add(up.clone().multiply(Math.sin(tilt)));
                ring(w, pos, rs + 1.3, 30, a, dir, mag, k * 0.25 + i, true);
            }
            // onde de choc tous les 4 ticks
            if (k % 4 == 0) {
                ring(w, pos, rs + 3, 40, rt, up, core, 0, true);
                for (int n = 0; n < 10; n++) {
                    double a = Math.PI * 2 * n / 10;
                    Vector v = rt.clone().multiply(Math.cos(a)).add(up.clone().multiply(Math.sin(a)));
                    w.spawnParticle(Particle.CLOUD, pos.clone().add(v.clone().multiply(rs + 1)), 0, v.getX(), v.getY(), v.getZ(), 0.5, null, true);
                }
            }
            // aspiration
            for (int n = 0; n < 6; n++) {
                Vector v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1)).normalize().multiply(rs + 6);
                w.spawnParticle(Particle.REVERSE_PORTAL, pos.clone().add(v), 0, -v.getX(), -v.getY(), -v.getZ(), 0.12, null, true);
            }
            // tranchée au sol (les traces restent un moment)
            Location g = ground(pos.clone().add(0, -2, 0));
            if (g != null && pos.getY() - g.getY() < rs + 6) scars.add(g.clone().add(0, 0.1, 0));
            for (int i = Math.max(0, scars.size() - 40); i < scars.size(); i += (k % 2 == 0 ? 1 : 2)) {
                Location sc = scars.get(i);
                w.spawnParticle(Particle.DUST, sc, 3, rs * 0.35, 0.05, rs * 0.35, 0, scar, true);
                if (i % 3 == 0) w.spawnParticle(Particle.SMOKE, sc, 2, rs * 0.3, 0.1, rs * 0.3, 0.01, null, true);
            }
            if (k % 8 == 4 && g != null) w.strikeLightningEffect(g);
            if (k % 5 == 0) {
                w.playSound(pos, Sound.BLOCK_BEACON_AMBIENT, 4f, 0.5f);
                w.playSound(pos, Sound.ENTITY_BREEZE_WIND_BURST, 3f, 0.5f);
                shake(pos, 35, 5, 2.2);
            }
            // ce qui est sur la route est happé
            for (LivingEntity le : targets(p, pos, rs + 0.5)) {
                if (!hit.add(le.getUniqueId())) continue;
                le.setNoDamageTicks(0);
                le.damage(pathDmg, DamageSource.builder(DamageType.SONIC_BOOM).withCausingEntity(p).withDirectEntity(p).build());
                freeze(le, 140, true);
                w.spawnParticle(Particle.END_ROD, le.getLocation().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.25);
            }
            if (stop) {
                task[0].cancel();
                hollowBurst(p, pos.clone(), rs);
            }
        }, 0L, 1L);
    }

    private void hollowBurst(Player p, Location c, double startR) {
        World w = c.getWorld();
        double R = cfg("hollow-purple.radius", 200), dmg = cfg("hollow-purple.damage", 60), core = cfg("hollow-purple.core-radius", 30);
        List<LivingEntity> list = targets(p, c, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) {
            dist.put(le.getUniqueId(), le.getLocation().distance(c));
            freeze(le, 110, true);
        }
        for (Player o : w.getPlayers()) {
            if (o.getLocation().distanceSquared(c) > sq(R + 40)) continue;
            o.setPlayerTime(18000, false);
            night.add(o.getUniqueId());
        }
        Particle.DustOptions purple = new Particle.DustOptions(PURPLE, 3.6f), lilac = new Particle.DustOptions(LILAC, 2.6f),
                ink = new Particle.DustOptions(Color.fromRGB(15, 0, 30), 2.6f), white = new Particle.DustOptions(Color.WHITE, 3f);
        ParticleBuilder wa = dust(PURPLE, 4f), wb = dust(MAGENTA, 3.4f), wc = dust(Color.WHITE, 2.4f), wd = dust(LILAC, 2.4f);
        Set<UUID> done = new HashSet<>(), passed = new HashSet<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boom(c, R + 60, Sound.BLOCK_END_PORTAL_SPAWN, 2f, 0.5f);
        boom(c, R + 60, Sound.BLOCK_BELL_RESONATE, 2f, 0.5f);
        shake(c, 80, 24, 1.8);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            if (k < 24) {
                // implosion : la sphère se replie, tout est aspiré en spirale
                double rs = startR * (1 - k / 24.0) + 0.4;
                orb(w, c, rs, 90, purple, k * 0.5, true);
                orb(w, c, rs * 0.5, 22, lilac, -k * 0.7, true);
                for (int arm = 0; arm < 8; arm++) {
                    for (int n = 0; n < 12; n++) {
                        double f = n / 12.0, rad = 45 * (1 - k / 24.0) * (1 - f) + 2;
                        double a = Math.PI * 2 * arm / 8 + f * 4 + k * 0.25;
                        w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * rad, (f - 0.5) * 6, Math.sin(a) * rad), 1, 0, 0, 0, 0, n % 3 == 0 ? lilac : purple, true);
                    }
                }
                w.spawnParticle(Particle.REVERSE_PORTAL, c, 60, 4, 4, 4, 0.5, null, true);
                w.spawnParticle(Particle.DUST, c, 10, 2, 2, 2, 0, ink, true);
                if (k == 12) boom(c, R + 60, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.5f);
                if (k == 18) boom(c, R + 60, Sound.ENTITY_WARDEN_SONIC_CHARGE, 3f, 0.5f);
                return;
            }
            if (k == 24) {
                // ---- EXPLOSION
                boom(c, R + 60, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
                boom(c, R + 60, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4f, 0.5f);
                boom(c, R + 60, Sound.ENTITY_WARDEN_SONIC_BOOM, 4f, 0.5f);
                boom(c, R + 60, Sound.ENTITY_WITHER_DEATH, 1.4f, 0.6f);
                boom(c, R + 60, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.5f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 14, 6, 4, 6, 0, null, true);
                w.spawnParticle(Particle.END_ROD, c, 900, 0.5, 0.5, 0.5, 2.2, null, true);
                w.spawnParticle(Particle.DUST, c, 600, 10, 8, 10, 0, purple, true);
                w.spawnParticle(Particle.REVERSE_PORTAL, c, 500, 3, 3, 3, 3.5, null, true);
                w.spawnParticle(Particle.DUST, c, 200, 12, 8, 12, 0, ink, true);
                blast(c, new Material[]{Material.PURPLE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS, Material.WHITE_STAINED_GLASS}, 42, 10, 26, 18);
                pillar(c, new Material[]{Material.PURPLE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS, Material.AMETHYST_BLOCK}, new float[]{9, 6, 2.2f}, 170, 8, 70, 20);
                debris(c, 55, 1.9, Particle.REVERSE_PORTAL, Material.CRYING_OBSIDIAN);
                shards(c, 34, 20, 150);
                lightning(c, 70, 16, 50);
                shake(c, R + 40, 70, 7.5);
            }
            if (k == 28 || k == 34 || k == 44 || k == 58) {
                boom(c, R + 60, k == 34 ? Sound.ENTITY_DRAGON_FIREBALL_EXPLODE : Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.5f + (k - 28) * 0.006f);
                boom(c, R + 60, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.6f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(rnd.nextDouble(-14, 14), rnd.nextDouble(0, 10), rnd.nextDouble(-14, 14)), 4, 5, 3, 5, 0, null, true);
            }
            int e = k - 24;
            double u = Math.min(1, e / 40.0), r = R * (1 - Math.pow(1 - u, 3));
            double u2 = Math.max(0, Math.min(1, (e - 6) / 40.0)), r2 = R * (1 - Math.pow(1 - u2, 3));
            // colonne de lumière + spirales
            if (k % 2 == 0 && k < 110) {
                for (double y = 0; y < 160; y += 3) {
                    double a = y * 0.15 + k * 0.2;
                    w.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * 5.5, c.getY() + y, c.getZ() + Math.sin(a) * 5.5, 1, 0.2, 0.4, 0.2, 0, lilac, true);
                    w.spawnParticle(Particle.DUST, c.getX() - Math.cos(a) * 5.5, c.getY() + y, c.getZ() - Math.sin(a) * 5.5, 1, 0.2, 0.4, 0.2, 0, purple, true);
                }
                w.spawnParticle(Particle.END_ROD, c.clone().add(0, rnd.nextDouble(0, 160), 0), 6, 1, 3, 1, 0.03, null, true);
            }
            // l'onde : un mur violet puis un mur blanc
            if (e <= 46 && k % 2 == 0) {
                wall(c, Math.max(1, r), R, wa, wb, Particle.END_ROD);
                if (e >= 6) wall(c, Math.max(1, r2), R, wc, wd, null);
            }
            // tout devient blanc (éclairs violets et noirs devant les yeux), puis ça revient
            for (Player o : w.getPlayers()) {
                double d = o.getLocation().distance(c);
                if (d > R + 20) continue;
                if (e >= 0 && e < 44 && (d <= r + 10)) {
                    int n = e < 30 ? 55 : 55 - (e - 30) * 4;
                    screen(o, Color.WHITE, e % 3 == 0 ? Color.fromRGB(240, 225, 255) : null, Math.max(6, n), 4f);
                    if (e % 2 == 0) streak(o, rnd.nextInt(3) == 0 ? Color.fromRGB(15, 0, 30) : PURPLE, 1.6f);
                    if (e % 4 == 1) streak(o, MAGENTA, 1.3f);
                } else if (e >= 44 && e < 70 && e % 4 == 0) {
                    // brume violette qui retombe
                    new ParticleBuilder(Particle.REVERSE_PORTAL).receivers(o).location(o.getLocation().add(0, 2, 0)).count(20).offset(7, 3, 7).extra(0.03).spawn();
                }
            }
            // le mur passe : dégâts
            if (e >= 0 && e <= 46) {
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    double amount = d <= core ? dmg : dmg * (1 - 0.7 * (d - core) / Math.max(1, R - core));
                    le.setNoDamageTicks(0);
                    le.damage(amount, DamageSource.builder(DamageType.SONIC_BOOM).withCausingEntity(p).withDirectEntity(p).build());
                    Location at = le.getLocation().add(0, 1, 0);
                    w.spawnParticle(Particle.END_ROD, at, 40, 0.3, 0.6, 0.3, 0.3);
                    w.spawnParticle(Particle.DUST, at, 30, 0.5, 0.8, 0.5, 0, purple);
                    w.playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.6f);
                }
                for (Player o : w.getPlayers()) {
                    double d = o.getLocation().distance(c);
                    if (d <= r && d <= R && passed.add(o.getUniqueId())) {
                        o.playSound(o.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.3f);
                        o.playSound(o.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 0.5f);
                        o.playSound(o.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
                    }
                }
            }
            // après : brume violette autour du centre
            if (k > 50 && k < 150 && k % 4 == 0) {
                w.spawnParticle(Particle.DUST, c, 50, 22, 8, 22, 0, purple, true);
                w.spawnParticle(Particle.DUST, c, 20, 18, 6, 18, 0, ink, true);
                w.spawnParticle(Particle.END_ROD, c, 15, 18, 10, 18, 0.02, null, true);
            }
            if (k >= 150) {
                task[0].cancel();
                for (UUID id : new ArrayList<>(night)) {
                    Player o = Bukkit.getPlayer(id);
                    if (o != null) o.resetPlayerTime();
                }
                night.clear();
                boom(c, R + 60, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.8f);
                chat(c, p, plugin.msg("weapons.hollow-purple.summary").replace("<n>", String.valueOf(done.size())));
            }
        }, 0L, 1L);
    }

    // ================================================================= FÛGA

    /*
     * 1. Un cercle de feu en spirale s'ouvre au sol, les deux mains s'embrasent, des éclats de lumière (étoiles)
     *    apparaissent autour du lanceur.
     * 2. Un arc de flammes se forme, la flèche apparaît et chauffe à blanc.
     * 3. Tir : une boule de feu entourée d'anneaux, suivie de longs rubans jaunes / orange qui ondulent.
     * 4. Impact : l'écran est envahi par le feu, boule de feu géante, ciel noir de cendres, roches qui sortent du sol,
     *    débris, puis une immense colonne de feu ; le dôme de flammes balaie 200 blocs.
     */
    private void fuga(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.fuga.chant");
        int chant = 84;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, chant, 4, false, false, false));
        Particle.DustOptions ember = new Particle.DustOptions(EMBER, 1.5f), fire = new Particle.DustOptions(FIRE, 1.7f),
                gold = new Particle.DustOptions(GOLD, 1.3f), hot = new Particle.DustOptions(Color.fromRGB(255, 245, 200), 1.4f),
                flareD = new Particle.DustOptions(Color.fromRGB(255, 170, 40), 1.1f), flareC = new Particle.DustOptions(Color.fromRGB(255, 250, 220), 1.6f),
                dark = new Particle.DustOptions(Color.fromRGB(20, 10, 10), 1.2f);
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
            if (!lines.isEmpty() && k % 40 == 0 && k / 40 < lines.size()) {
                chat(base, p, lines.get(k / 40));
                boom(base, 64, Sound.BLOCK_FIRE_AMBIENT, 2f, 0.5f);
            }
            if (k == 0) {
                boom(base, 128, Sound.ITEM_FIRECHARGE_USE, 2f, 0.5f);
                boom(base, 128, Sound.ENTITY_BLAZE_AMBIENT, 1.5f, 0.5f);
                boom(base, 128, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.6f);
            }
            if (k % 12 == 6) boom(base, 48, Sound.BLOCK_FIRE_AMBIENT, 1.5f, 0.6f);
            if (k == 20) boom(base, 96, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.5f);
            if (k == 44) boom(base, 96, Sound.ITEM_CROSSBOW_LOADING_START, 2f, 0.5f);
            if (k == 58) boom(base, 96, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 2f, 0.5f);
            if (k == 72) {
                boom(base, 128, Sound.ITEM_CROSSBOW_LOADING_END, 2f, 0.5f);
                boom(base, 128, Sound.ENTITY_BLAZE_BURN, 2f, 0.5f);
            }
            if (k == 50) shake(base, 35, 34, 1.4);
            Location feet = p.getLocation(), eye = p.getEyeLocation();
            Vector dir = eye.getDirection().normalize(), rt = right(dir), up = rt.clone().crossProduct(dir).normalize();
            double grow = Math.min(1, k / 24.0);
            // ---- cercle de feu en spirale au sol (anneaux qui tournent en sens inverse)
            for (int i = 0; i < 4; i++) {
                double rr = (1.1 + i * 0.95) * grow;
                int n = 18 + i * 8;
                for (int j = 0; j < n; j++) {
                    if ((j + k) % 2 == 1) continue;
                    double a = Math.PI * 2 * j / n + (i % 2 == 0 ? 1 : -1) * k * (0.09 - i * 0.012);
                    double wob = 0.18 * Math.sin(a * 5 + k * 0.3);
                    Location at = base.clone().add(Math.cos(a) * (rr + wob), 0.08, Math.sin(a) * (rr + wob));
                    if (j % 3 == 0) w.spawnParticle(Particle.FLAME, at, 1, 0, 0, 0, 0.004);
                    else w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, j % 3 == 1 ? fire : gold);
                }
            }
            // arcs de flammes qui tournent autour
            for (int arc = 0; arc < 3; arc++) {
                double a0 = Math.PI * 2 * arc / 3 + k * 0.15;
                for (double s = 0; s < 1.1; s += 0.12) {
                    double a = a0 + s, rr = 5.3 * grow;
                    w.spawnParticle(Particle.FLAME, base.clone().add(Math.cos(a) * rr, 0.2 + s * 0.6, Math.sin(a) * rr), 1, 0, 0, 0, 0);
                }
            }
            // braises qui montent, chaleur
            w.spawnParticle(Particle.LAVA, base, 1, 2.5 * grow, 0.1, 2.5 * grow, 0);
            if (k % 2 == 0) w.spawnParticle(Particle.SMALL_FLAME, base.clone().add(0, 0.5, 0), 4, 3 * grow, 0.4, 3 * grow, 0.03);
            if (k % 3 == 0) w.spawnParticle(Particle.WHITE_SMOKE, base.clone().add(0, 0.5, 0), 3, 2.5, 0.5, 2.5, 0.02);
            // ---- les deux mains en feu
            double yaw = Math.toRadians(feet.getYaw());
            for (int side = -1; side <= 1; side += 2) {
                Location hand = feet.clone().add(-Math.cos(yaw) * 0.38 * side - Math.sin(yaw) * 0.25, 0.95, -Math.sin(yaw) * 0.38 * side + Math.cos(yaw) * 0.25);
                if (k > 50) hand = eye.clone().add(dir.clone().multiply(0.9)).add(rt.clone().multiply(0.25 * side)).add(0, -0.25, 0);
                w.spawnParticle(Particle.FLAME, hand, 4, 0.1, 0.15, 0.1, 0.02);
                w.spawnParticle(Particle.DUST, hand, 2, 0.12, 0.15, 0.12, 0, k % 2 == 0 ? fire : ember);
                if (k % 4 == 0) w.spawnParticle(Particle.LAVA, hand, 1, 0.05, 0.05, 0.05, 0);
            }
            // ---- éclats de lumière (étoiles) et croix sombres
            if (k > 20 && k % 7 == 0) {
                Location at = eye.clone().add(rnd.nextDouble(-2.5, 2.5), rnd.nextDouble(-0.8, 2.5), rnd.nextDouble(-2.5, 2.5));
                flare(w, at, rt, rnd.nextDouble(0.8, 2.2), flareD, flareC);
            }
            if (k == 40 || k == 62 || k == 78) flare(w, eye.clone().add(dir.clone().multiply(0.8)).add(0, -0.3, 0), rt, 7, flareD, flareC);
            if (k > 30 && k % 11 == 0) {
                Location at = eye.clone().add(rnd.nextDouble(-2, 2), rnd.nextDouble(-0.5, 2), rnd.nextDouble(-2, 2));
                for (double y = -1.2; y <= 1.2; y += 0.12) w.spawnParticle(Particle.DUST, at.clone().add(0, y, 0), 1, 0, 0, 0, 0, dark);
                for (double x = -0.5; x <= 0.5; x += 0.12) w.spawnParticle(Particle.DUST, at.clone().add(rt.clone().multiply(x)), 1, 0, 0, 0, 0, dark);
            }
            // ---- piliers de feu qui jaillissent autour
            if (k == 24 || k == 46 || k == 66) {
                for (int n = 0; n < 6; n++) {
                    double a = Math.PI * 2 * n / 6 + k;
                    Location at = base.clone().add(Math.cos(a) * 6.5, 0, Math.sin(a) * 6.5);
                    w.spawnParticle(Particle.FLAME, at.clone().add(0, 2.5, 0), 70, 0.25, 2.5, 0.25, 0.02, null, true);
                    w.spawnParticle(Particle.LAVA, at, 6, 0.3, 0.2, 0.3, 0, null, true);
                }
                boom(base, 64, Sound.ITEM_FIRECHARGE_USE, 1.5f, 0.7f);
            }
            // ---- l'arc de feu (plus grand), la corde se tend, la flèche chauffe à blanc
            if (k >= 40) {
                double bg = Math.min(1, (k - 40) / 16.0), pull = Math.min(1, Math.max(0, (k - 52) / 26.0));
                Location c = eye.clone().add(dir.clone().multiply(1.3)).add(up.clone().multiply(-0.2));
                for (double s = -1; s <= 1.001; s += 0.07) {
                    if (Math.abs(s) > bg) continue;
                    Location at = c.clone().add(up.clone().multiply(s * 1.8)).add(dir.clone().multiply(0.6 * (1 - s * s) - 0.25));
                    w.spawnParticle(Particle.FLAME, at, 1, 0.02, 0.02, 0.02, 0, null, true);
                    if (((int) Math.round(s * 100)) % 3 == 0) w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, ember, true);
                }
                if (bg >= 1) {
                    Location nock = c.clone().add(dir.clone().multiply(-0.25 - pull * 1.0));
                    for (int side = -1; side <= 1; side += 2) {
                        Location tip = c.clone().add(up.clone().multiply(side * 1.8)).add(dir.clone().multiply(-0.25));
                        Vector seg = nock.toVector().subtract(tip.toVector());
                        for (double s = 0; s <= 1; s += 0.08) w.spawnParticle(Particle.DUST, tip.clone().add(seg.clone().multiply(s)), 1, 0, 0, 0, 0, gold, true);
                    }
                    double len = 0.6 + pull * 2.8;
                    for (double s = 0; s <= len; s += 0.15) w.spawnParticle(Particle.DUST, nock.clone().add(dir.clone().multiply(s)), 1, 0, 0, 0, 0, pull > 0.7 ? hot : fire, true);
                    Location head = nock.clone().add(dir.clone().multiply(len));
                    w.spawnParticle(Particle.FLAME, head, 5, 0.08, 0.08, 0.08, 0.02, null, true);
                    if (pull > 0.6) w.spawnParticle(Particle.END_ROD, head, 2, 0.05, 0.05, 0.05, 0.01, null, true);
                    // spirale de feu autour de la flèche
                    for (int sp = 0; sp < 2; sp++) {
                        double a = k * 0.6 + sp * Math.PI;
                        for (double s = 0; s <= len; s += 0.35) {
                            Vector o = rt.clone().multiply(Math.cos(a + s * 2) * 0.3).add(up.clone().multiply(Math.sin(a + s * 2) * 0.3));
                            w.spawnParticle(Particle.SMALL_FLAME, nock.clone().add(dir.clone().multiply(s)).add(o), 1, 0, 0, 0, 0, null, true);
                        }
                    }
                }
            }
            if (k >= chant) {
                task[0].cancel();
                casting.remove(p.getUniqueId());
                chat(base, p, plugin.msg("weapons.fuga.final"));
                boom(base, 260, Sound.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
                boom(base, 260, Sound.ENTITY_GHAST_SHOOT, 2f, 0.6f);
                boom(base, 260, Sound.ITEM_TRIDENT_RIPTIDE_3, 2f, 0.6f);
                boom(base, 260, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.3f);
                shake(base, 70, 14, 4.5);
                Location c = eye.clone().add(dir.clone().multiply(1.6));
                flare(w, c, rt, 12, flareD, flareC);
                w.spawnParticle(Particle.FLAME, c, 160, 0.2, 0.2, 0.2, 0.45, null, true);
                // onde de feu au départ
                for (int n = 0; n < 40; n++) {
                    double a = Math.PI * 2 * n / 40;
                    w.spawnParticle(Particle.FLAME, base.clone().add(Math.cos(a) * 1.5, 0.3, Math.sin(a) * 1.5), 0, Math.cos(a), 0.05, Math.sin(a), 0.5, null, true);
                }
                fugaFly(p, c.clone().add(dir.clone().multiply(1.5)), dir);
            }
        }, 0L, 1L);
    }

    private void fugaFly(Player p, Location start, Vector dir) {
        World w = start.getWorld();
        double range = cfg("fuga.range", 140), speed = 3.4;
        Particle.DustOptions fire = new Particle.DustOptions(FIRE, 2.4f), gold = new Particle.DustOptions(GOLD, 2.2f),
                yellow = new Particle.DustOptions(Color.fromRGB(255, 235, 90), 2.6f), ember = new Particle.DustOptions(EMBER, 2.0f),
                hot = new Particle.DustOptions(Color.fromRGB(255, 250, 225), 2.0f), flareD = new Particle.DustOptions(Color.fromRGB(255, 170, 40), 1.2f);
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
            for (double s = 0; s <= step; s += 0.4) {
                Location at = from.clone().add(dir.clone().multiply(s));
                double d = dist[0] + s;
                // trois rubans qui ondulent
                for (int j = 0; j < 3; j++) {
                    double ph = d * 0.35 + j * 2.1 + k * 0.2;
                    Vector o = rt.clone().multiply(Math.sin(ph) * 1.1).add(up.clone().multiply(Math.cos(ph * 0.8) * 0.7));
                    w.spawnParticle(Particle.DUST, at.clone().add(o), 1, 0.05, 0.05, 0.05, 0, j == 0 ? yellow : j == 1 ? fire : gold, true);
                }
                w.spawnParticle(Particle.DUST, at, 1, 0.08, 0.08, 0.08, 0, hot, true);
                if (hitAt == null && !targets(p, at, 1.8).isEmpty()) hitAt = at;
                tail.add(0, at.clone());
            }
            while (tail.size() > 70) tail.remove(tail.size() - 1);
            // longue traîne qui s'efface
            for (int i = 8; i < tail.size(); i += 3) {
                Location l = tail.get(i);
                w.spawnParticle(Particle.DUST, l, 1, 0.5, 0.35, 0.5, 0, i % 2 == 0 ? fire : ember, true);
                if (i % 9 == 0) w.spawnParticle(Particle.FLAME, l, 1, 0.3, 0.2, 0.3, 0.01, null, true);
            }
            if (hitAt != null) {
                pos.set(hitAt.getX(), hitAt.getY(), hitAt.getZ());
                stop = true;
            } else pos.add(dir.clone().multiply(step));
            dist[0] += step;
            // la tête : boule de feu + anneaux qui tournent + étoile
            orb(w, pos, 1.1, 34, gold, k * 0.4, true);
            orb(w, pos, 0.55, 12, hot, -k * 0.6, true);
            ring(w, pos, 1.7, 26, rt, up, fire, k * 0.5, true);
            ring(w, pos, 1.5, 24, up, dir, yellow, -k * 0.6, true);
            w.spawnParticle(Particle.FLAME, pos, 16, 0.4, 0.4, 0.4, 0.04, null, true);
            if (k % 3 == 0) flare(w, pos, rt, 3, flareD, hot);
            w.spawnParticle(Particle.FALLING_LAVA, pos, 3, 0.8, 0.4, 0.8, 0, null, true);
            if (k % 2 == 0) w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, from, 3, 0.4, 0.4, 0.4, 0.01, null, true);
            // sol brûlé sous la flèche
            Location g = ground(pos.clone().add(0, -1, 0));
            if (g != null && pos.getY() - g.getY() < 10) {
                w.spawnParticle(Particle.FLAME, g, 6, 0.8, 0.05, 0.8, 0.02, null, true);
                w.spawnParticle(Particle.LAVA, g, 1, 0.5, 0, 0.5, 0, null, true);
            }
            if (k % 3 == 0) {
                w.playSound(pos, Sound.ENTITY_BLAZE_SHOOT, 3f, 0.6f);
                w.playSound(pos, Sound.ITEM_FIRECHARGE_USE, 2f, 0.5f);
            }
            if (k % 5 == 0) shake(pos, 30, 5, 1.8);
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
        // bords de l'écran rougis
        for (Player o : w.getPlayers()) {
            if (o.getLocation().distanceSquared(c) > sq(R + 40) || o.getWorldBorder() != null) continue;
            WorldBorder b = Bukkit.createWorldBorder();
            b.setCenter(o.getLocation());
            b.setSize(10_000);
            b.setWarningDistance(20_000);
            o.setWorldBorder(b);
            borders.put(o.getUniqueId(), b);
        }
        Particle.DustOptions fire = new Particle.DustOptions(FIRE, 3.6f), gold = new Particle.DustOptions(GOLD, 2.8f),
                ember = new Particle.DustOptions(EMBER, 3.2f), ash = new Particle.DustOptions(Color.fromRGB(35, 30, 30), 4f),
                red = new Particle.DustOptions(Color.fromRGB(255, 30, 0), 1.2f), flareD = new Particle.DustOptions(Color.fromRGB(255, 120, 30), 1.4f),
                flareC = new Particle.DustOptions(Color.fromRGB(255, 240, 200), 2f);
        ParticleBuilder wa = dust(FIRE, 4f), wb = dust(EMBER, 3.6f), wc = dust(GOLD, 2.6f), wd = dust(Color.fromRGB(255, 240, 160), 2.2f);
        Set<UUID> done = new HashSet<>();
        Map<UUID, Integer> passedAt = new HashMap<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // ---- EXPLOSION
        boom(c, R + 60, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 4f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_BLAZE_DEATH, 2f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 16, 6, 4, 6, 0, null, true);
        w.spawnParticle(Particle.FLAME, c, 1500, 0.5, 0.5, 0.5, 1.3, null, true);
        w.spawnParticle(Particle.LAVA, c, 300, 6, 3, 6, 0, null, true);
        w.spawnParticle(Particle.LARGE_SMOKE, c, 400, 2, 2, 2, 0.7, null, true);
        w.spawnParticle(Particle.DUST, c, 500, 10, 7, 10, 0, fire, true);
        blast(c, new Material[]{Material.ORANGE_STAINED_GLASS, Material.RED_STAINED_GLASS, Material.YELLOW_STAINED_GLASS}, 40, 9, 22, 16);
        debris(c, 55, 2.0, Particle.FLAME, Material.MAGMA_BLOCK);
        shards(c, 40, 22, 160);
        lightning(c, 60, 6, 40);
        shake(c, R + 40, 80, 8);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
            double u2 = Math.max(0, Math.min(1, (k - 6) / 40.0)), r2 = R * (1 - Math.pow(1 - u2, 3));
            if (k == 6 || k == 14 || k == 26 || k == 40) {
                boom(c, R + 60, k == 14 ? Sound.ENTITY_DRAGON_FIREBALL_EXPLODE : Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.5f + k * 0.005f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(rnd.nextDouble(-16, 16), rnd.nextDouble(0, 8), rnd.nextDouble(-16, 16)), 4, 5, 3, 5, 0, null, true);
                w.spawnParticle(Particle.FLAME, c, 300, 6, 3, 6, 0.6, null, true);
            }
            // ---- la colonne de feu géante (vue de très loin)
            if (k == 18) {
                pillar(c, new Material[]{Material.ORANGE_STAINED_GLASS, Material.RED_STAINED_GLASS, Material.MAGMA_BLOCK}, new float[]{10, 7, 3.2f}, 160, 10, 90, 22);
                boom(c, R + 60, Sound.ENTITY_BLAZE_SHOOT, 4f, 0.4f);
                boom(c, R + 60, Sound.ITEM_TRIDENT_THUNDER, 3f, 0.5f);
            }
            if (k >= 18 && k < 130) {
                if (k % 2 == 0) {
                    double top = Math.min(160, (k - 18) * 16.0);
                    for (double y = 0; y < top; y += 4) {
                        double a = y * 0.12 + k * 0.25;
                        w.spawnParticle(Particle.FLAME, c.getX() + Math.cos(a) * 6, c.getY() + y, c.getZ() + Math.sin(a) * 6, 2, 0.3, 1, 0.3, 0.02, null, true);
                        w.spawnParticle(Particle.DUST, c.getX() - Math.cos(a) * 6, c.getY() + y, c.getZ() - Math.sin(a) * 6, 1, 0.3, 1, 0.3, 0, y % 12 < 4 ? gold : fire, true);
                    }
                    // feu à la base
                    w.spawnParticle(Particle.FLAME, c.clone().add(0, 1, 0), 40, 7, 1, 7, 0.06, null, true);
                }
                // éclats de lumière horizontaux sur la colonne
                if (k % 9 == 0) flare(w, c.clone().add(0, rnd.nextDouble(10, 120), 0), new Vector(1, 0, rnd.nextDouble(-0.5, 0.5)), rnd.nextDouble(12, 26), flareD, flareC);
            }
            // ---- champignon de fumée et de feu au-dessus du cratère
            if (k < 100 && k % 2 == 0) {
                double h = Math.min(60, 8 + k * 1.2), major = Math.min(26, 6 + k * 0.5), minor = Math.min(9, 3 + k * 0.15);
                for (int n = 0; n < 48; n++) {
                    double a = Math.PI * 2 * n / 48 + k * 0.04, b = (n * 2.39996) % (Math.PI * 2);
                    double x = (major + minor * Math.cos(b)) * Math.cos(a), z = (major + minor * Math.cos(b)) * Math.sin(a), y = h + minor * Math.sin(b);
                    w.spawnParticle(Particle.DUST, c.getX() + x, c.getY() + y, c.getZ() + z, 1, 1, 1, 1, 0, n % 3 == 0 ? ash : n % 3 == 1 ? fire : ember, true);
                }
                w.spawnParticle(Particle.LARGE_SMOKE, c.clone().add(0, h, 0), 30, major * 0.6, minor * 0.6, major * 0.6, 0.03, null, true);
            }
            // ---- le dôme de feu : mur de flammes puis mur doré
            if (k <= 46 && k % 2 == 0) {
                wall(c, Math.max(1, r), R, wa, wb, Particle.FLAME);
                if (k >= 6) wall(c, Math.max(1, r2), R, wc, wd, null);
            }
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
                    w.spawnParticle(Particle.LAVA, at, 8, 0.3, 0.4, 0.3, 0);
                    w.playSound(at, Sound.ENTITY_GENERIC_BURN, 1.5f, 0.6f);
                }
            }
            // ---- l'écran est envahi par le feu quand l'onde passe, puis ciel de cendres et braises
            for (Player o : w.getPlayers()) {
                double d = o.getLocation().distance(c);
                if (d > R + 20) continue;
                if (d <= r && !passedAt.containsKey(o.getUniqueId())) {
                    passedAt.put(o.getUniqueId(), k);
                    o.playSound(o.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
                    o.playSound(o.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.5f);
                    o.playSound(o.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 0.5f);
                }
                Integer at = passedAt.get(o.getUniqueId());
                if (at != null && k - at < 16) {
                    int n = 60 - (k - at) * 3;
                    screen(o, k % 2 == 0 ? FIRE : GOLD, (k - at) % 3 == 0 ? Color.fromRGB(255, 30, 0) : Color.fromRGB(255, 240, 120), n, 4f);
                } else if (at != null && k % 3 == 0 && k < 140) {
                    Location l = o.getLocation();
                    new ParticleBuilder(Particle.ASH).receivers(o).location(l.clone().add(0, 4, 0)).count(40).offset(10, 3, 10).extra(0).spawn();
                    new ParticleBuilder(Particle.DUST).receivers(o).location(l.clone().add(0, 2, 0)).count(6).offset(8, 3, 8).extra(0).data(red).spawn();
                    new ParticleBuilder(Particle.FALLING_LAVA).receivers(o).location(l.clone().add(0, 8, 0)).count(4).offset(8, 1, 8).extra(0).spawn();
                }
            }
            // ---- mer de flammes et fumée noire autour du cratère
            if (k > 30 && k < 170 && k % 3 == 0) {
                w.spawnParticle(Particle.FLAME, c, 40, 18, 0.5, 18, 0.02, null, true);
                w.spawnParticle(Particle.SMALL_FLAME, c, 30, 22, 0.5, 22, 0.02, null, true);
                w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, c, 8, 16, 1, 16, 0.03, null, true);
                w.spawnParticle(Particle.DUST, c.clone().add(0, 12, 0), 30, 25, 8, 25, 0, ash, true);
            }
            if (k >= 170) {
                task[0].cancel();
                for (Map.Entry<UUID, WorldBorder> en : new ArrayList<>(borders.entrySet())) {
                    Player o = Bukkit.getPlayer(en.getKey());
                    if (o != null && o.getWorldBorder() == en.getValue()) o.setWorldBorder(null);
                }
                borders.clear();
                chat(c, p, plugin.msg("weapons.fuga.summary").replace("<n>", String.valueOf(done.size())));
            }
        }, 0L, 1L);
    }
}
