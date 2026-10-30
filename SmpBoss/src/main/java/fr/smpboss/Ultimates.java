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
import org.bukkit.entity.TextDisplay;
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
    final Set<UUID> casting = new HashSet<>();
    private final NamespacedKey jumpKey, gravKey;

    /** entités figées */
    private record Frozen(LivingEntity e, Location at, int end, boolean frost, boolean ai) {}
    private final Map<UUID, Frozen> frozen = new HashMap<>();
    private BukkitTask freezeTask;
    /** ciel / teinte posés pendant une attaque, à rendre ensuite */
    private final Map<UUID, WorldBorder> borders = new HashMap<>();
    final Set<UUID> night = new HashSet<>();

    final FugaFx fugaFx;
    final HollowFx hollowFx;
    final WeaponFx weaponFx;

    Ultimates(SmpBoss plugin) {
        this.plugin = plugin;
        this.fugaFx = new FugaFx(plugin, this);
        this.hollowFx = new HollowFx(plugin, this);
        this.weaponFx = new WeaponFx(plugin, this);
        this.jumpKey = new NamespacedKey(plugin, "ult_freeze_jump");
        this.gravKey = new NamespacedKey(plugin, "ult_freeze_gravity");
    }

    double cfg(String path, double def) {
        return plugin.getConfig().getDouble("weapons." + path, def);
    }

    private static double sq(double v) {
        return v * v;
    }

    // ================================================================= lancement

    /** En duel ou dans une arène de duel : interdit (trop fort). Dans l'arène du boss : autorisé (sans rien casser). */
    private boolean forbiddenAt(Player p) {
        return duelCall("inDuel", java.util.UUID.class, p.getUniqueId()) || duelCall("isArena", Location.class, p.getLocation());
    }

    /**
     * Une cible protégée : les joueurs en duel ou dans une arène de duel, et les joueurs dans l'arène du boss
     * (on y tape le Roi et ses monstres, pas ses coéquipiers).
     */
    private boolean shielded(Entity en) {
        if (en instanceof Player && plugin.arenaAt(en.getLocation()) != null) return true;
        if (en instanceof Player pl && duelCall("inDuel", java.util.UUID.class, pl.getUniqueId())) return true;
        return duelCall("isArena", Location.class, en.getLocation());
    }

    private final Map<String, java.lang.reflect.Method> duelMethods = new HashMap<>();

    private boolean duelCall(String method, Class<?> type, Object arg) {
        org.bukkit.plugin.Plugin d = Bukkit.getPluginManager().getPlugin("SmpDuel");
        if (d == null || !d.isEnabled()) return false;
        try {
            java.lang.reflect.Method m = duelMethods.get(method);
            if (m == null || m.getDeclaringClass() != d.getClass()) {
                m = d.getClass().getMethod(method, type);
                duelMethods.put(method, m);
            }
            return (boolean) m.invoke(null, arg);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return false;
        }
    }

    void cast(Player p, String id) {
        String path = Weapons.path(id);
        if (forbiddenAt(p)) {
            p.sendMessage(parse(plugin.msg("weapons.ult-forbidden").replace("<item>", plugin.msg("weapons." + path + ".name"))));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f);
            return;
        }
        if (!casting.add(p.getUniqueId())) return;
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
        p.getPersistentDataContainer().set(cd, PersistentDataType.LONG, now + (long) (cfg(path + ".cooldown-minutes", 15) * 60_000));
        if (Weapons.HOLLOW.equals(id) && plugin.getConfig().getBoolean("weapons.hollow-purple.resource-pack", true)) hollowFx.cast(p);
        else if (Weapons.HOLLOW.equals(id)) hollow(p);
        else if (plugin.getConfig().getBoolean("weapons.fuga.resource-pack", true)) fugaFx.cast(p);
        else fuga(p);
    }

    /** Admin : remet à zéro la recharge des attaques finales d'un joueur. */
    void resetCooldown(Player p) {
        for (String id : new String[]{Weapons.HOLLOW, Weapons.FUGA}) p.getPersistentDataContainer().remove(new NamespacedKey(plugin, "ult_" + id));
    }

    /** Les lignes d'incantation, dans le chat des joueurs autour seulement. */
    void chat(Location c, Player caster, String line) {
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
    List<LivingEntity> targets(Player caster, Location c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        Boss k = plugin.king();
        for (Entity en : c.getWorld().getNearbyEntities(c, r, r, r)) {
            if (!(en instanceof LivingEntity le) || en.equals(caster) || le.isDead()) continue;
            if (en.getScoreboardTags().contains("smp_pet")) continue; // familiers (SmpPets)
            if (en.getLocation().distanceSquared(c) > r * r) continue;
            if (shielded(en)) continue;
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

    // ================================================================= lanceur immobile pendant une attaque finale

    private final Map<UUID, Location> rooted = new HashMap<>();
    private BukkitTask rootTask;

    /** Le lanceur ne peut plus bouger (ni sauter, ni tomber) : il peut juste regarder autour de lui. */
    private final Map<UUID, Boolean> flightBefore = new HashMap<>();
    private final Map<UUID, BukkitTask> flightRestore = new HashMap<>();

    /** Pendant l'attaque on a le droit d'être en l'air (sinon le serveur kick : "Flying is not enabled"). */
    private void allowFlightFor(Player p) {
        BukkitTask old = flightRestore.remove(p.getUniqueId());
        if (old != null) old.cancel();
        flightBefore.putIfAbsent(p.getUniqueId(), p.getAllowFlight());
        p.setAllowFlight(true);
        p.setFlying(false);
    }

    /** Rend l'état de vol d'avant, quelques secondes après (le temps de redescendre). */
    private void restoreFlightLater(Player p, int ticks) {
        BukkitTask old = flightRestore.remove(p.getUniqueId());
        if (old != null) old.cancel();
        flightRestore.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> {
            flightRestore.remove(p.getUniqueId());
            Boolean before = flightBefore.remove(p.getUniqueId());
            if (before == null || !p.isOnline()) return;
            var gm = p.getGameMode();
            if (gm == org.bukkit.GameMode.CREATIVE || gm == org.bukkit.GameMode.SPECTATOR) return;
            if (!before) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
            p.setFallDistance(0);
        }, Math.max(1, ticks)));
    }

    /** Déconnexion / arrêt : on rend tout de suite l'état de vol d'avant (sinon il resterait sauvegardé). */
    void releaseNow(Player p) {
        rooted.remove(p.getUniqueId());
        mod(p, Attribute.MOVEMENT_SPEED, new NamespacedKey(plugin, "ult_root_speed"), false);
        mod(p, Attribute.JUMP_STRENGTH, new NamespacedKey(plugin, "ult_root_jump"), false);
        mod(p, Attribute.GRAVITY, new NamespacedKey(plugin, "ult_root_gravity"), false);
        mod(p, Attribute.FLYING_SPEED, new NamespacedKey(plugin, "ult_root_flyspeed"), false);
        BukkitTask t = flightRestore.remove(p.getUniqueId());
        if (t != null) t.cancel();
        Boolean before = flightBefore.remove(p.getUniqueId());
        var gm = p.getGameMode();
        if (before != null && !before && gm != org.bukkit.GameMode.CREATIVE && gm != org.bukkit.GameMode.SPECTATOR) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
    }

    void root(Player p) {
        allowFlightFor(p);
        NamespacedKey fs = new NamespacedKey(plugin, "ult_root_flyspeed");
        mod(p, Attribute.FLYING_SPEED, fs, true);
        rooted.put(p.getUniqueId(), p.getLocation().clone());
        NamespacedKey sp = new NamespacedKey(plugin, "ult_root_speed"), jp = new NamespacedKey(plugin, "ult_root_jump"), gr = new NamespacedKey(plugin, "ult_root_gravity");
        mod(p, Attribute.MOVEMENT_SPEED, sp, true);
        mod(p, Attribute.JUMP_STRENGTH, jp, true);
        mod(p, Attribute.GRAVITY, gr, true);
        p.setVelocity(new Vector());
        if (rootTask == null) rootTask = Bukkit.getScheduler().runTaskTimer(plugin, this::rootTick, 1L, 1L);
    }

    /** Fait monter doucement le lanceur (il reste immobile en l'air). */
    void lift(Player p, double dy) {
        Location a = rooted.get(p.getUniqueId());
        if (a != null) a.add(0, dy, 0);
    }

    /** Libère le lanceur ; s'il est en l'air, il redescend doucement (chute lente, pas de dégâts). */
    void unroot(Player p, boolean slowFall) {
        rooted.remove(p.getUniqueId());
        mod(p, Attribute.MOVEMENT_SPEED, new NamespacedKey(plugin, "ult_root_speed"), false);
        mod(p, Attribute.JUMP_STRENGTH, new NamespacedKey(plugin, "ult_root_jump"), false);
        mod(p, Attribute.GRAVITY, new NamespacedKey(plugin, "ult_root_gravity"), false);
        mod(p, Attribute.FLYING_SPEED, new NamespacedKey(plugin, "ult_root_flyspeed"), false);
        p.setFallDistance(0);
        p.setFlying(false);
        restoreFlightLater(p, slowFall ? 20 * 9 : 20 * 3);
        if (slowFall && p.isOnline() && !p.isDead()) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 8, 0, false, false, true));
    }

    private void rootTick() {
        for (var it = rooted.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline() || p.isDead() || p.getWorld() != en.getValue().getWorld()) {
                it.remove();
                if (p != null) {
                    mod(p, Attribute.FLYING_SPEED, new NamespacedKey(plugin, "ult_root_flyspeed"), false);
                    if (p.isOnline()) restoreFlightLater(p, 20 * 3);
                    else flightBefore.remove(p.getUniqueId());
                    mod(p, Attribute.MOVEMENT_SPEED, new NamespacedKey(plugin, "ult_root_speed"), false);
                    mod(p, Attribute.JUMP_STRENGTH, new NamespacedKey(plugin, "ult_root_jump"), false);
                    mod(p, Attribute.GRAVITY, new NamespacedKey(plugin, "ult_root_gravity"), false);
                }
                continue;
            }
            Location l = p.getLocation(), a = en.getValue();
            Vector diff = a.toVector().subtract(l.toVector());
            if (diff.lengthSquared() > 2.25) {
                Location back = a.clone();
                back.setYaw(l.getYaw());
                back.setPitch(l.getPitch());
                Arena.allowTeleport(p);
                p.teleport(back);
                p.setVelocity(new Vector());
            } else {
                p.setVelocity(diff.multiply(0.5));
            }
            p.setFallDistance(0);
            if (p.isFlying()) p.setFlying(false);
        }
        if (rooted.isEmpty() && rootTask != null) {
            rootTask.cancel();
            rootTask = null;
        }
    }

    /** Combien on peut monter au-dessus du joueur sans se cogner (max blocs). */
    static double headroom(Player p, double max) {
        Location l = p.getLocation();
        for (int y = 2; y <= (int) max + 2; y++) {
            if (!l.clone().add(0, y, 0).getBlock().isPassable()) return Math.max(0, y - 3);
        }
        return max;
    }

    // ================================================================= figer

    private void freeze(LivingEntity e, int ticks, boolean frost) {
        Boss k = plugin.king();
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
        fugaFx.removeAll();
        hollowFx.removeAll();
        weaponFx.removeAll();
        java.util.Set<UUID> all = new HashSet<>(rooted.keySet());
        all.addAll(flightBefore.keySet());
        for (UUID id : all) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) releaseNow(p);
        }
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

    /**
     * Tremblement d'écran (comme SmpGojo) : de petites secousses "coup reçu" (l'écran s'incline un instant),
     * sans jamais toucher à la vue du joueur : pas d'effet de lag, la souris reste libre.
     * pulses = nombre de secousses, every = ticks entre deux.
     */
    /**
     * Vrai tremblement de caméra : l'écran est secoué dans tous les sens (de moins en moins fort), plus fort près du
     * centre. On revient exactement à la visée de départ à la fin. Réglages : effects.screen-shake / screen-shake-power.
     */
    void quake(Location c, double radius, int ticks, float power) {
        if (!plugin.getConfig().getBoolean("effects.screen-shake", true) || ticks <= 0) return;
        float mul = (float) plugin.getConfig().getDouble("effects.screen-shake-power", 1.0);
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Map<UUID, float[]> off = new HashMap<>();
        int[] n = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = n[0]++;
            boolean last = k >= ticks;
            double fade = Math.pow(1 - Math.min(1, (double) k / ticks), 1.3);
            for (Player o : w.getPlayers()) {
                float[] prev = off.get(o.getUniqueId());
                double d = o.getLocation().distance(c);
                if (d > radius && prev == null) continue;
                float amp = d > radius ? 0 : (float) (power * mul * fade * (1 - 0.8 * d / radius));
                float ny = last ? 0 : (float) ((k % 2 == 0 ? 1 : -1) * amp * rnd.nextDouble(0.5, 1));
                float np = last ? 0 : (float) (rnd.nextDouble(-1, 1) * amp * 0.75);
                float py = prev == null ? 0 : prev[0], pp = prev == null ? 0 : prev[1];
                if (ny == py && np == pp) continue;
                off.put(o.getUniqueId(), new float[]{ny, np});
                Location eye = o.getEyeLocation();
                eye.setYaw(eye.getYaw() + ny - py);
                eye.setPitch(Math.max(-89.5f, Math.min(89.5f, eye.getPitch() + np - pp)));
                Vector dir = eye.getDirection().multiply(10);
                try {
                    o.lookAt(eye.getX() + dir.getX(), eye.getY() + dir.getY(), eye.getZ() + dir.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            if (last) task[0].cancel();
        }, 0L, 1L);
    }

    void shake(Location c, double radius, int pulses, int every) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] n = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (n[0]++ >= pulses) {
                task[0].cancel();
                return;
            }
            for (Player o : w.getPlayers()) {
                if (o.getLocation().distanceSquared(c) > radius * radius) continue;
                o.playHurtAnimation((float) rnd.nextDouble(360));
            }
        }, 0L, Math.max(1, every));
    }

    /**
     * Les dégâts arrivent en plusieurs coups (par défaut 10 coups toutes les 4 ticks) : chaque coup est mortel,
     * donc chaque totem en main saute l'un après l'autre.
     */
    /**
     * Dégâts selon la distance au point d'explosion (zones de config "weapons.<path>.zones") :
     * au centre on meurt, plus on est loin moins ça fait mal. Retourne {dégâts, coups}, ou null si hors de portée.
     */
    private double[] zone(String path, double d) {
        List<java.util.Map<?, ?>> zones = plugin.getConfig().getMapList("weapons." + path + ".zones");
        List<double[]> z = new ArrayList<>();
        for (java.util.Map<?, ?> m : zones) {
            try {
                z.add(new double[]{Double.parseDouble(String.valueOf(m.get("rayon"))), Double.parseDouble(String.valueOf(m.get("degats"))),
                        m.get("coups") == null ? 1 : Double.parseDouble(String.valueOf(m.get("coups")))});
            } catch (RuntimeException ignored) {
            }
        }
        if (z.isEmpty()) z = List.of(new double[]{8, 1000, 10}, new double[]{25, 18, 3}, new double[]{60, 12, 2}, new double[]{120, 7, 1}, new double[]{200, 3, 1});
        z = new ArrayList<>(z);
        z.sort(java.util.Comparator.comparingDouble(a -> a[0]));
        for (double[] a : z) if (d <= a[0]) return new double[]{a[1], Math.max(1, a[2])};
        return null;
    }

    void zoneHit(Player caster, LivingEntity le, double d, DamageType type, String path, int fireTicks) {
        double[] z = zone(path, d);
        if (z == null) return;
        multiHit(caster, le, z[0], (int) z[1], type, path, fireTicks);
    }

    private void multiHit(Player caster, LivingEntity le, double total, DamageType type, String path, int fireTicks) {
        multiHit(caster, le, total, Math.max(1, (int) cfg(path + ".hits", 10)), type, path, fireTicks);
    }

    private void multiHit(Player caster, LivingEntity le, double total, int hitCount, DamageType type, String path, int fireTicks) {
        int hits = Math.max(1, hitCount), every = Math.max(1, (int) cfg(path + ".hit-interval-ticks", 4));
        double each = total / hits;
        int[] n = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (n[0]++ >= hits || !le.isValid() || le.isDead()) {
                task[0].cancel();
                return;
            }
            le.setNoDamageTicks(0);
            le.damage(each, DamageSource.builder(type).withCausingEntity(caster).withDirectEntity(caster).build());
            if (fireTicks > 0) le.setFireTicks(Math.max(le.getFireTicks(), fireTicks));
        }, 0L, every);
    }

    /**
     * Idéogrammes géants qui apparaissent dans le monde (comme dans l'anime : 「蒼」, 「赫」, 「茈」, 「開」...) :
     * ils grossissent d'un coup, flottent, puis s'effacent.
     */
    private void kanji(Location at, String mini, float scale, int life) {
        TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.text(parse(mini));
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setViewRange(6f);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.05f, 0.05f, 0.05f), new Quaternionf()));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(5);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale * 1.15f, scale * 1.15f, scale * 1.15f), new Quaternionf()));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(4);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        }, 7L);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0] += 2;
            if (!d.isValid()) {
                task[0].cancel();
                return;
            }
            int left = life - t[0];
            if (left < 16) d.setTextOpacity((byte) Math.max(20, 255 * left / 16));
            if (left <= 0) {
                d.remove();
                task[0].cancel();
            }
        }, 12L, 2L);
    }

    /** Petit noyau lumineux net (bloc translucide qui tourne, contour lumineux) qui suit une orbe. */
    private BlockDisplay core(Location at, Material m, float size, Color glow) {
        return at.getWorld().spawn(flat(at), BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setBlock(m.createBlockData());
            d.setTransformation(centered(new Quaternionf(), size, size, size, true));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(glow);
            d.setTeleportDuration(1);
            d.setViewRange(4f);
        });
    }

    private static void follow(BlockDisplay d, Location at, int k) {
        if (d == null || !d.isValid()) return;
        Location l = at.clone();
        l.setYaw(k * 14f);
        l.setPitch((float) (Math.sin(k * 0.13) * 70));
        d.teleport(l);
    }

    /**
     * Cratère : un bol creusé au centre de l'impact (10 blocs de profondeur max), qui s'ouvre du centre vers le bord.
     * Les zones protégées (spawn, terrains de guilde, arènes...) sont respectées : chaque bloc passe par la
     * vérification "casser un bloc" des autres plugins. Pas de coffres / spawners / blocs incassables, pas de drops.
     * Le fond est tapissé de quelques blocs brûlés (Fûga) ou d'améthyste / obsidienne pleureuse (Violet).
     */
    void crater(Player caster, Location center, String path, Material[] lining, boolean fire) {
        if (!plugin.getConfig().getBoolean("weapons." + path + ".crater", true)) return;
        double radius = cfg(path + ".crater-radius", 20);
        int depth = (int) Math.min(10, cfg(path + ".crater-depth", 10));
        World w = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY() - 1, cz = center.getBlockZ();
        List<Block> blocks = new ArrayList<>();
        List<Block> floor = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = Math.sqrt(x * x + z * z) / radius;
                if (d > 1) continue;
                int dd = (int) Math.round(depth * Math.sqrt(1 - d * d));
                for (int y = cy + 6; y > cy - dd; y--) {
                    Block b = w.getBlockAt(cx + x, y, cz + z);
                    if (breakable(b)) blocks.add(b);
                }
                Block f = w.getBlockAt(cx + x, cy - dd, cz + z);
                if (dd > 0 && breakable(f) && f.getType().isSolid()) floor.add(f);
            }
        }
        // les protections (spawn, zones, terrains de guilde, arène du boss, arènes de duel...) retirent leurs blocs,
        // comme pour une explosion : personne ne les contourne, même un admin
        blocks = allowedByProtections(caster, center, blocks);
        floor = allowedByProtections(caster, center, floor);
        if (blocks.isEmpty() && floor.isEmpty()) return;
        List<Block> todo = blocks, base = floor;
        todo.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(center), b.getLocation().distanceSquared(center)));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] i = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int done = 0;
            while (i[0] < todo.size() && done < 500) {
                Block b = todo.get(i[0]++);
                if (!breakable(b)) continue;
                if (rnd.nextInt(14) == 0) w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.4, 0.4, 0.4, 0, b.getBlockData(), true);
                b.setType(Material.AIR, false);
                done++;
            }
            if (i[0] >= todo.size()) {
                task[0].cancel();
                // le fond du cratère
                for (Block f : base) {
                    if (!breakable(f) || !f.getType().isSolid() || rnd.nextInt(4) != 0) continue;
                    f.setType(lining[rnd.nextInt(lining.length)], false);
                    if (fire && rnd.nextInt(3) == 0 && f.getRelative(0, 1, 0).getType().isAir()) f.getRelative(0, 1, 0).setType(Material.FIRE, false);
                }
            }
        }, 2L, 1L);
    }

    private static boolean breakable(Block b) {
        Material m = b.getType();
        return !m.isAir() && m.getHardness() >= 0 && m != Material.SPAWNER && m != Material.END_PORTAL_FRAME
                && !(b.getState(false) instanceof org.bukkit.block.Container) && !(b.getState(false) instanceof org.bukkit.block.Sign);
    }

    /** Poser un bloc ici serait refusé par une protection (zone, claim, arène...) ? */
    boolean protectedPlace(Player p, Location at) {
        List<Block> l = new ArrayList<>(List.of(at.getBlock()));
        return allowedByProtections(p, at, l).isEmpty();
    }

    /** Fait passer les blocs par la vérification "explosion" de tous les plugins de protection. */
    private List<Block> allowedByProtections(Player caster, Location center, List<Block> blocks) {
        if (blocks.isEmpty()) return blocks;
        // sécurité en plus : jamais dans l'arène du Roi Citrouille
        blocks.removeIf(b -> plugin.arenaAt(b.getLocation()) != null);
        org.bukkit.event.entity.EntityExplodeEvent ev = new org.bukkit.event.entity.EntityExplodeEvent(caster, center, new ArrayList<>(blocks), 0f,
                org.bukkit.ExplosionResult.DESTROY);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return new ArrayList<>();
        return new ArrayList<>(ev.blockList());
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
    static Location ground(Location l) {
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

    /** Même endroit, sans l'orientation du joueur (sinon les effets s'inclinent quand il regarde en haut/bas). */
    private static Location flat(Location l) {
        Location c = l.clone();
        c.setYaw(0);
        c.setPitch(0);
        return c;
    }

    private BlockDisplay block(Location at, BlockData data, Transformation tr, float view, float box) {
        return at.getWorld().spawn(flat(at), BlockDisplay.class, d -> {
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
    void shards(Location c, int n, double radius, int hold) {
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
                sh.teleport(flat(g).add(0, -sy * 0.45, 0));
                g.getWorld().spawnParticle(Particle.BLOCK, g, 15, 0.6, 0.2, 0.6, 0, sh.getBlock(), true);
            }, 2L + delay);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!sh.isValid()) return;
                sh.setTeleportDuration(20);
                sh.teleport(flat(g).add(0, -sy - 0.5, 0));
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
        List<Location> groundTrail = new ArrayList<>();
        Location[] last = new Location[1];
        BlockDisplay[] cores = new BlockDisplay[3]; // bleu, rouge, violet
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            if (!p.isOnline() || p.isDead() || p.getWorld() != w) {
                casting.remove(p.getUniqueId());
                for (BlockDisplay cd : cores) if (cd != null) cd.remove();
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
            // noyaux nets du Bleu et du Rouge + les idéogrammes 「蒼」 et 「赫」
            if (k == 2) cores[0] = core(feet, Material.LIGHT_BLUE_STAINED_GLASS, 0.5f, Color.fromRGB(60, 140, 255));
            if (k == 16) kanji(feet.clone().add(rt.clone().multiply(-3.2)).add(0, 4.2, 0), "<gradient:#2a6bff:#9fd0ff:#2a6bff><b>術式順転「蒼」", 2.6f, 46);
            if (k == 40) {
                cores[1] = core(feet.clone().add(0, 3.3, 0), Material.RED_STAINED_GLASS, 0.55f, Color.fromRGB(255, 40, 60));
                kanji(feet.clone().add(rt.clone().multiply(3.2)).add(0, 4.2, 0), "<gradient:#ff1e3c:#ffb0b8:#ff1e3c><b>術式反転「赫」", 2.6f, 46);
            }
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
            // ---- aura du lanceur : double spirale bleu / rouge autour du corps (jamais devant les yeux)
            if (k >= 10 && k < merge) {
                for (int h = 0; h < 2; h++) {
                    double a0 = k * 0.28 + Math.PI * h;
                    for (double y = 0; y < 1.4; y += 0.28) {
                        double a = a0 + y * 2.6;
                        w.spawnParticle(Particle.DUST, feet.clone().add(Math.cos(a) * 0.95, y, Math.sin(a) * 0.95), 1, 0, 0, 0, 0, h == 0 ? blue : (k < 40 ? glowP : red));
                    }
                }
                if (k % 3 == 0) w.spawnParticle(Particle.ENCHANT, feet.clone().add(0, 0.8, 0), 6, 1.4, 0.6, 1.4, 0.6);
            }
            // ---- 2. le Bleu fait le tour au ras du sol : traînée bleue + traits noirs
            if (k >= 22 && k < merge) {
                // halo qui tourne autour de la comète + crépitements bleus
                ring(w, bluePos, 1.05, 22, new Vector(Math.cos(k * 0.2), 0.4, Math.sin(k * 0.2)).normalize(), new Vector(0, 1, 0), deep, k * 0.4, true);
                if (k % 2 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, bluePos, 4, 0.7, 0.7, 0.7, 0.15, null, true);
                // sillage lumineux qui reste au sol
                if (k < 84) {
                    Location gg = ground(bluePos);
                    if (gg != null && bluePos.getY() - gg.getY() < 3) groundTrail.add(gg.clone().add(0, 0.05, 0));
                }
                if (k % 2 == 0) for (int i = Math.max(0, groundTrail.size() - 70); i < groundTrail.size(); i++) {
                    Location g2 = groundTrail.get(i);
                    w.spawnParticle(Particle.DUST, g2, 1, 0.35, 0, 0.35, 0, i % 3 == 0 ? glowP : deep, true);
                }
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
            if (k == 38) groundWave(feet, 9, 10, Particle.END_ROD, red);
            if (k >= 38 && k < 64) {
                // étincelles qui montent en spirale autour du faisceau
                for (int h = 0; h < 2; h++) {
                    double a = (k - 38) * 0.45 + Math.PI * h;
                    double y = ((k - 38) * 0.9) % 14;
                    w.spawnParticle(Particle.DUST, feet.clone().add(Math.cos(a) * 1.5, y, Math.sin(a) * 1.5), 2, 0.05, 0.2, 0.05, 0, redHot, true);
                    w.spawnParticle(Particle.END_ROD, feet.clone().add(Math.cos(a) * 1.5, y + 0.4, Math.sin(a) * 1.5), 1, 0, 0, 0, 0.01, null, true);
                }
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
            if (k >= 84 && k < merge) {
                // la gravité se tord : tout est aspiré vers le point entre le Bleu et le Rouge
                Location mid = bluePos.clone().add(redPos.toVector().subtract(bluePos.toVector()).multiply(0.5));
                for (int n = 0; n < 8; n++) {
                    Vector v = new Vector(rnd.nextDouble(-1, 1), rnd.nextDouble(-0.6, 1), rnd.nextDouble(-1, 1)).normalize().multiply(rnd.nextDouble(3, 6));
                    w.spawnParticle(n % 2 == 0 ? Particle.REVERSE_PORTAL : Particle.END_ROD, mid.clone().add(v), 0, -v.getX(), -v.getY(), -v.getZ(), n % 2 == 0 ? 0.25 : 0.12, null, true);
                }
                ring(w, bluePos, 1.0, 18, rt, new Vector(0, 1, 0), blue, k * 0.35, true);
                ring(w, redPos, 1.0, 18, rt, new Vector(0, 1, 0), red, -k * 0.35, true);
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
            if (k < merge) {
                follow(cores[0], bluePos, k);
                follow(cores[1], redPos, -k);
            }
            // ---- 5. fusion : cœur magenta, pics blancs, éclats violets et noirs
            if (k >= merge) {
                Location m = head.clone();
                int e = k - merge;
                if (e == 0) {
                    for (int ci = 0; ci < 2; ci++) if (cores[ci] != null) cores[ci].remove();
                    cores[2] = core(m, Material.MAGENTA_STAINED_GLASS, 0.8f, Color.fromRGB(200, 60, 255));
                    kanji(m.clone().add(0, 3.2, 0), "<gradient:#3c82ff:#b25cff:#ff2330><b>虚式「茈」", 5.5f, 60);
                }
                follow(cores[2], m, k * 2);
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
                    pillar(feet, new Material[]{Material.WHITE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS}, new float[]{1.2f, 0.6f}, 80, 2, 8, 6);
                    groundWave(feet, 16, 10, Particle.END_ROD, new Particle.DustOptions(MAGENTA, 2.6f));
                    boom(base, 260, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 0.6f);
                    boom(base, 260, Sound.BLOCK_END_PORTAL_SPAWN, 2f, 1.2f);
                    boom(base, 260, Sound.ITEM_TRIDENT_THUNDER, 2f, 0.7f);
                    boom(base, 260, Sound.BLOCK_BEACON_POWER_SELECT, 2f, 0.5f);
                    chat(base, p, plugin.msg("weapons.hollow-purple.final"));
                    shake(m, 60, 3, 2);
                }
                if (e >= 6) {
                    task[0].cancel();
                    casting.remove(p.getUniqueId());
                    if (cores[2] != null) cores[2].remove();
                    hollowBurst(p, m);
                }
            }
        }, 0L, 1L);
    }

    /** 6. L'explosion part du lanceur : tout devient blanc, l'onde balaie 200 blocs. */
    private void hollowBurst(Player p, Location c) {
        World w = c.getWorld();
        double R = cfg("hollow-purple.radius", 200), dmg = cfg("hollow-purple.damage", 1000), core = cfg("hollow-purple.core-radius", 30);
        List<LivingEntity> list = targets(p, c, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) {
            dist.put(le.getUniqueId(), le.getLocation().distance(c));
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
        shake(c, R + 30, 8, 3);
        Location gc0 = ground(c) == null ? c.clone() : ground(c);
        groundWave(gc0, 60, 22, Particle.END_ROD, new Particle.DustOptions(MAGENTA, 3f));
        // ---- la GRANDE EXPLOSION (visible après le flash blanc et de loin)
        pillar(gc0, new Material[]{Material.PURPLE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS, Material.WHITE_STAINED_GLASS},
                new float[]{12, 8, 3}, 200, 6, 120, 25);
        shards(gc0, 44, 26, 180);
        crater(p, gc0, "hollow-purple", new Material[]{Material.CRYING_OBSIDIAN, Material.AMETHYST_BLOCK, Material.OBSIDIAN}, false);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 14, 7, 4, 7, 0, null, true);
        w.spawnParticle(Particle.REVERSE_PORTAL, c, 600, 4, 4, 4, 4, null, true);
        w.spawnParticle(Particle.DUST, c, 300, 14, 10, 14, 0, new Particle.DustOptions(Color.fromRGB(12, 0, 28), 4f), true);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
            int white = 50;
            // dôme de l'explosion qui gonfle (anneaux à plusieurs hauteurs), rayons de lumière, explosions en chaîne
            if (k >= 4 && k <= 70 && k % 2 == 0) {
                double ud = Math.min(1, (k - 4) / 50.0), dr = 70 * (1 - Math.pow(1 - ud, 3));
                double[] lats = {0.05, 0.3, 0.6, 0.9, 1.2, 1.45};
                for (int li = 0; li < lats.length; li++) {
                    double rr = dr * Math.cos(lats[li]), y = dr * Math.sin(lats[li]);
                    int n = (int) Math.min(260, 20 + rr * 3.2);
                    for (int q = 0; q < n; q++) {
                        double a = Math.PI * 2 * q / n + k * 0.01 * (li % 2 == 0 ? 1 : -1);
                        w.spawnParticle(Particle.DUST, gc0.getX() + Math.cos(a) * rr, gc0.getY() + y, gc0.getZ() + Math.sin(a) * rr, 1, 0.3, 0.3, 0.3, 0,
                                q % 3 == 0 ? lilacD : q % 3 == 1 ? purpleD : new Particle.DustOptions(MAGENTA, 3.4f), true);
                    }
                }
                if (k % 4 == 0) w.spawnParticle(Particle.END_ROD, gc0.clone().add(0, dr * 0.3, 0), 60, dr * 0.6, dr * 0.3, dr * 0.6, 0.05, null, true);
            }
            if (k < 36 && k % 2 == 0) {
                for (int ray = 0; ray < 26; ray++) {
                    double a = ray * 2.39996, b = Math.acos(Math.max(-0.2, 1 - 2 * ((ray + 0.5) / 26)));
                    Vector v = new Vector(Math.sin(b) * Math.cos(a), Math.abs(Math.cos(b)), Math.sin(b) * Math.sin(a));
                    double len = Math.min(60, 6 + k * 2.2);
                    for (double q = len * 0.4; q < len; q += 1.4) w.spawnParticle(Particle.DUST, c.clone().add(v.clone().multiply(q)), 1, 0.1, 0.1, 0.1, 0,
                            q > len * 0.8 ? purpleD : new Particle.DustOptions(Color.WHITE, 2.6f), true);
                }
            }
            if (k > 2 && k < 44 && k % 5 == 0) {
                Location ex = gc0.clone().add(rnd.nextDouble(-30, 30), rnd.nextDouble(0, 14), rnd.nextDouble(-30, 30));
                w.spawnParticle(Particle.EXPLOSION_EMITTER, ex, 3, 3, 2, 3, 0, null, true);
                w.spawnParticle(Particle.DUST, ex, 60, 4, 3, 4, 0, purpleD, true);
                w.spawnParticle(Particle.ELECTRIC_SPARK, ex, 40, 4, 3, 4, 0.4, null, true);
            }
            // grondements en cascade
            if (k == 3 || k == 9 || k == 16 || k == 26 || k == 40) {
                boom(c, R + 60, k % 2 == 0 ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 3f, 0.45f + k * 0.004f);
                boom(c, R + 60, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.5f, 0.5f + k * 0.005f);
                if (k == 9) boom(c, R + 60, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.6f);
                if (k == 26) shake(c, R, 2, 3);
            }
            for (Player o : w.getPlayers()) {
                double d = o.getLocation().distance(c);
                if (d > R) continue;
                if (k < white && d <= r + 12) {
                    // écran blanc, éclairs violets et noirs, volutes roses / violettes sur les bords
                    int n = k < 12 ? 90 : k < white - 20 ? 65 : Math.max(4, 65 - (k - (white - 20)) * 3);
                    screen(o, Color.WHITE, k % 3 == 0 ? pink : (k % 3 == 1 ? haze : lav), n, 4f);
                    if (k < 46) {
                        streak(o, rnd.nextInt(3) == 0 ? Color.fromRGB(10, 0, 25) : PURPLE, 2.0f);
                        if (k % 2 == 0) streak(o, MAGENTA, 1.5f);
                        if (k < 14) streak(o, Color.fromRGB(10, 0, 25), 2.4f);
                    }
                    if (k > 6 && k < white - 8) edges(o, k % 2 == 0 ? PURPLE : Color.fromRGB(230, 120, 245), pink, k);
                    if (k == 0 || (k < 6 && d <= r)) o.playSound(o.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
                } else if (k >= white && k < white + 110 && k % 4 == 0) {
                    // aurore violette dans le ciel au-dessus de chacun
                    Particle.DustOptions sky = new Particle.DustOptions(k % 8 == 0 ? PURPLE : MAGENTA, 4f);
                    new ParticleBuilder(Particle.DUST).receivers(o).location(o.getLocation().add(0, 22, 0)).count(30).offset(24, 3, 24).extra(0).data(sky).force(true).spawn();
                    new ParticleBuilder(Particle.END_ROD).receivers(o).location(o.getLocation().add(0, 18, 0)).count(6).offset(20, 4, 20).extra(0.005).force(true).spawn();
                }
                if (k >= white && k < white + 60 && k % 3 == 0) {
                    // l'écran revient : brume et éclats violets qui retombent autour de chacun
                    new ParticleBuilder(Particle.REVERSE_PORTAL).receivers(o).location(o.getLocation().add(0, 2, 0)).count(30).offset(8, 3, 8).extra(0.04).spawn();
                    new ParticleBuilder(Particle.DUST).receivers(o).location(o.getLocation().add(0, 3, 0)).count(12).offset(8, 3, 8).extra(0).data(lilacD).spawn();
                    new ParticleBuilder(Particle.END_ROD).receivers(o).location(o.getLocation().add(0, 3, 0)).count(5).offset(8, 3, 8).extra(0.01).spawn();
                }
            }
            // cratère violet au centre, une fois le blanc parti
            if (k >= white - 20 && k < white + 110 && k % 2 == 0) {
                Location g = ground(c);
                Location gc = g == null ? c : g;
                for (int i = 0; i < 3; i++) ring(w, gc.clone().add(0, 0.15, 0), 6 + i * 6, 40 + i * 20, new Vector(1, 0, 0), new Vector(0, 0, 1), i == 1 ? lilacD : purpleD, k * 0.02 * (i % 2 == 0 ? 1 : -1), true);
                w.spawnParticle(Particle.DUST, gc.clone().add(0, 1.5, 0), 25, 10, 1.5, 10, 0, inkD, true);
                w.spawnParticle(Particle.ELECTRIC_SPARK, gc.clone().add(0, 1, 0), 15, 12, 1, 12, 0.2, null, true);
                w.spawnParticle(Particle.REVERSE_PORTAL, gc.clone().add(0, 1, 0), 30, 14, 2, 14, 0.05, null, true);
                // runes qui flottent au-dessus du cratère et lumière qui monte
                for (int n = 0; n < 12; n++) {
                    double a = Math.PI * 2 * n / 12 + k * 0.03;
                    w.spawnParticle(Particle.ENCHANT, gc.clone().add(Math.cos(a) * 9, 1.6, Math.sin(a) * 9), 4, 0.2, 0.3, 0.2, 0.4, null, true);
                }
                w.spawnParticle(Particle.END_ROD, gc.clone().add(0, 0.5, 0), 6, 5, 0.2, 5, 0.06, null, true);
            }
            // l'onde frappe chacun quand elle l'atteint
            if (k <= 46) {
                for (LivingEntity le : list) {
                    Double d = dist.get(le.getUniqueId());
                    if (d == null || d > r || !done.add(le.getUniqueId()) || !le.isValid() || le.isDead()) continue;
                    zoneHit(p, le, d, DamageType.SONIC_BOOM, "hollow-purple", 0);
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
            if (k == 36) kanji(base.clone().add(0, 5, 0), "<gradient:#ffd23c:#ff5a00:#ffd23c><b>「開」", 4.5f, 48);
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
            // spirales de feu qui montent du cercle (comme des pétales) autour du lanceur
            for (int arm = 0; arm < 3; arm++) {
                double a0 = k * 0.22 + Math.PI * 2 * arm / 3;
                for (double y = 0; y < 2.6; y += 0.32) {
                    double rr = (4.3 - y * 1.1) * grow, a = a0 + y * 1.4;
                    if (rr < 1.1) continue;
                    w.spawnParticle(y < 1.2 ? Particle.FLAME : Particle.SMALL_FLAME, feet.clone().add(Math.cos(a) * rr, y + 0.1, Math.sin(a) * rr), 1, 0, 0, 0, 0);
                }
            }
            if (k == 20 || k == 45 || k == 70) groundWave(feet, 7, 8, Particle.FLAME, ember);
            if (k % 3 == 0) w.spawnParticle(Particle.WHITE_SMOKE, feet.clone().add(0, 0.4, 0), 3, 2.8 * grow, 0.3, 2.8 * grow, 0.02);
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
                shake(base, 45, 2, 3);
                groundWave(feet, 14, 10, Particle.FLAME, fire);
                kanji(base.clone().add(0, 4.5, 0), "<gradient:#fff2a0:#ff7a1e:#ff2a00><b>竈 ・ フーガ", 3.6f, 44);
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
        List<Location> scorch = new ArrayList<>();
        BlockDisplay head = core(start, Material.ORANGE_STAINED_GLASS, 0.9f, Color.fromRGB(255, 170, 40));
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
                for (int j = 0; j < 5; j++) {
                    double ph = d * 0.32 + j * 1.26;
                    double amp = (j < 3 ? 0.6 : 0.35) + Math.min(1.2, d * 0.05);
                    Vector o = rt.clone().multiply(Math.sin(ph) * amp).add(up.clone().multiply(Math.cos(ph * 0.8) * amp * 0.6));
                    w.spawnParticle(Particle.DUST, at.clone().add(o), 1, 0.06, 0.06, 0.06, 0, j == 0 ? yellow : j == 1 ? fire : j == 2 ? gold : j == 3 ? hot : ember, true);
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
            // anneaux de feu laissés derrière, étincelles qui tombent
            if (k % 3 == 0) ring(w, from, 2.1, 30, rt, up, k % 6 == 0 ? fire : gold, 0, true);
            w.spawnParticle(Particle.FALLING_LAVA, from, 4, 0.8, 0.5, 0.8, 0, null, true);
            w.spawnParticle(Particle.LAVA, pos, 2, 0.3, 0.3, 0.3, 0, null, true);
            if (k % 2 == 0) w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, from, 3, 0.5, 0.5, 0.5, 0.01, null, true);
            Location sg = ground(pos.clone().add(0, -1, 0));
            if (sg != null && pos.getY() - sg.getY() < 8) {
                scorch.add(sg.clone().add(0, 0.1, 0));
                w.spawnParticle(Particle.FLAME, sg, 4, 0.6, 0.05, 0.6, 0.02, null, true);
            }
            if (k % 2 == 0) for (int i = Math.max(0, scorch.size() - 40); i < scorch.size(); i++)
                w.spawnParticle(Particle.DUST, scorch.get(i), 1, 0.5, 0, 0.5, 0, i % 2 == 0 ? ember : fire, true);
            // la tête de feu et son anneau
            orb(w, pos, 0.7, 20, gold, k * 0.4, true);
            ring(w, pos, 1.6, 26, up, dir, yellow, -k * 0.6, true);
            ring(w, pos, 1.3, 24, rt, up, fire, k * 0.5, true);
            w.spawnParticle(Particle.FLAME, pos, 12, 0.35, 0.35, 0.35, 0.04, null, true);
            if (k % 3 == 0) flare(w, pos, rt, 3, flareD, hot);
            if (k % 3 == 0) w.playSound(pos, Sound.ENTITY_BLAZE_SHOOT, 3f, 0.6f);
            if (k % 8 == 0) shake(pos, 10, 1, 1);
            if (dist[0] >= range) stop = true;
            follow(head, pos, k * 2);
            if (stop) {
                task[0].cancel();
                head.remove();
                Location c = rtb != null && hitAt == null ? pos.clone().subtract(dir.clone().multiply(0.6)) : pos.clone();
                fugaBurst(p, c);
            }
        }, 0L, 1L);
    }

    private void fugaBurst(Player p, Location c) {
        World w = c.getWorld();
        double R = cfg("fuga.radius", 200), dmg = cfg("fuga.damage", 1000), core = cfg("fuga.core-radius", 30);
        int burn = (int) (cfg("fuga.burn-seconds", 10) * 20);
        List<LivingEntity> list = targets(p, c, R);
        Map<UUID, Double> dist = new HashMap<>();
        for (LivingEntity le : list) {
            dist.put(le.getUniqueId(), le.getLocation().distance(c));
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
        crater(p, gc, "fuga", new Material[]{Material.MAGMA_BLOCK, Material.BLACKSTONE, Material.NETHERRACK}, true);
        shake(c, R + 30, 9, 3);
        groundWave(gc, 55, 20, Particle.FLAME, new Particle.DustOptions(FIRE, 3.2f));
        w.spawnParticle(Particle.LAVA, gc, 250, 8, 2, 8, 0, null, true);
        w.spawnParticle(Particle.LARGE_SMOKE, gc, 250, 3, 2, 3, 0.6, null, true);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = t[0]++;
            double u = Math.min(1, k / 40.0), r = R * (1 - Math.pow(1 - u, 3));
            // ---- l'immense colonne de feu
            if (k == 14) kanji(gc.clone().add(0, 40, 0), "<gradient:#ffd23c:#ff5a00:#ff1a00><b>竈", 16f, 100);
            if (k == 14) {
                // la colonne part de sous le sol (elle s'enfonce dans le cratère)
                pillar(gc.clone().add(0, -7, 0), new Material[]{Material.ORANGE_STAINED_GLASS, Material.MAGMA_BLOCK, Material.RED_STAINED_GLASS},
                        new float[]{11, 6.5f, 8.5f}, 227, 8, 130, 24);
                boom(c, R + 60, Sound.ENTITY_BLAZE_SHOOT, 4f, 0.4f);
                boom(c, R + 60, Sound.ITEM_TRIDENT_THUNDER, 3f, 0.5f);
                boom(c, R + 60, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
            }
            // grondements en cascade, la colonne fait trembler le sol tant qu'elle brûle
            if (k == 4 || k == 10 || k == 20 || k == 32) {
                boom(c, R + 60, k % 4 == 0 ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 3f, 0.45f + k * 0.005f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, gc.clone().add(rnd.nextDouble(-12, 12), rnd.nextDouble(0, 6), rnd.nextDouble(-12, 12)), 3, 4, 2, 4, 0, null, true);
            }
            if (k >= 30 && k < 150 && k % 30 == 0) {
                shake(gc, 50, 1, 1);
                boom(gc, R + 40, Sound.BLOCK_FIRE_AMBIENT, 3f, 0.5f);
                boom(gc, R + 40, Sound.ENTITY_BLAZE_BURN, 2f, 0.5f);
            }
            // tornade de feu à la base de la colonne + braises qui montent partout + éruptions de lave
            if (k >= 14 && k < 160) {
                for (int arm = 0; arm < 4; arm++) {
                    double a0 = k * 0.3 + Math.PI * arm / 2;
                    for (double y = 0; y < 34; y += 2.2) {
                        double rr = 11 - y * 0.2, a = a0 + y * 0.18;
                        w.spawnParticle(Particle.FLAME, gc.getX() + Math.cos(a) * rr, gc.getY() + y, gc.getZ() + Math.sin(a) * rr, 1, 0.2, 0.2, 0.2, 0.01, null, true);
                    }
                }
                if (k % 2 == 0) for (int n = 0; n < 8; n++) {
                    double a = rnd.nextDouble(Math.PI * 2), d0 = rnd.nextDouble(4, 28);
                    w.spawnParticle(Particle.FLAME, gc.clone().add(Math.cos(a) * d0, 0.3, Math.sin(a) * d0), 0, 0, 1, 0, rnd.nextDouble(0.15, 0.4), null, true);
                }
                if (k % 10 == 0) {
                    double a = rnd.nextDouble(Math.PI * 2), d0 = rnd.nextDouble(6, 22);
                    Location er = ground(gc.clone().add(Math.cos(a) * d0, 3, Math.sin(a) * d0));
                    if (er != null) {
                        w.spawnParticle(Particle.LAVA, er, 30, 0.6, 0.3, 0.6, 0, null, true);
                        w.spawnParticle(Particle.FLAME, er.clone().add(0, 2, 0), 40, 0.4, 2, 0.4, 0.05, null, true);
                        w.playSound(er, Sound.BLOCK_LAVA_POP, 2f, 0.6f);
                    }
                }
            }
            if (k == 14) flare(w, gc.clone().add(0, 20, 0), new Vector(1, 0, 0.2), 45, flareD, flareC);
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
                    zoneHit(p, le, d, DamageType.MAGIC, "fuga", burn);
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
