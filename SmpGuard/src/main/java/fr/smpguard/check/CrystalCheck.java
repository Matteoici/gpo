package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.Settings;
import fr.smpguard.Sight;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Crystal aura : poser et casser des cristaux de l'End plus vite et plus précisément qu'un humain.
 *
 * 1. Réaction : un cristal n'apparaît chez le client qu'après la moitié du ping, et le coup revient après l'autre moitié.
 *    Le délai serveur entre l'apparition et le coup = ping + réaction. On retire le ping (en supposant le ping
 *    le plus bas possible) : une aura casse presque TOUS ses cristaux en moins de 40 ms de réaction, un très bon
 *    joueur qui spam-clique en casse parfois un vite, mais jamais 75 % d'affilée.
 * 2. Cadence : plus de 12 cristaux cassés par seconde pendant 3 secondes de suite (un excellent joueur
 *    tourne autour de 6 à 9).
 * 3. Visée : cristaux cassés ou posés très loin du centre de l'écran, de façon répétée.
 *
 * Blocage : casser un cristal dans le même tick que son apparition est impossible (le client ne l'a pas encore
 * reçu ; c'est la technique de "prédiction d'identifiant" des auras) ; ce coup est annulé.
 */
public final class CrystalCheck {

    private static final int WINDOW = 12;

    private static final class Data {
        final Deque<Boolean> fast = new ArrayDeque<>();     // derniers cassages : trop rapides ?
        final Deque<Boolean> offAim = new ArrayDeque<>();   // dernières actions : hors du champ de vision ?
        final Deque<Integer> breaks = new ArrayDeque<>();   // ticks des cassages (cadence)
        int overSeconds;
    }

    private final SmpGuard plugin;
    private final Map<UUID, Integer> spawned = new HashMap<>();
    private final Map<UUID, Data> data = new HashMap<>();

    public CrystalCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    private Data data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new Data());
    }

    public void forget(Player p) {
        data.remove(p.getUniqueId());
    }

    private static void push(Deque<Boolean> d, boolean v) {
        d.addLast(v);
        while (d.size() > WINDOW) d.removeFirst();
    }

    private static int count(Deque<Boolean> d) {
        int n = 0;
        for (boolean b : d) if (b) n++;
        return n;
    }

    // ================================================================= pose

    public void onPlace(Player p, Entity crystal, Block on) {
        spawned.put(crystal.getUniqueId(), plugin.currentTick());
        if (p == null || on == null || !plugin.analysed(p)) return;
        Settings s = plugin.settings();
        if (!s.limits(Check.CRYSTAL).enabled()) return;
        Vector target = on.getLocation().toVector().add(new Vector(0.5, 1.0, 0.5));
        aim(p, Sight.angle(p.getEyeLocation(), target), target.distance(p.getEyeLocation().toVector()));
    }

    // ================================================================= cassage

    public void onHit(Player p, Entity crystal, Cancellable event) {
        if (!plugin.analysed(p)) return;
        Settings s = plugin.settings();
        if (!s.limits(Check.CRYSTAL).enabled()) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        int now = plugin.currentTick();
        Data d = data(p);
        d.breaks.addLast(now);

        Integer born = spawned.remove(crystal.getUniqueId());
        if (born != null) {
            int delayTicks = now - born;
            if (delayTicks <= 0) {
                if (s.crystalBlockImpossible) event.setCancelled(true);
                plugin.alerts().flag(p, prof, Check.CRYSTAL, 1.0, plugin.texts().raw("detail.crystal-instant"));
                return;
            }
            double weight = plugin.lag().weight(prof);
            if (weight >= 0.3) {
                double lowestPing = Math.max(0, prof.avgPing() - 2 * prof.jitter());
                double reaction = delayTicks * 50 + 50 - lowestPing;
                push(d.fast, reaction < s.crystalMinReactionMs);
                if (d.fast.size() >= WINDOW && count(d.fast) >= Math.ceil(WINDOW * s.crystalInhumanRatio)) {
                    String detail = plugin.texts().raw("detail.crystal-reaction")
                            .replace("<count>", String.valueOf(count(d.fast)))
                            .replace("<total>", String.valueOf(d.fast.size()))
                            .replace("<ping>", String.valueOf(prof.avgPing()));
                    d.fast.clear();
                    plugin.alerts().flag(p, prof, Check.CRYSTAL, 1.0 * weight, detail);
                }
            }
        }
        Vector center = crystal.getLocation().toVector().add(new Vector(0, 1.0, 0));
        aim(p, Sight.angle(p.getEyeLocation(), center), center.distance(p.getEyeLocation().toVector()));
    }

    private void aim(Player p, double angle, double dist) {
        if (dist < 2) return; // tout près, l'angle ne veut plus rien dire
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        Data d = data(p);
        // la rotation connue du serveur a jusqu'à 1 tick de retard : on laisse une large marge
        push(d.offAim, angle > plugin.settings().crystalMaxAngle);
        if (d.offAim.size() >= 10 && count(d.offAim) >= 4) {
            double weight = plugin.lag().weight(prof);
            String detail = plugin.texts().raw("detail.crystal-aim")
                    .replace("<count>", String.valueOf(count(d.offAim)))
                    .replace("<angle>", String.format(Locale.US, "%.0f", angle));
            d.offAim.clear();
            if (weight >= 0.3) plugin.alerts().flag(p, prof, Check.CRYSTAL, 1.0 * weight, detail);
        }
    }

    // ================================================================= chaque seconde : cadence

    public void tickSecond() {
        int now = plugin.currentTick();
        spawned.values().removeIf(t -> now - t > 200);
        Settings s = plugin.settings();
        for (Map.Entry<UUID, Data> e : data.entrySet()) {
            Data d = e.getValue();
            while (!d.breaks.isEmpty() && now - d.breaks.peekFirst() > 20) d.breaks.removeFirst();
            Player p = Bukkit.getPlayer(e.getKey());
            Profile prof = p == null ? null : plugin.profile(p);
            if (prof == null) continue;
            double weight = plugin.lag().weight(prof);
            // après un pic de lag, des paquets en retard arrivent d'un coup : on ne compte pas
            if (d.breaks.size() > s.crystalMaxPerSecond && weight >= 0.5) d.overSeconds++;
            else d.overSeconds = 0;
            if (d.overSeconds >= 3) {
                d.overSeconds = 0;
                String detail = plugin.texts().raw("detail.crystal-rate")
                        .replace("<rate>", String.valueOf(d.breaks.size()));
                plugin.alerts().flag(p, prof, Check.CRYSTAL, 1.0 * weight, detail);
            }
        }
    }
}
