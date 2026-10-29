package fr.smpcombat;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Combat façon Deepwoken : un coup entre joueurs met les deux "EN DANGER".
 * En danger : écran rouge, barre avec chrono et adversaires, pas de téléportation ni de commandes de fuite
 * (boutique, home, spawn, tpa...), et se déconnecter = mourir (kill pour l'adversaire).
 */
public final class SmpCombat extends JavaPlugin implements Listener, TabExecutor {

    static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Random RNG = new Random();

    private static final class Danger {
        long until;
        UUID lastEnemy;
        final Map<UUID, Long> enemies = new LinkedHashMap<>();
        BossBar bar;
        String flavor;
        int flavorTicks;
    }

    private final Map<UUID, Danger> danger = new HashMap<>();
    private final Set<UUID> kicked = new HashSet<>();
    private NamespacedKey allowKey;
    private int tick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", 1) < 2) {
            // en danger : plus d'élytres
            getConfig().set("block-elytra", true);
            getConfig().set("config-version", 2);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 3) {
            // le fruit de chorus ne téléporte plus en danger
            List<String> tp = new ArrayList<>(getConfig().getStringList("allowed-teleports"));
            tp.remove("CHORUS_FRUIT");
            getConfig().set("allowed-teleports", tp);
            getConfig().set("config-version", 3);
            saveConfig();
        }
        allowKey = new NamespacedKey("smpcombat", "allow_tp_until");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("combat");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        var mb = getCommand("mb");
        if (mb != null) {
            mb.setExecutor((s, c, l, a) -> {
                if (s instanceof Player p) myBad(p, a.length == 0 ? "all" : a[0]);
                return true;
            });
            mb.setTabCompleter((s, c, l, a) -> {
                List<String> out = new ArrayList<>();
                if (a.length != 1 || !(s instanceof Player p)) return out;
                out.add("all");
                Danger d = danger.get(p.getUniqueId());
                if (d != null) for (UUID id : d.enemies.keySet()) {
                    Player t = Bukkit.getPlayer(id);
                    if (t != null && ally(p.getUniqueId(), id)) out.add(t.getName());
                }
                out.removeIf(x -> !x.toLowerCase(java.util.Locale.ROOT).startsWith(a[0].toLowerCase(java.util.Locale.ROOT)));
                return out;
            });
        }
        Bukkit.getScheduler().runTaskTimer(this, this::safeTick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(danger.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) clear(p, false);
        }
    }

    Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private String msg(String k) {
        // sans valeur par défaut ici : les nouveaux messages du plugin marchent même avec un ancien config.yml
        String s = getConfig().getString("messages." + k);
        return s == null ? k : s;
    }

    private void send(CommandSender to, String key, String... repl) {
        String s = msg(key);
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace("<" + repl[i] + ">", repl[i + 1]);
        to.sendMessage(mm(msg("prefix") + s));
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }

    // ================================================================= état

    /** Secondes de danger restantes (0 = en sécurité). Utilisable par d'autres plugins. */
    public int left(Player p) {
        Danger d = danger.get(p.getUniqueId());
        if (d == null) return 0;
        long l = d.until - System.currentTimeMillis();
        return l <= 0 ? 0 : (int) ((l + 999) / 1000);
    }

    public boolean inDanger(Player p) {
        return left(p) > 0;
    }

    private void tag(Player p, Player enemy) {
        if (p.hasPermission("smpcombat.bypass") || p.isDead()) return;
        long now = System.currentTimeMillis();
        Danger d = danger.get(p.getUniqueId());
        boolean fresh = d == null;
        if (fresh) {
            d = new Danger();
            danger.put(p.getUniqueId(), d);
        }
        d.until = now + getConfig().getLong("danger-seconds", 20) * 1000;
        // en plein vol quand le combat commence : on retombe
        if (p.isGliding() && getConfig().getBoolean("block-elytra", true)) {
            p.setGliding(false);
            p.sendActionBar(mm(msg("blocked-elytra")));
        }
        if (enemy != null) {
            d.lastEnemy = enemy.getUniqueId();
            d.enemies.put(enemy.getUniqueId(), now);
        }
        if (fresh) {
            send(p, "enter");
            sound(p, "entity.wither.spawn", 0.35f, 1.8f);
            sound(p, "block.bell.resonate", 0.8f, 0.6f);
            if (getConfig().getBoolean("screen-tint", true)) tint(p, true);
            if (getConfig().getBoolean("bossbar", true)) {
                d.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
                p.showBossBar(d.bar);
            }
            newFlavor(d);
        }
    }

    private void newFlavor(Danger d) {
        List<String> lines = getConfig().getStringList("messages.flavor");
        d.flavor = lines.isEmpty() ? null : lines.get(RNG.nextInt(lines.size()));
        d.flavorTicks = 0;
    }

    private void clear(Player p, boolean message) {
        Danger d = danger.remove(p.getUniqueId());
        if (d == null) return;
        if (d.bar != null) p.hideBossBar(d.bar);
        tint(p, false);
        p.sendActionBar(Component.empty());
        if (message) {
            send(p, "leave");
            sound(p, "block.beacon.deactivate", 0.6f, 1.6f);
        }
    }

    /** Bords de l'écran en rouge (bordure du monde virtuelle, rien que pour lui). */
    private void tint(Player p, boolean on) {
        if (!on) {
            p.setWorldBorder(null);
            return;
        }
        WorldBorder b = Bukkit.createWorldBorder();
        b.setCenter(p.getLocation());
        b.setSize(10_000);
        double strength = Math.max(0.05, Math.min(0.95, getConfig().getDouble("tint-strength", 0.55)));
        b.setWarningDistance((int) (5_000 / (1 - strength)));
        p.setWorldBorder(b);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            getLogger().warning("SmpCombat : " + t);
        }
    }

    private void tick() {
        tick++;
        long now = System.currentTimeMillis();
        long secs = getConfig().getLong("danger-seconds", 20) * 1000;
        for (var e : new ArrayList<>(danger.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Danger d = e.getValue();
            if (p == null) {
                danger.remove(e.getKey());
                continue;
            }
            if (now >= d.until) {
                clear(p, true);
                continue;
            }
            d.enemies.values().removeIf(t -> now - t > secs);
            int s = (int) ((d.until - now + 999) / 1000);
            int n = Math.max(1, d.enemies.size());
            if (d.bar != null && tick % 2 == 0) {
                d.bar.progress(Math.max(0f, Math.min(1f, (float) (d.until - now) / secs)));
                d.bar.name(mm(msg("bossbar").replace("<s>", String.valueOf(s)).replace("<n>", String.valueOf(n)) + track(p, d)));
            }
            if (getConfig().getBoolean("flavor", true) && d.flavor != null && tick % 2 == 0) {
                if (++d.flavorTicks > 80) newFlavor(d);
                if (d.flavor != null) p.sendActionBar(mm(d.flavor));
            }
            if (getConfig().getBoolean("heartbeat", true) && tick % 20 == 0) {
                var max = p.getAttribute(Attribute.MAX_HEALTH);
                double ratio = p.getHealth() / (max == null ? 20 : max.getValue());
                if (ratio < 0.4) sound(p, "entity.warden.heartbeat", 1f, ratio < 0.2 ? 1.4f : 1f);
            }
        }
    }

    // ================================================================= flèche vers l'adversaire

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /** "| ↗ ~30 m" : à peu près où est l'adversaire (le dernier qui t'a tapé / que tu as tapé). */
    private String track(Player p, Danger d) {
        if (!getConfig().getBoolean("track-enemy", true) || d.lastEnemy == null) return "";
        Player e = Bukkit.getPlayer(d.lastEnemy);
        if (e == null || e.getWorld() != p.getWorld() || e.isDead()) return "";
        double dist = p.getLocation().distance(e.getLocation());
        if (dist < getConfig().getDouble("track-min-distance", 8)) return "";
        double dx = e.getLocation().getX() - p.getLocation().getX(), dz = e.getLocation().getZ() - p.getLocation().getZ();
        // angle de l'ennemi par rapport à là où on regarde (0 = devant)
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - p.getLocation().getYaw()) % 360 + 360 + 22.5) % 360;
        String arrow = ARROWS[(int) (rel / 45) % 8];
        long approx = dist < 50 ? Math.round(dist / 5) * 5 : Math.round(dist / 10) * 10;
        return " " + getConfig().getString("messages.track", "<dark_gray>|</dark_gray> <yellow><b><arrow></b> <white>~<dist> m")
                .replace("<arrow>", arrow).replace("<dist>", String.valueOf(approx));
    }

    // ================================================================= anti-fuite : perles, vent, trident, chorus

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p) || !inDanger(p)) return;
        Material m;
        int secs;
        if (e.getEntity() instanceof org.bukkit.entity.EnderPearl) {
            m = Material.ENDER_PEARL;
            secs = getConfig().getInt("pearl-cooldown-seconds", 15);
        } else if (e.getEntity() instanceof org.bukkit.entity.WindCharge) {
            m = Material.WIND_CHARGE;
            secs = getConfig().getInt("wind-charge-cooldown-seconds", 5);
        } else return;
        if (secs <= 0) return;
        // la recharge vanilla est posée juste après : on met la nôtre au tick suivant
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) p.setCooldown(m, secs * 20);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRiptide(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var it = e.getItem();
        if (it == null || it.getType() != Material.TRIDENT || !it.containsEnchantment(org.bukkit.enchantments.Enchantment.RIPTIDE)) return;
        if (!getConfig().getBoolean("block-riptide", true) || !inDanger(e.getPlayer())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(mm(msg("blocked-riptide")));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChorus(org.bukkit.event.player.PlayerItemConsumeEvent e) {
        if (e.getItem().getType() != Material.CHORUS_FRUIT || !getConfig().getBoolean("block-chorus", true) || !inDanger(e.getPlayer())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(mm(msg("blocked-chorus")));
    }

    // ================================================================= ce qui met en danger

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof TNTPrimed t && t.getSource() instanceof Player p) attacker = p;
        if (e.getEntity() instanceof Player victim) {
            if (attacker != null && attacker != victim) {
                // duel (SmpDuel) : les deux adversaires ne passent pas "en danger"
                NamespacedKey duel = new NamespacedKey("smpduel", "duel");
                if (victim.getPersistentDataContainer().has(duel, PersistentDataType.STRING)
                        && attacker.getPersistentDataContainer().has(duel, PersistentDataType.STRING)) return;
                tag(victim, attacker);
                tag(attacker, victim);
            } else if (attacker == null && e.getDamager() instanceof Monster && getConfig().getBoolean("mobs-trigger", false)) {
                tag(victim, null);
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        clear(e.getEntity(), false);
    }

    // ================================================================= se déconnecter en danger = mourir

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKick(PlayerKickEvent e) {
        kicked.add(e.getPlayer().getUniqueId()); // expulsé par le staff / le serveur : pas une fuite
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        boolean wasKicked = kicked.remove(p.getUniqueId());
        Danger d = danger.get(p.getUniqueId());
        if (d == null || System.currentTimeMillis() >= d.until || wasKicked || Bukkit.isStopping()) {
            clear(p, false);
            return;
        }
        if (getConfig().getBoolean("combat-log.kill", true)) {
            Player enemy = d.lastEnemy == null ? null : Bukkit.getPlayer(d.lastEnemy);
            if (enemy != null) p.setKiller(enemy); // le kill (et les primes / têtes) pour l'adversaire
            clear(p, false);
            p.setHealth(0);
        } else {
            clear(p, false);
        }
        if (getConfig().getBoolean("combat-log.broadcast", true)) {
            Bukkit.broadcast(mm(msg("prefix") + msg("logged").replace("<player>", MM.escapeTags(p.getName()))));
        }
    }

    // ================================================================= ce qui est interdit en danger

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        int s = left(p);
        if (s <= 0) return;
        String line = e.getMessage().substring(1).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        String first = line.split(" ")[0];
        if (first.contains(":")) line = line.substring(first.indexOf(':') + 1);
        for (String b : getConfig().getStringList("blocked-commands")) {
            String blocked = b.toLowerCase(Locale.ROOT).trim();
            if (line.equals(blocked) || line.startsWith(blocked + " ")) {
                e.setCancelled(true);
                send(p, "blocked-command", "cmd", blocked, "s", String.valueOf(s));
                sound(p, "block.note_block.bass", 1f, 0.6f);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        int s = left(p);
        if (s <= 0) return;
        if (getConfig().getStringList("allowed-teleports").contains(e.getCause().name())) return;
        Long allow = p.getPersistentDataContainer().get(allowKey, PersistentDataType.LONG);
        if (allow != null && allow > System.currentTimeMillis()) return; // prison, staff...
        if (e.getTo() != null && e.getFrom().getWorld() == e.getTo().getWorld()
                && e.getFrom().distance(e.getTo()) <= getConfig().getDouble("max-plugin-teleport", 12)
                && e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return; // dash, petites corrections
        e.setCancelled(true);
        send(p, "blocked-teleport", "s", String.valueOf(s));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChest(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() != Material.ENDER_CHEST || !getConfig().getBoolean("block-ender-chest", true)) return;
        if (!inDanger(e.getPlayer())) return;
        e.setCancelled(true);
        send(e.getPlayer(), "blocked-echest");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!e.isGliding() || !(e.getEntity() instanceof Player p) || !getConfig().getBoolean("block-elytra", true)) return;
        if (!inDanger(p)) return;
        e.setCancelled(true);
        p.sendActionBar(mm(msg("blocked-elytra")));
    }

    // ================================================================= /mb all : "my bad", on a tapé un allié

    /**
     * Enlève le combat causé par des coups entre alliés (amis, même guilde, guildes alliées).
     * Le combat contre les autres joueurs reste : impossible de s'en servir pour fuir un vrai combat.
     */
    /** demandes /mb envoyées à un adversaire (pas allié) : joueur -> (adversaire -> heure) */
    private final Map<UUID, Map<UUID, Long>> mbAsked = new HashMap<>();

    private boolean asked(UUID from, UUID to) {
        Map<UUID, Long> m = mbAsked.get(from);
        Long t = m == null ? null : m.get(to);
        return t != null && System.currentTimeMillis() - t <= getConfig().getLong("mb.delai-secondes", 30) * 1000;
    }

    /** Enlève le combat entre p et l'autre (des deux côtés). */
    private void forgive(Player p, UUID id, Danger d) {
        d.enemies.remove(id);
        Player t = Bukkit.getPlayer(id);
        Danger od = danger.get(id);
        if (od != null) {
            od.enemies.remove(p.getUniqueId());
            if (p.getUniqueId().equals(od.lastEnemy)) od.lastEnemy = od.enemies.isEmpty() ? null : od.enemies.keySet().iterator().next();
            if (od.enemies.isEmpty() && t != null) {
                clear(t, false);
                send(t, "mb-cleared-other", "player", p.getName());
                sound(t, "block.beacon.deactivate", 0.6f, 1.6f);
            }
        }
        Map<UUID, Long> m = mbAsked.get(p.getUniqueId());
        if (m != null) m.remove(id);
        m = mbAsked.get(id);
        if (m != null) m.remove(p.getUniqueId());
    }

    private void myBad(Player p, String who) {
        Danger d = danger.get(p.getUniqueId());
        if (d == null || !inDanger(p)) {
            send(p, "mb-not-in-combat");
            return;
        }
        List<UUID> targets = new ArrayList<>();
        if (who.equalsIgnoreCase("all") || who.equalsIgnoreCase("tous")) targets.addAll(d.enemies.keySet());
        else {
            Player t = Bukkit.getPlayerExact(who);
            if (t == null || !d.enemies.containsKey(t.getUniqueId())) {
                send(p, "mb-not-enemy", "player", who);
                return;
            }
            targets.add(t.getUniqueId());
        }
        List<String> forgiven = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        boolean mutual = getConfig().getBoolean("mb.ennemis-si-les-deux", true);
        for (UUID id : targets) {
            Player t = Bukkit.getPlayer(id);
            String name = t == null ? "?" : t.getName();
            if (ally(p.getUniqueId(), id)) {
                forgive(p, id, d);
                forgiven.add(name);
                continue;
            }
            if (!mutual || t == null) continue;
            // adversaire : il faut que les deux fassent /mb
            if (asked(id, p.getUniqueId())) {
                forgive(p, id, d);
                forgiven.add(name);
            } else {
                mbAsked.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(id, System.currentTimeMillis());
                waiting.add(name);
                send(t, "mb-request", "player", p.getName(), "s", String.valueOf(getConfig().getLong("mb.delai-secondes", 30)));
                sound(t, "block.note_block.chime", 0.8f, 1.2f);
            }
        }
        if (!waiting.isEmpty()) send(p, "mb-waiting", "players", String.join(", ", waiting), "s", String.valueOf(getConfig().getLong("mb.delai-secondes", 30)));
        if (d.lastEnemy != null && !d.enemies.containsKey(d.lastEnemy)) d.lastEnemy = d.enemies.isEmpty() ? null : d.enemies.keySet().iterator().next();
        if (forgiven.isEmpty()) {
            if (waiting.isEmpty()) send(p, mutual ? "mb-nothing" : "mb-no-ally");
            return;
        }
        if (d.enemies.isEmpty()) {
            clear(p, false);
            send(p, "mb-cleared", "players", String.join(", ", forgiven));
            sound(p, "block.beacon.deactivate", 0.6f, 1.6f);
        } else {
            send(p, "mb-partial", "players", String.join(", ", forgiven), "n", String.valueOf(d.enemies.size()));
        }
    }

    /** Amis (SmpFriends), même guilde ou guildes alliées (SmpGuilds). */
    private boolean ally(UUID a, UUID b) {
        if (getConfig().getBoolean("mb.amis", true)) {
            try {
                var pl = Bukkit.getPluginManager().getPlugin("SmpFriends");
                if (pl != null && pl.isEnabled()) {
                    Object f = pl.getClass().getMethod("friends").invoke(pl);
                    if (Boolean.TRUE.equals(f.getClass().getMethod("areFriends", UUID.class, UUID.class).invoke(f, a, b))) return true;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
        try {
            var pl = Bukkit.getPluginManager().getPlugin("SmpGuilds");
            if (pl == null || !pl.isEnabled()) return false;
            Object gm = pl.getClass().getMethod("guilds").invoke(pl);
            var of = gm.getClass().getMethod("of", UUID.class);
            Object ga = of.invoke(gm, a), gb = of.invoke(gm, b);
            if (ga == null || gb == null) return false;
            Object ida = ga.getClass().getField("id").get(ga), idb = gb.getClass().getField("id").get(gb);
            if (getConfig().getBoolean("mb.guilde", true) && ida.equals(idb)) return true;
            if (getConfig().getBoolean("mb.allies", true) && ga.getClass().getField("allies").get(ga) instanceof java.util.Collection<?> al && al.contains(idb)) return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
        return false;
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("reload") && s.hasPermission("smpcombat.admin")) {
            reloadConfig();
            s.sendMessage(mm(msg("prefix") + "<green>Config rechargée."));
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("clear") && s.hasPermission("smpcombat.admin")) {
            Player t = Bukkit.getPlayer(a[1]);
            if (t != null) {
                clear(t, true);
                send(s, "cleared", "player", t.getName());
            }
            return true;
        }
        if (!(s instanceof Player p)) return true;
        int left = left(p);
        Danger d = danger.get(p.getUniqueId());
        if (left <= 0) send(p, "status-safe");
        else send(p, "status-danger", "s", String.valueOf(left), "n", String.valueOf(d == null ? 0 : Math.max(1, d.enemies.size())));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("smpcombat.admin")) return List.of();
        if (a.length == 1) return List.of("clear", "reload");
        if (a.length == 2 && a[0].equalsIgnoreCase("clear")) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (inDanger(p)) out.add(p.getName());
            return out;
        }
        return List.of();
    }
}
