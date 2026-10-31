package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static fr.smpboss.SmpBoss.parse;

/**
 * L'arène du boss : construction (/boss arene construire), zone protégée, ambiance (brume rouge), retour en arrière.
 */
public final class Arena {

    private final SmpBoss plugin;
    private final File file, backupFile, gateFile;
    /** tourbillons du resource pack (plus de portails du Nether) */
    private org.bukkit.entity.ItemDisplay spawnSwirl, exitSwirl;
    // zone
    private String worldName;
    private int cx, cy, cz;
    private int floorRadius, protectRadius;
    private boolean defined;
    // chantier en cours
    private BukkitTask job;
    private World jobWorld;
    private List<int[]> jobPos;
    private List<String> jobOld;
    private final Map<UUID, long[]> confirm = new HashMap<>();
    private final Map<String, BlockData> dataCache = new HashMap<>();
    private final Map<UUID, WorldBorder> tints = new HashMap<>();
    private final Set<UUID> exiting = new HashSet<>();
    // point d'arrivée choisi par l'admin (sinon : sur le sol, côté crâne)
    private Location customSpawn;
    // zone "portail" (au spawn par exemple) : y entrer = téléporté dans l'arène
    private String portalWorld;
    private int[] portal; // minX, minY, minZ, maxX, maxY, maxZ
    private Location portalPos1, portalPos2;
    private final Map<UUID, Long> portalCooldown = new HashMap<>();
    // zone "sortie" (dans l'arène) : y entrer = retour au spawn
    private String exitWorld;
    private int[] exitBox;
    private Location exitPos1, exitPos2;
    private org.bukkit.entity.TextDisplay portalHolo, exitHolo;
    /** zone cliquable sous l'hologramme du portail : ouvre le menu du butin */
    private org.bukkit.entity.Interaction portalClick;
    /** Protection d'arrivée : intouchable quelques secondes en entrant dans l'arène. */
    private final Map<UUID, Long> shield = new HashMap<>();

    public boolean isProtected(Player p) {
        Long until = shield.get(p.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            shield.remove(p.getUniqueId());
            SmpBoss.hud(p, parse(plugin.getConfig().getString("arena.spawn-protection-end", "<gray>Ta protection d'arrivée est terminée.")));
            return false;
        }
        return true;
    }

    /** Arrivée dans l'arène : X secondes sans prendre de dégâts (finit si on frappe le boss). */
    public void protect(Player p) {
        int secs = plugin.getConfig().getInt("arena.spawn-protection-seconds", 6);
        if (secs <= 0) return;
        shield.put(p.getUniqueId(), System.currentTimeMillis() + secs * 1000L);
        SmpBoss.hud(p, parse("<aqua>🛡 Protection d'arrivée : <white>" + secs + " s"));
    }

    public void unprotect(Player p) {
        if (shield.remove(p.getUniqueId()) != null) SmpBoss.hud(p, parse("<gray>Tu as attaqué : ta protection d'arrivée s'arrête."));
    }

