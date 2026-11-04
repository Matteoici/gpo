package fr.smpmonde;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpmonde.SmpMonde.fmt;
import static fr.smpmonde.SmpMonde.mm;

/**
 * 👹 Raid du Colosse du Néant : boss mondial à l'arène (/raid lieu).
 * Sa vie grandit avec le nombre de joueurs présents. Séisme, rochers, serviteurs, charge ; enragé sous 30 %.
 * À sa mort, l'argent et l'XP de saison sont partagés entre tous ceux qui ont tapé, selon leurs dégâts.
 */
final class Raid implements Listener {

    private final SmpMonde plugin;
    private final NamespacedKey bossKey, minionKey;
    private Ravager boss;
    private BossBar bar;
    private BukkitTask task, warnTask;
    private double hp, maxHp;
    private int players;
    private long end;
    private boolean warning, dying;
    private int tick;
    private final Map<UUID, Double> damage = new HashMap<>();
    private final List<UUID> minions = new ArrayList<>();
    private final List<UUID> rocks = new ArrayList<>();

    Raid(SmpMonde plugin) {
        this.plugin = plugin;
        bossKey = new NamespacedKey(plugin, "raid_boss");
        minionKey = new NamespacedKey(plugin, "raid_minion");
    }

    boolean active() {
        return warning || (boss != null && boss.isValid());
    }

    private double c(String k, double def) {
        return plugin.getConfig().getDouble("raid." + k, def);
    }

