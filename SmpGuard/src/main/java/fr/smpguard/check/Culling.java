package fr.smpguard.check;

import fr.smpguard.Lag;
import fr.smpguard.Profile;
import fr.smpguard.Settings;
import fr.smpguard.Sight;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Protection anti-ESP : un joueur qui n'est pas visible (derrière la roche) n'est pas envoyé au client.
 * L'ESP et le freecam n'ont alors rien à afficher.
 *
 * Pour ne pas gêner les joueurs, même ceux qui laguent :
 * - on teste plusieurs points du corps et on anticipe les mouvements (vitesse x ping) des deux joueurs ;
 * - un joueur réapparaît dès qu'un seul rayon passe, mais n'est caché qu'après 2 tests négatifs d'affilée ;
 * - jamais caché à moins de 6 blocs, ni quand on vient de se faire frapper par lui.
 * Le nombre de rayons par tick est limité (anti-esp.budget-per-tick).
 */
public final class Culling {

    private final SmpGuard plugin;
    /** observateur -> joueurs cachés pour lui */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    /** observateur -> (cible -> tests négatifs d'affilée) */
    private final Map<UUID, Map<UUID, Integer>> misses = new HashMap<>();
    private final Deque<UUID> queue = new ArrayDeque<>();

    public Culling(SmpGuard plugin) {
        this.plugin = plugin;
    }

    public boolean isHidden(Player observer, Player target) {
        Set<UUID> s = hidden.get(observer.getUniqueId());
        return s != null && s.contains(target.getUniqueId());
    }

    public void tick() {
        Settings s = plugin.settings();
        if (!s.hideBehindWalls) {
            if (!hidden.isEmpty()) showAll();
            return;
        }
        if (queue.isEmpty()) for (Player p : Bukkit.getOnlinePlayers()) queue.add(p.getUniqueId());
        Sight.takeRayCount();
        int processed = 0;
        int max = queue.size();
        while (!queue.isEmpty() && processed < max && Sight.peekRayCount() < s.raysPerTick) {
            UUID id = queue.poll();
            processed++;
            Player o = Bukkit.getPlayer(id);
            if (o != null) update(o, s);
        }
    }

    private void update(Player o, Settings s) {
        boolean exempt = o.hasPermission("smpguard.see-all") || o.getGameMode() == GameMode.SPECTATOR
                || o.getGameMode() == GameMode.CREATIVE;
        Profile op = plugin.profile(o);
        World w = o.getWorld();
        Vector eye = o.getEyeLocation().toVector();
        int lead = op == null ? 3 : Lag.delayTicks(op);
        Vector oVel = o.getVelocity().clone().multiply(lead);
        Vector[] eyes = {eye, eye.clone().add(oVel), eye.clone().add(new Vector(0, 0.4, 0))};
        Map<UUID, Integer> miss = misses.computeIfAbsent(o.getUniqueId(), k -> new HashMap<>());
        for (Player t : w.getPlayers()) {
            if (t == o) continue;
            if (exempt) {
                show(o, t);
                continue;
            }
            double dist = t.getLocation().distance(o.getLocation());
            if (dist < 6 || dist > s.hideDistance) {
                show(o, t);
                miss.remove(t.getUniqueId());
                continue;
            }
            Vector feet = t.getLocation().toVector();
            Vector ahead = feet.clone().add(t.getVelocity().clone().multiply(lead + 2));
            Vector[] pts = concat(Sight.body(feet, t.getHeight()), Sight.body(ahead, t.getHeight()));
            if (Sight.anyClear(w, eyes, pts)) {
                show(o, t);
                miss.remove(t.getUniqueId());
            } else {
                int n = miss.merge(t.getUniqueId(), 1, Integer::sum);
                if (n >= 2) hide(o, t);
            }
        }
        queue.add(o.getUniqueId());
    }

    private static Vector[] concat(Vector[] a, Vector[] b) {
        Vector[] r = new Vector[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private void hide(Player o, Player t) {
        if (hidden.computeIfAbsent(o.getUniqueId(), k -> new HashSet<>()).add(t.getUniqueId())) {
            o.hidePlayer(plugin, t);
        }
    }

    private void show(Player o, Player t) {
        Set<UUID> s = hidden.get(o.getUniqueId());
        if (s != null && s.remove(t.getUniqueId())) o.showPlayer(plugin, t);
    }

    /** Un joueur frappe ou tire sur un autre : ils se voient immédiatement. */
    public void reveal(Player a, Player b) {
        show(a, b);
        show(b, a);
        Map<UUID, Integer> m = misses.get(a.getUniqueId());
        if (m != null) m.remove(b.getUniqueId());
        m = misses.get(b.getUniqueId());
        if (m != null) m.remove(a.getUniqueId());
    }

    public void forget(Player p) {
        Set<UUID> s = hidden.remove(p.getUniqueId());
        if (s != null) for (UUID id : s) {
            Player t = Bukkit.getPlayer(id);
            if (t != null) p.showPlayer(plugin, t);
        }
        misses.remove(p.getUniqueId());
        for (Map.Entry<UUID, Set<UUID>> e : hidden.entrySet()) {
            if (e.getValue().remove(p.getUniqueId())) {
                Player o = Bukkit.getPlayer(e.getKey());
                if (o != null) o.showPlayer(plugin, p);
            }
        }
        queue.remove(p.getUniqueId());
    }

    public void showAll() {
        for (Map.Entry<UUID, Set<UUID>> e : hidden.entrySet()) {
            Player o = Bukkit.getPlayer(e.getKey());
            if (o == null) continue;
            for (UUID id : new ArrayList<>(e.getValue())) {
                Player t = Bukkit.getPlayer(id);
                if (t != null) o.showPlayer(plugin, t);
            }
        }
        hidden.clear();
        misses.clear();
    }

    public int hiddenPairs() {
        int n = 0;
        for (Set<UUID> s : hidden.values()) n += s.size();
        return n;
    }
}
