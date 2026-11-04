package fr.smpmonde;

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
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpmonde.SmpMonde.mm;

/**
 * ☄ Pluie de météores : quelques météorites tombent loin du spawn et des territoires de guilde.
 * Chacune creuse un petit cratère rempli de minerais, gardé par un Golem de météorite.
 */
final class Meteors implements Listener {

    private final SmpMonde plugin;
    private final NamespacedKey guardKey;
    private final List<UUID> spawned = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean running;
    private final List<org.bukkit.Chunk> tickets = new ArrayList<>();

    Meteors(SmpMonde plugin) {
        this.plugin = plugin;
        guardKey = new NamespacedKey(plugin, "meteor_guard");
    }

    boolean running() {
        return running;
    }

    private double c(String k, double def) {
        return plugin.getConfig().getDouble("meteors." + k, def);
    }

    void start() {
        if (running) return;
        running = true;
        int warn = (int) c("warning-seconds", 60);
        Bukkit.broadcast(mm("<gold><b>☄ Pluie de météores</b> <gray>dans <white>" + warn + " s</gray> ! Des météorites pleines de minerais vont s'écraser... gardées par des golems."));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.6f, 0.6f);
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, this::launch, warn * 20L));
    }

    /** Choisit les points de chute (chunks chargés en asynchrone) puis fait tomber les météorites une à une. */
    private void launch() {
        World w = plugin.world();
        int count = (int) c("count", 4);
        List<Location> spots = new ArrayList<>();
        find(w, count, 40, spots);
    }

    private void find(World w, int count, int tries, List<Location> spots) {
        if (spots.size() >= count || tries <= 0) {
            fall(spots, 0);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double cx = plugin.getConfig().getDouble("center-x", 0), cz = plugin.getConfig().getDouble("center-z", 0);
        double min = c("min-radius", 300), max = c("max-radius", 2000);
        // de préférence pas trop loin d'un joueur (pour qu'on puisse y aller), sinon n'importe où dans la zone
        List<Player> near = new ArrayList<>();
        if (plugin.getConfig().getBoolean("meteors.near-players", true))
            for (Player p : w.getPlayers()) if (p.getGameMode() == GameMode.SURVIVAL && plugin.allowed(p.getLocation())) near.add(p);
        if (!near.isEmpty()) {
            Location c = near.get(r.nextInt(near.size())).getLocation();
            cx = c.getX();
            cz = c.getZ();
            min = c("near-min", 80);
            max = c("near-max", 250);
        }
        double a = r.nextDouble() * Math.PI * 2;
        double d = Math.sqrt(r.nextDouble() * (max * max - min * min) + min * min);
        int x = (int) (cx + Math.cos(a) * d), z = (int) (cz + Math.sin(a) * d);
        w.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((ch, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            Location g = null;
            if (err == null && ch != null) {
                // le chunk reste chargé jusqu'à la fin (chute, cratère, golem)
                ch.addPluginChunkTicket(plugin);
                tickets.add(ch);
                g = SmpMonde.ground(w, x, z);
            }
            if (g != null && plugin.allowed(g)) spots.add(g);
            find(w, count, tries - 1, spots);
        }));
    }

    private void fall(List<Location> spots, int i) {
        if (i >= spots.size()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> running = false, 200L);
            List<org.bukkit.Chunk> mine = new ArrayList<>(tickets);
            tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (org.bukkit.Chunk ch : mine) ch.removePluginChunkTicket(plugin);
                tickets.removeAll(mine);
            }, 20L * 60 * 16));
            if (spots.isEmpty()) {
                running = false;
                Bukkit.broadcast(mm("<gray>☄ Les météorites se sont désintégrées dans l'atmosphère..."));
            }
            return;
        }
        Location target = spots.get(i);
        Bukkit.broadcast(mm("<gold>☄ Météorite en approche <white>" + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ()
                + "</white> <gray>(" + (i + 1) + "/" + spots.size() + ")"));
        for (Player p : target.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(target) < 200 * 200) {
                p.showTitle(Title.title(mm("<gold><b>☄ MÉTÉORITE"), mm("<white>" + target.getBlockX() + " " + target.getBlockZ()),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
            }
        }
        // la boule de feu
        World w = target.getWorld();
        Vector dir = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1)).normalize().multiply(28);
        Location from = target.clone().add(dir).add(0, 90, 0);
        org.bukkit.Chunk fc = from.getChunk(); // chargé (et gardé) pour que la boule de feu existe
        fc.addPluginChunkTicket(plugin);
        tickets.add(fc);
        BlockDisplay rock = w.spawn(from, BlockDisplay.class, d -> {
            d.setBlock(Material.MAGMA_BLOCK.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(-1.5f, -1.5f, -1.5f), new AxisAngle4f(), new Vector3f(3, 3, 3), new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(2);
            d.setViewRange(8f);
            d.setPersistent(false);
        });
        spawned.add(rock.getUniqueId());
        int steps = 50;
        int[] k = {0};
        BukkitTask[] t = new BukkitTask[1];
        t[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int s = ++k[0];
            double f = s / (double) steps;
            Location at = from.clone().add(target.toVector().subtract(from.toVector()).multiply(f));
            if (!rock.isValid()) {
                t[0].cancel();
                return;
            }
            rock.teleport(at);
            w.spawnParticle(Particle.FLAME, at, 25, 1, 1, 1, 0.05, null, true);
            w.spawnParticle(Particle.LARGE_SMOKE, at, 12, 1.2, 1.2, 1.2, 0.02, null, true);
            w.spawnParticle(Particle.LAVA, at, 4, 0.8, 0.8, 0.8, 0, null, true);
            if (s % 10 == 0) w.playSound(at, Sound.ENTITY_BLAZE_SHOOT, 6f, 0.4f);
            if (s >= steps) {
                t[0].cancel();
                rock.remove();
                impact(target);
                Bukkit.getScheduler().runTaskLater(plugin, () -> fall(spots, i + 1), 60L);
            }
        }, 1L, 2L);
        tasks.add(t[0]);
    }

    private void impact(Location c) {
        World w = c.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 3, 1, 1, 1, 0, null, true);
        w.spawnParticle(Particle.LAVA, c, 80, 3, 2, 3, 0, null, true);
        w.spawnParticle(Particle.DUST, c, 120, 4, 2, 4, 0, new Particle.DustOptions(Color.fromRGB(255, 120, 20), 2.5f), true);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 8f, 0.5f);
        w.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 8f, 0.6f);
        for (Player p : w.getPlayers()) {
            double d = p.getLocation().distance(c);
            if (d < 6 && p.getGameMode() == GameMode.SURVIVAL) {
                p.damage(c("impact-damage", 10) * (1 - d / 7));
                p.setVelocity(p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(1.2).setY(0.7));
            }
        }
        crater(c);
        guardian(c.clone().add(0, 0.2, 0));
    }

    /** Cratère : on creuse une demi-sphère et on tapisse le fond de roche de météorite et de minerais. */
    private void crater(Location c) {
        int r = (int) Math.max(2, Math.min(6, c("crater-radius", 3)));
        Map<Material, Double> ores = new LinkedHashMap<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("meteors.ores");
        if (s != null) for (String k : s.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null && m.isBlock()) ores.put(m, s.getDouble(k));
        }
        if (ores.isEmpty()) ores.put(Material.MAGMA_BLOCK, 1.0);
        double total = ores.values().stream().mapToDouble(Double::doubleValue).sum();
        World w = c.getWorld();
        int cx = c.getBlockX(), cy = c.getBlockY() - 1, cz = c.getBlockZ();
        for (int x = -r - 1; x <= r + 1; x++) for (int y = -r - 1; y <= r; y++) for (int z = -r - 1; z <= r + 1; z++) {
            double d = Math.sqrt(x * x + y * y + z * z);
            Block b = w.getBlockAt(cx + x, cy + y, cz + z);
            if (b.getType() == Material.BEDROCK || b.getState() instanceof TileState) continue;
            if (y >= 0 && d <= r) {
                b.setType(Material.AIR, false);
            } else if (y < 0 && d <= r) {
                b.setType(Material.AIR, false);
            } else if (d <= r + 1.2 && y < 1 && !b.getType().isAir()) {
                double roll = ThreadLocalRandom.current().nextDouble() * total;
                for (Map.Entry<Material, Double> e : ores.entrySet()) {
                    roll -= e.getValue();
                    if (roll <= 0) {
                        b.setType(e.getKey(), false);
                        break;
                    }
                }
            }
        }
        // le cœur au fond
        w.getBlockAt(cx, cy - r, cz).setType(Material.CRYING_OBSIDIAN, false);
        w.getBlockAt(cx, cy - r + 1, cz).setType(Material.FIRE, false);
    }

    private void guardian(Location c) {
        Location at = c.clone();
        at.setY(at.getY() - Math.max(2, Math.min(6, c("crater-radius", 3))) + 0.5);
        IronGolem g = c.getWorld().spawn(at, IronGolem.class, e -> {
            e.setPlayerCreated(false);
            e.customName(mm("<gold><b>☄ Golem de météorite"));
            e.setCustomNameVisible(true);
            e.setRemoveWhenFarAway(false);
            e.getPersistentDataContainer().set(guardKey, PersistentDataType.BYTE, (byte) 1);
            AttributeInstance mh = e.getAttribute(Attribute.MAX_HEALTH);
            if (mh != null) mh.setBaseValue(Math.min(1024, c("guardian-health", 250)));
            e.setHealth(Math.min(1024, c("guardian-health", 250)));
            e.setVisualFire(true);
        });
        spawned.add(g.getUniqueId());
        // il attaque le joueur le plus proche
        BukkitTask[] t = new BukkitTask[1];
        t[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!g.isValid()) {
                t[0].cancel();
                return;
            }
            Player best = null;
            double bd = 18 * 18;
            for (Player p : g.getWorld().getPlayers()) {
                if (p.getGameMode() != GameMode.SURVIVAL) continue;
                double d = p.getLocation().distanceSquared(g.getLocation());
                if (d < bd) {
                    bd = d;
                    best = p;
                }
            }
            if (best != null && g.getTarget() != best) g.setTarget(best);
            g.getWorld().spawnParticle(Particle.FLAME, g.getLocation().add(0, 1.4, 0), 4, 0.5, 0.8, 0.5, 0.01);
        }, 20L, 20L);
        tasks.add(t[0]);
        // disparaît au bout de 15 min s'il n'est pas tué
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (g.isValid()) g.remove();
        }, 20L * 60 * 15));
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity d = e.getEntity();
        if (!d.getPersistentDataContainer().has(guardKey)) return;
        e.getDrops().clear();
        e.getDrops().add(new org.bukkit.inventory.ItemStack(Material.IRON_BLOCK, 2 + ThreadLocalRandom.current().nextInt(3)));
        e.getDrops().add(new org.bukkit.inventory.ItemStack(Material.DIAMOND, 2 + ThreadLocalRandom.current().nextInt(4)));
        if (ThreadLocalRandom.current().nextDouble() < 0.25) e.getDrops().add(new org.bukkit.inventory.ItemStack(Material.NETHERITE_SCRAP));
        e.setDroppedExp(120);
        Player k = d.getKiller();
        if (k == null) return;
        plugin.reward(k, plugin.getConfig().getStringList("meteors.guardian-rewards"));
        Bukkit.broadcast(mm("<gold>☄ <white>" + k.getName() + "</white> a vaincu un <b>Golem de météorite</b> !"));
    }

    void cleanup() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        for (UUID id : spawned) {
            var e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        spawned.clear();
        for (org.bukkit.Chunk ch : tickets) ch.removePluginChunkTicket(plugin);
        tickets.clear();
        running = false;
    }
}
