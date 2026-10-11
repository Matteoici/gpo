package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.Settings;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Freecam / blink : le client dit au serveur que les touches de déplacement sont enfoncées
 * (Minecraft envoie l'état des touches), mais le corps du joueur ne bouge pas d'un pouce.
 *
 * Cas normaux écartés : mur ou obstacle devant, accroupi au bord d'un bloc, véhicule, vol,
 * planeur, échelle (on compte aussi le mouvement vertical), joueur gelé par le staff,
 * et toute période où la connexion ou le serveur ne permet pas de conclure (Lag.weight).
 *
 * Limite honnête : un freecam qui bloque aussi l'envoi des touches est invisible pour le serveur.
 * La protection "anti-esp" (joueurs cachés derrière les murs) le rend alors inutile contre les joueurs.
 */
public final class FreecamCheck {

    public static final int PERIOD = 10;

    private final SmpGuard plugin;

    public FreecamCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    public void run() {
        Settings s = plugin.settings();
        if (!s.limits(Check.FREECAM).enabled()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Profile prof = plugin.profile(p);
            if (prof == null) continue;
            if (!plugin.analysed(p) || p.isInsideVehicle() || p.isFlying() || p.isGliding() || p.isSleeping()
                    || p.isDead() || p.isSneaking() || p.isSwimming() || plugin.frozen(p)) {
                prof.inputStillSeconds = 0;
                continue;
            }
            double weight = plugin.lag().weight(prof);
            Input in = p.getCurrentInput();
            boolean pressing = in != null && (in.isForward() || in.isBackward() || in.isLeft() || in.isRight());
            if (!pressing || weight < 0.3 || prof.historySize() < PERIOD + 1) {
                prof.inputStillSeconds = 0;
                continue;
            }
            Vector now = prof.at(0), before = prof.at(PERIOD);
            if (now == null || before == null || now.distance(before) > 0.03) {
                prof.inputStillSeconds = 0;
                continue;
            }
            if (blocked(p, in)) {
                prof.inputStillSeconds = 0;
                continue;
            }
            prof.inputStillSeconds += PERIOD / 20.0;
            double needed = s.freecamMinSeconds + prof.avgPing() / 1000.0 * 4 + prof.jitter() / 1000.0 * 6;
            if (prof.inputStillSeconds >= needed) {
                String detail = plugin.texts().raw("detail.freecam")
                        .replace("<seconds>", String.format(Locale.US, "%.1f", prof.inputStillSeconds));
                prof.inputStillSeconds = 0;
                plugin.alerts().flag(p, prof, Check.FREECAM, weight, detail);
            }
        }
    }

    /** Y a-t-il un obstacle dans la direction où le joueur essaie d'aller ? */
    private static boolean blocked(Player p, Input in) {
        Location l = p.getLocation();
        double yaw = Math.toRadians(l.getYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vector dir = new Vector();
        if (in.isForward()) dir.add(forward);
        if (in.isBackward()) dir.subtract(forward);
        if (in.isRight()) dir.add(right);
        if (in.isLeft()) dir.subtract(right);
        if (dir.lengthSquared() < 1e-6) return true; // avant + arrière en même temps : il ne doit pas bouger
        dir.normalize();
        double[] heights = {0.1, 1.0, Math.max(1.1, p.getHeight() - 0.1)};
        double[] offsets = {0.45, 0.8};
        for (double h : heights) {
            for (double d : offsets) {
                Block b = l.clone().add(dir.clone().multiply(d)).add(0, h, 0).getBlock();
                if (b.isCollidable() || b.isLiquid()) return true;
            }
        }
        // bord d'un vide avec un bloc en dessous qui retient (ex. plaque de pression, neige...) : on reste prudent
        Block below = l.clone().add(dir.clone().multiply(0.6)).add(0, -0.5, 0).getBlock();
        return below.getType().isAir() && p.isSneaking();
    }
}
