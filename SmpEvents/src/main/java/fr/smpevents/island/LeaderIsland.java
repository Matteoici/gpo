package fr.smpevents.island;

import fr.smpevents.DataStore;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import fr.smpevents.board.Board;
import fr.smpevents.board.Holograms;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static fr.smpevents.Texts.parse;

/**
 * L'Île des Classements : une île flottante générée avec tous les classements en hologrammes,
 * un portail au spawn pour y aller et un portail sur l'île pour revenir au spawn.
 *
 * /classement ile                      aller sur l'île (tout le monde)
 * /classement ile construire [taille] [ici|ciel|Y]   générer l'île (admin) — /classement ile annuler pour tout remettre
 * /classement ile portail [retirer]    poser l'arche du spawn devant soi (admin)
 * /classement ile arrivee              changer le point d'arrivée sur l'île (admin)
 */
public final class LeaderIsland implements Listener {

    private final SmpEvents plugin;
    private final File file, backupFile, gateBackupFile;
    private final NamespacedKey labelKey;

    // île
    private String world;
    private int cx, cy, cz, size;
    private boolean built;
    private double[] arrival;          // x, y, z, yaw, pitch (absolu)
    private int[] retBox;              // portail de retour (absolu)
    private final List<String> holoIds = new ArrayList<>();
    // arche du spawn
    private String gateWorld;
    private int[] gateBox;
    private double[] gateLabelPos;

