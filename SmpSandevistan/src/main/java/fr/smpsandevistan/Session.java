package fr.smpsandevistan;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
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
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Un Sandevistan actif : tout est figé autour du joueur, lui bouge. */
final class Session {

    private static final Vector ZERO = new Vector(0, 0, 0);

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

    private record Image(Mannequin m, int dieAt) {
    }

    final SmpSandevistan pl;
    final Player user;
    final int radius;
    final long endAt;
    final Map<UUID, Frozen> frozen = new HashMap<>();
    final Map<UUID, Double> pending = new HashMap<>();
    private final Deque<Image> images = new ArrayDeque<>();
    private final List<UUID> imageIds = new ArrayList<>();
    private BukkitTask task;
    private int tick, hits, color;
    private Location lastImage;
    boolean releasing;

    Session(SmpSandevistan pl, Player user, int seconds) {
        this.pl = pl;
        this.user = user;
        this.radius = Math.max(5, pl.getConfig().getInt("radius", 100));
        this.endAt = System.currentTimeMillis() + seconds * 1000L;
    }

    boolean isImage(Entity e) {
        return imageIds.contains(e.getUniqueId());
    }

    /** Un joueur figé s'est déconnecté : il repart libre. */
    void forget(UUID id) {
        frozen.remove(id);
    }

    boolean isFrozen(Entity e) {
        return frozen.containsKey(e.getUniqueId());
    }

    // ================================================================= début

    int start() {
        boostUser(true);
        scan();
        user.playSound(Sound.sound(Key.key("minecraft", "block.beacon.power_select"), Sound.Source.MASTER, 1f, 0.5f));
        user.playSound(Sound.sound(Key.key("minecraft", "item.trident.thunder"), Sound.Source.MASTER, 0.35f, 2f));
        user.playSound(Sound.sound(Key.key("minecraft", "block.respawn_anchor.charge"), Sound.Source.MASTER, 1f, 1.6f));
        user.getWorld().spawnParticle(Particle.END_ROD, user.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.25);
        task = Bukkit.getScheduler().runTaskTimer(pl, this::safeTick, 1L, 1L);
        return frozen.size();
    }