    Location arena() {
        String s = plugin.getConfig().getString("raid.location", "");
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(",");
        try {
            World w = Bukkit.getWorld(p[0]);
            return w == null ? null : new Location(w, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]), Integer.parseInt(p[3]) + 0.5);
        } catch (RuntimeException e) {
            return null;
        }
    }

    void announce() {
        if (active()) return;
        Location a = arena();
        if (a == null) {
            plugin.getLogger().warning("Raid : pas d'arène (/raid lieu). Raid annulé.");
            return;
        }
        int min = (int) c("warning-minutes", 5);
        warning = true;
        Bukkit.broadcast(mm("<dark_purple><b>👹 RAID DU COLOSSE</b> <gray>dans <white>" + min + " min</white> ! Venez nombreux : sa vie grandit avec le nombre de joueurs, "
                + "et <gold>" + fmt(c("money-pool", 1500000)) + " $</gold> sont partagés selon les dégâts." + (plugin.getConfig().getBoolean("raid.allow-tp", true) ? " <yellow>/raid tp" : "")));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 0.8f);
        warnTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            warning = false;
            spawn();
        }, min * 60L * 20L);
    }

    void tp(Player p) {
        Location a = arena();
        if (!active() || a == null) {
            p.sendMessage(mm("<gray>Pas de raid en ce moment. <white>/evenements"));
            return;
        }
        if (!plugin.getConfig().getBoolean("raid.allow-tp", true)) {
            p.sendMessage(mm("<gray>Le raid est en <white>" + a.getBlockX() + " " + a.getBlockY() + " " + a.getBlockZ()));
            return;
        }
        Location to = a.clone().add(ThreadLocalRandom.current().nextDouble(-6, 6), 0, ThreadLocalRandom.current().nextDouble(-6, 6));
        to.setY(a.getWorld().getHighestBlockYAt(to) + 1);
        p.teleportAsync(to.add(0, 0, 0).toCenterLocation().subtract(0, 0.5, 0));
        p.sendMessage(mm("<dark_purple>👹 Arène du Colosse !"));
    }

    void spawn() {
        if (boss != null && boss.isValid()) return;
        if (warnTask != null) warnTask.cancel();
        warning = false;
        Location a = arena();
        if (a == null) return;
        a.getChunk().load();
        damage.clear();
        dying = false;
        players = Math.max(1, near(a, 60).size());
        maxHp = c("base-health", 2000) + c("health-per-player", 600) * players;
        hp = maxHp;
        end = System.currentTimeMillis() + (long) (c("duration-minutes", 20) * 60_000);
        tick = 0;
        boss = a.getWorld().spawn(a, Ravager.class, r -> {
            r.customName(mm("<dark_purple><b>👹 Colosse du Néant"));
            r.setCustomNameVisible(true);
            r.setRemoveWhenFarAway(false);
            r.setPersistent(false);
            r.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            set(r, Attribute.MAX_HEALTH, 1000);
            r.setHealth(1000);
            set(r, Attribute.SCALE, 2.4);
            set(r, Attribute.ATTACK_DAMAGE, 18);
            set(r, Attribute.KNOCKBACK_RESISTANCE, 1);
            set(r, Attribute.MOVEMENT_SPEED, 0.28);
            set(r, Attribute.FOLLOW_RANGE, 48);
            r.setGlowing(true);
        });
        bar = BossBar.bossBar(mm("<dark_purple><b>👹 Colosse du Néant"), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20);
        World w = a.getWorld();
        w.strikeLightningEffect(a);
        w.playSound(a, Sound.ENTITY_WITHER_SPAWN, 10f, 0.5f);
        for (Player p : near(a, 120)) p.showTitle(Title.title(mm("<dark_purple><b>👹 LE COLOSSE"), mm("<gray>Tous ensemble !"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        Bukkit.broadcast(mm("<dark_purple><b>👹 Le Colosse du Néant est arrivé !</b> <gray>" + fmt(maxHp) + " PV (" + players + " joueur(s))"
                + (plugin.getConfig().getBoolean("raid.allow-tp", true) ? " · <yellow>/raid tp" : "")));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::loop, 20L, 10L);
    }

    private static void set(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private List<Player> near(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.getLocation().distanceSquared(c) <= r * r) out.add(p);
        }
        return out;
    }

    private void loop() {
        if (boss == null || !boss.isValid()) {
            stop(false);
            return;
        }
        tick++;
        Location a = arena(), b = boss.getLocation();
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            Bukkit.broadcast(mm("<dark_purple>👹 Le Colosse s'est lassé et repart dans le néant... <gray>(temps écoulé)"));
            stop(false);
            return;
        }
        // plus de monde arrive : il grossit (on garde le même pourcentage de vie)
        int now = near(b, 60).size();
        if (now > players) {
            double ratio = hp / maxHp;
            players = now;
            maxHp = c("base-health", 2000) + c("health-per-player", 600) * players;
            hp = maxHp * ratio;
        }
        // il ne quitte pas l'arène
        if (a != null && b.distanceSquared(a) > 30 * 30) boss.teleport(a);
        boolean enraged = hp / maxHp < 0.3;
        boss.setHealth(Math.max(1, Math.min(1000, 1000 * hp / maxHp)));
        bar.progress((float) Math.max(0, Math.min(1, hp / maxHp)));
        bar.name(mm("<dark_purple><b>👹 Colosse du Néant</b> " + (enraged ? "<red><b>ENRAGÉ</b> " : "") + "<white>" + fmt(hp) + " / " + fmt(maxHp)
                + " <dark_gray>· <gray>" + (left / 60000) + ":" + String.format("%02d", left / 1000 % 60)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(b.getWorld()) && p.getLocation().distanceSquared(b) < 100 * 100) p.showBossBar(bar);
            else p.hideBossBar(bar);
        }
        List<Player> targets = near(b, 40);
        if (!targets.isEmpty() && (boss.getTarget() == null || !(boss.getTarget() instanceof Player) || tick % 10 == 0)) {
            boss.setTarget(targets.get(ThreadLocalRandom.current().nextInt(targets.size())));
        }
        b.getWorld().spawnParticle(Particle.DUST, b.clone().add(0, 2, 0), 10, 1.5, 1.5, 1.5, 0, new Particle.DustOptions(Color.fromRGB(90, 0, 140), 2f), true);
        // une attaque toutes les 3 s (2 s enragé)
        int every = enraged ? 4 : 6;
        if (tick % every == 0 && !targets.isEmpty()) {
            int r = ThreadLocalRandom.current().nextInt(enraged ? 5 : 4);
            switch (r) {
                case 0 -> quake(b);
                case 1 -> rocks(b, targets);
                case 2 -> summon(b);
                default -> charge(targets);
            }
        }
    }

    /** Séisme : il frappe le sol, tout ce qui est autour saute et prend des dégâts. */
    private void quake(Location b) {
        World w = b.getWorld();
        w.playSound(b, Sound.ENTITY_RAVAGER_ROAR, 4f, 0.5f);
        w.playSound(b, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.6f);
        for (int ring = 1; ring <= 10; ring++) {
            int rr = ring;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (int i = 0; i < 24; i++) {
                    double ang = i * Math.PI * 2 / 24;
                    w.spawnParticle(Particle.BLOCK, b.clone().add(Math.cos(ang) * rr, 0.2, Math.sin(ang) * rr), 4, 0.2, 0.1, 0.2, 0, Material.DEEPSLATE.createBlockData());
                }
            }, ring);
        }
        for (Player p : near(b, 10)) {
            if (!p.isOnGround()) continue; // sauter au bon moment l'évite
            p.damage(8, boss);
            p.setVelocity(p.getVelocity().setY(0.9));
        }
    }

    /** Rochers : il lance des blocs sur des joueurs au hasard. */
    private void rocks(Location b, List<Player> targets) {
        World w = b.getWorld();
        w.playSound(b, Sound.ENTITY_IRON_GOLEM_ATTACK, 3f, 0.5f);
        for (int i = 0; i < Math.min(3, targets.size()); i++) {
            Player t = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
            Location to = t.getLocation().clone();
            Location from = b.clone().add(0, 4, 0);
            BlockDisplay d = w.spawn(from, BlockDisplay.class, e -> {
                e.setBlock(Material.CRYING_OBSIDIAN.createBlockData());
                e.setTransformation(new Transformation(new Vector3f(-0.6f, -0.6f, -0.6f), new AxisAngle4f(), new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f()));
                e.setTeleportDuration(2);
                e.setBrightness(new Display.Brightness(12, 12));
                e.setPersistent(false);
            });
            rocks.add(d.getUniqueId());
            int steps = 15;
            int[] k = {0};
            BukkitTask[] tt = new BukkitTask[1];
            tt[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                double f = ++k[0] / (double) steps;
                Location at = from.clone().add(to.toVector().subtract(from.toVector()).multiply(f)).add(0, Math.sin(f * Math.PI) * 6, 0);
                if (!d.isValid()) {
                    tt[0].cancel();
                    return;
                }
                d.teleport(at);
                w.spawnParticle(Particle.REVERSE_PORTAL, at, 4, 0.2, 0.2, 0.2, 0.01);
                if (k[0] >= steps) {
                    tt[0].cancel();
                    d.remove();
                    w.spawnParticle(Particle.EXPLOSION, to, 2, 0.5, 0.2, 0.5, 0);
                    w.playSound(to, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.9f);
                    for (Player p : near(to, 2.8)) p.damage(7, boss);
                }
            }, 1L, 2L);
        }
    }

    /** Serviteurs : des vex sortent du néant (au plus 10 en même temps). */
    private void summon(Location b) {
        minions.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        if (minions.size() >= 10) {
            quake(b);
            return;
        }
        b.getWorld().playSound(b, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 3f, 0.7f);
        for (int i = 0; i < 4; i++) {
            Location l = b.clone().add(ThreadLocalRandom.current().nextDouble(-3, 3), 2.5, ThreadLocalRandom.current().nextDouble(-3, 3));
            Vex v = b.getWorld().spawn(l, Vex.class, e -> {
                e.customName(mm("<dark_purple>Serviteur du Néant"));
                e.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
                e.setLimitedLifetimeTicks(20 * 40);
            });
            minions.add(v.getUniqueId());
        }
    }

    /** Charge : il fonce sur le joueur le plus loin. */
    private void charge(List<Player> targets) {
        Player far = targets.get(0);
        for (Player p : targets) if (p.getLocation().distanceSquared(boss.getLocation()) > far.getLocation().distanceSquared(boss.getLocation())) far = p;
        Vector dir = far.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 4) {
            quake(boss.getLocation());
            return;
        }
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 4f, 0.8f);
        boss.setVelocity(dir.normalize().multiply(2.2).setY(0.35));
        boss.setTarget(far);
    }

    void stop(boolean admin) {
        if (warnTask != null) warnTask.cancel();
        warning = false;
        if (task != null) task.cancel();
        task = null;
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        if (boss != null && boss.isValid()) boss.remove();
        boss = null;
        for (UUID id : minions) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        for (UUID id : rocks) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        minions.clear();
        rocks.clear();
        if (admin) Bukkit.broadcast(mm("<gray>👹 Le raid a été arrêté."));
    }

    /** Victoire : partage du butin selon les dégâts. */
    private void win() {
        if (boss == null) return;
        Location at = boss.getLocation();
        at.getWorld().strikeLightningEffect(at);
        at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 4, 1.5, 1.5, 1.5, 0);
        at.getWorld().playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 6f, 0.8f);
        double total = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(damage.entrySet());
        list.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        Bukkit.broadcast(mm(""));
        Bukkit.broadcast(mm("<dark_purple><b>👹 LE COLOSSE EST TOMBÉ !</b> <gray>Le butin est partagé selon les dégâts :"));
        double minShare = c("min-share-percent", 1) / 100.0;
        for (int i = 0; i < list.size(); i++) {
            var off = Bukkit.getOfflinePlayer(list.get(i).getKey());
            double share = total <= 0 ? 0 : list.get(i).getValue() / total;
            if (share < minShare) continue;
            double money = c("money-pool", 1500000) * share, xp = c("season-xp-pool", 6000) * share;
            plugin.money(off, money);
            plugin.seasonXp(off.getName(), xp, "raid du Colosse");
            if (i < 3) plugin.reward(off, plugin.getConfig().getStringList("raid.top-rewards." + (i + 1)));
            if (i < 8) Bukkit.broadcast(mm("  " + (i == 0 ? "<gold>🥇" : i == 1 ? "<gray>🥈" : i == 2 ? "<#cd7f32>🥉" : "<dark_gray>" + (i + 1) + ".")
                    + " <white>" + off.getName() + " <dark_gray>— <gray>" + Math.round(share * 100) + " % <dark_gray>· <gold>+" + fmt(money) + " $"));
            Player p = off.getPlayer();
            if (p != null) p.sendMessage(mm("<dark_purple>👹 Ta part : <gold>" + fmt(money) + " $</gold> <gray>+ " + fmt(xp) + " XP de saison (" + Math.round(share * 100) + " % des dégâts)"));
        }
        stop(false);
    }

    // ================================================================= écoute

    private boolean isBoss(Entity e) {
        return e != null && e.getPersistentDataContainer().has(bossKey);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!isBoss(e.getEntity()) || boss == null) return;
        if (dying) {
            e.setCancelled(true);
            return;
        }
        switch (e.getCause()) {
            case FALL, SUFFOCATION, DROWNING, FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CONTACT, CRAMMING, FLY_INTO_WALL -> {
                e.setCancelled(true);
                return;
            }
            default -> {
            }
        }
        double dealt = Math.min(e.getFinalDamage(), maxHp * c("max-hit-percent", 6) / 100.0);
        Player who = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            Entity d = ee.getDamager();
            if (d instanceof Player p) who = p;
            else if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) who = p;
        }
        if (who == null) {
            e.setCancelled(true); // seuls les joueurs comptent (pas les explosions de creeper...)
            return;
        }
        damage.merge(who.getUniqueId(), dealt, Double::sum);
        hp -= dealt;
        e.setDamage(0.001); // l'animation de coup, mais c'est notre vie à nous qui compte
        if (hp <= 0 && !dying) {
            dying = true;
            Bukkit.getScheduler().runTask(plugin, this::win);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (isBoss(e.getEntity()) || e.getEntity().getPersistentDataContainer().has(minionKey)) {
            e.getDrops().clear();
            e.setDroppedExp(isBoss(e.getEntity()) ? 0 : 5);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrief(EntityChangeBlockEvent e) {
        if (isBoss(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        // le Colosse et ses serviteurs ne visent que les joueurs
        if ((isBoss(e.getEntity()) || e.getEntity().getPersistentDataContainer().has(minionKey)) && e.getTarget() != null && !(e.getTarget() instanceof Player))
            e.setCancelled(true);
    }
}
