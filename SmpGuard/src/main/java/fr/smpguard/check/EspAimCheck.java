package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Lag;
import fr.smpguard.Profile;
import fr.smpguard.Settings;
import fr.smpguard.Sight;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * ESP : le joueur SUIT avec sa visée un autre joueur caché derrière des murs.
 *
 * Un joueur normal peut regarder par hasard dans la direction d'un joueur caché, mais il ne peut pas
 * le suivre précisément pendant qu'il se déplace derrière la roche. On compte donc des "poursuites" :
 * plusieurs mesures d'affilée où la visée reste sur la cible (à quelques degrés près), cible cachée et en mouvement,
 * et cachée aussi avant le début (sinon le joueur l'avait vue passer et anticipe : c'est normal).
 *
 * Lag : la position comparée est celle que le client affichait (ping + interpolation),
 * la tolérance d'angle augmente avec la vitesse de la cible et l'instabilité du ping,
 * et les points sont multipliés par la confiance (0 à 1) de Lag.weight().
 */
public final class EspAimCheck {

    private static final double CANDIDATE_ANGLE = 12;
    private static final int PERIOD = 5; // ticks entre deux mesures

    private final SmpGuard plugin;

    public EspAimCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    public static int period() {
        return PERIOD;
    }

    public void run() {
        Settings s = plugin.settings();
        if (!s.limits(Check.ESP_AIM).enabled()) return;
        long now = System.currentTimeMillis();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!plugin.analysed(o)) continue;
            Profile op = plugin.profile(o);
            if (op == null) continue;
            double weight = plugin.lag().weight(op);
            if (weight < 0.3) {
                op.tracks.clear();
                continue;
            }
            Location eye = o.getEyeLocation();
            World w = o.getWorld();
            int delay = Lag.delayTicks(op);
            double jitterTicks = op.jitter() / 50.0;
            for (Player t : w.getPlayers()) {
                if (t == o || t.getGameMode() == GameMode.SPECTATOR || t.isGlowing() || plugin.hidden(t)) continue;
                Profile tp = plugin.profile(t);
                if (tp == null || tp.historySize() < 10) continue;
                Vector feet = tp.at(delay);
                if (feet == null) continue;
                double h = t.getHeight();
                Vector chest = feet.clone().add(new Vector(0, h * 0.6, 0));
                double dist = chest.distance(eye.toVector());
                Profile.Track tr = op.tracks.get(t.getUniqueId());
                if (dist < s.aimMinDist || dist > s.aimMaxDist) {
                    op.tracks.remove(t.getUniqueId());
                    continue;
                }
                double angle = Sight.angle(eye, chest);
                if (angle > CANDIDATE_ANGLE) {
                    if (tr != null) op.tracks.remove(t.getUniqueId());
                    continue;
                }
                double speed = tp.moved(5) / 5.0; // blocs par tick
                double tol = s.aimAngle
                        + Math.toDegrees(Math.atan(0.35 / dist))
                        + Math.toDegrees(Math.atan(speed * (jitterTicks + 2) / dist));
                if (angle > tol) {
                    if (tr != null) op.tracks.remove(t.getUniqueId());
                    continue;
                }
                // la visée est sur la cible : est-elle visible ?
                if (Sight.anyClear(w, new Vector[]{eye.toVector()}, Sight.body(feet, h))) {
                    op.tracks.remove(t.getUniqueId());
                    continue;
                }
                if (tr == null || now - tr.lastSample > PERIOD * 50L * 3) {
                    tr = new Profile.Track();
                    tr.sx = feet.getX();
                    tr.sz = feet.getZ();
                    tr.startYaw = yawTo(eye, chest);
                    op.tracks.put(t.getUniqueId(), tr);
                }
                tr.samples++;
                tr.lastSample = now;
                if (tr.samples < s.aimTrackSamples) continue;

                double moved = Math.hypot(feet.getX() - tr.sx, feet.getZ() - tr.sz);
                int trackTicks = tr.samples * PERIOD;
                op.tracks.remove(t.getUniqueId());
                if (moved < s.aimMinMove) continue;
                // la visée a dû tourner pour suivre la cible (et pas une cible qui passe dans l'axe d'un joueur immobile)
                double sweep = Math.abs(((yawTo(eye, chest) - tr.startYaw) % 360 + 540) % 360 - 180);
                if (sweep < 4) continue;
                // alliés (même guilde SmpGuilds) : ils savent souvent où sont leurs coéquipiers, on ne compte pas
                if (plugin.sameGuild(o, t)) continue;
                if (wasVisibleBefore(w, op, tp, trackTicks, delay, h)) continue;

                String detail = plugin.texts().raw("detail.esp-aim")
                        .replace("<target>", t.getName())
                        .replace("<seconds>", String.format(Locale.US, "%.1f", trackTicks / 20.0))
                        .replace("<angle>", String.format(Locale.US, "%.1f", angle))
                        .replace("<distance>", String.valueOf((int) dist));
                plugin.alerts().flag(o, op, Check.ESP_AIM, weight, detail);
            }
        }
    }

    private static double yawTo(Location eye, Vector target) {
        double dx = target.getX() - eye.getX(), dz = target.getZ() - eye.getZ();
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** La cible était-elle visible juste avant la poursuite ? (le joueur l'a alors vue passer : normal) */
    private boolean wasVisibleBefore(World w, Profile op, Profile tp, int trackTicks, int delay, double h) {
        int before = (int) (plugin.settings().aimHiddenBeforeMs / 50);
        for (int k = trackTicks; k <= trackTicks + before; k += 10) {
            Vector eye = op.eyeAt(k);
            Vector feet = tp.at(k + delay);
            if (eye == null || feet == null) return true; // pas assez d'historique : on ne compte pas
            if (Sight.anyClear(w, new Vector[]{eye}, Sight.body(feet, h))) return true;
        }
        return false;
    }
}
