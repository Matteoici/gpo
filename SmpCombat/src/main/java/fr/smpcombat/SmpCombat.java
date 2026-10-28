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
        allowKey = new NamespacedKey("smpcombat", "allow_tp_until");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("combat");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
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
        return getConfig().getString("messages." + k, k);
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
                d.bar.name(mm(msg("bossbar").replace("<s>", String.valueOf(s)).replace("<n>", String.valueOf(n))));
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
        if (!e.isGliding() || !(e.getEntity() instanceof Player p) || !getConfig().getBoolean("block-elytra", false)) return;
        if (!inDanger(p)) return;
        e.setCancelled(true);
        p.sendActionBar(mm(msg("blocked-elytra")));
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
