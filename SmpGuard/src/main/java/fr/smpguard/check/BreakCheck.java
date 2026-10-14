package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.Sight;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FASTBREAK / NUKER :
 * - un bloc cassé nettement plus vite que le temps calculé par le jeu (outil, enchantements, Hâte, eau, au sol...) ;
 * - trop de blocs "non instantanés" cassés en une seconde (nuker) ;
 * - un bloc cassé derrière lui (hors du champ de vision).
 */
public final class BreakCheck {

    private record Start(Block block, int tick) {
    }

    private static final class State {
        Start start;
        int brokenThisSecond, fastSeconds;
    }

    private final SmpGuard plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public BreakCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    private State st(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    public void forget(Player p) {
        states.remove(p.getUniqueId());
    }

    public void onDamage(BlockDamageEvent e) {
        if (e.getInstaBreak()) return;
        st(e.getPlayer()).start = new Start(e.getBlock(), plugin.currentTick());
    }

    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!plugin.settings().limits(Check.FASTBREAK).enabled() || !plugin.analysed(p)) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        Block b = e.getBlock();
        float speed = b.getBreakSpeed(p);
        // le jeu divise la vitesse par 5 en l'air et sous l'eau : on prend toujours le cas le plus rapide,
        // pour ne jamais accuser quelqu'un qui a sauté ou nagé pendant qu'il minait
        if (!p.isOnGround()) speed *= 5;
        if (p.isUnderWater()) speed *= 5;
        if (speed <= 0 || speed >= 1) return; // instantané : rien à mesurer
        State s = st(p);
        s.brokenThisSecond++;
        double w = plugin.lag().weight(prof);
        String name = b.getType().name().toLowerCase(Locale.ROOT);
        int expected = (int) Math.ceil(1 / speed);
        if (s.start != null && s.start.block().equals(b) && w > 0.3) {
            int elapsed = plugin.currentTick() - s.start.tick();
            double tolerance = 2 + prof.jitter() / 50.0;
            if (elapsed + tolerance < expected * plugin.settings().num(Check.FASTBREAK, "min-ratio", 0.7)) {
                plugin.alerts().flag(p, prof, Check.FASTBREAK, 0.5 * w, plugin.texts().raw("detail.fastbreak")
                        .replace("<block>", name).replace("<ticks>", String.valueOf(elapsed)).replace("<expected>", String.valueOf(expected)));
                if (plugin.settings().flag(Check.FASTBREAK, "cancel", true)) e.setCancelled(true);
            }
        }
        s.start = null;
        double angle = Sight.angle(p.getEyeLocation(), b.getLocation().toCenterLocation().toVector());
        if (angle > plugin.settings().num(Check.FASTBREAK, "max-angle", 90) && w > 0.3) {
            plugin.alerts().flag(p, prof, Check.FASTBREAK, 0.5 * w, plugin.texts().raw("detail.nuker-angle")
                    .replace("<block>", name).replace("<angle>", String.valueOf(Math.round(angle))));
            if (plugin.settings().flag(Check.FASTBREAK, "cancel", true)) e.setCancelled(true);
        }
    }

    public void tickSecond() {
        int max = (int) plugin.settings().num(Check.FASTBREAK, "max-per-second", 9);
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s == null) continue;
            Profile prof = plugin.profile(p);
            if (prof != null && s.brokenThisSecond > max) {
                double w = plugin.lag().weight(prof);
                if (w > 0.3) plugin.alerts().flag(p, prof, Check.FASTBREAK, 1.0 * w, plugin.texts().raw("detail.nuker-rate")
                        .replace("<count>", String.valueOf(s.brokenThisSecond)));
            }
            s.brokenThisSecond = 0;
        }
    }
}
