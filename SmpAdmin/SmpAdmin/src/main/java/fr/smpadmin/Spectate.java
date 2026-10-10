package fr.smpadmin;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.smpadmin.Texts.pc;
import static fr.smpadmin.Texts.ph;

/**
 * /spec : mode spectateur à la première personne.
 * Sans joueur précisé, fait le tour de tous les joueurs connectés, un par un.
 */
public final class Spectate {

    private static final class Session {
        Location back;
        GameMode mode;
        boolean auto;
        boolean vanishedBefore;
        UUID target;
        int index;
        long nextSwitch;
    }

    private final SmpAdmin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    Spectate(SmpAdmin plugin) {
        this.plugin = plugin;
    }

    public boolean isWatching(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public boolean isAuto(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s != null && s.auto;
    }

    /** Joueurs que l'on peut observer. */
    private List<Player> targets(Player staff) {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == staff || sessions.containsKey(p.getUniqueId())) continue;
            if (plugin.vanish().is(p) || p.hasPermission("smpadmin.spec.exempt")) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            out.add(p);
        }
        out.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private Session open(Player staff) {
        Session s = sessions.get(staff.getUniqueId());
        if (s != null) return s;
        s = new Session();
        s.back = staff.getLocation().clone();
        s.mode = staff.getGameMode();
        s.vanishedBefore = plugin.vanish().is(staff);
        sessions.put(staff.getUniqueId(), s);
        if (plugin.settings().specAutoVanish && !s.vanishedBefore) plugin.vanish().set(staff, true, false);
        staff.setGameMode(GameMode.SPECTATOR);
        return s;
    }

    /** Tour automatique de tous les joueurs. */
    public void startAuto(Player staff) {
        List<Player> list = targets(staff);
        if (list.isEmpty()) {
            plugin.texts().send(staff, "spec.none");
            return;
        }
        Session s = open(staff);
        s.auto = true;
        s.index = -1;
        plugin.texts().send(staff, "spec.start-auto", ph("count", list.size()), ph("seconds", plugin.settings().specInterval));
        step(staff, s, 1);
    }

    /** Observer un joueur précis. */
    public void watch(Player staff, Player target) {
        if (target == staff) {
            plugin.texts().send(staff, "error.self");
            return;
        }
        Session s = open(staff);
        s.auto = false;
        plugin.texts().send(staff, "spec.start", ph("player", target.getName()));
        attach(staff, s, target);
    }

    public void next(Player staff, int dir) {
        Session s = sessions.get(staff.getUniqueId());
        if (s == null) {
            startAuto(staff);
            return;
        }
        step(staff, s, dir);
    }

    private void step(Player staff, Session s, int dir) {
        List<Player> list = targets(staff);
        if (list.isEmpty()) {
            plugin.texts().send(staff, "spec.none");
            stop(staff);
            return;
        }
        // reprend à partir du joueur actuel s'il est encore dans la liste
        int cur = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).getUniqueId().equals(s.target)) cur = i;
        int idx = cur < 0 ? (dir > 0 ? Math.max(0, s.index) : list.size() - 1) : cur + dir;
        idx = ((idx % list.size()) + list.size()) % list.size();
        s.index = idx;
        attach(staff, s, list.get(idx));
    }

    private void attach(Player staff, Session s, Player target) {
        s.target = target.getUniqueId();
        s.nextSwitch = System.currentTimeMillis() + plugin.settings().specInterval * 1000L;
        staff.setSpectatorTarget(null);
        staff.teleport(target.getLocation());
        staff.showTitle(Title.title(net.kyori.adventure.text.Component.empty(),
                plugin.texts().get("spec.title", ph("player", target.getName())),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(300))));
        // on attend que le client ait chargé la zone avant de "rentrer" dans le joueur
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Session now = sessions.get(staff.getUniqueId());
            if (now != s || !staff.isOnline() || !target.isOnline() || !target.getUniqueId().equals(s.target)) return;
            if (staff.getGameMode() != GameMode.SPECTATOR) staff.setGameMode(GameMode.SPECTATOR);
            staff.setSpectatorTarget(target);
        }, 4L);
    }

    public void stop(Player staff) {
        Session s = sessions.remove(staff.getUniqueId());
        if (s == null) return;
        staff.setSpectatorTarget(null);
        staff.teleport(s.back);
        staff.setGameMode(s.mode);
        if (plugin.settings().specAutoVanish && !s.vanishedBefore && plugin.vanish().is(staff)) {
            plugin.vanish().set(staff, false, false);
        }
        plugin.texts().send(staff, "spec.stop");
    }

    public void stopAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) stop(p);
        }
        sessions.clear();
    }

    /** Toutes les 10 ticks : changement de joueur, barre d'informations. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player staff = Bukkit.getPlayer(id);
            Session s = sessions.get(id);
            if (staff == null || s == null) {
                sessions.remove(id);
                continue;
            }
            Player target = s.target == null ? null : Bukkit.getPlayer(s.target);
            if (target == null || !target.isOnline()) {
                if (s.auto) step(staff, s, 1);
                else stop(staff);
                continue;
            }
            if (s.auto && now >= s.nextSwitch) {
                step(staff, s, 1);
                continue;
            }
            // le joueur observé a changé de monde : on le suit
            if (staff.getWorld() != target.getWorld()) attach(staff, s, target);
            info(staff, s, target, now);
        }
    }

    private void info(Player staff, Session s, Player target, long now) {
        int count = targets(staff).size();
        net.kyori.adventure.text.Component nextC = s.auto
                ? plugin.texts().get("spec.next-in", ph("seconds", Math.max(0, (s.nextSwitch - now + 999) / 1000)))
                : net.kyori.adventure.text.Component.empty();
        double max = 20;
        var attr = target.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) max = attr.getValue();
        staff.sendActionBar(plugin.texts().get("spec.actionbar", ph("player", target.getName()),
                ph("health", (int) Math.ceil(target.getHealth()) + "/" + (int) max), ph("ping", target.getPing()),
                ph("world", target.getWorld().getName()), ph("index", s.auto ? s.index + 1 : 1),
                ph("count", s.auto ? count : 1), pc("next", nextC)));
    }

    public void onQuit(Player p) {
        if (sessions.containsKey(p.getUniqueId())) stop(p);
    }
}