    private TextDisplay title, retLabel, gateLabel;
    private BukkitTask job;
    private final Map<UUID, long[]> confirm = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public LeaderIsland(SmpEvents plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "island.yml");
        this.backupFile = new File(plugin.getDataFolder(), "island-backup.dat");
        this.gateBackupFile = new File(plugin.getDataFolder(), "island-gate-backup.dat");
        this.labelKey = new NamespacedKey(plugin, "island_label");
    }

    // ================================================================= sauvegarde

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        world = y.getString("island.world");
        cx = y.getInt("island.x");
        cy = y.getInt("island.y");
        cz = y.getInt("island.z");
        size = y.getInt("island.size", 24);
        built = y.getBoolean("island.built", false);
        arrival = doubles(y.getDoubleList("island.arrival"), 5);
        List<Integer> rb = y.getIntegerList("island.return-box");
        retBox = rb.size() == 6 ? new int[]{rb.get(0), rb.get(1), rb.get(2), rb.get(3), rb.get(4), rb.get(5)} : null;
        holoIds.clear();
        holoIds.addAll(y.getStringList("island.holograms"));
        gateWorld = y.getString("gate.world");
        List<Integer> gb = y.getIntegerList("gate.box");
        gateBox = gb.size() == 6 ? new int[]{gb.get(0), gb.get(1), gb.get(2), gb.get(3), gb.get(4), gb.get(5)} : null;
        gateLabelPos = doubles(y.getDoubleList("gate.label"), 3);
    }

    private static double[] doubles(List<Double> l, int n) {
        if (l == null || l.size() != n) return null;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = l.get(i);
        return out;
    }

    private static List<Double> list(double[] a) {
        List<Double> out = new ArrayList<>();
        if (a != null) for (double v : a) out.add(v);
        return out;
    }

    private static List<Integer> list(int[] a) {
        List<Integer> out = new ArrayList<>();
        if (a != null) for (int v : a) out.add(v);
        return out;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        if (world != null) {
            y.set("island.world", world);
            y.set("island.x", cx);
            y.set("island.y", cy);
            y.set("island.z", cz);
            y.set("island.size", size);
            y.set("island.built", built);
            y.set("island.arrival", list(arrival));
            y.set("island.return-box", list(retBox));
            y.set("island.holograms", new ArrayList<>(holoIds));
        }
        if (gateWorld != null && gateBox != null) {
            y.set("gate.world", gateWorld);
            y.set("gate.box", list(gateBox));
            y.set("gate.label", list(gateLabelPos));
        }
        DataStore.write(file, y.saveToString());
    }

    // ================================================================= zones

    public boolean built() {
        return built && Bukkit.getWorld(world) != null;
    }

    public Location arrival() {
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w == null || arrival == null) return null;
        return new Location(w, arrival[0], arrival[1], arrival[2], (float) arrival[3], (float) arrival[4]);
    }

    /** Sur l'île (cylindre large, du dessous jusqu'au ciel). */
    public boolean onIsland(Location l) {
        if (!built || l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        double r = size * 1.35 + 4;
        if (dx * dx + dz * dz > r * r) return false;
        return l.getY() >= cy - size - 16 && l.getY() <= cy + 40;
    }

    private static boolean inBox(String w, int[] b, Location l, int grow) {
        if (b == null || w == null || l.getWorld() == null || !l.getWorld().getName().equals(w)) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= b[0] - grow && x <= b[3] + grow && y >= b[1] - grow && y <= b[4] + grow && z >= b[2] - grow && z <= b[5] + grow;
    }

    private boolean nearGate(Location l) {
        return inBox(gateWorld, gateBox, l, 4);
    }

    // ================================================================= commandes

    public void command(CommandSender sender, String[] a) {
        boolean adm = sender.hasPermission("smpevents.admin");
        String sub = a.length == 0 ? "tp" : a[0].toLowerCase(Locale.ROOT);
        if (!adm || sub.equals("tp") || sub.equals("aller")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(parse("<red>Commande réservée aux joueurs."));
                return;
            }
            toIsland(p);
            return;
        }
        switch (sub) {
            case "construire", "build", "generer" -> build(sender, a);
            case "annuler", "undo" -> undo(sender);
            case "portail", "porte", "gate" -> {
                if (a.length >= 2 && (a[1].equalsIgnoreCase("retirer") || a[1].equalsIgnoreCase("supprimer"))) removeGate(sender);
                else buildGate(sender);
            }
            case "arrivee", "arrivée", "spawn" -> {
                if (!(sender instanceof Player p) || !built()) {
                    sender.sendMessage(parse("<red>Il faut être en jeu et que l'île soit construite."));
                    return;
                }
                Location l = p.getLocation();
                arrival = new double[]{l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()};
                save();
                sender.sendMessage(parse("<green>Point d'arrivée de l'île réglé ici."));
            }
            case "info" -> info(sender);
            default -> help(sender);
        }
    }

    private void help(CommandSender s) {
        s.sendMessage(parse("<gold><b>Île des Classements"));
        s.sendMessage(parse("<yellow>/classement ile</yellow> <gray>— y aller"));
        s.sendMessage(parse("<yellow>/classement ile construire [taille] [ici|ciel|Y]</yellow> <gray>— générer l'île (centre = sous tes pieds)"));
        s.sendMessage(parse("<yellow>/classement ile portail</yellow> <gray>— poser l'arche du spawn devant toi · <yellow>portail retirer</yellow>"));
        s.sendMessage(parse("<yellow>/classement ile arrivee</yellow> <gray>— changer le point d'arrivée · <yellow>/classement ile annuler</yellow> — tout remettre"));
        s.sendMessage(parse("<yellow>/classement ile info"));
    }

    private void info(CommandSender s) {
        s.sendMessage(parse("<gold><b>Île des Classements"));
        s.sendMessage(parse("<gray>Île : " + (built() ? "<green>construite</green> <white>" + world + " " + cx + " " + cy + " " + cz
                + "</white> (taille " + size + ", " + holoIds.size() + " classements)" : "<red>pas construite")));
        s.sendMessage(parse("<gray>Arche du spawn : " + (gateBox != null ? "<green>posée</green> <white>" + gateWorld + " " + gateBox[0] + " "
                + gateBox[1] + " " + gateBox[2] : "<red>pas posée</red> <gray>(/classement ile portail)")));
    }

    // ================================================================= téléportations

    private boolean cooling(Player p) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < 2500) return true;
        cooldown.put(p.getUniqueId(), now);
        return false;
    }

    public void toIsland(Player p) {
        Location to = built() ? arrival() : null;
        if (to == null) {
            p.sendMessage(parse("<red>L'Île des Classements n'est pas encore construite."));
            return;
        }
        Location from = p.getLocation();
        if (!p.teleport(to)) {
            p.sendMessage(parse("<red>Impossible d'y aller maintenant."));
            if (inBox(gateWorld, gateBox, from, 0)) pushBack(p, from);
            return;
        }
        p.setFallDistance(0);
        p.playSound(to, Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.6f);
        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        to.getWorld().spawnParticle(Particle.END_ROD, to.clone().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.05);
        p.showTitle(net.kyori.adventure.title.Title.title(parse("<gradient:#ffe259:#ffa751><b>Île des Classements"),
                parse("<gray>Les meilleurs joueurs du serveur"),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1800),
                        java.time.Duration.ofMillis(500))));
    }

    public void toSpawn(Player p) {
        String cmd = plugin.getConfig().getString("island.spawn-command", "spawn %player%");
        Location before = p.getLocation();
        boolean ok = false;
        if (cmd != null && !cmd.isBlank()) ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        if (!ok || p.getLocation().distanceSquared(before) < 4) {
            if (!p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation())) {
                p.sendMessage(parse("<red>Impossible de repartir maintenant."));
                if (inBox(world, retBox, before, 0)) pushBack(p, before);
                return;
            }
        }
        p.setFallDistance(0);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
    }

    /** Repousse le joueur hors du portail (vers l'avant). */
    private void pushBack(Player p, Location from) {
        Location c = from.clone();
        c.setPitch(0);
        p.setVelocity(c.getDirection().multiply(-0.6).setY(0.25));
    }

    // ================================================================= événements

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location t = e.getTo();
        Location f = e.getFrom();
        if (f.getBlockX() == t.getBlockX() && f.getBlockY() == t.getBlockY() && f.getBlockZ() == t.getBlockZ()) return;
        Player p = e.getPlayer();
        if (inBox(gateWorld, gateBox, t, 0) || inBox(gateWorld, gateBox, t.clone().add(0, 1, 0), 0)) {
            if (!cooling(p)) Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) toIsland(p);
            });
            return;
        }
        if (!built) return;
        if (inBox(world, retBox, t, 0) || inBox(world, retBox, t.clone().add(0, 1, 0), 0)) {
            if (!cooling(p)) Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) toSpawn(p);
            });
            return;
        }
        // tombé de l'île : on remonte
        if (t.getWorld() != null && t.getWorld().getName().equals(world) && t.getY() < cy - size - 12) {
            double dx = t.getX() - (cx + 0.5), dz = t.getZ() - (cz + 0.5);
            double r = size * 1.35 + 12;
            if (dx * dx + dz * dz <= r * r && !cooling(p)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Location a = arrival();
                    if (a != null && p.isOnline() && p.teleport(a)) {
                        p.setFallDistance(0);
                        p.sendActionBar(parse("<aqua>Le vent te ramène sur l'île…"));
                    }
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (inBox(gateWorld, gateBox, e.getFrom(), 2) || inBox(world, retBox, e.getFrom(), 2)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (inBox(gateWorld, gateBox, e.getFrom(), 2) || inBox(world, retBox, e.getFrom(), 2)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onVoid(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !built) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && onIsland(p.getLocation())) e.setCancelled(true);
    }

    private boolean guarded(Location l) {
        return onIsland(l) || nearGate(l);
    }

    private boolean blocked(Player p, Location l) {
        return guarded(l) && !p.hasPermission("smpevents.admin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> guarded(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> guarded(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (guarded(e.getBlock().getLocation()) && (e.getPlayer() == null || !e.getPlayer().hasPermission("smpevents.admin"))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (guarded(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player p && p.hasPermission("smpevents.admin")) return;
        if (onIsland(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        var r = e.getSpawnReason();
        if ((r == CreatureSpawnEvent.SpawnReason.NATURAL || r == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS
                || r == CreatureSpawnEvent.SpawnReason.PATROL || r == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL) && onIsland(e.getLocation()))
            e.setCancelled(true);
        if (r == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL && nearGate(e.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !onIsland(victim.getLocation())) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player) e.setCancelled(true);
        else if (d instanceof Player) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldown.remove(e.getPlayer().getUniqueId());
        confirm.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================= textes flottants

    /** Toutes les 5 s : titre de l'île, panneau du portail de retour, panneau de l'arche du spawn. */
    public void tick() {
        if (built()) {
            World w = Bukkit.getWorld(world);
            title = label(title, new Location(w, cx + 0.5, cy + 8.2, cz + 0.5), 2.2f,
                    "<gradient:#ffe259:#ffa751><b>✦ Île des Classements ✦</b></gradient>\n<gray>Les meilleurs joueurs de Limitless");
            if (retBox != null) {
                retLabel = label(retLabel, new Location(w, (retBox[0] + retBox[3] + 1) / 2.0, retBox[4] + 3.3, (retBox[2] + retBox[5] + 1) / 2.0),
                        1.3f, "<aqua><b>⬅ Retour au spawn</b>\n<gray>Entre dans le portail");
            }
        } else {
            title = kill(title);
            retLabel = kill(retLabel);
        }
        World gw = gateWorld == null ? null : Bukkit.getWorld(gateWorld);
        if (gw != null && gateLabelPos != null) {
            gateLabel = label(gateLabel, new Location(gw, gateLabelPos[0], gateLabelPos[1], gateLabelPos[2]), 1.5f,
                    "<gradient:#ffe259:#ffa751><b>✦ Île des Classements ✦</b></gradient>\n<gray>Entre dans le portail\n<dark_gray>ou /classement ile");
        } else {
            gateLabel = kill(gateLabel);
        }
    }

    private TextDisplay label(TextDisplay cur, Location l, float scale, String text) {
        if (cur != null && cur.isValid()) {
            if (cur.getLocation().distanceSquared(l) > 0.01) cur.teleport(l);
            return cur;
        }
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return null;
        return l.getWorld().spawn(l, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(labelKey, PersistentDataType.BYTE, (byte) 1);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setLineWidth(400);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            Transformation tr = d.getTransformation();
            d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(scale, scale, scale), tr.getRightRotation()));
            d.text(parse(text));
        });
    }

    private static TextDisplay kill(TextDisplay d) {
        if (d != null) d.remove();
        return null;
    }

    public void shutdown() {
        title = kill(title);
        retLabel = kill(retLabel);
        gateLabel = kill(gateLabel);
        if (job != null) job.cancel();
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (d.getPersistentDataContainer().has(labelKey, PersistentDataType.BYTE)) d.remove();
            }
        }
    }

    // ================================================================= construction

    private BlockData data(String id) {
        try {
            return Bukkit.createBlockData("minecraft:" + id);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Bloc inconnu pour l'île : " + id);
            return Bukkit.createBlockData(org.bukkit.Material.AIR);
        }
    }

    private void build(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(parse("<red>Commande réservée aux joueurs."));
            return;
        }
        if (job != null) {
            p.sendMessage(parse("<red>Un chantier est déjà en cours."));
            return;
        }
        if (backupFile.exists()) {
            p.sendMessage(parse("<red>Une île est déjà construite. <gray>/classement ile annuler d'abord."));
            return;
        }
        int sz = 24;
        if (a.length >= 2) {
            try {
                sz = Integer.parseInt(a[1]);
            } catch (NumberFormatException ignored) {
                // taille par défaut
            }
        }
        sz = Math.max(16, Math.min(40, sz));
        String where = a.length >= 3 ? a[2].toLowerCase(Locale.ROOT) : "ici";
        World w = p.getWorld();
        int ty;
        if (where.equals("ciel") || where.equals("sky")) ty = Math.min(w.getMaxHeight() - 30, p.getLocation().getBlockY() + 40);
        else if (where.matches("-?\\d+")) ty = Integer.parseInt(where);
        else ty = p.getLocation().getBlockY() - 1;
        IslandDesign d = new IslandDesign(sz);
        if (ty + d.bottom - 2 < w.getMinHeight() || ty + 14 > w.getMaxHeight()) {
            p.sendMessage(parse("<red>Trop près du fond ou du plafond du monde. <gray>(Y entre " + (w.getMinHeight() - d.bottom + 2) + " et "
                    + (w.getMaxHeight() - 14) + ")"));
            return;
        }
        long[] c = confirm.get(p.getUniqueId());
        long sig = sz * 100_000L + ty;
        if (c == null || c[0] < System.currentTimeMillis() || c[1] != sig) {
            confirm.put(p.getUniqueId(), new long[]{System.currentTimeMillis() + 20_000, sig});
            p.sendMessage(parse("<gold><b>Construire l'Île des Classements ?</b>"));
            p.sendMessage(parse("<gray>Centre = au-dessus de tes pieds, à Y <white>" + ty + "</white>, rayon ≈ <white>" + (int) (sz * 1.2)
                    + "</white> blocs, " + (-d.bottom) + " blocs de roche dessous. L'arrivée et le portail de retour sont au <white>sud</white>."));
            p.sendMessage(parse("<gray>Une sauvegarde est faite : <aqua>/classement ile annuler</aqua> remet tout comme avant."));
            p.sendMessage(parse("<yellow>Retape la même commande dans les 20 secondes pour confirmer."));
            return;
        }
        confirm.remove(p.getUniqueId());
        int ox = p.getLocation().getBlockX(), oy = ty, oz = p.getLocation().getBlockZ();
        long[] keys = new long[d.blocks.size()];
        String[] ids = new String[keys.length];
        int n = 0;
        for (var en : d.blocks.entrySet()) {
            keys[n] = en.getKey();
            ids[n++] = en.getValue();
        }
        p.sendMessage(parse("<gold>Construction de l'île… <gray>(" + keys.length + " blocs)"));
        List<int[]> pos = new ArrayList<>();
        List<String> old = new ArrayList<>();
        Map<String, BlockData> cache = new HashMap<>();
        int perTick = 3000;
        final int[] idx = {0};
        job = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int done = 0;
            while (idx[0] < keys.length && done < perTick) {
                int i = idx[0]++;
                long k = keys[i];
                int x = ox + IslandDesign.kx(k), y = oy + IslandDesign.ky(k), z = oz + IslandDesign.kz(k);
                if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                Block b = w.getBlockAt(x, y, z);
                String id = ids[i];
                if (id.equals("air")) {
                    if (b.getType().isAir()) continue;
                } else if (d.soft.contains(k) && !(b.isEmpty() || b.isLiquid() || b.isReplaceable())) {
                    continue;
                }
                BlockData want = cache.computeIfAbsent(id, this::data);
                BlockData before = b.getBlockData();
                if (before.matches(want)) continue;
                pos.add(new int[]{x, y, z});
                old.add(before.getAsString());
                b.setBlockData(want, false);
                done++;
            }
            if (p.isOnline() && (idx[0] % (perTick * 4) < perTick || idx[0] >= keys.length))
                p.sendActionBar(parse("<gold>Île : <white>" + (idx[0] * 100L / keys.length) + "%"));
            if (idx[0] >= keys.length) {
                job.cancel();
                job = null;
                writeBackup(backupFile, w, pos, old);
                finish(p, d, w, ox, oy, oz);
            }
        }, 1L, 1L);
    }

    private void finish(Player p, IslandDesign d, World w, int ox, int oy, int oz) {
        world = w.getName();
        cx = ox;
        cy = oy;
        cz = oz;
        size = d.R;
        built = true;
        arrival = new double[]{ox + d.arrival[0], oy + d.arrival[1], oz + d.arrival[2], 180f, 0f};
        retBox = new int[]{ox + d.portal[0], oy + d.portal[1], oz + d.portal[2], ox + d.portal[3], oy + d.portal[4], oz + d.portal[5]};
        // les classements : un par socle
        Holograms holos = plugin.holograms();
        for (String id : holoIds) holos.remove(id);
        holoIds.clear();
        Board[] all = Board.values();
        for (int i = 0; i < d.boards.size() && i < all.length; i++) {
            double[] b = d.boards.get(i);
            Location l = new Location(w, ox + b[0], oy + b[1], oz + b[2]);
            holoIds.add(holos.create(l, List.of(all[i])).id);
        }
        save();
        tick();
        if (p.isOnline()) {
            p.sendMessage(parse("<green><b>Île des Classements terminée !</b> <gray>(" + holoIds.size() + " classements posés)"));
            p.sendMessage(parse("<gray>Pose maintenant l'arche au spawn : va au spawn, regarde où tu la veux et tape <aqua>/classement ile portail</aqua>."));
            toIsland(p);
        }
    }

    private void undo(CommandSender sender) {
        if (job != null) {
            sender.sendMessage(parse("<red>Un chantier est déjà en cours."));
            return;
        }
        if (!backupFile.exists()) {
            sender.sendMessage(parse("<red>Aucune île à retirer."));
            return;
        }
        for (String id : holoIds) plugin.holograms().remove(id);
        holoIds.clear();
        built = false;
        save();
        tick();
        restore(sender, backupFile, () -> sender.sendMessage(parse("<green>Île retirée, tout est comme avant.")));
    }

    // ================================================================= arche du spawn

    private void buildGate(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(parse("<red>Commande réservée aux joueurs."));
            return;
        }
        if (gateBackupFile.exists()) {
            p.sendMessage(parse("<red>Une arche est déjà posée. <gray>/classement ile portail retirer d'abord."));
            return;
        }
        BlockFace face = p.getFacing();
        if (face != BlockFace.NORTH && face != BlockFace.SOUTH && face != BlockFace.EAST && face != BlockFace.WEST) face = BlockFace.NORTH;
        int fx = face.getModX(), fz = face.getModZ();
        int ux = -fz, uz = fx; // côté (perpendiculaire)
        World w = p.getWorld();
        int ox = p.getLocation().getBlockX() + fx * 3, oy = p.getLocation().getBlockY() - 1, oz = p.getLocation().getBlockZ() + fz * 3;
        String axis = ux != 0 ? "x" : "z";
        List<int[]> pos = new ArrayList<>();
        List<String> old = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE,
                maxZ = Integer.MIN_VALUE;
        // d'abord le cadre, puis le portail (sinon il casse)
        List<Map.Entry<int[], String>> parts = new ArrayList<>(IslandDesign.gate().entrySet());
        parts.sort((x, y) -> Boolean.compare(x.getValue().equals("nether_portal"), y.getValue().equals("nether_portal")));
        for (var en : parts) {
            int[] q = en.getKey();
            int x = ox + q[0] * ux + q[2] * fx, y = oy + q[1], z = oz + q[0] * uz + q[2] * fz;
            String id = en.getValue();
            if (id.equals("nether_portal")) {
                id = "nether_portal[axis=" + axis + "]";
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            Block b = w.getBlockAt(x, y, z);
            pos.add(new int[]{x, y, z});
            old.add(b.getBlockData().getAsString());
            b.setBlockData(data(id), false);
        }
        writeBackup(gateBackupFile, w, pos, old);
        gateWorld = w.getName();
        gateBox = new int[]{minX, minY, minZ, maxX, maxY, maxZ};
        gateLabelPos = new double[]{ox + 0.5, oy + 7.4, oz + 0.5};
        save();
        tick();
        w.playSound(new Location(w, ox, oy + 2, oz), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, 1f);
        p.sendMessage(parse("<green>Arche posée devant toi : entrer dans le portail = direction l'Île des Classements."));
        if (!built()) p.sendMessage(parse("<yellow>L'île n'est pas encore construite : <white>/classement ile construire</white>."));
    }

    private void removeGate(CommandSender sender) {
        if (!gateBackupFile.exists()) {
            sender.sendMessage(parse("<red>Aucune arche posée."));
            return;
        }
        gateWorld = null;
        gateBox = null;
        gateLabelPos = null;
        save();
        tick();
        restore(sender, gateBackupFile, () -> sender.sendMessage(parse("<green>Arche retirée.")));
    }

    // ================================================================= sauvegarde du terrain

    private void writeBackup(File f, World w, List<int[]> pos, List<String> old) {
        Map<String, Integer> palette = new HashMap<>();
        List<String> pal = new ArrayList<>();
        for (String s : old) if (palette.putIfAbsent(s, pal.size()) == null) pal.add(s);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)))) {
            out.writeInt(0x534D4953); // "SMIS"
            out.writeUTF(w.getName());
            out.writeInt(pal.size());
            for (String s : pal) out.writeUTF(s);
            out.writeInt(pos.size());
            for (int i = 0; i < pos.size(); i++) {
                int[] q = pos.get(i);
                out.writeInt(q[0]);
                out.writeInt(q[1]);
                out.writeInt(q[2]);
                out.writeInt(palette.get(old.get(i)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Sauvegarde du terrain impossible : " + e.getMessage());
        }
    }

    private void restore(CommandSender sender, File f, Runnable done) {
        World w;
        int[] xs, ys, zs, ps;
        String[] pal;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            if (in.readInt() != 0x534D4953) throw new IOException("fichier invalide");
            w = Bukkit.getWorld(in.readUTF());
            if (w == null) throw new IOException("monde introuvable");
            pal = new String[in.readInt()];
            for (int i = 0; i < pal.length; i++) pal[i] = in.readUTF();
            int n = in.readInt();
            xs = new int[n];
            ys = new int[n];
            zs = new int[n];
            ps = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = in.readInt();
                ys[i] = in.readInt();
                zs[i] = in.readInt();
                ps[i] = in.readInt();
            }
        } catch (IOException e) {
            sender.sendMessage(parse("<red>Lecture de la sauvegarde impossible : " + e.getMessage()));
            return;
        }
        BlockData[] datas = new BlockData[pal.length];
        for (int i = 0; i < pal.length; i++) {
            try {
                datas[i] = Bukkit.createBlockData(pal[i]);
            } catch (IllegalArgumentException ex) {
                datas[i] = Bukkit.createBlockData(org.bukkit.Material.AIR);
            }
        }
        final int[] idx = {xs.length - 1};
        job = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = 0;
            while (idx[0] >= 0 && k < 4000) {
                int i = idx[0]--;
                w.getBlockAt(xs[i], ys[i], zs[i]).setBlockData(datas[ps[i]], false);
                k++;
            }
            if (idx[0] < 0) {
                job.cancel();
                job = null;
                if (!f.delete()) plugin.getLogger().warning(f.getName() + " non supprimé");
                done.run();
            }
        }, 1L, 1L);
    }
}
