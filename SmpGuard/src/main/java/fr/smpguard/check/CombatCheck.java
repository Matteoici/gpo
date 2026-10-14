package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Lag;
import fr.smpguard.Profile;
import fr.smpguard.Sight;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Combat :
 * - REACH : frappe un joueur / mob plus loin que la portée du jeu (3 blocs), la position de la cible étant
 *   prise sur tout l'intervalle "ce que le tricheur voyait" (ping + interpolation) : le lag ne compte jamais contre lui.
 * - KILLAURA : frappe une cible qu'il ne regarde pas (hors du champ de vision), à travers un mur,
 *   ou deux cibles dans des directions opposées dans le même tick.
 * - AUTOCLICKER : plus de X clics par seconde, tenus plusieurs secondes (sans miner).
 */
public final class CombatCheck {

    private static final class State {
        final Deque<Long> clicks = new ArrayDeque<>();
        int highSeconds;
        int lastHitTick = -1;
        Vector lastHitDir;
        UUID lastHitTarget;
    }

    private final SmpGuard plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public CombatCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    private State st(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    public void forget(Player p) {
        states.remove(p.getUniqueId());
    }

    // ================================================================= coups

    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!plugin.analysed(p) || p.isInsideVehicle()) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        double w = plugin.lag().weight(prof);
        if (w < 0.25) return;
        Entity t = e.getEntity();
        Vector eye = p.getEyeLocation().toVector();
        int delay = Lag.delayTicks(prof);

        // ---------------- portée : distance minimale entre ses yeux et la boîte de la cible, sur tout l'intervalle de lag
        double best = distance(eye, t.getBoundingBox());
        Profile tp = t instanceof Player tpl ? plugin.profile(tpl) : null;
        if (tp != null) {
            double wd = t.getWidth() / 2, h = t.getHeight();
            for (int k = 1; k <= delay + 2; k++) {
                Vector f = tp.at(k);
                if (f == null) break;
                best = Math.min(best, distance(eye, new BoundingBox(f.getX() - wd, f.getY(), f.getZ() - wd, f.getX() + wd, f.getY() + h, f.getZ() + wd)));
            }
        } else {
            double v = t.getVelocity().length() * (delay + 2);
            best = Math.max(0, best - v);
        }
        double range = 3.0;
        var attr = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (attr != null) range = attr.getValue();
        double margin = plugin.settings().num(Check.REACH, "margin", 0.4);
        double limit = range + margin;
        if (best > limit) {
            double over = best - limit;
            plugin.alerts().flag(p, prof, Check.REACH, Math.min(1.5, 0.4 + over / 0.3) * w,
                    plugin.texts().raw("detail.reach").replace("<distance>", fmt(best)).replace("<max>", fmt(limit)));
            if (best > range + plugin.settings().num(Check.REACH, "cancel-above", 1.2) && plugin.settings().flag(Check.REACH, "cancel", true)) {
                e.setCancelled(true);
            }
        }

        // ---------------- killaura : hors du champ de vision
        Vector aim = closest(eye, t.getBoundingBox());
        double angle = Sight.angle(p.getEyeLocation(), aim);
        double maxAngle = plugin.settings().num(Check.KILLAURA, "max-angle", 65);
        if (best > 1.2 && angle > maxAngle) {
            plugin.alerts().flag(p, prof, Check.KILLAURA, 0.6 * w, plugin.texts().raw("detail.aura-angle")
                    .replace("<target>", name(t)).replace("<angle>", String.valueOf(Math.round(angle))));
        }
        // ---------------- killaura : à travers un mur plein
        if (t instanceof LivingEntity le && !Sight.anyClear(p.getWorld(), new Vector[]{eye},
                Sight.body(le.getLocation().toVector(), le.getHeight()))) {
            plugin.alerts().flag(p, prof, Check.KILLAURA, 0.7 * w, plugin.texts().raw("detail.aura-wall").replace("<target>", name(t)));
        }
        // ---------------- killaura : deux cibles opposées dans le même tick
        State s = st(p);
        Vector dir = aim.clone().subtract(eye).setY(0);
        int now = plugin.currentTick();
        if (s.lastHitTick == now && s.lastHitTarget != null && !s.lastHitTarget.equals(t.getUniqueId()) && s.lastHitDir != null
                && dir.lengthSquared() > 0.01 && s.lastHitDir.lengthSquared() > 0.01) {
            double between = Math.toDegrees(dir.angle(s.lastHitDir));
            if (between > plugin.settings().num(Check.KILLAURA, "multi-angle", 70)) {
                plugin.alerts().flag(p, prof, Check.KILLAURA, 1.0 * w, plugin.texts().raw("detail.aura-multi")
                        .replace("<angle>", String.valueOf(Math.round(between))));
            }
        }
        s.lastHitTick = now;
        s.lastHitTarget = t.getUniqueId();
        s.lastHitDir = dir;
    }

    private static String name(Entity e) {
        return e instanceof Player p ? p.getName() : e.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static Vector closest(Vector p, BoundingBox b) {
        return new Vector(Math.max(b.getMinX(), Math.min(p.getX(), b.getMaxX())),
                Math.max(b.getMinY(), Math.min(p.getY(), b.getMaxY())),
                Math.max(b.getMinZ(), Math.min(p.getZ(), b.getMaxZ())));
    }

    private static double distance(Vector p, BoundingBox b) {
        return closest(p, b).distance(p);
    }

    // ================================================================= clics

    /** Un coup dans le vide ou sur une entité (pas en minant). */
    public void onSwing(Player p) {
        if (!plugin.analysed(p)) return;
        Block target = p.getTargetBlockExact(5);
        if (target != null && !target.getType().isAir()) return; // il mine : le bras bouge tout seul
        Deque<Long> c = st(p).clicks;
        long now = System.currentTimeMillis();
        c.addLast(now);
        while (!c.isEmpty() && now - c.peekFirst() > 1000) c.removeFirst();
    }

    /** Toutes les secondes. */
    public void tickSecond() {
        if (!plugin.settings().limits(Check.AUTOCLICKER).enabled()) return;
        long now = System.currentTimeMillis();
        int max = (int) plugin.settings().num(Check.AUTOCLICKER, "max-cps", 20);
        int seconds = (int) plugin.settings().num(Check.AUTOCLICKER, "seconds", 3);
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s == null) continue;
            while (!s.clicks.isEmpty() && now - s.clicks.peekFirst() > 1000) s.clicks.removeFirst();
            int cps = s.clicks.size();
            Profile prof = plugin.profile(p);
            if (prof == null) continue;
            double w = plugin.lag().weight(prof);
            if (cps > max && w > 0.4) s.highSeconds++;
            else s.highSeconds = 0;
            if (s.highSeconds >= seconds) {
                s.highSeconds = 0;
                plugin.alerts().flag(p, prof, Check.AUTOCLICKER, 1.0 * w, plugin.texts().raw("detail.autoclicker")
                        .replace("<cps>", String.valueOf(cps)).replace("<seconds>", String.valueOf(seconds)));
            }
        }
    }
}
