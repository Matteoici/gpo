package fr.smpduel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Duels 1v1 : recherche d'adversaire (file d'attente) ou défi direct, trois modes (amical = keepInventory,
 * à mort = on perd tout, pari = chacun mise des objets, le gagnant prend tout), arènes construites dans le
 * style de celle du Roi Citrouille, pas d'élytres, tout le reste est permis. ELO + classement.
 */
public final class SmpDuel extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static SmpDuel instance;

    final Map<String, DuelArena> arenas = new LinkedHashMap<>();
    private final Map<Mode, LinkedHashMap<UUID, Long>> queues = new EnumMap<>(Mode.class);
    private final Map<UUID, Match> matches = new HashMap<>();
    private final Map<UUID, BetWindow> bets = new HashMap<>();
    /** arènes réservées pendant qu'une mise se prépare */
    private final Set<DuelArena> reserved = new HashSet<>();
    private record Challenge(UUID from, Mode mode, long at) {}
    private final Map<UUID, Challenge> challenges = new HashMap<>();
    private final Set<UUID> bypassTp = new HashSet<>();
    private Stats stats;
    private Escrow escrow;
    NamespacedKey duelKey, returnKey;
    private File arenaFile;

    // ================================================================= API (SmpBoard...)

    public static int elo(UUID id) {
        if (instance == null) return 1000;
        Stats.Entry e = instance.stats.peek(id);
        return e == null ? 1000 : e.elo;
    }

    public static int wins(UUID id) {
        if (instance == null) return 0;
        Stats.Entry e = instance.stats.peek(id);
        return e == null ? 0 : e.wins();
    }

    public static int losses(UUID id) {
        if (instance == null) return 0;
        Stats.Entry e = instance.stats.peek(id);
        return e == null ? 0 : e.losses();
    }

    /** Ce point est-il dans une arène de duel ? (pour les autres plugins) */
    public static boolean isArena(Location l) {
        return instance != null && l != null && instance.arenaAt(l) != null;
    }

    public static boolean inDuel(UUID id) {
        return instance != null && instance.matches.containsKey(id);
    }

    // ================================================================= démarrage

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        duelKey = new NamespacedKey(this, "duel");
        returnKey = new NamespacedKey(this, "return");
        for (Mode m : Mode.values()) queues.put(m, new LinkedHashMap<>());
        getDataFolder().mkdirs();
        stats = new Stats(new File(getDataFolder(), "stats.yml"));
        escrow = new Escrow(new File(getDataFolder(), "mises.yml"));
        arenaFile = new File(getDataFolder(), "arenes.yml");
        loadArenas();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::matchmaking, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> stats.save(false), 6000L, 6000L);
        for (Player p : Bukkit.getOnlinePlayers()) rejoin(p);
    }

    @Override
    public void onDisable() {
        for (Match m : new ArrayList<>(new HashSet<>(matches.values()))) m.draw(msg("draw-stop"));
        for (BetWindow b : new ArrayList<>(new HashSet<>(bets.values()))) b.cancel(b.ida);
        stats.save(true);
        escrow.save();
    }

    Stats stats() {
        return stats;
    }

    Escrow escrow() {
        return escrow;
    }

    static Component mm(String s) {
        return MM.deserialize(s == null ? "" : s);
    }

    String msg(String key) {
        String s = getConfig().getString("messages." + key);
        return s == null ? "<red>[" + key + "]" : s;
    }

    private void send(CommandSender s, String key) {
        s.sendMessage(mm(msg("prefix") + msg(key)));
    }

    private void sendRaw(CommandSender s, String text) {
        s.sendMessage(mm(msg("prefix") + text));
    }

    String modeName(Mode m) {
        return msg("mode-" + m.id);
    }

    // ================================================================= arènes (fichier)

    private void loadArenas() {
        arenas.clear();
        if (!arenaFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(arenaFile);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s != null) arenas.put(k, DuelArena.load(k, s));
        }
    }

    private void saveArenas() {
        YamlConfiguration y = new YamlConfiguration();
        for (DuelArena a : arenas.values()) a.save(y.createSection(a.name));
        try {
            y.save(arenaFile);
        } catch (IOException e) {
            getLogger().warning("arenes.yml : " + e.getMessage());
        }
    }

    private DuelArena freeArena() {
        List<DuelArena> free = new ArrayList<>();
        for (DuelArena a : arenas.values()) if (a.ready() && a.match == null && !reserved.contains(a)) free.add(a);
        return free.isEmpty() ? null : free.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(free.size()));
    }

    DuelArena arenaAt(Location l) {
        for (DuelArena a : arenas.values()) if (a.region(l)) return a;
        return null;
    }

    // ================================================================= téléportation / retour

    void teleport(Player p, Location to) {
        bypassTp.add(p.getUniqueId());
        // SmpCombat : téléportation autorisée 2 s
        p.getPersistentDataContainer().set(new NamespacedKey("smpcombat", "allow_tp_until"), PersistentDataType.LONG, System.currentTimeMillis() + 2000);
        p.teleport(to);
        bypassTp.remove(p.getUniqueId());
    }

    Location returnLoc(Player p) {
        String s = p.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        if (s == null) return null;
        int i = s.indexOf(';');
        if (i < 0) return null;
        World w = Bukkit.getWorld(s.substring(0, i));
        return DuelArena.loc(w, s.substring(i + 1));
    }

    void sendBack(Player p) {
        Location l = returnLoc(p);
        p.getPersistentDataContainer().remove(returnKey);
        p.getPersistentDataContainer().remove(duelKey);
        if (l == null) l = Bukkit.getWorlds().get(0).getSpawnLocation();
        Match.heal(p);
        teleport(p, l);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
    }

    /** Connexion : un duel interrompu (déconnexion, crash) → retour à sa place, mises rendues. */
    private void rejoin(Player p) {
        if (matches.containsKey(p.getUniqueId())) return;
        p.getPersistentDataContainer().remove(duelKey);
        if (returnLoc(p) != null && !p.isDead()) Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && !matches.containsKey(p.getUniqueId()) && returnLoc(p) != null) sendBack(p);
        }, 10L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && escrow.deliver(p)) send(p, "bet-delivered");
        }, 40L);
    }

    void ended(Match m) {
        matches.remove(m.ida, m);
        matches.remove(m.idb, m);
    }

    // ================================================================= qui peut se battre

    /** null si OK, sinon la clé du message d'erreur. */
    private String cannot(Player p) {
        if (p == null || !p.isOnline()) return "offline";
        if (p.isDead()) return "dead";
        if (matches.containsKey(p.getUniqueId())) return "already-dueling";
        if (bets.containsKey(p.getUniqueId())) return "already-betting";
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return "gamemode";
        if (inDanger(p)) return "in-danger";
        return null;
    }

    /** SmpCombat : en danger = pas de duel (sinon on fuirait un combat). */
    private boolean inDanger(Player p) {
        Plugin c = Bukkit.getPluginManager().getPlugin("SmpCombat");
        if (c == null || !c.isEnabled()) return false;
        try {
            return (boolean) c.getClass().getMethod("inDanger", Player.class).invoke(c, p);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return false;
        }
    }

    // ================================================================= file d'attente

    Mode queuedMode(UUID id) {
        for (Map.Entry<Mode, LinkedHashMap<UUID, Long>> e : queues.entrySet()) if (e.getValue().containsKey(id)) return e.getKey();
        return null;
    }

    int queueSize(Mode m) {
        return queues.get(m).size();
    }

    void joinQueue(Player p, Mode m) {
        String err = cannot(p);
        if (err != null) {
            send(p, err);
            return;
        }
        if (arenas.values().stream().noneMatch(DuelArena::ready)) {
            send(p, "no-arena");
            return;
        }
        leaveQueue(p.getUniqueId());
        queues.get(m).put(p.getUniqueId(), System.currentTimeMillis());
        sendRaw(p, msg("queue-join").replace("<mode>", modeName(m)));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
    }

    boolean leaveQueue(UUID id) {
        boolean any = false;
        for (LinkedHashMap<UUID, Long> q : queues.values()) any |= q.remove(id) != null;
        return any;
    }

    private void matchmaking() {
        // défis expirés
        challenges.values().removeIf(c -> System.currentTimeMillis() - c.at() > 60_000);
        for (Mode m : Mode.values()) {
            LinkedHashMap<UUID, Long> q = queues.get(m);
            q.keySet().removeIf(id -> {
                Player p = Bukkit.getPlayer(id);
                return p == null || cannot(p) != null;
            });
            while (q.size() >= 2) {
                DuelArena arena = freeArena();
                if (arena == null) break;
                Iterator<UUID> it = q.keySet().iterator();
                Player a = Bukkit.getPlayer(it.next());
                it.remove();
                Player b = Bukkit.getPlayer(it.next());
                it.remove();
                if (a == null || b == null) continue;
                sendRaw(a, msg("match-found").replace("<enemy>", b.getName()).replace("<mode>", modeName(m)));
                sendRaw(b, msg("match-found").replace("<enemy>", a.getName()).replace("<mode>", modeName(m)));
                begin(a, b, m, arena, true);
            }
            // barre d'attente
            boolean noArena = !q.isEmpty() && freeArena() == null;
            for (Map.Entry<UUID, Long> e : q.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) continue;
                long s = (System.currentTimeMillis() - e.getValue()) / 1000;
                p.sendActionBar(mm(msg(noArena ? "queue-bar-wait" : "queue-bar").replace("<mode>", modeName(m))
                        .replace("<time>", String.format("%d:%02d", s / 60, s % 60)).replace("<n>", String.valueOf(q.size()))));
            }
        }
    }

    /** Lance un duel (en pari : d'abord la fenêtre de mise). */
    private void begin(Player a, Player b, Mode m, DuelArena arena, boolean fromQueue) {
        leaveQueue(a.getUniqueId());
        leaveQueue(b.getUniqueId());
        if (m != Mode.PARI) {
            start(a, b, m, arena, null, null);
            return;
        }
        reserved.add(arena);
        BetWindow[] w = new BetWindow[1];
        w[0] = new BetWindow(this, a, b, (sa, sb) -> {
            bets.remove(a.getUniqueId());
            bets.remove(b.getUniqueId());
            reserved.remove(arena);
            if (cannot(a) != null || cannot(b) != null || arena.match != null) {
                escrow.give(a.getUniqueId(), sa);
                escrow.give(b.getUniqueId(), sb);
                for (Player p : new Player[]{a, b}) if (p.isOnline()) send(p, "bet-aborted");
                return;
            }
            start(a, b, m, arena, sa, sb);
        }, () -> {
            bets.remove(a.getUniqueId());
            bets.remove(b.getUniqueId());
            reserved.remove(arena);
            // trouvé par la recherche : celui qui n'a pas annulé reprend sa place
            if (fromQueue && w[0] != null && w[0].cancelledBy != null) {
                Player other = Bukkit.getPlayer(w[0].cancelledBy.equals(a.getUniqueId()) ? b.getUniqueId() : a.getUniqueId());
                if (other != null) Bukkit.getScheduler().runTaskLater(this, () -> joinQueue(other, Mode.PARI), 5L);
            }
        });
        bets.put(a.getUniqueId(), w[0]);
        bets.put(b.getUniqueId(), w[0]);
    }

    private void start(Player a, Player b, Mode m, DuelArena arena, List<ItemStack> sa, List<ItemStack> sb) {
        Match match = new Match(this, arena, m, a, b, sa, sb);
        matches.put(a.getUniqueId(), match);
        matches.put(b.getUniqueId(), match);
        match.start();
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("arene") || sub.equals("arène") || sub.equals("arena")) {
            if (!s.hasPermission("smpduel.admin")) {
                send(s, "no-perm");
                return true;
            }
            arenaCommand(s, a);
            return true;
        }
        if (sub.equals("reload") && s.hasPermission("smpduel.admin")) {
            reloadConfig();
            loadArenas();
            sendRaw(s, "<green>Config et arènes rechargées.");
            return true;
        }
        if (sub.equals("top") || sub.equals("classement")) {
            top(s);
            return true;
        }
        if (sub.equals("stats")) {
            UUID id = a.length >= 2 ? Bukkit.getOfflinePlayer(a[1]).getUniqueId() : s instanceof Player p ? p.getUniqueId() : null;
            if (id != null) statsMsg(s, id, a.length >= 2 ? a[1] : s.getName());
            return true;
        }
        if (!(s instanceof Player p)) return true;
        switch (sub) {
            case "" -> new Menu(p).open();
            case "quitter", "leave", "stop", "annuler" -> {
                if (leaveQueue(p.getUniqueId())) {
                    send(p, "queue-leave");
                    p.sendActionBar(Component.empty());
                } else {
                    Match m = matches.get(p.getUniqueId());
                    if (m != null && m.state == Match.State.LOOT && p.getUniqueId().equals(m.winner)) m.finish();
                    else send(p, "not-queued");
                }
            }
            case "accepter", "accept", "oui" -> accept(p, a.length >= 2 ? a[1] : null);
            case "refuser", "deny", "non" -> {
                Challenge c = challenges.remove(p.getUniqueId());
                if (c == null) send(p, "no-challenge");
                else {
                    send(p, "challenge-denied-you");
                    Player from = Bukkit.getPlayer(c.from());
                    if (from != null) sendRaw(from, msg("challenge-denied").replace("<player>", p.getName()));
                }
            }
            default -> {
                Mode m = Mode.of(sub);
                if (m != null && a.length == 1) {
                    joinQueue(p, m);
                    return true;
                }
                // /duel <joueur> [mode]
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null || t.equals(p)) {
                    send(p, "unknown-player");
                    return true;
                }
                Mode mode = a.length >= 2 && Mode.of(a[1]) != null ? Mode.of(a[1]) : Mode.AMICAL;
                challenge(p, t, mode);
            }
        }
        return true;
    }

    private void challenge(Player p, Player t, Mode mode) {
        String err = cannot(p);
        if (err != null) {
            send(p, err);
            return;
        }
        if (cannot(t) != null) {
            sendRaw(p, msg("target-busy").replace("<player>", t.getName()));
            return;
        }
        challenges.put(t.getUniqueId(), new Challenge(p.getUniqueId(), mode, System.currentTimeMillis()));
        sendRaw(p, msg("challenge-sent").replace("<player>", t.getName()).replace("<mode>", modeName(mode)));
        sendRaw(t, msg("challenge-received").replace("<player>", p.getName()).replace("<mode>", modeName(mode)));
        t.sendMessage(mm(msg("challenge-buttons").replace("<player>", p.getName())));
        t.playSound(t.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
    }

    private void accept(Player p, String fromName) {
        Challenge c = challenges.get(p.getUniqueId());
        if (c == null || (fromName != null && !fromName.equalsIgnoreCase(Bukkit.getOfflinePlayer(c.from()).getName()))) {
            send(p, "no-challenge");
            return;
        }
        challenges.remove(p.getUniqueId());
        Player from = Bukkit.getPlayer(c.from());
        String err = cannot(p);
        if (err == null && cannot(from) != null) err = "challenger-busy";
        if (err != null) {
            send(p, err);
            return;
        }
        DuelArena arena = freeArena();
        if (arena == null) {
            send(p, "no-free-arena");
            send(from, "no-free-arena");
            return;
        }
        begin(from, p, c.mode(), arena, false);
    }

    private void top(CommandSender s) {
        sendRaw(s, msg("top-header"));
        int i = 1;
        for (Map.Entry<UUID, Stats.Entry> e : stats.top(10)) {
            Stats.Entry v = e.getValue();
            s.sendMessage(mm(msg("top-line").replace("<n>", String.valueOf(i++)).replace("<player>", v.name)
                    .replace("<elo>", String.valueOf(v.elo)).replace("<w>", String.valueOf(v.wins())).replace("<l>", String.valueOf(v.losses()))));
        }
    }

    private void statsMsg(CommandSender s, UUID id, String name) {
        Stats.Entry e = stats.of(id);
        sendRaw(s, msg("stats").replace("<player>", name).replace("<elo>", String.valueOf(e.elo))
                .replace("<rank>", String.valueOf(stats.rank(id))).replace("<w>", String.valueOf(e.wins()))
                .replace("<l>", String.valueOf(e.losses())).replace("<streak>", String.valueOf(e.streak)).replace("<best>", String.valueOf(e.best)));
    }

    private void arenaCommand(CommandSender s, String[] a) {
        String what = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
        String name = a.length >= 3 ? a[2].toLowerCase(Locale.ROOT) : null;
        Player p = s instanceof Player pl ? pl : null;
        switch (what) {
            case "construire", "build" -> {
                if (p == null || name == null) {
                    sendRaw(s, "<red>/duel arene construire <nom> [rayon 14-40, défaut 20 comme le boss] [ici|ciel|Y]");
                    return;
                }
                if (arenas.containsKey(name)) {
                    sendRaw(s, "<red>Une arène s'appelle déjà comme ça.");
                    return;
                }
                int r = a.length >= 4 ? clamp(parse(a[3], 20), 14, 40) : 20;
                Location c = p.getLocation();
                int y = c.getBlockY() - 1;
                if (a.length >= 5) {
                    if (a[4].equalsIgnoreCase("ciel")) y = Math.min(c.getWorld().getMaxHeight() - 40, c.getBlockY() + 60);
                    else if (!a[4].equalsIgnoreCase("ici")) y = parse(a[4], y);
                }
                DuelArena arena = new DuelArena(name, c.getWorld().getName(), c.getBlockX(), y, c.getBlockZ(), r);
                arena.defaultSpawns();
                sendRaw(s, "<yellow>Construction de l'arène <white>" + name + "</white> (rayon " + r + ")…");
                arena.build(this, new File(getDataFolder(), "sauvegardes/" + name + ".dat"), n -> {
                    arenas.put(name, arena);
                    saveArenas();
                    sendRaw(s, "<green>Arène <white>" + name + "</white> prête (" + n + " blocs). <gray>/duel arene annuler " + name + " pour tout remettre comme avant.");
                    if (p.isOnline()) teleport(p, arena.spawn1);
                });
            }
            case "definir", "définir", "define" -> {
                if (p == null || name == null) {
                    sendRaw(s, "<red>/duel arene definir <nom> <rayon> <gray>(au centre d'une arène déjà construite, debout sur le sol)");
                    return;
                }
                int r = a.length >= 4 ? clamp(parse(a[3], 20), 5, 40) : 20;
                Location c = p.getLocation();
                DuelArena arena = new DuelArena(name, c.getWorld().getName(), c.getBlockX(), c.getBlockY() - 1, c.getBlockZ(), r);
                arena.defaultSpawns();
                arenas.put(name, arena);
                saveArenas();
                sendRaw(s, "<green>Arène <white>" + name + "</white> définie ici (rayon " + r + "). <gray>Place les départs : /duel arene spawn1|spawn2 " + name);
            }
            case "spawn1", "spawn2" -> {
                DuelArena arena = name == null ? null : arenas.get(name);
                if (p == null || arena == null) {
                    sendRaw(s, "<red>/duel arene " + what + " <nom> <gray>(là où tu es)");
                    return;
                }
                if (what.equals("spawn1")) arena.spawn1 = p.getLocation().clone();
                else arena.spawn2 = p.getLocation().clone();
                saveArenas();
                sendRaw(s, "<green>Départ " + what.charAt(5) + " de " + name + " placé ici.");
            }
            case "supprimer", "remove" -> {
                DuelArena arena = name == null ? null : arenas.get(name);
                if (arena == null) {
                    sendRaw(s, "<red>Arène inconnue.");
                    return;
                }
                if (arena.match != null) arena.match.draw(msg("draw-stop"));
                arenas.remove(name);
                saveArenas();
                sendRaw(s, "<yellow>Arène " + name + " retirée (les blocs restent ; /duel arene annuler " + name + " pour les enlever).");
            }
            case "annuler", "undo" -> {
                if (name == null) {
                    sendRaw(s, "<red>/duel arene annuler <nom>");
                    return;
                }
                DuelArena arena = arenas.remove(name);
                if (arena != null && arena.match != null) arena.match.draw(msg("draw-stop"));
                saveArenas();
                int n = DuelArena.restore(this, new File(getDataFolder(), "sauvegardes/" + name + ".dat"));
                sendRaw(s, n < 0 ? "<red>Pas de sauvegarde pour cette arène." : "<green>Terrain remis comme avant (" + n + " blocs).");
            }
            case "tp" -> {
                DuelArena arena = name == null ? null : arenas.get(name);
                if (p == null || arena == null || arena.spawn1 == null) {
                    sendRaw(s, "<red>Arène inconnue.");
                    return;
                }
                teleport(p, arena.spawn1);
            }
            case "liste", "list" -> {
                if (arenas.isEmpty()) sendRaw(s, "<gray>Aucune arène. <white>/duel arene construire <nom>");
                for (DuelArena ar : arenas.values()) {
                    sendRaw(s, "<gold>" + ar.name + " <gray>" + ar.world + " " + ar.cx + " " + ar.cy + " " + ar.cz + " · rayon " + ar.r + " · "
                            + (ar.match != null ? "<red>duel en cours" : ar.ready() ? "<green>libre" : "<red>monde introuvable"));
                }
            }
            default -> {
                sendRaw(s, "<gold><b>Arènes de duel");
                sendRaw(s, "<aqua>/duel arene construire <nom> [rayon] [ici|ciel|Y]</aqua> <gray>- construit une arène ici");
                sendRaw(s, "<aqua>/duel arene definir <nom> <rayon></aqua> <gray>- une arène déjà construite (tu es au centre)");
                sendRaw(s, "<aqua>/duel arene spawn1|spawn2 <nom></aqua> <gray>- les deux points de départ");
                sendRaw(s, "<aqua>/duel arene liste · tp <nom> · supprimer <nom> · annuler <nom>");
            }
        }
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("amical", "mort", "pari", "quitter", "accepter", "refuser", "top", "stats"));
            if (s.hasPermission("smpduel.admin")) out.add("arene");
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(s)) out.add(p.getName());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("arene") && s.hasPermission("smpduel.admin")) {
            out.addAll(List.of("construire", "definir", "spawn1", "spawn2", "liste", "tp", "supprimer", "annuler"));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("arene") && s.hasPermission("smpduel.admin")) {
            out.addAll(arenas.keySet());
        } else if (a.length == 2 && Bukkit.getPlayerExact(a[0]) != null) {
            out.addAll(List.of("amical", "mort", "pari"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ================================================================= menu /duel

    private final class Menu implements InventoryHolder {
        private final Inventory inv;
        private final Player p;

        Menu(Player p) {
            this.p = p;
            this.inv = Bukkit.createInventory(this, 27, mm(msg("menu-title")));
            ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta pm = pane.getItemMeta();
            pm.setHideTooltip(true);
            pane.setItemMeta(pm);
            for (int i = 0; i < 27; i++) inv.setItem(i, pane);
            Mode queued = queuedMode(p.getUniqueId());
            inv.setItem(11, modeItem(Mode.AMICAL, Material.TOTEM_OF_UNDYING, queued));
            inv.setItem(13, modeItem(Mode.PARI, Material.GOLD_INGOT, queued));
            inv.setItem(15, modeItem(Mode.MORT, Material.WITHER_SKELETON_SKULL, queued));
            inv.setItem(4, statsItem());
            inv.setItem(22, topItem());
        }

        void open() {
            p.openInventory(inv);
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        private ItemStack modeItem(Mode m, Material mat, Mode queued) {
            List<String> lore = new ArrayList<>(getConfig().getStringList("messages.menu-" + m.id + "-lore"));
            lore.add("");
            lore.add(msg("menu-queue").replace("<n>", String.valueOf(queueSize(m))));
            lore.add(msg(queued == m ? "menu-click-leave" : "menu-click-join"));
            ItemStack it = item(mat, msg("menu-" + m.id + "-name"), lore);
            if (queued == m) {
                ItemMeta meta = it.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                it.setItemMeta(meta);
            }
            return it;
        }

        private ItemStack statsItem() {
            Stats.Entry e = stats.of(p.getUniqueId());
            List<String> lore = new ArrayList<>();
            for (String l : getConfig().getStringList("messages.menu-stats-lore")) {
                lore.add(l.replace("<elo>", String.valueOf(e.elo)).replace("<rank>", String.valueOf(stats.rank(p.getUniqueId())))
                        .replace("<streak>", String.valueOf(e.streak)).replace("<best>", String.valueOf(e.best))
                        .replace("<aw>", String.valueOf(e.of(Mode.AMICAL)[0])).replace("<al>", String.valueOf(e.of(Mode.AMICAL)[1]))
                        .replace("<mw>", String.valueOf(e.of(Mode.MORT)[0])).replace("<ml>", String.valueOf(e.of(Mode.MORT)[1]))
                        .replace("<pw>", String.valueOf(e.of(Mode.PARI)[0])).replace("<pl>", String.valueOf(e.of(Mode.PARI)[1])));
            }
            ItemStack it = item(Material.PLAYER_HEAD, msg("menu-stats-name").replace("<player>", p.getName()), lore);
            SkullMeta sm = (SkullMeta) it.getItemMeta();
            sm.setOwningPlayer(p);
            it.setItemMeta(sm);
            return it;
        }

        private ItemStack topItem() {
            List<String> lore = new ArrayList<>();
            int i = 1;
            for (Map.Entry<UUID, Stats.Entry> e : stats.top(10)) {
                lore.add(msg("top-line").replace("<n>", String.valueOf(i++)).replace("<player>", e.getValue().name)
                        .replace("<elo>", String.valueOf(e.getValue().elo)).replace("<w>", String.valueOf(e.getValue().wins()))
                        .replace("<l>", String.valueOf(e.getValue().losses())));
            }
            if (lore.isEmpty()) lore.add("<gray>Personne pour l'instant…");
            return item(Material.GOLDEN_HELMET, msg("menu-top-name"), lore);
        }

        void click(InventoryClickEvent e) {
            e.setCancelled(true);
            Mode m = switch (e.getRawSlot()) {
                case 11 -> Mode.AMICAL;
                case 13 -> Mode.PARI;
                case 15 -> Mode.MORT;
                default -> null;
            };
            if (m == null) return;
            p.closeInventory();
            if (queuedMode(p.getUniqueId()) == m) {
                leaveQueue(p.getUniqueId());
                send(p, "queue-leave");
                p.sendActionBar(Component.empty());
            } else joinQueue(p, m);
        }
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm(name).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(l);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInvClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder(false);
        if (h instanceof Menu m) m.click(e);
        else if (h instanceof BetWindow b) b.click(e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInvDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder(false);
        if (h instanceof Menu || h instanceof BetWindow) e.setCancelled(true);
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof BetWindow b) || b.closed()) return;
        b.cancel(e.getPlayer().getUniqueId());
    }

    // ================================================================= combat

    private static Player attacker(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    /** Les deux adversaires peuvent toujours se taper (même si la zone interdit le PvP) ; personne d'autre. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPvp(EntityDamageByEntityEvent e) {
        Player att = attacker(e.getDamager());
        Match mv = e.getEntity() instanceof Player v ? matches.get(v.getUniqueId()) : null;
        Match ma = att == null ? null : matches.get(att.getUniqueId());
        if (mv == null && ma == null) return;
        if (mv != null && mv == ma && mv.fighting() && !att.equals(e.getEntity())) {
            e.setCancelled(false);
            return;
        }
        if (mv != null && !mv.fighting()) e.setCancelled(true);
        else if (att != null && e.getEntity() instanceof Player && mv != ma) e.setCancelled(true);
    }

    /** Pas de dégâts pendant le compte à rebours ni après la fin. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = matches.get(p.getUniqueId());
        if (m == null) return;
        if (m.state == Match.State.COUNTDOWN || m.state == Match.State.OVER || (m.state == Match.State.LOOT && p.getUniqueId().equals(m.winner)))
            e.setCancelled(true);
    }

    /** Tôt : amical / pari = keepInventory (avant SmpBoss et les autres) ; à mort = tout tombe. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathEarly(PlayerDeathEvent e) {
        Match m = matches.get(e.getEntity().getUniqueId());
        if (m == null || !m.fighting()) return;
        applyDeathRules(e, m);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Match m = matches.get(p.getUniqueId());
        if (m == null || !m.fighting()) return;
        applyDeathRules(e, m);
        m.win(m.other(p.getUniqueId()), false);
    }

    private static void applyDeathRules(PlayerDeathEvent e, Match m) {
        if (m.mode.keepsInventory()) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        } else {
            if (e.getKeepInventory() && e.getDrops().isEmpty()) {
                // keepInventory activé sur le serveur : on fait tomber le stuff nous-mêmes
                for (ItemStack it : e.getEntity().getInventory().getContents()) if (it != null && !it.getType().isAir()) e.getDrops().add(it.clone());
            }
            e.setKeepInventory(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (matches.containsKey(p.getUniqueId()) && matches.get(p.getUniqueId()).state != Match.State.OVER
                && matches.get(p.getUniqueId()).state != Match.State.LOOT) return;
        Location back = returnLoc(p);
        if (back == null) return;
        e.setRespawnLocation(back);
        p.getPersistentDataContainer().remove(returnKey);
        p.getPersistentDataContainer().remove(duelKey);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && escrow.deliver(p)) send(p, "bet-delivered");
        }, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        leaveQueue(p.getUniqueId());
        challenges.remove(p.getUniqueId());
        BetWindow b = bets.get(p.getUniqueId());
        if (b != null && !b.closed()) b.cancel(p.getUniqueId());
        Match m = matches.get(p.getUniqueId());
        if (m == null) return;
        switch (m.state) {
            case COUNTDOWN -> m.draw(msg("draw-quit").replace("<player>", p.getName()));
            case FIGHT -> {
                if (m.mode == Mode.MORT) {
                    // fuir un duel à mort = mourir (le stuff tombe dans l'arène)
                    p.setHealth(0);
                    if (m.fighting()) m.win(m.other(p.getUniqueId()), true);
                } else m.win(m.other(p.getUniqueId()), true);
            }
            case LOOT -> {
                if (p.getUniqueId().equals(m.winner)) m.finish();
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        rejoin(e.getPlayer());
    }

    /** Compte à rebours : on ne bouge pas (on peut regarder autour). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (m == null || m.state != Match.State.COUNTDOWN) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getX() != t.getX() || f.getZ() != t.getZ() || t.getY() > f.getY()) {
            Location keep = f.clone();
            keep.setYaw(t.getYaw());
            keep.setPitch(t.getPitch());
            e.setTo(keep);
        }
    }

    /** Pas d'élytres en duel ni dans les arènes. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!e.isGliding() || !(e.getEntity() instanceof Player p)) return;
        if (matches.containsKey(p.getUniqueId()) || arenaAt(p.getLocation()) != null) {
            e.setCancelled(true);
            p.sendActionBar(mm(msg("no-elytra")));
        }
    }

    /** En duel : pas de /spawn, /home... (seulement les commandes autorisées). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (m == null || (m.state != Match.State.COUNTDOWN && m.state != Match.State.FIGHT)) return;
        if (e.getPlayer().hasPermission("smpduel.admin")) return;
        String first = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (first.contains(":")) first = first.substring(first.indexOf(':') + 1);
        if (getConfig().getStringList("allowed-commands").contains(first)) return;
        e.setCancelled(true);
        send(e.getPlayer(), "blocked-command");
    }

    /** En duel : on ne sort pas de l'arène (perle hors du cercle, téléportation d'un autre plugin...). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (bypassTp.contains(p.getUniqueId())) return;
        Match m = matches.get(p.getUniqueId());
        if (m == null || (m.state != Match.State.COUNTDOWN && m.state != Match.State.FIGHT)) return;
        if (e.getTo() != null && m.arena.inside(e.getTo())) return;
        e.setCancelled(true);
    }

    // ================================================================= protection des arènes

    private boolean admin(Player p) {
        return p.hasPermission("smpduel.admin") && p.getGameMode() == GameMode.CREATIVE;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        DuelArena a = arenaAt(e.getBlock().getLocation());
        if (a == null || admin(e.getPlayer())) return;
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (a.structure(e.getBlock()) || m == null || m.arena != a) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        DuelArena a = arenaAt(e.getBlock().getLocation());
        if (a == null || admin(e.getPlayer())) return;
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (a.structure(e.getBlock()) || m == null || m.arena != a || !m.fighting()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Block b = e.getBlockClicked().getRelative(e.getBlockFace());
        DuelArena a = arenaAt(b.getLocation());
        if (a == null || admin(e.getPlayer())) return;
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (a.structure(b) || m == null || m.arena != a || !m.fighting()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (arenas.isEmpty()) return;
        e.blockList().removeIf(b -> {
            DuelArena a = arenaAt(b.getLocation());
            return a != null && a.structure(b);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> {
            DuelArena a = arenaAt(b.getLocation());
            return a != null && a.structure(b);
        });
    }
}
