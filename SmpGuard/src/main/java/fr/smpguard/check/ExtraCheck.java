package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Contre les modules des clients de triche type CheatUtils, chacun réglé pour ne signaler que l'impossible :
 * - INVENTORY-MOVE : marche (touches enfoncées) avec un coffre / menu ouvert ; le jeu relâche toutes les touches
 *   à l'ouverture d'un menu, seul un mod "InvMove" envoie des déplacements à ce moment-là.
 * - CRITICALS : coup critique sans avoir réellement sauté (les "packet crits" font croire au serveur à une chute).
 * - SCAFFOLD : bloc posé contre du vide (AirPlace), ou posé en regardant à l'opposé, ou rythme de pose impossible.
 * - FLY (bateau) : bateau qui monte ou flotte en l'air (BoatFly).
 */
public final class ExtraCheck {

    private static final class State {
        int invMoveTicks;
        final Deque<Long> places = new ArrayDeque<>();
        int boatAir, boatRise;
    }

    private final SmpGuard plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public ExtraCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    private State st(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    public void forget(Player p) {
        states.remove(p.getUniqueId());
    }

    // ================================================================= InvMove (toutes les 10 ticks)

    public void tickInvMove() {
        if (!plugin.settings().limits(Check.INVMOVE).enabled()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Profile prof = plugin.profile(p);
            if (prof == null || !plugin.analysed(p) || plugin.scripted(p) || p.isInsideVehicle() || p.isInWater()
                    || p.isGliding() || p.isFlying() || plugin.isBedrock(p)) continue;
            State s = st(p);
            InventoryType top = p.getOpenInventory().getTopInventory().getType();
            boolean menu = top != InventoryType.CRAFTING && top != InventoryType.CREATIVE;
            Input in = p.getCurrentInput();
            boolean keys = in != null && (in.isForward() || in.isBackward() || in.isLeft() || in.isRight() || in.isJump());
            double w = plugin.lag().weight(prof);
            if (menu && keys && w >= 0.6 && prof.moved(10) > 0.6) {
                s.invMoveTicks += 10;
                if (s.invMoveTicks >= 30) {
                    s.invMoveTicks = 0;
                    plugin.alerts().flag(p, prof, Check.INVMOVE, 1.0 * w, plugin.texts().raw("detail.inventory-move")
                            .replace("<menu>", top.name().toLowerCase(Locale.ROOT)));
                }
            } else {
                s.invMoveTicks = 0;
            }
        }
    }

    // ================================================================= AutoCriticals

    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !e.isCritical()) return;
        if (!plugin.settings().limits(Check.CRITICALS).enabled() || !plugin.analysed(p) || plugin.scripted(p)) return;
        Profile prof = plugin.profile(p);
        if (prof == null || prof.historySize() < 12 || p.isInsideVehicle() || p.isClimbing() || p.isInWater()) return;
        double w = plugin.lag().weight(prof);
        if (w < 0.6) return;
        // vrai coup critique = vraie chute après un saut ou une marche : la hauteur a bougé d'au moins ~0,15 bloc
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i <= 10; i++) {
            Vector v = prof.at(i);
            if (v == null) return;
            min = Math.min(min, v.getY());
            max = Math.max(max, v.getY());
        }
        if (max - min < 0.12) {
            plugin.alerts().flag(p, prof, Check.CRITICALS, 0.6 * w, plugin.texts().raw("detail.criticals")
                    .replace("<height>", String.format(Locale.US, "%.2f", max - min)));
        }
    }

    // ================================================================= Scaffold / AirPlace

    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!plugin.settings().limits(Check.SCAFFOLD).enabled() || !plugin.analysed(p) || plugin.scripted(p)) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        double w = plugin.lag().weight(prof);
        if (w < 0.6) return;
        Block placed = e.getBlockPlaced(), against = e.getBlockAgainst();
        Material m = placed.getType();
        // AirPlace : posé contre de l'air (impossible avec le jeu normal), sauf objets qui se posent sur l'eau / partout
        if (against.getType().isAir() && !plugin.isBedrock(p) && m != Material.LILY_PAD
                && m != Material.FROGSPAWN && m != Material.SCAFFOLDING && !against.equals(placed)) {
            e.setCancelled(true);
            plugin.alerts().flag(p, prof, Check.SCAFFOLD, 1.0 * w, plugin.texts().raw("detail.airplace")
                    .replace("<block>", m.name().toLowerCase(Locale.ROOT)));
            return;
        }
        // posé en regardant franchement ailleurs (le bloc d'appui est derrière lui)
        Location eye = p.getEyeLocation();
        Vector to = against.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(eye.toVector());
        if (to.lengthSquared() > 1.5) {
            double angle = Math.toDegrees(eye.getDirection().angle(to));
            if (angle > plugin.settings().num(Check.SCAFFOLD, "max-angle", 110)) {
                plugin.alerts().flag(p, prof, Check.SCAFFOLD, 0.5 * w, plugin.texts().raw("detail.scaffold-angle")
                        .replace("<angle>", String.valueOf(Math.round(angle))));
            }
        }
        // rythme impossible (sans compter les blocs posés à la main très vite sur place)
        State s = st(p);
        long now = System.currentTimeMillis();
        s.places.addLast(now);
        while (!s.places.isEmpty() && now - s.places.peekFirst() > 1000) s.places.pollFirst();
        int max = (int) plugin.settings().num(Check.SCAFFOLD, "max-per-second", 14);
        if (s.places.size() > max) {
            s.places.clear();
            plugin.alerts().flag(p, prof, Check.SCAFFOLD, 0.7 * w, plugin.texts().raw("detail.scaffold-rate")
                    .replace("<count>", String.valueOf(max + 1)));
        }
    }

    // ================================================================= BoatFly

    public void onVehicleMove(VehicleMoveEvent e) {
        if (!(e.getVehicle() instanceof Boat boat) || boat.getPassengers().isEmpty()) return;
        if (!(boat.getPassengers().get(0) instanceof Player p)) return;
        if (!plugin.settings().limits(Check.FLY).enabled() || !plugin.analysed(p) || plugin.scripted(p)) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        State s = st(p);
        Location to = e.getTo();
        boolean supported = false;
        for (double dx : new double[]{-0.7, 0, 0.7}) {
            for (double dz : new double[]{-0.7, 0, 0.7}) {
                Block b = to.clone().add(dx, -0.3, dz).getBlock();
                Material m = b.getType();
                if (!b.isPassable() || b.isLiquid() || m == Material.BUBBLE_COLUMN || m == Material.WATER) supported = true;
                if (to.clone().add(dx, 0.2, dz).getBlock().isLiquid()) supported = true;
            }
        }
        if (supported) {
            s.boatAir = s.boatRise = 0;
            return;
        }
        s.boatAir++;
        if (e.getTo().getY() - e.getFrom().getY() > 0.02) s.boatRise++;
        double w = plugin.lag().weight(prof);
        // un bateau qui tombe d'une falaise tombe ; il ne monte pas et ne flotte pas 2 secondes en l'air
        if ((s.boatRise >= 8 || s.boatAir >= 40) && w >= 0.6) {
            s.boatAir = s.boatRise = 0;
            plugin.alerts().flag(p, prof, Check.FLY, 1.0 * w, plugin.texts().raw("detail.boatfly"));
        }
    }
}
