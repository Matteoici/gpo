package fr.smpprison;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prison pour les tricheurs : le prisonnier n'a accès à rien d'autre que sa cellule et la mine.
 * Chaque bloc miné retire du temps à sa peine ; à zéro il est libéré et récupère son inventaire.
 */
public final class SmpPrison extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final class Prisoner {
        UUID id;
        String name;
        double minutes, total;
        String reason, by;
        long since;
        boolean pending; // pas encore appliqué (était hors ligne)
        List<String> inventory = new ArrayList<>();
        int level;
        float exp;
        String gamemode;
    }

    private final Map<UUID, Prisoner> prisoners = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** téléportations faites par le plugin (autorisées) */
    private final Set<UUID> allowTp = new HashSet<>();
    /** blocs minés en attente de réapparition */
    private final Map<Block, BlockData> regen = new HashMap<>();
    private File dataFile;
    private Location cell, mine, exit, pos1, pos2;

    // ================================================================= démarrage

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        dataFile = new File(getDataFolder(), "prison.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("prison", "travailler", "cellule", "peine")) {
            var cmd = getCommand(c);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::safeTick, 20L, 10L);
        for (Player p : Bukkit.getOnlinePlayers()) if (prisoners.containsKey(p.getUniqueId())) enter(p);
    }

    @Override
    public void onDisable() {
        // les blocs minés réapparaissent tout de suite
        for (var e : regen.entrySet()) e.getKey().setBlockData(e.getValue(), false);
        regen.clear();
        for (Player p : Bukkit.getOnlinePlayers()) hideBar(p);
        save();
    }

    private static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private String msg(String key) {
        String s = getConfig().getString("messages." + key);
        return s == null ? "<red>[" + key + "]" : s;
    }

    private void send(CommandSender to, String key, String... repl) {
        String s = msg(key);
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace("<" + repl[i] + ">", repl[i + 1]);
        to.sendMessage(mm(msg("prefix") + s));
    }

    private String fill(String key, String... repl) {
        String s = msg(key);
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace("<" + repl[i] + ">", repl[i + 1]);
        return s;
    }

    // ================================================================= sauvegarde

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        cell = y.getLocation("cell");
        mine = y.getLocation("mine");
        exit = y.getLocation("exit");
        pos1 = y.getLocation("pos1");
        pos2 = y.getLocation("pos2");
        ConfigurationSection s = y.getConfigurationSection("prisoners");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            if (c == null) continue;
            Prisoner p = new Prisoner();
            p.id = UUID.fromString(k);
            p.name = c.getString("name", "?");
            p.minutes = c.getDouble("minutes");
            p.total = c.getDouble("total", p.minutes);
            p.reason = c.getString("reason", "");
            p.by = c.getString("by", "");
            p.since = c.getLong("since");
            p.pending = c.getBoolean("pending");
            p.inventory = c.getStringList("inventory");
            p.level = c.getInt("level");
            p.exp = (float) c.getDouble("exp");
            p.gamemode = c.getString("gamemode", "SURVIVAL");
            prisoners.put(p.id, p);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("cell", cell);
        y.set("mine", mine);
        y.set("exit", exit);
        y.set("pos1", pos1);
        y.set("pos2", pos2);
        for (Prisoner p : prisoners.values()) {
            String k = "prisoners." + p.id;
            y.set(k + ".name", p.name);
            y.set(k + ".minutes", p.minutes);
            y.set(k + ".total", p.total);
            y.set(k + ".reason", p.reason);
            y.set(k + ".by", p.by);
            y.set(k + ".since", p.since);
            y.set(k + ".pending", p.pending);
            y.set(k + ".inventory", p.inventory);
            y.set(k + ".level", p.level);
            y.set(k + ".exp", (double) p.exp);
            y.set(k + ".gamemode", p.gamemode);
        }
        try {
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("prison.yml : " + e.getMessage());
        }
    }

    // ================================================================= durées

    /** 30m, 5h, 2j, 1s, 3mo, 500a (ou un nombre seul = minutes). */
    static double parseMinutes(String s) {
        s = s.toLowerCase(Locale.ROOT).trim();
        String num = s.replaceAll("[^0-9.]", ""), unit = s.replaceAll("[0-9.]", "");
        if (num.isEmpty()) return -1;
        double v;
        try {
            v = Double.parseDouble(num);
        } catch (NumberFormatException e) {
            return -1;
        }
        return switch (unit) {
            case "", "m", "min", "minute", "minutes" -> v;
            case "h", "heure", "heures" -> v * 60;
            case "j", "d", "jour", "jours" -> v * 1440;
            case "s", "sem", "semaine", "semaines", "w" -> v * 10080;
            case "mo", "mois" -> v * 43200;
            case "a", "an", "ans", "y" -> v * 525600;
            default -> -1;
        };
    }

    static String fmt(double minutes) {
        long m = (long) Math.ceil(Math.max(0, minutes));
        long y = m / 525600;
        m %= 525600;
        long d = m / 1440;
        m %= 1440;
        long h = m / 60;
        m %= 60;
        StringBuilder b = new StringBuilder();
        if (y > 0) b.append(String.format(Locale.FRANCE, "%,d", y)).append(y > 1 ? " ans " : " an ");
        if (d > 0) b.append(d).append(" j ");
        if (h > 0 && y == 0) b.append(h).append(" h ");
        if (y == 0 && d == 0) b.append(m).append(" min");
        return b.toString().trim().isEmpty() ? "0 min" : b.toString().trim();
    }

    private double perBlock() {
        return Math.max(0.01, getConfig().getDouble("minutes-per-block", 1));
    }

    // ================================================================= entrer / sortir

    private boolean ready() {
        return cell != null && mine != null && cell.getWorld() != null && mine.getWorld() != null;
    }

    private void jail(CommandSender staff, OfflinePlayer target, double minutes, String reason) {
        Prisoner p = prisoners.get(target.getUniqueId());
        if (p != null) {
            // déjà en prison : on rallonge
            p.minutes += minutes;
            p.total += minutes;
            save();
            send(staff, "jailed-staff", "player", String.valueOf(target.getName()), "time", fmt(p.minutes));
            return;
        }
        p = new Prisoner();
        p.id = target.getUniqueId();
        p.name = target.getName() == null ? "?" : target.getName();
        p.minutes = minutes;
        p.total = minutes;
        p.reason = reason;
        p.by = staff.getName();
        p.since = System.currentTimeMillis();
        p.pending = true;
        prisoners.put(p.id, p);
        Player online = target.getPlayer();
        if (online != null) {
            enter(online);
            send(staff, "jailed-staff", "player", p.name, "time", fmt(minutes));
        } else {
            send(staff, "pending", "player", p.name);
        }
        Bukkit.broadcast(mm(msg("prefix") + fill("jailed-broadcast", "player", MM.escapeTags(p.name), "reason", MM.escapeTags(reason),
                "time", fmt(minutes))));
        save();
    }

    /** Applique la prison à un joueur connecté (première fois : on met son inventaire de côté). */
    private void enter(Player pl) {
        Prisoner p = prisoners.get(pl.getUniqueId());
        if (p == null) return;
        if (p.pending) {
            p.pending = false;
            p.inventory = new ArrayList<>();
            for (ItemStack it : pl.getInventory().getContents()) {
                p.inventory.add(it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
            }
            p.level = pl.getLevel();
            p.exp = pl.getExp();
            p.gamemode = pl.getGameMode().name();
            pl.getInventory().clear();
            pl.setLevel(0);
            pl.setExp(0);
            save();
            pl.sendMessage(mm(msg("prefix") + fill("jailed", "time", fmt(p.minutes), "reason", MM.escapeTags(p.reason))));
        }
        pl.setGameMode(GameMode.SURVIVAL);
        pl.setAllowFlight(false);
        pl.setFlying(false);
        if (pl.isInsideVehicle()) pl.leaveVehicle();
        givePickaxe(pl);
        pl.displayName(mm(getConfig().getString("prefix", "")).append(Component.text(pl.getName())));
        pl.playerListName(mm(getConfig().getString("prefix", "")).append(Component.text(pl.getName())));
        if (ready()) teleport(pl, cell);
        showBar(pl, p);
    }

    private final org.bukkit.NamespacedKey PICK_KEY = new org.bukkit.NamespacedKey(this, "pickaxe");

    /** Anciennes configs : pioche en pierre Efficacité I -> pioche en netherite Efficacité V. */
    private void migrateConfig() {
        if (getConfig().getInt("config-version", 1) >= 2) return;
        if ("STONE_PICKAXE".equalsIgnoreCase(getConfig().getString("pickaxe.material", "STONE_PICKAXE"))
                && getConfig().getInt("pickaxe.efficiency", 1) <= 1) {
            getConfig().set("pickaxe.material", "NETHERITE_PICKAXE");
            getConfig().set("pickaxe.efficiency", 5);
        }
        getConfig().set("config-version", 2);
        saveConfig();
    }

    /** Donne la pioche de la config ; remplace une ancienne pioche de prisonnier (autre matière / enchant). */
    private void givePickaxe(Player pl) {
        Material m = Material.matchMaterial(getConfig().getString("pickaxe.material", "NETHERITE_PICKAXE"));
        if (m == null) m = Material.NETHERITE_PICKAXE;
        int eff = getConfig().getInt("pickaxe.efficiency", 5);
        PlayerInventory inv = pl.getInventory();
        boolean ok = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.getType().name().endsWith("_PICKAXE")) continue;
            if (!ok && it.getType() == m && it.getEnchantmentLevel(Enchantment.EFFICIENCY) == Math.max(0, eff)) {
                ok = true;
                continue;
            }
            inv.setItem(i, null); // vieille pioche de prison (son vrai inventaire est mis de côté)
        }
        if (ok) return;
        ItemStack pick = new ItemStack(m);
        ItemMeta meta = pick.getItemMeta();
        meta.displayName(mm("<gray>Pioche de prisonnier"));
        meta.setUnbreakable(true);
        if (eff > 0) meta.addEnchant(Enchantment.EFFICIENCY, eff, true);
        meta.getPersistentDataContainer().set(PICK_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        pick.setItemMeta(meta);
        if (inv.getItem(0) == null) inv.setItem(0, pick);
        else inv.addItem(pick);
    }

    private void release(UUID id, boolean announce) {
        Prisoner p = prisoners.remove(id);
        if (p == null) return;
        save();
        Player pl = Bukkit.getPlayer(id);
        if (pl == null) {
            // hors ligne : on le libérera à sa connexion (inventaire rendu à ce moment)
            pendingRelease.put(id, p);
            savePendingRelease();
            return;
        }
        restore(pl, p);
        if (announce) Bukkit.broadcast(mm(msg("prefix") + fill("released-broadcast", "player", MM.escapeTags(p.name))));
    }

    private void restore(Player pl, Prisoner p) {
        hideBar(pl);
        PlayerInventory inv = pl.getInventory();
        // jamais vraiment entré en prison (libéré avant sa connexion) : on ne touche pas à son inventaire
        if (!p.pending) {
            inv.clear();
            ItemStack[] items = new ItemStack[inv.getSize()];
            for (int i = 0; i < Math.min(items.length, p.inventory.size()); i++) {
                String s = p.inventory.get(i);
                if (s.isEmpty()) continue;
                try {
                    items[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
                } catch (RuntimeException ignored) {
                }
            }
            inv.setContents(items);
            pl.setLevel(p.level);
            pl.setExp(p.exp);
            try {
                pl.setGameMode(GameMode.valueOf(p.gamemode));
            } catch (IllegalArgumentException ignored) {
            }
        }
        pl.displayName(null);
        pl.playerListName(null);
        Location out = exit != null && exit.getWorld() != null ? exit : pl.getWorld().getSpawnLocation();
        teleport(pl, out);
        send(pl, "released");
    }

    // libérés pendant qu'ils étaient hors ligne
    private final Map<UUID, Prisoner> pendingRelease = new HashMap<>();

    private void savePendingRelease() {
        // gardé en mémoire et dans le fichier pour survivre à un redémarrage
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (Prisoner p : pendingRelease.values()) {
            String k = "released." + p.id;
            y.set(k + ".inventory", p.inventory);
            y.set(k + ".level", p.level);
            y.set(k + ".exp", (double) p.exp);
            y.set(k + ".gamemode", p.gamemode);
            y.set(k + ".pending", p.pending);
        }
        try {
            y.save(dataFile);
        } catch (IOException ignored) {
        }
    }

    private Prisoner takeReleased(UUID id) {
        Prisoner p = pendingRelease.remove(id);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection c = y.getConfigurationSection("released." + id);
        if (p == null && c != null) {
            p = new Prisoner();
            p.id = id;
            p.inventory = c.getStringList("inventory");
            p.level = c.getInt("level");
            p.exp = (float) c.getDouble("exp");
            p.gamemode = c.getString("gamemode", "SURVIVAL");
            p.pending = c.getBoolean("pending");
        }
        if (c != null) {
            y.set("released." + id, null);
            try {
                y.save(dataFile);
            } catch (IOException ignored) {
            }
        }
        return p;
    }

    private void teleport(Player pl, Location to) {
        allowTp.add(pl.getUniqueId());
        // SmpCombat : la prison passe même si le joueur est "en danger"
        pl.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("smpcombat", "allow_tp_until"),
                org.bukkit.persistence.PersistentDataType.LONG, System.currentTimeMillis() + 2000);
        try {
            pl.teleport(to);
        } finally {
            allowTp.remove(pl.getUniqueId());
        }
    }

    // ================================================================= barre et boucle

    private void showBar(Player pl, Prisoner p) {
        BossBar bar = bars.computeIfAbsent(pl.getUniqueId(), k -> BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
        bar.name(mm(fill("bar", "time", fmt(p.minutes))));
        bar.progress((float) Math.max(0, Math.min(1, p.minutes / Math.max(1, p.total))));
        pl.showBossBar(bar);
    }

    private void hideBar(Player pl) {
        BossBar bar = bars.remove(pl.getUniqueId());
        if (bar != null) pl.hideBossBar(bar);
    }

    private int ticks;

    // une erreur répétée n'est écrite qu'une fois toutes les 10 minutes (jamais de logs qui remplissent le disque)
    private long lastTickError;

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastTickError > 600_000) {
                lastTickError = now;
                getLogger().warning("Erreur (affichée une fois toutes les 10 min) : " + t);
            }
        }
    }

    private void tick() {
        ticks++;
        boolean timePasses = getConfig().getBoolean("time-passes-online", false);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            Prisoner p = prisoners.get(pl.getUniqueId());
            if (p == null || p.pending) continue;
            if (timePasses) p.minutes -= 0.5 / 60.0;
            if (p.minutes <= 0) {
                release(p.id, true);
                continue;
            }
            // anti-évasion : hors de la cellule et hors de la mine = retour en cellule
            if (ready() && !allowed(pl.getLocation())) {
                teleport(pl, cell);
                send(pl, "escape");
            }
            if (pl.isFlying() || pl.getAllowFlight()) {
                pl.setFlying(false);
                pl.setAllowFlight(false);
            }
            if (pl.getGameMode() != GameMode.SURVIVAL && !pl.hasPermission("smpprison.admin")) pl.setGameMode(GameMode.SURVIVAL);
            showBar(pl, p);
        }
        if (ticks % 120 == 0) save();
    }

    private BoundingBox mineBox() {
        if (pos1 == null || pos2 == null) return null;
        return BoundingBox.of(pos1.getBlock(), pos2.getBlock());
    }

    private boolean inMine(Location l) {
        BoundingBox b = mineBox();
        return b != null && pos1.getWorld() == l.getWorld() && b.clone().expand(0.01).contains(l.toVector());
    }

    private boolean allowed(Location l) {
        if (cell != null && l.getWorld() == cell.getWorld() && l.distance(cell) <= getConfig().getDouble("cell-radius", 6)) return true;
        BoundingBox b = mineBox();
        if (b != null && pos1.getWorld() == l.getWorld() && b.clone().expand(2, 3, 2).contains(l.toVector())) return true;
        // pas de zone de mine définie : autour du point d'arrivée
        return b == null && mine != null && l.getWorld() == mine.getWorld() && l.distance(mine) <= 30;
    }

    // ================================================================= zone interdite aux autres joueurs

    /** La mine (+ une marge) : personne d'autre que les prisonniers et le staff n'y entre ni n'y touche. La cellule si protect-cell. */
    private boolean protectedZone(Location l) {
        if (l == null || l.getWorld() == null) return false;
        double margin = getConfig().getDouble("protect-margin", 4);
        if (getConfig().getBoolean("protect-cell", false) && cell != null && l.getWorld() == cell.getWorld()
                && l.distance(cell) <= getConfig().getDouble("cell-radius", 6) + margin) return true;
        BoundingBox b = mineBox();
        if (b != null && pos1.getWorld() == l.getWorld() && b.clone().expand(margin, margin + 2, margin).contains(l.toVector())) return true;
        return b == null && mine != null && l.getWorld() == mine.getWorld() && l.distance(mine) <= 30 + margin;
    }

    private boolean outsider(Player p) {
        return !isPrisoner(p) && !p.hasPermission("smpprison.admin");
    }

    private void kickOut(Player p) {
        Location out = exit != null && !protectedZone(exit) ? exit : p.getWorld().getSpawnLocation();
        if (protectedZone(out)) return; // sortie et spawn dans la zone : on le laisse simplement repartir
        allowTp.add(p.getUniqueId());
        try {
            p.teleport(out);
        } finally {
            allowTp.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOutsiderMove(org.bukkit.event.player.PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Player p = e.getPlayer();
        if (!protectedZone(e.getTo()) || !outsider(p)) return;
        if (protectedZone(e.getFrom())) {
            // déjà dedans (connexion, libéré à l'intérieur...) : dehors
            Bukkit.getScheduler().runTask(this, () -> kickOut(p));
            return;
        } else {
            Location back = e.getFrom().clone();
            back.setYaw(e.getTo().getYaw());
            back.setPitch(e.getTo().getPitch());
            e.setTo(back); // mur invisible
        }
        p.sendActionBar(mm(msg("outsider")));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOutsiderTeleport(PlayerTeleportEvent e) {
        if (allowTp.contains(e.getPlayer().getUniqueId())) return;
        if (protectedZone(e.getTo()) && !protectedZone(e.getFrom()) && outsider(e.getPlayer())) {
            e.setCancelled(true); // perle, /tpa, /home...
            send(e.getPlayer(), "outsider");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOutsiderPlace(BlockPlaceEvent e) {
        if (protectedZone(e.getBlock().getLocation()) && outsider(e.getPlayer())) {
            e.setCancelled(true);
            send(e.getPlayer(), "outsider");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOutsiderBreak(BlockBreakEvent e) {
        if (protectedZone(e.getBlock().getLocation()) && outsider(e.getPlayer())) {
            e.setCancelled(true);
            send(e.getPlayer(), "outsider");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOutsiderBucket(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        if (protectedZone(e.getBlock().getLocation()) && outsider(e.getPlayer())) e.setCancelled(true);
    }

    /** Lave / eau versée juste dehors qui coule dedans. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlowIn(org.bukkit.event.block.BlockFromToEvent e) {
        if (protectedZone(e.getToBlock().getLocation()) && !protectedZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonIn(org.bukkit.event.block.BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (protectedZone(b.getRelative(e.getDirection()).getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
        if (protectedZone(e.getBlock().getRelative(e.getDirection()).getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplodeIn(org.bukkit.event.entity.EntityExplodeEvent e) {
        e.blockList().removeIf(b -> protectedZone(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplodeIn(org.bukkit.event.block.BlockExplodeEvent e) {
        e.blockList().removeIf(b -> protectedZone(b.getLocation()));
    }

    private boolean isPrisoner(Player p) {
        Prisoner pr = prisoners.get(p.getUniqueId());
        return pr != null && !pr.pending;
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("prison")) {
            if (!(sender instanceof Player pl)) return true;
            Prisoner p = prisoners.get(pl.getUniqueId());
            if (p == null || p.pending) {
                send(pl, "not-prisoner");
                return true;
            }
            switch (name) {
                case "travailler" -> {
                    if (!ready()) {
                        send(pl, "no-setup");
                        return true;
                    }
                    givePickaxe(pl);
                    teleport(pl, mine);
                    send(pl, "to-mine", "per", fmt(perBlock()));
                }
                case "cellule" -> {
                    if (ready()) teleport(pl, cell);
                    send(pl, "to-cell");
                }
                default -> send(pl, "remaining", "time", fmt(p.minutes), "blocks", String.format(Locale.FRANCE, "%,d",
                        (long) Math.ceil(p.minutes / perBlock())));
            }
            return true;
        }
        if (a.length == 0) {
            sender.sendMessage(mm("<red><b>Prison</b> <gray>- /prison <joueur> <durée> [raison] · libérer <joueur> · réduire <joueur> <durée>"
                    + " · info <joueur> · liste · setcellule · setmine · setsortie · pos1 · pos2"));
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        Player self = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "setcellule", "setcell" -> {
                if (self == null) return true;
                cell = self.getLocation();
                save();
                send(sender, "set", "what", "Cellule");
            }
            case "setmine" -> {
                if (self == null) return true;
                mine = self.getLocation();
                save();
                send(sender, "set", "what", "Arrivée de la mine");
            }
            case "setsortie", "setexit" -> {
                if (self == null) return true;
                exit = self.getLocation();
                save();
                send(sender, "set", "what", "Sortie");
            }
            case "pos1" -> {
                if (self == null) return true;
                pos1 = self.getLocation().getBlock().getLocation();
                save();
                send(sender, "set", "what", "Coin 1 de la mine");
            }
            case "pos2" -> {
                if (self == null) return true;
                pos2 = self.getLocation().getBlock().getLocation();
                save();
                send(sender, "set", "what", "Coin 2 de la mine");
            }
            case "liste", "list" -> {
                if (prisoners.isEmpty()) send(sender, "list-empty");
                for (Prisoner p : prisoners.values()) {
                    send(sender, "list-line", "player", MM.escapeTags(p.name), "time", fmt(p.minutes), "reason", MM.escapeTags(p.reason));
                }
            }
            case "libérer", "liberer", "release", "free" -> {
                if (a.length < 2) return true;
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                if (!prisoners.containsKey(t.getUniqueId())) {
                    send(sender, "not-jailed", "player", a[1]);
                    return true;
                }
                release(t.getUniqueId(), true);
            }
            case "réduire", "reduire", "reduce" -> {
                if (a.length < 3) return true;
                Prisoner p = prisoners.get(Bukkit.getOfflinePlayer(a[1]).getUniqueId());
                double m = parseMinutes(a[2]);
                if (p == null || m < 0) {
                    send(sender, m < 0 ? "bad-time" : "not-jailed", "player", a[1]);
                    return true;
                }
                p.minutes -= m;
                send(sender, "remaining", "time", fmt(p.minutes), "blocks", String.valueOf((long) Math.ceil(Math.max(0, p.minutes) / perBlock())));
                if (p.minutes <= 0) release(p.id, true);
                save();
            }
            case "info" -> {
                if (a.length < 2) return true;
                Prisoner p = prisoners.get(Bukkit.getOfflinePlayer(a[1]).getUniqueId());
                if (p == null) {
                    send(sender, "not-jailed", "player", a[1]);
                    return true;
                }
                sender.sendMessage(mm("<gray>" + MM.escapeTags(p.name) + " : <white>" + fmt(p.minutes) + "</white> restants sur "
                        + fmt(p.total) + " · raison : <white>" + MM.escapeTags(p.reason) + "</white> · par " + MM.escapeTags(p.by)));
            }
            default -> {
                if (a.length < 2) {
                    send(sender, "bad-time");
                    return true;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[0]);
                if (t == null) t = Bukkit.getPlayerExact(a[0]);
                if (t == null) {
                    sender.sendMessage(mm("<red>Joueur inconnu : " + MM.escapeTags(a[0])));
                    return true;
                }
                double m = parseMinutes(a[1]);
                if (m <= 0) {
                    send(sender, "bad-time");
                    return true;
                }
                if (!ready()) send(sender, "no-setup");
                String reason = a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "Triche";
                jail(sender, t, m, reason);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (!cmd.getName().equalsIgnoreCase("prison")) return out;
        if (a.length == 1) {
            out.addAll(List.of("libérer", "réduire", "info", "liste", "setcellule", "setmine", "setsortie", "pos1", "pos2"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 2) {
            if (List.of("libérer", "liberer", "réduire", "reduire", "info").contains(a[0].toLowerCase(Locale.ROOT))) {
                for (Prisoner p : prisoners.values()) out.add(p.name);
            } else {
                out.addAll(List.of("30m", "1h", "1j", "1s", "1mo", "500a"));
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ================================================================= ce que le prisonnier ne peut pas faire

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        if (!isPrisoner(e.getPlayer()) || e.getPlayer().hasPermission("smpprison.admin")) return;
        String c = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (c.contains(":")) c = c.substring(c.indexOf(':') + 1);
        if (getConfig().getStringList("allowed-commands").contains(c)) return;
        e.setCancelled(true);
        send(e.getPlayer(), "denied-command");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTp(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!isPrisoner(p) || allowTp.contains(p.getUniqueId())) return;
        // seules les petites corrections du jeu (même endroit) passent
        if (e.getTo() != null && allowed(e.getTo()) && e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) return;
        e.setCancelled(true);
        send(p, "escape");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Prisoner pr = prisoners.get(p.getUniqueId());
        if (pr == null || pr.pending) {
            // personne d'autre ne casse la mine (sauf le staff)
            if (inMine(e.getBlock().getLocation()) && !p.hasPermission("smpprison.admin")) e.setCancelled(true);
            return;
        }
        if (!canMine(e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        e.setDropItems(false);
        e.setExpToDrop(0);
    }

    private boolean canMine(Block b) {
        return inMine(b.getLocation()) && !regen.containsKey(b) && b.getType().getHardness() >= 0 && !b.getType().isAir();
    }

    /** Le bloc est vraiment cassé : il repoussera, et la peine baisse. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Prisoner pr = prisoners.get(p.getUniqueId());
        if (pr == null || pr.pending || !inMine(e.getBlock().getLocation()) || regen.containsKey(e.getBlock())) return;
        Block b = e.getBlock();
        e.setDropItems(false);
        e.setExpToDrop(0);
        regen.put(b, b.getBlockData().clone());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            BlockData d = regen.remove(b);
            if (d != null) b.setBlockData(d, false);
        }, Math.max(1, getConfig().getLong("regen-seconds", 20)) * 20L);
        pr.minutes -= perBlock();
        p.sendActionBar(mm("<green>-" + fmt(perBlock()) + " <dark_gray>|</dark_gray> <gray>reste <white>" + fmt(pr.minutes)));
        if (pr.minutes <= 0) Bukkit.getScheduler().runTask(this, () -> release(pr.id, true));
    }

    /**
     * La prison est souvent dans une zone protégée (WorldGuard au spawn...) : la protection annule la casse.
     * Pour un prisonnier qui mine dans la mine, on passe en dernier et on autorise quand même.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreakAllow(BlockBreakEvent e) {
        Prisoner pr = prisoners.get(e.getPlayer().getUniqueId());
        if (pr == null || pr.pending || !e.isCancelled()) return;
        if (!canMine(e.getBlock())) return;
        e.setCancelled(false);
        e.setDropItems(false);
        e.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDigAllow(org.bukkit.event.block.BlockDamageEvent e) {
        if (!e.isCancelled() || !isPrisoner(e.getPlayer()) || !inMine(e.getBlock().getLocation())) return;
        e.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLeftClickAllow(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null || !isPrisoner(e.getPlayer())) return;
        if (!inMine(e.getClickedBlock().getLocation())) return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW);
        e.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (isPrisoner(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (!isPrisoner(e.getPlayer())) return;
        // seul le clic gauche pour miner est permis
        if (e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_AIR) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (isPrisoner(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && isPrisoner(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        if (isPrisoner(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isPrisoner(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isPrisoner(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isPrisoner(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && isPrisoner(p)) {
            e.setCancelled(true);
            p.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicle(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p && isPrisoner(p)) e.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (getConfig().getBoolean("can-chat", true)) return;
        Prisoner p = prisoners.get(e.getPlayer().getUniqueId());
        if (p != null && !p.pending) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(mm(msg("prefix") + msg("denied")));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (!isPrisoner(e.getEntity())) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (isPrisoner(e.getPlayer()) && ready()) e.setRespawnLocation(cell);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Prisoner released = takeReleased(p.getUniqueId());
        if (released != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) restore(p, released);
            }, 10L);
            return;
        }
        if (prisoners.containsKey(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) enter(p);
            }, 5L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hideBar(e.getPlayer());
        save();
    }

    @SuppressWarnings("unused")
    private static World w(Location l) {
        return l == null ? null : l.getWorld();
    }
}
