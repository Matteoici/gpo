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
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

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
                if (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
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

    // ================================================================= VIOLET IMAGINAIRE

    private void hollow(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.hollow-purple.chant");
        int chant = 80;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, chant, 3, false, false, false));
        Particle.DustOptions blue = new Particle.DustOptions(BLUE, 1.3f), red = new Particle.DustOptions(RED, 1.3f),
                purple = new Particle.DustOptions(PURPLE, 1.7f), lilac = new Particle.DustOptions(LILAC, 1.1f),
                ring = new Particle.DustOptions(MAGENTA, 1.0f);
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
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            Vector rt = right(dir);
            double conv = k < 52 ? 0 : Math.min(1, (k - 52) / 26.0);
            Location front = eye.clone().add(dir.clone().multiply(1.3 + conv * 0.6));
            double spread = 1.5 * (1 - conv);
            double rb = Math.min(0.5, 0.08 + k * 0.009);
            // incantation
            if (!lines.isEmpty() && k % 12 == 0 && k / 12 < lines.size()) {
                chat(base, p, lines.get(k / 12));
                boom(base, 64, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.4f, 0.5f + 0.12f * (k / 12));
            }
            if (k == 0) {
                boom(base, 96, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.5f);
                boom(base, 96, Sound.BLOCK_CONDUIT_ACTIVATE, 1.5f, 0.7f);
            }
            if (k == 20) boom(base, 96, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.6f);
            if (k == 40) boom(base, 96, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, 2f, 0.6f);
            if (k == 52) boom(base, 128, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 0.5f);
            if (k == 70) boom(base, 128, Sound.BLOCK_END_PORTAL_FRAME_FILL, 2f, 0.5f);
            // cercle magique au sol
            if (k % 2 == 0) {
                for (int n = 0; n < 40; n++) {
                    double a = Math.PI * 2 * n / 40 + k * 0.04;
                    w.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 3.2, 0.08, Math.sin(a) * 3.2), 1, 0, 0, 0, 0, ring);
                    if (n % 2 == 0) w.spawnParticle(Particle.DUST, base.clone().add(Math.cos(-a) * 4.4, 0.08, Math.sin(-a) * 4.4), 1, 0, 0, 0, 0, lilac);
                }
                for (int n = 0; n < 6; n++) {
                    double a = Math.PI * 2 * n / 6 - k * 0.03;
                    for (double s = 3.2; s <= 4.4; s += 0.3) w.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * s, 0.08, Math.sin(a) * s), 1, 0, 0, 0, 0, ring);
                }
            }
            // Bleu (attire) à gauche, Rouge (repousse) à droite
            if (conv < 1) {
                Location b = front.clone().add(rt.clone().multiply(-spread)), r = front.clone().add(rt.clone().multiply(spread));
                orb(w, b, rb, 16, blue, k * 0.4, false);
                orb(w, r, rb, 16, red, -k * 0.4, false);
                for (int n = 0; n < 2; n++) {
                    Vector v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1)).normalize().multiply(1.4);
                    w.spawnParticle(Particle.END_ROD, b.clone().add(v), 0, -v.getX(), -v.getY(), -v.getZ(), 0.09);
                    w.spawnParticle(Particle.FLAME, r, 0, v.getX(), v.getY(), v.getZ(), 0.06);
                }
            }
            // fusion : la sphère violette naît
            if (conv > 0) {
                orb(w, front, 0.15 + conv * 1.1, 34, purple, k * 0.3, false);
                orb(w, front, 0.1 + conv * 0.45, 12, lilac, -k * 0.5, false);
                w.spawnParticle(Particle.ELECTRIC_SPARK, front, 3, 0.4 * conv, 0.4 * conv, 0.4 * conv, 0.05);
                w.spawnParticle(Particle.REVERSE_PORTAL, front, 6, 0.6, 0.6, 0.6, 0.05);
            }
            if (k >= chant) {
                task[0].cancel();
                casting.remove(p.getUniqueId());
                chat(base, p, plugin.msg("weapons.hollow-purple.final"));
                boom(base, 260, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 0.6f);
                boom(base, 260, Sound.ENTITY_WITHER_SHOOT, 2f, 0.5f);
                boom(base, 260, Sound.ITEM_TRIDENT_THUNDER, 2f, 0.7f);
                w.spawnParticle(Particle.END_ROD, front, 60, 0.2, 0.2, 0.2, 0.35, null, true);
                hollowFly(p, front, dir);
            }
        }, 0L, 1L);
    }

    private void hollowFly(Player p, Location start, Vector dir) {
        World w = start.getWorld();
        double range = cfg("hollow-purple.range", 120), speed = 2.4, pathDmg = cfg("hollow-purple.path-damage", 30);
        Particle.DustOptions purple = new Particle.DustOptions(PURPLE, 2.6f), core = new Particle.DustOptions(LILAC, 1.8f),
                trail = new Particle.DustOptions(MAGENTA, 2.0f);
        Location pos = start.clone();
        Set<UUID> hit = new HashSet<>();
        int[] t = {0};
        double[] dist = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            double rs = Math.min(4, 1.3 + k * 0.45);
            RayTraceResult rtb = w.rayTraceBlocks(pos, dir, speed, FluidCollisionMode.NEVER, true);
            boolean stop = false;
            if (rtb != null) {
                pos.set(rtb.getHitPosition().getX(), rtb.getHitPosition().getY(), rtb.getHitPosition().getZ());
                pos.subtract(dir.clone().multiply(1.0));
                stop = true;
            } else pos.add(dir.clone().multiply(speed));
            dist[0] += speed;
            if (dist[0] >= range) stop = true;
            orb(w, pos, rs, 70, purple, k * 0.3, true);
            orb(w, pos, rs * 0.45, 18, core, -k * 0.5, true);
            w.spawnParticle(Particle.END_ROD, pos, 6, 0.3, 0.3, 0.3, 0.02, null, true);
            w.spawnParticle(Particle.REVERSE_PORTAL, pos, 20, rs * 0.6, rs * 0.6, rs * 0.6, 0.1, null, true);
            Location tail = pos.clone().subtract(dir.clone().multiply(rs));
            w.spawnParticle(Particle.DUST, tail, 12, rs * 0.5, rs * 0.5, rs * 0.5, 0, trail, true);
            w.spawnParticle(Particle.WITCH, tail, 6, rs * 0.4, rs * 0.4, rs * 0.4, 0, null, true);
            if (k % 5 == 0) {
                w.playSound(pos, Sound.BLOCK_BEACON_AMBIENT, 3f, 0.5f);
                w.playSound(pos, Sound.ENTITY_BREEZE_WIND_BURST, 2f, 0.5f);
            }
            // ce qui est sur la route est happé
            for (LivingEntity le : targets(p, pos, rs + 0.5)) {
                if (!hit.add(le.getUniqueId())) continue;
                le.setNoDamageTicks(0);
                le.damage(pathDmg, DamageSource.builder(DamageType.SONIC_BOOM).withCausingEntity(p).withDirectEntity(p).build());
                freeze(le, 120, true);
                w.spawnParticle(Particle.END_ROD, le.getLocation().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.2);
            }
            if (stop) {
                task[0].cancel();
                hollowBurst(p, pos.clone());
            }
        }, 0L, 1L);
    }

    private void hollowBurst(Player p, Location c) {
        World w = c.getWorld();
        double R = cfg("hollow-purple.radius", 200), dmg = cfg("hollow-purple.damage", 60), core = cfg("hollow-purple.core-radius", 30);
        List<LivingEntity> list = targets(p, c, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) {
            dist.put(le.getUniqueId(), le.getLocation().distance(c));
            freeze(le, 90, true);
        }
        // le ciel devient nuit pour ceux qui sont dedans
        for (Player o : w.getPlayers()) {
            if (o.getLocation().distanceSquared(c) > sq(R + 40)) continue;
            o.setPlayerTime(18000, false);
            night.add(o.getUniqueId());
        }
        Particle.DustOptions purple = new Particle.DustOptions(PURPLE, 3.2f), lilac = new Particle.DustOptions(LILAC, 2.4f);
        ParticleBuilder wa = dust(PURPLE, 4f), wb = dust(MAGENTA, 3.4f);
        Set<UUID> done = new HashSet<>(), passed = new HashSet<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boom(c, R + 60, Sound.BLOCK_END_PORTAL_SPAWN, 2f, 0.5f);
        boom(c, R + 60, Sound.BLOCK_BELL_RESONATE, 2f, 0.5f);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            if (k < 20) {
                // implosion : la sphère se replie sur elle-même
                double rs = 4.5 * (1 - k / 20.0) + 0.4;
                orb(w, c, rs, 80, purple, k * 0.5, true);
                orb(w, c, rs * 0.5, 20, lilac, -k * 0.7, true);
                for (int n = 0; n < 8; n++) {
                    Vector v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1)).normalize().multiply(9);
                    w.spawnParticle(Particle.END_ROD, c.clone().add(v), 0, -v.getX(), -v.getY(), -v.getZ(), 0.5, null, true);
                }
                w.spawnParticle(Particle.REVERSE_PORTAL, c, 40, 3, 3, 3, 0.4, null, true);
                if (k == 10) boom(c, R + 60, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.5f);
                return;
            }
            if (k == 20) {
                boom(c, R + 60, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.5f);
                boom(c, R + 60, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 3f, 0.5f);
                boom(c, R + 60, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 0.5f);
                boom(c, R + 60, Sound.ENTITY_WITHER_DEATH, 1.2f, 0.6f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 6, 3, 3, 3, 0, null, true);
                w.spawnParticle(Particle.END_ROD, c, 400, 0.5, 0.5, 0.5, 1.4, null, true);
                w.spawnParticle(Particle.DUST, c, 300, 6, 6, 6, 0, purple, true);
                w.spawnParticle(Particle.REVERSE_PORTAL, c, 300, 2, 2, 2, 2.5, null, true);
            }
            int e = k - 20;
            double u = Math.min(1, e / 40.0), r = R * (1 - Math.pow(1 - u, 3));
            // colonne de lumière visible de loin
            if (k % 2 == 0 && k < 90) {
                for (double y = 0; y < 140; y += 1.6) {
                    w.spawnParticle(Particle.DUST, c.getX(), c.getY() + y, c.getZ(), 1, 0.6, 0.4, 0.6, 0, y % 6 < 1.6 ? lilac : purple, true);
                }
                w.spawnParticle(Particle.END_ROD, c.clone().add(0, rnd.nextDouble(0, 140), 0), 4, 0.4, 2, 0.4, 0.02, null, true);
            }
            if (e <= 42 && k % 2 == 0) wall(c, Math.max(1, r), R, wa, wb, Particle.END_ROD);
            // le mur passe : dégâts
            if (e <= 42) {
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    double amount = d <= core ? dmg : dmg * (1 - 0.7 * (d - core) / Math.max(1, R - core));
                    le.setNoDamageTicks(0);
                    le.damage(amount, DamageSource.builder(DamageType.SONIC_BOOM).withCausingEntity(p).withDirectEntity(p).build());
                    Location at = le.getLocation().add(0, 1, 0);
                    w.spawnParticle(Particle.END_ROD, at, 30, 0.3, 0.6, 0.3, 0.25);
                    w.spawnParticle(Particle.DUST, at, 25, 0.5, 0.8, 0.5, 0, purple);
                    w.playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.6f);
                }
                for (Player o : w.getPlayers()) {
                    double d = o.getLocation().distance(c);
                    if (d <= r && d <= R && passed.add(o.getUniqueId())) {
                        o.playSound(o.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.3f);
                        o.playSound(o.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 0.5f);
                    }
                }
            }
            if (k >= 110) {
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

    private void fuga(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        List<String> lines = plugin.getConfig().getStringList("messages.weapons.fuga.chant");
        int chant = 60;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, chant, 3, false, false, false));
        Particle.DustOptions ember = new Particle.DustOptions(EMBER, 1.2f), fire = new Particle.DustOptions(FIRE, 1.4f),
                gold = new Particle.DustOptions(GOLD, 1.0f);
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
            if (!lines.isEmpty() && k % 25 == 0 && k / 25 < lines.size()) {
                chat(base, p, lines.get(k / 25));
                boom(base, 64, Sound.BLOCK_FIRE_AMBIENT, 2f, 0.5f);
            }
            if (k == 0) {
                boom(base, 96, Sound.ITEM_FIRECHARGE_USE, 2f, 0.5f);
                boom(base, 96, Sound.ENTITY_BLAZE_AMBIENT, 1.5f, 0.5f);
            }
            if (k == 15) boom(base, 96, Sound.ITEM_CROSSBOW_LOADING_START, 2f, 0.5f);
            if (k == 32) boom(base, 96, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 2f, 0.5f);
            if (k == 48) {
                boom(base, 128, Sound.ITEM_CROSSBOW_LOADING_END, 2f, 0.5f);
                boom(base, 128, Sound.ENTITY_BLAZE_BURN, 2f, 0.5f);
            }
            Location feet = p.getLocation();
            // tornade de feu autour du lanceur
            for (int h = 0; h < 2; h++) {
                double a0 = k * 0.35 + Math.PI * h;
                for (double y = 0; y < 3.2; y += 0.45) {
                    double rr = 1.4 - y * 0.18, a = a0 + y * 1.2;
                    w.spawnParticle(Particle.FLAME, feet.clone().add(Math.cos(a) * rr, y, Math.sin(a) * rr), 1, 0, 0, 0, 0);
                }
            }
            if (k % 3 == 0) w.spawnParticle(Particle.LAVA, feet, 1, 0.8, 0.1, 0.8, 0);
            // anneau brûlant au sol
            if (k % 2 == 0) for (int n = 0; n < 28; n++) {
                double a = Math.PI * 2 * n / 28 - k * 0.05;
                w.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 2.6, 0.08, Math.sin(a) * 2.6), 1, 0, 0, 0, 0, n % 2 == 0 ? ember : gold);
            }
            // l'arc de feu se forme, la corde se tend, la flèche apparaît
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            Vector rt = right(dir), up = rt.clone().crossProduct(dir).normalize();
            double grow = Math.min(1, k / 30.0), pull = Math.min(1, Math.max(0, (k - 15) / 35.0));
            Location c = eye.clone().add(dir.clone().multiply(1.1)).add(up.clone().multiply(-0.15));
            for (double s = -1; s <= 1.001; s += 0.1) {
                if (Math.abs(s) > grow) continue;
                Location at = c.clone().add(up.clone().multiply(s * 1.3)).add(dir.clone().multiply(0.45 * (1 - s * s) - 0.2));
                w.spawnParticle(Particle.FLAME, at, 1, 0.02, 0.02, 0.02, 0);
                if (((int) Math.round(s * 10)) % 3 == 0) w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, ember);
            }
            if (grow >= 1) {
                Location nock = c.clone().add(dir.clone().multiply(-0.2 - pull * 0.8));
                for (int side = -1; side <= 1; side += 2) {
                    Location tip = c.clone().add(up.clone().multiply(side * 1.3)).add(dir.clone().multiply(-0.2));
                    Vector seg = nock.toVector().subtract(tip.toVector());
                    for (double s = 0; s <= 1; s += 0.12) w.spawnParticle(Particle.SMALL_FLAME, tip.clone().add(seg.clone().multiply(s)), 1, 0, 0, 0, 0);
                }
                if (pull > 0) {
                    double len = 0.5 + pull * 2.2;
                    for (double s = 0; s <= len; s += 0.2) w.spawnParticle(Particle.DUST, nock.clone().add(dir.clone().multiply(s)), 1, 0, 0, 0, 0, fire);
                    Location head = nock.clone().add(dir.clone().multiply(len));
                    w.spawnParticle(Particle.FLAME, head, 3, 0.06, 0.06, 0.06, 0.01);
                    if (rnd.nextInt(3) == 0) w.spawnParticle(Particle.LAVA, head, 1, 0, 0, 0, 0);
                }
            }
            if (k >= chant) {
                task[0].cancel();
                casting.remove(p.getUniqueId());
                chat(base, p, plugin.msg("weapons.fuga.final"));
                boom(base, 260, Sound.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
                boom(base, 260, Sound.ENTITY_GHAST_SHOOT, 2f, 0.6f);
                boom(base, 260, Sound.ITEM_TRIDENT_RIPTIDE_3, 2f, 0.6f);
                w.spawnParticle(Particle.FLAME, c, 80, 0.2, 0.2, 0.2, 0.3, null, true);
                fugaFly(p, c.clone().add(dir.clone().multiply(1.5)), dir);
            }
        }, 0L, 1L);
    }

    private void fugaFly(Player p, Location start, Vector dir) {
        World w = start.getWorld();
        double range = cfg("fuga.range", 140), speed = 4;
        Particle.DustOptions fire = new Particle.DustOptions(FIRE, 2.0f), gold = new Particle.DustOptions(GOLD, 1.4f);
        Vector rt = right(dir), up = rt.clone().crossProduct(dir).normalize();
        Location pos = start.clone();
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
            // la flèche : traînée + spirale de feu
            for (double s = 0; s <= step; s += 0.5) {
                Location at = from.clone().add(dir.clone().multiply(s));
                double a = (dist[0] + s) * 0.9;
                w.spawnParticle(Particle.DUST, at, 1, 0.05, 0.05, 0.05, 0, (int) (s * 2) % 2 == 0 ? fire : gold, true);
                w.spawnParticle(Particle.FLAME, at.clone().add(rt.clone().multiply(Math.cos(a) * 0.6)).add(up.clone().multiply(Math.sin(a) * 0.6)), 1, 0, 0, 0, 0, null, true);
                w.spawnParticle(Particle.SMALL_FLAME, at.clone().add(rt.clone().multiply(-Math.cos(a) * 0.6)).add(up.clone().multiply(-Math.sin(a) * 0.6)), 1, 0, 0, 0, 0, null, true);
                if (hitAt == null && !targets(p, at, 1.8).isEmpty()) hitAt = at;
            }
            if (hitAt != null) {
                pos.set(hitAt.getX(), hitAt.getY(), hitAt.getZ());
                stop = true;
            } else pos.add(dir.clone().multiply(step));
            dist[0] += step;
            w.spawnParticle(Particle.FLAME, pos, 14, 0.2, 0.2, 0.2, 0.03, null, true);
            w.spawnParticle(Particle.LAVA, pos, 2, 0.1, 0.1, 0.1, 0, null, true);
            if (k % 2 == 0) w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, from, 2, 0.2, 0.2, 0.2, 0.01, null, true);
            if (k % 3 == 0) w.playSound(pos, Sound.ENTITY_BLAZE_SHOOT, 2f, 0.6f);
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
            freeze(le, 80, false);
        }
        // écran rougi pour ceux qui sont dedans
        for (Player o : w.getPlayers()) {
            if (o.getLocation().distanceSquared(c) > sq(R + 40) || o.getWorldBorder() != null) continue;
            WorldBorder b = Bukkit.createWorldBorder();
            b.setCenter(o.getLocation());
            b.setSize(10_000);
            b.setWarningDistance(20_000);
            o.setWorldBorder(b);
            borders.put(o.getUniqueId(), b);
        }
        Particle.DustOptions fire = new Particle.DustOptions(FIRE, 3.2f), gold = new Particle.DustOptions(GOLD, 2.4f),
                ember = new Particle.DustOptions(EMBER, 3.0f);
        ParticleBuilder wa = dust(FIRE, 4f), wb = dust(EMBER, 3.6f);
        Set<UUID> done = new HashSet<>(), passed = new HashSet<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boom(c, R + 60, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.5f);
        boom(c, R + 60, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 3f, 0.6f);
        boom(c, R + 60, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 3f, 0.6f);
        boom(c, R + 60, Sound.ENTITY_BLAZE_DEATH, 1.5f, 0.5f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 8, 4, 3, 4, 0, null, true);
        w.spawnParticle(Particle.FLAME, c, 500, 0.5, 0.5, 0.5, 0.9, null, true);
        w.spawnParticle(Particle.LAVA, c, 120, 4, 2, 4, 0, null, true);
        w.spawnParticle(Particle.LARGE_SMOKE, c, 200, 1, 1, 1, 0.5, null, true);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
            // colonne de feu et champignon
            if (k < 80) {
                double top = Math.min(110, 15 + k * 2.5);
                for (double y = 0; y < top; y += 1.6) {
                    double rr = 1.2 + y * 0.03;
                    w.spawnParticle(Particle.DUST, c.getX(), c.getY() + y, c.getZ(), 1, rr, 0.5, rr, 0, y % 5 < 1.6 ? gold : (y % 3 < 1.6 ? ember : fire), true);
                }
                w.spawnParticle(Particle.FLAME, c.clone().add(0, rnd.nextDouble(0, top), 0), 6, 1.2, 2, 1.2, 0.05, null, true);
                if (k % 2 == 0) {
                    double cap = Math.min(22, 4 + k * 0.5);
                    for (int n = 0; n < 36; n++) {
                        double a = Math.PI * 2 * n / 36 + k * 0.08;
                        Location at = c.clone().add(Math.cos(a) * cap, top, Math.sin(a) * cap);
                        w.spawnParticle(n % 3 == 0 ? Particle.LARGE_SMOKE : Particle.FLAME, at, 1, 0.5, 0.5, 0.5, 0.02, null, true);
                    }
                }
            }
            // anneau de feu au sol qui s'étend
            if (k % 2 == 0 && k <= 42) {
                double gr = Math.min(R, r);
                for (int n = 0; n < 60; n++) {
                    double a = Math.PI * 2 * n / 60 + k * 0.03;
                    w.spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * Math.min(gr, 30), 0.2, Math.sin(a) * Math.min(gr, 30)), 1, 0.2, 0.1, 0.2, 0.02, null, true);
                }
            }
            if (k <= 42 && k % 2 == 0) wall(c, Math.max(1, r), R, wa, wb, Particle.FLAME);
            if (k <= 42) {
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    double amount = d <= core ? dmg : dmg * (1 - 0.7 * (d - core) / Math.max(1, R - core));
                    le.setNoDamageTicks(0);
                    le.damage(amount, DamageSource.builder(DamageType.MAGIC).withCausingEntity(p).withDirectEntity(p).build());
                    le.setFireTicks(burn);
                    Location at = le.getLocation().add(0, 1, 0);
                    w.spawnParticle(Particle.FLAME, at, 40, 0.4, 0.7, 0.4, 0.12);
                    w.spawnParticle(Particle.LAVA, at, 6, 0.3, 0.4, 0.3, 0);
                    w.playSound(at, Sound.ENTITY_GENERIC_BURN, 1.5f, 0.6f);
                }
                for (Player o : w.getPlayers()) {
                    double d = o.getLocation().distance(c);
                    if (d <= r && d <= R && passed.add(o.getUniqueId())) {
                        o.playSound(o.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
                        o.playSound(o.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.5f);
                    }
                }
            }
            // braises et fumée qui retombent
            if (k > 40 && k < 100 && k % 3 == 0) {
                w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, c, 6, 6, 1, 6, 0.02, null, true);
                w.spawnParticle(Particle.LAVA, c, 6, 10, 2, 10, 0, null, true);
            }
            if (k >= 100) {
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
