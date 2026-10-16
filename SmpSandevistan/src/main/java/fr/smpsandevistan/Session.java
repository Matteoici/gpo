package fr.smpsandevistan;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Un Sandevistan actif : dans le rayon, le temps est arrêté ; le joueur, lui, file à toute vitesse.
 *
 * Début : onde de choc, titre qui "glitch", sons étouffés, ciel figé, teinte d'écran.
 * Pendant : images rémanentes qui changent de couleur en vieillissant, dash (touche F), coups en attente
 * (compteur au-dessus de la cible, étincelles figées dans l'air), projectiles renvoyés.
 * Fin : le temps reprend, les coups tombent en cascade, puis le contrecoup (lenteur, nausée).
 */
final class Session {

    private static final Vector ZERO = new Vector(0, 0, 0);
    private static final Random RNG = new Random();

    /** Ce qu'on doit rendre à une entité figée quand le temps reprend. */
    private static final class Frozen {
        final Entity e;
        final Location at;
        Vector velocity;
        boolean gravity;
        Boolean ai;          // monstres
        int fuse = -1;       // TNT
        int pickup = -1;     // objets au sol
        Vector direction;    // boules de feu
        float flySpeed = -1; // joueurs en vol

        Frozen(Entity e) {
            this.e = e;
            this.at = e.getLocation();
        }
    }

    private static final class Image {
        final Mannequin m;
        final int born, dieAt;
        int stage = -1;

        Image(Mannequin m, int born, int dieAt) {
            this.m = m;
            this.born = born;
            this.dieAt = dieAt;
        }
    }

    /** Coups portés à une cible figée. */
    private static final class Pending {
        double damage;
        int hits;
        TextDisplay label;
    }

    final SmpSandevistan pl;
    final Player user;
    final int radius;
    final long startAt, endAt;
    final Map<UUID, Frozen> frozen = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> deflected = new HashSet<>();
    private final Deque<Image> images = new ArrayDeque<>();
    private final Set<UUID> imageIds = new HashSet<>();
    private final List<Location> sparks = new ArrayList<>();
    private BossBar bar;
    private BukkitTask task;
    private int tick, hits, combo;
    private long lastDash;
    private Location lastImage;
    private double wave;
    boolean releasing;

    Session(SmpSandevistan pl, Player user, int seconds) {
        this.pl = pl;
        this.user = user;
        this.radius = Math.max(5, cfg().getInt("radius", 100));
        this.startAt = System.currentTimeMillis();
        this.endAt = startAt + seconds * 1000L;
    }

    private FileConfiguration cfg() {
        return pl.getConfig();
    }

    boolean isImage(Entity e) {
        return imageIds.contains(e.getUniqueId());
    }

    boolean isFrozen(Entity e) {
        return frozen.containsKey(e.getUniqueId());
    }

    /** Un joueur figé s'est déconnecté : il repart libre. */
    void forget(UUID id) {
        frozen.remove(id);
    }

    // ================================================================= début

    int start() {
        user.stopAllSounds(); // un silence, puis l'implant
        sound(user, "block.beacon.power_select", 1f, 0.5f);
        sound(user, "entity.warden.sonic_charge", 0.6f, 2f);
        sound(user, "block.amethyst_block.resonate", 1f, 0.6f);
        sound(user, "ambient.underwater.enter", 1f, 1.2f); // tout devient étouffé
        user.getWorld().spawnParticle(Particle.END_ROD, user.getLocation().add(0, 1, 0), 60, 0.4, 0.9, 0.4, 0.35);
        boostUser(true);
        if (cfg().getBoolean("screen.tint", true)) tint(true);
        if (cfg().getBoolean("screen.freeze-sky", true)) user.setPlayerTime(user.getWorld().getTime(), false);
        if (cfg().getBoolean("screen.bossbar", true)) {
            bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
            user.showBossBar(bar);
        }
        scan();
        task = Bukkit.getScheduler().runTaskTimer(pl, this::safeTick, 1L, 1L);
        return frozen.size();
    }

    private void boostUser(boolean on) {
        if (on) {
            int lvl = Math.max(0, cfg().getInt("speed-level", 3));
            if (lvl > 0) user.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 3600, lvl - 1, false, false, false));
            if (cfg().getBoolean("no-attack-cooldown", true)) {
                modifier(user, Attribute.ATTACK_SPEED, pl.attackKey, 100, AttributeModifier.Operation.ADD_NUMBER);
            }
            user.setFallDistance(0);
        } else {
            user.removePotionEffect(PotionEffectType.SPEED);
            removeModifier(user, Attribute.ATTACK_SPEED, pl.attackKey);
        }
    }

    /** Teinte sur les bords de l'écran (bordure du monde virtuelle, rien que pour lui). */
    private void tint(boolean on) {
        if (!on) {
            user.setWorldBorder(null);
            return;
        }
        WorldBorder b = Bukkit.createWorldBorder();
        b.setCenter(user.getLocation());
        b.setSize(10_000);
        double strength = Math.max(0.05, Math.min(0.95, cfg().getDouble("screen.tint-strength", 0.45)));
        b.setWarningDistance((int) (5_000 / (1 - strength)));
        user.setWorldBorder(b);
    }

    // ================================================================= chaque tick

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            pl.getLogger().warning("Sandevistan : " + t);
            pl.end(this);
        }
    }

    private void tick() {
        if (!user.isOnline() || user.isDead() || System.currentTimeMillis() >= endAt) {
            pl.end(this);
            return;
        }
        tick++;
        if (tick % 4 == 0) scan();
        hold();
        intro();
        hud();
        afterimages();
        frozenSparks();
        user.setFallDistance(0);
        // l'anticheat ne prend pas sa vitesse ni ses dashs pour de la triche
        user.getPersistentDataContainer().set(new NamespacedKey("smpguard", "exempt_until"), PersistentDataType.LONG,
                System.currentTimeMillis() + 3000);
    }

    /** Onde de choc qui part de lui, et le titre qui se met en place en "glitchant". */
    private void intro() {
        double max = Math.min(radius, cfg().getDouble("screen.wave-radius", 40));
        if (wave < max) {
            wave += 3.2;
            Location c = user.getLocation();
            int points = (int) Math.min(140, 10 + wave * 4);
            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2 * i / points;
                Location l = c.clone().add(Math.cos(a) * wave, 0.2, Math.sin(a) * wave);
                user.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(trailColor(i), 1.6f));
            }
        }
        if (tick <= 14 && cfg().getBoolean("screen.title", true)) {
            String word = "SANDEVISTAN";
            StringBuilder sb = new StringBuilder();
            String noise = "█▓▒░#$%&@";
            double clean = tick / 10.0;
            for (char ch : word.toCharArray()) sb.append(RNG.nextDouble() < clean ? ch : noise.charAt(RNG.nextInt(noise.length())));
            String txt = tick < 12 ? sb.toString() : word;
            user.showTitle(Title.title(pl.mm("<gradient:#00ff9c:#00e5ff:#ff2bd6><b>" + txt + "</b></gradient>"),
                    pl.mm("<dark_gray>// <gray>temps dilaté</gray> //"),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(tick < 14 ? 300 : 500), Duration.ofMillis(400))));
        }
    }

    private void hud() {
        long left = Math.max(0, endAt - System.currentTimeMillis());
        float progress = (float) left / Math.max(1, endAt - startAt);
        String s = String.format(Locale.US, "%.1f", left / 1000.0);
        if (bar != null && tick % 2 == 0) {
            bar.progress(Math.max(0f, Math.min(1f, progress)));
            bar.color(progress > 0.5f ? BossBar.Color.GREEN : progress > 0.2f ? BossBar.Color.YELLOW : BossBar.Color.RED);
            bar.name(pl.mm("<gradient:#00ff9c:#00e5ff><b>SANDEVISTAN</b></gradient> <dark_gray>|</dark_gray> <white>" + s
                    + "s <dark_gray>|</dark_gray> <gray>" + frozen.size() + " figé(s)"));
        }
        if (tick % 2 == 0) {
            String combo = this.combo > 0 ? "<gradient:#ff2bd6:#ffe600><b>COMBO x" + this.combo + "</b></gradient>"
                    : "<dark_gray>F <gray>: dash   <dark_gray>clic <gray>: renvoyer les projectiles";
            user.sendActionBar(pl.mm(combo));
        }
        if (tick % 20 == 0) sound(user, "entity.warden.heartbeat", 1f, 1.25f);
        if (progress < 0.2f && tick % 10 == 0) sound(user, "block.note_block.bit", 0.6f, 2f); // bientôt fini
    }

    // ================================================================= figer

    /** Fige tout ce qui entre dans le rayon. */
    private void scan() {
        Location c = user.getLocation();
        double r2 = (double) radius * radius;
        for (Entity e : user.getWorld().getNearbyEntities(c, radius, radius, radius)) {
            if (e == user || isFrozen(e) || isImage(e) || e.getLocation().distanceSquared(c) > r2) continue;
            if (e instanceof Display || e instanceof Interaction || e instanceof Marker || e instanceof Hanging) continue;
            if (e instanceof Projectile pr && pr.getShooter() == user && !deflected.contains(e.getUniqueId())) continue;
            if (e instanceof Player p && (pl.isUser(p) || p.hasPermission("smpsandevistan.bypass") || p.isDead())) continue;
            if (pl.frozenElsewhere(e, this)) continue;
            freeze(e);
        }
    }

    private void freeze(Entity e) {
        Frozen f = new Frozen(e);
        f.velocity = e.getVelocity();
        f.gravity = e.hasGravity();
        e.setVelocity(ZERO);
        e.setGravity(false);
        if (e instanceof Player p) {
            // le client ne peut plus bouger du tout : vitesse, saut et gravité à zéro, ciel arrêté
            modifier(p, Attribute.MOVEMENT_SPEED, pl.freezeKey, -1, AttributeModifier.Operation.ADD_SCALAR);
            modifier(p, Attribute.JUMP_STRENGTH, pl.freezeKey, -1, AttributeModifier.Operation.ADD_SCALAR);
            modifier(p, Attribute.GRAVITY, pl.freezeKey, -1, AttributeModifier.Operation.ADD_SCALAR);
            if (p.isFlying()) {
                f.flySpeed = p.getFlySpeed();
                p.setFlySpeed(0);
            }
            if (p.isGliding()) p.setGliding(false);
            if (cfg().getBoolean("screen.freeze-sky", true)) p.setPlayerTime(p.getWorld().getTime(), false);
        } else if (e instanceof LivingEntity le) {
            f.ai = le.hasAI();
            le.setAI(false);
        }
        if (e instanceof TNTPrimed t) f.fuse = t.getFuseTicks();
        if (e instanceof Item it) {
            f.pickup = it.getPickupDelay();
            it.setPickupDelay(Short.MAX_VALUE);
        }
        if (e instanceof Fireball fb) {
            f.direction = fb.getDirection();
            fb.setDirection(ZERO);
        }
        frozen.put(e.getUniqueId(), f);
    }

    /** Garde tout immobile (rien ne glisse, la TNT n'avance pas, la tête des joueurs figés ne tourne pas). */
    private void hold() {
        boolean lockHead = cfg().getBoolean("freeze-camera", true);
        var it = frozen.values().iterator();
        while (it.hasNext()) {
            Frozen f = it.next();
            Entity e = f.e;
            if (!e.isValid()) {
                if (!(e instanceof Player)) it.remove();
                continue;
            }
            if (e instanceof TNTPrimed t && f.fuse >= 0) t.setFuseTicks(f.fuse);
            if (e.getVelocity().lengthSquared() > 1e-6) e.setVelocity(ZERO);
            if (e.getWorld() != f.at.getWorld()) continue;
            Location now = e.getLocation();
            boolean moved = now.distanceSquared(f.at) > (e instanceof Player ? 0.04 : 0.0025);
            boolean turned = e instanceof Player && lockHead
                    && (Math.abs(now.getYaw() - f.at.getYaw()) > 1 || Math.abs(now.getPitch() - f.at.getPitch()) > 1);
            if (moved || turned) {
                Location back = f.at.clone();
                if (e instanceof Player && !lockHead) {
                    back.setYaw(now.getYaw());
                    back.setPitch(now.getPitch());
                }
                pl.snapBack(e, back);
            }
        }
    }

    Location frozenSpot(Entity e) {
        Frozen f = frozen.get(e.getUniqueId());
        return f == null ? null : f.at;
    }

    // ================================================================= images rémanentes

    private void afterimages() {
        // les images vieillissent : vert -> cyan -> rose -> rouge, puis disparaissent
        List<String> names = cfg().getStringList("afterimages.glow-colors");
        for (Image im : images) {
            if (names.isEmpty()) break;
            int life = Math.max(1, im.dieAt - im.born);
            int stage = Math.min(names.size() - 1, (tick - im.born) * names.size() / life);
            if (stage != im.stage) {
                im.stage = stage;
                glowTeam(names.get(stage)).addEntry(im.m.getUniqueId().toString());
            }
        }
        while (!images.isEmpty() && images.peekFirst().dieAt <= tick) {
            Image im = images.pollFirst();
            imageIds.remove(im.m.getUniqueId());
            im.m.getWorld().spawnParticle(Particle.DUST, im.m.getLocation().add(0, 1, 0), 4, 0.2, 0.5, 0.2, 0,
                    new Particle.DustOptions(Color.fromRGB(0xff2bd6), 0.9f));
            im.m.remove();
        }
        // traînée
        Location here = user.getLocation();
        user.getWorld().spawnParticle(Particle.DUST, here.clone().add(0, 1, 0), 3, 0.25, 0.5, 0.25, 0,
                new Particle.DustOptions(trailColor(tick / 3), 1.3f));
        if (!cfg().getBoolean("afterimages.enabled", true)) return;
        int every = Math.max(1, cfg().getInt("afterimages.every-ticks", 2));
        if (tick % every != 0) return;
        if (lastImage != null && lastImage.getWorld() == here.getWorld() && lastImage.distanceSquared(here) < 0.35 * 0.35) return;
        lastImage = here.clone();
        image(here, false);
    }

    /** Une copie de lui, figée là où il est passé. */
    void image(Location at, boolean swing) {
        if (!cfg().getBoolean("afterimages.enabled", true)) return;
        int max = cfg().getInt("afterimages.max", 18);
        while (images.size() >= max) {
            Image old = images.pollFirst();
            imageIds.remove(old.m.getUniqueId());
            old.m.remove();
        }
        int life = Math.max(2, cfg().getInt("afterimages.life-ticks", 14));
        Mannequin m = at.getWorld().spawn(at, Mannequin.class, e -> {
            e.setPersistent(false);
            e.setProfile(ResolvableProfile.resolvableProfile(user.getPlayerProfile()));
            e.setImmovable(true);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSilent(true);
            e.setCollidable(false);
            e.setDescription(Component.empty());
            e.setBodyYaw(at.getYaw());
            e.getEquipment().setArmorContents(user.getInventory().getArmorContents());
            e.getEquipment().setItemInMainHand(user.getInventory().getItemInMainHand());
            e.getEquipment().setItemInOffHand(user.getInventory().getItemInOffHand());
            if (user.isSneaking()) e.setPose(Pose.SNEAKING, true);
            e.setGlowing(true);
        });
        imageIds.add(m.getUniqueId());
        List<String> names = cfg().getStringList("afterimages.glow-colors");
        if (!names.isEmpty()) glowTeam(names.get(0)).addEntry(m.getUniqueId().toString());
        if (swing) m.swingMainHand();
        images.addLast(new Image(m, tick, tick + life));
    }

    private static Team glowTeam(String colorName) {
        NamedTextColor c = NamedTextColor.NAMES.valueOr(colorName.toLowerCase(Locale.ROOT), NamedTextColor.GREEN);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = "sdv_" + c;
        Team t = sb.getTeam(name);
        if (t == null) {
            t = sb.registerNewTeam(name);
            t.color(c);
            t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return t;
    }

    private Color trailColor(int i) {
        List<String> hex = cfg().getStringList("afterimages.trail-colors");
        if (hex.isEmpty()) return Color.LIME;
        try {
            return Color.fromRGB(Integer.parseInt(hex.get(Math.floorMod(i, hex.size())).replace("#", ""), 16));
        } catch (RuntimeException e) {
            return Color.LIME;
        }
    }

    // ================================================================= dash (touche F)

    void dash() {
        long now = System.currentTimeMillis();
        if (now - lastDash < cfg().getLong("dash.cooldown-ms", 500)) return;
        lastDash = now;
        double dist = cfg().getDouble("dash.distance", 9);
        Location from = user.getLocation();
        Vector dir = from.getDirection();
        if (Math.abs(dir.getY()) < 0.35) dir.setY(0); // dash à plat sauf si on vise franchement en haut / en bas
        dir.normalize();
        // on s'arrête avant un mur (pieds et tête)
        double free = dist;
        for (double h : new double[]{0.1, 1.0, 1.7}) {
            RayTraceResult r = from.getWorld().rayTraceBlocks(from.clone().add(0, h, 0), dir, dist, FluidCollisionMode.NEVER, true);
            if (r != null) free = Math.min(free, r.getHitPosition().distance(from.toVector().add(new Vector(0, h, 0))) - 0.6);
        }
        if (free < 1) return;
        Location to = from.clone().add(dir.clone().multiply(free));
        // images tout le long du trajet
        int steps = (int) Math.max(2, free / 1.2);
        for (int i = 1; i < steps; i++) image(from.clone().add(dir.clone().multiply(free * i / steps)), false);
        pl.snapBack(user, to);
        user.setVelocity(dir.clone().multiply(0.6));
        sound(user, "entity.breeze.wind_burst", 0.8f, 1.6f);
        sound(user, "item.trident.riptide_1", 0.5f, 2f);
        user.getWorld().spawnParticle(Particle.DUST, to.clone().add(0, 1, 0), 18, 0.3, 0.6, 0.3, 0,
                new Particle.DustOptions(trailColor(tick), 1.5f));
    }

    // ================================================================= coups en attente

    /** Un coup sur une cible figée : il est gardé et tombera quand le temps reprend. */
    void hit(Entity target, double damage) {
        Pending p = pending.computeIfAbsent(target.getUniqueId(), k -> new Pending());
        p.damage += damage;
        p.hits++;
        hits++;
        combo++;
        Location l = target.getLocation().add((RNG.nextDouble() - 0.5) * 0.5, target.getHeight() * (0.3 + RNG.nextDouble() * 0.5),
                (RNG.nextDouble() - 0.5) * 0.5);
        if (sparks.size() < cfg().getInt("hits.max-frozen-sparks", 80)) sparks.add(l);
        target.getWorld().spawnParticle(Particle.CRIT, l, 12, 0.2, 0.25, 0.2, 0.15);
        target.getWorld().spawnParticle(Particle.DUST, l, 8, 0.15, 0.2, 0.15, 0,
                new Particle.DustOptions(trailColor(combo), 1.2f));
        sound(user, "entity.player.attack.crit", 0.7f, 1.4f + Math.min(0.6f, combo * 0.03f));
        image(user.getLocation(), true); // une image qui frappe
        if (cfg().getBoolean("hits.counter", true)) label(target, p);
    }

    /** Compteur au-dessus de la cible : nombre de coups et dégâts en attente. */
    private void label(Entity target, Pending p) {
        String text = "<gradient:#ff2bd6:#ffe600><b>x" + p.hits + "</b></gradient> <gray>" + String.format(Locale.US, "%.1f", p.damage)
                + " <red>❤";
        if (p.label == null || !p.label.isValid()) {
            Location at = target.getLocation().add(0, target.getHeight() + 0.45, 0);
            p.label = target.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(true);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            });
        }
        p.label.text(pl.mm(text));
    }

    /** Les étincelles des coups restent suspendues dans l'air, comme le reste. */
    private void frozenSparks() {
        if (tick % 3 != 0) return;
        for (int i = 0; i < sparks.size(); i++) {
            Location l = sparks.get(i);
            l.getWorld().spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(trailColor(i), 0.8f));
        }
    }

    /** Clic près d'un projectile figé : il repartira vers son tireur. */
    void deflect() {
        Location eye = user.getEyeLocation();
        Vector look = eye.getDirection();
        double reach = cfg().getDouble("deflect.reach", 4);
        for (Frozen f : frozen.values()) {
            if (!(f.e instanceof Projectile pr) || deflected.contains(pr.getUniqueId())) continue;
            Vector to = pr.getLocation().toVector().subtract(eye.toVector());
            if (to.length() > reach || to.length() < 0.01 || to.clone().normalize().dot(look) < 0.55) continue;
            deflected.add(pr.getUniqueId());
            pr.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, pr.getLocation(), 12, 0.1, 0.1, 0.1, 0.2);
            sound(user, "item.shield.block", 0.8f, 1.7f);
            sound(user, "block.anvil.land", 0.25f, 2f);
            combo++;
        }
    }

    // ================================================================= fin

    /** Le temps reprend : tout est rendu, puis les coups en attente tombent en cascade. */
    int stop() {
        releasing = true;
        if (task != null) task.cancel();
        Map<UUID, Frozen> was = new HashMap<>(frozen);
        for (Frozen f : frozen.values()) restore(f);
        frozen.clear();
        for (Image im : images) im.m.remove();
        images.clear();
        imageIds.clear();
        boostUser(false);
        tint(false);
        if (user.isOnline()) {
            user.resetPlayerTime();
            if (bar != null) user.hideBossBar(bar);
            user.stopAllSounds();
            sound(user, "ambient.underwater.exit", 1f, 1.1f);
            sound(user, "block.beacon.deactivate", 1f, 1.4f);
            sound(user, "block.glass.break", 0.8f, 0.6f);
            user.sendActionBar(Component.empty());
            user.clearTitle();
        }
        // projectiles renvoyés vers leur tireur
        for (UUID id : deflected) {
            Frozen f = was.get(id);
            if (f == null || !(f.e instanceof Projectile pr) || !pr.isValid()) continue;
            double speed = Math.max(1.6, f.velocity == null ? 0 : f.velocity.length() * 1.3);
            ProjectileSource src = pr.getShooter();
            Vector v = src instanceof LivingEntity le && le.isValid()
                    ? le.getEyeLocation().toVector().subtract(pr.getLocation().toVector()).normalize().multiply(speed)
                    : (f.velocity == null ? new Vector(0, 0, 1) : f.velocity.clone().multiply(-1.3));
            pr.setShooter(user);
            pr.setVelocity(v);
        }
        deflected.clear();
        // les coups tombent, du plus proche au plus loin, un par tick
        List<Map.Entry<UUID, Pending>> order = new ArrayList<>(pending.entrySet());
        Location c = user.getLocation();
        order.sort(Comparator.comparingDouble(en -> {
            Entity e = Bukkit.getEntity(en.getKey());
            return e == null || e.getWorld() != c.getWorld() ? 1e9 : e.getLocation().distanceSquared(c);
        }));
        int delay = 0;
        for (var en : order) {
            Pending p = en.getValue();
            if (p.label != null) p.label.remove();
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof LivingEntity le)) continue;
            Bukkit.getScheduler().runTaskLater(pl, () -> land(le, p), Math.min(20, delay++));
        }
        pending.clear();
        // les étincelles figées éclatent
        for (Location l : sparks) l.getWorld().spawnParticle(Particle.CRIT, l, 6, 0.1, 0.1, 0.1, 0.4);
        sparks.clear();
        aftermath();
        releasing = false;
        return hits;
    }

    private void land(LivingEntity le, Pending p) {
        if (!le.isValid() || le.isDead()) return;
        le.setNoDamageTicks(0);
        if (user.isOnline()) le.damage(p.damage, user);
        else le.damage(p.damage);
        Vector away = le.getLocation().toVector().subtract(user.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-4) away = new Vector(0, 0, 1);
        away.normalize().multiply(Math.min(2.8, 0.4 + p.damage * 0.06)).setY(0.3 + Math.min(0.7, p.damage * 0.02));
        le.setVelocity(away);
        Location l = le.getLocation().add(0, le.getHeight() / 2, 0);
        le.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l, Math.min(6, 1 + p.hits / 2), 0.3, 0.3, 0.3, 0);
        le.getWorld().spawnParticle(Particle.DUST, l, 20, 0.3, 0.4, 0.3, 0, new Particle.DustOptions(Color.fromRGB(0xb0001e), 1.4f));
        le.getWorld().playSound(Sound.sound(Key.key("minecraft", "entity.player.attack.sweep"), Sound.Source.PLAYER, 1f, 0.8f), l.getX(), l.getY(), l.getZ());
        if (p.hits >= 5) le.getWorld().playSound(Sound.sound(Key.key("minecraft", "entity.generic.explode"), Sound.Source.PLAYER, 0.5f, 1.8f), l.getX(), l.getY(), l.getZ());
    }

    /** Le corps paie : un peu de lenteur et de nausée après l'effet (réglable, désactivable). */
    private void aftermath() {
        if (!user.isOnline() || !cfg().getBoolean("aftermath.enabled", true)) return;
        double used = Math.min(1, (System.currentTimeMillis() - startAt) / 10_000.0);
        int slow = (int) (cfg().getDouble("aftermath.slowness-seconds", 2) * 20 * Math.max(0.3, used));
        int nausea = (int) (cfg().getDouble("aftermath.nausea-seconds", 3) * 20 * Math.max(0.3, used));
        if (slow > 0) user.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 1, false, false, true));
        if (nausea > 0) user.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nausea, 0, false, false, true));
    }

    private void restore(Frozen f) {
        Entity e = f.e;
        if (e instanceof Player p) {
            removeModifier(p, Attribute.MOVEMENT_SPEED, pl.freezeKey);
            removeModifier(p, Attribute.JUMP_STRENGTH, pl.freezeKey);
            removeModifier(p, Attribute.GRAVITY, pl.freezeKey);
            if (f.flySpeed >= 0) p.setFlySpeed(f.flySpeed);
            p.resetPlayerTime();
        }
        if (!e.isValid() && !(e instanceof Player)) return;
        e.setGravity(f.gravity);
        if (f.ai != null && e instanceof LivingEntity le) le.setAI(f.ai);
        if (f.pickup >= 0 && e instanceof Item it) it.setPickupDelay(f.pickup);
        if (f.direction != null && e instanceof Fireball fb) fb.setDirection(f.direction);
        if (f.velocity != null) e.setVelocity(f.velocity);
    }

    // ================================================================= outils

    private static void sound(Player p, String key, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, volume, pitch));
    }

    static void modifier(LivingEntity e, Attribute a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst == null) return;
        removeModifier(e, a, key);
        inst.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }

    static void removeModifier(LivingEntity e, Attribute a, NamespacedKey key) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst == null) return;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (m.getKey().equals(key)) inst.removeModifier(m);
        }
    }

    @SuppressWarnings("unused")
    private static World world(Entity e) {
        return e.getWorld();
    }
}
