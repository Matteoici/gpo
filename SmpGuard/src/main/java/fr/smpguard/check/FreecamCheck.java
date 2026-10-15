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
 * Cas normaux écartés : mur, bloc, entité ou bordure du monde devant, coincé dans un bloc, accroupi, véhicule, vol,
 * planeur, lenteur extrême, déplacement bloqué par un plugin (gel, cinématique, zone interdite), faux murs SmpGojo, échelle (on compte aussi le mouvement vertical), joueur gelé par le staff,
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
                    || p.isDead() || p.isSneaking() || p.isSwimming() || plugin.frozen(p) || plugin.isBedrock(p)
                    || plugin.scripted(p) || immobile(p)) {
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

    /** Ralenti au point de ne plus bouger (Lenteur VI+, vitesse à 0, toile + lenteur...) par un pouvoir ou un boss. */
    private static boolean immobile(Player p) {
        org.bukkit.potion.PotionEffect slow = p.getPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        if (slow != null && slow.getAmplifier() >= 4) return true;
        var speed = p.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        return (speed != null && speed.getValue() < 0.03) || p.getWalkSpeed() < 0.05;
    }

    /** Blocs solides dont la vraie forme touche cette boîte. */
    private static java.util.List<Block> touching(Player p, org.bukkit.util.BoundingBox bb) {
        java.util.List<Block> out = new java.util.ArrayList<>();
        for (int x = (int) Math.floor(bb.getMinX()); x <= Math.floor(bb.getMaxX()); x++)
            for (int y = (int) Math.floor(bb.getMinY()); y <= Math.floor(bb.getMaxY()); y++)
                for (int z = (int) Math.floor(bb.getMinZ()); z <= Math.floor(bb.getMaxZ()); z++) {
                    Block b = p.getWorld().getBlockAt(x, y, z);
                    if (!b.isCollidable()) continue;
                    for (org.bukkit.util.BoundingBox part : b.getCollisionShape().getBoundingBoxes()) {
                        if (part.clone().shift(x, y, z).overlaps(bb)) {
                            out.add(b);
                            break;
                        }
                    }
                }
        return out;
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
        // coincé dans un bloc (sable tombé dessus, bug de position) : le jeu le bloque
        if (!touching(p, p.getBoundingBox().clone().expand(-0.05)).isEmpty()) return true;
        // bordure du monde juste devant
        Location ahead = l.clone().add(dir.clone().multiply(0.8));
        if (!l.getWorld().getWorldBorder().isInside(ahead)) return true;
        // entité solide ou joueur devant (bateau, shulker, minecart, joueur qui pousse en face, foule de mobs)
        org.bukkit.util.BoundingBox front = p.getBoundingBox().clone().shift(dir.clone().multiply(0.6)).expand(0.1, 0, 0.1);
        for (org.bukkit.entity.Entity e : l.getWorld().getNearbyEntities(front)) {
            if (e == p) continue;
            if (e instanceof org.bukkit.entity.LivingEntity || e instanceof org.bukkit.entity.Boat
                    || e instanceof org.bukkit.entity.Minecart) return true;
        }
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