    private void boostUser(boolean on) {
        if (on) {
            int lvl = Math.max(0, pl.getConfig().getInt("speed-level", 3));
            if (lvl > 0) user.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 3600, lvl - 1, false, false, false));
            if (pl.getConfig().getBoolean("no-attack-cooldown", true)) {
                modifier(user, Attribute.ATTACK_SPEED, pl.attackKey, 100, AttributeModifier.Operation.ADD_NUMBER);
            }
        } else {
            user.removePotionEffect(PotionEffectType.SPEED);
            removeModifier(user, Attribute.ATTACK_SPEED, pl.attackKey);
        }
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
        effects();
        // l'anticheat ne prend pas sa vitesse pour de la triche
        user.getPersistentDataContainer().set(new NamespacedKey("smpguard", "exempt_until"), PersistentDataType.LONG,
                System.currentTimeMillis() + 3000);
    }

    /** Fige tout ce qui entre dans le rayon. */
    private void scan() {
        Location c = user.getLocation();
        double r2 = (double) radius * radius;
        for (Entity e : user.getWorld().getNearbyEntities(c, radius, radius, radius)) {
            if (e == user || isFrozen(e) || isImage(e) || e.getLocation().distanceSquared(c) > r2) continue;
            if (e instanceof Display || e instanceof Interaction || e instanceof Marker || e instanceof Hanging) continue;
            if (e instanceof Projectile pr && pr.getShooter() == user) continue; // ses flèches volent
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
            // le client ne peut plus bouger du tout : vitesse, saut et gravité à zéro
            modifier(p, Attribute.MOVEMENT_SPEED, pl.freezeKey, -1, AttributeModifier.Operation.ADD_SCALAR);
            modifier(p, Attribute.JUMP_STRENGTH, pl.freezeKey, -1, AttributeModifier.Operation.ADD_SCALAR);
            modifier(p, Attribute.GRAVITY, pl.freezeKey, -1, AttributeModifier.Operation.ADD_SCALAR);
            if (p.isFlying()) {
                f.flySpeed = p.getFlySpeed();
                p.setFlySpeed(0);
            }
            if (p.isGliding()) p.setGliding(false);
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

    /** Garde tout immobile (rien ne glisse, la TNT n'avance pas). */
    private void hold() {
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
            if (e.getWorld() == f.at.getWorld() && e.getLocation().distanceSquared(f.at) > (e instanceof Player ? 0.04 : 0.0025)) {
                Location back = f.at.clone();
                if (e instanceof Player) {
                    back.setYaw(e.getLocation().getYaw());
                    back.setPitch(e.getLocation().getPitch());
                }
                pl.snapBack(e, back);
            }
        }
    }

    // ================================================================= effets visuels

    private void effects() {
        var cfg = pl.getConfig();
        long left = Math.max(0, endAt - System.currentTimeMillis());
        if (tick % 2 == 0) {
            int full = (int) Math.round(10.0 * left / Math.max(1, pl.durationMs(this)));
            String bar = "<green>" + "▮".repeat(Math.max(0, Math.min(10, full))) + "<dark_gray>" + "▮".repeat(Math.max(0, 10 - full));
            user.sendActionBar(pl.mm(cfg.getString("messages.actionbar", "<bar> <s>s").replace("<bar>", bar)
                    .replace("<s>", String.format(java.util.Locale.US, "%.1f", left / 1000.0))));
        }
        if (tick % 20 == 0) {
            user.playSound(Sound.sound(Key.key("minecraft", "entity.warden.heartbeat"), Sound.Source.MASTER, 0.9f, 1.3f));
        }
        // traînée de couleurs
        List<String> hex = cfg.getStringList("afterimages.trail-colors");
        if (!hex.isEmpty()) {
            Color col = color(hex.get((tick / 3) % hex.size()));
            user.getWorld().spawnParticle(Particle.DUST, user.getLocation().add(0, 1, 0), 3, 0.25, 0.5, 0.25, 0,
                    new Particle.DustOptions(col, 1.3f));
        }
        // images rémanentes
        while (!images.isEmpty() && images.peekFirst().dieAt() <= tick) {
            Image im = images.pollFirst();
            imageIds.remove(im.m().getUniqueId());
            im.m().remove();
        }
        if (!cfg.getBoolean("afterimages.enabled", true)) return;
        int every = Math.max(1, cfg.getInt("afterimages.every-ticks", 2));
        if (tick % every != 0 || images.size() >= cfg.getInt("afterimages.max", 14)) return;
        Location here = user.getLocation();
        if (lastImage != null && lastImage.getWorld() == here.getWorld() && lastImage.distanceSquared(here) < 0.35 * 0.35) return;
        lastImage = here.clone();
        spawnImage(here, cfg.getInt("afterimages.life-ticks", 12));
    }

    private void spawnImage(Location at, int life) {
        List<String> names = pl.getConfig().getStringList("afterimages.glow-colors");
        NamedTextColor glow = names.isEmpty() ? NamedTextColor.GREEN
                : NamedTextColor.NAMES.valueOr(names.get(color++ % names.size()).toLowerCase(java.util.Locale.ROOT), NamedTextColor.GREEN);
        Mannequin m = at.getWorld().spawn(at, Mannequin.class, e -> {
            e.setPersistent(false);
            e.setProfile(ResolvableProfile.resolvableProfile(user.getPlayerProfile()));
            e.setImmovable(true);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSilent(true);
            e.setCollidable(false);
            e.setDescription(Component.empty());
            e.getEquipment().setArmorContents(user.getInventory().getArmorContents());
            e.getEquipment().setItemInMainHand(user.getInventory().getItemInMainHand());
            e.getEquipment().setItemInOffHand(user.getInventory().getItemInOffHand());
            if (user.isSneaking()) e.setPose(Pose.SNEAKING, true);
            e.setGlowing(true);
        });
        glowTeam(glow).addEntry(m.getUniqueId().toString());
        images.addLast(new Image(m, tick + Math.max(2, life)));
        imageIds.add(m.getUniqueId());
    }

    private static Team glowTeam(NamedTextColor c) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = "sdv_" + c.toString();
        Team t = sb.getTeam(name);
        if (t == null) {
            t = sb.registerNewTeam(name);
            t.color(c);
            t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return t;
    }

    private static Color color(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
        } catch (RuntimeException e) {
            return Color.LIME;
        }
    }

    // ================================================================= coups en attente

    void hit(Entity target, double damage) {
        pending.merge(target.getUniqueId(), damage, Double::sum);
        hits++;
        Location l = target.getLocation().add(0, target.getHeight() / 2, 0);
        target.getWorld().spawnParticle(Particle.CRIT, l, 10, 0.25, 0.3, 0.25, 0.1);
        target.getWorld().spawnParticle(Particle.DUST, l, 6, 0.2, 0.3, 0.2, 0,
                new Particle.DustOptions(Color.fromRGB(0x00e5ff), 1.1f));
        user.playSound(Sound.sound(Key.key("minecraft", "entity.player.attack.crit"), Sound.Source.MASTER, 0.7f, 1.6f));
    }

    // ================================================================= fin

    /** Le temps reprend : tout est rendu, puis les coups en attente tombent d'un coup. */
    int stop() {
        releasing = true;
        if (task != null) task.cancel();
        for (Frozen f : frozen.values()) restore(f);
        Map<UUID, Frozen> was = new HashMap<>(frozen);
        frozen.clear();
        for (Image im : images) im.m().remove();
        images.clear();
        imageIds.clear();
        boostUser(false);
        int landed = 0;
        for (var en : pending.entrySet()) {
            Frozen f = was.get(en.getKey());
            Entity e = f != null ? f.e : Bukkit.getEntity(en.getKey());
            if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
            double amount = en.getValue();
            le.setNoDamageTicks(0);
            if (user.isOnline()) le.damage(amount, user);
            else le.damage(amount);
            Vector away = le.getLocation().toVector().subtract(user.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(0, 0, 1);
            away.normalize().multiply(Math.min(2.5, 0.4 + amount * 0.06)).setY(0.35 + Math.min(0.6, amount * 0.02));
            le.setVelocity(away);
            le.getWorld().spawnParticle(Particle.SWEEP_ATTACK, le.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0);
            landed++;
        }
        pending.clear();
        if (user.isOnline()) {
            user.playSound(Sound.sound(Key.key("minecraft", "block.beacon.deactivate"), Sound.Source.MASTER, 1f, 1.4f));
            if (landed > 0) user.playSound(Sound.sound(Key.key("minecraft", "entity.generic.explode"), Sound.Source.MASTER, 0.6f, 1.8f));
            user.sendActionBar(Component.empty());
        }
        releasing = false;
        return hits;
    }

    private void restore(Frozen f) {
        Entity e = f.e;
        if (e instanceof Player p) {
            removeModifier(p, Attribute.MOVEMENT_SPEED, pl.freezeKey);
            removeModifier(p, Attribute.JUMP_STRENGTH, pl.freezeKey);
            removeModifier(p, Attribute.GRAVITY, pl.freezeKey);
            if (f.flySpeed >= 0) p.setFlySpeed(f.flySpeed);
        }
        if (!e.isValid() && !(e instanceof Player)) return;
        e.setGravity(f.gravity);
        if (f.ai != null && e instanceof LivingEntity le) le.setAI(f.ai);
        if (f.pickup >= 0 && e instanceof Item it) it.setPickupDelay(f.pickup);
        if (f.direction != null && e instanceof Fireball fb) fb.setDirection(f.direction);
        if (f.velocity != null) e.setVelocity(f.velocity);
    }

    // ================================================================= attributs

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
}