    /** Petites particules autour des joueurs protégés (toutes les 10 ticks). */
    void shieldParticles() {
        for (UUID id : new java.util.ArrayList<>(shield.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !isProtected(p)) continue;
            Location l = p.getLocation().add(0, 1, 0);
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * 2 * k / 8 + System.currentTimeMillis() / 300.0;
                p.getWorld().spawnParticle(Particle.END_ROD, l.clone().add(Math.cos(a) * 0.7, Math.sin(a * 2) * 0.4, Math.sin(a) * 0.7), 1, 0, 0, 0, 0);
            }
        }
    }

    /** true = l'arène de la Reine Araignée (noir et bleu), sinon celle du Roi Citrouille. */
    final boolean spider;

    Arena(SmpBoss plugin) {
        this(plugin, false);
    }

    Arena(SmpBoss plugin, boolean spider) {
        this.plugin = plugin;
        this.spider = spider;
        this.file = new File(plugin.getDataFolder(), spider ? "arena-spider.yml" : "arena.yml");
        this.backupFile = new File(plugin.getDataFolder(), spider ? "arena-spider-backup.dat" : "arena-backup.dat");
        this.gateFile = new File(plugin.getDataFolder(), spider ? "arena-spider-gate.dat" : "arena-gate.dat");
        load();
    }

    /** "/boss arene" ou "/boss araignee arene" (textes d'aide). */
    private String cmd() {
        return spider ? "/arenearaignee" : "/arenecitrouille";
    }

    private Location myAltar() {
        return spider ? plugin.spiderAltar() : plugin.altar();
    }

    private String radiusKey() {
        return spider ? "spider.arena-radius" : "boss.arena-radius";
    }

    // ================================================================= zone

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (y.isSet("spawn.world") && Bukkit.getWorld(y.getString("spawn.world")) != null) {
            customSpawn = new Location(Bukkit.getWorld(y.getString("spawn.world")), y.getDouble("spawn.x"), y.getDouble("spawn.y"),
                    y.getDouble("spawn.z"), (float) y.getDouble("spawn.yaw"), (float) y.getDouble("spawn.pitch"));
        }
        if (y.isSet("portal.world")) {
            portalWorld = y.getString("portal.world");
            List<Integer> b = y.getIntegerList("portal.box");
            if (b.size() == 6) portal = new int[]{b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5)};
        }
        if (y.isSet("exit.world")) {
            exitWorld = y.getString("exit.world");
            List<Integer> b = y.getIntegerList("exit.box");
            if (b.size() == 6) exitBox = new int[]{b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5)};
        }
        if (!y.getBoolean("defined", false)) return;
        worldName = y.getString("world", "world");
        cx = y.getInt("x");
        cy = y.getInt("y");
        cz = y.getInt("z");
        floorRadius = y.getInt("floor-radius", 20);
        protectRadius = y.getInt("protect-radius", floorRadius + 24);
        defined = true;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("defined", defined);
        if (defined) {
            y.set("world", worldName);
            y.set("x", cx);
            y.set("y", cy);
            y.set("z", cz);
            y.set("floor-radius", floorRadius);
            y.set("protect-radius", protectRadius);
        }
        if (customSpawn != null && customSpawn.getWorld() != null) {
            y.set("spawn.world", customSpawn.getWorld().getName());
            y.set("spawn.x", customSpawn.getX());
            y.set("spawn.y", customSpawn.getY());
            y.set("spawn.z", customSpawn.getZ());
            y.set("spawn.yaw", customSpawn.getYaw());
            y.set("spawn.pitch", customSpawn.getPitch());
        }
        if (portal != null && portalWorld != null) {
            y.set("portal.world", portalWorld);
            y.set("portal.box", List.of(portal[0], portal[1], portal[2], portal[3], portal[4], portal[5]));
        }
        if (exitBox != null && exitWorld != null) {
            y.set("exit.world", exitWorld);
            y.set("exit.box", List.of(exitBox[0], exitBox[1], exitBox[2], exitBox[3], exitBox[4], exitBox[5]));
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Sauvegarde de l'arène impossible : " + e.getMessage());
        }
    }

    public boolean defined() {
        return defined && world() != null;
    }

    public World world() {
        return worldName == null ? null : Bukkit.getWorld(worldName);
    }

    public Location center() {
        World w = world();
        return w == null ? null : new Location(w, cx + 0.5, cy + 1, cz + 0.5);
    }

    public int floorRadius() {
        return floorRadius;
    }

    /** Dans la zone protégée (cylindre autour du centre). */
    public boolean contains(Location l) {
        if (!defined || l == null || l.getWorld() == null || !l.getWorld().getName().equals(worldName)) return false;
        double y = l.getY();
        if (y < cy - 30 || y > cy + 60) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        return dx * dx + dz * dz <= (double) protectRadius * protectRadius;
    }

    public boolean contains(Block b) {
        return b != null && contains(b.getLocation().add(0.5, 0.5, 0.5));
    }

    /** Sur le sol de combat (pas sur le pont, le bord...). */
    public boolean onFloor(Location l) {
        if (!contains(l)) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        return dx * dx + dz * dz <= (double) (floorRadius + 1) * (floorRadius + 1);
    }

    /** Sur la piste de combat elle-même (pas sur le muret, le pont du portail, les piliers...). */
    public boolean onFightFloor(Location l) {
        if (!defined || l == null || l.getWorld() == null || !l.getWorld().getName().equals(worldName)) return false;
        if (l.getY() < cy - 4 || l.getY() > cy + 30) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        double max = floorRadius + plugin.getConfig().getDouble("arena.protection.floor-margin", 0.5);
        return dx * dx + dz * dz <= max * max;
    }

    /** Point sûr sur le sol, du côté de la position donnée (sauvetage de la lave). */
    public Location safeSpot(Location from) {
        Location c = center();
        if (c == null) return null;
        double dx = from.getX() - c.getX(), dz = from.getZ() - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double dist = Math.max(0, floorRadius - 3);
        Location out = len < 0.5 ? c.clone() : c.clone().add(dx / len * dist, 0, dz / len * dist);
        out.setY(cy + 1);
        out.setDirection(c.toVector().subtract(out.toVector()).setY(0));
        if (!out.getBlock().isPassable() || !out.clone().add(0, 1, 0).getBlock().isPassable()) {
            out = c.clone();
            out.add(2, 0, 2);
        }
        return out;
    }

    /** Lave touchée dans l'arène : retour au spawn de l'arène, sans brûlure. */
    void lavaRescue(Player p) {
        Long last = portalCooldown.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < 400) return;
        portalCooldown.put(p.getUniqueId(), now);
        Location to = entrance();
        if (to == null) to = safeSpot(p.getLocation());
        if (to == null) return;
        allowTeleport(p);
        p.teleport(to);
        p.setFallDistance(0);
        p.setFireTicks(0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.setFireTicks(0), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.setFireTicks(0), 5L);
        p.getWorld().playSound(to, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
        SmpBoss.hud(p, parse(spider ? "<aqua>Les toiles te remontent du gouffre…" : "<gold>La lave te recrache dans l'arène…"));
    }

    /** Tombé dans le gouffre du nid (sous le niveau du sol, hors du sol de combat). */
    public boolean inAbyss(Location l) {
        if (!spider || !contains(l)) return false;
        if (l.getY() > cy - 1.5 || l.getY() < cy - 30) return false;
        double dx = l.getX() - (cx + 0.5), dz = l.getZ() - (cz + 0.5);
        double d2 = dx * dx + dz * dz;
        return d2 > (floorRadius + 1.0) * (floorRadius + 1.0) && d2 < (floorRadius + 8.5) * (floorRadius + 8.5);
    }

    /** Entrée : sur le sol de l'arène, côté crâne, regard vers l'autel. */
    public Location entrance() {
        if (customSpawn != null && customSpawn.getWorld() != null) return customSpawn.clone();
        World w = world();
        if (w == null) return null;
        Location l = new Location(w, cx + 0.5, cy + 1, cz - (floorRadius - 3) + 0.5);
        l.setYaw(0);
        l.setPitch(5);
        return l;
    }

    public static void allowTeleport(Player p) {
        p.getPersistentDataContainer().set(new NamespacedKey("smpcombat", "allow_tp_until"), PersistentDataType.LONG,
                System.currentTimeMillis() + 2000);
    }

    /** Sortie (portail au fond de la gueule du crâne) : le /spawn d'Essentials, sinon le spawn du monde. */
    public void exit(Player p) {
        if (!exiting.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> exiting.remove(p.getUniqueId()), 40L);
        String cmd = plugin.getConfig().getString("arena.exit-command", "spawn %player%");
        allowTeleport(p);
        boolean ok = false;
        if (cmd != null && !cmd.isBlank()) ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        if (!ok || contains(p.getLocation())) {
            allowTeleport(p);
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
    }

    /** Joueur : /arene. Le combat (SmpCombat) peut l'en empêcher. */
    public void join(Player p) {
        if (contains(p.getLocation())) {
            p.sendMessage(parse("<red>Tu es déjà dans l'arène."));
            return;
        }
        Location e = entrance();
        if (e == null) {
            p.sendMessage(parse("<red>L'arène n'est pas encore construite."));
            return;
        }
        if (p.teleport(e)) protect(p);
    }

    // ================================================================= ambiance

    /** Écran teinté de rouge dans l'arène (bordure du monde virtuelle, rien que pour le joueur) ; pas d'élytres. */
    void tint() {
        boolean on = !spider && plugin.getConfig().getBoolean("arena.red-tint", true);
        double strength = Math.max(0.05, Math.min(0.95, plugin.getConfig().getDouble("arena.red-tint-strength", 0.6)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean in = defined && contains(p.getLocation());
            if (in && p.isGliding() && plugin.getConfig().getBoolean("arena.protection.no-elytra", true)) p.setGliding(false);
            WorldBorder mine = tints.get(p.getUniqueId());
            if (in && on) {
                // une autre teinte (SmpCombat "en danger"...) a la priorité ; on revient après
                if (p.getWorldBorder() == null) {
                    WorldBorder b = Bukkit.createWorldBorder();
                    b.setCenter(p.getLocation());
                    b.setSize(10_000);
                    b.setWarningDistance((int) (5_000 / (1 - strength)));
                    p.setWorldBorder(b);
                    tints.put(p.getUniqueId(), b);
                }
            } else if (mine != null) {
                tints.remove(p.getUniqueId());
                if (p.getWorldBorder() == mine) p.setWorldBorder(null);
            }
        }
        tints.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Toutes les 10 ticks : brume rouge, spores, braises au-dessus de la lave. */
    void ambience() {
        portalParticles();
        exitParticles();
        holograms();
        shieldParticles();
        if (!defined || !plugin.getConfig().getBoolean("arena.ambience", true)) return;
        World w = world();
        if (w == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Particle.DustOptions fog = new Particle.DustOptions(spider ? Color.fromRGB(8, 14, 60) : Color.fromRGB(120, 8, 8), 3.2f);
        Particle.DustOptions ember = new Particle.DustOptions(spider ? Color.fromRGB(60, 170, 255) : Color.fromRGB(255, 70, 20), 1.1f);
        boolean someone = false;
        for (Player p : w.getPlayers()) {
            if (!contains(p.getLocation())) continue;
            someone = true;
            Location l = p.getLocation();
            for (int i = 0; i < 7; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = 4 + r.nextDouble() * 14;
                p.spawnParticle(Particle.DUST, l.clone().add(Math.cos(a) * d, r.nextDouble() * 4, Math.sin(a) * d), 2, 1.2, 0.6, 1.2, 0, fog);
            }
            p.spawnParticle(spider ? Particle.WARPED_SPORE : Particle.CRIMSON_SPORE, l.clone().add(0, 2, 0), 14, 9, 3, 9, 0);
            p.spawnParticle(Particle.DUST, l.clone().add(0, 1.5, 0), 4, 6, 2, 6, 0, ember);
        }
        if (!someone) return;
        // bulles de lave dans la rivière
        for (int i = 0; i < 4; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = floorRadius + 2 + r.nextDouble() * 2.5;
            Location l = new Location(w, cx + 0.5 + Math.cos(a) * d, cy + 1, cz + 0.5 + Math.sin(a) * d);
            if (spider) {
                w.spawnParticle(Particle.SOUL, l, 1, 0.3, 0.2, 0.3, 0.01);
                w.spawnParticle(Particle.DUST, l.add(0, 0.5, 0), 1, 0.2, 0.3, 0.2, 0, ember);
            } else {
                w.spawnParticle(Particle.LAVA, l, 1, 0.3, 0, 0.3, 0);
                w.spawnParticle(Particle.LARGE_SMOKE, l.add(0, 0.5, 0), 1, 0.2, 0.3, 0.2, 0.01);
            }
        }
    }

    // ================================================================= commandes

    void command(CommandSender sender, String[] a) {
        String sub = a.length < 2 ? "" : a[1].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "construire", "build" -> build(sender, a);
            case "annuler", "undo" -> undo(sender);
            case "tp" -> {
                Player p = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : sender instanceof Player s ? s : null;
                if (p == null) {
                    sender.sendMessage(parse("<red>" + cmd() + " tp [joueur]"));
                    return;
                }
                Location e = entrance();
                if (e == null) {
                    sender.sendMessage(parse("<red>Pas d'arène. <gray>" + cmd() + " construire"));
                    return;
                }
                allowTeleport(p);
                if (p.teleport(e)) protect(p);
            }
            case "definir", "define" -> {
                if (!(sender instanceof Player p)) return;
                int rad = a.length >= 3 ? parseInt(a[2], 20) : 20;
                Block b = p.getLocation().getBlock().getRelative(0, -1, 0);
                worldName = b.getWorld().getName();
                cx = b.getX();
                cy = b.getY();
                cz = b.getZ();
                floorRadius = Math.max(6, rad);
                protectRadius = floorRadius + (a.length >= 4 ? parseInt(a[3], 10) : 10);
                defined = true;
                save();
                sender.sendMessage(parse("<green>Zone d'arène définie ici : sol de rayon " + floorRadius + ", protection sur "
                        + protectRadius + " blocs. <gray>(pour une arène collée avec WorldEdit · /boss autel pour l'autel)"));
            }
            case "setspawn", "spawn" -> {
                if (!(sender instanceof Player p)) return;
                if (a.length >= 3 && (a[2].equalsIgnoreCase("reset") || a[2].equalsIgnoreCase("defaut"))) {
                    customSpawn = null;
                    save();
                    sender.sendMessage(parse("<green>Point d'arrivée de l'arène remis par défaut (sur le sol, côté crâne)."));
                    return;
                }
                customSpawn = p.getLocation().clone();
                save();
                sender.sendMessage(parse("<green>Point d'arrivée de l'arène placé ici <gray>(là où tu regardes compte aussi). "
                        + "<white>/arene</white><gray>, le portail du spawn et " + cmd() + " tp y mènent."));
            }
            case "portail", "portal" -> portalCommand(sender, a);
            case "sortie", "exit" -> exitCommand(sender, a);
            case "recuperer", "récupérer", "restaurer", "recover" -> recover(sender);
            case "supprimer", "remove" -> {
                defined = false;
                save();
                sender.sendMessage(parse("<yellow>Zone d'arène retirée (les blocs restent, la protection est coupée)."));
            }
            default -> {
                sender.sendMessage(parse(spider ? "<dark_aqua><b>Arène de la Reine Araignée" : "<dark_red><b>Arène du boss"));
                sender.sendMessage(parse("<aqua>" + cmd() + " construire [taille] [ici|ciel|Y]</aqua> <gray>- construit l'arène (taille 14-40, défaut 20 ; <white>ciel</white> = île flottante)"));
                sender.sendMessage(parse("<aqua>" + cmd() + " annuler</aqua> <gray>- remet le terrain comme avant la construction"));
                sender.sendMessage(parse("<aqua>" + cmd() + " tp [joueur]</aqua> <gray>- envoie dans l'arène (les joueurs : <white>/arene</white>)"));
                sender.sendMessage(parse("<aqua>" + cmd() + " definir <rayon> [marge]</aqua> <gray>- protège une arène collée avec WorldEdit"));
                sender.sendMessage(parse("<aqua>" + cmd() + " setspawn [reset]</aqua> <gray>- le point d'arrivée = là où tu es"));
                sender.sendMessage(parse("<aqua>" + cmd() + " portail poser</aqua> <gray>- construit l'arche d'entrée devant toi (au spawn) · <aqua>portail retirer"));
                sender.sendMessage(parse("<aqua>" + cmd() + " portail pos1|pos2|supprimer</aqua> <gray>- ou une zone à toi (au spawn) où entrer = tp dans l'arène"));
                sender.sendMessage(parse("<aqua>" + cmd() + " sortie pos1|pos2|supprimer</aqua> <gray>- une zone (dans l'arène) où entrer = retour au spawn"));
                sender.sendMessage(parse("<aqua>" + cmd() + " supprimer</aqua> <gray>- coupe la protection"));
                sender.sendMessage(parse("<aqua>" + cmd() + " recuperer</aqua> <gray>- retrouve la zone de l'arène construite (après un /definir par erreur)"));
                if (defined) {
                    sender.sendMessage(parse("<gray>Arène : " + worldName + " " + cx + " " + cy + " " + cz + " · sol " + floorRadius
                            + " · protection " + protectRadius));
                }
            }
        }
    }

    // ================================================================= récupération

    /**
     * Retrouve la zone de l'arène construite : la taille vient de la sauvegarde du terrain (l'étendue des blocs
     * changés), le centre vient de l'autel (placé au centre à la construction).
     */
    private void recover(CommandSender sender) {
        Location alt = myAltar();
        int size = -1;
        int bx = Integer.MIN_VALUE, bz = 0;
        String bw = null;
        if (backupFile.exists()) {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(backupFile)))) {
                if (in.readInt() != 0x534D4241) throw new IOException("fichier invalide");
                bw = in.readUTF();
                int pal = in.readInt();
                for (int i = 0; i < pal; i++) in.readUTF();
                int n = in.readInt();
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                for (int i = 0; i < n; i++) {
                    int x = in.readInt();
                    in.readInt();
                    int z = in.readInt();
                    in.readInt();
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
                if (n > 0) {
                    bx = (minX + maxX) / 2;
                    bz = (minZ + maxZ) / 2;
                    // bulle = sol + 24, coque jusqu'à +26 de chaque côté
                    size = Math.max(14, Math.min(40, Math.max(maxX - minX, maxZ - minZ) / 2 - 26));
                }
            } catch (IOException e) {
                sender.sendMessage(parse("<red>Sauvegarde illisible : " + e.getMessage()));
            }
        }
        if (alt == null || alt.getWorld() == null) {
            sender.sendMessage(parse("<red>Pas d'autel enregistré : impossible de retrouver le centre. "
                    + "<gray>Va sur le bloc d'obsidienne pleureuse au centre de l'arène et tape /boss autel en le regardant, puis réessaie."));
            return;
        }
        if (bx != Integer.MIN_VALUE && (bw == null || !bw.equals(alt.getWorld().getName())
                || Math.abs(alt.getBlockX() - bx) > 3 || Math.abs(alt.getBlockZ() - bz) > 3)) {
            sender.sendMessage(parse("<yellow>Attention : l'autel ne semble pas être au centre de l'arène construite "
                    + "(centre trouvé : " + bx + " " + bz + "). <gray>Si tu as déplacé l'autel, remets-le au centre avec /boss autel."));
        }
        if (size < 0) size = (int) Math.round(plugin.getConfig().getDouble(radiusKey(), 19) + 1);
        worldName = alt.getWorld().getName();
        cx = alt.getBlockX();
        cy = alt.getBlockY();
        cz = alt.getBlockZ();
        floorRadius = size;
        protectRadius = size + 28;
        defined = true;
        save();
        plugin.getConfig().set(radiusKey(), Math.max(8, size - 1));
        plugin.saveConfig();
        sender.sendMessage(parse("<green>Zone de l'arène retrouvée : centre " + cx + " " + cy + " " + cz + ", sol de rayon " + floorRadius
                + ", protection " + protectRadius + " blocs."));
    }

    // ================================================================= portail du spawn

    private void portalCommand(CommandSender sender, String[] a) {
        String what = a.length >= 3 ? a[2].toLowerCase(java.util.Locale.ROOT) : "";
        if (what.equals("poser") || what.equals("arche") || what.equals("construire")) {
            if (sender instanceof Player p) buildGate(p);
            return;
        }
        if (what.equals("retirer")) {
            removeGate(sender);
            return;
        }
        if (what.equals("info") || what.equals("voir")) {
            if (portal == null) sender.sendMessage(parse("<red>Pas de zone portail. <gray>" + cmd() + " portail pos1 puis pos2"));
            else {
                sender.sendMessage(parse("<gold>Zone portail</gold> <gray>" + portalWorld + " : <white>" + portal[0] + " " + portal[1] + " " + portal[2]
                        + "</white> → <white>" + portal[3] + " " + portal[4] + " " + portal[5] + "</white> · arrivée : "
                        + (entrance() == null ? "<red>pas d'arène" : "<green>ok")));
                if (sender instanceof Player p) showPortal(p);
            }
            return;
        }
        if (what.equals("supprimer") || what.equals("remove")) {
            portal = null;
            portalWorld = null;
            save();
            sender.sendMessage(parse("<yellow>Zone portail supprimée."));
            return;
        }
        if (!(sender instanceof Player p) || !(what.equals("pos1") || what.equals("pos2"))) {
            sender.sendMessage(parse("<gold>" + cmd() + " portail poser</gold> <gray>: construit l'arche d'entrée devant toi (le plus simple) · <gold>portail retirer"));
            sender.sendMessage(parse("<gold>" + cmd() + " portail pos1</gold> <gray>(un coin)</gray> <gold>puis pos2</gold> <gray>(le coin opposé) · "
                    + "<gold>" + cmd() + " portail supprimer"));
            return;
        }
        Location l = p.getLocation().getBlock().getLocation();
        if (what.equals("pos1")) portalPos1 = l;
        else portalPos2 = l;
        sender.sendMessage(parse("<gold>Portail " + what + " : <white>" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
        if (portalPos1 != null && portalPos2 != null && portalPos1.getWorld() == portalPos2.getWorld()) {
            portalWorld = portalPos1.getWorld().getName();
            portal = new int[]{Math.min(portalPos1.getBlockX(), portalPos2.getBlockX()), Math.min(portalPos1.getBlockY(), portalPos2.getBlockY()),
                    Math.min(portalPos1.getBlockZ(), portalPos2.getBlockZ()), Math.max(portalPos1.getBlockX(), portalPos2.getBlockX()),
                    Math.max(portalPos1.getBlockY(), portalPos2.getBlockY()) + 1, Math.max(portalPos1.getBlockZ(), portalPos2.getBlockZ())};
            save();
            sender.sendMessage(parse("<green>Zone portail créée <gray>(" + (portal[3] - portal[0] + 1) + "×" + (portal[4] - portal[1] + 1) + "×"
                    + (portal[5] - portal[2] + 1) + ") : entrer dedans = téléporté dans l'arène. <white>" + cmd() + " portail info</white> pour la revoir."));
            if (entrance() == null) sender.sendMessage(parse("<red>Attention : l'arène n'est pas encore construite / définie, le portail ne mènera nulle part."));
            showPortal(p);
        }
    }

    boolean inPortal(Location l) {
        if (portal == null || portalWorld == null || l.getWorld() == null || !l.getWorld().getName().equals(portalWorld)) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= portal[0] && x <= portal[3] && y >= portal[1] && y <= portal[4] && z >= portal[2] && z <= portal[5];
    }

    /** Contour de la zone portail (10 s), visible par l'admin. */
    void showPortal(Player p) {
        if (portal == null) return;
        World w = Bukkit.getWorld(portalWorld);
        if (w == null || p.getWorld() != w) return;
        Particle.DustOptions d = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 60, 60), 1.3f);
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if ((t[0] += 10) > 200 || !p.isOnline()) {
                task[0].cancel();
                return;
            }
            double x0 = portal[0], y0 = portal[1], z0 = portal[2], x1 = portal[3] + 1, y1 = portal[4] + 1, z1 = portal[5] + 1;
            for (double x = x0; x <= x1; x += 0.5) for (double[] yz : new double[][]{{y0, z0}, {y0, z1}, {y1, z0}, {y1, z1}}) p.spawnParticle(Particle.DUST, x, yz[0], yz[1], 1, 0, 0, 0, 0, d);
            for (double y = y0; y <= y1; y += 0.5) for (double[] xz : new double[][]{{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) p.spawnParticle(Particle.DUST, xz[0], y, xz[1], 1, 0, 0, 0, 0, d);
            for (double z = z0; z <= z1; z += 0.5) for (double[] xy : new double[][]{{x0, y0}, {x0, y1}, {x1, y0}, {x1, y1}}) p.spawnParticle(Particle.DUST, xy[0], xy[1], z, 1, 0, 0, 0, 0, d);
        }, 0L, 10L);
    }

    /** Toutes les 5 ticks : ceux qui sont dans la zone portail (même sans bouger) partent dans l'arène. */
    void portalTick() {
        exitTick();
        if (portal == null || portalWorld == null) return;
        World w = Bukkit.getWorld(portalWorld);
        if (w == null) return;
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (inPortal(p.getLocation()) || inPortal(p.getLocation().add(0, 1, 0))) checkPortal(p, p.getLocation().add(0, inPortal(p.getLocation()) ? 0 : 1, 0));
        }
    }

    /** Appelé quand un joueur change de bloc. */
    void checkPortal(Player p, Location to) {
        if (!inPortal(to)) return;
        long now = System.currentTimeMillis();
        Long last = portalCooldown.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        portalCooldown.put(p.getUniqueId(), now);
        Location e = entrance();
        if (e == null) {
            SmpBoss.hud(p, parse("<red>L'arène n'est pas encore construite."));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            // pas d'autorisation SmpCombat ici : un joueur "en danger" ne fuit pas par le portail
            if (p.teleport(e)) protect(p);
            p.setFireTicks(0);
            Bukkit.getScheduler().runTaskLater(plugin, () -> p.setFireTicks(0), 1L);
            p.getWorld().playSound(e, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
            SmpBoss.hud(p, parse(spider ? "<#5aa0ff><b>🕷 Le Nid de la Reine Araignée</b>" : "<dark_red><b>☠ L'arène du Roi Citrouille</b>"));
        });
    }

    // ================================================================= zone de sortie (arène -> spawn)

    private void exitCommand(CommandSender sender, String[] a) {
        String what = a.length >= 3 ? a[2].toLowerCase(java.util.Locale.ROOT) : "";
        if (what.equals("info") || what.equals("voir")) {
            if (exitBox == null) sender.sendMessage(parse("<red>Pas de zone de sortie. <gray>" + cmd() + " sortie pos1 puis pos2"));
            else {
                sender.sendMessage(parse("<green>Zone de sortie</green> <gray>" + exitWorld + " : <white>" + exitBox[0] + " " + exitBox[1] + " " + exitBox[2]
                        + "</white> → <white>" + exitBox[3] + " " + exitBox[4] + " " + exitBox[5]));
                if (sender instanceof Player p) outline(p, exitWorld, exitBox, Color.fromRGB(80, 255, 120));
            }
            return;
        }
        if (what.equals("supprimer") || what.equals("remove")) {
            exitBox = null;
            exitWorld = null;
            if (exitHolo != null) exitHolo.remove();
            exitHolo = null;
            save();
            sender.sendMessage(parse("<yellow>Zone de sortie supprimée."));
            return;
        }
        if (!(sender instanceof Player p) || !(what.equals("pos1") || what.equals("pos2"))) {
            sender.sendMessage(parse("<green>" + cmd() + " sortie pos1</green> <gray>(un coin)</gray> <green>puis pos2</green> <gray>(le coin opposé) · "
                    + "<green>" + cmd() + " sortie info|supprimer"));
            return;
        }
        Location l = p.getLocation().getBlock().getLocation();
        if (what.equals("pos1")) exitPos1 = l;
        else exitPos2 = l;
        sender.sendMessage(parse("<green>Sortie " + what + " : <white>" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
        if (exitPos1 != null && exitPos2 != null && exitPos1.getWorld() == exitPos2.getWorld()) {
            exitWorld = exitPos1.getWorld().getName();
            exitBox = new int[]{Math.min(exitPos1.getBlockX(), exitPos2.getBlockX()), Math.min(exitPos1.getBlockY(), exitPos2.getBlockY()),
                    Math.min(exitPos1.getBlockZ(), exitPos2.getBlockZ()), Math.max(exitPos1.getBlockX(), exitPos2.getBlockX()),
                    Math.max(exitPos1.getBlockY(), exitPos2.getBlockY()) + 1, Math.max(exitPos1.getBlockZ(), exitPos2.getBlockZ())};
            save();
            sender.sendMessage(parse("<green>Zone de sortie créée <gray>(" + (exitBox[3] - exitBox[0] + 1) + "×" + (exitBox[4] - exitBox[1] + 1) + "×"
                    + (exitBox[5] - exitBox[2] + 1) + ") : entrer dedans = retour au spawn."));
            outline(p, exitWorld, exitBox, Color.fromRGB(80, 255, 120));
        }
    }

    private static boolean inBox(String world, int[] box, Location l) {
        if (box == null || world == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
    }

    /** Sortie automatique : le cadre au fond du crâne / de la tête d'araignée (arène construite par le plugin). */
    int[] autoExit() {
        if (!defined || !backupFile.exists()) return null;
        int pz = spider ? cz - (floorRadius + 22) : cz - (floorRadius + 12);
        return new int[]{cx - 1, cy + 1, pz, cx + 1, cy + 4, pz};
    }

    private void exitTick() {
        int[] auto = autoExit();
        if ((exitBox == null || exitWorld == null) && auto == null) return;
        java.util.Set<World> worlds = new java.util.HashSet<>();
        if (exitWorld != null && Bukkit.getWorld(exitWorld) != null) worlds.add(Bukkit.getWorld(exitWorld));
        if (auto != null && world() != null) worlds.add(world());
        for (World w : worlds) for (Player p : w.getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            Location pl = p.getLocation(), ph = p.getLocation().add(0, 1, 0);
            boolean in = inBox(exitWorld, exitBox, pl) || inBox(exitWorld, exitBox, ph) || inBox(worldName, auto, pl) || inBox(worldName, auto, ph);
            if (!in) continue;
            long now = System.currentTimeMillis();
            Long last = portalCooldown.get(p.getUniqueId());
            if (last != null && now - last < 3000) continue;
            portalCooldown.put(p.getUniqueId(), now);
            p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.08);
            exit(p);
        }
    }

    // ================================================================= tourbillons (à la place des portails du Nether)

    private Color swirlColor() {
        return spider ? Color.fromRGB(70, 130, 255) : Color.fromRGB(255, 120, 25);
    }

    /** Toutes les 10 ticks : un tourbillon animé dans le portail du spawn et dans la sortie ; les vieux portails du Nether disparaissent. */
    private void swirls() {
        boolean on = plugin.getConfig().getBoolean("arena.portal-swirl", true);
        spawnSwirl = swirl(spawnSwirl, on ? portalWorld : null, portal);
        int[] auto = exitBox == null ? autoExit() : exitBox;
        exitSwirl = swirl(exitSwirl, on ? (exitBox != null ? exitWorld : auto != null ? worldName : null) : null, auto);
    }

    private org.bukkit.entity.ItemDisplay swirl(org.bukkit.entity.ItemDisplay cur, String world, int[] b) {
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w == null || b == null) {
            if (cur != null) cur.remove();
            return null;
        }
        double x = (b[0] + b[3] + 1) / 2.0, y = (b[1] + b[4] + 1) / 2.0, z = (b[2] + b[5] + 1) / 2.0;
        if (!w.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) return cur;
        // plus de portail du Nether : on vide l'intérieur
        for (int i = b[0]; i <= b[3]; i++) for (int j = b[1]; j <= b[4]; j++) for (int k = b[2]; k <= b[5]; k++) {
            Block bl = w.getBlockAt(i, j, k);
            if (bl.getType() == org.bukkit.Material.NETHER_PORTAL) bl.setType(org.bukkit.Material.AIR, false);
        }
        Location c = new Location(w, x, y, z);
        boolean near = false;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) < 64 * 64) near = true;
        if (!near) {
            if (cur != null) cur.remove();
            return null;
        }
        int dx = b[3] - b[0] + 1, dy = b[4] - b[1] + 1, dz = b[5] - b[2] + 1;
        // particules pour tout le monde (sans pack aussi)
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Particle.DustOptions dust = new Particle.DustOptions(swirlColor(), 1.2f);
        for (int i = 0; i < 6; i++) {
            Location l = new Location(w, b[0] + r.nextDouble() * dx, b[1] + r.nextDouble() * dy, b[2] + r.nextDouble() * dz);
            w.spawnParticle(i % 2 == 0 ? Particle.DUST : Particle.REVERSE_PORTAL, l, 1, 0, 0, 0, i % 2 == 0 ? 0 : 0.02, i % 2 == 0 ? dust : null);
        }
        if (cur != null && cur.isValid()) return cur;
        // zone plate (un bassin au sol) : le tourbillon est posé à plat ; sinon il est debout dans le cadre
        boolean flat = dy <= 2 && dx >= 2 && dz >= 2;
        boolean alongX = dx >= dz;
        float sw, sh;
        org.joml.Quaternionf rot;
        Location at = c;
        if (flat) {
            sw = dx;
            sh = dz;
            rot = new org.joml.Quaternionf().rotateX((float) (-Math.PI / 2));
            at = new Location(w, x, b[1] + 0.15, z);
        } else {
            sw = alongX ? dx : dz;
            sh = dy;
            rot = alongX ? new org.joml.Quaternionf() : new org.joml.Quaternionf().rotateY((float) (Math.PI / 2));
        }
        float fw = sw, fh = sh;
        return w.spawn(at, org.bukkit.entity.ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setItemStack(PackFx.tmodel("w_portal", swirlColor()));
            e.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE);
            e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            e.setViewRange(2f);
            e.setShadowRadius(0);
            e.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), rot, new org.joml.Vector3f(fw, fh, 1), new org.joml.Quaternionf()));
        });
    }

    /** L'arche d'entrée (au spawn), posée devant l'admin : le portail de l'arène est réglé tout seul. */
    private void buildGate(Player p) {
        if (gateFile.exists()) {
            p.sendMessage(parse("<red>Une arche est déjà posée. <gray>" + cmd() + " portail retirer d'abord."));
            return;
        }
        org.bukkit.block.BlockFace face = p.getFacing();
        if (face != org.bukkit.block.BlockFace.NORTH && face != org.bukkit.block.BlockFace.SOUTH && face != org.bukkit.block.BlockFace.EAST
                && face != org.bukkit.block.BlockFace.WEST) face = org.bukkit.block.BlockFace.NORTH;
        int fx = face.getModX(), fz = face.getModZ(), ux = -fz, uz = fx;
        World w = p.getWorld();
        int ox = p.getLocation().getBlockX() + fx * 3, oy = p.getLocation().getBlockY() - 1, oz = p.getLocation().getBlockZ() + fz * 3;
        Map<int[], String> plan = new java.util.LinkedHashMap<>();
        String pillar = spider ? "polished_deepslate" : "polished_blackstone_bricks", accent = spider ? "sea_lantern" : "jack_o_lantern",
                top = spider ? "chiseled_deepslate" : "chiseled_polished_blackstone", inner = spider ? "black_concrete" : "obsidian",
                lamp = spider ? "soul_lantern" : "lantern";
        for (int u = -2; u <= 2; u++) {
            for (int y = 0; y <= 5; y++) {
                boolean inside = Math.abs(u) <= 1 && y >= 1 && y <= 4;
                plan.put(new int[]{u, y, 0}, inside ? "air" : inner);
            }
        }
        for (int s = -1; s <= 1; s += 2) {
            for (int y = 0; y <= 6; y++) plan.put(new int[]{3 * s, y, 0}, y == 3 ? accent + (spider ? "" : "[facing=" + face.getOppositeFace().name().toLowerCase(java.util.Locale.ROOT) + "]") : y == 6 ? top : pillar);
            plan.put(new int[]{3 * s, 7, 0}, lamp);
            plan.put(new int[]{2 * s, 6, 0}, top);
            if (spider) {
                plan.put(new int[]{4 * s, 5, 0}, "cobweb");
                plan.put(new int[]{4 * s, 1, 0}, "cobweb");
            }
        }
        for (int u = -1; u <= 1; u++) plan.put(new int[]{u, 6, 0}, u == 0 ? accent + (spider ? "" : "[facing=" + face.getOppositeFace().name().toLowerCase(java.util.Locale.ROOT) + "]") : top);
        if (spider) plan.put(new int[]{0, 7, 0}, "cobweb");
        List<int[]> pos = new ArrayList<>();
        List<String> old = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var en : plan.entrySet()) {
            int[] q = en.getKey();
            int x = ox + q[0] * ux + q[2] * fx, y = oy + q[1], z = oz + q[0] * uz + q[2] * fz;
            Block b = w.getBlockAt(x, y, z);
            pos.add(new int[]{x, y, z});
            old.add(b.getBlockData().getAsString());
            b.setBlockData(data(en.getValue()), false);
            if (Math.abs(q[0]) <= 1 && q[1] >= 1 && q[1] <= 4) {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        }
        writeBackup(gateFile, w, pos, old);
        portalWorld = w.getName();
        portal = new int[]{minX, oy + 1, minZ, maxX, oy + 4, maxZ};
        save();
        if (spawnSwirl != null) spawnSwirl.remove();
        spawnSwirl = null;
        w.playSound(new Location(w, ox, oy + 2, oz), org.bukkit.Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, spider ? 0.7f : 1f);
        p.sendMessage(parse("<green>Arche posée devant toi : y entrer = téléporté dans " + (spider ? "le nid de la Reine" : "l'arène du Roi") + "."));
        if (entrance() == null) p.sendMessage(parse("<yellow>L'arène n'est pas encore construite : <white>" + cmd() + " construire</white>."));
    }

    private void removeGate(CommandSender sender) {
        if (!gateFile.exists()) {
            sender.sendMessage(parse("<red>Aucune arche posée par le plugin. <gray>(" + cmd() + " portail supprimer pour juste retirer la zone)"));
            return;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(gateFile)))) {
            if (in.readInt() != 0x534D4241) throw new IOException("fichier invalide");
            World w = Bukkit.getWorld(in.readUTF());
            if (w == null) throw new IOException("monde introuvable");
            String[] pal = new String[in.readInt()];
            for (int i = 0; i < pal.length; i++) pal[i] = in.readUTF();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt(), k = in.readInt();
                BlockData d;
                try {
                    d = Bukkit.createBlockData(pal[k]);
                } catch (IllegalArgumentException e) {
                    d = Bukkit.createBlockData(org.bukkit.Material.AIR);
                }
                w.getBlockAt(x, y, z).setBlockData(d, false);
            }
        } catch (IOException e) {
            sender.sendMessage(parse("<red>Lecture de la sauvegarde de l'arche impossible : " + e.getMessage()));
            return;
        }
        if (!gateFile.delete()) plugin.getLogger().warning(gateFile.getName() + " non supprimé");
        portal = null;
        portalWorld = null;
        save();
        if (spawnSwirl != null) spawnSwirl.remove();
        spawnSwirl = null;
        sender.sendMessage(parse("<green>Arche retirée, le terrain est comme avant."));
    }

    /** Contour d'une zone (10 s), visible par l'admin. */
    private void outline(Player p, String world, int[] box, Color color) {
        World w = Bukkit.getWorld(world);
        if (w == null || p.getWorld() != w) return;
        Particle.DustOptions d = new Particle.DustOptions(color, 1.3f);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if ((t[0] += 10) > 200 || !p.isOnline()) {
                task[0].cancel();
                return;
            }
            double x0 = box[0], y0 = box[1], z0 = box[2], x1 = box[3] + 1, y1 = box[4] + 1, z1 = box[5] + 1;
            for (double x = x0; x <= x1; x += 0.5) for (double[] yz : new double[][]{{y0, z0}, {y0, z1}, {y1, z0}, {y1, z1}}) p.spawnParticle(Particle.DUST, x, yz[0], yz[1], 1, 0, 0, 0, 0, d);
            for (double y = y0; y <= y1; y += 0.5) for (double[] xz : new double[][]{{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) p.spawnParticle(Particle.DUST, xz[0], y, xz[1], 1, 0, 0, 0, 0, d);
            for (double z = z0; z <= z1; z += 0.5) for (double[] xy : new double[][]{{x0, y0}, {x0, y1}, {x1, y0}, {x1, y1}}) p.spawnParticle(Particle.DUST, xy[0], xy[1], z, 1, 0, 0, 0, 0, d);
        }, 0L, 10L);
    }

    // ================================================================= hologrammes (portail du spawn, sortie de l'arène)

    /** Toutes les 10 ticks. */
    private void holograms() {
        boolean on = plugin.getConfig().getBoolean("arena.portal-hologram", true);
        double above = plugin.getConfig().getDouble("arena.portal-hologram-height", 0.6);
        portalHolo = holo(portalHolo, on ? portalWorld : null, portal, above,
                plugin.msg(spider ? "spider.portal-hologram" : "portal.hologram").replace("<status>", spider ? plugin.spiderStatus() : plugin.bossStatus()));
        int[] auto = exitBox == null ? autoExit() : null;
        exitHolo = holo(exitHolo, on ? (exitBox != null ? exitWorld : auto != null ? worldName : null) : null, exitBox != null ? exitBox : auto, above,
                plugin.msg("portal.exit-hologram"));
        swirls();
        if (portalHolo == null) {
            if (portalClick != null) portalClick.remove();
            portalClick = null;
        } else {
            Location at = portalHolo.getLocation().subtract(0, 0.2, 0);
            if (portalClick == null || !portalClick.isValid() || portalClick.getWorld() != at.getWorld() || portalClick.getLocation().distanceSquared(at) > 0.25) {
                if (portalClick != null) portalClick.remove();
                portalClick = DropsMenu.spawnClick(plugin, at, 2.6f, 2.8f, spider);
            }
        }
    }

    private org.bukkit.entity.TextDisplay holo(org.bukkit.entity.TextDisplay d, String world, int[] box, double above, String text) {
        World w = world == null ? null : Bukkit.getWorld(world);
        int bx = box == null ? 0 : (box[0] + box[3]) / 2, bz = box == null ? 0 : (box[2] + box[5]) / 2;
        if (w == null || box == null || !w.isChunkLoaded(bx >> 4, bz >> 4)) {
            if (d != null) d.remove();
            return null;
        }
        Location at = new Location(w, (box[0] + box[3] + 1) / 2.0, box[4] + 1 + above, (box[2] + box[5] + 1) / 2.0);
        if (d == null || !d.isValid() || d.getWorld() != w || d.getLocation().distanceSquared(at) > 0.25) {
            if (d != null) d.remove();
            d = w.spawn(at, org.bukkit.entity.TextDisplay.class, x -> {
                x.setPersistent(false);
                x.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                x.setShadowed(true);
                x.setBackgroundColor(Color.fromARGB(110, 10, 0, 0));
                x.setLineWidth(260);
                x.setViewRange(0.6f);
            });
        }
        d.text(parse(text));
        return d;
    }

    /** Toutes les 10 ticks : la zone de sortie scintille de vert. */
    private void exitParticles() {
        if (exitBox == null || exitWorld == null) return;
        World w = Bukkit.getWorld(exitWorld);
        if (w == null) return;
        Location c = new Location(w, (exitBox[0] + exitBox[3] + 1) / 2.0, exitBox[1], (exitBox[2] + exitBox[5] + 1) / 2.0);
        boolean near = false;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) < 48 * 48) near = true;
        if (!near) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Particle.DustOptions green = new Particle.DustOptions(Color.fromRGB(80, 255, 120), 1.2f);
        int n = Math.min(30, 6 + (exitBox[3] - exitBox[0] + 1) * (exitBox[5] - exitBox[2] + 1) / 2);
        for (int i = 0; i < n; i++) {
            Location l = new Location(w, exitBox[0] + r.nextDouble() * (exitBox[3] - exitBox[0] + 1), exitBox[1] + r.nextDouble() * 2.2,
                    exitBox[2] + r.nextDouble() * (exitBox[5] - exitBox[2] + 1));
            w.spawnParticle(i % 4 == 0 ? Particle.END_ROD : Particle.DUST, l, 1, 0, 0, 0, 0, i % 4 == 0 ? null : green);
        }
    }

    /** Toutes les 10 ticks : la zone portail scintille de rouge. */
    void portalParticles() {
        if (portal == null || portalWorld == null) return;
        World w = Bukkit.getWorld(portalWorld);
        if (w == null) return;
        Location c = new Location(w, (portal[0] + portal[3] + 1) / 2.0, portal[1], (portal[2] + portal[5] + 1) / 2.0);
        boolean near = false;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) < 48 * 48) near = true;
        if (!near) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Particle.DustOptions red = new Particle.DustOptions(spider ? Color.fromRGB(40, 110, 255) : Color.fromRGB(200, 20, 20), 1.3f);
        int n = Math.min(40, 6 + (portal[3] - portal[0] + 1) * (portal[5] - portal[2] + 1) / 2);
        for (int i = 0; i < n; i++) {
            Location l = new Location(w, portal[0] + r.nextDouble() * (portal[3] - portal[0] + 1), portal[1] + r.nextDouble() * 2.2,
                    portal[2] + r.nextDouble() * (portal[5] - portal[2] + 1));
            w.spawnParticle(i % 3 == 0 ? Particle.PORTAL : Particle.DUST, l, 1, 0, 0, 0, 0, i % 3 == 0 ? null : red);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ================================================================= construction

    private BlockData data(String id0) {
        return dataCache.computeIfAbsent(id0, k -> {
            for (String opt : k.split("\\|")) {
                try {
                    return Bukkit.createBlockData("minecraft:" + opt);
                } catch (IllegalArgumentException ignored) {
                    // essaie le nom suivant (ex. "iron_chain|chain")
                }
            }
            plugin.getLogger().warning("Bloc inconnu pour l'arène : " + k);
            return Bukkit.createBlockData(org.bukkit.Material.AIR);
        });
    }

    private void build(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) return;
        if (job != null) {
            sender.sendMessage(parse("<red>Un chantier est déjà en cours."));
            return;
        }
        int def = spider ? 32 : 20;   // la Reine est immense : son nid est plus grand
        int size = a.length >= 3 ? parseInt(a[2], def) : def;
        size = Math.max(14, Math.min(40, size));
        // hauteur : "ici" (sous tes pieds, défaut), "ciel" (île flottante tout en haut) ou un Y précis
        String where = a.length >= 4 ? a[3].toLowerCase(java.util.Locale.ROOT) : "ici";
        if (a.length >= 3 && !a[2].matches("\\d+")) where = a[2].toLowerCase(java.util.Locale.ROOT); // "construire ciel"
        World pw = p.getWorld();
        int skyY = pw.getMaxHeight() - size - 32;
        int targetY;
        if (where.equals("ciel") || where.equals("sky")) targetY = Math.min(skyY, Math.max(pw.getMinHeight() + 30, 230));
        else if (where.matches("-?\\d+")) targetY = Integer.parseInt(where);
        else targetY = p.getLocation().getBlockY() - 1;
        long[] c = confirm.get(p.getUniqueId());
        String cmd = cmd() + " construire " + size + (a.length >= 4 ? " " + a[3] : "");
        if (c == null || c[0] < System.currentTimeMillis() || c[1] != size * 100_000L + targetY) {
            confirm.put(p.getUniqueId(), new long[]{System.currentTimeMillis() + 20_000, size * 100_000L + targetY});
            int total = size + 27;
            boolean sky = targetY > p.getLocation().getBlockY() + 5;
            sender.sendMessage(parse("<gold><b>Construire " + (spider ? "le nid de la Reine Araignée " : "l'arène infernale ") + (sky ? "dans le ciel (Y " + targetY + ")" : "ici") + " ?</b>"));
            sender.sendMessage(parse("<gray>Centre = " + (sky ? "au-dessus de toi, à Y <white>" + targetY + "</white> (île de roche flottante)"
                    : "le bloc sous tes pieds") + " : ce sera l'autel. C'est une bulle fermée de rayon ≈ <white>" + total
                    + "</white> blocs et " + (size + 25) + " de haut, " + (spider ? "la tête d'araignée (sortie)" : "le crâne-portail") + " est au <white>nord</white>. Tout ce qui est dedans est remplacé."));
            sender.sendMessage(parse("<gray>Une sauvegarde est faite : <aqua>" + cmd() + " annuler</aqua> remet tout comme avant."));
            sender.sendMessage(parse("<yellow>Retape <white>" + cmd + "</white> dans les 20 secondes pour confirmer."));
            return;
        }
        confirm.remove(p.getUniqueId());
        if (backupFile.exists()) {
            sender.sendMessage(parse("<red>Une arène construite existe déjà (sauvegarde présente). <gray>" + cmd() + " annuler d'abord, "
                    + "ou supprime plugins/SmpBoss/arena-backup.dat pour garder l'ancienne telle quelle."));
            return;
        }
        Location at = p.getLocation().clone();
        at.setY(targetY);
        Block base = at.getBlock();
        World w = base.getWorld();
        int below = spider ? 36 : 25;
        if (base.getY() - below < w.getMinHeight() || base.getY() + size + 28 > w.getMaxHeight()) {
            sender.sendMessage(parse("<red>Trop près du fond ou du plafond du monde. <gray>(Y entre " + (w.getMinHeight() + below) + " et "
                    + (w.getMaxHeight() - size - 28) + ")"));
            return;
        }
        ArenaPlan d = spider ? new SpiderDesign(size) : new ArenaDesign(size);
        int ox = base.getX(), oy = base.getY(), oz = base.getZ();
        long[] keys = new long[d.blocks().size()];
        String[] ids = new String[keys.length];
        int n = 0;
        for (var e : d.blocks().entrySet()) {
            keys[n] = e.getKey();
            ids[n++] = e.getValue();
        }
        worldName = w.getName();
        cx = ox;
        cy = oy;
        cz = oz;
        floorRadius = size;
        protectRadius = d.protect();
        defined = true;
        save();
        sender.sendMessage(parse("<gold>Construction de l'arène… <gray>(" + keys.length + " blocs)"));

        List<int[]> backupPos = new ArrayList<>();
        List<String> backupData = new ArrayList<>();
        int perTick = Math.max(500, plugin.getConfig().getInt("arena.blocks-per-tick", 4000));
        jobWorld = w;
        jobPos = backupPos;
        jobOld = backupData;
        final int[] idx = {0};
        final int total = keys.length;
        job = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int done = 0;
            while (idx[0] < total && done < perTick) {
                int i = idx[0]++;
                long k = keys[i];
                int x = ox + ArenaDesign.kx(k), y = oy + ArenaDesign.ky(k), z = oz + ArenaDesign.kz(k);
                if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                Block b = w.getBlockAt(x, y, z);
                String id = ids[i];
                if (id.equals("air")) {
                    if (b.getType().isAir()) continue;
                } else if (d.soft().contains(k) && !(b.isEmpty() || b.isLiquid() || b.isReplaceable())) {
                    continue;
                }
                BlockData want = data(id);
                BlockData old = b.getBlockData();
                if (old.matches(want)) continue;
                backupPos.add(new int[]{x, y, z});
                backupData.add(old.getAsString());
                b.setBlockData(want, false);
                done++;
            }
            if (idx[0] % (perTick * 5) < perTick || idx[0] >= total) {
                int pct = (int) (idx[0] * 100L / total);
                if (p.isOnline()) SmpBoss.hud(p, parse("<gold>Arène : <white>" + pct + "%"));
            }
            if (idx[0] >= total) {
                job.cancel();
                job = null;
                jobPos = null;
                finishBuild(p, d, ox, oy, oz, w, backupPos, backupData);
            }
        }, 1L, 1L);
    }

    private void finishBuild(Player p, ArenaPlan d, int ox, int oy, int oz, World w, List<int[]> pos, List<String> old) {
        // bannières : fond rouge, crâne noir, bordure et dégradé sombres
        for (int[] b : d.banners()) {
            Block blk = w.getBlockAt(ox + b[0], oy + b[1], oz + b[2]);
            if (blk.getState() instanceof Banner banner) {
                banner.addPattern(new Pattern(DyeColor.BLACK, PatternType.GRADIENT));
                banner.addPattern(new Pattern(DyeColor.BLACK, PatternType.SKULL));
                banner.addPattern(new Pattern(DyeColor.BLACK, PatternType.BORDER));
                banner.update(true, false);
            }
        }
        writeBackup(w, pos, old);
        // lien avec le boss : l'autel au centre, le boss reste sur le sol
        if (spider) plugin.setSpiderAltar(new Location(w, ox, oy, oz));
        else plugin.setAltar(new Location(w, ox, oy, oz));
        plugin.getConfig().set(radiusKey(), Math.max(8, d.radius() - 1));
        plugin.saveConfig();
        if (p.isOnline()) {
            Location e = entrance();
            if (e != null) {
                allowTeleport(p);
                p.teleport(e);
            }
            p.sendMessage(parse("<green><b>Arène terminée !</b> <gray>(" + pos.size() + " blocs changés)"));
            p.sendMessage(parse("<gray>L'autel est le bloc d'obsidienne pleureuse au centre : clic droit dessus avec "
                    + (spider ? "un <aqua>Cocon sacrificiel</aqua> pour réveiller la Reine Araignée" : "une <gold>Citrouille sacrificielle</gold> pour réveiller le Roi Citrouille")
                    + ". Rayon du boss réglé sur <white>"
                    + Math.max(8, d.radius() - 1) + "</white>."));
            p.sendMessage(parse("<gray>Zone protégée : pas de casse/pose, pas de PvP, aucun monstre à part le boss. <aqua>/arene</aqua> pour y aller, le portail au fond " + (spider ? "de la tête d'araignée" : "du crâne") + " pour sortir."));
        }
    }

    private void writeBackup(World w, List<int[]> pos, List<String> old) {
        writeBackup(backupFile, w, pos, old);
    }

    private void writeBackup(File backupFile, World w, List<int[]> pos, List<String> old) {
        Map<String, Integer> palette = new HashMap<>();
        List<String> pal = new ArrayList<>();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(backupFile)))) {
            out.writeInt(0x534D4241); // "SMBA"
            out.writeUTF(w.getName());
            for (String s : old) {
                if (!palette.containsKey(s)) {
                    palette.put(s, pal.size());
                    pal.add(s);
                }
            }
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

    private void undo(CommandSender sender) {
        if (job != null) {
            sender.sendMessage(parse("<red>Un chantier est déjà en cours."));
            return;
        }
        if (!backupFile.exists()) {
            sender.sendMessage(parse("<red>Aucune sauvegarde : rien à annuler."));
            return;
        }
        if (plugin.king() != null && plugin.king().active()) {
            sender.sendMessage(parse("<red>Le boss est en plein combat. <gray>/boss stop d'abord."));
            return;
        }
        World w;
        int[] xs, ys, zs, ps;
        String[] pal;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(backupFile)))) {
            if (in.readInt() != 0x534D4241) throw new IOException("fichier invalide");
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
        sender.sendMessage(parse("<gold>Remise en état du terrain… <gray>(" + xs.length + " blocs)"));
        // on coupe la protection pendant la remise en état
        defined = false;
        save();
        int perTick = Math.max(500, plugin.getConfig().getInt("arena.blocks-per-tick", 4000));
        final int[] idx = {xs.length - 1};
        job = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int done = 0;
            while (idx[0] >= 0 && done < perTick) {
                int i = idx[0]--;
                w.getBlockAt(xs[i], ys[i], zs[i]).setBlockData(datas[ps[i]], false);
                done++;
            }
            if (idx[0] < 0) {
                job.cancel();
                job = null;
                if (!backupFile.delete()) plugin.getLogger().warning("arena-backup.dat non supprimé");
                sender.sendMessage(parse("<green>Terrain remis comme avant. <gray>(l'autel n'existe plus : /boss autel pour en refaire un)"));
            }
        }, 1L, 1L);
    }

    void shutdown() {
        if (spawnSwirl != null) spawnSwirl.remove();
        if (exitSwirl != null) exitSwirl.remove();
        if (portalHolo != null) portalHolo.remove();
        if (exitHolo != null) exitHolo.remove();
        if (portalClick != null) portalClick.remove();
        for (var en : tints.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && p.getWorldBorder() == en.getValue()) p.setWorldBorder(null);
        }
        tints.clear();
        if (job != null) job.cancel();
        // arrêt du serveur en plein chantier : on garde quand même de quoi annuler
        if (jobPos != null && !jobPos.isEmpty()) writeBackup(jobWorld, jobPos, jobOld);
        jobPos = null;
    }
}
